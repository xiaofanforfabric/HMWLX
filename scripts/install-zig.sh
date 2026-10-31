#!/usr/bin/env bash
#
# 安装 zig（CI 和本地都能用）。
#
# ==========================================================================
# 为什么需要专门的脚本
# ==========================================================================
#
# zig 的官方 tarball 解压出来有 13000+ 个小文件。在某些文件系统上
# 这会造成严重问题 —— CNB 的 $HOME 就踩到了：
#
#   tar: zig-linux-x86_64-0.13.0/zig: Cannot open: Function not implemented
#
# ENOSYS（Function not implemented）说明后端存储不支持 tar 需要的
# 某些操作。整步耗时 3.4 分钟才失败，因为每个文件都要超时重试。
#
# 所以解压位置必须可配置，且要能自动回退到更「普通」的位置。
#
# ==========================================================================
# 解压位置的选择顺序
# ==========================================================================
#
#   1. $HMWLX_ZIG_DIR             显式指定（CI 里可以精确控制）
#   2. $ROOT/.tooling/zig         项目同目录 —— 本地默认首选
#   3. /tmp/hmwlx-zig             /tmp 兜底，但要小心：CNB 的 /tmp 常是
#                                 overlay 挂载，同样不支持批量小文件
#   4. $HOME/.local/opt           $HOME 兜底
#   5. $HOME/.cache/hmwlx-zig     **最后**才试：CI 上那一堆 ENOSYS 都是从
#                                 $HOME 报出来的，别让它抢在前面
#
# 每个位置都试三层解压方式（整包 → 分段 → 只解必需目录）。五个位置全报
# ENOSYS 之后才确认：光换位置治不好病，得改解压的节奏，见下一节。
#
# 为什么项目目录排第一：
#   本地开发和 CNB 一样，仓库就是普通的 git 工作区，文件系统语义正常；
#   zig 解到项目里，反复跑脚本不用重新下载解压，也方便调试。
#
# 注意：CNB 构建里 $ROOT（/workspace）本身也可能挂在不支持批量建文件的
# 存储上 —— 那份存储专门为「频繁 commit 几百万文件」做过优化，代价就是
# tar 展开 zig 的 16000 个小文件时直接 ENOSYS。所以这里不能死认项目目录：
#
#   - CNB CI 里由 .cnb.yml 通过 HMWLX_ZIG_DIR 明确指向 /usr/local（容器层，
#     真实文件系统），见那边的注释；
#   - 探测只验证「建 200 个平铺文件 + 几层 mkdir」，而 tar 展开的是上万层
#     嵌套小文件，探测通过 ≠ 解压一定成功。因此解压会逐个候选位置重试，
#     失败就清干净换下一个，而不是一次失败就整体退出。
#
# ==========================================================================
# 用法
# ==========================================================================
#
#   ./scripts/install-zig.sh           装到自动选定的位置
#   ./scripts/install-zig.sh --path    只打印 zig 可执行文件路径
#   ./scripts/install-zig.sh --quiet   --path 的同义词，供 pack 脚本静默调用
#
# 装完后把返回的目录加进 PATH：
#
#   ZIG_BIN_DIR="$(./scripts/install-zig.sh --path)"
#   export PATH="$ZIG_BIN_DIR:$PATH"
#
# ==========================================================================
# 解压方式：一次写不完，就分几次写
# ==========================================================================
#
# 事实先摆在这儿（来自 1k698d55i 那次构建的日志）：
#
#   - 五个候选位置，连容器层 /usr/local 在内，全部报
#     "Cannot open: Function not implemented"（ENOSYS），共 16390 个；
#   - 同一个位置，脚本自己建 200 个平铺文件 + 几层 mkdir 却毫无问题；
#   - 压缩包过 tar -tf 校验，完好。
#
# 三条一起说明问题不在权限、不在包、也不在「哪个位置更特殊」，而在
# 写入量：这些 overlay 挂载点在一次 tar 连续写上万个小文件时，写到某个
# 量以后 open(2) 直接返回 ENOSYS。
#
# 那就别一口气写完。三层逐级降级，全失败才算这个位置没戏：
#
#   full    整包 tar -xf，一次解完。本地和普通磁盘最快，行为和以前一致。
#   chunk   按字节预算把成员切成若干段，每段单独一次 tar，段间 sync +
#           小睡。峰值写入量被压到「200 个平铺文件」那个量级附近。
#           要解哪些用排除法算（tar 列表 − SKIP），升版本不用改代码。
#   sparse  只解编译必需的目录。最后一道保险：能编就能打包。
#
# 每层之后都跑一次 zig version —— 那是唯一可靠的判据，别去猜目录名
# （镜像重打包可能改前缀）。

