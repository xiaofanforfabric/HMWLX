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
 * @param onLoggedIn 登录成功，携带新账号
 * @param onDismiss 关闭对话框（不影响已经发起的登录，只是不再展示结果）
 */
@Composable
fun MicrosoftLoginDialog(
    onLoggedIn: (AccountProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    // null 表示还在申请设备码
    var verificationUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var done by remember { mutableStateOf(false) }
    // 浏览器是否已经自动拉起过，避免重组时重复打开
    var browserOpened by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 整个登录流程是阻塞的，必须离开 UI 线程
        val result = withContext(Dispatchers.IO) {
            MicrosoftLogin.login { url ->
                // 回调在 IO 线程上触发，交给 Compose 的状态更新即可
                verificationUrl = url
            }
        }
        done = true
        when (result) {
            is LoginResult.Success -> onLoggedIn(result.profile)
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

    AlertDialog(
        onDismissRequest = { if (done) onDismiss() },
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
