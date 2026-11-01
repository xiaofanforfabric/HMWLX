#!/usr/bin/env bash
#
# 构建全部产物。被两处调用：
#   1. .cnb.yml 的 release-build（dev:/rel: 等发版前缀，演练用）
#   2. .cnb.yml 的 release-merged（PR 合并后，真正发版）
#
# 为什么抽出来：这两条路径的构建步骤完全一样，复制两遍必然会
# 改一处忘另一处。
#
# 前置：工作目录是仓库根，依赖已装好（镜像里都有）。
#
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")/.."

# ------------------------------------------------------------ 工具检查
#
# zip / rpm / xz / file 和图形库全都装在镜像里
# （docker/build-env.Dockerfile），这里不再 apt install：
#   - 每个 stage 是独立容器，但用的是同一个镜像；
#   - 重复 apt update 每次要几秒到几十秒，apt 源被墙时还会让整个
#     打包失败 —— 明明镜像里已经装好了。
#
# 这里只做存在性检查：缺东西立刻报错，别拖到打包第 3 步才发现。
for cmd in zip unzip rpmbuild xz file jlink python3; do
    command -v "$cmd" >/dev/null 2>&1 || {
        echo "错误：镜像里缺少 $cmd，请检查 docker/build-env.Dockerfile" >&2
        exit 1
    }
done

chmod +x ./gradlew scripts/*.sh scripts/*.py

# 启动器编译器：Windows 用 mingw-w64，Linux 用 gcc
for cc in x86_64-w64-mingw32-gcc gcc; do
    command -v "$cc" >/dev/null 2>&1 || {
        echo "错误：镜像里缺少 $cc，请检查 docker/build-env.Dockerfile" >&2
        exit 1
    }
done
echo "Windows 编译器: $(x86_64-w64-mingw32-gcc --version | head -1)"
echo "Linux 编译器:   $(gcc --version | head -1)"

# ------------------------------------------------------------ 架构校验
#
# 产物里写死了 amd64 / x86_64（DEB 的 Architecture 字段、AppImage
# 文件名）。万一 runner.tags 没生效、被调度到 arm64 节点，宁可这里
# 直接失败，也别产出架构标错的包 —— 那种包能下载能看，却装不上。
host_arch="$(uname -m)"
if [ "$host_arch" != "x86_64" ]; then
    echo "错误：需要 x86_64 构建节点，当前是 $host_arch" >&2
    echo "  产物架构写死为 amd64，跑在 $host_arch 上会打出错包。" >&2
    exit 1
fi
echo "构建节点架构: $host_arch ✓"

# ------------------------------------------------------------ 构建
echo "==> 构建 fat jar"
./scripts/build.sh --fast

# 三步打包，顺序不能变：formats 依赖 pack-linux.sh 的目录产物
echo "==> 打包 Windows"
./scripts/pack-windows.sh
echo "==> 打包 Linux（目录 + 单文件）"
./scripts/pack-linux.sh
echo "==> 打包 Linux（DEB / RPM / AppImage）"
./scripts/pack-linux-formats.sh

# ------------------------------------------------------------ 收集 fat jar
#
# FAT_JAR 在 build/libs/，之前从没拷进 dist/，所以发版产物里一直
# 没有它。用户想「直接 java -jar 跑」就得有这个。
#
# 不用 source scripts/version.sh 取 $FAT_JAR —— 这个脚本虽然用 bash
# 跑，但 glob 更简单，版本号变了也不用改。
FAT_JAR=""
for f in build/libs/*-all.jar; do
    [ -f "$f" ] && FAT_JAR="$f"
done
if [ -z "$FAT_JAR" ]; then
    echo "错误：找不到 fat jar（build/libs/*-all.jar）" >&2
    ls -la build/libs/ 2>/dev/null >&2 || true
    exit 1
fi
mkdir -p dist
cp "$FAT_JAR" dist/
echo "已收集: dist/$(basename "$FAT_JAR")"

# ------------------------------------------------------------ 清单
echo ""
echo "=== 产物 ==="
ls -lh dist/
