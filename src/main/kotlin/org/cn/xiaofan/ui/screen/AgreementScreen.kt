package org.cn.xiaofan.ui.screen

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import org.cn.xiaofan.legal.UserAgreement

/**
 * 开屏用户协议界面。
 *
 * <p>整屏展示而不是弹窗：协议正文很长，弹窗在小屏幕上滚动体验很差，
 * 而且模态弹窗容易被误点关闭。这里占满整个窗口，把「同意 / 拒绝」做成
 * 明确的两个动作，没有第三种退路。
 *
 * <p>是否需要展示由调用方根据 `AppConfig.isLegalAccepted()` 决定，
 * 本组件只负责展示与回调。
 *
 * @param onAccept 用户点击「同意」，由调用方负责写回配置并进入主界面
 * @param onDecline 用户确认「拒绝并退出」，由调用方负责退出应用
 */
@Composable
fun AgreementScreen(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    var confirmExit by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            AgreementHeader()

            HorizontalDivider()

            // 正文区吃掉剩余高度，单独滚动。
            // weight(1f) 而不是 fillMaxSize()：后者会按父约束的完整高度测量，
            // 总高变成 头部 + 整窗高，底部按钮会被挤出窗口。
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 32.dp, vertical = 16.dp)
            ) {
                AgreementBody(scrollState)
            }

            HorizontalDivider()

            AgreementActions(
                onAccept = onAccept,
                onDecline = { confirmExit = true },
            )
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(text(TextKey.AGREEMENT_DECLINE_CONFIRM_TITLE)) },
            text = { Text(text(TextKey.AGREEMENT_DECLINE_CONFIRM_BODY)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmExit = false
                    onDecline()
                }) {
                    Text(text(TextKey.AGREEMENT_DECLINE_CONFIRM_OK))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) {
                    Text(text(TextKey.AGREEMENT_DECLINE_CONFIRM_CANCEL))
                }
            },
        )
    }
}

@Composable
private fun AgreementHeader() {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 20.dp)) {
        Text(
            text = text(TextKey.AGREEMENT_WELCOME),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = text(TextKey.AGREEMENT_VERSION, UserAgreement.VERSION),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AgreementBody(scrollState: ScrollState) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Text(
            text = UserAgreement.TITLE,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )

        UserAgreement.SECTIONS.forEach { section ->
            Spacer(Modifier.height(20.dp))
            Text(
                text = section.heading,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            section.paragraphs.forEach { paragraph ->
                Text(
                    text = paragraph,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        // 末尾留白，避免最后一行贴着分隔线
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun AgreementActions(
    onAccept: () -> Unit,
    onDecline: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = onDecline) {
            Text(text(TextKey.AGREEMENT_DECLINE))
        }
        Spacer(Modifier.width(12.dp))
        Button(onClick = onAccept) {
            Text(text(TextKey.AGREEMENT_ACCEPT))
        }
    }
}
