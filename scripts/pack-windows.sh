#!/usr/bin/env bash
#
# HMWLX Windows 单文件打包脚本
#
# 产物：dist/HMWLX-<ver>-win-x64.exe   ← 一个文件，双击即用
#
# 原理：
#   launcher/sfx.c 编译出的启动器 exe，在文件末尾追加 LZMA 压缩的
#   runtime/ 和 hmwlx.jar。启动时先解压到
#     %LOCALAPPDATA%\HMWLX\app\<指纹>\
#   再启动 JVM。已解压过就直接启动（有 .extracted 标记）。
#
# 与之前 ZIP 方案的区别：
#   旧方案给用户一个 167MB 的 zip，要手动解压、还得保证三个文件
#   相对位置不变。现在是一个 exe，双击就行。
#
# 前置：
#   - 已执行 ./gradlew fatJar
#   - zig 位于 $ZIG 或 /tmp/zig-linux-x86_64-0.13.0/zig
#   - 需代理下载 JRE：export https_proxy=http://127.0.0.1:7890
#
# 用法：
#   ./scripts/pack-windows.sh             单文件 exe（默认）
#   ./scripts/pack-windows.sh --zip       额外再出一个便携 zip
#
set -euo pipefail

# 脚本在 scripts/ 下，项目根目录是它上一级
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# 版本号唯一来源：gradle.properties 的 appVersion。
# 同时提供 $FAT_JAR（根据版本拼出的构建产物路径）。
# shellcheck source=./version.sh
source "$ROOT/scripts/version.sh"
STAGE_DIR="build/win-payload"          # 待嵌入的目录（exe 内不含这个结构）
EXE_OUT="dist/HMWLX-${APP_VERSION}-win-x64.exe"
ZIP_OUT="dist/HMWLX-${APP_VERSION}-win-x64.zip"
JRE_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jre_x64_windows_hotspot_25.0.5_8.zip"
JRE_CACHE="dist/cache/temurin25-win-jre.zip"

WANT_ZIP=false
[[ "${1:-}" == "--zip" ]] && WANT_ZIP=true

# ---------------------------------------------------------------- 工具定位
ZIG="${ZIG:-}"
if [[ -z "$ZIG" ]]; then
  if command -v zig >/dev/null 2>&1; then
    ZIG="$(command -v zig)"
  elif [[ -x /tmp/zig-linux-x86_64-0.13.0/zig ]]; then
    ZIG=/tmp/zig-linux-x86_64-0.13.0/zig
  else
    echo "错误：找不到 zig。请设置 ZIG=/path/to/zig" >&2
    exit 1
  fi
fi
echo "==> zig: $ZIG ($("$ZIG" version))"

# ---------------------------------------------------------------- 依赖检查
if [[ ! -f "$FAT_JAR" ]]; then
  echo "错误：找不到 fat jar，请先运行 ./scripts/build.sh 或 ./gradlew fatJar" >&2
  exit 1
fi
echo "==> 版本: $APP_VERSION"
echo "==> fat jar: $(du -h "$FAT_JAR" | cut -f1)"

mkdir -p dist/cache

# ---------------------------------------------------------------- 编译 stub
#
# -Wl,--subsystem,windows 让产物是 GUI 子系统，双击不弹黑框。
# zig 不认 gcc 的 -mwindows（只会警告 argument unused）。
#
echo "==> 编译自解压启动器"
mkdir -p build/launcher
"$ZIG" cc -target x86_64-windows-gnu -O2 -s -Wall \
  -Wl,--subsystem,windows -Wl,-e,mainCRTStartup \
  launcher/sfx.c launcher/third_party/lzma/LzmaDec.c \
  -lcomctl32 \
  -o build/launcher/HMWLX-sfx.exe
STUB_SIZE=$(stat -c%s build/launcher/HMWLX-sfx.exe)
echo "    stub: $((STUB_SIZE / 1024)) KB"

if ! file build/launcher/HMWLX-sfx.exe | grep -q "GUI"; then
  echo "错误：产物不是 GUI 子系统，双击会弹黑框" >&2
  exit 1
fi

# ---------------------------------------------------------------- 组装载荷
echo "==> 组装载荷目录"
rm -rf "$STAGE_DIR"
mkdir -p "$STAGE_DIR"

