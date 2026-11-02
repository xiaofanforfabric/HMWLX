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

package org.cn.xiaofan.util

import java.awt.Desktop
import java.net.URI

/**
 * 用系统默认浏览器打开链接。
 *
 * <p>不用裸的 `Desktop.browse`：它在 Linux 上经常抛
 * `UnsupportedOperationException`（取决于是否装了桌面环境、是否注册了
 * `BROWSE` 动作），在无桌面环境的最小化容器里更是必然失败。
 *
 * <p>策略是先试 `Desktop`，不行再按平台调命令行：
 * - Windows：`rundll32 url.dll,FileProtocolHandler`
 * - macOS：`open`
 * - Linux/其他：`xdg-open`
 *
 * <p>全部失败时返回 false，由调用方决定怎么提示 —— 界面本身仍然展示了链接，
 * 用户可以手动复制。
 */
object Browser {

    /**
     * 尝试打开 [url]。
     *
     * @return 是否成功发起打开动作。注意：返回 true 只表示命令执行成功，
     *         不代表浏览器真的显示出来了。
     */
    fun open(url: String): Boolean {
        if (openWithDesktop(url)) return true
        return openWithCommand(url)
    }

    private fun openWithDesktop(url: String): Boolean = runCatching {
        if (!Desktop.isDesktopSupported()) return false
        val desktop = Desktop.getDesktop()
        if (!desktop.isSupported(Desktop.Action.BROWSE)) return false
        desktop.browse(URI(url))
        true
    }.getOrDefault(false)

    private fun openWithCommand(url: String): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val command = when {
            os.contains("win") -> listOf("rundll32", "url.dll,FileProtocolHandler", url)
            os.contains("mac") -> listOf("open", url)
            else -> listOf("xdg-open", url)
        }

        return runCatching {
            val process = ProcessBuilder(command)
                // 丢掉子进程的输出：xdg-open 在缺少桌面环境时会往 stderr 喷一堆
                // 无用的信息，混进我们自己的日志里反而干扰排查
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            // 不等它结束：浏览器可能是常驻进程，等下去会卡住调用线程。
            // 只做一个很短的等待，确认命令没有立刻失败（如 xdg-open 不存在）。
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            true
        }.getOrDefault(false)
    }
}
