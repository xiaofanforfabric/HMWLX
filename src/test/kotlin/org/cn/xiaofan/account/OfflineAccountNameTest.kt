package org.cn.xiaofan.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 离线账号用户名校验规则的测试。
 *
 * <p>规则：
 * - 长度 1–16（不能为空），按 UTF-16 code unit（`String.length`）计算，与
 *   Minecraft 协议一致（`Login Start` 的名字字段是 `String (16)`）。
 * - 除控制字符与格式字符外全部放行：中文、日文、韩文、emoji、符号都可以。
 *
 * <p>放行中文是实际需求：部分服务器（如 2b2t.xin）明确要求离线玩家用中文名加入。
 * 不设字符数下限，单字英文名与单字中文名都是有效名字。
 *
 * <p>直接调 `AccountService.validateName`（`internal`，测试同模块可见）：规则只有一份，
 * 改了实现这里立刻会红。之前测试里复制一份副本「交叉验证」，实际上只是对着自己验自己，
 * 副本被一起改掉时测试依然是绿的。
 */
class OfflineAccountNameTest {

    /** 把生产代码返回的原因归一成断言用的短标签。 */
    private fun validate(name: String): String? =
        when (AccountService.validateName(name)) {
            null -> null
            "用户名不能为空" -> "empty"
            "用户名最多 16 个字符" -> "too_long"
            else -> "invalid_chars"
        }

    @Test
    fun `英文用户名通过`() {
        listOf(
            "a",
            "ab",
            "abc",
            "Steve",
            "TestPlayer1",
            "Player_123",
            "0123456789abcdef",   // 刚好 16
        ).forEach { assertNull(validate(it), "「$it」应通过校验") }
    }

    @Test
    fun `中文用户名通过`() {
        listOf(
            "王",                 // 单字
            "小王",
            "小明同学",
            "2b2t.xin玩家",
            "测试_玩家1",
            "中文名字测试",
        ).forEach { assertNull(validate(it), "「$it」应通过校验") }
    }

    @Test
    fun `中日韩与符号通过`() {
        listOf(
            "プレイヤー",
            "한국인",
            "玩家@123",
            "a-b-c",
            "带 空格 的名字",
        ).forEach { assertNull(validate(it), "「$it」应通过校验") }
    }

    @Test
    fun `emoji 按两个长度单位计算`() {
        // emoji 是代理对，String.length 为 2，与 Minecraft 协议的算法一致。
        assertEquals(2, "😀".length)

        // 7 个 emoji = 14 个 length 单位，通过
        assertNull(validate("😀😀😀😀😀😀😀"))

        // 9 个 emoji = 18 个 length 单位，超过 16
        assertEquals("too_long", validate("😀😀😀😀😀😀😀😀😀"))
    }

    @Test
    fun `空用户名被拒`() {
        assertEquals("empty", validate(""))
    }

    @Test
    fun `单字符用户名合法`() {
        assertNull(validate("a"))
        assertNull(validate("王"))
    }

    @Test
    fun `过长被拒`() {
        assertEquals("too_long", validate("0123456789abcdefg"))   // 17 个字符
        assertEquals("too_long", validate("中".repeat(17)))
        assertEquals("too_long", validate("a".repeat(64)))
    }

    @Test
    fun `控制字符被拒`() {
        listOf(
            "abc\u0000",
            "a\u0001bc",
            "ab\ncd",
            "ab\tcd",
        ).forEach { assertEquals("invalid_chars", validate(it), "「$it」应因控制字符被拒") }
    }

    @Test
    fun `格式字符被拒`() {
        listOf(
            "abc\u200B",      // 零宽空格
            "\u200Eabc",      // 从左到右标记
            "a\u202Ebcd",     // 双向覆盖符，用于伪造名字
            "abc\u00AD",      // 软连字符
        ).forEach { assertEquals("invalid_chars", validate(it), "「$it」应因格式字符被拒") }
    }

    @Test
    fun `边界长度`() {
        assertNull(validate("中"))              // 1
        assertNull(validate("中".repeat(16)))   // 16
        assertEquals("too_long", validate("中".repeat(17)))
    }
}
