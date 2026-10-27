package org.cn.xiaofan.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import org.cn.xiaofan.account.AccountProfile
import org.cn.xiaofan.account.AccountResolver
import org.cn.xiaofan.account.sourceLabel
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import org.cn.xiaofan.ui.component.AccountBar
import org.cn.xiaofan.ui.screen.AccountSwitchScreen

/**
 * 启动状态。UI 只依赖这个 sealed 类型，不直接碰 Quarkus。
 */
sealed interface BootState {
    /** 正在启动 Quarkus 容器。 */
    data object Loading : BootState

    /**
     * 容器已就绪。
     *
     * @param account 当前登录账号（含头像来源），未登录时为默认离线账号
     * @param detail 从 headlessmc 取到的桥接信息，用于验证 CDI 链路
     */
    data class Ready(val account: AccountProfile, val detail: String) : BootState

    /** 启动失败。 */
    data class Failed(val message: String, val cause: Throwable?) : BootState
}

/**
 * 应用主界面。
 *
 * 布局：顶部固定账号栏，下方是内容区。
 * 账号栏在容器就绪后才显示 —— 因为账号信息要通过 CDI 从 headlessmc 读取。
 */
@Composable
fun HmwLxApp() {
    var state by remember { mutableStateOf<BootState>(BootState.Loading) }
    // 是否正在展示账号切换界面。账号切换是覆盖整个内容区的独立页面，
    // 而不是弹窗：它的列表可能很长，弹窗放不下。
    var showAccountSwitch by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        state = bootstrap()
    }

    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                when (val current = state) {
                    is BootState.Loading -> ContentArea {
                        LoadingScreen()
                    }
                    is BootState.Ready -> {
                        if (showAccountSwitch) {
                            AccountSwitchScreen(
                                current = current.account,
                                onSwitched = { profile ->
                                    // 切换成功后立刻回到主界面，并用新账号刷新状态。
                                    // 不重新启动容器：账号只是 headlessmc 里的一个字段，
                                    // 容器本身不需要重启。
                                    state = current.copy(account = profile)
                                    showAccountSwitch = false
                                },
                                onClose = { showAccountSwitch = false },
                            )
                        } else {
                            AccountBar(
                                profile = current.account,
                                onOpenSwitch = { showAccountSwitch = true },
                            )
                            // weight(1f) 让内容区吃掉账号栏之外的**剩余**高度。
                            // 这里不能用 fillMaxSize()：Column 内的 fillMaxSize 会按父约束的
                            // 完整高度测量，总高变成 账号栏 + 整窗高，超出部分被裁掉，
                            // 居中的内容会偏到看不见的位置。
                            ContentArea {
                                ReadyScreen(current.detail)
                            }
                        }
                    }
                    is BootState.Failed -> ContentArea {
                        FailedScreen(current)
                    }
                }
            }
        }
    }
}

/**
 * 内容区：占满 [Column] 里的剩余高度，内容居中。
 *
 * 抽出来是因为三个分支都要同一份约束逻辑，避免复制粘贴时再次写错成 `fillMaxSize()`。
 */
@Composable
private fun ColumnScope.ContentArea(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        content()
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
        println("[HMWLX] 正在启动 headlessmc 容器…")
        HeadlessMcBootstrap.start()
        println("[HMWLX] 容器已就绪，状态=${HeadlessMcBootstrap.currentState()}")

        val account = AccountResolver.current()
        println("[HMWLX] 当前账号: ${account.name} (${account.sourceLabel()}) uuid=${account.uuid}")
        println("[HMWLX] 头像来源: ${account.avatar ?: "<无>"}")

        val detail = readHeadlessMcInfo()
        println("[HMWLX] 桥接信息:\n$detail")
        BootState.Ready(account = account, detail = detail)
    } catch (t: Throwable) {
        println("[HMWLX] 容器启动失败: ${t.message}")
        t.printStackTrace()
        BootState.Failed(t.message ?: t.javaClass.simpleName, t)
    }
}

/**
 * 从 headlessmc 取点真实信息，证明 CDI bridge 是通的。
 */
private fun readHeadlessMcInfo(): String {
    val parts = mutableListOf<String>()
    parts += Messages.get(TextKey.READY_CONTAINER_STATE, HeadlessMcBootstrap.currentState())

    // CommandLine 是 headlessmc 的核心 bean，能取到就说明 CDI 正常
    val commandLine = runCatching {
        HeadlessMcBootstrap.bean(picocli.CommandLine::class.java)
    }.getOrNull()

    if (commandLine != null) {
        parts += Messages.get(TextKey.READY_ROOT_COMMAND, commandLine.commandSpec.name())
        parts += Messages.get(TextKey.READY_SUBCOMMAND_COUNT, commandLine.subcommands.size)
    } else {
        parts += Messages.get(TextKey.READY_ROOT_COMMAND_MISSING)
    }

    return parts.joinToString("\n")
}

@Composable
private fun LoadingScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(text(TextKey.BOOT_LOADING), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun ReadyScreen(detail: String) {
    Column(
        modifier = Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text(TextKey.APP_NAME),
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = text(TextKey.APP_SUBTITLE),
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
            text = text(TextKey.BOOT_FAILED_TITLE),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(12.dp))
        Text(text = failed.message, style = MaterialTheme.typography.bodyMedium)
    }
}
