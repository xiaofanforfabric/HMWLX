/*
 * HMWLX 启动器 / HMWLX Launcher
 *
 * 一个极小的原生启动器（~100KB），负责：
 *   1. 定位自身所在目录
 *   2. 找到随附的 runtime/ 里的 java(w)
 *   3. 拼接 JVM 参数，启动 hmwlx.jar
 *   4. 出错时弹窗提示，而不是静默消失
 *
 * 支持 Windows (PE, GUI 子系统) 与 Linux (ELF)，由 zig cc 交叉编译。
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <unistd.h>
#include <sys/wait.h>
#include <limits.h>
#endif

#define JAR_NAME "hmwlx.jar"
#define APP_NAME "HMWLX"
#define MAIN_CLASS "org.cn.xiaofan.MainKt"

/* JVM 参数，顺序敏感（--add-opens 必须在 -jar 之前） */
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

/* ---------------------------------------------------------------- 错误提示 */

static void show_error(const char *msg)
{
#ifdef _WIN32
    /* 转 UTF-8 -> UTF-16，因为源码里可能含中文 */
    wchar_t wbuf[2048];
    int n = MultiByteToWideChar(CP_UTF8, 0, msg, -1, wbuf, 2048);
    if (n <= 0) {
        MultiByteToWideChar(CP_ACP, 0, msg, -1, wbuf, 2048);
    }
    MessageBoxW(NULL, wbuf, L"" APP_NAME L" 启动失败", MB_ICONERROR | MB_OK);
#else
    fprintf(stderr, "[" APP_NAME "] %s\n", msg);
#endif
}

/* -------------------------------------------------------- 获取自身所在目录 */

#ifdef _WIN32

static int get_exe_dir(char *out, size_t cap)
{
    wchar_t wpath[MAX_PATH];
    DWORD n = GetModuleFileNameW(NULL, wpath, MAX_PATH);
    if (n == 0 || n >= MAX_PATH) {
        return -1;
    }
    /* 去掉文件名部分 */
    for (DWORD i = n; i > 0; i--) {
        if (wpath[i - 1] == L'\\' || wpath[i - 1] == L'/') {
            wpath[i - 1] = L'\0';
            break;
        }
    }
    int r = WideCharToMultiByte(CP_UTF8, 0, wpath, -1, out, (int)cap, NULL, NULL);
    return r > 0 ? 0 : -1;
}

#else

static int get_exe_dir(char *out, size_t cap)
{
    ssize_t n = readlink("/proc/self/exe", out, cap - 1);
    if (n <= 0) {
        return -1;
    }
    out[n] = '\0';
    for (ssize_t i = n; i > 0; i--) {
        if (out[i - 1] == '/') {
            out[i - 1] = '\0';
            break;
        }
    }
    return 0;
}

#endif

/* ------------------------------------------------------------ 文件存在检查 */

static int file_exists(const char *path)
{
#ifdef _WIN32
    wchar_t wpath[2048];
    if (MultiByteToWideChar(CP_UTF8, 0, path, -1, wpath, 2048) <= 0) {
        return 0;
    }
    DWORD attr = GetFileAttributesW(wpath);
    return attr != INVALID_FILE_ATTRIBUTES && !(attr & FILE_ATTRIBUTE_DIRECTORY);
#else
    FILE *f = fopen(path, "rb");
    if (f) {
        fclose(f);
        return 1;
    }
    return 0;
#endif
}

/* ------------------------------------------------------------------ 主流程 */

