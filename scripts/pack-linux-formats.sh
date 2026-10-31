#!/usr/bin/env bash
#
# 把 dist/HMWLX-linux-x64（pack-linux.sh 的产物）打成两种**便携/可分发**格式。
#
# 产出：
#   dist/hmwlx_<版本>_amd64.deb        Debian/Ubuntu 安装包
#   dist/hmwlx-<版本>-1.x86_64.rpm     Fedora/RHEL/openSUSE 安装包
#   dist/HMWLX-<版本>-x86_64.AppImage  单文件便携版
#
# 前置：先跑 ./scripts/pack-linux.sh
#
# 用法：
#   ./scripts/pack-linux-formats.sh
#   ./scripts/pack-linux-formats.sh --deb-only
#   ./scripts/pack-linux-formats.sh --rpm-only
#   ./scripts/pack-linux-formats.sh --appimage-only
#
# ---------------------------------------------------------------------------
# 两种格式的定位差异（这不是可选项，是用户预期）
# ---------------------------------------------------------------------------
#
# DEB = 安装包。用 apt install ./xxx.deb 装（别用 dpkg -i，那个不处理
# 依赖也不回滚，缺依赖时包会卡在「已解包未配置」状态）。进系统菜单，
# 能 apt remove 卸载。
#   适合"我愿意让它进系统"的用户。
#
# AppImage = 单文件便携。chmod +x 后双击运行，删文件即卸载。
#   适合"我不想让它碰我的系统"的用户，以及 U 盘/共享机场景。
#
# 两个都要：前者是发行版生态的规范做法，后者是"便携"这一诉求的直接答案。
#
set -euo pipefail

# 脚本在 scripts/ 下，项目根目录是它上一级
cd "$(dirname "$(readlink -f "$0")")/.."

APP_NAME="HMWLX"
PKG_NAME="hmwlx"          # DEB 包名必须全小写（Debian 政策）

# 版本号唯一来源：gradle.properties 的 appVersion。
# 提供 APP_VERSION（原始，如 0.1-dev）、
#     DEB_VERSION（0.1~dev，Debian 用 ~ 表示预发布，排序低于正式版）、
#     RPM_VERSION（0.1.dev，RPM 版本字段不允许连字符）。
# shellcheck source=./version.sh
source "$PWD/scripts/version.sh"

MAINTAINER="xiaofan <xiaofan@cn.xiaofan>"
HOMEPAGE="https://cnb.cool/xiaofanforandroid/HMWLX/HMWLX"
DESCRIPTION="Minecraft 启动器（HMWLX）"

SRC_DIR="dist/${APP_NAME}-linux-x64"
DIST_DIR="dist"
ICON="src/main/resources/images/icon-release.png"

DO_DEB=true
DO_RPM=true
DO_APPIMAGE=true
case "${1:-}" in
    --deb-only)       DO_RPM=false; DO_APPIMAGE=false ;;
    --rpm-only)       DO_DEB=false; DO_APPIMAGE=false ;;
    --appimage-only)  DO_DEB=false; DO_RPM=false ;;
esac

# ---------------------------------------------------------------------------
# 前置检查
# ---------------------------------------------------------------------------
if [[ ! -d "$SRC_DIR" ]]; then
    echo "错误：找不到 $SRC_DIR" >&2
    echo "请先运行 ./scripts/pack-linux.sh" >&2
    exit 1
fi

if [[ ! -f "$ICON" ]]; then
    echo "错误：找不到图标 $ICON" >&2
    exit 1
fi

APPIMAGE_TOOL=""
for c in "${APPIMAGETOOL:-}" /tmp/appimagetool "$(command -v appimagetool || true)"; do
    if [[ -n "$c" && -x "$c" ]]; then APPIMAGE_TOOL="$c"; break; fi
done

