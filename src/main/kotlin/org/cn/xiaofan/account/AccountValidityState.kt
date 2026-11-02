package org.cn.xiaofan.account

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import io.github.headlesshq.headlessmc.auth.Account
import io.github.headlesshq.headlessmc.auth.AuthService

/**
 * 账号有效性状态。
 *
 * <p>界面上需要知道「哪些账号的登录信息已经失效了」，用来把头像变灰、
 * 名字加删除线。这个信息只能靠**实际刷新 token** 才能确认 ——
 * `provider` 字段是磁盘上的 JSON，可以手改；token 也可能早被撤销。
 *
 * <h2>为什么不复用下载中心那套校验</h2>
 *
 * 下载中心校验的是「有没有任意一个有效微软号」（为了放行下载），
 * 而这里要的是「**每个**账号各自是否有效」（为了逐个标灰）。
 * 目标不同，判定粒度也不同，混用会导致一边妥协。
 *
 * <h2>什么时候校验</h2>
 *
 * 只在启动后和切换账号后触发，不做定时轮询：
 * 每次校验都要发网络请求，轮询会持续吃流量和电量。
 * 用户如果怀疑状态过期，切一下账号就会重新校验。
 */
@Stable
class AccountValidityState(
    private val scope: CoroutineScope,
) {
    /**
     * 已确认失效的账号 key 集合。
     *
     * <p>key 用 `provider:uuid`，与 [AccountProfile.avatarKey] 一致 ——
     * 用它而不是玩家名，是因为玩家名可以改，改完之后失效标记会挂到别人身上。
     */
    var invalidKeys by mutableStateOf<Set<String>>(emptySet())
        private set

    /** 是否正在校验。界面可以据此显示进度，避免用户以为标记丢了。 */
    var checking by mutableStateOf(false)
        private set

    /** 上次校验失败的说明；非 null 表示「没能完成校验」，而不是「账号都有效」。 */
    var lastError by mutableStateOf<String?>(null)
        private set

    private var job: kotlinx.coroutines.Job? = null

    /**
     * 重新校验全部账号的有效性。
     *
     * <p>重复调用会取消上一次：用户快速切换账号时不该并发跑多轮校验，
     * 否则最后谁覆盖谁不确定。
     */
    fun refresh() {
        job?.cancel()
        job = scope.launch {
            checking = true
            lastError = null
            try {
                invalidKeys = computeInvalidKeys()
            } catch (t: Throwable) {
                // 校验失败不等于账号失效：不能因为网络断了就把所有账号标灰，
                // 那会让用户以为连自己号都被清空了。
                // 保留上一次的结果，只记错误。
                lastError = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            } finally {
                checking = false
            }
        }
    }

    /** 逐个刷新，收集失效的账号。网络操作，全程在 IO 线程。 */
    private suspend fun computeInvalidKeys(): Set<String> = withContext(Dispatchers.IO) {
        val service = runCatching {
            HeadlessMcBootstrap.bean(AuthService::class.java)
        }.getOrElse {
            // 容器没起来：这不是「账号失效」，抛出去让上层保留旧结果。
            throw IllegalStateException("无法取得 AuthService", it)
        }

        val invalid = mutableSetOf<String>()

        for (account in service.accounts) {
            // 离线账号没有 token 可刷新，它们「永远不会失效」——
            // 把离线号标成失效只会误导用户。
            if (!account.isMicrosoftAccount && !account.isYggdrasilAccount) continue

            val ok = when (val result = refreshOne(account)) {
                is AuthPreflight.Result.Ready -> true

                is AuthPreflight.Result.CredentialInvalid,
                is AuthPreflight.Result.SessionExpired,
                -> false

                // 网络问题/限流：**不能**判定为失效。账号可能是好的，
                // 只是这次没验证成。误标成失效会让用户白白去重登。
                else -> true
            }

            if (!ok) {
                invalid.add("${account.provider}:${account.uuid}")
            }
        }

        invalid
    }

    /**
     * 刷新单个账号。微软与皮肤站走不同分支，复用 AuthPreflight 的分类逻辑。
     *
     * <p>用 try/catch 而不是 `runCatching`：后者是内联函数，但它的接收者
     * lambda **不是** suspend 的 —— 在里面调 suspend 函数编译不过。
     */
    private suspend fun refreshOne(account: Account): AuthPreflight.Result = try {
        when {
            account.isMicrosoftAccount -> AuthPreflight.refreshMicrosoft(account)
            account.isYggdrasilAccount -> AuthPreflight.refreshYggdrasil(account)
            else -> AuthPreflight.Result.Ready(account)
        }
    } catch (e: Exception) {
        // 刷新抛异常：按未预期处理。上层会把它当「有效」，
        // 因为异常更可能是环境问题而非凭据问题 —— 不轻易标灰。
        AuthPreflight.Result.Unexpected(
            detail = e.message ?: e.javaClass.simpleName,
            cause = e,
        )
    }

    /** 判断某个账号是否已确认失效。 */
    fun isInvalid(profile: AccountProfile): Boolean =
        profile.avatarKey in invalidKeys
}

/**
 * 创建并记住账号有效性状态。
 *
 * <p>由 [org.cn.xiaofan.ui.HmwLxApp] 持有并传给账号栏 ——
 * 状态放页面里的话，切页面会重建、标记丢失。
 */
@androidx.compose.runtime.Composable
fun rememberAccountValidityState(
    scope: CoroutineScope,
): AccountValidityState {
    return androidx.compose.runtime.remember(scope) {
        AccountValidityState(scope)
    }
}
