#!/usr/bin/env python3
"""
HMWLX 自解压包构建器

把「启动器 exe + LZMA 压缩的 runtime/ 和 hmwlx.jar」合成一个单文件 exe。

用法：
    python3 scripts/make-sfx.py \\
        --stub   build/launcher/HMWLX.exe \\
        --payload-dir dist/HMWLX-win-x64 \\
        --output dist/HMWLX-1.0.0-win-x64.exe

载荷结构（追加在 PE 映像之后）：

    [块 0 压缩数据][块 1 压缩数据] ...
    [索引区]
    [TRAILER: "HMWLXSFX" + indexOffset(u64) + indexSize(u64) + count(u32)]

索引区每条：
    offset(u64) packedSize(u64) origSize(u64) props[5] nameLen(u8) name[nameLen]

全部小端，与 launcher/sfx.c 的解析代码一一对应。
"""

import argparse
import lzma
import os
import struct
import sys

MAGIC = b"HMWLXSFX"
TRAILER_FMT = "<8sQQI"
TRAILER_SIZE = struct.calcsize(TRAILER_FMT)   # 28 字节

# LZMA1 参数。props[0] 的布局由 LZMA SDK 的 LzmaProps_Decode 定义：
#
#     d = lc + 9 * (lp + 5 * pb)
#     lc = d % 9;  lp = (d / 9) % 5;  pb = (d / 9) / 5
#
# 注意这不是「位域拼接」！我第一次写成 pb | lp<<4 | lc<<5 得到 0x62，
# 被解成 lc=8 lp=1 pb=2，解码器一读就 SZ_ERROR_DATA。
# lc=3 lp=0 pb=2 对应 d = 3 + 9*(0 + 10) = 93 = 0x5D。
#
# dict_size 影响解压时字典缓冲大小：LzmaDecode 会把输出缓冲直接当字典用，
# 所以 C 端必须按 max(origSize, dictSize) 分配，这里给 16MB 足够。
LZMA_LC = 3
LZMA_LP = 0
LZMA_PB = 2
LZMA_DICT = 1 << 24    # 16 MiB

_LZMA_D = LZMA_LC + 9 * (LZMA_LP + 5 * LZMA_PB)

PROPS = bytes([_LZMA_D]) + LZMA_DICT.to_bytes(4, "little")

FILTERS = [{
    "id": lzma.FILTER_LZMA1,
    "preset": 9,
    "lc": LZMA_LC,
    "lp": LZMA_LP,
    "pb": LZMA_PB,
    "dict_size": LZMA_DICT,
}]


def walk_payload(payload_dir):
    """遍历载荷目录，返回 [(相对路径, 绝对路径, 是否目录)]，路径用正斜杠。"""
    items = []
    for root, dirs, files in os.walk(payload_dir):
        dirs.sort()
        for d in dirs:
            full = os.path.join(root, d)
            rel = os.path.relpath(full, payload_dir).replace(os.sep, "/") + "/"
            items.append((rel, full, True))
        for f in sorted(files):
            full = os.path.join(root, f)
            rel = os.path.relpath(full, payload_dir).replace(os.sep, "/")
            items.append((rel, full, False))
    return items


def build(stub_path, payload_dir, output_path, level=9):
    with open(stub_path, "rb") as f:
        stub = f.read()
    print(f"启动器 : {len(stub):,} 字节")

    items = walk_payload(payload_dir)
    print(f"载荷   : {len(items)} 个条目（{payload_dir}）")

    # ---- 逐条压缩，同时记录索引 ----
    blobs = []        # [(压缩数据, 索引记录)]
    total_orig = 0
    total_packed = 0

    for i, (rel, full, is_dir) in enumerate(items):
        if is_dir:
            # 目录条目：不占数据，只在索引里标记（名字以 / 结尾）
            blobs.append((b"", 0, 0, rel))
            continue

        with open(full, "rb") as f:
            data = f.read()

        # 空文件单独处理：LZMA 对 0 字节输入会产出 0 字节输出，
        # 但 C 端 LzmaDecode 在 dstLen=0 时行为不明确，所以这里
        # 保持统一——空文件也压缩，解压侧写 0 字节即可。
        compressed = lzma.compress(data, format=lzma.FORMAT_RAW, filters=FILTERS)

        total_orig += len(data)
        total_packed += len(compressed)
        blobs.append((compressed, len(compressed), len(data), rel))

        if (i + 1) % 500 == 0:
            print(f"  已压缩 {i + 1}/{len(items)} …", end="\r")

    print(f"  原始 {total_orig / 1048576:.1f} MB -> 压缩 {total_packed / 1048576:.1f} MB")

    # ---- 布局：PE 映像 + 各块数据 + 索引 + trailer ----
    #
    # PE 映像之后必须按 8 字节对齐，否则某些加载器会抱怨。
    body = bytearray(stub)
    while len(body) % 8:
        body.append(0)

    # 第一遍：按顺序写数据块，记录每块的真实偏移
    entries = []
    for compressed, packed_len, orig_len, rel in blobs:
        offset = len(body)
        if compressed:
            body.extend(compressed)
        name_bytes = rel.encode("utf-8")
        if len(name_bytes) > 127:
            print(f"错误：路径过长（{len(name_bytes)} 字节）：{rel}", file=sys.stderr)
            return 1
        entries.append((offset, packed_len, orig_len, PROPS, name_bytes))

    # 第二遍：写索引区（定长字段 + 变长名字，C 端顺序读取）
    index_offset = len(body)
    for offset, packed_len, orig_len, props, name_bytes in entries:
        body.extend(struct.pack("<QQQ", offset, packed_len, orig_len))
        body.extend(props)
        body.append(len(name_bytes))
        body.extend(name_bytes)
    index_size = len(body) - index_offset

    body.extend(struct.pack(TRAILER_FMT, MAGIC, index_offset, index_size, len(entries)))

    with open(output_path, "wb") as f:
        f.write(body)

    # 继承 stub 的权限位。Python 的 open() 用默认 umask 建文件（通常 0644），
    # Linux 版用户拿到后直接跑会 Permission denied。
    if os.name != "nt":
        os.chmod(output_path, os.stat(stub_path).st_mode & 0o777)

    print()
    print("=" * 60)
    print(f"输出 : {output_path}")
    print(f"大小 : {len(body) / 1048576:.1f} MB")
    print(f"索引 : offset={index_offset:,} size={index_size:,} count={len(entries)}")
    print("=" * 60)
    return 0


def main():
    ap = argparse.ArgumentParser(description="构建 HMWLX 单文件自解压 exe")
    ap.add_argument("--stub", required=True, help="启动器 exe 路径")
    ap.add_argument("--payload-dir", required=True, help="要嵌入的目录")
    ap.add_argument("--output", required=True, help="输出 exe 路径")
    args = ap.parse_args()

    for p in (args.stub, args.payload_dir):
        if not os.path.exists(p):
            print(f"错误：{p} 不存在", file=sys.stderr)
            return 1

    os.makedirs(os.path.dirname(args.output) or ".", exist_ok=True)
    return build(args.stub, args.payload_dir, args.output)


if __name__ == "__main__":
    sys.exit(main())
