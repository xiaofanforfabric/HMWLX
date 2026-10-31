#!/usr/bin/env python3
#
# 纯 Python 组装 .deb —— 不调用 dpkg-deb，也不调用外部 tar/ar。
#
# ===========================================================================
# 为什么不用 dpkg-deb
# ===========================================================================
#
# dpkg-deb --build 内部执行 `tar -cf - -C <root> .`。在 CNB 上，
# 当 <root> 落在 /workspace（OverlayFS）时，这一步会报：
#
#   tar: ./opt/hmwlx/runtime/lib/server/libjvm.so:
#        Cannot stat: Function not implemented
#   dpkg-deb: error: tar -cf subprocess failed with exit status 2
#
# 那是 OverlayFS 下层目录的 stat 限制。我们没法改 dpkg-deb 内部怎么调 tar。
#
# ===========================================================================
# 那这个脚本绕开了什么
# ===========================================================================
#
#   - 不用 dpkg-deb     → 自己按 DEB 格式写 ar 归档
#   - 不用外部 tar      → 用 Python 标准库 tarfile
#   - 不用外部 ar       → 用 debian.arfile（纯 Python）或自己写 ar 头
#
# 三者都是进程内的 Python 调用，不 fork 子进程，因此不经过 shell，
# 也不会触发那个 tar 的 bug 路径。
#
# ===========================================================================
# DEB 格式（就是个 ar 归档）
# ===========================================================================
#
#   !<arch>
#   debian-binary     内容固定三行 "2.0\n"
#   control.tar.xz    DEBIAN/ 目录（control、postinst、postrm…）
#   data.tar.xz       opt/、usr/ 等实际文件树
#
# 成员顺序必须是这个顺序，dpkg 依赖它找到 control。
#
# 用法：
#   python3 scripts/make-deb.py --root <DEB_ROOT> --output <xxx.deb>
#                               [--compression xz|gzip|none]
#
import argparse
import io
import os
import sys
import tarfile
import time


# --------------------------------------------------------------------------
# ar 归档写入
# --------------------------------------------------------------------------
#
# 自己写，不用 debian.arfile。原因：arfile 主要面向读取，写入接口
# 依赖调用方提供 fileobj，且不同版本行为有差异。ar 格式本身极简单，
# 自己写 20 行更可控，也少一个运行期依赖。
#
# ar 成员头固定 60 字节：
#   name      16   文件名（末尾用 / 补齐）
#   mtime     12   修改时间（十进制秒）
#   uid        6
#   gid        6
#   mode       8   八进制
#   size      10   十进制字节数
#   magic      2   "`\n"
#
AR_HEADER_SIZE = 60


def _ar_member_header(name: str, size: int, mtime: int,
                      mode: int = 0o100644, uid: int = 0, gid: int = 0) -> bytes:
    """构造一个 ar 成员头（60 字节）。"""
    # ar 的 name 字段：短名直接写，末尾补 '/'，不足 16 字节用空格填充
    name_bytes = name.encode("ascii") + b"/"
    if len(name_bytes) > 16:
        raise ValueError(f"ar 成员名太长（>15）: {name}")

    def field(value: bytes, width: int) -> bytes:
        if len(value) > width:
            raise ValueError(f"字段超宽: {value!r} > {width}")
        return value.ljust(width, b" ")

    header = b"".join([
        field(name_bytes, 16),
        field(str(mtime).encode(), 12),
        field(str(uid).encode(), 6),
        field(str(gid).encode(), 6),
        field(f"{mode:o}".encode(), 8),
        field(str(size).encode(), 10),
        b"`\n",
    ])
    assert len(header) == AR_HEADER_SIZE, len(header)
    return header


def write_ar(output_path: str, members: list[tuple[str, bytes]]) -> None:
    """把 (name, data) 列表写成 ar 归档。

    members 的顺序就是归档里的顺序 —— DEB 要求 debian-binary 在最前。
    """
    with open(output_path, "wb") as fp:
        fp.write(b"!<arch>\n")
        now = int(time.time())
        for name, data in members:
            fp.write(_ar_member_header(name, len(data), now))
            fp.write(data)
            # ar 要求每个成员按偶数对齐，奇数长度补一个 '\n'
            if len(data) % 2:
                fp.write(b"\n")


# --------------------------------------------------------------------------
# tar 生成
# --------------------------------------------------------------------------
#
# 用标准库 tarfile，进程内完成，不调外部 tar。
#
# 关键细节：
#   1. 成员顺序要稳定（按路径排序），否则每次构建的包哈希都不同，
#      不利于校验和复现。
#   2. 符号链接要保留（/usr/bin/hmwlx 指向 /opt/hmwlx/HMWLX），
#      不能用 add() 的默认跟随行为。
#   3. 属主统一 root:root —— 构建机上的 uid 没有意义，
#      DEB 的 data.tar 应该按 root 归属。
#   4. mtime 固定，理由同上（可复现构建）。
#
FIXED_MTIME = 1700000000   # 2023-11-14，随便一个固定值


