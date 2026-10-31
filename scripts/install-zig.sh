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
#   1. $HMWLX_ZIG_DIR         显式指定（CI 里可以精确控制）
#   2. $PWD/.tooling/zig      项目同目录 —— 默认首选
#   3. /tmp/hmwlx-zig         /tmp 兜底，但要小心：CNB 的 /tmp 是
#                             overlay 挂载，同样不支持批量小文件
#   4. $HOME/.local/opt       最后的兜底
#
# 为什么项目目录排第一：
#   仓库所在的盘是真实工作区的盘，一定有正常文件系统语义。
#   而且 zig 解到项目里，反复跑脚本不用重新下载解压，
#   也方便本地调试。.tooling/ 已加进 .gitignore。
#
#   /tmp 在容器里经常是 overlayfs 或内存盘，对「一次创建上千个小文件」
#   这种模式支持很差 —— CNB 上就是这样：mkdir 能过，真解压就 ENOSYS。
#
# 每个位置都会先做一次「能不能创建 13000 个文件」的快速探测：
# 建 200 个空文件试试，不行就换下一个。比解压到一半失败快得多。
#
# 注意：探测通过 ≠ 解压一定成功。CNB 上 200 个平铺文件能过，
# 但 tar 展开 lib/std 那 16000 个嵌套文件就挂了。所以探测只是
# 快速排除明显不可用的位置，真正的保险是「项目目录排第一」。
#
# ==========================================================================
# 用法
# ==========================================================================
#
#   ./scripts/install-zig.sh           装到自动选定的位置
#   ./scripts/install-zig.sh --path    只打印 zig 可执行文件路径
#
# 装完后把返回的目录加进 PATH：
#
#   ZIG_BIN_DIR="$(./scripts/install-zig.sh --path)"
#   export PATH="$ZIG_BIN_DIR:$PATH"
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 固定版本：本地验证过，不要随意漂移。
# 升版本前先在本地跑一遍 pack-windows.sh / pack-linux.sh 确认能编。
ZIG_VERSION="${ZIG_VERSION:-0.13.0}"

TARBALL="zig-linux-x86_64-${ZIG_VERSION}.tar.xz"
URL="https://ziglang.org/download/${ZIG_VERSION}/${TARBALL}"
EXTRACTED_DIR="zig-linux-x86_64-${ZIG_VERSION}"

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
# 项目同目录优先：这里的文件系统一定支持正常语义
candidates+=("$ROOT/.tooling/zig")
# /tmp 兜底但靠后：容器里常是 overlayfs，批量小文件会 ENOSYS
candidates+=("/tmp/hmwlx-zig")
candidates+=("${HOME:-/root}/.local/opt")

# ---------------------------------------------------------------- 已装好？
for dir in "${candidates[@]}"; do
    if [[ -x "$dir/$EXTRACTED_DIR/zig" ]]; then
        if "$dir/$EXTRACTED_DIR/zig" version > /dev/null 2>&1; then
            if [[ "${1:-}" == "--path" ]]; then
                echo "$dir/$EXTRACTED_DIR"
            else
                info "zig 已就绪: $dir/$EXTRACTED_DIR ($("$dir/$EXTRACTED_DIR/zig" version))"
            fi
            exit 0
        fi
    fi
done

# ---------------------------------------------------------------- 选位置
TARGET=""
for dir in "${candidates[@]}"; do
    info "探测 $dir …"
    if probe_dir "$dir"; then
        TARGET="$dir"
        info "  可用"
        break
    else
        info "  不可用（文件系统不支持批量小文件），换下一个"
    fi
done

[[ -n "$TARGET" ]] || die "所有候选位置都不可用。
  试过的：${candidates[*]}
  可以用 HMWLX_ZIG_DIR=/some/path 指定一个可写目录。"

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

if [[ -f "$CACHE" ]] && [[ "$(stat -c%s "$CACHE" 2>/dev/null || echo 0)" -gt 40000000 ]]; then
    info "使用已下载的 $CACHE"
else
    info "下载 zig $ZIG_VERSION"
    curl -fL --retry 3 --connect-timeout 30 -o "$CACHE.part" "$URL" >&2
    mv "$CACHE.part" "$CACHE"
fi

# ---------------------------------------------------------------- 解压
info "解压到 $TARGET"
mkdir -p "$TARGET"
# -xJf 里的 J 是 xz；CNB 日志显示它卡在逐个建文件上，
# 所以这里先确认 xz 命令存在，缺了会给出更明确的错误。
command -v xz > /dev/null 2>&1 || die "缺少 xz 命令（apt install xz-utils）"

tar -xJf "$CACHE" -C "$TARGET"

ZIG_BIN="$TARGET/$EXTRACTED_DIR/zig"
[[ -x "$ZIG_BIN" ]] || die "解压后找不到 $ZIG_BIN"

info "验证"
"$ZIG_BIN" version >&2

if [[ "${1:-}" == "--path" ]]; then
    echo "$TARGET/$EXTRACTED_DIR"
else
    echo ""
    echo "zig 已安装：$TARGET/$EXTRACTED_DIR"
    echo "加到 PATH："
    echo "  export PATH=\"$TARGET/$EXTRACTED_DIR:\$PATH\""
fi
