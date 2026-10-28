package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.AuthProvider
import io.github.headlesshq.headlessmc.auth.AuthService
import io.github.headlesshq.headlessmc.auth.Authenticator
import io.github.headlesshq.headlessmc.console.Console
import io.github.headlesshq.headlessmc.console.Password
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 微软正版账号登录（OAuth 2.0 设备码流程）。
 *
 * <p>流程就是「申请一个链接，然后等着」：
 * 1. 向微软申请设备码，得到一个可点击的验证链接（链接里已带设备码）
 * 2. 用户在浏览器打开链接、完成授权
 * 3. 后台阻塞等待，授权成功后账号自动写入 headlessmc 的账号存储
 *
 * <p>走 headlessmc 公开的 [Authenticator] 扩展点（`loginWithCode`），
 * 不反射任何私有方法。设备码由它通过 [Console.write] 输出，格式固定为：
 * ```
 * To login visit https://microsoft.com/link?otc=XXXXXXXX
 * ```
 * 我们实现一个只做「抓取这一行」的 [Console]，把链接交给 GUI。
 *
 * <p>为什么不直接调 `McAuthProvider.addAccount`：它是 package-private，
 * 包外访问不到，只能由 [Authenticator] 内部调用。
 */
object MicrosoftLogin {

    /** `Authenticator.getMethods()` 里设备码登录的键名。 */
    private const val METHOD_DEVICE_CODE = "default"

    /** 设备码输出行的前缀，见 [MicrosoftLogin] 的类注释。 */
    private const val DEVICE_CODE_PREFIX = "To login visit "

    /**
     * 发起一次设备码登录。
     *
     * <p>这是**阻塞调用**，必须在后台线程执行：内部会一直等待直到用户完成授权
     * 或设备码过期。
     *
     * @param onVerificationUrl 拿到验证链接时回调，GUI 用它显示「打开这个链接」。
     *                          回调在登录线程上执行，不要在里面做耗时操作。
     * @return 登录结果
     */
    fun login(onVerificationUrl: (String) -> Unit): LoginResult = runCatching {
        val provider = HeadlessMcBootstrap.bean(AuthService::class.java)
            .getProvider(AuthProvider.DEFAULT)
            .orElse(null)
            ?: return@runCatching LoginResult.Failed("headlessmc 未启用微软认证源")

        val method = provider.authenticator.methods[METHOD_DEVICE_CODE]
            ?: return@runCatching LoginResult.Failed(
                "微软认证源没有设备码登录方式，可用方式: ${provider.authenticator.methods.keys}"
            )

        val console = CaptureConsole(DEVICE_CODE_PREFIX, onVerificationUrl)
        val account = method.login(console)

        LoginResult.Success(
            AccountResolver.fromAccount(
                name = account.name,
                provider = account.provider,
                uuid = account.uuid,
            )
        )
    }.getOrElse { t ->
        LoginResult.Failed(t.message ?: t.javaClass.simpleName)
    }

    /**
     * 只用于截获设备码链接的 [Console]。
     *
     * <p>登录流程本身不需要任何输入，也不需要显示其他输出，所以除了抓取
     * 指定前缀的行，其余一律转给控制台（便于排查）。
     */
    private class CaptureConsole(
        private val prefix: String,
        private val onVerificationUrl: (String) -> Unit,
    ) : Console {

        override fun write(message: String) {
            // 设备码行可能在多次 write 里拼出来，也可能一次写完；两者都处理。
            val trimmed = message.trim()
            if (trimmed.startsWith(prefix)) {
                val url = trimmed.removePrefix(prefix).trim()
                if (url.isNotEmpty()) {
                    println("[HMWLX] 微软登录：请在浏览器打开 $url")
                    onVerificationUrl(url)
                    return
                }
            }
            println("[HMWLX][microsoft] $message")
        }

        override fun write(message: String, level: String) {
            write(message)
        }

        /**
         * 登录流程不需要任何输入。
         *
         * 返回空串而不是抛异常：万一某条路径尝试读输入，空串会让它走「无输入」
         * 分支，而不是把整个登录线程炸掉。
         */
        override fun read(): String = ""

        override fun readPassword(): Password = Password(charArrayOf())
    }
}

/**
 * 微软登录的结果。
 *
 * 用 sealed 类型而不是 null/异常：界面需要对「成功」与「失败」给出不同反馈，
 * 只回 null 的话用户不知道是网断了还是授权超时。
 */
sealed interface LoginResult {
    /** 登录成功，携带新账号。 */
    data class Success(val profile: AccountProfile) : LoginResult

    /**
     * 账号有多个角色，需要用户先选一个再继续登录。
     *
     * @property profiles 候选角色名，按服务端返回的顺序
     */
    data class ProfileRequired(val profiles: List<String>) : LoginResult

    /** 失败，`reason` 可直接展示。 */
    data class Failed(val reason: String) : LoginResult
}
