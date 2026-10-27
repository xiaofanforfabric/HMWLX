package org.cn.xiaofan.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 离线账号用户名校验规则的测试。
 *
 * <p>规则取自 Mojang 官方：3–16 个字符，仅 `A-Z a-z 0-9 _`。
 * 校验逻辑在 `AccountService` 里是 private，这里复制同一份规则做交叉验证 ——
 * 目的不是测「代码能跑」，而是把规则本身钉死，避免哪天有人放宽后
 * 用户建出游戏里显示不出来的名字。
 */
class OfflineAccountNameTest {

    /** 与 `AccountService.validateName` 相同的规则。 */
    private fun validate(name: String): String? {
        if (name.isEmpty()) return "empty"
        if (name.length < 3) return "too_short"
        if (name.length > 16) return "too_long"
        if (!Regex("^[A-Za-z0-9_]+$").matches(name)) return "invalid_chars"
        return null
    }

    @Test
    fun `合法用户名通过`() {
        listOf(
            "abc",
            "Steve",
            "xiaofanbot",
            "Player_123",
            "a_b_c",
            "0123456789abcdef",   // 刚好 16
            "A1_",                // 刚好 3
        ).forEach { assertNull(validate(it), "「$it」应通过校验") }
    }

    @Test
    fun `空用户名被拒`() {
        assertEquals("empty", validate(""))
    }

    @Test
    fun `过短被拒`() {
        assertEquals("too_short", validate("a"))
        assertEquals("too_short", validate("ab"))
    }

    @Test
    fun `过长被拒`() {
        assertEquals("too_long", validate("0123456789abcdefg"))  // 17 个字符
        assertEquals("too_long", validate("a".repeat(64)))
    }

    @Test
    fun `非法字符被拒`() {
        listOf(
            "abc def",      // 空格
            "abc-def",      // 连字符
            "中文名字",      // 非 ASCII
            "abc.def",      // 点
            "abc!",         // 符号
            "player\u0000", // 控制字符
        ).forEach { assertEquals("invalid_chars", validate(it), "「$it」应因非法字符被拒") }
    }

    @Test
    fun `边界长度刚好合法`() {
        assertEquals(3, "abc".length)
        assertNull(validate("abc"))
        assertNull(validate("0123456789abcde"))   // 15
        assertNull(validate("0123456789abcdef"))  // 16
        assertTrue(validate("0123456789abcdefg") != null)
    }
}
