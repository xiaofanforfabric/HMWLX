package org.cn.xiaofan.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.cn.xiaofan.account.AuthPreflight
import org.cn.xiaofan.account.authServerLabel
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/**
 * 启动前账号预检的提示对话框。
 *
 * <p>按 [AuthPreflight.Result.blocksLaunch] 和 [AuthPreflight.Result.shouldWarn]
 * 两把尺子分成两种情况，按钮组合完全不同：
 *
 * **阻止启动**（账号本身有问题，不处理就没法玩）：
 *
 * | 结果 | 主按钮 | 次按钮 |
 * |---|---|---|
 * | 凭据失效 | 重新登录 | 关闭 |
 * | 无账号 | 去登录 | 关闭 |
 * | 认证源不可用 | 关闭 | — |
 * | 未知错误 | 关闭 | — |
 *
 * **仅提示、不拦**（微软账号的网络类问题）：
 *
 * | 结果 | 主按钮 | 次按钮 |
 * |---|---|---|
 * | 网络失败 | 仍然启动 | 取消 |
 * | 429 限流 | 仍然启动 | 取消 |
 *
 * <p>为什么网络类还能「仍然启动」：`refresh()` 失败只说明这次没验证成，
 * 不代表 token 已经作废。token 有效期通常几小时到一天，网络断一下不会让它失效。
 * 单人游戏根本不需要 token，联机时才需要 —— 拦住用户等于让他连单机都玩不了。
 *
 * <p>但提示本身是必要的：微软账号续期失败是**真实可能**token 过期的，
 * 用户该知道「这次启动可能进不了正版验证服务器」。
 *
 * @param result 预检结果，为 null 或 [AuthPreflight.Result.Ready] 时不显示
 * @param onLaunchAnyway 网络类失败时仍要启动
 * @param onRelogin 去重新登录
 * @param onDismiss 关闭对话框
 */
