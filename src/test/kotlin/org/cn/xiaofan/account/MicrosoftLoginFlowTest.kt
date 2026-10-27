package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.AuthProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 微软登录结果的形态。
 *
 * <p>回溯：登录成功后对话框不关、账号列表不刷新。根因有两个，且都在 UI 层
 * （回调在 IO 线程、列表是进入界面时的快照），但能被钉住的不变式在这里：
 * 登录成功必须给出一个「能切过去」的 [AccountProfile] —— provider 是正版、
 * uuid 不带连字符，否则后续 `switchTo` 找不到账号，用户就卡在「登录成功但没反应」。
 */
class MicrosoftLoginFlowTest {

    @Test
    fun `登录成功后的账号能被识别为正版并能切号`() {
        // 微软返回的 uuid 带连字符，AccountResolver 要负责清掉
        val raw = "069a79f4-44e9-4726-a5be-fca90e38aaf5"
        val profile = AccountResolver.fromAccount(
            name = "Notch",
            provider = AuthProvider.DEFAULT,
            uuid = raw,
        )

        assertTrue(profile.isMicrosoft, "provider 应为正版: ${profile.provider}")
        assertEquals("069a79f444e94726a5befca90e38aaf5", profile.uuid, "uuid 应去掉连字符")
        assertTrue(profile.avatarUrl != null, "正版账号应能推出头像地址")
    }

    @Test
    fun `失败结果带可展示的原因`() {
        val failed = LoginResult.Failed("headlessmc 未启用微软认证源")

        // 界面直接把它塞进 account.login.microsoft.failed 的 {0}，空串会显示成
        // 「登录失败：」——等于没说。原因必须非空。
        assertFalse(failed.reason.isBlank(), "失败原因不能为空")
    }

    @Test
    fun `成功结果携带账号信息`() {
        val profile = AccountResolver.fromAccount("Notch", AuthProvider.DEFAULT, "0".repeat(32))

        assertEquals(profile, LoginResult.Success(profile).profile)
    }
}
