package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.Account
import io.github.headlesshq.headlessmc.auth.AuthException
import io.github.headlesshq.headlessmc.auth.AuthProvider
import io.github.headlesshq.headlessmc.auth.AuthService
import io.github.headlesshq.headlessmc.auth.LastUsedAccountService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 启动前的账号预检：把 headlessmc `AuthService.refresh()` 会抛的异常分成几类，
 * 让界面能针对不同原因说人话。
 *
 * <h2>为什么需要这一层</h2>
 *
 * headlessmc 的 `refresh()` 失败时**直接向上抛**，而且把所有网络层错误都包成
 * 同一个 [AuthException]（见 `McAuthProvider.refresh`：`catch (IOException e)
 * -> throw new AuthException(e)`）。调用方拿到的是一个笼统的异常，
 * 分不清「网络断了」和「账号被撤销」。
 *
 * 这两种情况对用户的意义完全不同：
 * - 网络断：等会儿再试，或者换离线账号先玩
 * - 账号失效：必须重新登录，重试一万次也没用
 *
 * 所以这里沿着异常的 cause 链往下走，按底层类型分类。
 *
 * <h2>为什么不直接启动、失败了再说</h2>
 *
 * 离线账号没有 token，不联网也能进单人游戏。正版账号离线启动会拿到一个过期的
 * token，游戏能开但一进正版验证服务器就被踢。用户看不出区别，只会以为启动器坏了。
 * 所以在启动**之前**就把话说清楚。
 */
object AuthPreflight {

    /**
     * 预检结果。
     */
    sealed interface Result {
        /**
         * 账号可用，可以启动。
         *
         * @property account 已刷新过的账号，token 是最新的。
         *   注意刷新后返回的是**新对象**，[Account] 的字段全是 final，
         *   刷新不会原地改旧对象。
         */
        data class Ready(val account: Account) : Result

        /**
         * 没有可用账号，连离线兜底都没有。
         *
         * 只会在「存储里没有任何账号」时出现 —— 正常情况至少有
         * [Account.defaultOfflineAccount] 兜底，但这里不做兜底：
         * 用户点的是自己选的账号，悄悄换成 Steve 会让人莫名其妙。
         */
        data object NoAccount : Result

        /**
         * 网络问题导致 token 无法刷新。
         *
         * <p>**是否提示由账号类型决定**（见 [AuthPreflight.Result.shouldWarn]）：
         * 只有微软账号的 token 需要定期找微软续期，所以只有它会碰到这个问题。
         * 离线账号没有 token，皮肤站走自己的服务器 —— 微软连不上跟它们无关，
         * 弹窗只会让用户误以为有什么要处理。
         *
         * @property account 没刷新成功的原账号，token 可能是旧的
         * @property detail 底层原因，拼在提示后面方便排查
         */
        data class NetworkFailure(
            val account: Account,
            val detail: String,
        ) : Result

        /**
         * 账号凭据失效：token 被撤销、账号被封、没有游戏所有权（XGP 过期）。
         *
         * <p>**不可降级**：重试没用，只能重新登录。
         *
         * @property account 失效的账号，界面可以用它定位到具体哪一条
         * @property detail 底层原因
         */
        data class CredentialInvalid(
            val account: Account,
            val detail: String,
        ) : Result

        /**
         * 被服务器限流（HTTP 429）。
         *
         * <p>这不是网络问题也不是凭据问题：请求**成功送达**了，微软也听懂了，
         * 只是回了一句「你问得太频」。常见触发原因：
         * - 短时间内反复点启动
         * - 网络波动后客户端自动重试
         * - 同一个 IP 下有别人也在用官方启动器
         *
         * <p>**可以降级启动**：token 本身可能还是有效的，只是这次没能验证。
         *
         * @property retryAfter 服务器给的 Retry-After 秒数，没有时为 null
         */
        data class RateLimited(
            val account: Account,
            val retryAfter: Long?,
            val detail: String,
        ) : Result

        /**
         * 账号所属的认证源当前不可用（比如 Yggdrasil 站没配置 url、
         * 或者 provider 被禁用）。
         */
        data class ProviderUnavailable(val provider: String, val detail: String) : Result

        /**
         * 其它未归类的错误。保留原始异常链，不吞掉。
         */
        data class Unexpected(val detail: String, val cause: Throwable?) : Result
    }

