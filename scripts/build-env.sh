#!/usr/bin/env bash
#
# 统一构建目录（被 pack-*.sh / build-launcher.sh 引入）
#
# ===========================================================================
# 为什么要单独定义「构建目录」
# ===========================================================================
#
# 在 CNB 上，/workspace 落在 OverlayFS 上（文档「流水线缓存」一节提到
# copy-on-write 就基于 OverlayFS）。对这种下层目录做批量 stat / 创建
# 会返回 ENOSYS：
#
#   tar: ./opt/hmwlx/runtime/lib/server/libjvm.so:
#        Cannot stat: Function not implemented
#   dpkg-deb: error: tar -cf subprocess failed with exit status 2
#
# 历史上一共踩过四次，根因同一个：
#   1. zig 解压 16000 个小文件
#   2. zip 原地更新 119MB jar
#   3. skiko 原生库嵌入
#   4. dpkg-deb / rpmbuild 遍历暂存树
#
# 解法不是「换个目录碰运气」，而是显式声明一个 data 卷：
#
#   .cnb.yml:
#     volumes:
#       - /build:data
#     env:
#       BUILD_ROOT: /build
#
# data 卷 = 临时数据卷，落在节点本地盘，结束时自动清理。
# 编译、暂存、打包全在里面做，工作区只留最终产物。
#
# ===========================================================================
# 目录布局
# ===========================================================================
#
#   $BUILD_ROOT/            编译工作区（data 卷，可能随时消失）
#   ├── launcher/           编译出的启动器 stub
#   ├── win-payload/        Windows 自解压包的待嵌入目录
#   ├── linux-stage/        DEB / RPM 暂存
#   └── cache/              下载缓存（skiko jar、JRE zip）
#
#   <仓库>/dist/            最终产物（留在工作区才能被 CNB 收集）
#
# ===========================================================================
# 环境变量
# ===========================================================================
#
#   BUILD_ROOT   显式指定构建目录。CNB 上由 .cnb.yml 注入 /build。
#                本地不设时默认用 <仓库>/build，行为和以前一致。
#   HMWLX_KEEP_STAGE=1
#                保留中间产物，便于排查。默认结束时清理。
#
set -u

# 只在被 source 时初始化，不重复执行
[[ -n "${_BUILD_ENV_LOADED:-}" ]] && return 0
_BUILD_ENV_LOADED=1

# 仓库根目录。调用方通常已经 cd 到根了，这里再算一次更稳。
BUILD_ENV_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# 构建目录：显式指定优先，否则用仓库内的 build/（本地开发默认）
BUILD_ROOT="${BUILD_ROOT:-$BUILD_ENV_ROOT/build}"

# 转成绝对路径：脚本里会 cd 到别处执行 zip/tar，相对路径会失效
case "$BUILD_ROOT" in
    /*) : ;;
    *)  BUILD_ROOT="$BUILD_ENV_ROOT/$BUILD_ROOT" ;;
esac

# 子目录
LAUNCHER_DIR="$BUILD_ROOT/launcher"
CACHE_DIR="$BUILD_ROOT/cache"
WIN_PAYLOAD_DIR="$BUILD_ROOT/win-payload"
LINUX_STAGE_DIR="$BUILD_ROOT/linux-stage"

mkdir -p "$LAUNCHER_DIR" "$CACHE_DIR"

# ---------------------------------------------------------------------------
# 清理
# ---------------------------------------------------------------------------
#
# 只清我们自己创建的子目录，不 rm -rf 整个 BUILD_ROOT ——
# 万一有人把 BUILD_ROOT 设成 / 或者仓库根，整个删掉就完了。
#
# HMWLX_KEEP_STAGE=1 时跳过，方便本地排查中间产物。
#
build_cleanup() {
    [[ "${HMWLX_KEEP_STAGE:-0}" == "1" ]] && {
        echo "（HMWLX_KEEP_STAGE=1，保留 $BUILD_ROOT）" >&2
        return 0
    }
    rm -rf "$LAUNCHER_DIR" "$WIN_PAYLOAD_DIR" "$LINUX_STAGE_DIR"
}

# 多个脚本会各自注册 EXIT trap，后注册的会覆盖先注册的。
# 这里用一个「是否已注册」的标记避免重复，且只注册一次。
if [[ -z "${_BUILD_ENV_TRAP_SET:-}" ]]; then
    _BUILD_ENV_TRAP_SET=1
    trap build_cleanup EXIT
fi

# 供脚本打印，排查时一眼看出产物落在哪
build_env_report() {
    echo "构建目录: $BUILD_ROOT"
    echo "产物目录: $BUILD_ENV_ROOT/dist"
    [[ "$BUILD_ROOT" != "$BUILD_ENV_ROOT/build" ]] && \
        echo "（BUILD_ROOT 由环境变量指定，通常对应 CNB 的 data 卷）"
}