# ==========================================================================
# 下载地址（镜像）
# ==========================================================================
#
# 默认走官方 ziglang.org。CNB 构建机上实测只有 ~5MB/s，44MB 的包要 8 秒，
# 而且换节点后会慢到 1MB/s 以上 —— 这一步本该是「装个工具」，不该成为
# 打包 Stage 的瓶颈，还要陪上几十秒。
#
# 所以按镜像列表逐个试，第一个成功的赢：
#
#   1. $HMWLX_ZIG_MIRROR  显式指定，可给多个（空格或逗号分隔）
#   2. https://ziglang.org/download         官方
#   3. https://mirrors.huaweicloud.com/zig  国内镜像，官方 tarball 的镜像副本
#
# 注意：npmmirror 的 /mirrors/zig 已经失效 —— 它会 302 到
# cdn.npmmirror.com/binaries/zig/…，那边是 NoSuchKey 404。
# 别再把它加回列表，否则每次构建都要先吃一个必然失败的请求。
#
# 镜像目录结构与官方一致（<url>/<version>/<tarball>）。
# 下载结果过了大小门槛还要能被 tar 列开，别让镜像的 404 页面变成半个压缩包。
#
# 镜像上同时找 .tar.xz 和 .tar.gz：前者小 30%，后者解压不需要 xz 命令
# （eclipse-temurin 这类精简镜像不带 xz）。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 固定版本：本地验证过，不要随意漂移。
# 升版本前先在本地跑一遍 pack-windows.sh / pack-linux.sh 确认能编。
ZIG_VERSION="${ZIG_VERSION:-0.13.0}"

TARBALL="zig-linux-x86_64-${ZIG_VERSION}.tar.xz"
TARBALL_GZ="zig-linux-x86_64-${ZIG_VERSION}.tar.gz"
EXTRACTED_DIR="zig-linux-x86_64-${ZIG_VERSION}"

# 镜像按顺序试；HMWLX_ZIG_MIRROR 支持空格或逗号分隔的多个前缀。
MIRRORS=()
if [[ -n "${HMWLX_ZIG_MIRROR:-}" ]]; then
    _m="${HMWLX_ZIG_MIRROR//,/ }"
    # shellcheck disable=SC2206  # 故意按空白拆分
    MIRRORS+=($_m)
fi
MIRRORS+=("https://ziglang.org/download")
MIRRORS+=("https://mirrors.huaweicloud.com/zig")

info() { echo "==> $*" >&2; }
die()  { echo "错误：$*" >&2; exit 1; }

# ---------------------------------------------------------------- 快速探测
#
# 在某个目录下建 200 个空文件，看会不会报 ENOSYS 之类。
# 200 个足够暴露问题，又不会耗时太久（实测 < 50ms）。
probe_dir() {
    local dir="$1"
    # 先确保父目录能建
    mkdir -p "$dir" 2>/dev/null || return 1

    local probe="$dir/.probe.$$"
    mkdir -p "$probe" 2>/dev/null || return 1

    local ok=0
    for i in $(seq 1 200); do
        if ! : > "$probe/f$i" 2>/dev/null; then
            ok=1
            break
        fi
    done
    # 顺便试试 mkdir 嵌套（tar 会建很多层目录）
    if [[ $ok -eq 0 ]]; then
        mkdir -p "$probe/a/b/c/d/e" 2>/dev/null || ok=1
    fi

    rm -rf "$probe" 2>/dev/null || true
    return $ok
}

# ---------------------------------------------------------------- 候选位置
candidates=()
[[ -n "${HMWLX_ZIG_DIR:-}" ]] && candidates+=("$HMWLX_ZIG_DIR")
# 项目同目录优先：本地开发时这里就是普通工作区
candidates+=("$ROOT/.tooling/zig")
# /tmp 兜底但靠后：容器里常是 overlayfs，批量小文件会 ENOSYS
candidates+=("/tmp/hmwlx-zig")
candidates+=("${HOME:-/root}/.local/opt")
# $HOME 放最后：CNB 上 ENOSYS 就是从这里报出来的
candidates+=("${HOME:-/root}/.cache/hmwlx-zig")

