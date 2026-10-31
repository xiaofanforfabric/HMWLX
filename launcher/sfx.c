/*
 * HMWLX 自解压启动器 / HMWLX self-extracting launcher
 *
 * 单文件 exe：内部嵌入 LZMA 压缩的 JRE + fat jar。
 * 首次运行时解压到用户缓存目录，之后直接复用（带内容哈希校验）。
 *
 * 为什么不用 7zSD.sfx：
 *   官方 SFX 的行为是「解压到临时目录 -> 运行程序 -> 删除临时目录」。
 *   我们的启动器是 CreateProcess 后立即返回（不等游戏退出），
 *   临时目录会被 SFX 立刻删掉，JVM 还没加载完就崩了。而且每次
 *   都重新解压 300M，太慢。
 *
 * 打包格式（pe 文件末尾追加）：
 *
 *   [PE 映像]
 *   [块0 数据][块1 数据] ...
 *   [索引：每块 { offset(u64), packed(u64), original(u64), props[5], nameLen(u8), name[] }]
 *   [TRAILER: magic[8] + indexOffset(u64) + indexSize(u64) + entryCount(u32)]
 *
 * 索引放在末尾，启动器只需读最后 32 字节就能定位全部内容。
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdarg.h>

#ifdef _WIN32
/* 进度条控件要求 Windows 2000+ 的 comctl32，声明版本避免被当成 IE3 */
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#ifndef _WIN32_IE
#define _WIN32_IE 0x0600
#endif
#include <windows.h>
#include <commctrl.h>   /* PROGRESS_CLASSW / PBM_SETPOS，进度条用 */
#else
#include <unistd.h>
#include <sys/wait.h>
#include <sys/stat.h>
#include <errno.h>
#endif

#include "third_party/lzma/LzmaDec.h"

#define JAR_NAME   "hmwlx.jar"
#define APP_NAME   "HMWLX"
#define MAIN_CLASS "org.cn.xiaofan.MainKt"
#define CACHE_DIR  "HMWLX"
#define CACHE_TAG  "app"          /* 解压目标子目录名 */
#define MARK_FILE  ".extracted"   /* 完成标记，避免解压到一半就启动 */

/* 文件末尾的魔数，用于识别「这个 exe 是不是自解压包」 */
static const char TRAILER_MAGIC[8] = { 'H','M','W','L','X','S','F','X' };
#define TRAILER_SIZE (8 + 8 + 8 + 4)   /* magic + indexOff + indexSize + count */

/* ------------------------------------------------------------------ 结构 */

typedef struct {
    uint64_t offset;      /* 压缩数据在 exe 中的绝对偏移 */
    uint64_t packedSize;
    uint64_t origSize;
    unsigned char props[LZMA_PROPS_SIZE];
    char name[128];       /* 相对路径（正斜杠） */
} SFXEntry;

typedef struct {
    SFXEntry *entries;
    uint32_t count;
    char selfPath[2048];
    char cacheRoot[2048];   /* 解压根目录 */
    char cacheTag[64];      /* 内容哈希，用于版本隔离 */
} SFXIndex;

/* ------------------------------------------------------------ 诊断输出 */

/* 全局状态：日志句柄和详细模式开关，多处用到，集中定义在最前 */
static FILE *g_logFile = NULL;
static int   g_verbose = 0;

/* 路径工具定义在下方，但日志函数要先用到，这里前置声明 */
static int  get_cache_root(char *out, size_t cap);
static int  mkdirs(const char *path);
static void path_join(char *out, size_t cap, const char *a, const char *b);

/*
 * 首次运行解压 300MB 要 8 秒左右，这期间如果不给任何反馈，
 * 用户会以为双击没反应，然后反复双击。
 *
 * Windows GUI 子系统没有控制台，所以这里弹一个带进度条的小窗口；
 * 解压完成后自动关闭。Linux 直接打到 stderr。
 *
 * 窗口用「边解压边泵消息」的方式实现，不需要开线程：
 * extract_all() 每处理一个文件就调用 progress_tick()，
 * 里面用 PeekMessage 处理重绘和关闭请求，并重画进度条。
 */
#ifdef _WIN32

typedef struct {
    HWND hwnd;
    HWND hbar;
    HWND htext;
    int total;
    int done;
    int cancelled;
} ProgressUI;

static ProgressUI g_ui;

static LRESULT CALLBACK progress_wndproc(HWND h, UINT m, WPARAM w, LPARAM l)
{
    switch (m) {
    case WM_CLOSE:
        /* 用户点叉：标记取消，extract_all 下一次检查时中止 */
        g_ui.cancelled = 1;
        return 0;
    case WM_DESTROY:
        return 0;
    }
    return DefWindowProcW(h, m, w, l);
}

