#!/usr/bin/env bash
#
# 打 Debian/Ubuntu 安装包：dist/hmwlx_<版本>_amd64.deb
#
# 前置：./scripts/pack-linux.sh 先产出目录版。
#
# 用法：
#   ./scripts/pack-deb.sh
#
# ------------------------------------------------------------
# 包内布局
# ------------------------------------------------------------
#
#   /opt/hmwlx/                     程序本体（自带 JRE，不碰系统 Java）
#   /usr/bin/hmwlx                  软链 → /opt/hmwlx/HMWLX
#   /usr/share/applications/…      桌面菜单项
#   /usr/share/icons/hicolor/…      图标
#
# 装到 /opt 而不是 /usr/lib：/opt 是「第三方自包含软件」的规范位置。
#
# ------------------------------------------------------------
# 为什么不用 dpkg-deb
# ------------------------------------------------------------
#
# dpkg-deb --build 内部执行 `tar -cf - -C <root> .`。在 CNB 上
# /workspace 是 OverlayFS，遍历 runtime/ 几百个文件会报：
#
#   tar: ./opt/hmwlx/runtime/lib/server/libjvm.so:
#        Cannot stat: Function not implemented
#
# 我们改不了 dpkg-deb 内部怎么调 tar，所以整个打包过程改用
# scripts/make-deb.py（纯 Python：tarfile 生成两个 tar.xz，
# 手写 ar 头拼成 .deb），全程进程内完成，不 fork tar/ar。
#
set -euo pipefail

# shellcheck source=./pack-common.sh
source "$(dirname "$(readlink -f "$0")")/pack-common.sh"

print_fingerprint "$0"
require_srcdir
require_icon
require_cmd python3

echo "→ 构建 DEB…"

DEB_ROOT="$(pack_stage_base)/deb-root"
rm -rf "$DEB_ROOT"
mkdir -p "$DEB_ROOT/DEBIAN" \
         "$DEB_ROOT/opt/${PKG_NAME}" \
         "$DEB_ROOT/usr/bin" \
         "$DEB_ROOT/usr/share/applications" \
         "$DEB_ROOT/usr/share/icons/hicolor"

# ------------------------------------------------------------ 程序本体
cp -r "$SRC_DIR/." "$DEB_ROOT/opt/${PKG_NAME}/"

# ------------------------------------------------------------ 命令行入口
ln -sf "/opt/${PKG_NAME}/HMWLX" "$DEB_ROOT/usr/bin/${PKG_NAME}"

# ------------------------------------------------------------ 桌面菜单项
#
# Path= 指向 /opt/<pkg>：程序要读同目录的 runtime/ 和 app/，
# 不设的话从菜单启动时 cwd 可能是 /，相对路径全失效。
cat > "$DEB_ROOT/usr/share/applications/${PKG_NAME}.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=HMWLX
Name[zh_CN]=HMWLX 启动器
GenericName=Minecraft Launcher
Comment=启动 Minecraft（便携版，内置运行时）
Comment[zh_CN]=启动 Minecraft（便携版，内置运行时）
Exec=/opt/${PKG_NAME}/HMWLX
Path=/opt/${PKG_NAME}
Icon=${PKG_NAME}
Terminal=false
Categories=Game;
Keywords=minecraft;launcher;game;
StartupNotify=true
StartupWMClass=HMWLX
DESKTOP
chmod 644 "$DEB_ROOT/usr/share/applications/${PKG_NAME}.desktop"

# ------------------------------------------------------------ 图标
if command -v convert >/dev/null 2>&1; then
    for size in 16 32 48 64 128 256; do
        dir="$DEB_ROOT/usr/share/icons/hicolor/${size}x${size}/apps"
        mkdir -p "$dir"
        convert "$ICON" -resize "${size}x${size}" \
                "$dir/${PKG_NAME}.png" 2>/dev/null || true
    done
else
    # 没有 ImageMagick 就只放原始尺寸 —— 能用，只是某些尺寸下不够清晰。
    dir="$DEB_ROOT/usr/share/icons/hicolor/256x256/apps"
    mkdir -p "$dir"
    cp "$ICON" "$dir/${PKG_NAME}.png"
fi

# ------------------------------------------------------------ control
#
# Installed-Size 单位是 KB，dpkg 用它估算占用。
INSTALLED_SIZE=$(du -sk "$DEB_ROOT/opt" | cut -f1)

# Depends 只写真正必须的四个：
#   libx11-6         X11 客户端库（窗口系统）
#   libfontconfig1   字体配置
#   libfreetype6     字体渲染
#   libgl1           OpenGL —— Skiko 即使软件渲染也要链接它
#
# 这些是 libskiko-linux-x64.so 的 ldd 结果里、包内没提供的。
# 不声明的话，精简系统上 dpkg -i 能成功，但一运行就
#   UnsatisfiedLinkError: libGL.so.1: cannot open shared object file
# 而且发生在 Compose 初始化阶段，堆栈很深，用户看不出是缺系统库。
#
# 不依赖 libglu1-mesa / libglx-mesa0：由桌面环境按需装，
# 强依赖会让服务器的安装变得臃肿。
cat > "$DEB_ROOT/DEBIAN/control" <<CONTROL
Package: ${PKG_NAME}
Version: ${DEB_VERSION}
Section: games
Priority: optional
Architecture: amd64
Installed-Size: ${INSTALLED_SIZE}
Depends: libx11-6, libfontconfig1, libfreetype6, libgl1
Maintainer: ${MAINTAINER}
Homepage: ${HOMEPAGE}
Description: ${DESCRIPTION}
 一个基于 headlessmc 的 Minecraft 启动器，内置 Java 运行时。
 用户无需单独安装 Java，安装后可从应用菜单启动。
CONTROL

# ------------------------------------------------------------ 维护脚本
cat > "$DEB_ROOT/DEBIAN/postinst" <<'HOOK'
#!/bin/sh
set -e
command -v update-desktop-database >/dev/null && update-desktop-database -q /usr/share/applications || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
exit 0
HOOK
cat > "$DEB_ROOT/DEBIAN/postrm" <<'HOOK'
#!/bin/sh
set -e
command -v update-desktop-database >/dev/null && update-desktop-database -q /usr/share/applications || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
exit 0
HOOK
chmod 755 "$DEB_ROOT/DEBIAN/postinst" "$DEB_ROOT/DEBIAN/postrm"

# 权限：DEBIAN 目录必须 755，control 644，否则 dpkg 会警告
chmod 755 "$DEB_ROOT/DEBIAN"
chmod 644 "$DEB_ROOT/DEBIAN/control"

# ------------------------------------------------------------ 打包
DEB_OUT="${DIST_DIR}/${PKG_NAME}_${DEB_VERSION}_amd64.deb"
mkdir -p "$DIST_DIR"

# 纯 Python 组装：不 fork dpkg-deb / tar / ar。
# 详见 scripts/make-deb.py 顶部注释。
python3 "$PWD/scripts/make-deb.py" \
    --root "$DEB_ROOT" \
    --output "$DEB_OUT" \
    --compression xz

if [[ ! -f "$DEB_OUT" ]]; then
    echo "错误：未生成 $DEB_OUT" >&2
    exit 1
fi

echo "   $DEB_OUT  ($(size_of "$DEB_OUT"))"
echo "   DEB 安装   : sudo apt install ./$DEB_OUT"
echo "   DEB 卸载   : sudo apt remove ${PKG_NAME}"