# ---------------------------------------------------------------- 已装好？
#
# 除了标准的 zig-linux-x86_64-<ver>/ 目录，也认「按目录解压」留下的那份：
# 顶层目录名由 tar 顶层条目决定，这里用通配各扫一层，不按名字猜。
zig_ready() {
    local bin
    for bin in "$1/$EXTRACTED_DIR/zig" "$1"/zig-linux-*/zig; do
        [[ -x "$bin" ]] || continue
        "$bin" version >/dev/null 2>&1 && { dirname "$bin"; return 0; }
    done
    return 1
}

for dir in "${candidates[@]}"; do
    if ready="$(zig_ready "$dir")"; then
        if [[ "${1:-}" == "--path" || "${1:-}" == "--quiet" ]]; then
            echo "$ready"
        else
            info "zig 已就绪: $ready ($("$ready/zig" version))"
        fi
        exit 0
    fi
done

# ---------------------------------------------------------------- 下载
#
# 缓存放到项目内的 .tooling/ 而不是 /tmp：
#   1. 单文件写入 /tmp 一般没问题，但 CNB 的 /tmp 是 overlay，
#      写 44MB 也不是不可能出问题，能避开就避开；
#   2. 缓存在项目里，重复跑脚本不用重新下载（CI 里无所谓，
#      本地调试时省很多时间）。
#
CACHE_DIR="$ROOT/.tooling/cache"
mkdir -p "$CACHE_DIR"
# 缓存文件名固定带 .tar.xz 后缀（历史沿用），内容按魔数识别，
# tar -x 自己会挑 gzip/xz 解压器，所以下到 .tar.gz 也存这里。
CACHE="$CACHE_DIR/hmwlx-zig-${ZIG_VERSION}.tar.xz"

MIN_BYTES=40000000   # 0.13.0 的包约 44.9MB，留点余量

download_zig() {
    rm -f "$CACHE"
    local name url size
    for base in "${MIRRORS[@]}"; do
        # .tar.xz 优先（小 30%），镜像只放了 .tar.gz 的退回 gz。
        # 关键是 .tar.gz 用 gzip 解，不需要 xz 命令 —— eclipse-temurin
        # 这类精简镜像不一定带 xz，多个选择就少一个失败点。
        for name in "$TARBALL" "$TARBALL_GZ"; do
            url="${base%/}/${ZIG_VERSION}/${name}"
            info "下载 zig $ZIG_VERSION ← $base（$name）"
            # 单个镜像 2 次重试、单次最长 10 分钟，够慢速链路下完 44MB，
            # 又不至于卡死整条流水线。
            if ! curl -fL --retry 2 --retry-delay 2 --connect-timeout 15 \
                    --max-time 600 -o "$CACHE.part" "$url" 2>/dev/null; then
                info "  下载失败，换下一个镜像"
                rm -f "$CACHE.part"
                continue
            fi
            size="$(stat -c%s "$CACHE.part" 2>/dev/null || echo 0)"
            # 光看大小不够：镜像的 HTML 错误页也可能很大。真正的判据是
            # tar 能不能把它列开 —— 比调 xz 命令更稳，也不依赖外部命令。
            if [[ "$size" -gt "$MIN_BYTES" ]] && tar -tf "$CACHE.part" >/dev/null 2>&1; then
                mv "$CACHE.part" "$CACHE"
                return 0
            fi
            info "  不是有效的压缩包（${size}B），换下一个"
            rm -f "$CACHE.part"
        done
    done
    return 1
}

if [[ -f "$CACHE" ]] && [[ "$(stat -c%s "$CACHE" 2>/dev/null || echo 0)" -gt "$MIN_BYTES" ]]; then
    info "使用已下载的 $CACHE"
else
    download_zig || die "所有镜像都下载不到 zig $ZIG_VERSION。
  试过的：${MIRRORS[*]}
  可以用 HMWLX_ZIG_MIRROR=<前缀> 指定一个镜像目录。"
fi

# ---------------------------------------------------------------- 解压
#
# 探测通过的位置也可能解压到一半 ENOSYS，所以这里逐个候选位置真试一遍：
# 失败就把半成品删掉换下一个。tar 的报错有上万行，只留最后 5 行提示。
# 不写死 tar -xJf：eclipse-temurin 这类精简镜像不带 xz 命令，-J 会直接
# 失败在「找不到 xz」上，和文件系统问题长得一模一样。tar -x 按魔数自己
# 挑解压器，.tar.xz / .tar.gz 都能吃，也不依赖外部 xz 命令。
if ! tar -tf "$CACHE" > /dev/null 2>&1; then
    info "缓存里的 zig 包已损坏（多半是上次下载中途挂了），重新下载"
    download_zig || die "zig 压缩包损坏且重新下载失败。
  可以手动删掉 $CACHE 再试。"