static void progress_open(int total)
{
    memset(&g_ui, 0, sizeof(g_ui));
    g_ui.total = total;

    HINSTANCE hi = GetModuleHandleW(NULL);

    /* 进度条属于 comctl32 的通用控件，必须先初始化才能创建；
     * 不调的话 CreateWindowExW(PROGRESS_CLASSW) 会返回 NULL，
     * 窗口里就没有进度条。 */
    INITCOMMONCONTROLSEX icc;
    icc.dwSize = sizeof(icc);
    icc.dwICC = ICC_PROGRESS_CLASS;
    InitCommonControlsEx(&icc);

    WNDCLASSEXW wc;
    ZeroMemory(&wc, sizeof(wc));
    wc.cbSize = sizeof(wc);
    wc.lpfnWndProc = progress_wndproc;
    wc.hInstance = hi;
    wc.hCursor = LoadCursorW(NULL, (LPCWSTR)MAKEINTRESOURCEW(32512));  /* IDC_ARROW */
    wc.hbrBackground = (HBRUSH)(COLOR_WINDOW + 1);
    wc.lpszClassName = L"HMWLXProgress";
    RegisterClassExW(&wc);

    /* 居中于屏幕 */
    int w0 = 420, h0 = 150;
    int sw = GetSystemMetrics(SM_CXSCREEN), sh = GetSystemMetrics(SM_CYSCREEN);
    int x = (sw - w0) / 2, y = (sh - h0) / 2;

    g_ui.hwnd = CreateWindowExW(
        WS_EX_TOPMOST | WS_EX_APPWINDOW, L"HMWLXProgress",
        L"" APP_NAME L" 首次启动", WS_POPUP | WS_CAPTION | WS_SYSMENU,
        x, y, w0, h0, NULL, NULL, hi, NULL);

    /* 默认光标/字体即可：zig 的 mingw 头文件把 GetStockObject 标成了
     * dllimport 但没提供对应导入库，索性不设字体，系统会用默认的，
     * 在中文 Windows 上一样能正常显示中文。 */

    g_ui.htext = CreateWindowExW(0, L"STATIC", L"正在解压运行环境，请稍候…",
                                 WS_CHILD | WS_VISIBLE,
                                 20, 20, w0 - 60, 24, g_ui.hwnd, NULL, hi, NULL);

    g_ui.hbar = CreateWindowExW(0, PROGRESS_CLASSW, NULL,
                                WS_CHILD | WS_VISIBLE | PBS_SMOOTH,
                                20, 55, w0 - 60, 22, g_ui.hwnd, NULL, hi, NULL);
    SendMessageW(g_ui.hbar, PBM_SETRANGE32, 0, total > 0 ? total : 1);

    ShowWindow(g_ui.hwnd, SW_SHOW);
    UpdateWindow(g_ui.hwnd);
}

/* 泵一次消息循环，让窗口能重绘、能响应关闭 */
static void progress_pump(void)
{
    MSG msg;
    while (PeekMessageW(&msg, NULL, 0, 0, PM_REMOVE)) {
        TranslateMessage(&msg);
        DispatchMessageW(&msg);
    }
}

static void progress_tick(int done)
{
    if (!g_ui.hwnd) return;
    g_ui.done = done;
    SendMessageW(g_ui.hbar, PBM_SETPOS, (WPARAM)done, 0);
    progress_pump();
}

static void progress_close(void)
{
    if (g_ui.hwnd) {
        DestroyWindow(g_ui.hwnd);
        g_ui.hwnd = NULL;
    }
    UnregisterClassW(L"HMWLXProgress", GetModuleHandleW(NULL));
}

#else   /* ---------------- Linux ---------------- */

static void progress_open(int total)  { (void)total; }
static void progress_tick(int done)
{
    if (g_verbose && done % 32 == 0) fprintf(stderr, "  已解压 %d 个文件\n", done);
}
static void progress_close(void)      { }

#endif

/*
 * 日志。
 *
 * GUI 子系统的 exe 没有控制台：stdout/stderr 句柄是无效的，
 * fprintf 写出去的东西直接消失，用户和我们都看不到。
 *
 * 所以日志一律写文件：%LOCALAPPDATA%\HMWLX\launcher.log
 * （Linux 是 $XDG_CACHE_HOME/HMWLX/launcher.log）。
 *
 * 每次启动覆盖写，只保留本次运行的过程 —— 这是个启动器，
 * 排查问题是「刚才那次为什么没起来」，不需要历史累积。
 * 追加模式会让文件无限长大，反而不好发给别人。
 */
static void log_open(void)
{
    char root[2048], path[2048];
    if (get_cache_root(root, sizeof(root)) != 0) return;
    mkdirs(root);
    path_join(path, sizeof(path), root, "launcher.log");

#ifdef _WIN32
    wchar_t w[2048];
    if (MultiByteToWideChar(CP_UTF8, 0, path, -1, w, 2048) <= 0) return;
    g_logFile = _wfopen(w, L"w");
#else
    g_logFile = fopen(path, "w");
#endif
    if (!g_logFile) return;

    /* 记一行启动信息，方便对照版本和路径 */
    fprintf(g_logFile, APP_NAME " 启动器日志\n");
    fprintf(g_logFile, "日志文件 : %s\n", path);
    fflush(g_logFile);
}

static void log_close(void)
{
    if (g_logFile) {
        fclose(g_logFile);
        g_logFile = NULL;
    }
}

