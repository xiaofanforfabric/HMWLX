#!/usr/bin/env bash
#
# HMWLX 一键启动脚本。
#
# 用法：
#   ./run.sh            启动（后台，日志写 /tmp/hmylx-run.log）
#   ./run.sh --fg       前台启动（日志直接打在终端）
#   ./run.sh --stop     停止正在运行的实例
#   ./run.sh --log      跟踪日志
#
# 为什么需要这个脚本：headlessmc 是 Java 25 编译的（class file 69），
# 系统默认 JDK 17 只能认到 61，直接 ./gradlew run 会报 UnsupportedClassVersionError。
# 所以这里强制指定 JDK 25。

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

JAVA_HOME_25="$(find_jdk)" || {
    echo "错误：找不到 JDK 25。请用 HMYLX_JAVA_HOME=/path/to/jdk25 指定。" >&2
    exit 1
}

# 校验版本确实是 25，防止探测到错的
JAVA_VERSION="$("$JAVA_HOME_25/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [[ "$JAVA_VERSION" != "25" ]]; then
    echo "错误：$JAVA_HOME_25 是 Java $JAVA_VERSION，需要 25。" >&2
    echo "请用 HMYLX_JAVA_HOME=/path/to/jdk25 指定。" >&2
    exit 1
fi

export JAVA_HOME="$JAVA_HOME_25"
export PATH="$JAVA_HOME/bin:$PATH"

LOG_FILE="/tmp/hmylx-run.log"
LOG_PID=""

# ---------------------------------------------------------------------------
# 停止已有实例
# ---------------------------------------------------------------------------
stop_running() {
    local stopped=0

    if pkill -f 'org.cn.xiaofan.MainKt' 2>/dev/null; then
        stopped=1
    fi
    if pkill -f 'GradleWrapperMain.*run' 2>/dev/null; then
        stopped=1
    fi

    if [[ $stopped -eq 1 ]]; then
        echo "已停止正在运行的实例"
        sleep 1
    fi
}

# ---------------------------------------------------------------------------
# 参数处理
# ---------------------------------------------------------------------------
case "${1:-}" in
    --stop)
        stop_running
        exit 0
        ;;
    --log)
        tail -f "$LOG_FILE"
        exit 0
        ;;
    --fg)
        stop_running
        exec ./gradlew --no-daemon run
        ;;
    --help|-h)
        sed -n '2,12p' "$0" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    "")
        ;;
    *)
        echo "未知参数：$1（用 --help 查看用法）" >&2
        exit 1
        ;;
esac

# ---------------------------------------------------------------------------
# 后台启动
# ---------------------------------------------------------------------------
stop_running
: > "$LOG_FILE"

echo "JDK:  $JAVA_HOME"
echo "日志: $LOG_FILE"

# setsid + </dev/null + disown：三重脱离，
# 保证关掉终端 / Ctrl-C 都不会把 GUI 拖下水，并且不抢终端输入。
setsid env DISPLAY="${DISPLAY:-:0}" ./gradlew --no-daemon run \
    </dev/null >"$LOG_FILE" 2>&1 &
disown

echo "已启动，等待窗口出现…"
echo "查看日志：./run.sh --log"

exit 0
