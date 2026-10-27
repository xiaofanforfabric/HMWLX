package org.cn.xiaofan

import org.cn.xiaofan.account.AccountResolver
import org.cn.xiaofan.account.sourceLabel
import org.cn.xiaofan.i18n.Language
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 语言包自检：确保中文包里的 key 与 [TextKey] 一一对应。
 *
 * 不需要启动 Quarkus 容器，纯资源读取。
 */
class I18nTest {

    @Test
    fun `中文语言包能加载且关键文案不为空`() {
        assertEquals(Language.ZH_CN, Messages.current)

        listOf(
            TextKey.BOOT_LOADING,
            TextKey.BOOT_FAILED_TITLE,
            TextKey.APP_NAME,
            TextKey.APP_SUBTITLE,
            TextKey.WINDOW_TITLE,
            TextKey.READY_ROOT_COMMAND_MISSING,
            TextKey.SOURCE_MICROSOFT,
            TextKey.SOURCE_YGGDRASIL,
            TextKey.SOURCE_OFFLINE,
        ).forEach { key ->
            val value = Messages.get(key)
            assertTrue(value.isNotBlank() && !value.startsWith("!"), "缺少文案: $key")
        }
    }

    @Test
    fun `占位符会被替换`() {
        assertEquals("根命令: hmc", Messages.get(TextKey.READY_ROOT_COMMAND, "hmc"))
        assertEquals("子命令数: 3", Messages.get(TextKey.READY_SUBCOMMAND_COUNT, 3))
    }

    @Test
    fun `未知 key 返回醒目的占位而不是抛异常`() {
        assertEquals("!not.exists!", Messages.get("not.exists"))
    }

    @Test
    fun `账号来源标签走语言包`() {
        val offline = AccountResolver.fromAccount(name = "Steve", provider = "OFFLINE", uuid = "0".repeat(32))
        assertEquals("离线", offline.sourceLabel())
    }
}
