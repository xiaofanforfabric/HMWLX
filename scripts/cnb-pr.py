#!/usr/bin/env python3
"""
用 CNB OpenAPI 创建合并请求（PR）。

背景
====

发版流程需要「算出版本号 -> 提交到新分支 -> 开 PR -> 合并后打 tag」。
GitHub Actions 那边有 `gh pr create`，但需要仓库开
「Allow GitHub Actions to create and approve pull requests」开关。

CNB 这边其实也能开 PR：`POST /{repo}/-/pulls`（operationId: PostPull），
而 `push` 事件下的 `CNB_TOKEN` 已经有 `repo-pr:rw` 权限。所以不需要
NPC，也不需要额外开关。

用法
====

    python3 scripts/cnb-pr.py \
        --repo   xiaofanforandroid/HMWLX/HMWLX \
        --head   release/v0.1-dev1 \
        --base   master \
        --title  "chore(release): 0.1-dev1" \
        --body   /tmp/pr-body.md

环境变量：
    CNB_TOKEN           流水线内置令牌（自动注入）
    CNB_API_ENDPOINT    API 地址，默认 https://api.cnb.cool

幂等：同 head->base 的 PR 已存在时不报错，原样返回已有 PR 的编号。
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request


DEFAULT_API = "https://api.cnb.cool"


def api_request(method, url, token, payload=None, timeout=30):
    """发一个 API 请求，返回 (status, parsed_json_or_text)。"""
    data = None
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.cnb.api+json",
    }
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"

    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", "replace")
            try:
                return resp.status, json.loads(raw)
            except json.JSONDecodeError:
                return resp.status, raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        try:
            return e.code, json.loads(raw)
        except json.JSONDecodeError:
            return e.code, raw


def find_existing(api, repo, token, head, base):
    """查是否已有 head->base 的 PR，返回编号或 None。"""
    # GET /{repo}/-/pulls 的查询参数随版本可能不同，最稳的做法是
    # 拉列表再自己筛。列表默认按创建时间倒序，取前 100 条够用。
    status, data = api_request(
        "GET", f"{api}/{repo}/-/pulls?page=1&page_size=100", token)

    if status != 200:
        # 查不到不算错误 —— 交给后面的 create 去撞，让 API 报真实原因
        return None

    items = data if isinstance(data, list) else data.get("data") or data.get("items") or []
    for it in items:
        h = it.get("head") or {}
        b = it.get("base") or {}
        head_ref = h.get("ref") or h.get("label") or h.get("name")
        base_ref = b.get("ref") or b.get("label") or b.get("name")
        if head_ref == head and base_ref == base:
            num = it.get("number") or it.get("iid")
            state = (it.get("state") or "").lower()
            print(f"已存在 PR #{num}（{head} -> {base}，状态 {state}）")
            return num
    return None


def main(argv):
    ap = argparse.ArgumentParser(description="用 CNB OpenAPI 创建 PR")
    ap.add_argument("--repo", required=True, help="组织/仓库，不带 .git")
    ap.add_argument("--head", required=True, help="源分支")
    ap.add_argument("--base", required=True, help="目标分支")
    ap.add_argument("--title", required=True, help="PR 标题")
    ap.add_argument("--body", help="PR 内容，或包含内容的文件路径")
    ap.add_argument("--body-file", help="PR 内容文件（与 --body 二选一）")
    args = ap.parse_args(argv)

    token = os.environ.get("CNB_TOKEN")
    if not token:
        print("错误：环境变量 CNB_TOKEN 为空（不在 CNB 流水线里？）", file=sys.stderr)
        return 1

    api = (os.environ.get("CNB_API_ENDPOINT") or DEFAULT_API).rstrip("/")

    body = ""
    if args.body_file:
        with open(args.body_file, encoding="utf-8") as f:
            body = f.read()
    elif args.body:
        body = args.body

    # 先查重：重跑时不该报 409
    existing = find_existing(api, args.repo, token, args.head, args.base)
    if existing:
        print(f"跳过创建，复用 PR #{existing}")
        return 0

    payload = {
        "head": args.head,
        "base": args.base,
        "title": args.title,
        "body": body,
    }

    print(f"创建 PR: {args.head} -> {args.base}")
    status, data = api_request(
        "POST", f"{api}/{args.repo}/-/pulls", token, payload)

    if status not in (200, 201):
        print(f"错误：创建 PR 失败，HTTP {status}", file=sys.stderr)
        print(json.dumps(data, ensure_ascii=False, indent=2)
              if not isinstance(data, str) else data, file=sys.stderr)
        return 1

    num = None
    if isinstance(data, dict):
        num = data.get("number") or data.get("iid")

    print(f"成功：PR #{num}" if num else "成功：PR 已创建")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
