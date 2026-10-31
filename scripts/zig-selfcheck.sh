#!/usr/bin/env bash
#
# zig 安装环境自检（CI 专用）。
#
# ==========================================================================
# 为什么单独一个脚本
# ==========================================================================
#
# release-build 反复报「所有候选位置都无法解压 zig」，改了好几轮解压路径
# 都没治好。问题不在解压逻辑本身 —— 是报错把「存储不支持」和「包坏了」
# 混成一句话，谁看都只能猜。
#
# 这个脚本只做一件事：把当前环境的基本盘打印出来，再在**每个候选位置**
# 上真的解一遍 zig，逐个报结果。CI 日志里一眼就能看出是「全位置都失败」
# 还是「只有某个位置失败」，不用再猜。
#
# 注意：自检失败不直接判死刑。存储够用但只是主路径姿势不对时，后面的
# install-zig.sh 还有独立重试的机会 —— 那才是真正的结论。
#
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

ZIG_VERSION="${ZIG_VERSION:-0.13.0}"
EXPANDED="zig-linux-x86_64-${ZIG_VERSION}"
CACHE="${HMWLX_ZIG_SELFCHECK_TARBALL:-$ROOT/.tooling/cache/hmwlx-zig-${ZIG_VERSION}.tar.xz}"

info() { echo "==> $*"; }
warn() { echo "    ! $*"; }

info "环境"

# 版本号可能长达 90 字符，日志里折行会让人数不清有几层嵌套文件系统 ——
# 这恰恰是判断「是不是堆叠 overlay」的关键，所以截断。内核版本照抄。
uname -srm 2>/dev/null | cut -c1-160 || warn "uname 不可用"
echo "    用户: $(id -un 2>/dev/null || echo '?') uid=$(id -u 2>/dev/null || echo '?')"
echo "    xz: $(command -v xz || echo '缺失')  tar: $(command -v tar || echo '缺失')"
echo "    工作区: $ROOT"

info "挂载点（nested overlay 是 ENOSYS 的常见根因）"
if command -v findmnt >/dev/null 2>&1; then
    findmnt -no TARGET,FSTYPE,OPTIONS --target "$ROOT" 2>/dev/null | cut -c1-200 || true
fi
# /proc/mounts 一定存在，findmnt 缺了也能给出证据。
awk '$2 == "/" || $2 ~ /^\/(workspace|tmp|root|usr|var)/ { printf "    %-14s %-12s %s\n", $2, $3, $4 }' \
    /proc/mounts 2>/dev/null | cut -c1-200 || true

if [[ ! -f "$CACHE" ]]; then
    warn "自检用的压缩包不存在：$CACHE"
    warn "跳过逐位置解压测试（先跑 install-zig.sh 下载）"
    exit 0
fi

info "压缩包 $CACHE（$(stat -c%s "$CACHE" 2>/dev/null || echo '?') 字节）"
if command -v xz >/dev/null 2>&1 && ! xz -t "$CACHE" 2>/dev/null; then
    # 这一行比后面所有解压报错都重要：包坏了，任何位置都解不开。
    warn "xz -t 校验不通过 —— 包本身已损坏，换位置没有意义"
fi

# ---------------------------------------------------------------- 逐位置实测
#
# 只看「探测能建几个空文件」说明不了问题：探测是 200 个平铺文件，
# tar 展开的是 1.5 万个嵌套小文件。所以这里真的解一遍。
#
# 用 `tar -x` 让 tar 自己按魔数挑解压器，不写死 -J：
# 镜像里 xz 命令缺失时，-J 会直接失败，而 tar 内置的 xz 支持往往还在。
test_dir() {
    local dir="$1" log t0 t1 rc files first
    if ! mkdir -p "$dir" 2>/dev/null; then
        printf '    %-28s 建目录失败\n' "$dir"
        return
    fi
    log="$(mktemp)"
    rm -rf "${dir:?}/$EXPANDED"
    t0=$(date +%s%N)
    # -f 放最后：GNU tar 会把 --force-local 之后的内容当文件名。
    tar -x -C "$dir" -f "$CACHE" >"$log" 2>&1
    rc=$?
    t1=$(date +%s%N)
    files=$(find "$dir" -type f 2>/dev/null | wc -l)
    first="$(grep -m1 -v '^$' "$log" | cut -c1-120)"
    if [[ $rc -eq 0 ]]; then
        printf '    %-28s 成功  %s 文件  %dms\n' "$dir" "$files" $(( (t1 - t0) / 1000000 ))
    else
        printf '    %-28s 失败  %s 文件  %dms\n' "$dir" "$files" $(( (t1 - t0) / 1000000 ))
        [[ -n "$first" ]] && printf '        %s\n' "$first"
        # 只要有一个位置成功过，就能确认「存储支持批量小文件」这个前提成立。
    fi
    rm -rf "${dir:?}/$EXPANDED" 2>/dev/null
    rm -f "$log"
}

info "逐位置解压 ${EXPANDED}（15373 个小文件）"
# 顺序与 install-zig.sh 的候选一致，外加容器层，方便对照。
for d in "${HMWLX_ZIG_DIR:-}" "$ROOT/.tooling/zig" /usr/local/zig /tmp/hmwlx-zig "${HOME:-/root}/.local/opt"; do
    [[ -n "$d" ]] && test_dir "$d"
done

info "系统临时目录"
# /tmp 常和 $HOME 一起被提，实际是不是同一份存储，日志里直接看。
df -h /tmp "$ROOT" 2>/dev/null | sed 's/^/    /' || true

echo ""
echo "自检结束。若上面全部成功，说明存储能放下 zig 的小文件，"
echo "后续 install-zig.sh 失败就该往「包/下载/镜像」方向查。"
