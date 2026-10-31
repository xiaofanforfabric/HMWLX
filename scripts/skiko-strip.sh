#!/usr/bin/env bash
#
# 把 fat jar 里的 skiko 原生库替换成指定平台的。
#
# 为什么需要这个东西：
#
#   build.gradle 里写的是 `compose.desktop.currentOs`，它按【构建机】
#   挑平台的 skiko 原生库。在 Linux 上构建，jar 里就只有
#   libskiko-linux-x64.so。
#
#   于是打 Windows 包时，Windows 上会崩：
#     org.jetbrains.skiko.LibraryLoadException:
#       Cannot find skiko-windows-x64.dll.sha256
#
#   打 macOS 包同理。
#
# 做法：从 Maven Central 下目标平台的 skiko-awt-runtime-<平台>，
# 用它里面的原生文件替换 jar 根目录下的旧文件。
#
# 用法：
#   ./scripts/skiko-strip.sh <fat.jar> <平台> [输出.jar]
#
# 平台取值：
#   windows-x64  windows-arm64  linux-x64  linux-arm64  macos-x64  macos-arm64
#
# 不给输出路径时原地修改。
#
# 注意：
#   - 只碰 jar 根目录下的原生库文件，不动 META-INF/MANIFEST.MF
#     （启动器用 -jar 启动，全靠它的 Main-Class）
#   - icudtl.dat 是 Skia 的 ICU 数据，Windows 和 Linux 都需要，
#     macOS 不需要（它用系统 ICU）。这里统一按「该平台包里有什么
#     就给什么」处理，不自己猜。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

JAR="${1:-}"
PLATFORM="${2:-}"
OUT_JAR="${3:-$JAR}"

if [[ -z "$JAR" || -z "$PLATFORM" ]]; then
  echo "用法: $0 <fat.jar> <平台> [输出.jar]" >&2
  echo "平台: windows-x64 windows-arm64 linux-x64 linux-arm64 macos-x64 macos-arm64" >&2
  exit 1
fi
[[ -f "$JAR" ]] || { echo "错误：$JAR 不存在" >&2; exit 1; }

# ---------------------------------------------------------------- 版本号
#
# 从 gradle 的依赖树里读，而不是写死 —— 升级 Compose 后版本会变，
# 写死的话下次就悄悄对不上了。
SKIKO_VERSION="${SKIKO_VERSION:-}"
if [[ -z "$SKIKO_VERSION" ]]; then
  SKIKO_VERSION="$(./gradlew -q :dependencies --configuration runtimeClasspath 2>/dev/null \
    | grep -oE 'org\.jetbrains\.skiko:skiko:[0-9.]+' \
    | head -1 | cut -d: -f3)"
fi
if [[ -z "$SKIKO_VERSION" ]]; then
  echo "错误：读不到 skiko 版本号，请设 SKIKO_VERSION 环境变量" >&2
  exit 1
fi
echo "==> skiko $SKIKO_VERSION / 目标平台 $PLATFORM"

# ---------------------------------------------------------------- 下载
CACHE="dist/cache"
mkdir -p "$CACHE"
SKIKO_JAR="$CACHE/skiko-$PLATFORM-$SKIKO_VERSION.jar"
SKIKO_URL="https://repo1.maven.org/maven2/org/jetbrains/skiko/skiko-awt-runtime-$PLATFORM/$SKIKO_VERSION/skiko-awt-runtime-$PLATFORM-$SKIKO_VERSION.jar"

if [[ ! -f "$SKIKO_JAR" ]] || [[ "$(stat -c%s "$SKIKO_JAR")" -lt 1000000 ]]; then
  echo "==> 下载 skiko-awt-runtime-$PLATFORM"
  curl -L --fail --retry 3 -o "$SKIKO_JAR.part" "$SKIKO_URL"
  mv "$SKIKO_JAR.part" "$SKIKO_JAR"
fi

