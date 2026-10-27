package org.cn.xiaofan.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import org.cn.xiaofan.account.AccountProfile
import org.cn.xiaofan.account.AccountService
import org.cn.xiaofan.account.OfflineAccountResult
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/**
 * 添加离线账号的对话框。
 *
 * <p>离线账号不需要任何外部交互，填个名字就能创建，所以做成对话框而不是独立页面。
 *
 * <p>校验策略：输入时实时提示，但**不阻断输入**——让用户可以继续打字，
 * 而不是每按一个键就被弹回来的错误信息打断。点「确定」时才真正拒绝。
 *
 * @param onCreated 创建成功，携带新账号
 * @param onDismiss 取消
 */
@Composable
fun AddOfflineAccountDialog(
    onCreated: (AccountProfile) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }

    // 打开即聚焦输入框，省掉一次点击
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    /** 当前输入是否明显不合法，仅用于给输入框标红 + 提示文字。 */
    val liveHint: String? = when {
        name.isEmpty() -> null
        name.length > 16 -> Messages.get(TextKey.ACCOUNT_ADD_OFFLINE_TOO_LONG)
        !NAME_PATTERN.matches(name) -> Messages.get(TextKey.ACCOUNT_ADD_OFFLINE_INVALID_CHARS)
        name.length < 3 -> Messages.get(TextKey.ACCOUNT_ADD_OFFLINE_TOO_SHORT)
        else -> null
    }

    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(text(TextKey.ACCOUNT_ADD_OFFLINE_TITLE)) },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = text(TextKey.ACCOUNT_ADD_OFFLINE_DESC),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        // 硬性上限 16 字符：再多也存不进 headlessmc，直接挡住比让用户白打强
                        if (it.length <= 16) {
                            name = it
                            error = null
                        }
                    },
                    label = { Text(text(TextKey.ACCOUNT_ADD_OFFLINE_LABEL)) },
                    singleLine = true,
                    isError = liveHint != null,
                    supportingText = liveHint?.let { hint -> { Text(hint) } },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                    enabled = !submitting,
                )

                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !submitting && name.isNotBlank(),
                onClick = {
                    submitting = true
                    when (val result = AccountService.addOfflineAccount(name)) {
                        is OfflineAccountResult.Success -> onCreated(result.profile)
                        is OfflineAccountResult.InvalidName -> {
                            error = result.reason
                            submitting = false
                        }
                        is OfflineAccountResult.DuplicateName ->
                            error = Messages.get(TextKey.ACCOUNT_ADD_OFFLINE_DUPLICATE, result.name)
                        is OfflineAccountResult.Failed ->
                            error = Messages.get(TextKey.ACCOUNT_ADD_OFFLINE_FAILED, result.reason)
                    }
                },
            ) {
                Text(text(TextKey.ACCOUNT_ADD_OFFLINE_CONFIRM))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !submitting) {
                Text(text(TextKey.ACCOUNT_ADD_OFFLINE_CANCEL))
            }
        },
    )
}

/** 与 AccountService 里的规则保持一致：仅字母、数字、下划线。 */
private val NAME_PATTERN = Regex("^[A-Za-z0-9_]+$")
