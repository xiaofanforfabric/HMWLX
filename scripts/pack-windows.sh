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

# 构建工作区（CNB 上是 data 卷 /build，本地默认 <仓库>/build）。
# 详见 scripts/build-env.sh 顶部关于 OverlayFS 的说明。
# shellcheck source=./build-env.sh
source "$ROOT/scripts/build-env.sh"

# 待嵌入的目录（exe 内不含这个结构）。
# make-sfx.py 要遍历这几百个文件算 LZMA 索引，放在 OverlayFS 上会
# 报 ENOSYS（dpkg-deb 就是这么挂的），所以放构建区。
STAGE_DIR="$WIN_PAYLOAD_DIR"
EXE_OUT="dist/HMWLX-${APP_VERSION}-win-x64.exe"
ZIP_OUT="dist/HMWLX-${APP_VERSION}-win-x64.zip"
JRE_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jre_x64_windows_hotspot_25.0.5_8.zip"
# 下载缓存也放构建区：56MB 的 zip，没必要在工作区留一份
JRE_CACHE="$CACHE_DIR/temurin25-win-jre.zip"

WANT_ZIP=false
[[ "${1:-}" == "--zip" ]] && WANT_ZIP=true

# ---------------------------------------------------------------- 选择编译器
#
# 优先 mingw-w64，zig 只作后备。
#
# 为什么不再默认用 zig：
#   zig 的发行包解出来是 16000+ 个小文件。CI 环境（CNB）的存储对
#   「tar 展开上万个文件」会报 Function not implemented (ENOSYS)，
#   而 apt 装包（dpkg 路径）却完全正常 —— 试过六轮修补 tar 那一侧
#   （换目录、换源、稀疏解压、分段写、docker build）全部无效。
#
#   sfx.c 只是普通 C（标准头 + windows.h + unistd.h + LZMA SDK），
#   没有任何 zig 语言特性，用 mingw-w64 编出来的 PE 一样能用。
#   apt 装 mingw-w64 走 dpkg，绕开了 tar 那个坑。
#
# 用 zig 的条件：显式设了 ZIG，或本地没装 mingw 但 .tooling/zig 在。
#
CROSS_CC=""
if [[ -n "${ZIG:-}" ]] && [[ -x "${ZIG}" || -n "$(command -v "$ZIG" 2>/dev/null)" ]]; then
  CROSS_CC="$ZIG"
  COMPILER_KIND=zig
elif command -v x86_64-w64-mingw32-gcc >/dev/null 2>&1; then
  CROSS_CC="x86_64-w64-mingw32-gcc"
  COMPILER_KIND=mingw
elif [[ -n "${CC_WINDOWS:-}" ]] && command -v "$CC_WINDOWS" >/dev/null 2>&1; then
  CROSS_CC="$CC_WINDOWS"
  COMPILER_KIND=mingw
elif _zd="$(bash scripts/install-zig.sh --quiet 2>/dev/null)" && [[ -x "$_zd/zig" ]]; then
  CROSS_CC="$_zd/zig"
  COMPILER_KIND=zig
else
  cat >&2 <<'EOF'
错误：找不到能交叉编译 Windows 产物的编译器
  首选：apt-get install -y mingw-w64
        （提供 x86_64-w64-mingw32-gcc）
  备选：设置 ZIG=/path/to/zig
EOF
  exit 1
fi

# ---------------------------------------------------------------- 依赖检查
if [[ ! -f "$FAT_JAR" ]]; then
  echo "错误：找不到 fat jar，请先运行 ./scripts/build.sh 或 ./gradlew fatJar" >&2
  exit 1
fi
echo "==> 版本: $APP_VERSION"
echo "==> fat jar: $(du -h "$FAT_JAR" | cut -f1)"
echo "==> 编译器($COMPILER_KIND): $CROSS_CC"

mkdir -p dist/cache

# ---------------------------------------------------------------- 编译 stub
#
# Windows GUI 子系统：双击不弹黑框。两种编译器的写法不同：
#   mingw: -mwindows（同时决定子系统 + 入口点）
#   zig:   zig 不认 -mwindows，必须显式给链接器参数
#
# -s 去符号表，产物从 ~300KB 降到 ~60KB。
#
echo "==> 编译自解压启动器"

SRCS=(launcher/sfx.c launcher/third_party/lzma/LzmaDec.c)

if [[ "$COMPILER_KIND" == "zig" ]]; then
  "$CROSS_CC" cc -target x86_64-windows-gnu -O2 -s -Wall \
    -Wl,--subsystem,windows -Wl,-e,mainCRTStartup \
    "${SRCS[@]}" \
    -lcomctl32 \
    -o "$LAUNCHER_DIR/HMWLX-sfx.exe"
else
  # mingw-w64
  #
  # -municode 不需要：我们的入口是普通 main()，mingw 默认就能处理。
  # -static-libgcc：别把 libgcc 的 DLL 依赖带进产物，
  #   否则用户机器上少个 DLL 就启动不了。
  "$CROSS_CC" -O2 -s -Wall -Wno-format-truncation -mwindows \
    -static-libgcc \
    "${SRCS[@]}" \
    -lcomctl32 \
    -o "$LAUNCHER_DIR/HMWLX-sfx.exe"
fi

STUB="$LAUNCHER_DIR/HMWLX-sfx.exe"
STUB_SIZE=$(stat -c%s "$STUB")
echo "    stub: $((STUB_SIZE / 1024)) KB"

if ! file "$STUB" | grep -q "GUI"; then
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
#
# make-sfx.py 用到 python3 的 lzma 模块（标准库）。缺 python3 时
# 报错必须发生在压缩之前 —— 否则前面下载 JRE、组装载荷白干几分钟，
# 最后才在 'python3: command not found' 上失败。
command -v python3 >/dev/null 2>&1 || {
  echo "错误：找不到 python3（合成自解压 exe 需要它）" >&2
  echo "  CI 镜像：检查 docker/build-env.Dockerfile 是否装了 python3" >&2
  echo "  本地：apt-get install -y python3" >&2
  exit 1
}
echo "==> LZMA 压缩并追加到 exe（较慢，约 1-3 分钟）"
python3 scripts/make-sfx.py \
  --stub "$STUB" \
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
