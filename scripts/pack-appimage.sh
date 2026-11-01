#!/usr/bin/env bash
#
# 打 AppImage 单文件便携版：dist/HMWLX-<版本>-x86_64.AppImage
#
# 前置：./scripts/pack-linux.sh 先产出目录版。
#
# 用法：
#   ./scripts/pack-appimage.sh
#   APPIMAGETOOL=/path/to/appimagetool ./scripts/pack-appimage.sh
#
# ------------------------------------------------------------
# AppImage 是什么
# ------------------------------------------------------------
#
# 一个 AppDir 用 squashfs 压成单个可执行文件，运行时挂载到
# /tmp/.mount_XXXX 再执行 AppRun。chmod +x 后双击即用，
# 删文件即卸载，不碰系统。
#
# AppDir 结构：
#   HMWLX.AppDir/
#   ├── AppRun           入口脚本
#   ├── hmwlx.desktop    桌面项（文件名要和 Icon= 对应）
#   ├── hmwlx.png        图标
#   └── usr/             实际内容
#
# **所有路径必须相对 AppRun**，不能写死 /opt —— 挂载点每次都变，
# 这是 AppImage 最常见的踩坑点。
#
set -euo pipefail

# shellcheck source=./pack-common.sh
source "$(dirname "$(readlink -f "$0")")/pack-common.sh"

print_fingerprint "$0"
require_srcdir
require_icon

echo "→ 构建 AppImage…"

# ------------------------------------------------------------ 找 appimagetool
#
# 查找顺序：
#   1. $APPIMAGETOOL 显式指定
#   2. PATH 里的 appimagetool（镜像里是 /usr/local/bin 的软链）
#   3. 镜像里展开好的真二进制
#   4. /tmp/appimagetool（手工下载的旧备选路径）
#
# 用展开后的 usr/bin/appimagetool 而不是 AppImage 本体：
# 容器里通常没有 FUSE，直接执行 AppImage 会失败；展开后是
# 静态链接的普通 ELF，随时能跑。
APPIMAGE_TOOL=""
for c in "${APPIMAGETOOL:-}" \
         "$(command -v appimagetool || true)" \
         /opt/appimagetool/usr/bin/appimagetool \
         /tmp/appimagetool; do
    if [[ -n "$c" && -x "$c" ]]; then
        APPIMAGE_TOOL="$c"
        break
    fi
done

if [[ -z "$APPIMAGE_TOOL" ]]; then
    echo "错误：找不到 appimagetool" >&2
    echo "  镜像里应该预置在 /opt/appimagetool/usr/bin/appimagetool。" >&2
    echo "  本地手动准备：" >&2
    echo "    curl -L -o /tmp/ait.AppImage https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage" >&2
    echo "    chmod +x /tmp/ait.AppImage && /tmp/ait.AppImage --appimage-extract" >&2
    echo "    sudo mkdir -p /opt/appimagetool && sudo cp -a squashfs-root/. /opt/appimagetool/" >&2
    exit 1
fi
echo "   使用: $APPIMAGE_TOOL"

# appimagetool 会调 mksquashfs。镜像里两份放同一目录
# （/opt/appimagetool/usr/bin），这里把该目录补进 PATH，
# 不用依赖 Dockerfile 的 ENV —— 本地直接跑脚本也能用。
APPIMAGE_TOOL_DIR="$(dirname "$APPIMAGE_TOOL")"
export PATH="${APPIMAGE_TOOL_DIR}:${PATH}"

# ------------------------------------------------------------ AppDir
APPDIR="$BUILD_ROOT/${APP_NAME}.AppDir"
rm -rf "$APPDIR"
mkdir -p "$APPDIR/usr/bin" \
         "$APPDIR/usr/share/applications" \
         "$APPDIR/usr/share/icons/hicolor/256x256/apps"

# 程序本体放进 usr/bin/HMWLX 保持自包含（含 runtime/ 和 app/）
mkdir -p "$APPDIR/usr/bin/HMWLX"
cp -r "$SRC_DIR/." "$APPDIR/usr/bin/HMWLX/"

# ------------------------------------------------------------ AppRun
#
# 用脚本而不是软链：APPDIR 是 AppImage 运行时注入的环境变量，
# 指向临时挂载点（形如 /tmp/.mount_HMWLXxxxx），每次运行都不同。
#
# 启动器是 C 写的，自己用 /proc/self/exe 定位程序目录，不依赖
# cwd，所以这里直接 exec 就行，不用 cd。
cat > "$APPDIR/AppRun" <<'APPRUN'
#!/bin/sh
#
# AppImage 入口。
#
# APPDIR 由 AppImage 运行时注入，指向本次挂载点。启动器靠
# /proc/self/exe 找同目录的 hmwlx.jar 和 runtime/，不受 cwd 影响。
SELF="$APPDIR/usr/bin/HMWLX/HMWLX"

if [ ! -x "$SELF" ]; then
    echo "错误：找不到启动器 $SELF" >&2
    exit 1
fi

exec "$SELF" "$@"
APPRUN
chmod 755 "$APPDIR/AppRun"

# ------------------------------------------------------------ 桌面项与图标
#
# 文件名必须与 Icon= 的值一致，否则桌面环境找不到图标。
cp "$ICON" "$APPDIR/${PKG_NAME}.png"
cp "$ICON" "$APPDIR/usr/share/icons/hicolor/256x256/apps/${PKG_NAME}.png"

cat > "$APPDIR/${PKG_NAME}.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=HMWLX
Name[zh_CN]=HMWLX 启动器
Comment=Minecraft launcher with bundled runtime
Comment[zh_CN]=内置运行时的 Minecraft 启动器
Exec=HMWLX
Icon=${PKG_NAME}
Terminal=false
Categories=Game;
Keywords=minecraft;launcher;
StartupWMClass=HMWLX
DESKTOP
cp "$APPDIR/${PKG_NAME}.desktop" "$APPDIR/usr/share/applications/"

# ------------------------------------------------------------ 打包
APPIMAGE_OUT="${DIST_DIR}/${APP_NAME}-${APP_VERSION}-x86_64.AppImage"
mkdir -p "$DIST_DIR"

# --no-appstream：不生成 AppStream 元数据。那需要额外的
# appstream-util 和元数据文件，对自用分发没必要；缺了它
# appimagetool 会报 warning 但不影响产物可用性。
ARCH=x86_64 "$APPIMAGE_TOOL" --no-appstream "$APPDIR" "$APPIMAGE_OUT" 2>&1 \
    | grep -vE '^(appimagetool|WARNING: AppStream)' || true

if [[ ! -f "$APPIMAGE_OUT" ]]; then
    echo "错误：未生成 $APPIMAGE_OUT" >&2
    exit 1
fi

chmod +x "$APPIMAGE_OUT"
echo "   $APPIMAGE_OUT  ($(size_of "$APPIMAGE_OUT"))"
echo "   AppImage   : chmod +x $APPIMAGE_OUT && ./$APPIMAGE_OUT"