# ===========================================================================
# 1. DEB
#
# 结构（Debian 政策）：
#   hmwlx_${DEB_VERSION}_amd64/
#   ├── DEBIAN/control            元数据
#   ├── DEBIAN/postinst           装完后刷新桌面数据库
#   ├── DEBIAN/postrm             卸载后刷新
#   ├── opt/hmwlx/                程序本体（整份便携目录）
#   └── usr/
#       ├── bin/hmwlx             命令行入口（软链到 /opt/hmwlx/HMWLX）
#       ├── share/applications/   桌面菜单项
#       └── share/icons/hicolor/  图标（多尺寸）
# ===========================================================================
# ---------------------------------------------------------------------------
# 打包暂存目录放哪
#
# 不放 build/ 而放 $TMPDIR（通常是 /tmp）：
#   dpkg-deb 和 rpmbuild 内部都要用 tar 遍历整个暂存树（runtime/ 有
#   几百个文件）。CI（CNB）的工作区存储对这个操作报
#     tar: ./opt/hmwlx/runtime/bin/java: Cannot stat: Function not implemented
#   而同样的操作在容器本地盘（/tmp）完全正常 —— 和之前 zig 解压、
#   zip 更新大 jar 是同一个存储限制。
#
# 所以：暂存全在 $TMPDIR 里做，做好的 .deb/.rpm 再拷回 dist/。
# 这样工作区只承载最终产物（几个大文件），不承载「上万次小文件操作」。
#
STAGE_BASE="${TMPDIR:-/tmp}/hmwlx-stage-$$"
mkdir -p "$STAGE_BASE"
# 退出时清理暂存目录（几百 MB 的 runtime 副本，别留在 /tmp 里堆着）
cleanup_stage() { rm -rf "$STAGE_BASE"; }
trap cleanup_stage EXIT

if [[ "$DO_DEB" == "true" ]]; then
    echo "→ 构建 DEB…"
    if ! command -v dpkg-deb >/dev/null; then
        echo "  跳过：系统没有 dpkg-deb" >&2
    else
        DEB_ROOT="$STAGE_BASE/deb-root"
        rm -rf "$DEB_ROOT"
        mkdir -p "$DEB_ROOT/DEBIAN" \
                 "$DEB_ROOT/opt/${PKG_NAME}" \
                 "$DEB_ROOT/usr/bin" \
                 "$DEB_ROOT/usr/share/applications" \
                 "$DEB_ROOT/usr/share/icons/hicolor"

        # --- 程序本体 ---
        # 装到 /opt 而不是 /usr/lib：/opt 是"第三方自包含软件"的规范位置，
        # 里面自带 JRE，不与系统 Java 冲突。
        cp -r "$SRC_DIR/." "$DEB_ROOT/opt/${PKG_NAME}/"

        # --- 命令行入口 ---
        ln -sf "/opt/${PKG_NAME}/HMWLX" "$DEB_ROOT/usr/bin/${PKG_NAME}"

        # --- 桌面菜单项 ---
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

        # --- 图标（多尺寸，桌面环境各自挑合适的）---
        if command -v convert >/dev/null; then
            for size in 16 32 48 64 128 256; do
                dir="$DEB_ROOT/usr/share/icons/hicolor/${size}x${size}/apps"
                mkdir -p "$dir"
                convert "$ICON" -resize "${size}x${size}" "$dir/${PKG_NAME}.png" 2>/dev/null || true
            done
        else
            # 没有 ImageMagick 就只放原始尺寸 —— 能用，只是某些尺寸下不够清晰。
            dir="$DEB_ROOT/usr/share/icons/hicolor/256x256/apps"
            mkdir -p "$dir"
            cp "$ICON" "$dir/${PKG_NAME}.png"
        fi

        # --- 控制信息 ---
        # Installed-Size 单位是 KB，dpkg 用它估算占用。
        INSTALLED_SIZE=$(du -sk "$DEB_ROOT/opt" | cut -f1)
        #
        # Depends 是实际必须的：
        #   libskiko-linux-x64.so 用 ldd 查出来依赖 libX11 / libfontconfig /
        #   libfreetype / libGL 等。这些不在包内 —— 我们只打包了 JRE 和
        #   jar，原生图形库靠系统提供。
        #
        #   不声明的话，在一台精简系统（比如 docker 基础镜像、最小化
        #   安装的服务器）上 dpkg -i 能成功，但一运行就
        #     UnsatisfiedLinkError: libGL.so.1: cannot open shared object file
        #   而且报错发生在 Compose 初始化阶段，堆栈很深，用户根本看不出
        #   是缺系统库。
        #
        # 只写真正必须的四个，别贪多：
        #   libx11-6          X11 客户端库（窗口系统）
        #   libfontconfig1    字体配置
        #   libfreetype6      字体渲染
        #   libgl1            OpenGL —— Skiko 即使软件渲染也要链接它
        #
        # 不依赖 libglu1-mesa、libglx-mesa0 这些：它们由桌面环境按需装，
        # 强依赖会让服务器上的安装变得臃肿。
        #
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

        # --- 安装/卸载钩子 ---
        # 只是刷新桌面数据库，让菜单项立刻出现/消失。
        # 用 `|| true` 兜底：某些精简系统没有这些工具，不该因此安装失败。
        cat > "$DEB_ROOT/DEBIAN/postinst" <<'HOOK'
