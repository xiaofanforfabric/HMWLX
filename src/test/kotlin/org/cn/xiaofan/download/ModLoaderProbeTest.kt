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
import org.junit.jupiter.api.Test

/**
 * 真实网络探测：打印各版本的实际查询结果。
 *
 * <p>**不做断言** —— 用于人工排查「为什么界面说某个加载器不支持」。
 * 断言版测试在 [ModLoaderServiceTest] 里，那些只验证可在任何环境复现的逻辑。
 *
 * <p>运行（此机需要走代理才能连 maven.neoforged.net）：
 * ```
 * JAVA_TOOL_OPTIONS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890" \
 *   ./gradlew test --tests '*ModLoaderProbeTest*' -i
 * ```
 */
class ModLoaderProbeTest {

    @Test
    fun `打印真实查询结果`() = runBlocking {
        println("########## ModLoader 真实查询探测 ##########")
        println("https.proxyHost=${System.getProperty("https.proxyHost")}")
        println("https.proxyPort=${System.getProperty("https.proxyPort")}")

        for (version in listOf("1.21.7", "1.21.6", "1.21.4", "1.20.1", "26.3")) {
            println("========== MC $version ==========")
            println("  neoforgePrefix = ${ModLoaderService.neoforgePrefix(version)}")

            val results = ModLoaderService.querySupport(version)
            results.forEach { s ->
                println("  " + when (s) {
                    is ModLoaderService.Support.Available ->
                        "${s.loader.displayName}: ${s.builds.size} 个, 最新=${s.builds.firstOrNull()}"
                    is ModLoaderService.Support.Unsupported ->
                        "${s.loader.displayName}: Unsupported"
                    is ModLoaderService.Support.Failed ->
                        "${s.loader.displayName}: Failed - ${s.reason}"
                })
            }
        }
        println("########## 探测结束 ##########")
    }
}