int main(int argc, char **argv)
{
    char dir[2048];
    char java_path[2304];
    char jar_path[2304];

    if (get_exe_dir(dir, sizeof(dir)) != 0) {
        show_error("无法定位自身所在目录。");
        return 1;
    }

#ifdef _WIN32
    snprintf(java_path, sizeof(java_path), "%s\\runtime\\bin\\javaw.exe", dir);
    snprintf(jar_path, sizeof(jar_path), "%s\\" JAR_NAME, dir);
#else
    snprintf(java_path, sizeof(java_path), "%s/runtime/bin/java", dir);
    snprintf(jar_path, sizeof(jar_path), "%s/" JAR_NAME, dir);
#endif

    if (!file_exists(java_path)) {
        char msg[2600];
        snprintf(msg, sizeof(msg),
                 "找不到 Java 运行环境：\n%s\n\n请确认 runtime 目录完整（不要只复制 exe）。",
                 java_path);
        show_error(msg);
        return 2;
    }

    if (!file_exists(jar_path)) {
        char msg[2600];
        snprintf(msg, sizeof(msg),
                 "找不到程序主文件：\n%s\n\n请确认 " JAR_NAME " 与本程序在同一目录。",
                 jar_path);
        show_error(msg);
        return 3;
    }

    /* --------------------------------------------------- 组装命令行 */

    int total = 1 /* java */ + JVM_ARG_COUNT + 2 /* -jar, jar */ + (argc - 1) + 1 /* NULL */;
    char **args = (char **)calloc((size_t)total, sizeof(char *));
    if (!args) {
        show_error("内存不足。");
        return 4;
    }

    int k = 0;
    args[k++] = java_path;
    for (int i = 0; i < JVM_ARG_COUNT; i++) {
        args[k++] = (char *)JVM_ARGS[i];
    }
    args[k++] = "-jar";
    args[k++] = jar_path;
    for (int i = 1; i < argc; i++) {
        args[k++] = argv[i];
    }
    args[k] = NULL;

#ifdef _WIN32

    /* 把 argv 转成 CreateProcessW 需要的命令行字符串（参数加引号转义） */
    size_t cmd_cap = 32768;
    char *cmd = (char *)calloc(cmd_cap, 1);
    if (!cmd) {
        show_error("内存不足。");
        return 4;
    }
    size_t used = 0;
    for (int i = 0; args[i]; i++) {
        const char *a = args[i];
        int need_quote = (a[0] == '\0' || strpbrk(a, " \t\"") != NULL);
        if (used + strlen(a) * 2 + 8 >= cmd_cap) {
            break;
        }
        if (i > 0) {
            cmd[used++] = ' ';
        }
        if (need_quote) {
            cmd[used++] = '"';
        }
        for (const char *p = a; *p; p++) {
            if (*p == '"' || *p == '\\') {
                /* 简单转义：反斜杠与引号前加反斜杠 */
                cmd[used++] = '\\';
            }
            cmd[used++] = *p;
        }
        if (need_quote) {
            cmd[used++] = '"';
        }
    }
    cmd[used] = '\0';

    wchar_t *wcmd = (wchar_t *)calloc(65536, sizeof(wchar_t));
    if (!wcmd || MultiByteToWideChar(CP_UTF8, 0, cmd, -1, wcmd, 65536) <= 0) {
        free(cmd);
        show_error("命令行编码转换失败。");
        return 5;
    }
    free(cmd);

    STARTUPINFOW si;
    PROCESS_INFORMATION pi;
    ZeroMemory(&si, sizeof(si));
    si.cb = sizeof(si);
    ZeroMemory(&pi, sizeof(pi));

    /* 以自身目录为工作目录 */
    wchar_t wdir[2048];
    MultiByteToWideChar(CP_UTF8, 0, dir, -1, wdir, 2048);

    if (!CreateProcessW(NULL, wcmd, NULL, NULL, FALSE,
                        CREATE_UNICODE_ENVIRONMENT,
                        NULL, wdir, &si, &pi)) {
        DWORD err = GetLastError();
        char msg[2600];
        snprintf(msg, sizeof(msg), "启动 JVM 失败（错误码 %lu）。\n\n命令行：\n%s",
                 (unsigned long)err, dir);
        show_error(msg);
        free(wcmd);
        free(args);
        return 6;
    }

    free(wcmd);
    free(args);

    /* 我们不等它退出——关掉启动器窗口后游戏继续运行 */
    CloseHandle(pi.hThread);
    CloseHandle(pi.hProcess);
    return 0;

#else /* ---------------- Linux ---------------- */

    pid_t pid = fork();
    if (pid < 0) {
        show_error("fork 失败。");
        free(args);
        return 6;
    }

    if (pid == 0) {
        /* 子进程：切换工作目录后 exec */
        if (chdir(dir) != 0) {
            _exit(7);
        }
        execv(java_path, args);
        _exit(8);
    }

    /* 父进程等待，把退出码透传出去，便于脚本/终端感知 */
    int status = 0;
    waitpid(pid, &status, 0);
    free(args);

    if (WIFEXITED(status)) {
        return WEXITSTATUS(status);
    }
    return 9;
#endif
}
