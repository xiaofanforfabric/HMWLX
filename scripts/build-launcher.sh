#!/usr/bin/env bash
#
# 编译 HMWLX 自解压启动器（Windows / Linux）→ build/launcher/
#
# 单独跑本脚本可以只验证启动器能编过，不用等整个打包流程。
# 打包脚本（pack-windows.sh / pack-linux.sh）内部也会调用同样的
# zig 命令，所以平时不需要先跑这个。
#
# 需要 zig：./scripts/install-zig.sh 会自动装到 .tooling/zig。
#
# 为什么用 zig 而不是 mingw-w64：
#   - zig 自带完整的 clang + lld + 各平台 sysroot，一条命令出 PE，
#     不用 apt 装几百 MB 的交叉工具链。
#   - 生成的 PE 不绑特定 ucrt 版本，兼容性更好。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

OUT="build/launcher"
SRC="launcher/sfx.c"
LZMA="launcher/third_party/lzma/LzmaDec.c"

# ---------------------------------------------------------------- 定位 zig
ZIG="${ZIG:-}"
if [[ -z "$ZIG" ]]; then
    if command -v zig >/dev/null 2>&1; then
        ZIG="$(command -v zig)"
    elif [[ -x "$ROOT/.tooling/zig/zig-linux-x86_64-0.13.0/zig" ]]; then
        ZIG="$ROOT/.tooling/zig/zig-linux-x86_64-0.13.0/zig"
    else
        echo "错误：找不到 zig。" >&2
        echo "  跑 ./scripts/install-zig.sh 自动装到 .tooling/zig" >&2
        echo "  或设 ZIG=/path/to/zig" >&2
        exit 1
    fi
fi

for f in "$SRC" "$LZMA"; do
    [[ -f "$f" ]] || { echo "错误：源码 $f 不存在" >&2; exit 1; }
done

echo "==> zig $("$ZIG" version): $ZIG"
mkdir -p "$OUT"

# ---------------------------------------------------------------- Windows
#
# -Wl,--subsystem,windows 让产物是 GUI 子系统，双击不弹黑框。
# zig 不认 gcc 的 -mwindows（只会警告 argument unused），必须走链接器参数。
#
# -Wl,-e,mainCRTStartup：
#   GUI 子系统默认入口是 WinMainCRTStartup，它要 wWinMain 符号。
#   我们写的是标准 main()，所以要显式指定 C 运行时入口。
#
# -lcomctl32：进度条窗口（PROGRESS_CLASSW）来自 comctl32。
#
echo "==> Windows (x86_64-windows-gnu)"
"$ZIG" cc \
    -target x86_64-windows-gnu \
    -O2 -s -Wall \
    -Wl,--subsystem,windows \
    -Wl,-e,mainCRTStartup \
    "$SRC" "$LZMA" \
    -lcomctl32 \
    -o "$OUT/HMWLX.exe"
echo "    HMWLX.exe  $(du -h "$OUT/HMWLX.exe" | cut -f1)"

# ---------------------------------------------------------------- Linux
#
# .2.31 是 glibc 下限：Ubuntu 20.04 起、Debian 11 起都满足。
# 不指定会绑当前系统的 glibc（2.35+），老发行版直接
# "version `GLIBC_2.34' not found"。
#
echo "==> Linux (x86_64-linux-gnu)"
"$ZIG" cc \
    -target x86_64-linux-gnu.2.31 \
    -O2 -s -Wall \
    "$SRC" "$LZMA" \
    -o "$OUT/HMWLX-linux"
chmod 755 "$OUT/HMWLX-linux"
echo "    HMWLX-linux  $(du -h "$OUT/HMWLX-linux" | cut -f1)"

# ---------------------------------------------------------------- 验证
echo "==> 验证产物"
file "$OUT/HMWLX.exe"    | sed 's/^/    /'
file "$OUT/HMWLX-linux"  | sed 's/^/    /'

if file "$OUT/HMWLX.exe" | grep -q "GUI"; then
    echo "    ✓ Windows 产物为 GUI 子系统（无控制台窗口）"
else
    echo "    ✗ Windows 产物不是 GUI 子系统，双击会弹黑框" >&2
    exit 1
fi

if file "$OUT/HMWLX-linux" | grep -q "ELF 64-bit"; then
    echo "    ✓ Linux 产物为 ELF x86-64"
else
    echo "    ✗ Linux 产物格式异常" >&2
    exit 1
fi

echo ""
echo "完成。产物在 $OUT/"
