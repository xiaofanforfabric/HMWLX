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

package org.cn.xiaofan.download

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证「网络全断」时 querySupport 的行为。
 *
 * <h2>这个测试要证伪一条评审意见</h2>
 *
 * 鲸鱼娘在 PR #55 上说 `querySupport` 会把 IOException 抛出来、
 * 导致详情弹窗崩溃。理由是：`fetchText` 抛异常 → `async` 失败 →
 * `coroutineScope` 重新抛出 → 没有人接。
 *
 * <p>实际上 `queryOne` 内部有 `try/catch(Exception)` 包住整个查询，
 * 异常会在**进入 `async` 之前**就被折成 `Support.Failed`。所以
 * `async` 永远正常返回，`coroutineScope` 也不会抛。
 *
 * <p>但「读代码觉得没问题」和「实测没问题」是两件事 —— 这条测试
 * 把行为锁住：把所有请求指向一个必然失败的本机端口（未监听的 1 号端口），
 * 断言 `querySupport` 正常返回三个 `Failed`，而不是抛异常。
 */
class ModLoaderNetworkFailureTest {

    @Test
    fun `全部请求失败时返回三个 Failed 而不是抛异常`() = runBlocking {
        // 把 JVM 的代理指向一个没人监听的端口，所有请求都会
        // ConnectException / SocketException。这是模拟「网络全断」最干净的方式。
        val savedHost = System.getProperty("https.proxyHost")
        val savedPort = System.getProperty("https.proxyPort")
        val savedSelector = java.net.ProxySelector.getDefault()

        try {
            System.setProperty("https.proxyHost", "127.0.0.1")
            System.setProperty("https.proxyPort", "1")
            System.setProperty("http.proxyHost", "127.0.0.1")
            System.setProperty("http.proxyPort", "1")
            java.net.ProxySelector.setDefault(
                object : java.net.ProxySelector() {
                    override fun select(uri: java.net.URI) = listOf(
                        java.net.Proxy(
                            java.net.Proxy.Type.HTTP,
                            java.net.InetSocketAddress("127.0.0.1", 1),
                        ),
                    )

                    override fun connectFailed(
                        uri: java.net.URI,
                        sa: java.net.SocketAddress,
                        ioe: java.io.IOException,
                    ) = Unit
                },
            )

            // 关键断言：不抛异常，正常返回。
            val results = ModLoaderService.querySupport("1.21.4")

            assertEquals(
                ModLoaderService.Loader.entries.size,
                results.size,
                "应返回三个条目，实际: $results",
            )

            assertTrue(
                results.all { it is ModLoaderService.Support.Failed },
                "全断网时三个都应转为 Failed（而不是抛异常），实际: $results",
            )

            println("=== 断网模拟结果（应全部 Failed）===")
            results.forEach { println("  $it") }
        } finally {
            // 恢复环境，避免污染同进程内的其它测试
            restore("https.proxyHost", savedHost)
            restore("https.proxyPort", savedPort)
            restore("http.proxyHost", null)
            restore("http.proxyPort", null)
            java.net.ProxySelector.setDefault(savedSelector)
        }
    }

    private fun restore(key: String, value: String?) {
        if (value == null) System.clearProperty(key) else System.setProperty(key, value)
    }
}
