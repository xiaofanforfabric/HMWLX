#!/usr/bin/env python3
"""
版本号推进器 —— 自动发版的核心逻辑。

设计原则：
  1. 纯函数，无副作用，可以单独测试
  2. 只有一处真正改文件（--apply 模式），其余都是 dry-run
  3. 版本非法组合直接报错退出非 0，让 CI 失败

======================================================================
阶段模型
======================================================================

  阶段序：dev < beta < RC < rel          （rel 是正式版）

  版本字符串形如：  <base>[-<stage><n>]
    0.1-dev1       base=0.1  stage=dev   n=1
    0.1-beta2      base=0.1  stage=beta  n=2
    0.1-RC1        base=0.1  stage=RC    n=1
    0.1            base=0.1  stage=rel   n=0（正式版不带后缀）

  无数字后缀时 n 默认为 1（如 0.1-dev 等价于 0.1-dev1）。

======================================================================
推进规则
======================================================================

从 0.1-dev 出发：

  打 rel:   0.1          （同 base 直接转正；若已是 rel 则 base+1）
  打 RC:    0.1-RC1
  打 beta:  0.1-beta1
  打 dev:   0.1-dev2     （同阶段递增）

从 0.1-beta1 出发：

  打 rel:   0.1          （转正）
  打 RC:    0.1-RC1      （阶段前进）
  打 beta:  0.1-beta2    （同阶段递增）
  打 dev:   错误         （阶段倒退）

从 0.1-RC1 出发：

  打 rel:   0.1
  打 RC:    0.1-RC2
  打 beta:  错误
  打 dev:   错误

从 0.1（rel）出发：

  打 rel:   0.2          （小版本 +1）
  打 RC:    0.2-RC1      （新小版本的 RC）
  打 beta:  0.2-beta1
  打 dev:   0.2-dev1

即：
  - 目标阶段 == 当前阶段  ->  同阶段递增（rel 则 base+1）
  - 目标阶段 >  当前阶段  ->  base 不变，目标阶段从 1 开始（rel 则直接用 base）
  - 目标阶段 <  当前阶段  ->  报错，版本不能倒退
"""

import re
import sys

STAGES = ["dev", "beta", "RC", "rel"]


class VersionError(Exception):
    """版本推进非法时抛出，CI 应当因此失败。"""


def parse_version(text):
    """
    解析版本字符串，返回 (base, stage, n)。

    base 形如 "0.1"、"1.2.3"，只允许数字和点。
    stage 是 STAGES 之一，rel 表示正式版（无后缀）。
    """
    text = text.strip()

    # 正式版：纯数字点号
    if re.fullmatch(r"\d+(?:\.\d+)*", text):
        return text, "rel", 0

    # 预发布版：base-stage<number>
    m = re.fullmatch(r"(\d+(?:\.\d+)*)-(dev|beta|RC)(\d*)", text)
    if not m:
        raise VersionError(
            f"无法解析版本号：{text!r}\n"
            f"  合法形式：0.1 / 0.1-dev / 0.1-dev2 / 0.1-beta1 / 0.1-RC1"
        )

    base, stage, num = m.group(1), m.group(2), m.group(3)
    # 无数字视为 1
    n = int(num) if num else 1
    if n < 1:
        raise VersionError(f"版本号后缀必须 >= 1：{text!r}")
    return base, stage, n


def format_version(base, stage, n):
    """把 (base, stage, n) 拼回字符串。"""
    if stage == "rel":
        return base
    return f"{base}-{stage}{n}"


def bump_base(base):
    """
    小版本 +1：0.1 -> 0.2，1.2 -> 1.3，1.2.3 -> 1.3。
    （只递增次版本号，保留主版本；末位丢弃）
    """
    parts = [int(x) for x in base.split(".")]
    if len(parts) == 1:
        # 只有一个数字时，视为主版本，次版本从 0 起
        return f"{parts[0] + 1}.0"
    parts[1] += 1
    # 递增次版本后，修订号归零（0.1.5 -> 0.2）
    return ".".join(str(x) for x in parts[:2])


