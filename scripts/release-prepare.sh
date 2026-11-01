#!/usr/bin/env bash
#
# 发版：推进版本号、推分支、开 PR。
#
# 为什么单独成文件而不是内联在 .cnb.yml 的 script: 里：
#
#   CNB 的 script 任务用 sh（dash）兼容的方式执行，`[[ ]]`、`source`
#   这些 bash 扩展会报 `sh: 21: [[: not found` —— 而且由于 dash 对
#   未知命令返回 0，错误还可能被吞掉，跑到后面才炸（比如「release.sh
#   没有输出任何变量」其实是前面 [[ 直接失败导致的空值）。
#
#   所以所有逻辑挪到这里，文件开头 #!/usr/bin/env bash，由 script
#   显式 `bash ./scripts/release-prepare.sh` 调用，不依赖平台的默认 shell。
#
# 用法（由 .cnb.yml 调用，也可本地手动跑）：
#   bash ./scripts/release-prepare.sh "dev:"
#
set -euo pipefail

MESSAGE="${1:-${CNB_COMMIT_MESSAGE_TITLE:-}}"

if [[ -z "$MESSAGE" ]]; then
    echo "错误：没有提交信息（用法：release-prepare.sh \"dev: ...\"）" >&2
    exit 1
fi

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

# ---------------------------------------------------------------- 版本号
#
# release.sh 通过 $CI_ENV_FILE 把变量交给调用方。
# 不设这个变量的话，APP_VERSION / VERSION_CHANGED 只会打到 stdout，
# 后续步骤读不到 —— 之前就是这里没接上，发版步骤等于空转。
CI_ENV_FILE="$(mktemp)"
# shellcheck disable=SC2064  # 有意立即展开路径
trap "rm -f '$CI_ENV_FILE'" EXIT
export CI_ENV_FILE

echo "发版/演练提交：$MESSAGE"
./scripts/release.sh --prepare "$MESSAGE"

if [[ ! -s "$CI_ENV_FILE" ]]; then
    echo "错误：release.sh 没有输出任何变量" >&2
    exit 1
fi

echo "  release.sh 输出："
while IFS='=' read -r k v; do
    [[ -n "$k" ]] && printf '    %s=%s\n' "$k" "$v"
done < "$CI_ENV_FILE"

# 读进当前进程
set -a
# shellcheck disable=SC1090  # 路径是运行时变量
source "$CI_ENV_FILE"
set +a

# ---------------------------------------------------------------- 开 PR
#
# 只在版本真的变了时做。test: 前缀时 VERSION_CHANGED=false ——
# 那是打包演练，不该推进版本号。
if [[ "${VERSION_CHANGED:-false}" != "true" ]]; then
    echo "版本号未变化（演练或非发版提交），跳过开 PR"
    exit 0
fi

BRANCH="release/v${APP_VERSION}"
BASE_BRANCH="${CNB_DEFAULT_BRANCH:-master}"
echo "推进版本号：${VERSION_PREV} -> ${APP_VERSION}"
echo "分支：$BRANCH，目标：$BASE_BRANCH"

# ---------------------------------------------------------------- 推送目标
#
# 不能直接 `git push origin`：CNB 的代码是平台用独立 service 拉下来的，
# 容器里的 .git/config 不一定留了可用的凭据（而且构建镜像原本连 git
# 都没装）。
#
# 用 CNB_TOKEN 现造一个带认证的 URL：
#   https://cnb:<CNB_TOKEN>@<host>/<repo>.git
# 用户名固定为 cnb（文档的 CNB_TOKEN_USER_NAME 就是这个值）。
#
# 优先用 CNB_REPO_URL_HTTPS（平台注入，已经是完整地址），
# 没有就按 CNB_WEB_HOST + CNB_REPO_SLUG 拼。
if [[ -n "${CNB_TOKEN:-}" ]]; then
    if [[ -n "${CNB_REPO_URL_HTTPS:-}" ]]; then
        _url="$CNB_REPO_URL_HTTPS"
    else
        _url="https://${CNB_WEB_HOST}/${CNB_REPO_SLUG}.git"
    fi
    _user="${CNB_TOKEN_USER_NAME:-cnb}"
    # 令牌写进临时凭据文件，而不是塞进 URL ——
    # git 报错时会把含令牌的 URL 原样打出来，日志里就泄漏了。
    # 用 credential.helper=store + 临时文件，命令行里只有干净地址。
    CRED_FILE="$(mktemp)"
    chmod 600 "$CRED_FILE"
    _clean="${_url#https://}"
    _clean="${_clean#http://}"
    printf 'https://%s:%s@%s\n' "$_user" "$CNB_TOKEN" "${_clean#*@}" > "$CRED_FILE"
    export GIT_TERMINAL_PROMPT=0
    REMOTE="${_url}"
    GIT_CRED_OPTS=(-c "credential.helper=store --file=$CRED_FILE")
    unset _url _clean _user
