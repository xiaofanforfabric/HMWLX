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

package org.cn.xiaofan.account

import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 设备码捕获的实测。
 *
 * <p>这是整个微软登录方案唯一不确定的地方：设备码是 headlessmc 通过
 * `Console.write("To login visit <url>")` 输出的，我们靠前缀抓取。
 * 一旦 headlessmc 改了这行文本，登录就静默失效。
 *
 * <p>本测试只验证「能拿到链接」，不去完成授权：拿到链接后主动中断，
 * 不让它真的等到超时。
 *
 * <h2>为什么需要联网，以及没有网络时怎么办</h2>
 *
 * <p>申请设备码要向 login.microsoftonline.com 发真实请求，这是这个测试
 * 存在的意义 —— 它验证的是「headlessmc 的真实输出格式」，mock 掉就失去价值。
 *
 * <p>但有些 CI 环境没有外网（比如 CNB 的构建容器：实测 5 次
 * ConnectException）。这种环境里必然拿不到链接，报失败是假阳性，会掩盖
 * 真正的问题。
 *
 * <p>所以先用 TCP 探测能不能连上微软，连不上就 Skip（而非 Fail）。
 * 这样：
 * <ul>
 *   <li>无外网的 CI —— 跳过，不会红</li>
 *   <li>有外网的本地 / GitHub Actions —— 正常跑，能发现格式变更</li>
 * </ul>
 */
class MicrosoftDeviceCodeTest {

    /**
     * 快速判断能不能连到微软。
     *
     * <p>只做 TCP 握手，不发 HTTP。2 秒超时足够：真有网络时 RTT 通常
     * 几十毫秒，2 秒还没连上基本可以判定不通。
     */
    private fun canReachMicrosoft(): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress("login.microsoftonline.com", 443), 2000)
            true
        }
    }.getOrDefault(false)

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `能捕获到微软设备码链接`() {
        Assumptions.assumeTrue(
            canReachMicrosoft(),
            "连不上 login.microsoftonline.com，跳过（无外网的 CI 环境）"
        )

        HeadlessMcBootstrap.start()

        val captured = AtomicReference<String?>(null)

        // 登录会一直阻塞等待授权，这里用线程 + 中断来「拿到链接就撤」
        val thread = Thread {
            runCatching {
                MicrosoftLogin.login { url -> captured.set(url) }
            }
        }
        thread.isDaemon = true
        thread.start()

        // 给足申请设备码的时间（含网络往返）
        val deadline = System.currentTimeMillis() + 60_000
        while (captured.get() == null && System.currentTimeMillis() < deadline && thread.isAlive) {
            Thread.sleep(200)
        }
        thread.interrupt()

        val url = captured.get()
        println("=== 捕获到的设备码链接: $url ===")

        if (url == null) {
            error(
                "没抓到设备码链接。若 headlessmc 改了输出格式，需要同步更新 " +
                    "MicrosoftLogin.DEVICE_CODE_PREFIX"
            )
        }

        // 设备码链接应当指向微软的登录地址
        assert(url.contains("microsoft.com") || url.contains("live.com")) {
            "设备码链接的域名不像微软登录页: $url"
        }
    }
}
