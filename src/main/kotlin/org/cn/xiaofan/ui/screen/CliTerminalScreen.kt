package org.cn.xiaofan.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cn.xiaofan.dev.CliTerminalService
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/**
 * 嘉豪终端。
 *
 * <p>把上游 headlessmc 的 CLI 原样搬进界面：输出滚动区 + 输入框。
 *
 * <h2>为什么输出用本地列表而不是直接读 flow</h2>
 *
 * [CliTerminalService.output] 是 `SharedFlow`，它**没有重放**：
 * 用户在终端里跑了几条命令再切去别的页面、切回来，订阅重建时
 * 拿到的是空流，之前的输出全没了。所以这里用 `mutableStateListOf`
 * 自己累积，并且只在首次进入时订阅。
 *
 * <p>列表本身放在页面里意味着切走再回来也会重建 —— 这是已知限制，
 * 但终端进程还在（状态在 service 里），只是历史输出丢了。
 * 如果以后要保留历史，把列表提到 service 里即可。
 */
@Composable
fun CliTerminalScreen(
    debugMode: Boolean,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var startError by remember { mutableStateOf<String?>(null) }

    // 订阅输出。key 用 Unit：这个页面在组合里是稳定的，
    // 只有进入/离开才会重建。
    LaunchedEffect(Unit) {
        CliTerminalService.output.collect { line ->
            lines.add(line)
            // 自动滚到底。terminal 的规矩：新输出必须在视野里。
            listState.animateScrollToItem((lines.size - 1).coerceAtLeast(0))
        }
    }

    // 首次进入自动启动。若已经在跑（用户切走去别的页面又回来），
    // start() 内部会直接返回 null，不会重复起进程。
    LaunchedEffect(Unit) {
        startError = CliTerminalService.start(debugMode)
        running = CliTerminalService.state.value == CliTerminalService.State.RUNNING
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // ---- 顶栏 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = text(TextKey.CLI_TERMINAL_TITLE),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { lines.clear() }) {
                Text(text(TextKey.CLI_TERMINAL_CLEAR))
            }
            OutlinedButton(
                onClick = {
                    CliTerminalService.stop()
                    running = false
                },
                enabled = running,
            ) {
                Text(text(TextKey.CLI_TERMINAL_STOP))
            }
            OutlinedButton(onClick = onBack) {
                Text(text(TextKey.CLI_TERMINAL_BACK))
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ---- 输出区 ----
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            itemsIndexed(lines) { _, line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    // 等宽字体：CLI 输出里有对齐的表格和日志时间戳，
                    // 比例字体读起来会错位。
                    fontFamily = FontFamily.Monospace,
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        // ---- 输入区 ----
        if (startError != null) {
            Text(
                text = startError!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = running,
            singleLine = true,
            placeholder = { Text(text(TextKey.CLI_TERMINAL_INPUT_HINT)) },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(
                onSend = {
                    if (input.isNotBlank()) {
                        CliTerminalService.send(input)
                        input = ""
                    }
                },
            ),
        )

        // 未能自动启动时给一个手动重试入口：
        // 用户可能因为网络/权限原因第一次失败，修好后不该被迫退出重进。
        if (!running && startError != null) {
            TextButton(
                onClick = {
                    scope.launch {
                        startError = CliTerminalService.start(debugMode)
                        running =
                            CliTerminalService.state.value == CliTerminalService.State.RUNNING
                    }
                },
            ) {
                Text(text(TextKey.SETTINGS_CLI_ENTER))
            }
        }
    }
}
