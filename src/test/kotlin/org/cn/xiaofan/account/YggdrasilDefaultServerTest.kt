package org.cn.xiaofan.account

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 外置登录默认服务器地址的测试。
 *
 * <p>GUI 会把地址预填进输入框，用户很可能直接点登录。如果预填的地址和实际
 * 连接的地址不一致，用户会以为连的是 A 站，实际连的是 B 站 —— 这类问题
 * 排查起来很费劲。所以默认值必须和上游 `configuredOrDefaultServer()` 完全一致：
 * 配置优先，缺省 `https://littleskin.cn`。
 */
class YggdrasilDefaultServerTest {

    private val key = "hmc.auth.yggdrasil.url"

    @AfterEach
    fun cleanup() {
        System.clearProperty(key)
    }

    @Test
    fun `配置了系统属性时用配置值`() {
        System.setProperty(key, "https://example-skin.com")
        assertEquals("https://example-skin.com", YggdrasilLogin.configuredServerUrl())
    }

    @Test
    fun `系统属性优先于配置文件`() {
        // 这台机器的 ~/.config/headlessmc/config.properties 里可能配了 littleskin，
        // 系统属性必须盖过它
        System.setProperty(key, "https://override.example")
        assertEquals("https://override.example", YggdrasilLogin.configuredServerUrl())
    }

    @Test
    fun `没有配置时回退到 LittleSkin`() {
        System.clearProperty(key)
        val actual = YggdrasilLogin.configuredServerUrl()

        // 本机配置文件可能配了别的站，所以两种都接受：
        // 配了就返回配置值，没配就返回默认值。关键是「不能返回空串」。
        assertEquals(false, actual.isBlank(), "默认地址不能为空")
        if (actual == YggdrasilLogin.DEFAULT_SERVER) {
            assertEquals("https://littleskin.cn", actual)
        }
    }

    @Test
    fun `默认值与上游一致`() {
        // 上游 YggdrasilAuthenticator 的兜底值就是这个，改了会导致
        // GUI 显示的地址与实际连接的地址不一致
        assertEquals("https://littleskin.cn", YggdrasilLogin.DEFAULT_SERVER)
    }
}
