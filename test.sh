#!/usr/bin/env bash
#
# HMWLX 本地 CI。尽量复现 GitHub Actions 和 CNB 的构建环境，
# 让「本地绿 → 远端绿」成为可靠的预期，而不是碰运气。
#
# 用法：
#   ./test.sh              跑全部测试（默认，等同 CI 的 test job）
#   ./test.sh --build      测试 + 构建（等同 CI 的 build job）
#   ./test.sh --clean      先 clean 再跑
#   ./test.sh --fast       只编译，不跑测试
#   ./test.sh --verbose    打印完整 Gradle 输出
#   ./test.sh --help       看用法
#
# 退出码：0 表示全部通过，非 0 表示有失败 —— 可以直接用在
#   `./test.sh && git push` 这类串联命令里。
#
# ---------------------------------------------------------------------------
# 为什么脚本里的命令和 CI 有细微差别
# ---------------------------------------------------------------------------
#
# 1. **JDK**：CI 用 eclipse-temurin:25 镜像 / setup-java。
#    本地没有这个镜像，只能探测已安装的 JDK 25。探测失败就直接退出，
#    不降级到 JDK 17 —— 那样测试会全部报 UnsupportedClassVersionError，
#    看起来像是代码坏了，实际只是 JDK 不对。
#
# 2. **LANG**：CI 显式设 C.UTF-8。本地如果 LANG 是空的或 C/POSIX，
#    JDK 会用 ASCII 建文件名，而测试方法名是中文，生成报告时会抛
#    "Malformed input or input contains unmappable characters"，
#    测试全绿却让整个任务失败。这里强制设成 C.UTF-8。
#
# 3. **--no-build-cache**：与 .cnb.yml 一致。quarkusGenerateCode 不把
#    application.properties 当输入，缓存会恢复出旧配置的 bean 索引。
#    GitHub Actions 那边没加这个参数，是因为它每次都是干净环境、
#    且 setup-gradle 的缓存按 commit 键隔离；本地缓存跨提交复用，
#    不加就会有「改了配置但测试行为没变」的怪现象。
#
# 4. **--no-daemon**：CI 里没有常驻 daemon。本地也用同样的方式跑，
#    避免「在我机器上是 daemon 缓存后的结果」这类差异。

set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

# ---------------------------------------------------------------------------
# JDK 25
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

    for c in /usr/lib/jvm/*25*; do
        if [[ -x "$c/bin/java" ]]; then
            echo "$c"
            return 0
        fi
    done

    return 1
}

JDK="$(find_jdk)" || {
    cat >&2 <<'MSG'
错误：找不到 JDK 25。

CI 用的是 eclipse-temurin:25，本地需要 JDK 25 才能跑测试
（headlessmc 的 class 文件版本是 69，JDK 17 只能认到 61）。

可以用环境变量指定：
    HMYLX_JAVA_HOME=/path/to/jdk25 ./test.sh
MSG
    exit 1
}

JAVA_VERSION="$("$JDK/bin/java" -version 2>&1 | head -1 | sed -E 's/.*"([0-9]+).*/\1/')"
if [[ -z "$JAVA_VERSION" || "$JAVA_VERSION" -lt 25 ]]; then
    echo "错误：$JDK 是 Java $JAVA_VERSION，需要 25+。" >&2
    exit 1
fi

export JAVA_HOME="$JDK"

# 与 CI 一致的 UTF-8 环境。中文测试名要靠它才能生成报告。
export LANG=C.UTF-8
export LC_ALL=C.UTF-8

# ---------------------------------------------------------------------------
# 参数
# ---------------------------------------------------------------------------
DO_CLEAN=0
DO_BUILD=0
FAST=0
VERBOSE=0

for arg in "$@"; do
    case "$arg" in
        --build)   DO_BUILD=1 ;;
        --clean)   DO_CLEAN=1 ;;
        --fast)    FAST=1 ;;
        --verbose) VERBOSE=1 ;;
        -h|--help)
            sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'
            exit 0
            ;;
        *)
            echo "未知参数：$arg（用 --help 看用法）" >&2
            exit 1
            ;;
    esac
done

# ---------------------------------------------------------------------------
# 命令拼装
# ---------------------------------------------------------------------------
GRADLE_ARGS=(--no-daemon --no-build-cache)

if [[ "$FAST" == "1" ]]; then
    TASKS=(compileKotlin compileTestKotlin)
elif [[ "$DO_BUILD" == "1" ]]; then
    TASKS=(build)
else
    TASKS=(test)
fi

# ---------------------------------------------------------------------------
# 环境检查：CI 是干净容器，本地可能有一堆用户级 gradle.properties
# 把构建行为改掉了。这几项是最容易造成「本地和 CI 不一致」的。
# ---------------------------------------------------------------------------
echo "=== 环境 ==="
echo "JDK:      $JDK (Java $JAVA_VERSION)"
echo "LANG:     $LANG"

