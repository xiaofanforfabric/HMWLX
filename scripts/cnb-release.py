#!/usr/bin/env python3
"""
用 CNB OpenAPI 发布版本（Release）并上传产物附件。

背景
====

CNB 的「制品库」只托管 Docker / Helm / Maven / npm 这类包管理格式，
不托管 .exe / .deb / .rpm / AppImage 这种分发包。想把这些文件挂到
tag 下面给用户下载，得用 CNB 的「版本发布」（Release）功能 ——
和 GitHub Release 是一个概念。

之前 .cnb.yml 的 upload 阶段只 echo 了一下文件名，所以流水线「成功」
了但什么都没发出去。这个脚本补上真正的动作。

流程
====

    POST /{repo}/-/releases                      创建版本
    POST /{repo}/-/releases/{id}/asset-upload-url 申请附件上传地址
    PUT  {upload_url}                             直接传到对象存储
    POST /{repo}/-/releases/{id}/asset-upload-confirmation/...  确认

第三步是往对象存储 PUT，不带 Authorization 头（地址本身带签名）。

用法
====

    python3 scripts/cnb-release.py \
        --repo  xiaofanforandroid/HMWLX/HMWLX \
        --tag   v0.1-dev1 \
        --name  HMWLX 0.1-dev1 \
        --prerelease \
        --note-file /tmp/release-note.md \
        dist/*.exe dist/*.deb dist/*.rpm

环境变量：
    CNB_TOKEN           流水线内置令牌（自动注入，需 repo-release:rw）
    CNB_API_ENDPOINT    API 地址，默认 https://api.cnb.cool

幂等：
    - 版本已存在 -> 复用，不重建
    - 附件同名已存在 -> 先删后传（overwrite 语义）
    这样流水线重跑不会因为「已存在」而失败。
"""

import argparse
import glob
import json
import os
import sys
import time
import urllib.error
import urllib.request


DEFAULT_API = "https://api.cnb.cool"


