package org.cn.xiaofan

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.ui.HmwLxApp

/**
 * HMWLX 桌面端入口。
 *
 * <p>Quarkus 容器在 [HmwLxApp] 的 LaunchedEffect 里于后台线程启动，
 * 窗口可以先显示出来，用户看到的是加载状态而不是空白。
 */
fun main() = application {
    val windowState = rememberWindowState(size = DpSize(1000.dp, 680.dp))

    Window(
        onCloseRequest = {
            HeadlessMcBootstrap.shutdown()
            exitApplication()
        },
        state = windowState,
        title = Messages.get(TextKey.WINDOW_TITLE)
    ) {
        HmwLxApp()
    }
}
