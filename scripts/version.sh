#!/usr/bin/env bash
#
# 版本号与产物路径的公共定义 —— 被 scripts/pack-*.sh source 使用。
#
# 设计：唯一真相是 gradle.properties 里的 appVersion。
# 打 gradle 的 version 也是从那里读的，所以 jar 文件名里的版本号和
# 这里的 appVersion 永远一致，不会出现「脚本找 xx-1.0.jar 但产物叫
# xx-0.1.jar」这种问题。
#
# 不要 source 这个文件后自己再定义 VERSION —— 用 $APP_VERSION。
#
# 提供的变量：
#   APP_VERSION      原始版本，如 0.1-dev
#   DEB_VERSION      Debian 风格，把 -dev 变成 ~dev（~ 排序低于无后缀，
#                    保证 0.1 正式版能覆盖 0.1~dev 预发布版）
#   RPM_VERSION      RPM 风格，把 -dev 变成 .dev（RPM 不允许连字符）
#   FAT_JAR          构建产物的绝对路径

set -euo pipefail

# 定位项目根（本文件在 scripts/ 下）
_VERSION_SH_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
_GRADLE_PROPS="$_VERSION_SH_ROOT/gradle.properties"

if [[ ! -f "$_GRADLE_PROPS" ]]; then
    echo "错误：找不到 $_GRADLE_PROPS" >&2
    return 1
fi

# 从 gradle.properties 读 appVersion。
# 不用 source 是因为那是 Java properties 格式，不是 shell 语法
# （值里可能有空格、等号，直接 source 会出错）。
APP_VERSION="$(sed -n 's/^appVersion[[:space:]]*=[[:space:]]*//p' "$_GRADLE_PROPS" | head -1 | tr -d '\r' | xargs)"

if [[ -z "$APP_VERSION" ]]; then
    echo "错误：gradle.properties 里没有 appVersion" >&2
    return 1
fi

# --- 各打包格式的版本号规范化 ---
#
# Debian：连字符只允许出现在「上游版本」和「修订号」之间，
#   0.1-dev 会被 dpkg 解析成 上游=0.1、修订=dev，语义变成「0.1 的
#   第 dev 次修订」，而不是预发布。所以要把 - 换成 ~：
#   0.1~dev 排在 0.1 之前，正式版能正确覆盖预发布版。
#
# RPM：版本字段不允许连字符，换成 . 即可（0.1.dev）。
#
DEB_VERSION="${APP_VERSION//-/\~}"
RPM_VERSION="${APP_VERSION//-/.}"

# --- fat jar 路径 ---
#
# Gradle 的 version 现在等于 appVersion，所以文件名形如
# hmwlx-0.1-dev-all.jar。
FAT_JAR="$_VERSION_SH_ROOT/build/libs/hmwlx-${APP_VERSION}-all.jar"

# 兜底：如果按版本拼出来的路径不存在，去 libs 里找最新的 *-all.jar。
# 这能容忍「改了版本但还没重新构建」的情况，给出明确提示而不是
# 一个莫名其妙的「文件不存在」。
if [[ ! -f "$FAT_JAR" ]]; then
    _fallback="$(ls -t "$_VERSION_SH_ROOT"/build/libs/*-all.jar 2>/dev/null | head -1 || true)"
    if [[ -n "$_fallback" ]]; then
        echo "警告：$FAT_JAR 不存在，改用 $_fallback" >&2
        echo "      请重新构建以同步版本号：./scripts/build.sh" >&2
        FAT_JAR="$_fallback"
    fi
fi