def api_request(method, url, token, payload=None, timeout=60, raw_body=None,
                content_type=None):
    """发一个 API 请求，返回 (status, parsed_json_or_text)。"""
    headers = {"Accept": "application/vnd.cnb.api+json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"

    data = None
    if payload is not None:
        data = json.dumps(payload).encode("utf-8")
        headers["Content-Type"] = "application/json"
    elif raw_body is not None:
        data = raw_body
        if content_type:
            headers["Content-Type"] = content_type

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


def unwrap(data):
    """CNB 有些接口把结果包在 {"data": ...} 里，有些直接返回，统一一下。"""
    if isinstance(data, dict) and "data" in data and len(data) <= 2:
        return data["data"]
    return data


def auto_note(files):
    """没给描述时自动生成一份，至少让版本页不是空白。

    按扩展名分组列出文件名和大小，用户一眼能看出该下哪个。
    """
    groups = {
        "Windows": (".exe",),
        "macOS": (".dmg",),
        "Linux": (".run", ".AppImage", ".deb", ".rpm", ".tar.gz"),
        "JAR": ("-all.jar",),
    }
    lines = []
    for title, exts in groups.items():
        picked = [f for f in files if os.path.basename(f).endswith(exts)]
        if not picked:
            continue
        lines.append(f"### {title}")
        lines.append("")
        for f in picked:
            size = os.path.getsize(f)
            lines.append(f"- `{os.path.basename(f)}`"
                         f"（{size / 1024 / 1024:.0f} MB）")
        lines.append("")
    return "\n".join(lines).strip()


def find_release(api, repo, token, tag):
    """按 tag 查已有版本，返回 release dict 或 None。"""
    status, data = api_request(
        "GET", f"{api}/{repo}/-/releases/tags/{urllib.request.quote(tag)}", token)
    if status == 200:
        return unwrap(data)
    return None


def release_id_of(rel):
    """版本对象里的 id 字段名可能变，兼容几种写法。"""
    for k in ("id", "release_id", "releaseId"):
        if isinstance(rel, dict) and rel.get(k) is not None:
            return rel[k]
    return None


def create_release(api, repo, token, tag, name, note, prerelease):
    """创建版本。已存在则复用。"""
    payload = {
        "tag_name": tag,
        "name": name or tag,
        "body": note or "",
        "prerelease": bool(prerelease),
        # dev/beta/rc 之类不抢 latest，避免把正式版挤下去
        "make_latest": "false" if prerelease else "true",
    }

    print(f"创建版本 {tag} …")
    status, data = api_request(
        "POST", f"{api}/{repo}/-/releases", token, payload)

    if status in (200, 201):
        rel = unwrap(data)
        rid = release_id_of(rel)
        print(f"  已创建：id={rid}")
        return rel

    if status in (409, 422):
        print(f"  版本已存在（HTTP {status}），复用")
        rel = find_release(api, repo, token, tag)
        if rel:
            return rel

    print(f"错误：创建版本失败，HTTP {status}", file=sys.stderr)
    print(json.dumps(data, ensure_ascii=False, indent=2)
          if not isinstance(data, str) else data, file=sys.stderr)
    return None


def delete_asset(api, repo, token, rid, asset_id):
    status, _ = api_request(
        "DELETE", f"{api}/{repo}/-/releases/{rid}/assets/{asset_id}", token)
    return status in (200, 204)


def existing_assets(api, repo, token, rid):
    """列出已有附件，返回 {name: id}。"""
    status, data = api_request(
        "GET", f"{api}/{repo}/-/releases/{rid}", token)
    if status != 200:
        return {}
    rel = unwrap(data) or {}
    items = rel.get("assets") or []
    out = {}
    for a in items:
        n = a.get("name") or a.get("asset_name")
        aid = a.get("id") or a.get("asset_id")
        if n and aid is not None:
            out[n] = aid
    return out


def upload_one(api, repo, token, rid, path):
    """上传单个附件：申请地址 -> PUT -> 确认。"""
    name = os.path.basename(path)
    size = os.path.getsize(path)
    print(f"  上传 {name} ({size / 1024 / 1024:.1f} MB) …")

    # 1) 申请上传地址
    status, data = api_request(
        "POST", f"{api}/{repo}/-/releases/{rid}/asset-upload-url", token,
        {"asset_name": name, "size": size, "overwrite": True})

    if status not in (200, 201):
        print(f"    错误：申请上传地址失败，HTTP {status}", file=sys.stderr)
        print(json.dumps(data, ensure_ascii=False, indent=2)
              if not isinstance(data, str) else data, file=sys.stderr)
        return False

    info = unwrap(data) or {}
    upload_url = info.get("upload_url")
    verify_url = info.get("verify_url")
    if not upload_url:
        print(f"    错误：返回里没有上传地址：{info}", file=sys.stderr)
        return False

    # 2) PUT 到对象存储。签名在 URL 里，不能再带 Authorization，
    #    带了会被当成另一套鉴权而拒绝。
    t0 = time.time()
    try:
        req = urllib.request.Request(upload_url, data=open(path, "rb").read(),
                                     method="PUT")
        req.add_header("Content-Type", "application/octet-stream")
        with urllib.request.urlopen(req, timeout=1800) as resp:
            if resp.status >= 300:
                print(f"    错误：上传失败，HTTP {resp.status}", file=sys.stderr)
                return False
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", "replace")
        print(f"    错误：上传失败，HTTP {e.code}: {raw[:300]}", file=sys.stderr)
        return False
    except Exception as e:  # noqa: BLE001 - 网络问题统一报
        print(f"    错误：上传异常 {type(e).__name__}: {e}", file=sys.stderr)
        return False

    dt = time.time() - t0
    print(f"    已传 {size / 1024 / 1024:.1f} MB，用时 {dt:.1f}s"
          f"（{size / 1024 / 1024 / max(dt, 0.001):.1f} MB/s）")

    # 3) 确认。upload_token 和 asset_path 都要从 verify_url 里提取 ——
    #    API 文档写得很含蓄：「PostReleaseAssetUploadURL 接口返回值
    #    verify_url 字段提取的 upload_token」，也就是说这两个值不是
    #    独立字段，而是嵌在确认地址的路径里。
    if verify_url:
        # verify_url 可能是完整 URL，也可能是相对路径，补全一下
        if not verify_url.startswith(("http://", "https://")):
            verify_url = f"{api}/{verify_url.lstrip('/')}"
        # 直接 POST verify_url 就是确认，不用自己拼路径
        status, data = api_request("POST", verify_url, token)
        if status not in (200, 201, 204):
            print(f"    错误：确认上传失败，HTTP {status}", file=sys.stderr)
            print(json.dumps(data, ensure_ascii=False, indent=2)
                  if not isinstance(data, str) else data, file=sys.stderr)
            return False
        print("    已确认")
    else:
        print("    提示：返回里没有 verify_url，跳过确认步骤")

    return True


def main(argv):
    ap = argparse.ArgumentParser(description="用 CNB OpenAPI 发布版本并上传附件")
    ap.add_argument("--repo", required=True, help="组织/仓库，不带 .git")
    ap.add_argument("--tag", required=True, help="tag 名，如 v0.1-dev1")
    ap.add_argument("--name", help="版本标题，默认用 tag")
    ap.add_argument("--note", help="版本描述")
    ap.add_argument("--note-file", help="版本描述文件（与 --note 二选一）")
    ap.add_argument("--prerelease", action="store_true",
                    help="标记为预发布（dev/beta/rc 用）")
    ap.add_argument("files", nargs="*", help="要上传的附件路径")
    args = ap.parse_args(argv)

    token = os.environ.get("CNB_TOKEN")
    if not token:
        print("错误：环境变量 CNB_TOKEN 为空（不在 CNB 流水线里？）", file=sys.stderr)
        return 1

    api = (os.environ.get("CNB_API_ENDPOINT") or DEFAULT_API).rstrip("/")

    note = args.note or ""
    if args.note_file:
        with open(args.note_file, encoding="utf-8") as f:
            note = f.read()

    # 参数里可以直接写 glob（流水线里 shell 展开不了时方便）
    files = []
    for pat in args.files:
        expanded = sorted(glob.glob(pat))
        if expanded:
            files.extend(expanded)
        elif os.path.isfile(pat):
            files.append(pat)
        else:
            print(f"警告：找不到 {pat}，跳过", file=sys.stderr)

    if not files:
        print("错误：没有可上传的附件", file=sys.stderr)
        return 1

    if not note.strip():
        note = auto_note(files)

    print(f"共 {len(files)} 个附件待上传")

    rel = find_release(api, args.repo, token, args.tag)
    if rel:
        rid = release_id_of(rel)
        print(f"版本 {args.tag} 已存在，复用 id={rid}")
    else:
        rel = create_release(api, args.repo, token, args.tag,
                             args.name, note, args.prerelease)
        if not rel:
            return 1
        rid = release_id_of(rel)

    if rid is None:
        print("错误：拿不到版本 id", file=sys.stderr)
        return 1

    # 同名附件先删，保证重跑能覆盖
    olds = existing_assets(api, args.repo, token, rid)
    for path in files:
        name = os.path.basename(path)
        if name in olds:
            print(f"  删除同名旧附件 {name}")
            delete_asset(api, args.repo, token, rid, olds[name])

    ok = 0
    failed = []
    for path in files:
        if upload_one(api, args.repo, token, rid, path):
            ok += 1
        else:
            failed.append(os.path.basename(path))

    print("")
    print(f"完成：{ok}/{len(files)} 个附件上传成功")
    if failed:
        print(f"失败：{', '.join(failed)}", file=sys.stderr)
        return 1

    print(f"版本地址：https://cnb.cool/{args.repo}/-/releases/tag/"
          f"{urllib.request.quote(args.tag)}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
