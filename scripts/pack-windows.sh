#!/usr/bin/env bash
#
# HMWLX Windows 便携包打包脚本
#
# 产物：dist/HMWLX-<ver>-win-x64.zip
#
# 结构：
#   HMWLX/
#   ├── HMWLX.exe      原生启动器（zig 交叉编译，~37KB）
#   ├── hmwlx.jar      fat jar（~120MB，含全部依赖）
#   └── runtime/       Windows JRE（Temurin 25，~90MB）
#
# 前置：
#   - 已执行 ./gradlew fatJar
#   - zig 位于 $ZIG 或 /tmp/zig-linux-x86_64-0.13.0/zig
#   - 需代理下载 JRE：export https_proxy=http://127.0.0.1:7890
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

VERSION="${VERSION:-1.0.0}"
OUT_DIR="dist/HMWLX-win-x64"
ZIP_OUT="dist/HMWLX-${VERSION}-win-x64.zip"
JRE_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.5%2B8/OpenJDK25U-jre_x64_windows_hotspot_25.0.5_8.zip"
JRE_CACHE="dist/cache/temurin25-win-jre.zip"

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
  echo "错误：$FAT_JAR 不存在，请先运行 ./gradlew fatJar" >&2
  exit 1
fi
echo "==> fat jar: $(du -h "$FAT_JAR" | cut -f1)"

# ---------------------------------------------------------------- 清理输出
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR" dist/cache

# ---------------------------------------------------------------- 编译启动器
echo "==> 编译 Windows 启动器"
"$ZIG" cc -target x86_64-windows-gnu -O2 -s \
  -Wl,--subsystem,windows -Wl,-e,mainCRTStartup \
  launcher/main.c -o "$OUT_DIR/HMWLX.exe"
echo "    $(ls -lh "$OUT_DIR/HMWLX.exe" | awk '{print $5}')"

# ---------------------------------------------------------------- 拷贝 jar
echo "==> 拷贝 fat jar"
cp "$FAT_JAR" "$OUT_DIR/hmwlx.jar"

# ---------------------------------------------------------------- 下载 JRE
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
mv "$JRE_DIR" "$OUT_DIR/runtime"
echo "    runtime: $(du -sh "$OUT_DIR/runtime" | cut -f1)"

# ---------------------------------------------------------------- 精简 JRE
echo "==> 精简 JRE"
rm -rf "$OUT_DIR/runtime/demo" \
       "$OUT_DIR/runtime/man" \
       "$OUT_DIR/runtime/include" \
       "$OUT_DIR/runtime/lib/src.zip" 2>/dev/null || true

# ---------------------------------------------------------------- 校验关键文件
echo "==> 校验"
for f in HMWLX.exe hmwlx.jar runtime/bin/javaw.exe runtime/bin/server/jvm.dll; do
  if [[ ! -f "$OUT_DIR/$f" ]]; then
    echo "错误：缺少 $f" >&2
    exit 1
  fi
done
echo "    所有关键文件就位"

# ---------------------------------------------------------------- 权限修正
# Gradle / mktemp 可能留下 0700 目录，解压到别的机器会导致权限异常
chmod -R a+rX "$OUT_DIR"

# ---------------------------------------------------------------- 打包 ZIP
echo "==> 打包 ZIP"
rm -f "$ZIP_OUT"
(cd dist && zip -qr9 "$(basename "$ZIP_OUT")" "HMWLX-win-x64")

echo ""
echo "=========================================="
echo " 打包完成"
echo "=========================================="
echo " 目录: $OUT_DIR  ($(du -sh "$OUT_DIR" | cut -f1))"
echo " ZIP : $ZIP_OUT ($(du -h "$ZIP_OUT" | cut -f1))"
echo ""
echo " 解压后双击 HMWLX.exe 即可运行，无需安装 Java。"
