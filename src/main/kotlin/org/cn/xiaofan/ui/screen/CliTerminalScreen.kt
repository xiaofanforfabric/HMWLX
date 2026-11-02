/*
 * HMWLX - Hello My World Launcher X
 * Copyright (C) 2026 xiaofan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

package org.cn.xiaofan.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cn.xiaofan.dev.CliTerminalService
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/**
 * 终端字体色：荧光绿。
 *
 * <p>用纯 `#00FF00` 而不是主题里的绿：暗色底上的柔和绿（比如
 * Material 的 secondary）会显得发灰，失去「老式终端」的辨识度。
 */
private val TerminalGreen = Color(0xFF00FF00)

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
        //
        // 经典终端配色：纯黑底 + 荧光绿字。
        // 不用主题色：终端是「老式 CRT」的视觉隐喻，跟着主题走反而失去识别度。
        // 绿色用 #00FF00 而不是柔和色调 —— 暗色底上的柔和绿会显得发灰。
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(Color.Black)
                .border(1.dp, Color(0xFF2A2A2A))
                .padding(8.dp),
        ) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                itemsIndexed(lines) { _, line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        // 等宽字体：CLI 输出里有对齐的表格和日志时间戳，
                        // 比例字体读起来会错位。
                        fontFamily = FontFamily.Monospace,
                        color = TerminalGreen,
                    )
                }
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

        // 输入框也用同一套配色：黑底绿字之间嵌一个白色输入框会很跳。
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            enabled = running,
            singleLine = true,
            placeholder = {
                Text(
                    text = text(TextKey.CLI_TERMINAL_INPUT_HINT),
                    // 占位符压暗一点，避免和真实输入混淆
                    color = TerminalGreen.copy(alpha = 0.45f),
                )
            },
            textStyle = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                color = TerminalGreen,
            ),
            // 光标用绿色：默认光标在纯黑底上几乎看不见
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = Color.Black,
                unfocusedContainerColor = Color.Black,
                disabledContainerColor = Color.Black,
                focusedBorderColor = TerminalGreen.copy(alpha = 0.7f),
                unfocusedBorderColor = Color(0xFF2A2A2A),
                disabledBorderColor = Color(0xFF2A2A2A),
                focusedTextColor = TerminalGreen,
                unfocusedTextColor = TerminalGreen,
                disabledTextColor = TerminalGreen.copy(alpha = 0.4f),
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
                Text(text(TextKey.CLI_TERMINAL_RETRY))
            }
        }
    }
}
