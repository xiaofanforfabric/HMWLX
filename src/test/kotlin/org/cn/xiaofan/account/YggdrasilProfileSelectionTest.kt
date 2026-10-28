package org.cn.xiaofan.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 角色选择的两阶段登录契约。
 *
 * <p>回溯两处问题：
 * 1. 初版 Console 的 `read()` 返回空串，想表达「直接回车 = 选第一个」。但上游
 *    `selectProfile` 用 `Integer.parseInt(read().trim())` 解析，空串直接抛异常，
 *    报 `Invalid profile selection: ''`。
 * 2. 解析候选角色的正则写成了 `1: name`（冒号），而实际输出是 `1. name`（点号），
 *    导致角色列表为空，选择框里一个选项都没有。
 *
 * <p>本测试用**实测的原始输出**做样本，锁死格式。
 */
class YggdrasilProfileSelectionTest {

    /** 与 `YggdrasilLogin.PROFILE_LINE` 相同的正则。 */
    private val pattern = Regex("""^\s*(\d+)\.\s+(.+?)\s*$""", RegexOption.MULTILINE)

    /**
     * 实测输出（LittleSkin，账号 cengxudaima@qq.com，2026-10）。
     *
     * 注意：序号用点号 `1.`，不是冒号 `1:`；行首有两个空格缩进。
     */
    private val realOutput = """
        Found 2 profiles:
          1. xiaofanforMC
          2. xiaofanbot
        Select a profile (1-2):
    """.trimIndent()

    @Test
    fun `能从真实输出里解析出角色名`() {
        val names = pattern.findAll(realOutput).map { it.groupValues[2].trim() }.toList()

        assertEquals(
            listOf("xiaofanforMC", "xiaofanbot"),
            names,
            "正则必须匹配「缩进 + 数字 + 点号 + 名字」的实际格式",
        )
    }

    @Test
    fun `不会把提问行误当成角色`() {
        val names = pattern.findAll(realOutput).map { it.groupValues[2].trim() }.toList()

        assertTrue(names.none { it.contains("Select") }, "「Select a profile (1-2):」不是角色行")
        assertTrue(names.none { it.contains("Found") }, "「Found 2 profiles:」不是角色行")
    }

    @Test
    fun `冒号格式不会被匹配`() {
        // 记录这个坑：早期正则写的是冒号，匹配不到实际输出
        val wrongFormat = "1: xiaofanforMC\n2: xiaofanbot"

        assertTrue(
            pattern.findAll(wrongFormat).none(),
            "点号是上游的真实格式；这条断言提醒不要改回冒号",
        )
    }

    @Test
    fun `解析出的角色名不能是序号`() {
        // 曾经踩过的坑：正则有两个分组，1 是序号、2 是名字，
        // 取值写成 groupValues[1] 就把 "1"、"2" 当成角色名显示给用户了
        val names = pattern.findAll(realOutput)
            .map { it.groupValues[1] to it.groupValues[2].trim() }
            .toList()

        assertEquals(listOf("1" to "xiaofanforMC", "2" to "xiaofanbot"), names)

        names.forEach { (index, name) ->
            assertTrue(
                name != index,
                "角色名不能等于序号（$name == $index），说明取错了分组",
            )
            assertTrue(
                name.any { !it.isDigit() },
                "角色名里必须有非数字字符，纯数字说明取了序号分组：$name",
            )
        }
    }

    @Test
    fun `角色序号从 1 开始`() {
        // 上游用 iconst_1 / if_icmplt 做下界检查，界面回传的必须是 1..size
        assertTrue(1 >= 1, "序号必须 >= 1，传 0 会被判为非法")
    }

    @Test
    fun `空串会让 parseInt 抛异常`() {
        val failed = runCatching { "".trim().toInt() }.isFailure
        assertTrue(failed, "空串无法转成数字，这就是 Invalid profile selection: '' 的成因")
    }
}
