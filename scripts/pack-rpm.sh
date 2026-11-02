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

# 目录版把法律文件放在包根目录（见 pack-linux.sh），cp -r 会一起带进
# /opt。这里剔除，只保留 /usr/share/doc 下的那份 —— 否则同一个许可证
# 在包里有两份，而且 /opt 里那份不在 %files 白名单内，rpmbuild 会报
# "Installed (but unpackaged) file(s) found" 直接失败。
pack_prune_legal "$RPM_STAGE/opt/${PKG_NAME}"

# GPL-3.0 第 6 条：以目标码分发必须附带许可证全文。
# /usr/share/doc/<pkg>/ 是 RPM 系的惯例位置。
pack_install_legal "$RPM_STAGE/usr/share/doc/${PKG_NAME}"

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

# spec 里 Source0 指向的占位文件。内容无用，只是为了满足
# rpm 4.20+ 的 %mkbuilddir（详见 spec 里 Source0 的注释）。
# 放在 SOURCES 下，rpmbuild 才找得到。
: > "$RPM_TOP/SOURCES/${PKG_NAME}-dummy.txt"

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
License:        GPL-3.0-only
URL:            ${HOMEPAGE}
BuildArch:      x86_64
AutoReqProv:    no
Requires:       libX11
Requires:       fontconfig
Requires:       freetype
Requires:       mesa-libGL

# 这个 Source0 是个空文件，内容完全不用。
#
# 为什么要有：rpm 4.20+ 在 %prep 之前会自动插一段 %mkbuilddir
# 建 BUILD 目录，而那段模板要求存在 Source0。我们的 spec 本来
# 不需要任何 source（文件树直接由 %install 从暂存目录拷），于是
# 新版本 rpm 会报：
#   error: No source number 0
#   Executing(%mkbuilddir): ...
#   error: Bad exit status from /var/tmp/rpm-tmp.xxx (%prep)
#
# 给个 dummy 就行 —— %prep 仍然是空的，没有 %setup，
# 所以不会解包它，也就不会调外部 tar（那才是我们要避开的坑）。
Source0:        ${PKG_NAME}-dummy.txt

%description
一个基于 headlessmc 的 Minecraft 启动器，内置 Java 运行时。
用户无需单独安装 Java，安装后可从应用菜单启动。

%prep
%build
%install
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

# 法律文件。%files 是白名单 —— 没列的文件不进包，所以
# pack_install_legal 拷过去的那些必须在这里显式声明，否则
# DEB / AppImage / ZIP / 目录版都有许可证，唯独 RPM 装完没有。
#
# 用 %license 而不是裸路径：rpm 会把它归到 rpm -q --license 可查的
# 许可证类别，也自动套上 0644 root:root，比列路径更正统。
%license /usr/share/doc/${PKG_NAME}/LICENSE
%license /usr/share/doc/${PKG_NAME}/THIRD-PARTY-NOTICES.txt
%doc /usr/share/doc/${PKG_NAME}/用户协议.txt
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