#!/bin/sh
set -e
if [ "$1" = "configure" ]; then
    command -v update-desktop-database >/dev/null && update-desktop-database -q /usr/share/applications || true
    command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
fi
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

        DEB_OUT="${DIST_DIR}/${PKG_NAME}_${DEB_VERSION}_amd64.deb"
        dpkg-deb --root-owner-group -Zxz --build "$DEB_ROOT" "$DEB_OUT" >/dev/null
        echo "   $DEB_OUT  ($(du -h "$DEB_OUT" | cut -f1))"
    fi
fi

# ===========================================================================
# 2. RPM
#
# 目标发行版：Fedora / RHEL / CentOS Stream / Rocky / AlmaLinux / openSUSE
#
# 与 DEB 的关键差异（不是照抄就能用的）：
#
#   1. 路径惯例不同。RPM 系习惯把第三方软件放 /usr/lib/<name> 或
#      /opt，但**/opt 在 RHEL 系上是给 ISV 的**，包管理器不管理其中内容。
#      这里仍选 /opt/hmwlx：内置 JRE 不与应用拆开，符合 /opt 的语义，
#      且和 DEB 保持一致的目录结构，减少两套包的行为差异。
#
#   2. 不允许 BuildRoot 里出现未声明的文件。spec 的 %files 必须逐项列出，
#      漏一个就构建失败；多一个也失败。用 %{_libexecdir} 之类宏反而
#      容易和实际路径对不上，所以直接写绝对路径。
#
#   3. 需要 %post / %postun 刷新桌面数据库，和 DEB 的 postinst/postrm 对应。
#
#   4. openSUSE 用 rpm 但不认 dnf，%post 里调用的 update-desktop-database
#      要加 || true 兜底。
# ===========================================================================
if [[ "$DO_RPM" == "true" ]]; then
    echo "→ 构建 RPM…"
    if ! command -v rpmbuild >/dev/null; then
        echo "  跳过：系统没有 rpmbuild（apt install rpm / dnf install rpm-build）" >&2
    else
        RPM_TOP="$STAGE_BASE/rpmbuild"
        rm -rf "$RPM_TOP"
        mkdir -p "$RPM_TOP"/{BUILD,RPMS,SOURCES,SPECS,SRPMS,BUILDROOT}

        # --- 准备源码目录（不是 BUILDROOT）---
        #
        # 关键：rpmbuild 会在 %install 之前**清空 BUILDROOT**。
        # 预先往 BUILDROOT 里塞文件等于白做 —— 会报
        # `cp: cannot stat '.../BUILDROOT/.'`。
        #
        # 正确做法是：把要安装的内容打成 Source tar 包，
        # 在 %install 里解到 %{buildroot}。这也是 RPM 的标准范式。
        RPM_STAGE="$RPM_TOP/stage/${PKG_NAME}-${RPM_VERSION}"
        rm -rf "$RPM_TOP/stage"
        mkdir -p "$RPM_STAGE/opt/${PKG_NAME}" \
                 "$RPM_STAGE/usr/bin" \
                 "$RPM_STAGE/usr/share/applications" \
                 "$RPM_STAGE/usr/share/icons/hicolor/256x256/apps"

        cp -r "$SRC_DIR/." "$RPM_STAGE/opt/${PKG_NAME}/"
        ln -sf "/opt/${PKG_NAME}/HMWLX" "$RPM_STAGE/usr/bin/${PKG_NAME}"

        # 桌面项与 DEB 完全相同（Exec/Path/Icon 都对得上 /opt 布局）
        cat > "$RPM_STAGE/usr/share/applications/${PKG_NAME}.desktop" <<DESKTOP
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
        chmod 644 "$RPM_STAGE/usr/share/applications/${PKG_NAME}.desktop"

        cp "$ICON" "$RPM_STAGE/usr/share/icons/hicolor/256x256/apps/${PKG_NAME}.png"

        # 打 Source tar 包
        tar -C "$RPM_TOP/stage" -czf "$RPM_TOP/SOURCES/${PKG_NAME}-${RPM_VERSION}.tar.gz" \
            "${PKG_NAME}-${RPM_VERSION}"

        # --- spec ---
        # BuildArch: x86_64 —— 内置的 JRE 是平台相关的，不能标 noarch。
        # %install 里什么都不做：文件已经在 BUILDROOT 里摆好了。
        # 这是"预构建产物"打包的标准做法（类似 Go/Rust 二进制的打包方式）。
        cat > "$RPM_TOP/SPECS/${PKG_NAME}.spec" <<SPEC
