package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.Account
import io.github.headlesshq.headlessmc.auth.AuthException
import io.github.headlesshq.headlessmc.auth.AuthProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * [AuthPreflight] 的异常分类。
 *
 * <p>**只测分类逻辑，不碰网络**：所有异常都是手工构造的。
 * 真去连微软服务器会让测试依赖外网，既慢又不稳定，而且封号这类场景
 * 根本无法复现。
 *
 * <p>重点测两件事：
 * 1. **包了几层的异常还能不能认出来**。headlessmc 的 `McAuthProvider.refresh`
 *    会把 `IOException` 包进 `AuthException`，只看最外层永远认不出。
 * 2. **分类顺序**。网络判定必须在凭据判定之前，否则消息里恰好带 "401" 的
 *    网络错误会被误判成「账号失效」，把用户导向不必要的重新登录。
 */
class AuthPreflightTest {

    private val msaAccount = Account(
        AuthProvider.DEFAULT,
        "TestPlayer",
        "d8c48e47db1d315f8fcb0f8f260af745",
        "fake-token",
        Account.TYPE_MSA,
        "",
    )

    // ---------------------------------------------------------------------
    // 网络类
    // ---------------------------------------------------------------------

    @Test
    fun `连接被拒绝归为网络失败`() {
        val result = classify(AuthException(ConnectException("Connection refused")))

        assertTrue(result is AuthPreflight.Result.NetworkFailure, "实际类型: ${result::class.simpleName}")
        result as AuthPreflight.Result.NetworkFailure
        assertTrue(result.detail.contains("Connection refused"), "detail=${result.detail}")
        assertEquals("TestPlayer", result.account.name)
    }

    @Test
    fun `DNS 解析失败归为网络失败`() {
        val result = classify(AuthException(UnknownHostException("login.microsoftonline.com")))
        assertTrue(result is AuthPreflight.Result.NetworkFailure, "实际类型: ${result::class.simpleName}")
    }

    @Test
    fun `读超时归为网络失败`() {
        val result = classify(AuthException(SocketTimeoutException("Read timed out")))
        assertTrue(result is AuthPreflight.Result.NetworkFailure, "实际类型: ${result::class.simpleName}")
    }

    @Test
    fun `多层嵌套的 IOException 也能认出是网络问题`() {
        // 模拟真实链路：AuthException → IOException → ConnectException
        val deep = AuthException(IOException("wrapper", ConnectException("refused")))
        val result = classify(deep)
        assertTrue(result is AuthPreflight.Result.NetworkFailure, "实际类型: ${result::class.simpleName}")
    }

    // ---------------------------------------------------------------------
    // 凭据类
    // ---------------------------------------------------------------------

    @Test
    fun `HTTP 401 归为凭据失效`() {
        val result = classify(AuthException("Request failed with status code 401 Unauthorized"))

        assertTrue(result is AuthPreflight.Result.CredentialInvalid, "实际类型: ${result::class.simpleName}")
        result as AuthPreflight.Result.CredentialInvalid
        assertEquals("TestPlayer", result.account.name)
    }

    @Test
    fun `HTTP 403 归为凭据失效`() {
        val result = classify(AuthException("Forbidden"))
        assertTrue(result is AuthPreflight.Result.CredentialInvalid, "实际类型: ${result::class.simpleName}")
    }

    @Test
    fun `invalid_grant 归为凭据失效`() {
        val result = classify(
            AuthException(
                "invalid_grant: The provided value for the input parameter " +
                    "'refresh_token' is not valid"
            )
        )
        assertTrue(result is AuthPreflight.Result.CredentialInvalid, "实际类型: ${result::class.simpleName}")
    }

    @Test
    fun `token 过期归为凭据失效`() {
        val result = classify(AuthException("Minecraft token expired"))
        assertTrue(result is AuthPreflight.Result.CredentialInvalid, "实际类型: ${result::class.simpleName}")
    }

    // ---------------------------------------------------------------------
    // provider / 未知
    // ---------------------------------------------------------------------

    @Test
    fun `找不到 provider 归为认证源不可用`() {
        val result = classify(
            AuthException("Failed to find provider yggdrasil, available: msa,offline")
        )

        assertTrue(result is AuthPreflight.Result.ProviderUnavailable, "实际类型: ${result::class.simpleName}")
        result as AuthPreflight.Result.ProviderUnavailable
        assertEquals(AuthProvider.DEFAULT, result.provider)
    }

