package org.cn.xiaofan

import io.github.headlesshq.headlessmc.auth.AuthProvider
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
            TextKey.SOURCE_MICROSOFT,
            TextKey.SOURCE_YGGDRASIL,
            TextKey.SOURCE_OFFLINE,
            TextKey.ACCOUNT_LOGIN_MICROSOFT_REQUESTING,
            TextKey.ACCOUNT_LOGIN_MICROSOFT_HINT,
            TextKey.ACCOUNT_LOGIN_MICROSOFT_WAITING,
            TextKey.ACCOUNT_LOGIN_MICROSOFT_REOPEN,
            TextKey.ACCOUNT_LOGIN_MICROSOFT_CLOSE,
            TextKey.ACCOUNT_LOGIN_MICROSOFT_FAILED,
        ).forEach { key ->
            val value = Messages.get(key)
            assertTrue(value.isNotBlank() && !value.startsWith("!"), "缺少文案: $key")
        }
    }

    @Test
    fun `占位符会被替换`() {
        assertEquals("已存在名为 Steve 的离线账号", Messages.get(TextKey.ACCOUNT_ADD_OFFLINE_DUPLICATE, "Steve"))
        assertEquals("切换账号失败：boom", Messages.get(TextKey.ACCOUNT_SWITCH_FAILED, "boom"))
    }

    @Test
    fun `未知 key 返回醒目的占位而不是抛异常`() {
        assertEquals("!not.exists!", Messages.get("not.exists"))
    }

    @Test
    fun `账号来源标签走语言包`() {
        // provider 用 AuthProvider 常量而不是字面量：headlessmc 这几个值是全小写的
        // （offline / default / yggdrasil），写成 "OFFLINE" 会落到 else 分支直接回显原值，
        // 看起来像翻译丢了，其实是大小写没对上。
        val offline = AccountResolver.fromAccount(
            name = "Steve",
            provider = AuthProvider.OFFLINE,
            uuid = "0".repeat(32),
        )
        assertEquals("离线", offline.sourceLabel())
        assertEquals("正版", profileOf(AuthProvider.DEFAULT).sourceLabel())
        assertEquals("皮肤站", profileOf(AuthProvider.YGGDRASIL).sourceLabel())
    }

    /** 未知 provider 不做翻译，原样回显。 */
    @Test
    fun `未知 provider 原样显示`() {
        assertEquals("custom", profileOf("custom").sourceLabel())
    }

    private fun profileOf(provider: String) =
        AccountResolver.fromAccount(name = "Steve", provider = provider, uuid = "0".repeat(32))
}