else
    # 本地直接跑脚本时没有 CNB_TOKEN，回落到 origin
    echo "（没有 CNB_TOKEN，用 origin）"
    REMOTE="origin"
    CRED_FILE=""
    GIT_CRED_OPTS=()
fi

_cleanup_cred() { [[ -n "$CRED_FILE" ]] && rm -f "$CRED_FILE"; }
trap _cleanup_cred EXIT

git config user.name  "cnb"
git config user.email "cnb@cnb.cool"

# 幂等：上次可能已经把分支推上去了（比如开 PR 那步挂了），
# 重跑时直接覆盖，不要因为 non-fast-forward 让整个流水线失败。
REMOTE_SHA="$(git ${GIT_CRED_OPTS[@]+"${GIT_CRED_OPTS[@]}"} ls-remote --heads "$REMOTE" "$BRANCH" | awk '{print $1}')"

git checkout -b "$BRANCH"
git add gradle.properties
git commit \
    -m "chore(release): 版本号 ${VERSION_PREV} -> ${APP_VERSION}" \
    -m "由 CNB 流水线自动生成。阶段：${RELEASE_STAGE}，tag：${RELEASE_TAG}，预发布：${IS_PRERELEASE}"

if [[ -n "$REMOTE_SHA" ]]; then
    # 覆盖前先确认远端那份改的确实是同一个版本号，
    # 否则说明分支被人动过，宁可报错也别乱推。
    REMOTE_VER="$(git show "${REMOTE_SHA}:gradle.properties" 2>/dev/null \
                  | sed -n 's/^appVersion[[:space:]]*=[[:space:]]*//p' \
                  | tr -d '\r')"
    if [[ "$REMOTE_VER" != "$APP_VERSION" ]]; then
        echo "错误：远端 $BRANCH 的 appVersion 是 '$REMOTE_VER'，不是 '$APP_VERSION'" >&2
        echo "      这个分支可能被改过，请人工确认后再重跑。" >&2
        exit 1
    fi
    echo "远端已有 $BRANCH（$REMOTE_SHA），内容一致，覆盖"
    git ${GIT_CRED_OPTS[@]+"${GIT_CRED_OPTS[@]}"} push --force-with-lease="refs/heads/${BRANCH}:${REMOTE_SHA}" \
        "$REMOTE" "$BRANCH"
else
    git ${GIT_CRED_OPTS[@]+"${GIT_CRED_OPTS[@]}"} push "$REMOTE" "$BRANCH"
fi

# ---------------------------------------------------------------- PR 正文
PRBODY="$(mktemp)"
# shellcheck disable=SC2064
trap "rm -f '$CI_ENV_FILE' '$PRBODY'" EXIT

{
    printf '## 发版：%s\n\n' "$APP_VERSION"
    printf '| 项 | 值 |\n|---|---|\n'
    printf '| 版本 | `%s` → `%s` |\n' "$VERSION_PREV" "$APP_VERSION"
    printf '| 阶段 | `%s` |\n' "$RELEASE_STAGE"
    printf '| Tag | `%s` |\n' "$RELEASE_TAG"
    printf '| 预发布 | %s |\n\n' "$IS_PRERELEASE"
    printf '合并到 `%s` 后，release-tag 流程会自动打 tag 并构建发布；\n' "$BASE_BRANCH"
    printf 'CNB 制品库那边的产物也已同步产出。\n\n'
    printf -- '---\n'
    printf '*此 PR 由 CNB 流水线自动创建。*\n'
} > "$PRBODY"

python3 ./scripts/cnb-pr.py \
    --repo  "${CNB_REPO_SLUG}" \
    --head  "$BRANCH" \
    --base  "$BASE_BRANCH" \
    --title "chore(release): ${APP_VERSION}" \
    --body-file "$PRBODY"

# ---------------------------------------------------------------- 回到原分支
#
# 后面的构建必须在原始代码上跑，不能停在 release 分支上。
git checkout "${CNB_BRANCH:-$BASE_BRANCH}"
echo "已回到分支：$(git branch --show-current)"
