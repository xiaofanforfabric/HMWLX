#!/usr/bin/env python3
"""
给源文件批量加上 GPL-3.0 的版权声明头。

为什么需要：GPL-3.0 要求在源文件里「附上适当的版权声明」，光有根目录的
LICENSE 文件不够 —— 别人单独拷一个文件走，看不出它受什么许可证约束。

设计要点：
  1. 幂等：已经有头的文件跳过，重复跑不会叠加
  2. 尊重 shebang 与 package 声明：头必须插在 shebang 之后、package 之前，
     否则 Kotlin 编译不过
  3. 跳过空文件和已经是别的许可证的文件（避免覆盖上游代码的声明）

用法：
    ./scripts/add-license-header.py            # 处理全部
    ./scripts/add-license-header.py --check    # 只检查，不改文件（CI 用）
    ./scripts/add-license-header.py --dry-run  # 打印会改哪些
"""

import argparse
import os
import sys

HEADER_MARK = "SPDX-License-Identifier: GPL-3.0-only"

COPYRIGHT = "Copyright (C) 2026 xiaofan"

HEADER = f"""\
/*
 * HMWLX - Hello My World Launcher X
 * {COPYRIGHT}
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * {HEADER_MARK}
 */

"""

# 只处理这些后缀。构建脚本、C 源码同理，但它们要的是另一种注释语法，
# 需要时再扩展 —— 不要在这里一刀切，会把 shell 脚本写坏。
TARGET_EXTS = {
    ".kt": ["src/"],
}

# 跳过测试资源、生成代码
SKIP_PARTS = (
    "/build/",
    "/.gradle/",
    "/generated/",
)


def iter_targets(root):
    for ext, prefixes in TARGET_EXTS.items():
        for prefix in prefixes:
            base = os.path.join(root, prefix)
            if not os.path.isdir(base):
                continue
            for dirpath, dirnames, filenames in os.walk(base):
                # 原地裁剪，避免走进 build 目录
                dirnames[:] = [
                    d for d in dirnames
                    if f"/{d}/" not in SKIP_PARTS and d not in ("build", ".gradle")
                ]
                for name in filenames:
                    if name.endswith(ext):
                        yield os.path.join(dirpath, name)


def has_header(text):
    return HEADER_MARK in text or "GNU General Public License" in text[:2000]


def insert_header(text):
    """
    把 HEADER 插到正确位置。

    Kotlin 文件的合法结构：
        [可选] shebang
        [可选] 文件注解 @file:JvmName(...)
        package ...
        import ...

    版权头必须放在 package 之前、shebang 之后。
    """
    lines = text.splitlines(keepends=True)
    insert_at = 0

    # 跳过 shebang
    if lines and lines[0].startswith("#!"):
        insert_at = 1

    # 跳过空行
    while insert_at < len(lines) and not lines[insert_at].strip():
        insert_at += 1

    before = "".join(lines[:insert_at])
    after = "".join(lines[insert_at:])
    return before + HEADER + after


def main():
    ap = argparse.ArgumentParser(description="给源文件加 GPL-3.0 版权头")
    ap.add_argument("--check", action="store_true", help="只检查，缺失则退出码 1")
    ap.add_argument("--dry-run", action="store_true", help="只打印，不写文件")
    args = ap.parse_args()

    root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    missing = []
    changed = 0

    for path in sorted(iter_targets(root)):
        with open(path, "r", encoding="utf-8") as f:
            text = f.read()

        if not text.strip():
            continue
        if has_header(text):
            continue

        missing.append(os.path.relpath(path, root))
        if args.check or args.dry_run:
            continue

        with open(path, "w", encoding="utf-8") as f:
            f.write(insert_header(text))
        changed += 1

    if args.check:
        if missing:
            print(f"以下 {len(missing)} 个文件缺少 GPL 版权头：", file=sys.stderr)
            for p in missing:
                print(f"  {p}", file=sys.stderr)
            return 1
        print("所有源文件都带有版权头")
        return 0

    if args.dry_run:
        for p in missing:
            print(f"将添加: {p}")
        print(f"\n共 {len(missing)} 个文件待处理")
        return 0

    print(f"已为 {changed} 个文件添加版权头")
    return 0


if __name__ == "__main__":
    sys.exit(main())
