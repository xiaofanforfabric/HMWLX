package org.cn.xiaofan.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap

/**
 * 启动状态。UI 只依赖这个 sealed 类型，不直接碰 Quarkus。
 */
sealed interface BootState {
    /** 正在启动 Quarkus 容器。 */
    data object Loading : BootState

    /** 容器已就绪。[detail] 是从 headlessmc 取到的信息，用于证明 bridge 真的通了。 */
    data class Ready(val detail: String) : BootState

    /** 启动失败。 */
    data class Failed(val message: String, val cause: Throwable?) : BootState
}

/**
 * 应用主界面。
 */
@Composable
fun HmyLxApp() {
    var state by remember { mutableStateOf<BootState>(BootState.Loading) }

    LaunchedEffect(Unit) {
        state = bootstrap()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                when (val current = state) {
                    is BootState.Loading -> LoadingScreen()
                    is BootState.Ready -> ReadyScreen(current.detail)
                    is BootState.Failed -> FailedScreen(current)
                }
            }
        }
    }
}

/**
 * 在后台线程启动 headlessmc 容器，并尝试取一个 bean 验证可用性。
 *
 * 失败时除了放进 [BootState.Failed] 供界面显示，还打印到控制台 ——
 * 否则窗口一闪而过时，用户完全看不到原因。
 */
private suspend fun bootstrap(): BootState = withContext(Dispatchers.IO) {
    try {
        println("[HMYLX] 正在启动 headlessmc 容器…")
        HeadlessMcBootstrap.start()
        println("[HMYLX] 容器已就绪，状态=${HeadlessMcBootstrap.currentState()}")
        val detail = readHeadlessMcInfo()
        println("[HMYLX] 桥接信息:\n$detail")
        BootState.Ready(detail)
    } catch (t: Throwable) {
        println("[HMYLX] 容器启动失败: ${t.message}")
        t.printStackTrace()
        BootState.Failed(t.message ?: t.javaClass.simpleName, t)
    }
}

/**
 * 从 headlessmc 取点真实信息，证明 CDI bridge 是通的。
 */
private fun readHeadlessMcInfo(): String {
    val parts = mutableListOf<String>()
    parts += "容器状态: ${HeadlessMcBootstrap.currentState()}"

    // CommandLine 是 headlessmc 的核心 bean，能取到就说明 CDI 正常
    val commandLine = runCatching {
        HeadlessMcBootstrap.bean(picocli.CommandLine::class.java)
    }.getOrNull()

    if (commandLine != null) {
        parts += "根命令: ${commandLine.commandSpec.name()}"
        parts += "子命令数: ${commandLine.subcommands.size}"
    } else {
        parts += "根命令: <未取到>"
    }

    return parts.joinToString("\n")
}

@Composable
private fun LoadingScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text("正在启动 headlessmc 容器…", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ReadyScreen(detail: String) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "HMYLX",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = "hello my world launcher X",
            style = MaterialTheme.typography.titleSmall
        )
        Spacer(Modifier.height(24.dp))
        Text(text = detail, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedScreen(failed: BootState.Failed) {
    Column(
        modifier = Modifier.padding(32.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "启动失败",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(12.dp))
        Text(text = failed.message, style = MaterialTheme.typography.bodyMedium)
    }
}