@Composable
fun AuthFailureDialog(
    result: AuthPreflight.Result?,
    onLaunchAnyway: () -> Unit,
    onRelogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    if (result == null || result is AuthPreflight.Result.Ready) return

    val spec = when (result) {
        is AuthPreflight.Result.NetworkFailure -> DialogSpec(
            // 服务器名称按账号类型取：微软账号说「微软服务器」，
            // 皮肤站说「外置登录服务器」。混用会让用户去检查错误的东西。
            title = text(TextKey.AUTH_NETWORK_FAILED_TITLE, result.account.authServerLabel),
            body = text(
                TextKey.AUTH_NETWORK_FAILED_BODY,
                result.account.authServerLabel,
                result.account.name,
            ),
            hint = text(TextKey.AUTH_NETWORK_FAILED_HINT),
            detail = result.detail,
            primary = Button(text(TextKey.AUTH_NETWORK_LAUNCH_ANYWAY), onLaunchAnyway),
            secondary = Button(text(TextKey.AUTH_NETWORK_CANCEL), onDismiss),
        )

        is AuthPreflight.Result.RateLimited -> DialogSpec(
            title = text(TextKey.AUTH_NETWORK_FAILED_TITLE, result.account.authServerLabel),
            // 服务器给了 Retry-After 就说出来；拿不到就用笼统的说法。
            body = if (result.retryAfter != null) {
                text(
                    TextKey.AUTH_RATE_LIMITED_BODY,
                    result.account.authServerLabel,
                    result.account.name,
                    formatDelay(result.retryAfter),
                )
            } else {
                text(
                    TextKey.AUTH_RATE_LIMITED_BODY_NO_DELAY,
                    result.account.authServerLabel,
                    result.account.name,
                )
            },
            hint = text(TextKey.AUTH_NETWORK_FAILED_HINT),
            detail = result.detail,
            primary = Button(text(TextKey.AUTH_NETWORK_LAUNCH_ANYWAY), onLaunchAnyway),
            secondary = Button(text(TextKey.AUTH_NETWORK_CANCEL), onDismiss),
        )

        is AuthPreflight.Result.CredentialInvalid -> DialogSpec(
            title = text(TextKey.AUTH_CREDENTIAL_INVALID_TITLE, result.account.name),
            body = text(TextKey.AUTH_CREDENTIAL_INVALID_HINT),
            hint = null,
            detail = result.detail,
            primary = Button(text(TextKey.AUTH_CREDENTIAL_RELOGIN), onRelogin),
            secondary = Button(text(TextKey.AUTH_DISMISS), onDismiss),
        )

        is AuthPreflight.Result.SessionExpired -> DialogSpec(
            title = text(TextKey.AUTH_SESSION_EXPIRED_TITLE, result.account.name),
            body = text(TextKey.AUTH_SESSION_EXPIRED_HINT),
            hint = null,
            detail = result.detail,
            // 主按钮是「继续启动」：上游已把账号降级成离线，单人游戏照常玩。
            // 拦下来反而剥夺了用户玩单机的权利。
            primary = Button(text(TextKey.AUTH_SESSION_EXPIRED_LAUNCH), onLaunchAnyway),
            secondary = Button(text(TextKey.AUTH_CREDENTIAL_RELOGIN), onRelogin),
        )

        is AuthPreflight.Result.NoAccount -> DialogSpec(
            title = text(TextKey.AUTH_NO_ACCOUNT),
            body = null,
            hint = null,
            detail = null,
            primary = Button(text(TextKey.AUTH_CREDENTIAL_RELOGIN), onRelogin),
            secondary = Button(text(TextKey.AUTH_DISMISS), onDismiss),
        )

        is AuthPreflight.Result.ProviderUnavailable -> DialogSpec(
            title = text(TextKey.AUTH_PROVIDER_UNAVAILABLE_TITLE, result.provider),
            body = null,
            hint = null,
            detail = result.detail,
            primary = Button(text(TextKey.AUTH_DISMISS), onDismiss),
        )

        is AuthPreflight.Result.Unexpected -> DialogSpec(
            title = text(TextKey.AUTH_UNEXPECTED_TITLE),
            body = null,
            hint = null,
            detail = result.detail,
            primary = Button(text(TextKey.AUTH_DISMISS), onDismiss),
        )

        is AuthPreflight.Result.Ready -> return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = spec.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        },
        text = {
            Column(modifier = Modifier.widthIn(max = 460.dp)) {
                if (spec.body != null) {
                    Text(text = spec.body, style = MaterialTheme.typography.bodyMedium)
                }
                if (spec.hint != null) {
                    if (spec.body != null) Spacer(Modifier.height(10.dp))
                    Text(
                        text = spec.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (spec.detail != null) {
                    if (spec.body != null || spec.hint != null) Spacer(Modifier.height(10.dp))
                    // 底层原因用小字灰色放在最后：给需要排查的人看，
                    // 但不该是正文的主角。
                    Text(
                        text = spec.detail,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f),
                    )
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                spec.secondary?.let { btn ->
                    TextButton(onClick = btn.action) { Text(btn.label) }
                }
                TextButton(onClick = spec.primary.action) {
                    Text(spec.primary.label, fontWeight = FontWeight.SemiBold)
                }
            }
        },
    )
}

/** 对话框的文案与按钮。 */
private data class DialogSpec(
    val title: String,
    val body: String?,
    /** 次要说明，比 [body] 小一档、淡一档。 */
    val hint: String?,
    val detail: String?,
    val primary: Button,
    val secondary: Button? = null,
)

private data class Button(val label: String, val action: () -> Unit)

/**
 * 把 Retry-After 的秒数说成人话。
 *
 * <p>向上取整到分钟：说「5 分钟」比说「300 秒」好理解，
 * 且宁可让用户多等一会儿也不要提前去撞 429。
 */
private fun formatDelay(seconds: Long): String {
    val minutes = (seconds + 59) / 60
    return if (minutes <= 1) "1 分钟" else "$minutes 分钟"
}
