package org.cn.xiaofan

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.cn.xiaofan.config.AppConfig
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.ui.HmwLxApp
import org.cn.xiaofan.ui.screen.AgreementScreen
import kotlin.system.exitProcess

/**
 * HMWLX 桌面端入口。
 *
 * <p>启动流程：
 * 1. 检查 `AppConfig.isLegalAccepted()`，未同意时先展示用户协议
 * 2. 同意后写回配置，进入主界面
 * 3. 拒绝则直接退出进程
 *
 * <p>协议展示发生在 Quarkus 容器启动之前——用户还没同意协议时，
 * 不应该已经跑起来任何与 Minecraft 相关的东西。
 *
 * <p>Quarkus 容器在 [HmwLxApp] 的 LaunchedEffect 里于后台线程启动，
 * 窗口可以先显示出来，用户看到的是加载状态而不是空白。
 */
fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1000.dp, 680.dp))

    // 读一次即可：整个过程只在这个进程里生效，同意后直接改内存状态。
    // 配置读取失败（文件损坏、无权限）时按「未同意」处理，倾向于弹窗。
    val agreedAtStartup = remember { AppConfig.isLegalAccepted() }
    val showAgreement = remember { mutableStateOf(!agreedAtStartup) }
    val showDeclineConfirm = remember { mutableStateOf(false) }

    Window(
        onCloseRequest = {
            HeadlessMcBootstrap.shutdown()
            // 协议页点 X 等同于「不同意并退出」，同样走二次确认，
            // 不然右上角就成了绕过协议的后门。主界面里 X 保持原样，直接退出。
            if (showAgreement.value) showDeclineConfirm.value = true else exitApplication()
        },
        state = windowState,
        title = Messages.get(TextKey.WINDOW_TITLE)
    ) {
        if (showAgreement.value) {
            AgreementScreen(
                onAccept = {
                    if (!AppConfig.setLegalAccepted(true)) {
                        // 写不进去也放行：下次启动再弹一遍，不该让人用不了软件。
                        System.err.println("用户协议同意状态写入失败，配置路径: ${AppConfig.configPath()}")
                    }
                    showAgreement.value = false
                },
                showDeclineConfirm = showDeclineConfirm.value,
                onDeclineRequest = { showDeclineConfirm.value = true },
                onDeclineDismiss = { showDeclineConfirm.value = false },
                onDecline = {
                    showDeclineConfirm.value = false
                    HeadlessMcBootstrap.shutdown()
                    // 用 exitProcess 而不是 exitApplication：拒绝协议属于主动终止，
                    // 需要立刻结束进程，避免 Compose 的窗口销毁流程拖出多余的动画与日志。
                    exitProcess(0)
                },
            )
        } else {
            HmwLxApp()
        }
    }
}