static void logmsg(const char *fmt, ...)
{
    va_list ap;

    /* 始终写文件：即使没有 --verbose，出错时的关键信息也要留下 */
    if (g_logFile) {
        va_start(ap, fmt);
        vfprintf(g_logFile, fmt, ap);
        va_end(ap);
        fflush(g_logFile);
    }

    /* 有控制台时（开发调试 / 从终端启动）同时打到 stderr */
    if (!g_verbose) return;
#ifdef _WIN32
    HANDLE h = GetStdHandle(STD_ERROR_HANDLE);
    if (!h || h == INVALID_HANDLE_VALUE) return;
#endif
    va_start(ap, fmt);
    vfprintf(stderr, fmt, ap);
    va_end(ap);
}

static void show_error(const char *msg)
{
#ifdef _WIN32
    /* 路径和错误信息里可能有中文，必须走 UTF-8 -> UTF-16 */
    wchar_t *wbuf = (wchar_t *)calloc(8192, sizeof(wchar_t));
    if (wbuf) {
        if (MultiByteToWideChar(CP_UTF8, 0, msg, -1, wbuf, 8192) <= 0) {
            MultiByteToWideChar(CP_ACP, 0, msg, -1, wbuf, 8192);
        }
        MessageBoxW(NULL, wbuf, L"" APP_NAME L" 启动失败", MB_ICONERROR | MB_OK);
        free(wbuf);
    }
#else
    fprintf(stderr, "[" APP_NAME "] %s\n", msg);
#endif
}

/* ------------------------------------------------------------ 路径工具 */

static int get_self_path(char *out, size_t cap)
{
#ifdef _WIN32
    wchar_t wpath[2048];
    DWORD n = GetModuleFileNameW(NULL, wpath, 2048);
    if (n == 0 || n >= 2048) return -1;
    return WideCharToMultiByte(CP_UTF8, 0, wpath, -1, out, (int)cap, NULL, NULL) > 0 ? 0 : -1;
#else
    ssize_t n = readlink("/proc/self/exe", out, cap - 1);
    if (n <= 0) return -1;
    out[n] = '\0';
    return 0;
#endif
}

static void path_dirname(char *path)
{
    for (size_t i = strlen(path); i > 0; i--) {
        if (path[i - 1] == '\\' || path[i - 1] == '/') {
            path[i - 1] = '\0';
            return;
        }
    }
}

static void path_join(char *out, size_t cap, const char *a, const char *b)
{
#ifdef _WIN32
    snprintf(out, cap, "%s\\%s", a, b);
#else
    snprintf(out, cap, "%s/%s", a, b);
#endif
}

/* 递归建目录（等价 mkdir -p） */
static int mkdirs(const char *path)
{
#ifdef _WIN32
    wchar_t w[2048];
    if (MultiByteToWideChar(CP_UTF8, 0, path, -1, w, 2048) <= 0) return -1;
    for (wchar_t *p = w + 1; *p; p++) {
        if (*p == L'\\' || *p == L'/') {
            wchar_t save = *p;
            *p = L'\0';
            CreateDirectoryW(w, NULL);
            *p = save;
        }
    }
    CreateDirectoryW(w, NULL);
    DWORD a = GetFileAttributesW(w);
    return (a != INVALID_FILE_ATTRIBUTES && (a & FILE_ATTRIBUTE_DIRECTORY)) ? 0 : -1;
#else
    char tmp[2048];
    snprintf(tmp, sizeof(tmp), "%s", path);
    for (char *p = tmp + 1; *p; p++) {
        if (*p == '/') {
            *p = '\0';
            mkdir(tmp, 0755);
            *p = '/';
        }
    }
    if (mkdir(tmp, 0755) != 0 && errno != EEXIST) return -1;
    return 0;
#endif
}

static int file_exists(const char *path)
{
#ifdef _WIN32
    wchar_t w[2048];
    if (MultiByteToWideChar(CP_UTF8, 0, path, -1, w, 2048) <= 0) return 0;
    return GetFileAttributesW(w) != INVALID_FILE_ATTRIBUTES;
#else
    struct stat st;
    return stat(path, &st) == 0;
#endif
}

/* 读取缓存根目录：%LOCALAPPDATA%\HMWLX（Windows）或 ~/.cache/HMWLX */
static int get_cache_root(char *out, size_t cap)
{
#ifdef _WIN32
    wchar_t w[1024];
    DWORD n = GetEnvironmentVariableW(L"LOCALAPPDATA", w, 1024);
    if (n == 0 || n >= 1024) {
        n = GetEnvironmentVariableW(L"APPDATA", w, 1024);
        if (n == 0 || n >= 1024) {
            n = GetEnvironmentVariableW(L"TEMP", w, 1024);
            if (n == 0) return -1;
        }
    }
    char base[1024];
    if (WideCharToMultiByte(CP_UTF8, 0, w, -1, base, sizeof(base), NULL, NULL) <= 0) return -1;
    path_join(out, cap, base, CACHE_DIR);
    return 0;
#else
    const char *xdg = getenv("XDG_CACHE_HOME");
    if (xdg && *xdg) {
        path_join(out, cap, xdg, CACHE_DIR);
    } else {
        const char *home = getenv("HOME");
        if (!home) return -1;
        char base[1024];
        snprintf(base, sizeof(base), "%s/.cache", home);
        path_join(out, cap, base, CACHE_DIR);
    }
    return 0;
#endif
}

