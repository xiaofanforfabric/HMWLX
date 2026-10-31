#!/usr/bin/env bash
#
# HMWLX 构建脚本。
#
# 用法：
#   ./build.sh              完整构建（编译 + 测试 + 打包）
#   ./build.sh --fast       跳过测试
#   ./build.sh --clean      先 clean 再构建
#
# 为什么需要这个脚本：headlessmc 是 Java 25 编译的（class file 69），
# 系统默认 JDK 17 只能认到 61。不指定 JDK 25 的话，测试会在
# "has been compiled by a more recent version of the Java Runtime" 上全挂。
# 直接 ./gradlew build 不会去探测 JDK，只会用启动它的那个 JVM。
#
# 所以这里做和 run.sh 一样的事：先找到 JDK 25，再拿它跑 Gradle。

set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

# ---------------------------------------------------------------------------
# JDK 25：优先用环境变量，其次探测常见安装位置
# ---------------------------------------------------------------------------
find_jdk() {
    if [[ -n "${HMYLX_JAVA_HOME:-}" && -x "${HMYLX_JAVA_HOME}/bin/java" ]]; then
        echo "${HMYLX_JAVA_HOME}"
        return 0
    fi

    local candidates=(
        /opt/idea-IU-262.8665.337/jbr
        "$HOME/.jdks/temurin-25"
        "$HOME/.jdks/openjdk-25"
        /usr/lib/jvm/java-25-openjdk-amd64
    )

    local c
    for c in "${candidates[@]}"; do
        if [[ -x "$c/bin/java" ]]; then
            echo "$c"
            return 0
        fi
    done

    # 兜底：扫 /usr/lib/jvm 下带 25 的目录
    for c in /usr/lib/jvm/*25*; do
        if [[ -x "$c/bin/java" ]]; then
            echo "$c"
            return 0
        fi
    done

    return 1
}

JDK="$(find_jdk)" || {
    echo "错误：找不到 JDK 25。请用 HMYLX_JAVA_HOME=/path/to/jdk25 指定。" >&2
    exit 1
}

# 校验版本确实是 25+。只判断目录名会漏掉「名字带 25 但实际是 17」的软链。
JAVA_VERSION="$("$JDK/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [[ -z "$JAVA_VERSION" || "$JAVA_VERSION" -lt 25 ]]; then
    echo "错误：$JDK 是 Java $JAVA_VERSION，需要 25+。" >&2
    exit 1
fi

export JAVA_HOME="$JDK"
echo "JDK:  $JDK (Java $JAVA_VERSION)"

# ---------------------------------------------------------------------------
# 参数
# ---------------------------------------------------------------------------
GRADLE_ARGS=(--no-daemon)
DO_CLEAN=0

for arg in "$@"; do
    case "$arg" in
        --fast)
            # 跳过测试：只编译和打包。改完界面想快速看一眼时用。
            # -x test 只是跳过执行，仍会编译测试源码，所以测试代码写错
            # 还是会被发现。
            GRADLE_ARGS+=(-x test)
            ;;
        --clean)
            DO_CLEAN=1
            ;;
        -h|--help)
            sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "未知参数：$arg（用 --help 看用法）" >&2
            exit 1
            ;;
    esac
done

# clean 和 build 分两次调用，而不是拼成 "clean build" 一条命令：
# Gradle 会按命令行顺序执行，但拼在一起时 clean 删掉 build/ 目录后，
# 同一批次里已算好的任务图可能还引用旧路径，偶发 "output dir does not exist"。
if [[ "$DO_CLEAN" == "1" ]]; then
    echo "任务: clean"
    echo
    ./gradlew --no-daemon clean
    echo
fi

TASKS=(build)

# ---------------------------------------------------------------------------
# 构建
# ---------------------------------------------------------------------------
echo "任务: ${TASKS[*]} ${GRADLE_ARGS[*]}"
echo

./gradlew "${GRADLE_ARGS[@]}" "${TASKS[@]}"

# ---------------------------------------------------------------------------
# 报告产物
# ---------------------------------------------------------------------------
echo
echo "=== 产物 ==="
if [[ -d build/quarkus-app ]]; then
    echo "可运行目录: build/quarkus-app/  ($(du -sh build/quarkus-app | cut -f1))"
    echo "启动方式:   cd build/quarkus-app && java -jar quarkus-run.jar"
fi
if compgen -G "build/libs/*.jar" > /dev/null; then
    for jar in build/libs/*.jar; do
        echo "普通 JAR:  $jar  ($(du -h "$jar" | cut -f1)，不含依赖，不可直接运行)"
    done
fi
