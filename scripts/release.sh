#!/usr/bin/env bash
#
# 发版主控脚本 —— 读提交信息里的阶段前缀，推进版本号，输出 CI 变量。
#
# ==========================================================================
# 工作流（两个阶段，由 CI 串联）
# ==========================================================================
#
#   阶段 1（push 到任意分支，提交信息以 rel:/beta:/RC:/dev: 开头）
#     release.sh --prepare
#       -> 算出下一个版本号
#       -> 写进 gradle.properties
#       -> 输出 VERSION / TAG / IS_PRERELEASE 等到 $GITHUB_ENV / $CNB_ENV
#     然后 CI 开新分支、提交、开 PR。
#
#   阶段 2（PR 合并到主分支后）
#     release.sh --verify
#       -> 确认当前版本号合法、且没有重复的 tag
#       -> 打 tag 并推送
#     然后 CI 按 tag 构建各平台产物、上传 Release。
#
# ==========================================================================
# 版本推进规则
# ==========================================================================
#
# 阶段序 dev < beta < RC < rel，详见 scripts/bump-version.py 的注释。
# 从 0.1-dev 出发：
#   rel:  -> 0.1         RC:   -> 0.1-RC1
#   beta: -> 0.1-beta1   dev:  -> 0.1-dev2
#
# 从 0.1（正式版）出发：
#   rel:  -> 0.2         RC:   -> 0.2-RC1
#   beta: -> 0.2-beta1   dev:  -> 0.2-dev1
#
# 阶段倒退（如 0.1-RC1 打 beta:）直接报错，CI 失败。
#
# ==========================================================================
# 用法
# ==========================================================================
#
#   ./scripts/release.sh --prepare                 从 $RELEASE_MESSAGE 读前缀
#   ./scripts/release.sh --prepare "rel: 修好了"    直接给消息
#   ./scripts/release.sh --verify                  校验并准备打 tag
#   ./scripts/release.sh --detect "rel: 修好了"     只打印阶段，不改文件
#
# ==========================================================================
# 关于 test: 前缀
# ==========================================================================
#
#   test: 是「打包演练」—— 跑一遍完整的 CI 构建（编译 + 全平台打包），
#   但不推进版本号、不开 PR、不发版。
#
#   用途：改了打包脚本想验证能不能出东西时，用空提交触发：
#     git commit --allow-empty -m "test: 验证打包"
#     git push
#
#   这样不会污染版本历史（不会产生 release/v0.2 分支和 PR）。
#
#   本脚本对 test: 的处理：--detect 返回 "test"，--prepare 给出提示后
#   直接返回，不修改 gradle.properties。
#
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

GRADLE_PROPS="$ROOT/gradle.properties"
BUMP="$ROOT/scripts/bump-version.py"

# ------------------------------------------------------------------ 工具

die() { echo "错误：$*" >&2; exit 1; }
info() { echo "==> $*"; }

# 从提交信息首行提取阶段。
#
# 支持的前缀（大小写不敏感，后面必须跟冒号）：
#   rel:   正式版
#   RC:    候选版
#   beta:  公测版
#   dev:   开发版
#   test:  打包演练（不进版本状态机，见下方说明）
#
# 注意 RC 通常写作大写，但这里不区分大小写 —— 用户敲 rc: 也能认。
detect_stage() {
    local msg="${1:-}"
    local first_line
    first_line="$(printf '%s' "$msg" | head -1)"

    # 用 bash 正则，避免 grep -o 在不同平台行为差异。
    # 大小写不敏感：RC: / rc: / Rc: 都认，test: / TEST: 也都认。
    # 注意保存并恢复 nocasematch，这个 shopt 是全局状态，
    # 乱改会影响调用方后续的字符串比较。
    local _old_nocase
    _old_nocase="$(shopt -p nocasematch 2>/dev/null || true)"
    shopt -s nocasematch
    if [[ "$first_line" =~ ^(rel|RC|beta|dev|test)[[:space:]]*:[[:space:]]* ]]; then
        local s="${BASH_REMATCH[1]}"
        s="$(printf '%s' "$s" | tr '[:upper:]' '[:lower:]')"
        eval "$_old_nocase" 2>/dev/null || shopt -u nocasematch
        case "$s" in
            rel)  echo "rel" ;;
            rc)   echo "RC" ;;   # 统一输出大写 RC
            beta) echo "beta" ;;
            dev)  echo "dev" ;;
            test) echo "test" ;;
        esac
        return 0
    fi
    eval "$_old_nocase" 2>/dev/null || shopt -u nocasematch
    return 1
}