Name:           ${PKG_NAME}
Version:        ${RPM_VERSION}
Release:        1%{?dist}
Summary:        ${DESCRIPTION}
License:        Proprietary
URL:            ${HOMEPAGE}
BuildArch:      x86_64
AutoReqProv:    no
# AutoReqProv: no 关掉了自动依赖探测（它会把包内 JRE 的 .so 当依赖，
# 产生一堆不存在的包名）。所以系统库必须手写。
#
# 这些是 libskiko-linux-x64.so 用 ldd 查出来的硬依赖：
#   libX11 X11 客户端库、fontconfig/freetype 字体、
#   mesa-libGL OpenGL（Skiko 即使软件渲染也要链接）。
# 不声明的话，精简系统上装完一跑就 UnsatisfiedLinkError。
Requires:       libX11
Requires:       fontconfig
Requires:       freetype
Requires:       mesa-libGL
Source0:        %{name}-%{version}.tar.gz

%description
一个基于 headlessmc 的 Minecraft 启动器，内置 Java 运行时。
用户无需单独安装 Java，安装后可从应用菜单启动。

%prep
# 解包预构建产物（里面是按最终路径摆好的目录树）。
# -n 显式指定解包后的目录名，不依赖 rpm 的默认推导
# （默认是 %{name}-%{version}，和我们生成的 tar 一致，写出来更明确）。
%setup -q -n %{name}-%{version}

%build
# 预构建产物，无需编译

%install
# 把解包出来的整棵目录树拷进 buildroot。
# 用 -a 保留符号链接（/usr/bin/hmwlx 是软链）。
cp -a . %{buildroot}/

%post
if [ -x /usr/bin/update-desktop-database ]; then
    /usr/bin/update-desktop-database -q /usr/share/applications || :
fi
if [ -x /usr/bin/gtk-update-icon-cache ]; then
    /usr/bin/gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || :
fi
exit 0

%postun
if [ -x /usr/bin/update-desktop-database ]; then
    /usr/bin/update-desktop-database -q /usr/share/applications || :
fi
exit 0

