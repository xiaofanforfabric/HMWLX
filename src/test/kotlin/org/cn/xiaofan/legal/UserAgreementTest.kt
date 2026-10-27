package org.cn.xiaofan.legal

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * 协议正文一致性校验。
 *
 * <p>协议文本存在两份：`LEGAL/用户协议.txt`（正式归档文本）与
 * [UserAgreement]（界面渲染用）。两份是人手维护的，必然漂移，
 * 所以这里逐段比对，任何不一致都让 CI 报红。
 *
 * <p>比对前做归一化，因为两份的排版约束不同：
 * - txt 有硬换行和缩进，kt 是整段拼接——统一把换行折叠成空格
 * - txt 有分隔线、小标题装饰，kt 用结构表达——只比对段落正文
 * - 空白差异不构成内容差异，连续空白折叠成一个空格
 */
class UserAgreementTest {

    @Test
    fun `协议版本号一致`() {
        val text = readAgreementText()
        assertTrue(
            text.contains("版本：${UserAgreement.VERSION}"),
            "txt 里的版本号与 UserAgreement.VERSION 不一致",
        )
        assertTrue(
            text.contains("生效日期：${UserAgreement.EFFECTIVE_DATE}"),
            "txt 里的生效日期与 UserAgreement.EFFECTIVE_DATE 不一致",
        )
    }

    @Test
    fun `协议各小节都出现在 txt 中`() {
        val text = normalize(readAgreementText())
        UserAgreement.SECTIONS.forEach { section ->
            assertTrue(
                text.contains(normalize(section.heading)),
                "txt 里缺少小节标题：${section.heading}",
            )
        }
    }

    @Test
    fun `协议每个段落都出现在 txt 中`() {
        val text = normalize(readAgreementText())
        val missing = mutableListOf<String>()

        UserAgreement.SECTIONS.forEach { section ->
            section.paragraphs.forEach { paragraph ->
                if (!text.contains(normalize(paragraph))) {
                    missing += "[${section.heading}] $paragraph"
                }
            }
        }

        assertEquals(
            emptyList<String>(),
            missing,
            "以下段落在 LEGAL/用户协议.txt 中找不到，两份协议已漂移：\n" +
                missing.joinToString("\n\n"),
        )
    }

    @Test
    fun `协议不含 Markdown 标记`() {
        // 纯文本协议里出现 ** 会原样显示给用户；kt 里也不该有。
        val text = readAgreementText()
        assertTrue(
            !text.contains("**"),
            "LEGAL/用户协议.txt 里残留 Markdown 粗体标记 **",
        )

        UserAgreement.SECTIONS.forEach { section ->
            section.paragraphs.forEach { paragraph ->
                assertTrue(
                    !paragraph.contains("**"),
                    "UserAgreement 段落里残留 Markdown 粗体标记：$paragraph",
                )
            }
        }
    }

    @Test
    fun `协议文本非空`() {
        assertTrue(UserAgreement.TITLE.isNotBlank(), "协议标题为空")
        assertTrue(UserAgreement.SECTIONS.isNotEmpty(), "协议正文为空")

        UserAgreement.SECTIONS.forEach { section ->
            assertTrue(section.heading.isNotBlank(), "存在空的小节标题")
            assertTrue(section.paragraphs.isNotEmpty(), "小节「${section.heading}」没有正文")
        }
    }

    /**
     * 归一化文本用于比对。
     *
     * 直接删掉所有空白，只比字符序列。原因：
     * - txt 是硬换行排版，中英混排时换行处该不该留空格无法机械判断
     *   （“Minecraft 的” 折行成 “Minecraft\n     的”，空格其实在行尾）
     * - kt 是整段拼接，空格位置与 txt 不同
     * 折叠成单个空格解决不了这个问题，删光才两边一致。
     * 代价是看不到“少了空格”这类差异，但协议里这是排版问题，不是内容问题。
     */
    private fun normalize(raw: String): String =
        raw.replace(Regex("\\s+"), "")

    private fun readAgreementText(): String {
        // 测试从项目根目录跑，两种可能的 cwd 都覆盖一下。
        val candidates = listOf(
            File("LEGAL/用户协议.txt"),
            File("../LEGAL/用户协议.txt"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到 LEGAL/用户协议.txt，尝试过：${candidates.joinToString { it.absolutePath }}")
        return file.readText(StandardCharsets.UTF_8)
    }
}
