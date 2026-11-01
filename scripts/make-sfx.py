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
    offset(u64) packedSize(u64) origSize(u64) mode(u16) props[5] nameLen(u8) name[nameLen]

mode 是可执行位（0755 或 0644）。Linux/macOS 解压时按它还原，
否则 runtime/bin/java 出来是 644，execv 会 EACCES。Windows 忽略。

全部小端，与 launcher/sfx.c 的解析代码一一对应。
"""

import argparse
import hashlib
import lzma
import os
import struct
import sys

MAGIC = b"HMWLXSFX"
# 字段：magic[8] + indexOffset(u64) + indexSize(u64) + count(u32)
#       + contentHash(u32)
#
# contentHash 是打包时算出的载荷内容指纹，启动器用它做缓存目录
# 名的一部分。**必须**有：早期版本只用 (indexSize, count) 当指纹，
# 而这两个值在「改了 jar 内容、但文件数和路径长度都没变」时完全
# 相同 —— 用户换了新 EXE，启动器却认成「已解压」，继续跑旧的
# jar。表现就是「更新了启动器，内容还是老版本」。
TRAILER_FMT = "<8sQQII"
TRAILER_SIZE = struct.calcsize(TRAILER_FMT)   # 32 字节

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

    # 载荷内容指纹：把所有「路径 + 大小 + 内容」滚进一个 u32。
    #
    # 为什么不直接用 (indexSize, count)：那两个值只看「名字总长度
    # 和条目数」，改了 jar 内容而文件结构不变时完全相同，启动器
    # 会误判成「已解压」，用户换了新 EXE 却还在跑旧 jar。
    #
    # 用 blake2b（标准库，比 md5 快且无碰撞顾虑），取前 4 字节。
    # 32 位空间对「判断自己是不是同一版」足够了 —— 碰撞概率相当于
    # 随机 40 亿分之一的版本对才可能撞上。
    content_hasher = hashlib.blake2b(digest_size=4)

    for i, (rel, full, is_dir) in enumerate(items):
        if is_dir:
            # 目录条目：不占数据，只在索引里标记（名字以 / 结尾）
            blobs.append((b"", 0, 0, rel, 0o755))
            # 目录名也要进指纹：空目录的有无也算内容变化
            content_hasher.update(b"D")
            content_hasher.update(rel.encode("utf-8"))
            continue

        with open(full, "rb") as f:
            data = f.read()

        # 空文件单独处理：LZMA 对 0 字节输入会产出 0 字节输出，
        # 但 C 端 LzmaDecode 在 dstLen=0 时行为不明确，所以这里
        # 保持统一——空文件也压缩，解压侧写 0 字节即可。
        compressed = lzma.compress(data, format=lzma.FORMAT_RAW, filters=FILTERS)

        # 指纹：路径 + 内容。用原始内容而不是压缩后的 —— 压缩
        # 结果可能随库版本变化，那不是「内容变化」。
        content_hasher.update(b"F")
        content_hasher.update(rel.encode("utf-8"))
        content_hasher.update(struct.pack("<Q", len(data)))
        content_hasher.update(data)

        total_orig += len(data)
        total_packed += len(compressed)
        # 记可执行位：Linux/macOS 上 runtime/bin/java 之类必须保留 x，
        # 否则解压出来是 644，execv 直接 EACCES。Windows 忽略此字段。
        st = os.stat(full)
        mode = 0o755 if (st.st_mode & 0o111) else 0o644
        blobs.append((compressed, len(compressed), len(data), rel, mode))

        if (i + 1) % 500 == 0:
            print(f"  已压缩 {i + 1}/{len(items)} …", end="\r")

    content_hash = struct.unpack("<I", content_hasher.digest())[0]
    print(f"  原始 {total_orig / 1048576:.1f} MB -> 压缩 {total_packed / 1048576:.1f} MB")
    print(f"  内容指纹: {content_hash:08x}")

    # ---- 布局：PE 映像 + 各块数据 + 索引 + trailer ----
    #
    # PE 映像之后必须按 8 字节对齐，否则某些加载器会抱怨。
    body = bytearray(stub)
    while len(body) % 8:
        body.append(0)

    # 第一遍：按顺序写数据块，记录每块的真实偏移
    entries = []
    for compressed, packed_len, orig_len, rel, mode in blobs:
        offset = len(body)
        if compressed:
            body.extend(compressed)
        name_bytes = rel.encode("utf-8")
        if len(name_bytes) > 127:
            print(f"错误：路径过长（{len(name_bytes)} 字节）：{rel}", file=sys.stderr)
            return 1
        entries.append((offset, packed_len, orig_len, PROPS, mode, name_bytes))

    # 第二遍：写索引区（定长字段 + 变长名字，C 端顺序读取）
    index_offset = len(body)
    for offset, packed_len, orig_len, props, mode, name_bytes in entries:
        body.extend(struct.pack("<QQQH", offset, packed_len, orig_len, mode))
        body.extend(props)
        body.append(len(name_bytes))
        body.extend(name_bytes)
    index_size = len(body) - index_offset

    body.extend(struct.pack(TRAILER_FMT, MAGIC, index_offset, index_size,
                            len(entries), content_hash))

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