    @Test
    fun `无法归类的异常归为 Unexpected 并保留 cause`() {
        val e = AuthException("wrapped")
        e.initCause(IllegalStateException("something weird"))

        val result = classify(e)

        assertTrue(result is AuthPreflight.Result.Unexpected, "实际类型: ${result::class.simpleName}")
        result as AuthPreflight.Result.Unexpected
        assertSame(e, result.cause)
    }

    // ---------------------------------------------------------------------
    // 429 限流
    // ---------------------------------------------------------------------

    @Test
    fun `429 Too Many Requests 归为限流而不是网络失败`() {
        // 实测碰到的真实形态：MinecraftAuth 把响应体塞进异常消息里。
        // 这条如果落到 NetworkFailure，用户会去检查自己的网络 —— 而实际
        // 该做的是什么都不做，等几分钟。
        val e = AuthException(
            """status: 429 Too Many Requests, message: { "path" : "/launcher/login" }"""
        )
        val result = classify(e)

        assertTrue(result is AuthPreflight.Result.RateLimited, "实际类型: ${result::class.simpleName}")
    }

    @Test
    fun `429 能被认出并被网络判定拦截之前处理掉`() {
        // 带上 ConnectException 的 cause：如果 429 判定排在网络判定之后，
        // 这条就会被归为网络失败。顺序错误会在这里暴露。
        val e = AuthException("429 Too Many Requests", ConnectException("reset"))
        val result = classify(e)

        assertTrue(result is AuthPreflight.Result.RateLimited, "实际类型: ${result::class.simpleName}")
    }

    @Test
    fun `429 能解析出 Retry-After 秒数`() {
        val e = AuthException("""status: 429, {"retryAfter": 300}""")
        val result = classify(e)

        assertTrue(result is AuthPreflight.Result.RateLimited, "实际类型: ${result::class.simpleName}")
        result as AuthPreflight.Result.RateLimited
        assertEquals(300L, result.retryAfter)
    }

    @Test
    fun `429 没有 Retry-After 时为 null`() {
        val e = AuthException("429 Too Many Requests")
        val result = classify(e)

        assertTrue(result is AuthPreflight.Result.RateLimited, "实际类型: ${result::class.simpleName}")
        result as AuthPreflight.Result.RateLimited
        assertEquals(null, result.retryAfter)
    }

    @Test
    fun `异常链取最内层的消息而不是外层的包装`() {
        // 外层 AuthException 的消息往往是泛泛的一句话，真正的细节在里层。
        val e = AuthException("Authentication failed", IllegalStateException("token revoked"))
        val result = classify(e)

        assertTrue(result is AuthPreflight.Result.CredentialInvalid, "实际类型: ${result::class.simpleName}")
        result as AuthPreflight.Result.CredentialInvalid
        assertEquals("token revoked", result.detail)
    }

    // ---------------------------------------------------------------------
    // 分类顺序
    // ---------------------------------------------------------------------

    @Test
    fun `网络异常优先于凭据判定`() {
        // 最容易写错的一条：消息里带 "401" 的网络错误，
        // 如果先判凭据就会被误判成「账号失效」。
        val e = AuthException(ConnectException("connect to port 401 failed"))
        val result = classify(e)
        assertTrue(result is AuthPreflight.Result.NetworkFailure, "实际类型: ${result::class.simpleName}")
    }

    /**
     * 调私有方法 `classify`。
     *
     * <p>反射是因为 `classify` 不该公开：它是 `check()` 的内部实现，
     * 对外只暴露 `check()`。而 `check()` 需要真容器和真网络，测不了。
     * 与其为了测试把私有方法改成 public，不如在这一处用反射。
     */
    private fun classify(e: Throwable): AuthPreflight.Result {
        val method = AuthPreflight::class.java
            .getDeclaredMethod("classify", Account::class.java, Throwable::class.java)
        method.isAccessible = true
        return method.invoke(AuthPreflight, msaAccount, e) as AuthPreflight.Result
    }

    // ---------------------------------------------------------------------
    // shouldWarn / blocksLaunch：按账号类型分流
    //
    // 这组测试锁定一条容易被破坏的规则：网络类失败**只对微软账号**提示。
    // 曾经的 bug 是预检读的账号（lastUsed）和界面显示的账号（accounts 第一个）
    // 不是同一条，导致用户选离线账号却弹出微软令牌的提示 —— 表现上像是
    // 「账号类型判断错了」，实际是取错了账号。这里从结果侧兜底：
    // 只要 account.provider 是离线或皮肤站，就绝不该提示网络问题。
    // ---------------------------------------------------------------------