/* ------------------------------------------------------------ 文件读取 */

/* 打开自身，把 [offset, offset+size) 读进新分配的缓冲 */
static unsigned char *read_self_range(const char *selfPath, uint64_t offset, uint64_t size)
{
    if (size == 0 || size > (uint64_t)1 << 34) return NULL;  /* 上限 16G，防呆 */
#ifdef _WIN32
    wchar_t w[2048];
    if (MultiByteToWideChar(CP_UTF8, 0, selfPath, -1, w, 2048) <= 0) return NULL;
    HANDLE f = CreateFileW(w, GENERIC_READ, FILE_SHARE_READ, NULL,
                           OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return NULL;
    LARGE_INTEGER li;
    li.QuadPart = (LONGLONG)offset;
    if (!SetFilePointerEx(f, li, NULL, FILE_BEGIN)) { CloseHandle(f); return NULL; }
    unsigned char *buf = (unsigned char *)malloc((size_t)size);
    if (!buf) { CloseHandle(f); return NULL; }
    DWORD done = 0;
    size_t total = 0;
    while (total < (size_t)size) {
        DWORD chunk = (DWORD)((size - total > 0x10000000u) ? 0x10000000u : (size - total));
        if (!ReadFile(f, buf + total, chunk, &done, NULL) || done == 0) break;
        total += done;
    }
    CloseHandle(f);
    if (total != (size_t)size) { free(buf); return NULL; }
    return buf;
#else
    FILE *f = fopen(selfPath, "rb");
    if (!f) return NULL;
    if (fseeko(f, (off_t)offset, SEEK_SET) != 0) { fclose(f); return NULL; }
    unsigned char *buf = (unsigned char *)malloc((size_t)size);
    if (!buf) { fclose(f); return NULL; }
    size_t got = fread(buf, 1, (size_t)size, f);
    fclose(f);
    if (got != (size_t)size) { free(buf); return NULL; }
    return buf;
#endif
}

/* -------------------------------------------------------- 构建索引 */
/*
 * 读 exe 末尾 32 字节 -> 校验魔数 -> 读索引区 -> 解析成 SFXEntry 数组。
 * 如果魔数不匹配，说明这是「老式」启动器（没内嵌数据），
 * 返回特殊码 1，主流程会退回「读相邻目录」的兼容模式。
 */
static int load_index(SFXIndex *idx)
{
    if (get_self_path(idx->selfPath, sizeof(idx->selfPath)) != 0) return -1;

    /* 取文件大小 */
    uint64_t fileSize = 0;
#ifdef _WIN32
    wchar_t w[2048];
    if (MultiByteToWideChar(CP_UTF8, 0, idx->selfPath, -1, w, 2048) <= 0) return -1;
    WIN32_FILE_ATTRIBUTE_DATA fad;
    if (!GetFileAttributesExW(w, GetFileExInfoStandard, &fad)) return -1;
    fileSize = ((uint64_t)fad.nFileSizeHigh << 32) | fad.nFileSizeLow;
#else
    struct stat st;
    if (stat(idx->selfPath, &st) != 0) return -1;
    fileSize = (uint64_t)st.st_size;
#endif
    if (fileSize < TRAILER_SIZE) return 1;

    unsigned char *tail = read_self_range(idx->selfPath, fileSize - TRAILER_SIZE, TRAILER_SIZE);
    if (!tail) return -1;

    if (memcmp(tail, TRAILER_MAGIC, 8) != 0) {
        free(tail);
        return 1;   /* 不是自解压包 */
    }

    uint64_t indexOff, indexSize;
    uint32_t count;
    memcpy(&indexOff, tail + 8, 8);
    memcpy(&indexSize, tail + 16, 8);
    memcpy(&count, tail + 24, 4);
    free(tail);

    if (count == 0 || count > 65536) return -1;

    unsigned char *raw = read_self_range(idx->selfPath, indexOff, indexSize);
    if (!raw) return -1;

    idx->entries = (SFXEntry *)calloc(count, sizeof(SFXEntry));
    if (!idx->entries) { free(raw); return -1; }
    idx->count = count;

    /* 解析：定长字段 + 变长名字，顺序读取 */
    unsigned char *p = raw;
    unsigned char *end = raw + indexSize;
    for (uint32_t i = 0; i < count; i++) {
        SFXEntry *e = &idx->entries[i];
        if (p + 8 + 8 + 8 + LZMA_PROPS_SIZE + 1 > end) goto bad;
        memcpy(&e->offset, p, 8); p += 8;
        memcpy(&e->packedSize, p, 8); p += 8;
        memcpy(&e->origSize, p, 8); p += 8;
        memcpy(e->props, p, LZMA_PROPS_SIZE); p += LZMA_PROPS_SIZE;
        unsigned nameLen = *p++;
        if (nameLen >= sizeof(e->name) || p + nameLen > end) goto bad;
        memcpy(e->name, p, nameLen);
        e->name[nameLen] = '\0';
        p += nameLen;
    }
    free(raw);

    /*
     * 缓存目录名用「索引区大小 + 条目数」做指纹，而不是真正的哈希。
     * 理由：这两个值任何内容变化都会导致不同，且不用在 C 里引额外算法。
     * 万一碰撞，顶多是复用了旧版本目录，MARK_FILE 里还存了同样的指纹。
     */
    snprintf(idx->cacheTag, sizeof(idx->cacheTag), "%08x%08x",
             (unsigned)indexSize, (unsigned)count);
    return 0;

bad:
    free(raw);
    free(idx->entries);
    idx->entries = NULL;
    idx->count = 0;
    return -1;
}

/* ------------------------------------------------------------ 解压核心 */

/*
 * LzmaDecode 需要一个显存的 allocator（它要分配约 16MB 的概率表）。
 * 传 NULL 会让 SDK 解引用空指针直接段错误，所以这里给个最简实现：
 * 直接转发到 malloc/free。
 */
static void *sz_alloc(ISzAllocPtr p, size_t size)
{
    (void)p;
    return malloc(size);
}

static void sz_free(ISzAllocPtr p, void *address)
{
    (void)p;
    free(address);
}

static const ISzAlloc g_alloc = { sz_alloc, sz_free };

/*
 * 解压一个条目的内容到内存。LZMA 的 props（5 字节）在打包时由
 * lzma_alone 风格的自定义打包器写入，解压时用 LzmaProps_Decode 还原。
 */
static unsigned char *inflate_entry(const SFXEntry *e, const char *selfPath)
{
    unsigned char *packed = read_self_range(selfPath, e->offset, e->packedSize);
    if (!packed) return NULL;

    /*
     * 关键：LzmaDecode 把 dest 直接当作字典缓冲区（p.dic = dest），
     * 解码过程中会回看最多 dictSize 字节的历史。如果 dest 比 dictSize 小，
     * 回看就会越界读，最终返回 SZ_ERROR_DATA。
     *
     * 所以缓冲必须按 max(实际大小, dictSize) 分配。
     * dictSize 存在 props[1..4]（小端）。
     */
    uint32_t dictSize = (uint32_t)e->props[1]
                      | ((uint32_t)e->props[2] << 8)
                      | ((uint32_t)e->props[3] << 16)
                      | ((uint32_t)e->props[4] << 24);
    uint64_t bufSize = e->origSize > dictSize ? e->origSize : dictSize;
    if (bufSize > (uint64_t)1 << 32) { free(packed); return NULL; }

    unsigned char *out = (unsigned char *)malloc((size_t)bufSize);
    if (!out) { free(packed); return NULL; }

    /*
     * LzmaDecode 要求输出缓冲带 LZMA_BUF_ERROR 的余量检查，
     * 这里传精确大小 + LZMA_FINISH_END，能效验「数据完整性」：
     * 解压不完整会返回 SZ_ERROR_INPUT_EOF。
     */
    SizeT srcLen = (SizeT)e->packedSize;
    SizeT dstLen = (SizeT)e->origSize;
    ELzmaStatus status;
    SRes res = LzmaDecode(out, &dstLen, packed, &srcLen,
                          e->props, LZMA_PROPS_SIZE,
                          LZMA_FINISH_END, &status, &g_alloc);
    free(packed);

    if (res != SZ_OK || dstLen != (SizeT)e->origSize) {
        logmsg("LZMA 解压失败 %s: res=%d dstLen=%lu/%lu\n",
               e->name, (int)res,
               (unsigned long)dstLen, (unsigned long)e->origSize);
        free(out);
        return NULL;
    }
    return out;
}

/* 把内存写进文件 */
static int write_file(const char *path, const unsigned char *data, uint64_t size)
{
#ifdef _WIN32
    wchar_t w[2048];
    if (MultiByteToWideChar(CP_UTF8, 0, path, -1, w, 2048) <= 0) return -1;
    HANDLE f = CreateFileW(w, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
                           FILE_ATTRIBUTE_NORMAL, NULL);
    if (f == INVALID_HANDLE_VALUE) return -1;
    size_t total = 0;
    while (total < (size_t)size) {
        DWORD done = 0;
        DWORD chunk = (DWORD)((size - total > 0x10000000u) ? 0x10000000u : (size - total));
        if (!WriteFile(f, data + total, chunk, &done, NULL) || done == 0) break;
        total += done;
    }
    CloseHandle(f);
    return total == (size_t)size ? 0 : -1;
#else
    FILE *f = fopen(path, "wb");
    if (!f) return -1;
    if (size == 0 || !data) {          /* 空文件：建出来就行 */
        fclose(f);
        return 0;
    }
    size_t w = fwrite(data, 1, (size_t)size, f);
    fclose(f);
    return w == (size_t)size ? 0 : -1;
#endif
}

/* 把相对路径（正斜杠）转成当前平台分隔符并建父目录 */
static int prepare_target(const char *root, const char *rel, char *out, size_t cap)
{
    char tmp[2048];
    snprintf(tmp, sizeof(tmp), "%s/%s", root, rel);
#ifndef _WIN32
    snprintf(out, cap, "%s", tmp);
#else
    for (char *p = tmp; *p; p++) if (*p == '/') *p = '\\';
    snprintf(out, cap, "%s", tmp);
#endif
    /* 建父目录 */
    char dir[2048];
    snprintf(dir, sizeof(dir), "%s", out);
    path_dirname(dir);
    return mkdirs(dir);
}

static int is_marked_extracted(const char *root, const char *tag)
{
    char mark[2048];
    path_join(mark, sizeof(mark), root, MARK_FILE);
    if (!file_exists(mark)) return 0;
    FILE *f = fopen(mark, "rb");
    if (!f) return 0;
    char buf[128] = {0};
    size_t n = fread(buf, 1, sizeof(buf) - 1, f);
    fclose(f);
    buf[n] = '\0';
    return strcmp(buf, tag) == 0;
}

static int write_mark(const char *root, const char *tag)
{
    char mark[2048];
    path_join(mark, sizeof(mark), root, MARK_FILE);
    return write_file(mark, (const unsigned char *)tag, strlen(tag));
}

/* 解压全部条目到 root，逐条报告进度 */
static int extract_all(const SFXIndex *idx, const char *root)
{
    int done = 0;
    for (uint32_t i = 0; i < idx->count; i++) {
        const SFXEntry *e = &idx->entries[i];

#ifdef _WIN32
        if (g_ui.cancelled) return -4;
#endif

        /* 目录条目（名字以 / 结尾）只建目录 */
        size_t nl = strlen(e->name);
        if (nl > 0 && e->name[nl - 1] == '/') {
            char d[2048];
            snprintf(d, sizeof(d), "%s/%s", root, e->name);
            d[strlen(d) - 1] = '\0';
#ifdef _WIN32
            for (char *p = d; *p; p++) if (*p == '/') *p = '\\';
#endif
            mkdirs(d);
            continue;
        }

        char target[2048];
        if (prepare_target(root, e->name, target, sizeof(target)) != 0) {
            logmsg("mkdir 失败: %s\n", e->name);
            return -1;
        }

        /* 空文件：LZMA 对 0 字节输入的输出无意义，直接建空文件跳过解码 */
        if (e->origSize == 0) {
            if (write_file(target, NULL, 0) != 0) {
                logmsg("写文件失败: %s\n", e->name);
                return -3;
            }
            progress_tick(++done);
            continue;
        }

        unsigned char *data = inflate_entry(e, idx->selfPath);
        if (!data) {
            logmsg("解压失败: %s\n", e->name);
            return -2;
        }

        int rc = write_file(target, data, e->origSize);
        free(data);
        if (rc != 0) {
            logmsg("写文件失败: %s\n", e->name);
            return -3;
        }
        progress_tick(++done);
    }
    return 0;
}

/* ------------------------------------------------------------ 启动 JVM */

static const char *JVM_ARGS[] = {
    "-XX:+UseSerialGC",
    "-XX:TieredStopAtLevel=1",
    "-Xshare:auto",
    "-Dfile.encoding=UTF-8",
    "-Dsun.jnu.encoding=UTF-8",
    "-Djava.awt.headless=false",
    "-Dskiko.renderApi=SOFTWARE",
    "--add-opens", "java.base/java.lang=ALL-UNNAMED",
    "--add-opens", "java.base/java.nio=ALL-UNNAMED",
    "--add-opens", "java.base/java.util=ALL-UNNAMED",
    "--add-opens", "java.base/java.lang.reflect=ALL-UNNAMED",
    "--add-opens", "java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.awt=ALL-UNNAMED",
    "--add-opens", "java.desktop/sun.java2d=ALL-UNNAMED",
    "--enable-native-access=ALL-UNNAMED",
    NULL
};
#define JVM_ARG_COUNT ((int)(sizeof(JVM_ARGS) / sizeof(JVM_ARGS[0]) - 1))

static int launch_jvm(const char *root, int argc, char **argv)
{
    char javaPath[2048], jarPath[2048];

#ifdef _WIN32
    path_join(javaPath, sizeof(javaPath), root, "runtime\\bin\\javaw.exe");
    path_join(jarPath, sizeof(jarPath), root, JAR_NAME);
#else
    path_join(javaPath, sizeof(javaPath), root, "runtime/bin/java");
    path_join(jarPath, sizeof(jarPath), root, JAR_NAME);
#endif

    if (!file_exists(javaPath)) {
        char msg[2600];
        snprintf(msg, sizeof(msg), "解压后找不到 Java 运行时：\n%s", javaPath);
        logmsg("错误：java 不存在 %s\n", javaPath);
        show_error(msg);
        return 2;
    }
    if (!file_exists(jarPath)) {
        char msg[2600];
        snprintf(msg, sizeof(msg), "解压后找不到主文件：\n%s", jarPath);
        logmsg("错误：jar 不存在 %s\n", jarPath);
        show_error(msg);
        return 3;
    }

    logmsg("JAVA  : %s\n", javaPath);
    logmsg("JAR   : %s\n", jarPath);

    int total = 1 + JVM_ARG_COUNT + 2 + (argc - 1) + 1;
    char **args = (char **)calloc((size_t)total, sizeof(char *));
    if (!args) { show_error("内存不足。"); return 4; }

    int k = 0;
    args[k++] = javaPath;
    for (int i = 0; i < JVM_ARG_COUNT; i++) args[k++] = (char *)JVM_ARGS[i];
    args[k++] = "-jar";
    args[k++] = jarPath;
    for (int i = 1; i < argc; i++) args[k++] = argv[i];
    args[k] = NULL;

#ifdef _WIN32
    /*
     * 拼命令行。必须严格按 MS C 运行库（CommandLineToArgvW）的反解析规则，
     * 否则参数会被拆错或路径里的反斜杠被吃掉。
     *
     * 规则（只对「带引号的参数」生效）：
     *   - 连续的 N 个反斜杠后面跟一个引号  ->  输出 2N 个反斜杠 + 一个引号
     *   - 连续的 N 个反斜杠后面跟普通字符  ->  原样输出 N 个反斜杠
     *   - 连续的 N 个反斜杠在参数结尾      ->  原样输出 N 个反斜杠
     *
     * 这里踩过一个坑：早先的代码无脑给每个 '\\' 都再补一个 '\\'，
     * 结果 C:\Users\... 变成了 C:\\Users\\...，JVM 直接找不到 jar
     * （日志里那行「命令行: C:\\Users\\...」就是这么来的）。
     * 反斜杠只有在「紧邻引号」时才需要翻倍。
     */
    size_t cap = 65536;
    char *cmd = (char *)calloc(cap, 1);
    if (!cmd) { free(args); show_error("内存不足。"); return 4; }
    size_t used = 0;

    for (int i = 0; args[i]; i++) {
        const char *a = args[i];
        if (i) cmd[used++] = ' ';

        int need_quote = (*a == '\0' || strpbrk(a, " \t") != NULL);
        if (need_quote) cmd[used++] = '"';

        const char *p = a;
        while (*p) {
            /* 数一下当前这段连续反斜杠 */
            size_t nbs = 0;
            while (*p == '\\') { nbs++; p++; }

            if (*p == '\0') {
                /* 反斜杠在参数结尾：
                 * 带引号时要翻倍（否则会把收尾的引号转义掉），不带则原样 */
                size_t emit = need_quote ? nbs * 2 : nbs;
                for (size_t k = 0; k < emit; k++) cmd[used++] = '\\';
            } else if (*p == '"') {
                /* 反斜杠后跟引号：反斜杠翻倍，引号转义 */
                for (size_t k = 0; k < nbs * 2 + 1; k++) cmd[used++] = '\\';
                cmd[used++] = '"';
                p++;
            } else {
                /* 普通字符：反斜杠原样 */
                for (size_t k = 0; k < nbs; k++) cmd[used++] = '\\';
                cmd[used++] = *p++;
            }
        }

        if (need_quote) cmd[used++] = '"';
    }
    cmd[used] = '\0';

    wchar_t *wcmd = (wchar_t *)calloc(65536, sizeof(wchar_t));
    wchar_t wdir[2048];
    if (!wcmd || MultiByteToWideChar(CP_UTF8, 0, cmd, -1, wcmd, 65536) <= 0) {
        free(cmd); free(wcmd); free(args);
        show_error("命令行编码转换失败。");
        return 5;
    }
    MultiByteToWideChar(CP_UTF8, 0, root, -1, wdir, 2048);

    /* 这行是最有用的排查信息：用户说「起不来」时，先看命令行对不对 */
    logmsg("工作目录: %s\n", root);
    logmsg("命令行: %s\n", cmd);

    free(cmd);

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    if (!CreateProcessW(NULL, wcmd, NULL, NULL, FALSE, CREATE_UNICODE_ENVIRONMENT,
                        NULL, wdir, &si, &pi)) {
        char msg[2600];
        snprintf(msg, sizeof(msg), "启动 JVM 失败（错误码 %lu）。", (unsigned long)GetLastError());
        logmsg("错误：CreateProcessW 失败，GetLastError=%lu\n",
               (unsigned long)GetLastError());
        show_error(msg);
        free(wcmd); free(args);
        return 6;
    }
    free(wcmd);
    free(args);

    /*
     * CreateProcessW 成功只代表进程被创建出来了，不代表 JVM 起来了。
     * javaw.exe 如果找不到 jar 或参数有问题，会在几百毫秒内自己退出，
     * 而启动器早就返回了 —— 用户看到的就是「双击没反应」。
     *
     * 所以这里等最多 3 秒：
     *   - 进程还活着  -> 正常，说明 JVM 在跑（GUI 起来要几秒）
     *   - 进程已退出  -> 立刻把退出码弹出来，别让人瞎猜
     *
     * 3 秒后无论死活都直接关闭句柄返回，不拖住用户。
     */
    DWORD wr = WaitForSingleObject(pi.hProcess, 3000);
    if (wr == WAIT_OBJECT_0) {
        DWORD code = 0;
        GetExitCodeProcess(pi.hProcess, &code);
        char msg[2600];
        snprintf(msg, sizeof(msg),
                 "Java 进程启动后立即退出（退出码 %lu）。\n\n"
                 "常见原因：\n"
                 "  · 运行时文件不完整（解压中断或被杀毒软件拦截）\n"
                 "  · 显卡驱动不兼容（已强制软件渲染，通常不是这个）\n\n"
                 "详细信息见日志：\n%%LOCALAPPDATA%%\\HMWLX\\launcher.log",
                 (unsigned long)code);
        logmsg("错误：JVM 进程提前退出，退出码 %lu\n", (unsigned long)code);
        show_error(msg);
        CloseHandle(pi.hThread);
        CloseHandle(pi.hProcess);
        return 7;
    }

    logmsg("JVM 进程已启动%s\n",
           wr == WAIT_TIMEOUT ? "（3 秒内未退出，判定正常）" : "");

    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;
#else
    pid_t pid = fork();
    if (pid < 0) { free(args); show_error("fork 失败。"); return 6; }
    if (pid == 0) {
        if (chdir(root) != 0) _exit(7);
        execv(javaPath, args);
        _exit(8);
    }
    int status = 0;
    waitpid(pid, &status, 0);
    free(args);
    return WIFEXITED(status) ? WEXITSTATUS(status) : 9;
#endif
}

/* ---------------------------------------------------------------- 主流程 */

int main(int argc, char **argv)
{
    for (int i = 1; i < argc; i++) {
        if (strcmp(argv[i], "--verbose") == 0) g_verbose = 1;
    }

    /* 先开日志：后面任何一步失败都要能查到原因 */
    log_open();
    logmsg("参数: %d 个\n", argc);

    SFXIndex idx;
    memset(&idx, 0, sizeof(idx));

    int rc = load_index(&idx);

    if (rc == 1) {
        /*
         * 兼容模式：这个 exe 没内嵌数据（比如是开发时的裸启动器），
         * 退回「同目录下有 runtime/ 和 hmwlx.jar」的老行为。
         */
        logmsg("未发现内嵌索引，走兼容模式（同目录查找 runtime/）\n");
        char dir[2048];
        if (get_self_path(dir, sizeof(dir)) != 0) {
            logmsg("错误：无法定位自身路径\n");
            show_error("无法定位自身路径。");
            log_close();
            return 1;
        }
        path_dirname(dir);
        logmsg("程序目录: %s\n", dir);
        int r = launch_jvm(dir, argc, argv);
        logmsg("兼容模式退出码: %d\n", r);
        log_close();
        return r;
    }
    if (rc != 0) {
        logmsg("错误：索引解析失败 (rc=%d)\n", rc);
        show_error("自解压索引损坏，文件可能下载不完整。");
        log_close();
        return 1;
    }

    logmsg("索引: %u 个条目, 自身路径 %s\n", idx.count, idx.selfPath);

    if (get_cache_root(idx.cacheRoot, sizeof(idx.cacheRoot)) != 0) {
        logmsg("错误：无法确定缓存目录\n");
        show_error("无法确定缓存目录。");
        free(idx.entries);
        log_close();
        return 1;
    }

    char appRoot[2048];
    char tmp[2048];
    path_join(tmp, sizeof(tmp), idx.cacheRoot, CACHE_TAG);
    path_join(appRoot, sizeof(appRoot), tmp, idx.cacheTag);
    logmsg("解压目标: %s\n", appRoot);

    /* 已解压过就直接启动，这是常态路径 */
    if (!is_marked_extracted(appRoot, idx.cacheTag)) {
        if (mkdirs(appRoot) != 0) {
            char msg[2600];
            snprintf(msg, sizeof(msg), "无法创建解压目录：\n%s", appRoot);
            logmsg("错误：mkdirs 失败 %s\n", appRoot);
            show_error(msg);
            free(idx.entries);
            log_close();
            return 1;
        }
        logmsg("首次运行，开始解压 %u 个条目\n", idx.count);

        /* 弹进度窗：解压要 8 秒左右，没有反馈用户会反复双击 */
        progress_open((int)idx.count);
        int erc = extract_all(&idx, appRoot);
        progress_close();

        if (erc == -4) {
            /* 用户主动取消：静默退出，不弹错误框 */
            logmsg("用户取消解压\n");
            free(idx.entries);
            log_close();
            return 0;
        }
        if (erc != 0) {
            char msg[2600];
            snprintf(msg, sizeof(msg),
                     "解压失败（代码 %d）。\n\n可能是磁盘空间不足，或下载的文件不完整。\n"
                     "可以删除下面这个目录后重试：\n%s", erc, appRoot);
            logmsg("错误：解压失败，代码 %d\n", erc);
            show_error(msg);
            free(idx.entries);
            log_close();
            return 1;
        }
        write_mark(appRoot, idx.cacheTag);
        logmsg("解压完成，已写标记文件\n");
    } else {
        logmsg("已解压过，跳过解压\n");
    }

    free(idx.entries);
    int r = launch_jvm(appRoot, argc, argv);
    logmsg("启动 JVM 返回: %d\n", r);
    log_close();
    return r;
}
