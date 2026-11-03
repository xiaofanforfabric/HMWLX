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

import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * 代理服务器配置。
 *
 * <h2>为什么需要这个</h2>
 *
 * **Java 不读 `http_proxy` / `https_proxy` 环境变量。** 实测：
 *
 * ```
 * $ https_proxy=http://127.0.0.1:7890 java ...
 * ProxySelector.getDefault().select(https://...)  ->  [DIRECT]
 * ```
 *
 * JVM 默认的 [ProxySelector] 在 Linux 上完全不看这两个变量
 * （`java.net.useSystemProxies=true` 在 Linux 上也不可靠，
 * 它读的是 GNOME 的 gsettings，不是环境变量）。
 *
 * <p>后果不是「慢一点」，而是**请求从真实 IP 发出**。微软的账号风控
 * 会把「同一账号在短时间内从香港和陕西两个 IP 登录」判为可疑并冻结账号 ——
 * 这就是之前「微软账号密码被改了」那次事件的真实原因，不是被盗。
 *
 * <h2>怎么生效</h2>
 *
 * 两条路，缺一不可：
 *
 * 1. **系统属性**（`http.proxyHost` 等）—— 给所有走
 *    [java.net.HttpURLConnection] 的代码生效，包括 headlessmc 的
 *    下载器、认证请求、`MinecraftAuth`。上游 `NetConfig` 没有代理字段，
 *    只能这么全局设。
 *
 * 2. **自定义 [ProxySelector]** —— 给显式用 `ProxySelector` 的代码生效。
 *    设了系统属性后 JVM 会自动装一个默认 selector，但它对
 *    `https.proxyHost` 的字符串匹配有边界情况（如非标准端口）。
 *    自己实现一份更可控，也便于打印诊断日志。
 *
 * <p><b>必须在 Quarkus 容器启动之前调用 [apply]</b> —— 系统属性里的
 * 代理只在第一次建立连接时读取，之后再改对已缓存的 `HttpClient` 无效。
 *
 * <h2>配置来源的优先级</h2>
 *
 * 1. HMWLX 配置文件里的显式设置（用户在设置页填的）
 * 2. 环境变量 `https_proxy` / `http_proxy` / `HTTPS_PROXY` / `HTTP_PROXY`
 * 3. 都没有 -> 直连
 *
 * 配置文件优先于环境变量：用户既然在界面里填了，说明他明确要这个，
 * 而不是被 shell 里的旧变量影响。
 */
object ProxyConfig {

    /** 配置文件里的键：代理地址，形如 `http://127.0.0.1:7890`。 */
    const val KEY_PROXY_URL = "PROXY_URL"

    /** 配置文件里的键：是否禁用代理（即使环境变量有）。 */
    const val KEY_PROXY_DISABLED = "PROXY_DISABLED"

    /** 需要绕过代理的主机（本机、内网）。 */
    private const val NON_PROXY_HOSTS = "localhost|127.*|0.0.0.0|::1|[::1]|10.*|192.168.*|172.16.*|172.17.*|172.18.*|172.19.*|172.2[0-9].*|172.3[01].*"

    /** 解析出的最终代理；null 表示直连。 */
    @Volatile
    private var active: ProxyEntry? = null

    /** 一次解析的结果。 */
    data class ProxyEntry(
        val host: String,
        val port: Int,
        val scheme: String,
        /** 配置来源，用于设置页展示与日志。 */
        val source: Source,
    ) {
        /** 供显示用，隐藏可能的认证信息。 */
        val display: String get() = "$scheme://$host:$port"

        override fun toString(): String = "$display（$source）"
    }

    /** 代理来源。 */
    enum class Source {
        /** 配置文件里的显式设置。 */
        CONFIG,

        /** 环境变量。 */
        ENVIRONMENT,

        /** 直连。 */
        DIRECT,
    }

    /**
     * 解析并应用代理配置。
     *
     * <p>应在 Quarkus 容器启动**之前**调用，见类注释。
     *
     * @return 实际生效的代理；null 表示直连
     */
    fun apply(): ProxyEntry? {
        val entry = resolve()
        active = entry

        if (entry == null) {
            clearSystemProperties()
            ProxySelector.setDefault(DirectSelector)
            return null
        }

        // HTTP 与 HTTPS 都设：Minecraft 的资源索引、jar 下载走 HTTPS，
        // 但部分镜像（以及某些认证回调）仍是 HTTP。
        // 只设一半会出现「版本列表能拉，jar 下载直连」这种诡异现象。
        System.setProperty("http.proxyHost", entry.host)
        System.setProperty("http.proxyPort", entry.port.toString())
        System.setProperty("https.proxyHost", entry.host)
        System.setProperty("https.proxyPort", entry.port.toString())
        System.setProperty("http.nonProxyHosts", NON_PROXY_HOSTS)

        // 显式关掉 system proxies：如果不关，JVM 可能用平台机制覆盖我们设的值
        // （Windows 上会读注册表，Linux 上读 gsettings）。
        System.setProperty("java.net.useSystemProxies", "false")

        ProxySelector.setDefault(FixedSelector(entry))

        return entry
    }