def next_version(current, target_stage):
    """
    核心：给定当前版本和目标阶段，算出下一个版本。

    参数：
      current       当前版本字符串，如 "0.1-dev"
      target_stage  目标阶段，必须是 STAGES 之一

    返回：下一个版本字符串

    非法组合抛 VersionError。
    """
    if target_stage not in STAGES:
        raise VersionError(
            f"未知的阶段：{target_stage!r}（只能是 {' / '.join(STAGES)}）"
        )

    base, cur_stage, cur_n = parse_version(current)
    cur_idx = STAGES.index(cur_stage)
    tgt_idx = STAGES.index(target_stage)

    # --- 特例：当前是正式版（rel），打任何阶段都先递增小版本 ---
    #
    # 0.1 + rel  -> 0.2          （再来一个正式版）
    # 0.1 + RC   -> 0.2-RC1      （下一个版本的 RC）
    # 0.1 + beta -> 0.2-beta1
    # 0.1 + dev  -> 0.2-dev1
    #
    # 这一步必须在「阶段倒退」判断之前做：rel 是最高阶段，
    # 按阶段序比任何预发布阶段都「高」，但语义上它意味着
    # 「这个版本已经发布完了，开始做下一个」。所以不算倒退。
    if cur_stage == "rel":
        new_base = bump_base(base)
        if target_stage == "rel":
            return format_version(new_base, "rel", 0)
        return format_version(new_base, target_stage, 1)

    # --- 阶段倒退，直接拒绝 ---
    if tgt_idx < cur_idx:
        raise VersionError(
            f"版本不能倒退：当前 {current}（{cur_stage} 阶段），"
            f"不能打 {target_stage}。\n"
            f"  阶段顺序是 {' < '.join(STAGES)}，只能往下游走。"
        )

    # --- 同阶段 ---
    if tgt_idx == cur_idx:
        # 预发布阶段：序号 +1
        # （rel 的情况上面已经处理掉了，走到这里一定不是 rel）
        return format_version(base, target_stage, cur_n + 1)

    # --- 阶段前进 ---
    if target_stage == "rel":
        # 转正：base 不变
        return format_version(base, "rel", 0)
    # 进入新的预发布阶段：base 不变，序号从 1 开始
    return format_version(base, target_stage, 1)


# ======================================================================
# 命令行
# ======================================================================

def main(argv):
    import argparse
    import pathlib

    ap = argparse.ArgumentParser(
        description="计算/写入下一个版本号",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
示例：
  %(prog)s --current 0.1-dev --stage rel      # -> 0.1
  %(prog)s --current 0.1-dev --stage beta     # -> 0.1-beta1
  %(prog)s --current 0.1-beta1 --stage beta   # -> 0.1-beta2
  %(prog)s --current 0.1-beta1 --stage RC     # -> 0.1-RC1
  %(prog)s --current 0.1-beta1 --stage dev    # -> 报错退出 1
  %(prog)s --current 0.1 --stage rel          # -> 0.2

  # 直接写入 gradle.properties（CI 用）
  %(prog)s --current 0.1-dev --stage rel --apply gradle.properties
""",
    )
    ap.add_argument("--current", required=True,
                    help="当前版本，如 0.1-dev。也可写 @gradle.properties 自动读取")
    ap.add_argument("--stage", required=True, choices=STAGES,
                    help="目标阶段")
    ap.add_argument("--apply", metavar="FILE",
                    help="把结果写入 FILE 的 appVersion=（其余内容不动）")
    ap.add_argument("--quiet", action="store_true",
                    help="只输出版本号本身，便于脚本捕获")
    args = ap.parse_args(argv)

    try:
        # 支持 --current @gradle.properties
        current = args.current
        if current.startswith("@"):
            current = read_app_version(pathlib.Path(current[1:]))

        nxt = next_version(current, args.stage)
    except VersionError as e:
        print(f"错误：{e}", file=sys.stderr)
        return 1

    if args.apply:
        try:
            write_app_version(pathlib.Path(args.apply), nxt)
        except OSError as e:
            print(f"错误：写入 {args.apply} 失败：{e}", file=sys.stderr)
            return 1
        if not args.quiet:
            print(f"{current} -> {nxt}（已写入 {args.apply}）")
    elif args.quiet:
        print(nxt)
    else:
        print(f"{current} -> {nxt}")

    return 0


def read_app_version(path):
    """从 gradle.properties 读 appVersion。"""
    text = path.read_text(encoding="utf-8")
    # 同 write_app_version：用 [^\S\n] 而不是 \s，避开 换行
    m = re.search(r"^appVersion[^\S\n]*=[^\S\n]*(\S+)[^\S\n]*$", text, re.MULTILINE)
    if not m:
        raise VersionError(f"{path} 里没有 appVersion=")
    return m.group(1)


def write_app_version(path, version):
    """
    只替换 appVersion 那一行，其余内容原样保留（包括注释和空行）。

    不用 properties 库重写整个文件 —— 那会把注释和顺序全打乱，
    gradle.properties 里那些说明文字是有价值的。
    """
    text = path.read_text(encoding="utf-8")
    # 注意：不要用 \s*$ —— \s 包含 \n，会把行尾换行一起吃掉，
    # 导致 appVersion 后面那一行（通常是空行）被并过来。
    # 用 [^\S\n]*$ 只匹配行内空白，保留换行符。
    new_text, count = re.subn(
        r"^appVersion[^\S\n]*=[^\S\n]*\S+[^\S\n]*$",
        f"appVersion={version}",
        text,
        count=1,
        flags=re.MULTILINE,
    )
    if count == 0:
        raise VersionError(f"{path} 里没有 appVersion= 可替换")
    path.write_text(new_text, encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