cp "$FAT_JAR" "$STAGE_DIR/hmwlx.jar"

# ---------------------------------------------------------------- 换 skiko
#
# fat jar 里的 skiko 原生库是【构建机】平台的（compose.desktop.currentOs），
# 在 Linux 上构建就只有 libskiko-linux-x64.so。直接拿到 Windows 上会崩：
#
#   org.jetbrains.skiko.LibraryLoadException:
#     Cannot find skiko-windows-x64.dll.sha256
#
# skiko-strip.sh 会把 jar 里的原生库换成目标平台的。
#
echo "==> 替换 skiko 原生库为 Windows 版"
./scripts/skiko-strip.sh "$STAGE_DIR/hmwlx.jar" windows-x64

if [[ ! -f "$JRE_CACHE" ]] || [[ "$(stat -c%s "$JRE_CACHE")" -lt 20000000 ]]; then
  echo "==> 下载 Windows JRE (Temurin 25)"
  curl -L --fail --retry 3 -o "$JRE_CACHE.part" "$JRE_URL"
  mv "$JRE_CACHE.part" "$JRE_CACHE"
else
  echo "==> 使用缓存的 JRE ($(du -h "$JRE_CACHE" | cut -f1))"
fi

echo "==> 解压 JRE"
TMP_EXTRACT="$(mktemp -d)"
trap 'rm -rf "$TMP_EXTRACT"' EXIT
unzip -q "$JRE_CACHE" -d "$TMP_EXTRACT"

JRE_DIR="$(find "$TMP_EXTRACT" -maxdepth 1 -mindepth 1 -type d | head -1)"
if [[ -z "$JRE_DIR" ]]; then
  echo "错误：JRE 解压后找不到目录" >&2
  exit 1
fi
mv "$JRE_DIR" "$STAGE_DIR/runtime"

# 精简：demo/man/include/src.zip 运行期用不到
rm -rf "$STAGE_DIR/runtime/demo" \
       "$STAGE_DIR/runtime/man" \
       "$STAGE_DIR/runtime/include" \
       "$STAGE_DIR/runtime/lib/src.zip" 2>/dev/null || true

for f in hmwlx.jar runtime/bin/javaw.exe runtime/bin/server/jvm.dll; do
  if [[ ! -f "$STAGE_DIR/$f" ]]; then
    echo "错误：载荷缺少 $f" >&2
    exit 1
  fi
done
echo "    载荷: $(du -sh "$STAGE_DIR" | cut -f1) / $(find "$STAGE_DIR" -type f | wc -l) 个文件"

# ---------------------------------------------------------------- 合成 exe
echo "==> LZMA 压缩并追加到 exe（较慢，约 1-3 分钟）"
python3 scripts/make-sfx.py \
  --stub build/launcher/HMWLX-sfx.exe \
  --payload-dir "$STAGE_DIR" \
  --output "$EXE_OUT"

# ---------------------------------------------------------------- 可选 zip
if [[ "$WANT_ZIP" == "true" ]]; then
  echo "==> 额外生成便携 ZIP"
  ZIP_STAGE="build/win-zip/HMWLX"
  rm -rf build/win-zip
  mkdir -p "$ZIP_STAGE"
  cp build/launcher/HMWLX.exe "$ZIP_STAGE/"
  cp "$STAGE_DIR/hmwlx.jar" "$ZIP_STAGE/"
  cp -r "$STAGE_DIR/runtime" "$ZIP_STAGE/"
  chmod -R a+rX build/win-zip
  rm -f "$ZIP_OUT"
  (cd build/win-zip && zip -qr9 "$ROOT/$ZIP_OUT" "HMWLX")
  echo "    $ZIP_OUT ($(du -h "$ZIP_OUT" | cut -f1))"
fi

echo ""
echo "=========================================="
echo " 打包完成"
echo "=========================================="
echo " 单文件: $EXE_OUT"
echo " 大小  : $(du -h "$EXE_OUT" | cut -f1)"
echo ""
echo " 用户双击即可运行。首次启动会解压到"
echo "   %LOCALAPPDATA%\\HMWLX\\app\\"
echo " 之后每次直接复用，不再解压。"
