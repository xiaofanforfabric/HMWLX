package org.cn.xiaofan.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cn.xiaofan.account.AccountProfile
import org.cn.xiaofan.account.LoginResult
import org.cn.xiaofan.account.MicrosoftLogin
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import org.cn.xiaofan.util.Browser

/**
 * 微软账号登录对话框。
 *
 * <p>流程：打开即向微软申请设备码 → 显示验证链接 → 用户在浏览器完成授权 →
 * 后台等待 → 成功后回调 [onLoggedIn]。
 *
 * <p>请求设备码与等待授权都是有网络操作的阻塞调用，全部放在 IO 线程，
 * 界面只负责展示状态。
 *
 * <p>成功与失败都走 [onDismiss]：本对话框不负责切号，也不负责刷新账号列表，
 * 那些在调用方做。它只上报结果、然后退场。
 *
 * @param onLoggedIn 登录成功，携带新账号。在 UI 线程回调，可以安全地改状态
 * @param onDismiss 关闭对话框。登录成功后会被自动调用一次
 */
@Composable
fun MicrosoftLoginDialog(
    onLoggedIn: (AccountProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    // null 表示还在申请设备码
    var verificationUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // 浏览器是否已经自动拉起过，避免重组时重复打开
    var browserOpened by remember { mutableStateOf(false) }
    // 是否已成功。登录线程把结果抛回 UI 线程，对话框自己关掉。
    //
    // 曾经的做法是让调用方在 onLoggedIn 里把 showMicrosoftLogin 置 false ——
    // 但那个回调是从 IO 线程上的登录线程里发出的，Compose 不会因为别的线程
    // 改了 state 就取消正在运行的 LaunchedEffect，对话框留在原地转圈，
    // 用户看不到「登录成功」，也看不到列表已经变了。
    var succeeded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 整个登录流程是阻塞的，必须离开 UI 线程
        val result = withContext(Dispatchers.IO) {
            MicrosoftLogin.login { url ->
                // 回调在 IO 线程上触发。只写状态，不在这里做任何事 ——
                // 浏览器是交给下面那个 effect 去开的，保证只开一次。
                verificationUrl = url
            }
        }
        // 回到这里已经在 UI 线程：先收掉成功状态，再通知调用方，
        // 两件事都在同一个线程上完成，顺序可预期。
        when (result) {
            is LoginResult.Success -> {
                succeeded = true
                onLoggedIn(result.profile)
            }
            is LoginResult.Failed -> error = result.reason
        }
    }

    // 拿到链接就自动拉起浏览器。放在独立的 effect 里而不是登录回调内：
    // 回调在 IO 线程执行，且可能被调用多次，这里用 browserOpened 保证只开一次。
    LaunchedEffect(verificationUrl) {
        val url = verificationUrl ?: return@LaunchedEffect
        if (browserOpened) return@LaunchedEffect
        browserOpened = true
        Browser.open(url)
    }

    // 登录成功后自己关：调用方那边要切号、要刷新列表，对话框不该继续挂着
    LaunchedEffect(succeeded) {
        if (succeeded) onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (succeeded || error != null) onDismiss() },
        title = { Text(text(TextKey.ACCOUNT_LOGIN_MICROSOFT)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                val url = verificationUrl

                when {
                    error != null -> {
                        Text(
                            text = Messages.get(TextKey.ACCOUNT_LOGIN_MICROSOFT_FAILED, error!!),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    url == null -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = text(TextKey.ACCOUNT_LOGIN_MICROSOFT_REQUESTING),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }

                    else -> {
                        Text(
                            text = text(TextKey.ACCOUNT_LOGIN_MICROSOFT_HINT),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))

                        // 链接仍然展示：自动打开可能失败（无桌面环境、没有默认浏览器），
                        // 这时用户还能手动复制。等宽 + 可选中，方便辨认与复制。
                        SelectionContainer {
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        TextButton(
                            onClick = { Browser.open(url) },
                            contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                        ) {
                            Text(text(TextKey.ACCOUNT_LOGIN_MICROSOFT_REOPEN))
                        }

                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(12.dp))
                            Text(
                                text = text(TextKey.ACCOUNT_LOGIN_MICROSOFT_WAITING),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text(TextKey.ACCOUNT_LOGIN_MICROSOFT_CLOSE))
            }
        },
    )
}
