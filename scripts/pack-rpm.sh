#!/usr/bin/env bash
#
# 打 RPM 安装包：dist/hmwlx-<版本>-1.x86_64.rpm
#
# 目标发行版：Fedora / RHEL / CentOS Stream / Rocky / AlmaLinux / openSUSE
#
# 前置：./scripts/pack-linux.sh 先产出目录版。
#
# 用法：
#   ./scripts/pack-rpm.sh
#
# ------------------------------------------------------------
# 关键：不经过 Source0 + %setup
# ------------------------------------------------------------
#
# RPM 的教科书写法是把产物打成 Source tar.gz，再在 %prep 里
# `%setup -q` 解开。但 %setup 内部调 /usr/bin/tar，在 CNB 的
# OverlayFS 上会报：
#
#   tar: hmwlx-0.1.dev/opt/hmwlx/runtime/bin/java:
#        Cannot open: Function not implemented
#   tar: hmwlx-0.1.dev/usr: Cannot mkdir: Function not implemented
#   error: Bad exit status from /var/tmp/rpm-tmp.xxx (%prep)
#
# 而我们本来就已经把文件树按最终路径摆好在暂存目录里了 ——
# 打 tar 再解一遍纯属多余。
#
# 所以：
#   - spec 里没有 Source0，%prep 留空
#   - %install 里 `cp -a <暂存目录>/. %{buildroot}/`
#   - 全程不调 tar
#
# 附带好处：少一次压缩 + 解压，构建更快。
#
set -euo pipefail

# shellcheck source=./pack-common.sh
source "$(dirname "$(readlink -f "$0")")/pack-common.sh"

print_fingerprint "$0"
require_srcdir
require_icon
require_cmd rpmbuild

echo "→ 构建 RPM…"

RPM_TOP="$(pack_stage_base)/rpmbuild"
rm -rf "$RPM_TOP"
mkdir -p "$RPM_TOP"/{BUILD,RPMS,SOURCES,SPECS,SRPMS,BUILDROOT}

# ------------------------------------------------------------ 暂存目录
#
# 先摆到 STAGE，%install 里再 `cp -a <STAGE>/. <buildroot>/`。
#
# 为什么不直接塞 BUILDROOT：rpmbuild 在 %install 之前会**清空**
# BUILDROOT，预先塞进去等于白做，会报
#   cp: cannot stat '.../BUILDROOT/.'
#
RPM_STAGE="$RPM_TOP/stage/${PKG_NAME}-${RPM_VERSION}"
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

# ------------------------------------------------------------ spec
#
# BuildArch: x86_64 —— 内置的 JRE 是平台相关的，不能标 noarch。
#
# 写 spec 前先确认 RPM_STAGE 有值：spec 是无引号 heredoc，
# shell 会当场展开 ${RPM_STAGE}。要是它没定义，set -u 会让整个
# 脚本崩在这里，报错行号指向 cat 那一行，看不出真凶。
: "${RPM_STAGE:?RPM_STAGE 未定义（RPM 暂存目录）}"

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
# 这些是 libskiko-linux-x64.so 的 ldd 结果里、包内没提供的：
Requires:       libX11
Requires:       fontconfig
Requires:       freetype
Requires:       mesa-libGL

%description
一个基于 headlessmc 的 Minecraft 启动器，内置 Java 运行时。
用户无需单独安装 Java，安装后可从应用菜单启动。

%prep
# 什么都不做：不走 Source0 + %setup（它会调外部 tar，见脚本头注释）。

%build
# 预构建产物，无需编译

%install
# 把暂存目录的整棵文件树拷进 buildroot。
# -a 保留符号链接（/usr/bin/hmwlx 是软链）和权限。
#
# ${RPM_STAGE} 由外层 shell 在写 spec 时就展开成绝对路径
# （heredoc 无引号），不用 rpmbuild 的 --define 传。
cp -a "${RPM_STAGE}"/. %{buildroot}/

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

# ------------------------------------------------------------ 打包
#
# --define "_topdir"：把 rpmbuild 的工作目录指到暂存目录，
# 不污染 ~/rpmbuild —— 构建脚本不该往用户主目录写东西。
#
# 注意必须是**绝对路径**，不能拼 $(pwd)/ 前缀 ——
# 那会拼成 /mnt/A/HMYLX//build/rpmbuild。
rpmbuild -bb \
    --define "_topdir ${RPM_TOP}" \
    --define "_build_id_links none" \
    "$RPM_TOP/SPECS/${PKG_NAME}.spec" > "$RPM_TOP/build.log" 2>&1 || {
        echo "  RPM 构建失败，日志末尾：" >&2
        tail -30 "$RPM_TOP/build.log" >&2
        exit 1
    }

RPM_OUT="$(find "$RPM_TOP/RPMS" -name '*.rpm' -type f | head -1)"
if [[ -z "$RPM_OUT" ]]; then
    echo "错误：rpmbuild 没有产出 .rpm" >&2
    exit 1
fi

mkdir -p "$DIST_DIR"
cp "$RPM_OUT" "$DIST_DIR/"
echo "   $DIST_DIR/$(basename "$RPM_OUT")  ($(size_of "$DIST_DIR/$(basename "$RPM_OUT")"))"
echo "   RPM 安装   : sudo dnf install ./$(basename "$RPM_OUT")"
echo "   RPM 卸载   : sudo dnf remove ${PKG_NAME}"
