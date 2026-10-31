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

VERSION="${VERSION:-1.0.0}"
STAGE_DIR="build/win-payload"          # 待嵌入的目录（exe 内不含这个结构）
EXE_OUT="dist/HMWLX-${VERSION}-win-x64.exe"
ZIP_OUT="dist/HMWLX-${VERSION}-win-x64.zip"
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
FAT_JAR="build/libs/hmwlx-1.0-SNAPSHOT-all.jar"
if [[ ! -f "$FAT_JAR" ]]; then
  echo "错误：$FAT_JAR 不存在，请先运行 ./scripts/build.sh 或 ./gradlew fatJar" >&2
  exit 1
fi
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
# 这一步是必须的，否则 Windows 上直接崩：
#
#   org.jetbrains.skiko.LibraryLoadException:
#     Cannot find skiko-windows-x64.dll.sha256, proper native dependency missing.
#
# 原因：build.gradle 里写的是 compose.desktop.currentOs，
# 它按【构建机】挑平台的 skiko 原生库。我们在 Linux 上构建，
# fat jar 里只有 libskiko-linux-x64.so，Windows 上自然找不到 DLL。
#
# Compose 的 Skia 渲染必须靠这个原生库，没有它 GUI 起不来。
# 所以这里把 Windows 版的 skiko runtime 下载下来，替换掉 jar 里的
# Linux 版本 —— 顺带删掉 29MB 的 .so，包还能小一点。
#
SKIKO_VERSION="0.9.37.3"
SKIKO_WIN_CACHE="dist/cache/skiko-windows-x64-${SKIKO_VERSION}.jar"
SKIKO_WIN_URL="https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-windows-x64/${SKIKO_VERSION}/skiko-awt-runtime-windows-x64-${SKIKO_VERSION}.jar"

if [[ ! -f "$SKIKO_WIN_CACHE" ]] || [[ "$(stat -c%s "$SKIKO_WIN_CACHE")" -lt 1000000 ]]; then
  echo "==> 下载 Windows 版 skiko 原生库"
  curl -L --fail --retry 3 -o "$SKIKO_WIN_CACHE.part" "$SKIKO_WIN_URL"
  mv "$SKIKO_WIN_CACHE.part" "$SKIKO_WIN_CACHE"
else
  echo "==> 使用缓存的 skiko ($(du -h "$SKIKO_WIN_CACHE" | cut -f1))"
fi

echo "==> 替换 jar 内的 skiko 原生库（Linux -> Windows）"
SKIKO_TMP="$(mktemp -d)"
if unzip -q "$SKIKO_WIN_CACHE" -d "$SKIKO_TMP" 2>/dev/null; then
  for need in skiko-windows-x64.dll skiko-windows-x64.dll.sha256 icudtl.dat; do
    [[ -f "$SKIKO_TMP/$need" ]] || { echo "错误：skiko 包缺少 $need" >&2; exit 1; }
  done

  # 先删掉 Linux 的原生库，再把 Windows 的塞进去。
  # 注意不要动 META-INF/MANIFEST.MF —— 启动器用 -jar 启动，
  # 全靠它里面的 Main-Class。删了就会报「无法加载主类」。
  zip -qd "$STAGE_DIR/hmwlx.jar" \
      'libskiko-linux-x64.so' \
      'libskiko-linux-x64.so.sha256' 2>/dev/null || true

  ( cd "$SKIKO_TMP" && zip -q -X "$OLDPWD/$STAGE_DIR/hmwlx.jar" \
      skiko-windows-x64.dll skiko-windows-x64.dll.sha256 icudtl.dat )

  echo "    已嵌入: $(du -h "$SKIKO_TMP/skiko-windows-x64.dll" | cut -f1) DLL + $(du -h "$SKIKO_TMP/icudtl.dat" | cut -f1) icudtl"
else
  echo "错误：skiko jar 解压失败" >&2
  exit 1
fi
rm -rf "$SKIKO_TMP"

# 校验：必须有 Windows DLL，不能残留 Linux 的
if ! unzip -l "$STAGE_DIR/hmwlx.jar" | grep -q 'skiko-windows-x64.dll'; then
  echo "错误：替换后 jar 内仍无 skiko-windows-x64.dll" >&2
  exit 1
fi
if unzip -l "$STAGE_DIR/hmwlx.jar" | grep -q 'libskiko-linux-x64.so$'; then
  echo "警告：jar 内仍残留 Linux skiko 库" >&2
fi

# MANIFEST 必须还在：启动器用 -jar 启动，靠它找 Main-Class
if ! unzip -p "$STAGE_DIR/hmwlx.jar" META-INF/MANIFEST.MF 2>/dev/null | grep -qi 'Main-Class'; then
  echo "错误：jar 内 META-INF/MANIFEST.MF 缺失或没有 Main-Class" >&2
  exit 1
fi
echo "    jar 现为 $(du -h "$STAGE_DIR/hmwlx.jar" | cut -f1)"

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
