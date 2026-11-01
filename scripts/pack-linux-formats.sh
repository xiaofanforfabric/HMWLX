#!/usr/bin/env bash
#
# 把 pack-linux.sh 的目录版打成三种分发格式。
#
# 产出：
#   dist/hmwlx_<版本>_amd64.deb          Debian/Ubuntu 安装包
#   dist/hmwlx-<版本>-1.x86_64.rpm       Fedora/RHEL/openSUSE 安装包
#   dist/HMWLX-<版本>-x86_64.AppImage    单文件便携版
#
# 前置：先跑 ./scripts/pack-linux.sh
#
# 用法：
#   ./scripts/pack-linux-formats.sh              三个都打
#   ./scripts/pack-linux-formats.sh --deb-only
#   ./scripts/pack-linux-formats.sh --rpm-only
#   ./scripts/pack-linux-formats.sh --appimage-only
#
# 这个文件只是调度器。实际逻辑在：
#   scripts/pack-deb.sh
#   scripts/pack-rpm.sh
#   scripts/pack-appimage.sh
#   scripts/pack-common.sh       三者共用的常量与前置检查
#
# 单独跑某一个，直接执行对应脚本即可 —— 它们不依赖这个调度器。
#
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")/.."

DO_DEB=true
DO_RPM=true
DO_APPIMAGE=true

case "${1:-}" in
    --deb-only)       DO_RPM=false; DO_APPIMAGE=false ;;
    --rpm-only)       DO_DEB=false; DO_APPIMAGE=false ;;
    --appimage-only)  DO_DEB=false; DO_RPM=false ;;
    "")               ;;
    -h|--help)
        sed -n '2,24p' "$0" | sed 's/^# \?//'
        exit 0
        ;;
    *)
        echo "未知参数: $1" >&2
        echo "可用: --deb-only / --rpm-only / --appimage-only" >&2
        exit 1
        ;;
esac

# 一个失败不影响其余：三个格式相互独立，全都试一遍再汇总。
# （以前是不管哪一个挂了整个脚本就 exit，前面打好的包也白等）
failed=()

if [[ "$DO_DEB" == "true" ]]; then
    ./scripts/pack-deb.sh || failed+=("DEB")
fi

if [[ "$DO_RPM" == "true" ]]; then
    ./scripts/pack-rpm.sh || failed+=("RPM")
fi

if [[ "$DO_APPIMAGE" == "true" ]]; then
    ./scripts/pack-appimage.sh || failed+=("AppImage")
fi

echo ""
echo "=== 产物 ==="
ls -lh "$PWD/dist"/*.deb "$PWD/dist"/*.rpm "$PWD/dist"/*.AppImage 2>/dev/null || true

if [[ ${#failed[@]} -gt 0 ]]; then
    echo "" >&2
    echo "失败: ${failed[*]}" >&2
    exit 1
fi
