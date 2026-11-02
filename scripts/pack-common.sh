#!/usr/bin/env bash
#
# 三个打包脚本（pack-deb.sh / pack-rpm.sh / pack-appimage.sh）的公共环境。
#
# 干什么：
#   1. 定位仓库根目录
#   2. 定义 APP_NAME / PKG_NAME / 路径 / 描述等常量
#   3. 加载版本号（version.sh）和构建工作区（build-env.sh）
#   4. 前置检查（目录版是否已生成、图标是否存在）
#   5. 提供一个输出文件大小的小工具
#
# 用法：在打包脚本里 source 它，不要直接执行。
#
#     source "$PWD/scripts/pack-common.sh"
#
# 它假定调用方已经 set -euo pipefail，并且已经 cd 到仓库根目录。
# pack_common_init() 会再做一次兜底。
#
# 注意：这里只定义变量和函数，不做任何打包动作。
#

# 避免被重复 source
if [[ -n "${_PACK_COMMON_LOADED:-}" ]]; then
    return 0
fi
_PACK_COMMON_LOADED=1

set -euo pipefail

# ------------------------------------------------------------------ 路径
#
# 调用方通常在 scripts/ 下执行，用 $0 反推仓库根。
# 如果调用方已经 cd 过，_PACK_COMMON_ROOT 仍然指向正确位置。
_PACK_COMMON_SELF="${BASH_SOURCE[0]}"
_PACK_COMMON_ROOT="$(cd "$(dirname "$(readlink -f "$_PACK_COMMON_SELF")")/.." && pwd)"
cd "$_PACK_COMMON_ROOT"

# ------------------------------------------------------------------ 常量
APP_NAME="HMWLX"
PKG_NAME="hmwlx"                         # DEB/RPM 包名，必须全小写
MAINTAINER="xiaofan <xiaofan@cn.xiaofan>"
HOMEPAGE="https://cnb.cool/xiaofanforandroid/HMWLX/HMWLX"
DESCRIPTION="Minecraft 启动器（HMWLX）"

# pack-linux.sh 的目录版产物在构建区（不是 dist/）。
# 实际值在下面 source 完 build-env.sh 之后设置 —— 它依赖 BUILD_ROOT。
DIST_DIR="dist"                          # 最终安装包放这里，CNB 才会收集
ICON="src/main/resources/images/icon-release.png"

# ------------------------------------------------------------------ 加载
# 版本号唯一来源：gradle.properties 的 appVersion。
#   APP_VERSION  原始，如 0.1-dev
#   DEB_VERSION  0.1~dev   Debian 用 ~ 表示预发布
#   RPM_VERSION  0.1.dev   RPM 版本字段不允许连字符
# shellcheck source=./version.sh
source "$_PACK_COMMON_ROOT/scripts/version.sh"

# 构建工作区（CNB 上是 data 卷 /build，本地默认 <仓库>/build）。
# 详见 scripts/build-env.sh 顶部关于 OverlayFS 的说明。
# shellcheck source=./build-env.sh
source "$_PACK_COMMON_ROOT/scripts/build-env.sh"

# build-env.sh 定义了 BUILD_ROOT，SRC_DIR 依赖它，所以放这之后。
SRC_DIR="$BUILD_ROOT/${APP_NAME}-linux-x64"

# ------------------------------------------------------------------ 工具
# 打印文件大小，用于产物输出。文件不存在会报错（调用方问题）。
size_of() {
    du -h "$1" | cut -f1
}

# 打印脚本指纹：CI 里报行号时，对着这个 SHA 能确认跑的是哪份代码。
# 用法：print_fingerprint "$0"
print_fingerprint() {
    local f="$1"
    echo "→ $(basename "$f") @ $(sha256sum "$f" | cut -c1-12)"
}

# 前置检查：目录版目录必须存在。
require_srcdir() {
    if [[ ! -d "$SRC_DIR" ]]; then
        echo "错误：找不到 $SRC_DIR" >&2
        echo "请先运行 ./scripts/pack-linux.sh" >&2
        return 1
    fi
}

# 前置检查：图标必须存在。
require_icon() {
    if [[ ! -f "$ICON" ]]; then
        echo "错误：找不到图标 $ICON" >&2
        return 1
    fi
}

# 前置检查：命令是否存在。
require_cmd() {
    local c
    for c in "$@"; do
        if ! command -v "$c" >/dev/null 2>&1; then
            echo "错误：缺少命令 $c" >&2
            return 1
        fi
    done
}

# 暂存目录的顶层（DEB / RPM 共用）。
# 放 data 卷（CNB 上是 /build），避免 OverlayFS 的 ENOSYS。
pack_stage_base() {
    echo "$LINUX_STAGE_DIR"
}

# 清理暂存目录（幂等）。
pack_stage_clean() {
    rm -rf "$(pack_stage_base)"
    mkdir -p "$(pack_stage_base)"
}

# ---------------------------------------------------------------------------
# 分发法律文件
# ---------------------------------------------------------------------------
#
# GPL-3.0 第 6 条要求：以目标码形式分发时，必须附带许可证全文与版权声明。
# 光把 LICENSE 放在 git 仓库里不算 —— 用户下载的是安装包，不是仓库。
#
# 所以每个安装包/便携包都要带上这三份：
#   LICENSE                         本项目的 GPL-3.0 全文
#   LEGAL/用户协议.txt               最终用户协议
#   LEGAL/THIRD-PARTY-NOTICES.txt   headlessmc 的 MIT 声明（同样是硬性要求）
#
# 用法：pack_install_legal <目标目录> [目标目录...]
#
# 目标目录不存在会自动创建。缺文件直接报错 —— 宁可打包失败，
# 也不要产出一个合规性残缺的安装包。
pack_install_legal() {
    local dest
    local f

    for dest in "$@"; do
        mkdir -p "$dest"
        for f in LICENSE "LEGAL/用户协议.txt" "LEGAL/THIRD-PARTY-NOTICES.txt"; do
            if [[ ! -f "$_PACK_COMMON_ROOT/$f" ]]; then
                echo "错误：缺少法律文件 $f（GPL-3.0 要求分发时附带）" >&2
                return 1
            fi
            cp "$_PACK_COMMON_ROOT/$f" "$dest/"
        done
    done
}

# ---------------------------------------------------------------------------
# 从程序目录里剔除法律文件
# ---------------------------------------------------------------------------
#
# Linux 目录版（pack-linux.sh）把三份法律文件放在**包根目录**，方便用户
# 解压后一眼看到。但 DEB/RPM/AppImage 都是拿 `cp -r "$SRC_DIR/."` 整体
# 复制的 —— 于是那三份就跟着进了 /opt/hmwlx/。
#
# 后果：
#   1. 安装包里同一个许可证有两份（/opt 一份、/usr/share/doc 一份）
#   2. RPM 的 %files 是白名单，/opt/hmwlx/LICENSE 没列进去 ——
#      rpmbuild 直接报 "Installed (but unpackaged) file(s) found" 失败
#
# 所以这三个发行版在 cp -r 之后、pack_install_legal 之前，先用本函数把
# 程序目录里的法律文件删掉，只保留 /usr/share/doc 那份规范位置。
#
# 用法：pack_prune_legal <程序目录>
pack_prune_legal() {
    local prog_dir="$1"
    local f

    if [[ ! -d "$prog_dir" ]]; then
        echo "错误：pack_prune_legal 的目标目录不存在: $prog_dir" >&2
        return 1
    fi

    for f in LICENSE THIRD-PARTY-NOTICES.txt "用户协议.txt"; do
        rm -f "$prog_dir/$f"
    done
}

