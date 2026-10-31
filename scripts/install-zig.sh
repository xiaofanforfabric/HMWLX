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
            if [[ "${1:-}" == "--path" ]]; then
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

if [[ -f "$CACHE" ]] && [[ "$(stat -c%s "$CACHE" 2>/dev/null || echo 0)" -gt 40000000 ]]; then
    info "使用已下载的 $CACHE"
else
    info "下载 zig $ZIG_VERSION"
    curl -fL --retry 3 --connect-timeout 30 -o "$CACHE.part" "$URL" >&2
    mv "$CACHE.part" "$CACHE"
fi

# ---------------------------------------------------------------- 解压
#
# 探测通过的位置也可能解压到一半 ENOSYS，所以这里逐个候选位置真试一遍：
# 失败就把半成品删掉换下一个。tar 的报错有上万行，只留最后 5 行提示。
command -v xz > /dev/null 2>&1 || die "缺少 xz 命令（apt install xz-utils）"

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

[[ -n "$ZIG_BIN" ]] || die "所有候选位置都无法解压 zig。
  试过的：${candidates[*]}
  可以用 HMWLX_ZIG_DIR=/some/path 指定一个可写目录。"

info "验证"
"$ZIG_BIN" version >&2

if [[ "${1:-}" == "--path" ]]; then
    echo "$(dirname "$ZIG_BIN")"
else
    echo ""
    echo "zig 已安装：$(dirname "$ZIG_BIN")"
    echo "加到 PATH："
    echo "  export PATH=\"$(dirname "$ZIG_BIN"):\$PATH\""
fi
