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

package org.cn.xiaofan.config

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.nio.file.Files

/**
 * 代理配置测试。
 *
 * <h2>为什么这些断言重要</h2>
 *
 * 代理配置错了不会报错，只会**静默直连** —— 请求照样成功，但源 IP 是真实的。
 * 微软风控因此冻结账号时，用户只会看到「登录失效」，根本想不到是代理没生效。
 * 所以这里要断言的不只是「解析对了」，还要断言「JVM 真的会用这个代理」。
 */
class ProxyConfigTest {

    private lateinit var tempDir: File
    private var savedSelector: ProxySelector? = null
    private val touchedProperties = listOf(
        "http.proxyHost", "http.proxyPort",
        "https.proxyHost", "https.proxyPort",
        "http.nonProxyHosts", "java.net.useSystemProxies",
    )

    @BeforeEach
    fun setUp() {
        tempDir = Files.createTempDirectory("hmwlx-proxy-test").toFile()
        AppConfig.overrideDirectory = tempDir
        savedSelector = ProxySelector.getDefault()
    }

    @AfterEach
    fun tearDown() {
        AppConfig.overrideDirectory = null
        savedSelector?.let { ProxySelector.setDefault(it) }
        touchedProperties.forEach { System.clearProperty(it) }
        tempDir.deleteRecursively()
    }

    // ---------------------------------------------------------------- 解析

    @Test
    fun `能解析常见代理写法`() {
        assertEquals(
            ProxyConfig.ProxyEntry("127.0.0.1", 7890, "http", ProxyConfig.Source.CONFIG),
            ProxyConfig.parse("http://127.0.0.1:7890"),
        )
        // 省略 scheme：按 http 处理。`http://proxy.corp` 这种写法很常见。
        assertEquals(
            "127.0.0.1",
            ProxyConfig.parse("127.0.0.1:7890")?.host,
        )
        // SOCKS5
        assertEquals(
            ProxyConfig.ProxyEntry("127.0.0.1", 1080, "socks5", ProxyConfig.Source.CONFIG),
            ProxyConfig.parse("socks5://127.0.0.1:1080"),
        )
        // 省略端口时按 scheme 取默认
        assertEquals(8080, ProxyConfig.parse("http://proxy.corp")?.port)
        assertEquals(1080, ProxyConfig.parse("socks5://proxy.corp")?.port)
    }

    @Test
    fun `拒绝带认证信息的代理`() {
        // http://user:pass@host:port 的认证部分 JVM 只能以明文放进系统属性，
        // 而且 http.proxyUser 只对 Basic 认证有效。与其静默忽略认证导致
        // 「设置了却连不上」，不如明确拒绝。
        assertNull(ProxyConfig.parse("http://user:pass@127.0.0.1:7890"))
    }

    @Test
    fun `拒绝非法输入`() {
        assertNull(ProxyConfig.parse(""))
        assertNull(ProxyConfig.parse("   "))
        assertNull(ProxyConfig.parse("http://"))
        assertNull(ProxyConfig.parse(":::"))
    }

    // ---------------------------------------------------------------- 优先级

    @Test
    fun `配置文件优先于环境变量`() {
        AppConfig.setProxyUrl("http://10.0.0.1:8888")

        val entry = ProxyConfig.resolve()
        assertNotNull(entry)
        assertEquals("10.0.0.1", entry!!.host)
        assertEquals(8888, entry.port)
        // 来源必须是 CONFIG 而不是 ENVIRONMENT，即使测试机恰好设了环境变量：
        // 用户既然在界面里填了，说明他明确要这个。
        assertEquals(ProxyConfig.Source.CONFIG, entry.source)
    }

    @Test
    fun `禁用开关会无视环境变量`() {
        AppConfig.setProxyDisabled(true)
        // 这个测试在有 https_proxy 的机器上才有意义，但断言本身在任何环境都成立：
        // 禁用后 resolve() 必须返回 null。
        assertNull(ProxyConfig.resolve())
    }

    @Test
    fun `没设过代理时返回 null 而不是空串`() {
        // 关键区别：null 表示「回落环境变量」，空串表示「直连」。
        // 如果把没设过当直连，环境变量里的代理就永远失效了。
        assertNull(AppConfig.getProxyUrl())
        assertNull(ProxyConfig.resolve().takeIf {
            // 测试机可能真有环境变量，此时不算失败
            System.getenv("https_proxy") == null &&
                System.getenv("HTTPS_PROXY") == null &&
                System.getenv("http_proxy") == null &&
                System.getenv("HTTP_PROXY") == null
        })
    }

    // ---------------------------------------------------------------- 生效

    @Test
    fun `apply 后 JVM 系统属性被设置`() {
        AppConfig.setProxyUrl("http://127.0.0.1:7890")

        val entry = ProxyConfig.apply()
        assertNotNull(entry)

        assertEquals("127.0.0.1", System.getProperty("http.proxyHost"))
        assertEquals("7890", System.getProperty("http.proxyPort"))
        // HTTPS 也必须设：MC 的资源索引与 jar 下载走 HTTPS。
        // 只设 http 会出现「版本列表能拉、jar 下载直连」的诡异现象。
        assertEquals("127.0.0.1", System.getProperty("https.proxyHost"))
        assertEquals("7890", System.getProperty("https.proxyPort"))
    }

    @Test
    fun `apply 后 ProxySelector 真的会选择代理`() {
        AppConfig.setProxyUrl("http://127.0.0.1:7890")
        ProxyConfig.apply()

        val selector = ProxySelector.getDefault()
        val proxies = selector.select(URI.create("https://meta.fabricmc.net/v2/versions/loader/1.21.4"))

        assertEquals(1, proxies.size)
        assertTrue(
            proxies[0].type() == Proxy.Type.HTTP,
            "外部地址应走 HTTP 代理，实际: ${proxies[0]}",
        )
        assertEquals(7890, (proxies[0].address() as java.net.InetSocketAddress).port)
    }

    @Test
    fun `本机与内网地址不走代理`() {
        AppConfig.setProxyUrl("http://127.0.0.1:7890")
        ProxyConfig.apply()

        val selector = ProxySelector.getDefault()
        for (host in listOf(
            "http://localhost:8080/x",
            "http://127.0.0.1:25565",
            "http://192.168.1.10/api",
            "http://10.0.0.5/api",
            "http://172.20.0.1/api",
        )) {
            val proxies = selector.select(URI.create(host))
            assertEquals(
                Proxy.Type.DIRECT,
                proxies[0].type(),
                "$host 应直连，实际: ${proxies[0]}",
            )
        }
    }

    @Test
    fun `清除代理后属性与选择器都回到直连`() {
        AppConfig.setProxyUrl("http://127.0.0.1:7890")
        ProxyConfig.apply()
        assertEquals("127.0.0.1", System.getProperty("http.proxyHost"))

        // 用户关掉代理
        AppConfig.setProxyUrl(null)
        assertNull(ProxyConfig.apply())

        assertNull(System.getProperty("http.proxyHost"), "http.proxyHost 应被清掉")
        assertNull(System.getProperty("https.proxyHost"), "https.proxyHost 应被清掉")

        val proxies = ProxySelector.getDefault()
            .select(URI.create("https://meta.fabricmc.net/x"))
        assertEquals(Proxy.Type.DIRECT, proxies[0].type())
    }
}