%files
%dir /opt/${PKG_NAME}
%attr(755,root,root) /opt/${PKG_NAME}/HMWLX
%attr(644,root,root) /opt/${PKG_NAME}/hmwlx.jar
%dir /opt/${PKG_NAME}/runtime
/opt/${PKG_NAME}/runtime/*
/usr/bin/${PKG_NAME}
/usr/share/applications/${PKG_NAME}.desktop
/usr/share/icons/hicolor/256x256/apps/${PKG_NAME}.png
SPEC

        # --define "_topdir"：把 rpmbuild 的工作目录指到暂存目录，
        # 不污染 ~/rpmbuild —— 构建脚本不该往用户主目录写东西。
        #
        # 注意 RPM_TOP 现在是**绝对路径**（在 $TMPDIR 下），不能再像
        # 以前那样拼 $(pwd)/ 前缀 —— 那会拼成
        #   /mnt/A/HMYLX//tmp/hmwlx-stage-xxx/rpmbuild
        # rpmbuild 找不到 Source0，报 "Bad source"。
        if rpmbuild -bb \
                --define "_topdir ${RPM_TOP}" \
                --define "_build_id_links none" \
                "$RPM_TOP/SPECS/${PKG_NAME}.spec" > "$RPM_TOP/build.log" 2>&1; then
            RPM_OUT=$(find "$RPM_TOP/RPMS" -name "*.rpm" | head -1)
            if [[ -n "$RPM_OUT" ]]; then
                cp "$RPM_OUT" "${DIST_DIR}/"
                echo "   ${DIST_DIR}/$(basename "$RPM_OUT")  ($(du -h "$RPM_OUT" | cut -f1))"
            fi
        else
            echo "  RPM 构建失败，日志末尾：" >&2
            tail -20 "$RPM_TOP/build.log" >&2
        fi
    fi
fi

# ===========================================================================
# 3. AppImage
#
# AppImage 的结构就是一个 AppDir：
#   HMWLX.AppDir/
#   ├── AppRun          入口（通常是软链或脚本）
#   ├── hmwlx.desktop   桌面项（文件名必须和 desktop 里的 Icon 对应）
#   ├── hmwlx.png       图标
#   └── usr/            实际内容
#
# 运行时会把整个 AppDir 挂载到 /tmp/.mount_XXX，所以**所有路径都必须是
# 相对 AppRun 的**，不能写死 /opt —— 这是 AppImage 最常见的踩坑点。
# ===========================================================================
if [[ "$DO_APPIMAGE" == "true" ]]; then
    echo "→ 构建 AppImage…"
    if [[ -z "$APPIMAGE_TOOL" ]]; then
        echo "  跳过：找不到 appimagetool" >&2
        echo "  下载：curl -L -o /tmp/appimagetool https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage && chmod +x /tmp/appimagetool" >&2
    else
        APPDIR="build/HMWLX.AppDir"
        rm -rf "$APPDIR"
        mkdir -p "$APPDIR/usr/bin" "$APPDIR/usr/share/applications" \
                 "$APPDIR/usr/share/icons/hicolor/256x256/apps"

        # --- 程序本体 ---
        # 放进 usr/bin/HMWLX 保持自包含（含 runtime/ 和 app/）。
        mkdir -p "$APPDIR/usr/bin/HMWLX"
        cp -r "$SRC_DIR/." "$APPDIR/usr/bin/HMWLX/"

        # --- AppRun ---
        # 用脚本而不是软链：APPDIR 是 AppImage 运行时注入的环境变量，
        # 指向临时挂载点（形如 /tmp/.mount_HMWLXxxxx），每次运行都不同，
        # 无法写死。
        #
        # 现在是 C 启动器，它自己用 /proc/self/exe 定位程序目录，
        # 不依赖 cwd，所以这里直接 exec 就行，不用 cd。
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

        # --- 桌面项与图标 ---
        # 文件名与 Icon= 值必须一致，否则桌面环境找不到图标。
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

        APPIMAGE_OUT="${DIST_DIR}/${APP_NAME}-${APP_VERSION}-x86_64.AppImage"
        # --no-appstream：不生成 AppStream 元数据。
        # 那需要额外的 appstream-util 和元数据文件，对自用分发没必要，
        # 缺了它 appimagetool 会报 warning 但不影响产物可用性。
        ARCH=x86_64 "$APPIMAGE_TOOL" --no-appstream "$APPDIR" "$APPIMAGE_OUT" 2>&1 \
            | grep -vE "^(appimagetool|WARNING: AppStream)" || true
        chmod +x "$APPIMAGE_OUT"
        echo "   $APPIMAGE_OUT  ($(du -h "$APPIMAGE_OUT" | cut -f1))"
    fi
fi

echo ""
echo "=== 完成 ==="
ls -lh "${DIST_DIR}"/*.deb "${DIST_DIR}"/*.AppImage 2>/dev/null || true
echo ""
echo "DEB 安装   : sudo apt install ./${DIST_DIR}/${PKG_NAME}_${DEB_VERSION}_amd64.deb"
echo "DEB 卸载   : sudo apt remove ${PKG_NAME}"
echo "AppImage   : chmod +x ${DIST_DIR}/${APP_NAME}-${APP_VERSION}-x86_64.AppImage && ./${DIST_DIR}/${APP_NAME}-${APP_VERSION}-x86_64.AppImage"