    /**
     * 检查当前账号并刷新 token。
     *
     * <p>整个流程要发两次 HTTP 请求（token + profile），所以走 [Dispatchers.IO]。
     * 界面在这段时间应该显示「正在检查账号…」，而不是让按钮看起来没反应。
     */
    suspend fun check(): Result = withContext(Dispatchers.IO) {
        val account = readCurrentAccount() ?: return@withContext Result.NoAccount

        // 离线账号没有 token，刷新没有意义，直接放行。
        // 对离线账号调 refresh 会走到 AuthProvider.OFFLINE 的实现，
        // 它返回的也是原对象，但少一次无谓的分支判断。
        if (AuthProvider.OFFLINE.equals(account.provider, ignoreCase = true)) {
            return@withContext Result.Ready(account)
        }

        // 只有微软账号需要找微软续期。
        //
        // 皮肤站走自己的服务器，「微软连不上」既不影响它，也不该拿
        // 微软的错误文案去吓用户。这里提前放行，而不是等 refresh 抛异常
        // 再由 shouldWarn 过滤 —— 后者会让用户每次启动都白等两次 HTTP 超时，
        // 而且那条路径上的错误信息跟账号类型是错配的。
        //
        // 注意：皮肤站的 token 也可能是过期的，但那要让皮肤站自己报错，
        // 不是我们在启动前猜。
        if (!account.isMicrosoftAccount) {
            return@withContext Result.Ready(account)
        }

        val authService = runCatching {
            HeadlessMcBootstrap.bean(AuthService::class.java)
        }.getOrElse {
            return@withContext Result.Unexpected(
                detail = it.message ?: it.javaClass.simpleName,
                cause = it,
            )
        }

        try {
            Result.Ready(authService.refresh(account))
        } catch (e: AuthException) {
            classify(account, e)
        } catch (e: Exception) {
            // refresh 理论上只抛 AuthException，但 provider 的实现可能抛别的。
            // 兜住而不是让它冒到 UI 线程上去，否则会连累已经启动的容器。
            classify(account, e)
        }
    }

    /**
     * 按异常链分类。
     *
     * <p>分类顺序有讲究：**先判网络，再判凭据**。原因是网络异常会被包裹成
     * `AuthException`，而凭据失效的异常也会带 `IOException` 子类（HTTP 4xx）。
     * 如果先判凭据，某些网络错误会被误判成「账号失效」，把用户导向
     * 不必要的重新登录。
     */
    private fun classify(account: Account, e: Throwable): Result {
        val chain = e.causeChain()

        // 整条异常链拼成一个字符串，后面几处判定都要用。
        val text = buildString {
            var cur: Throwable? = e
            while (cur != null) {
                append(cur.javaClass.simpleName).append(' ')
                append(cur.message.orEmpty()).append(' ')
                cur = cur.cause
            }
        }.lowercase()

        // ---- 限流：必须最先判 ----
        // 429 的响应体里通常带 IOException 之类的外壳，如果先判网络
        // 就会把「你问得太频」当成「网络不通」，给出完全错误的建议。
        if ("429" in text || "too many requests" in text || "rate limit" in text) {
            return Result.RateLimited(
                account = account,
                retryAfter = extractRetryAfter(text),
                detail = rootMessage(e),
            )
        }

        // ---- 网络层：连不上、超时、DNS 失败 ----
        // 这三种都是「请求根本没送到」或「送到了没等到回复」，
        // 与账号本身的状态无关。
        chain.firstOrNull {
            it is UnknownHostException ||
                it is ConnectException ||
                it is SocketTimeoutException
        }?.let {
            return Result.NetworkFailure(
                account = account,
                detail = it.message ?: it.javaClass.simpleName,
            )
        }

        // ---- 认证层：HTTP 401 / 403，或 MinecraftAuth 抛的凭据类异常 ----
        // MinecraftAuth 把 HTTP 错误包在自己的异常里，类名带 "Unauthorized"
        // 或 "Forbidden"，也可能直接是带状态码信息的 AuthException。
        // 这里按名字匹配是不得已：那些类不是我们的依赖，不能直接 import。
        val looksLikeCredential = listOf(
            "401", "403", "unauthorized", "forbidden",
            "invalid_grant", "invalidgrant",
            "invalid session", "invalidtoken", "invalid token",
            "expired", "revoked",
        ).any { it in text }

        if (looksLikeCredential) {
            return Result.CredentialInvalid(account, rootMessage(e))
        }

        // ---- 其它 IOException：宽泛归到网络 ----
        // 走到这里说明有 IO 错误但不匹配上面的具体类型（比如 SSL 握手失败、
        // 连接被重置）。对用户来说都是「网络不通」，归到网络比归到「未知」有用。
        if (chain.any { it is IOException }) {
            return Result.NetworkFailure(account, rootMessage(e))
        }

        // ---- provider 找不到 ----
        // AuthServiceImpl 在 enabledProviders 里找不到匹配的 provider 时抛
        // AuthException("Failed to find provider ...")。
        // 典型场景：Yggdrasil 没配 url，isEnabled() 返回 false。
        if ("failed to find provider" in text) {
            return Result.ProviderUnavailable(
                provider = account.provider,
                detail = rootMessage(e),
            )
        }

        return Result.Unexpected(rootMessage(e), e)
    }

    /**
     * 从异常链里取最有信息量的那条消息。
     *
     * <p>优先用**最内层**的：外层通常是 `AuthException` 这种包装，
     * 消息要么是空的要么是泛泛的一句话，真正的细节（HTTP 状态码、JSON body）
     * 都在最里层。
     */
    private fun rootMessage(e: Throwable): String {
        val chain = e.causeChain()
        return chain.lastOrNull()?.message
            ?: e.message
            ?: e.javaClass.simpleName
    }

