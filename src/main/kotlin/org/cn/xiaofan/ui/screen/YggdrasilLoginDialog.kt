package org.cn.xiaofan.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cn.xiaofan.account.AccountProfile
import org.cn.xiaofan.account.LoginResult
import org.cn.xiaofan.account.YggdrasilLogin
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/**
 * 第三方外置登录对话框（Yggdrasil / authlib-injector 兼容）。
 *
 * <p>三个字段：皮肤站地址、账号、密码。地址留空时用 headlessmc 配置里的默认值。
 *
 * <p>登录成功后会提示 authlib-injector 的必要性 —— 登录本身能成功，但正版验证
 * 的服务器还需要 `-javaagent` 才能进得去。不提示的话用户会以为是 bug。
 */
@Composable
fun YggdrasilLoginDialog(
    onLoggedIn: (AccountProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    // 默认值来自 headlessmc 配置（hmc.auth.yggdrasil.url），
    // 没配置时用 LittleSkin —— 它是国内最常用的外置登录站，也是上游
    // YggdrasilAuthenticator 里写死的默认值，保持一致。
    // 预填而不是做成 placeholder：用户可以直接改，也能一眼看出会连到哪。
    var serverUrl by remember { mutableStateOf(defaultServerUrl()) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // 待执行的登录参数；null 表示没有进行中的登录。
    // 用单独的状态而不是在 onClick 里直接调，是因为登录是挂起操作（含网络），
    // 需要在下面的 LaunchedEffect 里 await。
    var pending by remember { mutableStateOf<PendingLogin?>(null) }

    // 待选的角色列表；非 null 时展示角色选择步骤
    var profileChoices by remember { mutableStateOf<List<String>?>(null) }

    // 真正执行登录
    LaunchedEffect(pending) {
        val job = pending ?: return@LaunchedEffect
        val result = withContext(Dispatchers.IO) {
            if (job.profileIndex == null) {
                YggdrasilLogin.login(job.server, job.username, job.password)
            } else {
                YggdrasilLogin.login(job.server, job.username, job.password, job.profileIndex)
            }
        }
        submitting = false
        pending = null
        when (result) {
            is LoginResult.Success -> onLoggedIn(result.profile)
            is LoginResult.ProfileRequired -> {
                // 账号有多个角色：切到选择步骤，不关闭对话框
                profileChoices = result.profiles
            }
            is LoginResult.Failed -> error = Messages.get(
                TextKey.ACCOUNT_LOGIN_YGGDRASIL_FAILED, result.reason,
            )
        }
    }

    val choices = profileChoices
    if (choices != null) {
        ProfileSelectionDialog(
            profiles = choices,
            onSelect = { index ->
                submitting = true
                profileChoices = null
                // 复用同一份凭据重新登录，这次带上选中的角色序号
                pending = PendingLogin(serverUrl, username, password, profileIndex = index)
            },
            onCancel = {
                profileChoices = null
                onDismiss()
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(text(TextKey.ACCOUNT_LOGIN_YGGDRASIL)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = text(TextKey.ACCOUNT_LOGIN_YGGDRASIL_DESC),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it; error = null },
                    label = { Text(text(TextKey.ACCOUNT_LOGIN_YGGDRASIL_SERVER)) },
                    singleLine = true,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; error = null },
                    label = { Text(text(TextKey.ACCOUNT_LOGIN_YGGDRASIL_USERNAME)) },
                    singleLine = true,
                    enabled = !submitting,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; error = null },
                    label = { Text(text(TextKey.ACCOUNT_LOGIN_YGGDRASIL_PASSWORD)) },
                    singleLine = true,
                    enabled = !submitting,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                if (submitting) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = text(TextKey.ACCOUNT_LOGIN_YGGDRASIL_WAITING),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && username.isNotBlank() && password.isNotEmpty(),
                onClick = {
                    submitting = true
                    pending = PendingLogin(serverUrl, username, password)
                },
            ) {
                Text(text(TextKey.ACCOUNT_LOGIN_YGGDRASIL_CONFIRM))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(text(TextKey.ACCOUNT_ADD_OFFLINE_CANCEL))
            }
        },
    )
}

/**
 * 角色选择步骤。
 *
 * <p>一个皮肤站账号可能有多个角色（skin site 里叫「角色」或「档案」）。
 * 上游 `selectProfile` 让用户输入序号，这里做成列表点击。
 *
 * @param profiles 候选角色名，顺序与序号一致（第 n 个对应 n）
 * @param onSelect 选中回调，参数是**从 1 开始**的序号，与上游约定一致
 * @param onCancel 取消，放弃本次登录
 */
@Composable
private fun ProfileSelectionDialog(
    profiles: List<String>,
    onSelect: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(text(TextKey.ACCOUNT_LOGIN_YGGDRASIL_PROFILE_TITLE)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = text(TextKey.ACCOUNT_LOGIN_YGGDRASIL_PROFILE_DESC),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                profiles.forEachIndexed { i, name ->
                    TextButton(
                        onClick = { onSelect(i + 1) },
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = name,
                            modifier = Modifier.fillMaxWidth(),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancel) {
                Text(text(TextKey.ACCOUNT_ADD_OFFLINE_CANCEL))
            }
        },
    )
}

/** 待执行的登录参数；null 表示没有进行中的登录。 */
private data class PendingLogin(
    val server: String,
    val username: String,
    val password: String,
    /** 角色序号（从 1 开始）；null 表示先不带，让服务端决定。 */
    val profileIndex: Int? = null,
)

/**
 * 外置登录默认的服务器地址。
 *
 * <p>优先读 headlessmc 配置里的 `hmc.auth.yggdrasil.url`，没配就用 LittleSkin。
 * 两者都与上游 `YggdrasilAuthenticator.configuredOrDefaultServer()` 的逻辑一致：
 * 配置优先，缺省 `https://littleskin.cn`。
 */
private fun defaultServerUrl(): String =
    runCatching { YggdrasilLogin.configuredServerUrl() }.getOrNull()
        ?: YggdrasilLogin.DEFAULT_SERVER