def _walk_sorted(root: str):
    """按路径排序遍历 root 下所有条目（含 root 自身）。"""
    entries = []
    for dirpath, dirnames, filenames in os.walk(root, followlinks=False):
        dirnames.sort()
        filenames.sort()
        entries.append(dirpath)
        for f in filenames:
            entries.append(os.path.join(dirpath, f))
    entries.sort()
    return entries


def make_tar_xz(root: str, compression: str = "xz") -> bytes:
    """把 root 目录打成 tar.<compression>，返回字节串。

    root 自身不出现在归档里，内容是它下面的 opt/、usr/ 等 ——
    这正是 DEB 的 data.tar 所要求的布局。
    """
    if compression == "xz":
        mode = "w:xz"
    elif compression == "gzip":
        mode = "w:gz"
    elif compression == "none":
        mode = "w"
    else:
        raise ValueError(f"不支持的压缩方式: {compression}")

    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode=mode, format=tarfile.GNU_FORMAT) as tf:
        for path in _walk_sorted(root):
            # 跳过 root 自身
            if os.path.abspath(path) == os.path.abspath(root):
                continue

            arcname = os.path.relpath(path, root)

            info = tf.gettarinfo(path, arcname=arcname)
            # 属主统一 root:root
            info.uid = 0
            info.gid = 0
            info.uname = "root"
            info.gname = "root"
            info.mtime = FIXED_MTIME
            # 去掉不稳定的扩展属性
            info.pax_headers = {}

            if info.isfile():
                # 普通文件：拷贝内容
                with open(path, "rb") as f:
                    tf.addfile(info, f)
            else:
                # 目录 / 符号链接 / 其他：不带内容
                # addfile(info, None) 对目录和链接都是正确的
                tf.addfile(info)

    return buf.getvalue()


# --------------------------------------------------------------------------
# 主流程
# --------------------------------------------------------------------------
def build_deb(root: str, output: str, compression: str = "xz") -> None:
    debian_dir = os.path.join(root, "DEBIAN")
    if not os.path.isdir(debian_dir):
        raise SystemExit(f"错误：{root} 下没有 DEBIAN/ 目录，不是合法的 deb 根")
    control = os.path.join(debian_dir, "control")
    if not os.path.isfile(control):
        raise SystemExit(f"错误：缺少 {control}")

    print(f"  打包 control.tar.{compression} …")
    control_tar = make_tar_xz(debian_dir, compression)

    # data.tar 要包含除 DEBIAN/ 之外的所有内容。
    # 做法：把 root 下除 DEBIAN 之外的顶层项，分别打包。
    print(f"  打包 data.tar.{compression} …")
    buf = io.BytesIO()
    mode = {"xz": "w:xz", "gzip": "w:gz", "none": "w"}[compression]
    with tarfile.open(fileobj=buf, mode=mode, format=tarfile.GNU_FORMAT) as tf:
        for name in sorted(os.listdir(root)):
            if name == "DEBIAN":
                continue
            top = os.path.join(root, name)
            for path in _walk_sorted(top):
                arcname = os.path.relpath(path, root)
                info = tf.gettarinfo(path, arcname=arcname)
                info.uid = 0
                info.gid = 0
                info.uname = "root"
                info.gname = "root"
                info.mtime = FIXED_MTIME
                info.pax_headers = {}
                if info.isfile():
                    with open(path, "rb") as f:
                        tf.addfile(info, f)
                else:
                    tf.addfile(info)
    data_tar = buf.getvalue()

    # debian-binary 必须是第一个成员，内容 "2.0\n"
    debian_binary = b"2.0\n"

    print(f"  写入 {output} …")
    os.makedirs(os.path.dirname(os.path.abspath(output)) or ".", exist_ok=True)
    write_ar(output, [
        ("debian-binary", debian_binary),
        (f"control.tar.{compression}", control_tar),
        (f"data.tar.{compression}", data_tar),
    ])

    size = os.path.getsize(output)
    print(f"  完成：{output}  ({size / 1024 / 1024:.1f} MB)")


def main() -> int:
    ap = argparse.ArgumentParser(
        description="纯 Python 组装 .deb（不用 dpkg-deb，不用外部 tar）")
    ap.add_argument("--root", required=True, help="deb 根目录（含 DEBIAN/）")
    ap.add_argument("--output", required=True, help="输出的 .deb 路径")
    ap.add_argument("--compression", default="xz", choices=["xz", "gzip", "none"],
                    help="data/control 的压缩方式（默认 xz，和 dpkg-deb -Zxz 一致）")
    args = ap.parse_args()

    if not os.path.isdir(args.root):
        raise SystemExit(f"错误：{args.root} 不是目录")

    build_deb(args.root, args.output, args.compression)
    return 0


if __name__ == "__main__":
    sys.exit(main())