if [[ -f "$HOME/.gradle/gradle.properties" ]]; then
    echo ""
    echo "注意：检测到用户级 ~/.gradle/gradle.properties，"
    echo "      里面的配置（代理、离线模式、仓库覆盖等）会影响本次构建，"
    echo "      而 CI 环境没有这个文件。若本地结果与 CI 不一致，先怀疑它。"
fi

# Git 状态：CI 是干净 checkout，本地若有未提交改动，
# 「测试通过」只代表这堆混杂的改动没坏，不代表某个提交没坏。
if [[ -n "$(git status --porcelain 2>/dev/null)" ]]; then
    echo ""
    echo "注意：工作区有未提交改动。这些改动会参与构建，"
    echo "      但不会出现在任何提交里 —— CI 测的是提交，不是你的工作区。"
fi

echo ""
echo "=== 执行 ==="
echo "任务: ${TASKS[*]} ${GRADLE_ARGS[*]}"
echo ""

if [[ "$DO_CLEAN" == "1" ]]; then
    # clean 单独跑：和 build 拼成一条命令时，任务图里已经算好的
    # build/ 路径会被 clean 删掉，偶发 "output dir does not exist"。
    echo "[1/2] clean"
    ./gradlew --no-daemon clean
    echo ""
fi

set +e
if [[ "$VERBOSE" == "1" ]]; then
    ./gradlew "${GRADLE_ARGS[@]}" "${TASKS[@]}" --stacktrace 2>&1 | tee /tmp/hmylx-test.log
    EXIT_CODE=${PIPESTATUS[0]}
else
    ./gradlew "${GRADLE_ARGS[@]}" "${TASKS[@]}" --stacktrace 2>&1 \
        | tee /tmp/hmylx-test.log \
        | grep -vE '^(> Task .*(UP-TO-DATE|NO-SOURCE|SKIPPED)$|$)'
    # PIPESTATUS 要取 gradlew 那一段的退出码，不是 tee 或 grep 的。
    EXIT_CODE=${PIPESTATUS[0]}
fi
set -e

## ---------------------------------------------------------------------------
# 结果
# ---------------------------------------------------------------------------
echo ""
echo "=== 结果 ==="

# 判断这次测试是真跑了还是复用了上次的结果。
# --no-build-cache 只禁用了「跨机器/跨提交的构建缓存」，Gradle 的
# up-to-date 判定仍然有效：输入没变就直接跳过 :test 任务，
# 沿用上一次的 XML 报告。这时脚本报的「通过」其实是**上次**的结论。
if [[ "$FAST" != "1" ]] && grep -qE ':test UP-TO-DATE|:test FROM-CACHE' /tmp/hmylx-test.log 2>/dev/null; then
    echo "⚠️  :test 任务被跳过（输入未变，复用了上次结果）"
    echo "    下面的数字来自上一次运行，不是这次真实执行的结果。"
    echo "    要强制真跑一遍：./test.sh --clean"
    echo ""
fi

if [[ "$FAST" != "1" && -d build/test-results/test ]]; then
    # 汇总每个测试类的通过/失败数。CI 上看不到 HTML 报告，
    # 本地至少要能一眼看出「测了多少、挂了几个」。
    python3 - <<'PY' 2>/dev/null || true
import glob, xml.etree.ElementTree as ET

total = fails = errors = skipped = 0
failed_cases = []

for path in glob.glob('build/test-results/test/TEST-*.xml'):
    root = ET.parse(path).getroot()
    total += int(root.get('tests', 0))
    fails += int(root.get('failures', 0))
    errors += int(root.get('errors', 0))
    skipped += int(root.get('skipped', 0))
    for case in root.iter('testcase'):
        if case.find('failure') is not None or case.find('error') is not None:
            failed_cases.append(f"{root.get('name')} > {case.get('name')}")

print(f"测试总数: {total}   失败: {fails}   错误: {errors}   跳过: {skipped}")
if failed_cases:
    print("\n失败用例:")
    for name in failed_cases:
        print(f"  ✗ {name}")
PY

    if [[ -d build/reports/tests/test ]]; then
        echo ""
        echo "HTML 报告: build/reports/tests/test/index.html"
    fi
fi

if [[ "$FAST" != "1" && -d build/quarkus-app ]]; then
    echo "可运行产物: build/quarkus-app/  ($(du -sh build/quarkus-app | cut -f1))"
fi

echo ""
if [[ "$EXIT_CODE" == "0" ]]; then
    echo "✅ 全部通过"
else
    echo "❌ 失败（退出码 $EXIT_CODE）"
    if [[ "$VERBOSE" != "1" && -f /tmp/hmylx-test.log ]]; then
        echo "   完整输出: /tmp/hmylx-test.log"
        echo "   或重跑:   ./test.sh --verbose"
    fi
fi

exit "$EXIT_CODE"
