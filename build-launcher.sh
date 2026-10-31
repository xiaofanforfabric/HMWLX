#!/usr/bin/env bash
#
# 编译 HMWLX 原生启动器（Windows / Linux）
#
# 用途：把 launcher/main.c 交叉编译成两个平台的可执行文件。
#       打包脚本（pack-*.sh）会调用它，也可以单独跑。
#
# 为什么用 zig 而不是 mingw-w64：
#   - zig 自带完整的 clang + lld + 各平台 sysroot，一条命令就能出 PE，
#     不需要 apt 装几百 MB 的 mingw 交叉工具链。
#   - zig.cc 生成的 PE 不依赖 MSVCRT 的 ucrt 版本差异，兼容性更好。
#
# 产物：
#   build/launcher/HMWLX.exe    PE32+ GUI 子系统（双击不弹黑框）
#   build/launcher/HMWLX        ELF x86-64
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

OUT="build/launcher"
SRC="launcher/main.c"

# ---------------------------------------------------------------- 定位 zig
ZIG="${ZIG:-}"
if [[ -z "$ZIG" ]]; then
    if command -v zig >/dev/null 2>&1; then
        ZIG="$(command -v zig)"
    elif [[ -x /tmp/zig-linux-x86_64-0.13.0/zig ]]; then
        ZIG=/tmp/zig-linux-x86_64-0.13.0/zig
    else
        echo "错误：找不到 zig。" >&2
        echo "  apt/snap 装不了的话，直接下压缩包：" >&2
        echo "  curl -LO https://ziglang.org/download/0.13.0/zig-linux-x86_64-0.13.0.tar.xz" >&2
        echo "  tar xf zig-linux-x86_64-0.13.0.tar.xz" >&2
        exit 1
    fi
fi

[[ -f "$SRC" ]] || { echo "错误：源码 $SRC 不存在" >&2; exit 1; }

echo "==> zig $("$ZIG" version): $ZIG"
mkdir -p "$OUT"

# ---------------------------------------------------------------- Windows
#
# -Wl,--subsystem,windows 是关键：
#   不加的话产物是 PE32+ console 子系统，双击会先弹一个黑框，
#   而且 javaw 是 GUI 程序，黑框会一直挂着。
#   注意 zig 不认 gcc 的 -mwindows 开关（会警告 argument unused），
#   必须走链接器参数。
#
# -Wl,-e,mainCRTStartup：
#   GUI 子系统下入口点默认是 WinMainCRTStartup，它要 wWinMain 符号。
#   我们写的是标准 main()，所以显式指定 C 运行时入口。
#
echo "==> Windows (x86_64-windows-gnu)"
"$ZIG" cc \
    -target x86_64-windows-gnu \
    -O2 -s -Wall \
    -Wl,--subsystem,windows \
    -Wl,-e,mainCRTStartup \
    "$SRC" -o "$OUT/HMWLX.exe"
echo "    HMWLX.exe  $(du -h "$OUT/HMWLX.exe" | cut -f1)"

# ---------------------------------------------------------------- Linux
#
# .2.31 是 glibc 版本下限：Ubuntu 20.04 起、Debian 11 起都满足。
# 不指定的话会绑当前系统的 glibc（2.35+），在老发行版上直接
# "version `GLIBC_2.34' not found"。
#
echo "==> Linux (x86_64-linux-gnu)"
"$ZIG" cc \
    -target x86_64-linux-gnu.2.31 \
    -O2 -s -Wall \
    "$SRC" -o "$OUT/HMWLX"
echo "    HMWLX      $(du -h "$OUT/HMWLX" | cut -f1)"

# ---------------------------------------------------------------- 验证
echo "==> 验证产物"
file "$OUT/HMWLX.exe" | sed 's/^/    /'
file "$OUT/HMWLX"     | sed 's/^/    /'

# GUI 子系统检查：console 子系统会让人以为启动器挂了
if file "$OUT/HMWLX.exe" | grep -q "GUI"; then
    echo "    ✓ Windows 产物为 GUI 子系统（无控制台窗口）"
else
    echo "    ✗ Windows 产物不是 GUI 子系统，双击会弹黑框" >&2
    exit 1
fi