    /** 当前生效的代理；null 表示直连。 */
    fun current(): ProxyEntry? = active

    /** 只解析不应用，供设置页预览用。 */
    fun resolve(): ProxyEntry? {
        // 1. 用户显式禁用
        if (AppConfig.isProxyDisabled()) return null

        // 2. 配置文件
        AppConfig.getProxyUrl()?.let { parsed ->
            parse(parsed)?.let { return it.copy(source = Source.CONFIG) }
        }

        // 3. 环境变量。大小写都试 —— 不同 shell / CI 习惯不同。
        for (name in listOf("https_proxy", "HTTPS_PROXY", "http_proxy", "HTTP_PROXY")) {
            val raw = System.getenv(name)?.trim()
            if (raw.isNullOrBlank()) continue
            parse(raw)?.let { return it.copy(source = Source.ENVIRONMENT) }
        }

        return null
    }

    /**
     * 解析代理 URL。
     *
     * <p>支持的形式：
     * - `http://127.0.0.1:7890`
     * - `127.0.0.1:7890`（省略 scheme，按 http 处理）
     * - `socks5://127.0.0.1:1080`
     *
     * <p>**不支持**带认证的 `http://user:pass@host:port` —— JVM 的
     * `http.proxyUser` / `http.proxyPassword` 只对 Basic 认证有效，
     * 而且会以明文出现在 `System.getProperties()` 里。需要认证代理的用户
     * 应改用系统级代理或本地转发端口。这里显式拒绝，而不是静默忽略认证部分
     * （后者会让用户以为设置了但实际连不上）。
     */
    internal fun parse(raw: String): ProxyEntry? {
        val text = raw.trim()
        if (text.isEmpty()) return null

        val withScheme = if ("://" in text) text else "http://$text"

        val uri = runCatching { URI(withScheme) }.getOrNull() ?: return null
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return null

        if (uri.userInfo != null) return null

        val scheme = uri.scheme?.lowercase() ?: "http"
        if (scheme !in setOf("http", "https", "socks", "socks5")) return null

        val port = when {
            uri.port > 0 -> uri.port
            // 省略端口时按 scheme 给默认值，而不是直接判失败 ——
            // `http://proxy.corp` 这种写法很常见。
            scheme.startsWith("socks") -> 1080
            scheme == "https" -> 443
            else -> 8080
        }
        if (port !in 1..65535) return null

        return ProxyEntry(host = host, port = port, scheme = scheme, source = Source.CONFIG)
    }

    private fun clearSystemProperties() {
        // 清干净：如果上一次运行是走代理，残留在 -D 里的值会让本次仍走代理。
        // （同一次进程里 apply 多次的场景：用户在设置页关掉代理后立即生效。）
        for (key in listOf(
            "http.proxyHost", "http.proxyPort",
            "https.proxyHost", "https.proxyPort",
            "http.nonProxyHosts",
        )) {
            System.clearProperty(key)
        }
    }

    /**
     * 固定代理选择器。
     *
     * <p>不走 `java.net.ProxySelector.getDefault()` 的字符串匹配，
     * 因为它在处理 `http.nonProxyHosts` 的通配符时行为古怪
     * （`127.*` 在某些 JDK 版本上不生效）。
     */
    private class FixedSelector(private val entry: ProxyEntry) : ProxySelector() {
        private val proxy: Proxy = when {
            entry.scheme.startsWith("socks") ->
                Proxy(Proxy.Type.SOCKS, InetSocketAddress(entry.host, entry.port))

            else -> Proxy(Proxy.Type.HTTP, InetSocketAddress(entry.host, entry.port))
        }

        override fun select(uri: URI): List<Proxy> {
            val host = uri.host.orEmpty()
            return if (isLocal(host)) listOf(Proxy.NO_PROXY) else listOf(proxy)
        }

        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) {
            // 由具体请求方处理，这里无事可做。
        }
    }

    /** 直连选择器（未配置代理时安装，覆盖 JVM 默认行为）。 */
    private object DirectSelector : ProxySelector() {
        override fun select(uri: URI): List<Proxy> = listOf(Proxy.NO_PROXY)
        override fun connectFailed(uri: URI, sa: SocketAddress, ioe: java.io.IOException) = Unit
    }

    /** 本机 / 内网地址不走代理。 */
    private fun isLocal(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        if (host == "::1" || host == "[::1]" || host == "0.0.0.0") return true
        val ip = host.removePrefix("[")
        return ip.startsWith("127.") ||
            ip.startsWith("10.") ||
            ip.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2[0-9]|3[01])\\.").containsMatchIn(ip)
    }
}
