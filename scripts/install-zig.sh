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
#   4. $HOME/.local/opt           最后的兜底
#   5. $HOME/.cache/hmwlx-zig     **最后**才试：CI 上那一堆 ENOSYS 都是从
#                                 $HOME 报出来的，别让它抢在前面
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
# 下载结果过了大小门槛还要能通过 xz -t 校验，别让镜像的 404 页面变成半个 tar.xz。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 固定版本：本地验证过，不要随意漂移。
# 升版本前先在本地跑一遍 pack-windows.sh / pack-linux.sh 确认能编。
ZIG_VERSION="${ZIG_VERSION:-0.13.0}"

TARBALL="zig-linux-x86_64-${ZIG_VERSION}.tar.xz"
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
for dir in "${candidates[@]}"; do
    if [[ -x "$dir/$EXTRACTED_DIR/zig" ]]; then
        if "$dir/$EXTRACTED_DIR/zig" version > /dev/null 2>&1; then
            if [[ "${1:-}" == "--path" || "${1:-}" == "--quiet" ]]; then
                echo "$dir/$EXTRACTED_DIR"
            else
                info "zig 已就绪: $dir/$EXTRACTED_DIR ($("$dir/$EXTRACTED_DIR/zig" version))"
            fi
            exit 0
        fi
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
CACHE="$CACHE_DIR/hmwlx-zig-${ZIG_VERSION}.tar.xz"

MIN_BYTES=40000000   # 0.13.0 的包约 44.9MB，留点余量

download_zig() {
    rm -f "$CACHE"
    for base in "${MIRRORS[@]}"; do
        url="${base%/}/${ZIG_VERSION}/${TARBALL}"
        info "下载 zig $ZIG_VERSION ← $base"
        # 单个镜像 2 次重试、单次最长 10 分钟，够慢速链路下完 44MB，
        # 又不至于卡死整条流水线。
        if curl -fL --retry 2 --retry-delay 2 --connect-timeout 15 \
                --max-time 600 -o "$CACHE.part" "$url" 2>/dev/null; then
            size="$(stat -c%s "$CACHE.part" 2>/dev/null || echo 0)"
            # 光看大小不够：镜像的 HTML 错误页也可能很大，真正的判据是
            # 能不能通过 xz 的完整性校验。
            if [[ "$size" -gt "$MIN_BYTES" ]] && xz -t "$CACHE.part" 2>/dev/null; then
                mv "$CACHE.part" "$CACHE"
                return 0
            fi
            info "  不是有效的 tar.xz（${size}B），换下一个镜像"
        else
            info "  下载失败，换下一个镜像"
        fi
        rm -f "$CACHE.part"
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
command -v xz > /dev/null 2>&1 || die "缺少 xz 命令（apt install xz-utils）
  镜像 eclipse-temurin 不带 xz，装 zig 的 stage 必须先装上。"

# 压缩包先用 xz 自己验一遍完整性。tar 展开到一半才发现坏包的话，
# 错误会伪装成文件系统问题（报一堆 truncated / unexpected EOF），
# 极难定位；单独验包能一眼看出是包坏了。
if ! xz -t "$CACHE" 2>/dev/null; then
    info "缓存里的 zig 包已损坏（多半是上次下载中途挂了），重新下载"
    download_zig || die "zig 压缩包损坏且重新下载失败。
  可以手动删掉 $CACHE 再试。"
fi

ZIG_BIN=""
for dir in "${candidates[@]}"; do
    info "探测 $dir …"
    if ! probe_dir "$dir"; then
        info "  不可用（文件系统不支持批量小文件），换下一个"
        continue
    fi

    info "  可用，解压到 $dir"
    rm -rf "${dir:?}/$EXTRACTED_DIR"
    mkdir -p "$dir"
    if tar -xJf "$CACHE" -C "$dir" 2>"$CACHE.tar.log"; then
        ZIG_BIN="$dir/$EXTRACTED_DIR/zig"
        [[ -x "$ZIG_BIN" ]] && break
        info "  解压后找不到 zig 可执行文件，换下一个"
    else
        info "  解压失败（$(grep -c 'Function not implemented' "$CACHE.tar.log" 2>/dev/null || echo 0) 个 ENOSYS），换下一个"
        tail -n 5 "$CACHE.tar.log" | sed 's/^/    /' >&2
    fi
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
    echo "" >&2
    echo "  逐个位置的结果：" >&2
    for dir in "${candidates[@]}"; do
        if probe_dir "$dir"; then
            echo "    $dir  可写，但解压失败" >&2
        else
            echo "    $dir  不可写/不支持批量小文件" >&2
        fi
    done
    echo "" >&2
    if [[ -s "$CACHE.tar.log" ]]; then
        echo "  最后一次 tar 的报错（末 10 行）：" >&2
        tail -n 10 "$CACHE.tar.log" | sed 's/^/    /' >&2
    fi
    echo "" >&2
    echo "  常见原因：" >&2
    echo "    1. 缓存里的 tar.xz 是坏的 —— 删掉 $CACHE 重试" >&2
    echo "    2. 没装 xz —— apt install xz-utils" >&2
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