fi

# ---------------------------------------------------------------- 解压实现
#
# 拆成小块解，是为了绕过存储的写入限制，而不是为了省空间。
#
# 这一轮日志把话说死了：五个候选位置、两层解压方式，全部报同一个错 ——
#   tar: zig-linux-x86_64-0.13.0/zig: Cannot open: Function not implemented
#   tar: zig-linux-x86_64-0.13.0/lib/tsan/ubsan: Cannot mkdir: Function not implemented
# 可写探测（200 个平铺小文件）全过，包也校验过。所以不是权限、不是坏包、
# 也不是「某个位置更特殊」—— 是这些 overlay 挂载点扛不住一次 tar 连续写
# 上万个小文件，写到某个量之后 open(2) 直接返回 ENOSYS。
#
# 那就别一口气写完。把要解的文件排成若干段，每段单独一次 tar，段间给内核
# 一点时间把脏页刷下去。峰值写入量和连写时长都被压到探测能过的那个量级。
#
# 三段式，逐级降级：
#   full   整包一次解完。本地/普通磁盘最快，行为和以前完全一致。
#   chunk  分段解，每次 CHUNK_SIZE 个文件。要解哪些用排除法算：
#          tar -tf 列表 − SKIP 里的目录，这样版本升级也不会漏东西。
#   sparse 只解编译必需的目录，一个子项一次。最后一道，能编就能打包。
#
# full 失败 → chunk。chunk 失败 → sparse。三层都失败才判这个位置没用。

# 版本升级后这里不用改。唯一的例外是 zig 把新的大目录塞进 lib/：那样
# 它会被当成「要解」的东西，只是落在某一段里，仍然能解出来。
SKIP=(doc lib/docs lib/tsan lib/compiler lib/libcxx lib/libcxxabi
      lib/libunwind lib/libc/musl lib/libc/wasi lib/libc/darwin)

# 最后一道保险用的必需集合。只用来在 sparse 阶段判断「这份够不够编译」，
# 不做排除，所以版本升级对它无感。
SPARSE_REQUIRED=(zig lib/std lib/include lib/compiler_rt.zig lib/compiler_rt
                 lib/libc/include lib/libc/glibc lib/libc/mingw lib/c.zig lib/zig.h)

# 每段的两个上限：条目数和落盘字节数，谁先到就换段。
#
# 只看字节会被大文件坑 —— 一个 150MB 的 zig 本体单独就能占满一段预算，
# 结果切出上百个只有一两个成员的段；只看条目数又会忽略「一千个几十字节的
# 头文件比几十个大文件更容易把存储写崩」。两个一起卡，段长才稳定。
#
# 默认 800 个 / 4MB：1 万个成员切成十来段，每段解完 sync + 小睡一下。
CHUNK_MAX_ENTRIES="${HMWLX_ZIG_CHUNK:-800}"
CHUNK_MAX_KB="${HMWLX_ZIG_CHUNK_KB:-4096}"

# 展开后检查 zig 能不能真的跑起来 —— 这是唯一可靠的判据，
# 别去猜目录名（镜像重打包可能改前缀）。
zig_ok() {
    local bin="$1"
    [[ -n "$bin" && -x "$bin" ]] || return 1
    "$bin" version >/dev/null 2>&1
}

# 让内核把刚写下的东西落盘，同时给存储后端一点喘息时间。
# sync 不保证后端真的消化完了，所以再给一小段 sleep；这点代价换来
# 「下一段不撞在上一段的尾巴上」，值。
settle() {
    sync
    sleep "${HMWLX_ZIG_SETTLE:-0.3}"
}

# 从 tar 列表里删掉 SKIP 覆盖的条目。
# SKIP 里写的是相对归档顶层目录的路径（doc、lib/tsan…），所以先剥掉顶层
# 再比；顶层名不写死，取第一个成员的第一段，镜像重打包改了前缀也认。
filter_list() {
    local list="$1" out="$2" top="$3"
    printf '%s\n' "${SKIP[@]}" > "$CACHE.skip"
    awk -v top="$top/" '
        NR == FNR { skip[$0] = 1; next }
        {
            rel = $0
            if (index(rel, top) == 1) rel = substr(rel, length(top) + 1)
            for (s in skip) {
                if (rel == s || substr(rel, 1, length(s) + 1) == s "/") next
            }
            print
        }
    ' "$CACHE.skip" "$list" > "$out"
}

