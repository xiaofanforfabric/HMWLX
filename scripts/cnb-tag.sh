#!/usr/bin/env bash
#
# 在 CNB 上给当前提交打 tag 并推送。
#
# 什么时候跑：PR 合并到主分支之后（.cnb.yml 的 release-tag stage）。
#
# 为什么需要：原来设计上 tag 完全交给 GitHub 的 release-tag.yml，
# CNB 只负责构建。但主分支合并后 GitHub 那边因为
#   release.sh --verify 的输出写进 $GITHUB_ENV，当前 step 读不到
# 一直没打出 tag。补上这一步，让 CNB 自己能闭环发版。
#
# 两个平台都打同一个 tag 会冲突，所以这里做幂等：
# 远端已有同名 tag 就跳过，不报错。
#
set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")/.."

# 只有主分支合并后的 push 才需要打 tag。
# 其它分支、PR 检查都不该动 tag。
BRANCH="${CNB_BRANCH:-}"
DEFAULT_BRANCH="${CNB_DEFAULT_BRANCH:-master}"

if [[ -n "$BRANCH" && "$BRANCH" != "$DEFAULT_BRANCH" ]]; then
    echo "当前分支 $BRANCH 不是主分支 $DEFAULT_BRANCH，跳过打 tag"
    exit 0
fi

# ------------------------------------------------------------ 版本号
VERSION="$(sed -n 's/^appVersion[[:space:]]*=[[:space:]]*//p' gradle.properties \
           | head -1 | tr -d '\r' | xargs)"
if [[ -z "$VERSION" ]]; then
    echo "错误：gradle.properties 里没有 appVersion" >&2
    exit 1
fi

# 校验版本号格式（复用 bump-version.py 的解析器，单一事实源）
python3 - "$VERSION" <<'PY' || { echo "错误：版本号格式非法：$VERSION" >&2; exit 1; }
import sys
sys.path.insert(0, "scripts")
from importlib.util import spec_from_file_location, module_from_spec
spec = spec_from_file_location("bv", "scripts/bump-version.py")
bv = module_from_spec(spec)
spec.loader.exec_module(bv)
bv.parse_version(sys.argv[1])
PY

TAG="v${VERSION}"
echo "版本: $VERSION，tag: $TAG"

# ------------------------------------------------------------ 推送目标
#
# 与 release-prepare.sh 同样的做法：用 CNB_TOKEN 走 http.extraheader，
# 不把令牌写进 URL（git 报错会把 URL 原样打出来，会泄漏）。
if [[ -n "${CNB_TOKEN:-}" ]]; then
    if [[ -n "${CNB_REPO_URL_HTTPS:-}" ]]; then
        _url="$CNB_REPO_URL_HTTPS"
    else
        _url="https://${CNB_WEB_HOST}/${CNB_REPO_SLUG}.git"
    fi
    [[ "$_url" == *.git ]] || _url="${_url%.git}.git"

    _basic="$(printf '%s:%s' "${CNB_TOKEN_USER_NAME:-cnb}" "$CNB_TOKEN" \
              | base64 | tr -d '\n')"
    export GIT_TERMINAL_PROMPT=0
    REMOTE="$_url"
    GIT_OPTS=(-c "http.${_url}.extraheader=Authorization: Basic ${_basic}")
    unset _url _basic
else
    echo "（没有 CNB_TOKEN，用 origin）"
    REMOTE="origin"
    GIT_OPTS=()
fi

# ------------------------------------------------------------ 幂等检查
REMOTE_TAG="$(git ${GIT_OPTS[@]+"${GIT_OPTS[@]}"} ls-remote --tags "$REMOTE" "refs/tags/$TAG" \
              | awk '{print $1}' | head -1)"

if [[ -n "$REMOTE_TAG" ]]; then
    echo "远端已有 tag $TAG（$REMOTE_TAG），跳过"
    exit 0
fi

# ------------------------------------------------------------ 打 tag
git config user.name  "cnb"
git config user.email "cnb@cnb.cool"

# 注解 tag，带版本号和阶段说明
if [[ "$VERSION" == *-* ]]; then
    _note="HMWLX $VERSION（预发布）"
else
    _note="HMWLX $VERSION"
fi

git tag -a "$TAG" -m "$_note"
echo "已创建 tag $TAG"

git ${GIT_OPTS[@]+"${GIT_OPTS[@]}"} push "$REMOTE" "refs/tags/$TAG"
echo "已推送 tag $TAG"
