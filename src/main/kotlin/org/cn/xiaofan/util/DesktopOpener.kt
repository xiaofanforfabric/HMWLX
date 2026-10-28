package org.cn.xiaofan.util

import java.awt.Desktop
import java.io.File

/**
 * 用系统默认程序打开目录或文件。
 *
 * <p>和 [Browser] 是分开的两件事：`Desktop.open` 走的是 OPEN 动作，
 * `Desktop.browse` 走 BROWSE，Linux 上两者的可用性并不一致；
 * 参数也不一样（一个是 File，一个是 URI）。合并会得到一个
 * 「取决于传什么就干什么」的方法，调用点反而看不出行为。
 *
 * <p>策略与 [Browser] 对齐：先试 `Desktop`，不行再按平台调命令行。
 */
object DesktopOpener {

    /**
     * 打开 [path]。
     *
     * @return 是否成功发起打开动作。false 表示两种方式都失败了 ——
     *         调用方可以静默，也可以提示，但**不要**因此阻断主流程。
     */
    fun open(path: String): Boolean {
        val file = File(path)
        if (!file.exists()) return false
        return openWithDesktop(file) || openWithCommand(file)
    }

    private fun openWithDesktop(file: File): Boolean = runCatching {
        if (!Desktop.isDesktopSupported()) return false
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.OPEN)) return false
        desktop.open(file)
        true
    }.getOrDefault(false)

    private fun openWithCommand(file: File): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            os.contains("win") -> listOf("explorer", file.absolutePath)
            os.contains("mac") -> listOf("open", file.absolutePath)
            else -> listOf("xdg-open", file.absolutePath)
        }
        return runCatching {
            ProcessBuilder(command)
                // 丢掉子进程输出：xdg-open 在无桌面环境时会往 stderr 喷一堆信息，
                // 混进我们自己的日志里只会干扰排查。
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.getOrDefault(false)
    }
}
