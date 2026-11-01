#!/usr/bin/env bash
#
# 下载并安装 appimagetool 到指定目录。
#
# 谁在用：
#   - docker/build-env.Dockerfile（CNB 构建镜像时）
#   - .github/workflows/*.yml（GitHub 托管 runner 上，没有我们的镜像）
#
# 为什么不直接用 AppImage：
#   容器 / 精简 runner 里通常没有 FUSE，执行 AppImage 会失败。
#   用 --appimage-extract 展开后是静态链接的普通 ELF，随时能跑。
#
# 为什么必须保留整个 usr/bin：
#   appimagetool 会调同目录的 mksquashfs；官方 AppRun 也是靠
#   $this_dir/usr/bin 定位的。只拷一个二进制跑不起来。
#
# 用法：
#   ./scripts/install-appimagetool.sh [目标目录]
#
# 默认装到 /opt/appimagetool。装完会：
#   - 软链到 /usr/local/bin/appimagetool（如果在 PATH 里）
#   - 打印版本号
#
# 目标目录要可写。GitHub runner 上 /opt 需要 sudo，所以那边传
# $HOME/.local/appimagetool。
#
set -euo pipefail

DEST="${1:-/opt/appimagetool}"
URL="${APPIMAGETOOL_URL:-https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage}"

echo "==> 安装 appimagetool 到 $DEST"

# 已经装好就跳过（CI 里会重复调用）
if [[ -x "$DEST/usr/bin/appimagetool" && -x "$DEST/usr/bin/mksquashfs" ]]; then
    echo "    已存在，跳过下载"
    "$DEST/usr/bin/appimagetool" --version
    exit 0
fi

TMP_APPIMAGE="$(mktemp -d)/appimagetool.AppImage"
TMP_EXTRACT="$(mktemp -d)"
cleanup() { rm -rf "$(dirname "$TMP_APPIMAGE")" "$TMP_EXTRACT"; }
trap cleanup EXIT

echo "    下载: $URL"
# --retry：GitHub Releases 的 302 经常中途断
curl -fsSL --retry 3 --retry-delay 5 --connect-timeout 20 \
     -o "$TMP_APPIMAGE" "$URL"

chmod +x "$TMP_APPIMAGE"

echo "    展开（容器里没 FUSE，必须 --appimage-extract）"
( cd "$TMP_EXTRACT" && "$TMP_APPIMAGE" --appimage-extract >/dev/null )

mkdir -p "$DEST"
cp -a "$TMP_EXTRACT/squashfs-root/." "$DEST/"

# 自检：两个二进制都必须可执行，光有 appimagetool 不够
for f in usr/bin/appimagetool usr/bin/mksquashfs; do
    if [[ ! -x "$DEST/$f" ]]; then
        echo "错误：$DEST/$f 不存在或不可执行" >&2
        exit 1
    fi
done

# 尽量软链到 PATH 里；没权限就算了（调用方会用绝对路径）
if [[ -w /usr/local/bin ]] 2>/dev/null; then
    ln -sf "$DEST/usr/bin/appimagetool" /usr/local/bin/appimagetool 2>/dev/null || true
fi

echo "    完成："
"$DEST/usr/bin/appimagetool" --version | sed 's/^/      /'
