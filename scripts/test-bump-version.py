#!/usr/bin/env python3
"""
bump-version.py 的单元测试。

运行：
    python3 scripts/test-bump-version.py

或让 test.sh 一起跑（它会自动发现 scripts/test-*.py）。

为什么要单独测：版本推进的规则很密集（阶段序、递增、禁止倒退），
而且一旦出错会直接产出错误的发布版本号，事后很难补救
（发了 0.2 才发现该是 0.1.1）。所以把用户给的规则表逐条固化。
"""

import importlib.util
import pathlib
import sys
import unittest

# 从脚本所在目录加载 bump-version.py（文件名带连字符，不能直接 import）
_HERE = pathlib.Path(__file__).resolve().parent
_spec = importlib.util.spec_from_file_location("bump_version", _HERE / "bump-version.py")
bv = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(bv)


class TestParsing(unittest.TestCase):
    def test_plain_release(self):
        self.assertEqual(bv.parse_version("0.1"), ("0.1", "rel", 0))
        self.assertEqual(bv.parse_version("1.2.3"), ("1.2.3", "rel", 0))

    def test_prerelease_with_number(self):
        self.assertEqual(bv.parse_version("0.1-dev2"), ("0.1", "dev", 2))
        self.assertEqual(bv.parse_version("0.1-beta1"), ("0.1", "beta", 1))
        self.assertEqual(bv.parse_version("0.1-RC3"), ("0.1", "RC", 3))

    def test_prerelease_without_number_means_1(self):
        # 无数字后缀等价于 1，这是用户明确要求的
        self.assertEqual(bv.parse_version("0.1-dev"), ("0.1", "dev", 1))
        self.assertEqual(bv.parse_version("0.1-beta"), ("0.1", "beta", 1))

    def test_invalid(self):
        for bad in ["", "abc", "0.1-", "0.1-gamma1", "0.1-dev0", "v0.1", "0.1-dev1x"]:
            with self.assertRaises(bv.VersionError, msg=f"应当拒绝 {bad!r}"):
                bv.parse_version(bad)


class TestFormatting(unittest.TestCase):
    def test_roundtrip(self):
        for text in ["0.1", "1.0", "0.1-dev1", "0.1-beta2", "0.1-RC10", "1.2.3"]:
            base, stage, n = bv.parse_version(text)
            self.assertEqual(bv.format_version(base, stage, n), text)


class TestBumpBase(unittest.TestCase):
    def test_increments_minor(self):
        self.assertEqual(bv.bump_base("0.1"), "0.2")
        self.assertEqual(bv.bump_base("1.0"), "1.1")
        self.assertEqual(bv.bump_base("0.9"), "0.10")

    def test_drops_patch(self):
        # 0.1.5 -> 0.2（次版本 +1，修订号归零）
        self.assertEqual(bv.bump_base("0.1.5"), "0.2")

    def test_single_component(self):
        self.assertEqual(bv.bump_base("1"), "2.0")


class TestNextVersionFromDev(unittest.TestCase):
    """从 0.1-dev 出发"""

    def test_rel(self):
        self.assertEqual(bv.next_version("0.1-dev", "rel"), "0.1")

    def test_rc(self):
        self.assertEqual(bv.next_version("0.1-dev", "RC"), "0.1-RC1")

    def test_dev(self):
        self.assertEqual(bv.next_version("0.1-dev", "dev"), "0.1-dev2")

    def test_beta(self):
        self.assertEqual(bv.next_version("0.1-dev", "beta"), "0.1-beta1")


class TestNextVersionFromBeta(unittest.TestCase):
    """从 0.1-beta1 出发"""

    def test_rel(self):
        self.assertEqual(bv.next_version("0.1-beta1", "rel"), "0.1")

    def test_rc(self):
        self.assertEqual(bv.next_version("0.1-beta1", "RC"), "0.1-RC1")

    def test_beta_increments(self):
        self.assertEqual(bv.next_version("0.1-beta1", "beta"), "0.1-beta2")

    def test_dev_is_rejected(self):
        with self.assertRaises(bv.VersionError):
            bv.next_version("0.1-beta1", "dev")


class TestNextVersionFromRc(unittest.TestCase):
    """从 0.1-RC1 出发"""

    def test_rel(self):
        self.assertEqual(bv.next_version("0.1-RC1", "rel"), "0.1")

    def test_rc_increments(self):
        self.assertEqual(bv.next_version("0.1-RC1", "RC"), "0.1-RC2")

    def test_dev_rejected(self):
        with self.assertRaises(bv.VersionError):
            bv.next_version("0.1-RC1", "dev")

    def test_beta_rejected(self):
        with self.assertRaises(bv.VersionError):
            bv.next_version("0.1-RC1", "beta")


class TestNextVersionFromRelease(unittest.TestCase):
    """
    从正式版出发：先递增小版本，再进入目标阶段。

    这是最容易写错的地方 —— rel 在阶段序里最高，但它并不意味着
    「不能回到 dev」，而是意味着「这一版发布完了，开始下一版」。
    """

    def test_rel_bumps_minor(self):
        self.assertEqual(bv.next_version("0.1", "rel"), "0.2")

    def test_rc_of_next_version(self):
        self.assertEqual(bv.next_version("0.1", "RC"), "0.2-RC1")

    def test_dev_of_next_version(self):
        self.assertEqual(bv.next_version("0.1", "dev"), "0.2-dev1")

    def test_beta_of_next_version(self):
        self.assertEqual(bv.next_version("0.1", "beta"), "0.2-beta1")


class TestFullLifecycle(unittest.TestCase):
    """走一遍完整生命周期，确认每一步都符合预期。"""

    def test_lifecycle(self):
        v = "0.1-dev"
        steps = [
            ("dev",   "0.1-dev2"),
            ("dev",   "0.1-dev3"),
            ("beta",  "0.1-beta1"),
            ("beta",  "0.1-beta2"),
            ("RC",    "0.1-RC1"),
            ("RC",    "0.1-RC2"),
            ("rel",   "0.1"),
            ("rel",   "0.2"),
            # 0.2 已经是正式版，再开发就是下一个小版本 0.3
            ("dev",   "0.3-dev1"),
        ]
        for i, (stage, expect) in enumerate(steps, 1):
            v = bv.next_version(v, stage)
            self.assertEqual(v, expect, f"第 {i} 步")


class TestInvalidStage(unittest.TestCase):
    def test_unknown_stage(self):
        with self.assertRaises(bv.VersionError):
            bv.next_version("0.1-dev", "gamma")


class TestGradlePropertiesRoundtrip(unittest.TestCase):
    """读写 gradle.properties 不能破坏其它内容。"""

    def test_write_preserves_comments(self):
        import tempfile

        sample = (
            "# 头部注释\n"
            "# 第二行\n"
            "appVersion=0.1-dev\n"
            "\n"
            "# 后面的注释\n"
            "other=value\n"
        )
        with tempfile.TemporaryDirectory() as d:
            p = pathlib.Path(d) / "gradle.properties"
            p.write_text(sample, encoding="utf-8")

            self.assertEqual(bv.read_app_version(p), "0.1-dev")
            bv.write_app_version(p, "0.1-beta1")

            got = p.read_text(encoding="utf-8")
            self.assertIn("appVersion=0.1-beta1", got)
            self.assertIn("# 头部注释", got)
            self.assertIn("# 后面的注释", got)
            self.assertIn("other=value", got)
            # 行数不变，说明没重排
            self.assertEqual(len(got.splitlines()), len(sample.splitlines()))


if __name__ == "__main__":
    unittest.main(verbosity=2)
