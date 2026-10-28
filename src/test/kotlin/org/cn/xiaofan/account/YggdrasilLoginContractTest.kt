package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.AuthProvider
import io.github.headlesshq.headlessmc.auth.AuthService
import io.github.headlesshq.headlessmc.console.Console
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Yggdrasil 登录的反射契约测试。
 *
 * <p>GUI 靠反射调 `YggdrasilAuthenticator.login(String, String, String, Console)`，
 * 这是 private 方法，上游一改签名就失效。本测试不真的登录（需要真实账号），
 * 只锁定：
 * 1. 那个方法确实存在，且参数类型符合预期
 * 2. 外置登录 provider 能从容器里取到
 *
 * <p>方法一旦被改名或改签名，这里立刻报红，而不是等到用户点登录才失败。
 */
class YggdrasilLoginContractTest {

    private val expectedParams = arrayOf(
        String::class.java,
        String::class.java,
        String::class.java,
        Console::class.java,
    )

    @Test
    fun `YggdrasilAuthenticator 仍然有预期的 private login 方法`() {
        HeadlessMcBootstrap.start()

        val authenticator = provider().authenticator

        val method = authenticator.javaClass.declaredMethods.firstOrNull {
            it.name == "login" && it.parameterTypes.contentEquals(expectedParams)
        }

        assertNotNull(
            method,
            "找不到 login(String, String, String, Console)。" +
                "上游可能改了 YggdrasilAuthenticator 的实现，需要同步更新 YggdrasilLogin。\n" +
                "现有方法：${authenticator.javaClass.declaredMethods.map { it.name }.distinct().sorted()}",
        )

        // 必须是 private：这正是我们走反射的原因
        assertTrue(
            java.lang.reflect.Modifier.isPrivate(method!!.modifiers),
            "该方法已不是 private，说明上游可能主动开放了 API，可以改回直接调用",
        )

        // 返回类型应当是 Account
        assertEquals(
            io.github.headlesshq.headlessmc.auth.Account::class.java,
            method.returnType,
            "方法返回类型变了，反射调用的强转会失败",
        )
    }

    @Test
    fun `能从容器取到 yggdrasil 认证源`() {
        HeadlessMcBootstrap.start()

        val p = provider()
        assertEquals(
            AuthProvider.YGGDRASIL,
            p.name,
            "认证源名字应为 yggdrasil，实际是 ${p.name}",
        )
    }

    private fun provider() = HeadlessMcBootstrap.bean(AuthService::class.java)
        .getProvider(AuthProvider.YGGDRASIL)
        .orElse(null)
        ?: error("容器里没有 yggdrasil 认证源，检查 auth-yggdrasil 是否被索引")
}