# 把归档列表写成「名字 + 落盘增量（KB）」两列。
#
# 落盘量从一个上限里扣：tar -tvf 每次都会把自己写过多少打印成 total，
# 在段边界记账就能推出每个成员实际写了多少（目录、符号链接写 0）。
# 按写入量切段而不是按个数切：几十字节的头文件最扎堆，几 MB 的库文件
# 反而是最省心的那类。
member_list() {
    local tf="$1" tv="$2" out="$3"
    tar -tf "$CACHE" > "$tf" 2>/dev/null || return 1
    LC_ALL=C tar -tvf "$CACHE" 2>/dev/null | awk '{ print $3 }' > "$tv" || return 1
    paste -d '\t' "$tf" "$tv" | awk -F '\t' '
        {
            kb = int($2 / 1024)
            if (kb < 1) kb = 1        # 单个小文件也要算数，否则切不出段
            printf "%s\t%d\n", $1, kb
        }
    ' > "$out"
}

extract_full() {
    local dir="$1" log="$CACHE.tar.log" bin
    tar -xf "$CACHE" -C "$dir" 2>"$log"
    bin="$(find "$dir" -maxdepth 3 -type f -name zig -perm -u+x 2>/dev/null | head -1)"
    zig_ok "$bin" && { echo "$bin"; return 0; }
    return 1
}

# 分段解。段边界对齐到「同一目录的最后一次出现」，尽量别把一个目录
# 拆成两段（每次 tar 都要重新 mkdir 一棵树，拆得太碎反而多写目录）。
extract_chunked() {
    local dir="$1" log="$CACHE.chunk.log" top list n=0 total
    : >"$log"

    top="$(tar -tf "$CACHE" 2>/dev/null | head -1 | cut -d/ -f1)"
    [[ -n "$top" ]] || return 1

    member_list "$CACHE.names.raw" "$CACHE.names.verbose" "$CACHE.phys" || return 1
    cut -f1 "$CACHE.phys" > "$CACHE.names"
    filter_list "$CACHE.names" "$CACHE.wanted" "$top" || return 1
    # 只要物理量，名字顺序与 names 一致，直接并回去。
    awk 'NR == FNR { w[$0]=1; next }
         ($1 in w) { print }' "$CACHE.wanted" "$CACHE.phys" > "$CACHE.wanted.phys"

    total=$(wc -l < "$CACHE.wanted.phys")
    [[ "$total" -gt 0 ]] || return 1

    split_list "$CACHE.wanted.phys" || return 1

    for list in "$CACHE".chunk.*; do
        [[ -f "$list" ]] || continue
        n=$((n + 1))
        # 去掉目录成员（以 / 结尾）。GNU tar 的 -T 只要碰到目录名就会切到
        # 递归匹配模式，后面那些正经文件名全被判成 "Not found in archive"。
        # 目录反正由 tar 按需自己建。
        grep -v '/$' "$list" > "$list.names" || true
        if ! tar -xf "$CACHE" -C "$dir" -T "$list.names" 2>>"$log"; then
            return 1
        fi
        settle
    done

    info "  分段解压完成（$n 段，$total 个成员）"
    zig_ok "$dir/$top/zig" && { echo "$dir/$top/zig"; return 0; }
    return 1
}

# 把「名字 + 落盘量」列表切成若干段：$CACHE.chunk.NN
split_list() {
    local src="$1" idx=0 count=0 kb_sum=0 name kb cur
    rm -f "$CACHE".chunk.*
    cur="$(printf '%s.chunk.%02d' "$CACHE" "$idx")"
    : > "$cur"
    while IFS=$'\t' read -r name kb; do
        # 任一上限到头就换段；当前这条带进新段，避免切出空段。
        if [[ "$count" -gt 0 ]] &&
           { [[ "$count" -ge "$CHUNK_MAX_ENTRIES" ]] || [[ $((kb_sum + kb)) -gt "$CHUNK_MAX_KB" ]]; }; then
            idx=$((idx + 1))
            count=0
            kb_sum=0
            cur="$(printf '%s.chunk.%02d' "$CACHE" "$idx")"
            : > "$cur"
        fi
        count=$((count + 1))
        kb_sum=$((kb_sum + kb))
        printf '%s\n' "$name" >> "$cur"
    done < "$src"
}