# test: 是「打包演练」前缀 —— 只跑 CI 构建，不推进版本号。
#
# 它不进 STAGES 状态机（bump-version.py 不认识 test），也不会出现在
# 任何发版路径上。这里识别它只是为了：
#   1. --detect 能告诉调用方「这是演练，不是发版」
#   2. --prepare 遇到它时给出明确提示，而不是笼统地报「版本推进失败」
is_dryrun_stage() {
    [[ "$1" == "test" ]]
}

# 判断版本是否预发布（含 -dev / -beta / -RC）
is_prerelease() {
    [[ "$1" == *-* ]]
}

# 往 CI 的环境文件写变量。
#
# GitHub Actions 用 $GITHUB_ENV，CNB 用 $CNB_ENV（如果存在），
# 都没有就打到 stdout（本地跑时方便看）。
emit_var() {
    local name="$1" value="$2"
    local wrote=false
    if [[ -n "${GITHUB_ENV:-}" && -w "${GITHUB_ENV:-/dev/null}" ]]; then
        echo "${name}=${value}" >> "$GITHUB_ENV"
        wrote=true
    fi
    # CNB 的变量注入方式：写到一个约定文件，由 .cnb.yml 读取。
    # 这里统一用 CI_ENV_FILE，两端脚本里都会设置它。
    if [[ -n "${CI_ENV_FILE:-}" ]]; then
        echo "${name}=${value}" >> "$CI_ENV_FILE"
        wrote=true
    fi
    if [[ "$wrote" == "false" ]]; then
        echo "  ${name}=${value}"
    fi
}

# ------------------------------------------------------------------ 子命令

cmd_detect() {
    local msg="${1:-${RELEASE_MESSAGE:-}}"
    local stage
    if ! stage="$(detect_stage "$msg")"; then
        echo "不是发版提交（提交信息首行不以 rel:/RC:/beta:/dev:/test: 开头）"
        return 1
    fi
    echo "$stage"
}

cmd_prepare() {
    local msg="${1:-${RELEASE_MESSAGE:-}}"
    [[ -n "$msg" ]] || die "没有提交信息。用 --prepare \"rel: xxx\" 或设 RELEASE_MESSAGE"

    local stage
    stage="$(detect_stage "$msg")" || die "提交信息首行不是发版前缀（rel:/RC:/beta:/dev:/test:）"

    # test: 是演练前缀，明确拒绝推进版本 ——
    # 给出可执行的建议，而不是让 bump-version 报「未知阶段」。
    if is_dryrun_stage "$stage"; then
        echo "注意：$stage: 是打包演练前缀，不会推进版本号。" >&2
        echo "      CI 会跑一遍完整打包但不发版。" >&2
        echo "      要真正发版，请用 rel: / RC: / beta: / dev:。" >&2
        emit_var "RELEASE_STAGE" "test"
        emit_var "VERSION_CHANGED" "false"
        return 0
    fi

    local current next
    # 先读当前值（--quiet 模式下 bump-version 只输出结果，这里要原始值）
    current="$(sed -n 's/^appVersion[[:space:]]*=[[:space:]]*//p' "$GRADLE_PROPS" | head -1 | tr -d '\r' | xargs)"
    [[ -n "$current" ]] || die "gradle.properties 里没有 appVersion"

    # 计算下一个版本。失败时把 bump-version 的详细错误打出来 ——
    # 它会把「为什么不能倒退」说清楚，比一句「版本推进失败」有用得多。
    if ! next="$(python3 "$BUMP" --current "$current" --stage "$stage" --quiet 2>&1)"; then
        echo "$next" >&2
        die "版本推进失败"
    fi

    info "阶段: $stage"
    info "版本: $current -> $next"

    python3 "$BUMP" --current "$current" --stage "$stage" --apply "$GRADLE_PROPS" --quiet > /dev/null
    info "已写入 gradle.properties"

    local tag="v${next}"
    local pre="false"
    is_prerelease "$next" && pre="true"

    emit_var "APP_VERSION" "$next"
    emit_var "RELEASE_STAGE" "$stage"
    emit_var "RELEASE_TAG" "$tag"
    emit_var "IS_PRERELEASE" "$pre"
    emit_var "VERSION_PREV" "$current"

    # 供 CI 判断用：这次发版是否需要开 PR（即版本号确实变了）
    if [[ "$current" == "$next" ]]; then
        emit_var "VERSION_CHANGED" "false"
    else
        emit_var "VERSION_CHANGED" "true"
    fi

    echo ""
    echo "版本 $current -> $next  (tag: $tag, 预发布: $pre)"
}