# ---------------------------------------------------------------- 解包
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
unzip -q "$SKIKO_JAR" -d "$TMP"

# 收集该平台提供的原生文件（根目录下，跳过 META-INF）
mapfile -t NEW_FILES < <(cd "$TMP" && find . -maxdepth 1 -type f \
  \( -name '*.dll' -o -name '*.so' -o -name '*.dylib' -o -name '*.sha256' -o -name 'icudtl.dat' \) \
  -printf '%f\n' | sort)

if [[ ${#NEW_FILES[@]} -eq 0 ]]; then
  echo "错误：$SKIKO_JAR 里没找到任何原生库" >&2
  exit 1
fi
echo "    提供: ${NEW_FILES[*]}"

# ---------------------------------------------------------------- 替换
#
# 先把 jar 里所有「平台原生库 + 哈希 + icudtl」删干净，再放入目标平台的。
# 这样从 Linux 构建的 jar 切到 Windows/macOS 都不会残留。
#
# 注意 set -u 下空数组展开会报错，用 ${arr[@]+"${arr[@]}"} 兜住。
#
echo "==> 清理 jar 内旧的原生库"
OLD_FILES=()
while IFS= read -r line; do
  [[ -n "$line" ]] && OLD_FILES+=("$line")
done < <(unzip -l "$JAR" \
  | awk '{print $NF}' \
  | grep -E '^(libskiko-[^/]*\.(so|dylib)|skiko-[^/]*\.dll|icudtl\.dat)(\.sha256)?$' || true)

if [[ ${#OLD_FILES[@]} -gt 0 ]]; then
  echo "    删除: ${OLD_FILES[*]}"
  # -q 静默，-d 删除；文件不存在时 zip 会报错，用 || true 兜住
  zip -qd "$JAR" "${OLD_FILES[@]}" 2>/dev/null || true
else
  echo "    （jar 内没有原生库，跳过）"
fi

if [[ "$OUT_JAR" != "$JAR" ]]; then
  cp "$JAR" "$OUT_JAR"
fi

# zip 要在解包目录里执行（用相对文件名），所以目标路径先转成绝对路径
case "$OUT_JAR" in
  /*) ABS_OUT="$OUT_JAR" ;;
  *)  ABS_OUT="$ROOT/$OUT_JAR" ;;
esac

echo "==> 嵌入 $PLATFORM 的原生库"
( cd "$TMP" && zip -q -X "$ABS_OUT" "${NEW_FILES[@]}" )

# ---------------------------------------------------------------- 校验
fail=0
for f in "${NEW_FILES[@]}"; do
  if ! unzip -l "$OUT_JAR" | grep -qF "$f"; then
    echo "错误：$OUT_JAR 内缺少 $f" >&2
    fail=1
  fi
done

# 不应该残留别的平台
if unzip -l "$OUT_JAR" | awk '{print $NF}' \
   | grep -E '^(libskiko-[^/]*\.(so|dylib)|skiko-[^/]*\.dll)$' \
   | grep -vFf <(printf '%s\n' "${NEW_FILES[@]}") > /dev/null 2>&1; then
  echo "警告：jar 内仍有非目标平台的原生库" >&2
  unzip -l "$OUT_JAR" | awk '{print $NF}' \
    | grep -E '^(libskiko-[^/]*\.(so|dylib)|skiko-[^/]*\.dll)$' | sed 's/^/    /'
fi

# MANIFEST 必须还在，否则 -jar 启动会失败
if ! unzip -p "$OUT_JAR" META-INF/MANIFEST.MF 2>/dev/null | grep -qi 'Main-Class'; then
  echo "错误：$OUT_JAR 内 MANIFEST 缺失或没有 Main-Class" >&2
  fail=1
fi

[[ $fail -eq 0 ]] || exit 1

echo "==> 完成: $OUT_JAR ($(du -h "$OUT_JAR" | cut -f1))"