    /**
     * 从响应文本里抠出 `retry_after` 之类的秒数。
     *
     * <p>微软的 429 响应体里带 `"retryAfter": 300` 或 `Retry-After: 300`，
     * 拿到的话可以直接告诉用户「再等 5 分钟」，比笼统的「过一会儿」有用得多。
     * 拿不到就返回 null，界面回退到通用文案。
     */
    private fun extractRetryAfter(text: String): Long? {
        // 匹配 retryafter / retry-after / retry_after 后面跟数字的几种写法。
        val regex = Regex("""retry[_-]?after["']?\s*[:=]\s*"?(\d+)""")
        return regex.find(text)?.groupValues?.getOrNull(1)?.toLongOrNull()
            ?.takeIf { it in 1..3600 }
    }

    /** 沿 cause 链展开，最多 16 层，防止异常自己引用自己时死循环。 */
    private fun Throwable.causeChain(): List<Throwable> {
        val result = mutableListOf<Throwable>()
        var cur: Throwable? = this
        var guard = 0
        while (cur != null && guard++ < 16) {
            result += cur
            if (cur.cause === cur) break
            cur = cur.cause
        }
        return result
    }

    /**
     * 读当前账号的原始 [Account]（带 token 的那个）。
     *
     * <p>与 [AccountService.currentAccount] 的区别：那边返回的是给界面看的
     * [AccountProfile]，**没有 token 字段**；启动需要 token，所以这里直接读原始对象。
     *
     * <p>不做「回退到列表第一个」这一步：启动是用户对**特定账号**的操作，
     * 悄悄换成另一个账号启动，名字和皮肤都变了，用户会以为是 bug。
     * 实在没有账号时返回 null，由调用方提示去登录。
     */
    private fun readCurrentAccount(): Account? = runCatching {
        HeadlessMcBootstrap.bean(LastUsedAccountService::class.java)
            .lastUsedAccount
            .orElse(null)
    }.getOrNull()
}

// ---------------------------------------------------------------------------
// 结果的判定规则
//
// 放在 object 外面是因为它们是扩展属性，需要被 UI 层 import。
// 写在 object 内部的话外部拿不到（`AuthPreflight.Result.shouldWarn`
// 这种写法 Kotlin 不支持），只能靠成员方法绕，那反而不如顶层清晰。
// ---------------------------------------------------------------------------

/**
 * 这个结果该不该打扰用户。
 *
 * <p>分两类：
 *
 * **永远提示**（账号本身有问题，不处理就没法玩）：
 * - [AuthPreflight.Result.CredentialInvalid]、[AuthPreflight.Result.NoAccount]、
 *   [AuthPreflight.Result.ProviderUnavailable]、[AuthPreflight.Result.Unexpected]
 *
 * **看账号类型**：
 * - [AuthPreflight.Result.NetworkFailure]、[AuthPreflight.Result.RateLimited]
 *   只在**微软账号**上提示。
 *
 * <p>为什么要按账号类型分：微软账号的 token 是有期限的，需要定期找微软续期。
 * 微软连不上 → 这次续不了 → token 可能真的过期了，用户该知道。
 *
 * <p>离线账号根本没有 token，皮肤站走的是自己的服务器 —— 微软连不上
 * 跟它们毫无关系，弹窗纯属干扰。而且这两类账号启动后要去验证的
 * 也不是微软服务器，提示「可能进不了正版验证服务器」是错的。
 */
val AuthPreflight.Result.shouldWarn: Boolean
    get() = when (this) {
        is AuthPreflight.Result.Ready -> false
        is AuthPreflight.Result.NetworkFailure -> account.isMicrosoftAccount
        is AuthPreflight.Result.RateLimited -> account.isMicrosoftAccount
        is AuthPreflight.Result.CredentialInvalid -> true
        is AuthPreflight.Result.NoAccount -> true
        is AuthPreflight.Result.ProviderUnavailable -> true
        is AuthPreflight.Result.Unexpected -> true
    }

/**
 * 这个结果是否应该**阻止启动**。
 *
 * <p>只有账号本身有问题的才拦：网络类问题是暂时的，token 大概率还有效，
 * 拦住用户反而让他玩不了单机。
 */
val AuthPreflight.Result.blocksLaunch: Boolean
    get() = when (this) {
        is AuthPreflight.Result.Ready -> false
        is AuthPreflight.Result.NetworkFailure -> false
        is AuthPreflight.Result.RateLimited -> false
        is AuthPreflight.Result.CredentialInvalid -> true
        is AuthPreflight.Result.NoAccount -> true
        is AuthPreflight.Result.ProviderUnavailable -> true
        is AuthPreflight.Result.Unexpected -> true
    }

/**
 * 是不是微软账号。
 *
 * <p>`AuthProvider.DEFAULT` 就是微软正版（见 `AccountResolver` 的文档：
 * provider 取值 `OFFLINE` / `YGGDRASIL` / `DEFAULT`）。
 * 比较时忽略大小写，因为 provider 字符串来自磁盘上的 JSON，
 * 大小写不受我们控制。
 */
internal val Account.isMicrosoftAccount: Boolean
    get() = AuthProvider.DEFAULT.equals(provider, ignoreCase = true)