    @Test
    fun `微软账号的网络失败需要提示但不阻止启动`() {
        val result = AuthPreflight.Result.NetworkFailure(msaAccount, "timeout")

        assertTrue(result.shouldWarn, "微软账号的网络问题应该提示")
        assertEquals(false, result.blocksLaunch, "网络问题不该阻止启动")
    }

    @Test
    fun `微软账号的 429 限流需要提示但不阻止启动`() {
        val result = AuthPreflight.Result.RateLimited(msaAccount, 300, "429")

        assertTrue(result.shouldWarn, "微软账号的限流应该提示")
        assertEquals(false, result.blocksLaunch, "限流不该阻止启动")
    }

    @Test
    fun `离线账号的网络失败不提示也不阻止`() {
        val offline = accountWith(AuthProvider.OFFLINE)
        val result = AuthPreflight.Result.NetworkFailure(offline, "timeout")

        assertEquals(false, result.shouldWarn, "离线账号不该因为微软连不上被打扰")
        assertEquals(false, result.blocksLaunch)
    }

    @Test
    fun `皮肤站的网络失败不提示也不阻止`() {
        val yggdrasil = accountWith(AuthProvider.YGGDRASIL)
        val result = AuthPreflight.Result.NetworkFailure(yggdrasil, "timeout")

        assertEquals(false, result.shouldWarn, "皮肤站走自己的服务器，与微软无关")
        assertEquals(false, result.blocksLaunch)
    }

    @Test
    fun `离线账号的 429 限流不提示`() {
        val offline = accountWith(AuthProvider.OFFLINE)
        val result = AuthPreflight.Result.RateLimited(offline, 60, "429")

        assertEquals(false, result.shouldWarn)
    }

    @Test
    fun `凭据失效无论哪种账号都要提示并阻止启动`() {
        // 凭据问题是账号自身的问题，跟 provider 无关：
        // 微软账号被吊销、皮肤站账号密码改了，都得让用户知道。
        listOf(
            AuthProvider.DEFAULT,
            AuthProvider.OFFLINE,
            AuthProvider.YGGDRASIL,
        ).forEach { provider ->
            val result = AuthPreflight.Result.CredentialInvalid(
                accountWith(provider),
                "revoked",
            )
            assertTrue(result.shouldWarn, "provider=$provider 凭据失效应该提示")
            assertTrue(result.blocksLaunch, "provider=$provider 凭据失效必须阻止启动")
        }
    }

    @Test
    fun `Ready 既不提示也不阻止`() {
        val result = AuthPreflight.Result.Ready(msaAccount)
        assertEquals(false, result.shouldWarn)
        assertEquals(false, result.blocksLaunch)
    }

    @Test
    fun `provider 名字大小写不影响微软账号判定`() {
        // provider 字符串来自磁盘上的 JSON，大小写不受我们控制。
        // 注意微软 provider 的实际值是 "default"（见 AuthProvider.DEFAULT），
        // 不是 "msa" —— "msa" 是 Account.type 的取值，两者别混。
        val upper = accountWith("DEFAULT")
        val result = AuthPreflight.Result.NetworkFailure(upper, "timeout")

        assertTrue(result.shouldWarn, "大小写不该影响判定")
    }

    @Test
    fun `Account type 的 msa 不被误当成微软 provider`() {
        // 这是最容易写错的一处：Account 有两个"类型"字段 ——
        // provider（认证源：default/offline/yggdrasil）
        // type   （账号类型：msa/legacy）
        // 判定微软账号只能看 provider。如果误看 type，离线账号的
        // type 也是 msa，会把它当成微软账号去提示网络问题。
        val offline = Account(
            AuthProvider.OFFLINE,
            "TestPlayer",
            "d8c48e47db1d315f8fcb0f8f260af745",
            "",
            Account.TYPE_MSA,        // ← type 是 msa
            "",
        )
        val result = AuthPreflight.Result.NetworkFailure(offline, "timeout")

        assertEquals(false, result.shouldWarn, "离线账号的 type 也是 msa，不能据此判断")
    }

    /**
     * 造一个指定 provider 的账号。
     *
     * <p>不能直接用 `copy`：[Account] 是 Lombok `@Data` 的 **Java** 类，
     * 没有 Kotlin data class 的 `copy`。
     */
    private fun accountWith(provider: String): Account = Account(
        provider,
        "TestPlayer",
        "d8c48e47db1d315f8fcb0f8f260af745",
        "fake-token",
        Account.TYPE_MSA,
        "",
    )
}