extract_sparse() {
    local dir="$1" log="$CACHE.sparse.log" top entry kept=0 bin
    : >"$log"

    top="$(tar -tf "$CACHE" 2>/dev/null | head -1 | cut -d/ -f1)"
    [[ -n "$top" ]] || return 1

    for entry in "${SPARSE_REQUIRED[@]}"; do
        if ! tar -xf "$CACHE" -C "$dir" "$top/$entry" 2>>"$log"; then
            return 1
        fi
        kept=$((kept + 1))
        settle
    done

    bin="$dir/$top/zig"
    zig_ok "$bin" || return 1
    echo "$bin"
}

ZIG_BIN=""
for dir in "${candidates[@]}"; do
    info "探测 $dir …"
    if ! probe_dir "$dir"; then
        info "  不可用（建不了 200 个平铺文件），换下一个"
        continue
    fi

    mkdir -p "$dir"
    rm -rf "${dir:?}/$EXTRACTED_DIR"

    info "  可写，整包解压到 $dir"
    if ZIG_BIN="$(extract_full "$dir")"; then
        break
    fi
    info "  整包解压失败（$(grep -c 'Function not implemented' "$CACHE.tar.log" 2>/dev/null || echo 0) 个 ENOSYS），改分段解"
    tail -n 3 "$CACHE.tar.log" 2>/dev/null | sed 's/^/    /' >&2 || true
    rm -rf "${dir:?}/$EXTRACTED_DIR"

    if ZIG_BIN="$(extract_chunked "$dir")"; then
        break
    fi
    info "  分段解也失败，改只解编译必需的目录"
    tail -n 3 "$CACHE.chunk.log" 2>/dev/null | sed 's/^/    /' >&2 || true
    rm -rf "${dir:?}/$EXTRACTED_DIR"

    if ZIG_BIN="$(extract_sparse "$dir")"; then
        info "  只解必需目录成功（少了解不开的部分，编译够用）"
        break
    fi
    info "  三种解压方式都失败，换下一个位置"
    tail -n 3 "$CACHE.sparse.log" 2>/dev/null | sed 's/^/    /' >&2 || true

    # 清掉半成品，否则下次的「已装好？」检查可能撞见残缺目录
    rm -rf "${dir:?}/$EXTRACTED_DIR"
    ZIG_BIN=""
done

if [[ -z "$ZIG_BIN" ]]; then
    # 失败时不能只说「都失败了，自己指定个目录吧」—— 那句话在 99% 的
    # 情况下都是误导：位置基本没问题，坏的是缓存或环境。把证据摊开。
    echo "" >&2
    echo "错误：所有候选位置都无法解压 zig。" >&2
    echo "" >&2
    echo "  压缩包：$CACHE（$(stat -c%s "$CACHE" 2>/dev/null || echo '?') 字节）" >&2
    echo "  校验和：$(sha256sum "$CACHE" 2>/dev/null | cut -d' ' -f1 || echo '?')" >&2
    echo "  解压方式：整包 → 分段（每段 ≤${CHUNK_MAX_ENTRIES} 个 / ≤${CHUNK_MAX_KB}KB）→ 只解必需目录，三层都试过了" >&2
    echo "" >&2
    echo "  逐个位置的结果：" >&2
    for dir in "${candidates[@]}"; do
        if probe_dir "$dir"; then
            echo "    $dir  可写，但三种解压方式都失败" >&2
        else
            echo "    $dir  建不了 200 个平铺文件" >&2
        fi
    done
    echo "" >&2
    if [[ -s "$CACHE.sparse.log" ]]; then
        echo "  最后一次报错（末 10 行）：" >&2
        tail -n 10 "$CACHE.sparse.log" | sed 's/^/    /' >&2
    fi
    echo "" >&2
    echo "  常见原因：" >&2
    echo "    1. 压缩包是坏的 —— 删掉 $CACHE 重试" >&2
    echo "    2. 存储写入量受限 —— 调小 HMWLX_ZIG_CHUNK（每段条目数，默认 ${CHUNK_MAX_ENTRIES}）再试" >&2
    echo "    3. 真的没有可写位置 —— HMWLX_ZIG_DIR=/some/path 指定一个" >&2
    die "zig 安装失败"
fi

info "验证"
"$ZIG_BIN" version >&2

case "${1:-}" in
    --path|--quiet)
        echo "$(dirname "$ZIG_BIN")"
        ;;
    *)
        echo ""
        echo "zig 已安装：$(dirname "$ZIG_BIN")"
        echo "加到 PATH："
        echo "  export PATH=\"$(dirname "$ZIG_BIN"):\$PATH\""
        ;;
esac