cmd_verify() {
    # 演练提交不发版。
    #
    # 放在这里而不是 workflow 的 if 里：GHA 表达式没有正则，
    # 用 startsWith 得写 test:/TEST:/Test: 三个变体，还漏 tEsT:。
    # 统一由脚本判断，一处生效，GitHub 和 CNB 都走同一条逻辑。
    local msg="${1:-${RELEASE_MESSAGE:-${CI_COMMIT_MESSAGE:-}}}"
    if [[ -n "$msg" ]]; then
        local stage
        if stage="$(detect_stage "$msg")" && is_dryrun_stage "$stage"; then
            echo "这是 test: 演练提交，跳过发版。"
            emit_var "APP_VERSION" ""
            emit_var "RELEASE_TAG" ""
            emit_var "IS_PRERELEASE" ""
            return 0
        fi
    fi

    local version tag
    version="$(sed -n 's/^appVersion[[:space:]]*=[[:space:]]*//p' "$GRADLE_PROPS" | head -1 | tr -d '\r' | xargs)"
    [[ -n "$version" ]] || die "gradle.properties 里没有 appVersion"

    # 校验版本号格式本身合法
    python3 - <<PY || die "版本号格式非法：$version"
import sys
sys.path.insert(0, "scripts")
from importlib.util import spec_from_file_location, module_from_spec
spec = spec_from_file_location("bv", "scripts/bump-version.py")
bv = module_from_spec(spec); spec.loader.exec_module(bv)
bv.parse_version("$version")
PY

    tag="v${version}"

    # 同一个 tag 不能发两次
    if git rev-parse -q --verify "refs/tags/${tag}" > /dev/null 2>&1; then
        die "tag ${tag} 已存在。版本号没变？检查 gradle.properties"
    fi

    local pre="false"
    is_prerelease "$version" && pre="true"

    info "校验通过：$version"
    emit_var "APP_VERSION" "$version"
    emit_var "RELEASE_TAG" "$tag"
    emit_var "IS_PRERELEASE" "$pre"

    echo ""
    echo "将发布 tag: $tag  (预发布: $pre)"
}

cmd_tag() {
    # 打 tag 并推送。由 CI 在 verify 之后调用。
    local version tag
    version="$(sed -n 's/^appVersion[[:space:]]*=[[:space:]]*//p' "$GRADLE_PROPS" | head -1 | tr -d '\r' | xargs)"
    tag="v${version}"

    if git rev-parse -q --verify "refs/tags/${tag}" > /dev/null 2>&1; then
        info "tag ${tag} 已存在，跳过"
        return 0
    fi

    info "创建 tag $tag"
    git config user.name  "${GIT_AUTHOR_NAME:-hmwlx-bot}"
    git config user.email "${GIT_AUTHOR_EMAIL:-bot@cn.xiaofan}"
    git tag -a "$tag" -m "HMWLX $version"
    info "推送 tag"
    git push origin "refs/tags/${tag}"
}

usage() {
    sed -n '3,45p' "$0" | sed 's/^# \{0,1\}//'
}

# ------------------------------------------------------------------ 入口

case "${1:-}" in
    --detect)
        shift
        cmd_detect "$@"
        ;;
    --prepare)
        shift
        cmd_prepare "$@"
        ;;
    --verify)
        shift
        cmd_verify "$@"
        ;;
    --tag)
        shift
        cmd_tag "$@"
        ;;
    -h|--help|"")
        usage
        ;;
    *)
        die "未知参数：$1（用 --help 看用法）"
        ;;
esac
