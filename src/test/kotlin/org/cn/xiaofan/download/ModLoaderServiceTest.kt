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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 模组加载器查询测试。
 *
 * <h2>分两类</h2>
 *
 * - **纯本地逻辑**（[neoforgePrefix 换算]、结果条数）—— 必须总是通过，
 *   不依赖外网。
 * - **网络查询** —— CI 不一定有外网，所以只断言「结构正确」，
 *   不断言「Fabric 一定支持某版本」。断网环境下三个都是 `Failed`，
 *   这本身就是正确行为（界面会显示「查询失败，可重试」）。
 */
class ModLoaderServiceTest {

    // ---------------------------------------------------------------- 本地逻辑

    @Test
    fun `NeoForge 版本前缀换算`() {
        // ---- 老方案：三段式 MC 版本 ----
        // 1.20.1 是唯一特例，沿用 Forge 的 47.x 版本号
        assertEquals("47.1.", ModLoaderService.neoforgePrefix("1.20.1"))

        // 1.20.2 起改成 <minor>.<patch>.<build>
        assertEquals("20.2.", ModLoaderService.neoforgePrefix("1.20.2"))
        assertEquals("21.4.", ModLoaderService.neoforgePrefix("1.21.4"))
        assertEquals("21.1.", ModLoaderService.neoforgePrefix("1.21.1"))
        assertEquals("21.11.", ModLoaderService.neoforgePrefix("1.21.11"))

        // ---- 新方案：两段式 MC 版本（Mojang 从 26.1 起改的）----
        //
        // NeoForge 官方 26.1 说明：版本号变成四段，前三段标识 MC 版本，
        // 第四段是构建号。例如 26.1.0.10-beta 对应 MC 26.1。
        // 所以前缀必须带 "0."，否则 "26.1." 会误匹配 26.10.x。
        assertEquals("26.1.0.", ModLoaderService.neoforgePrefix("26.1"))
        assertEquals("26.3.0.", ModLoaderService.neoforgePrefix("26.3"))
        // MC hotfix 版（若存在）：26.1.1 -> 26.1.1.<build>
        assertEquals("26.1.1.", ModLoaderService.neoforgePrefix("26.1.1"))

        // 关键反例：MC 26.10 不能被 26.1 的前缀误匹配。
        // 这正是前缀取三段而不是两段的原因。
        val p2610 = ModLoaderService.neoforgePrefix("26.10")
        assertEquals("26.10.0.", p2610)
        assertTrue(
            !"26.10.0.5".startsWith("26.1.0."),
            "26.10 的构建不应匹配 26.1 的前缀",
        )

        // ---- 不支持的情况 ----
        // NeoForge 从 1.20.1 才开始
        assertNull(ModLoaderService.neoforgePrefix("1.19.4"))
        assertNull(ModLoaderService.neoforgePrefix("1.12.2"))
        assertNull(ModLoaderService.neoforgePrefix("1.20.0"))

        // 远古版 / 快照 / 格式不符
        assertNull(ModLoaderService.neoforgePrefix("a1.2.6"))
        assertNull(ModLoaderService.neoforgePrefix("b1.7.3"))
        assertNull(ModLoaderService.neoforgePrefix("24w14a"))
        assertNull(ModLoaderService.neoforgePrefix("1.21"))
        assertNull(ModLoaderService.neoforgePrefix(""))
    }

    // ---------------------------------------------------------------- 网络查询

    @Test
    fun `查询结果条数始终等于加载器数量`() = runBlocking {
        val results = ModLoaderService.querySupport("1.21.4")

        // 无论网络是否可用都必须三条 —— 缺了说明查询用了短路，
        // 界面会少几个选项且不报错。
        assertEquals(
            ModLoaderService.Loader.entries.size,
            results.size,
            "应返回 ${ModLoaderService.Loader.entries.size} 条，实际: $results",
        )

        // 每条结果的 loader 必须与枚举顺序对应，不能串位。
        results.forEachIndexed { index, support ->
            val expected = ModLoaderService.Loader.entries[index]
            val actual = when (support) {
                is ModLoaderService.Support.Available -> support.loader
                is ModLoaderService.Support.Unsupported -> support.loader
                is ModLoaderService.Support.Failed -> support.loader
            }
            assertEquals(expected, actual, "第 $index 条应为 $expected")
        }

        println("=== 1.21.4 加载器支持情况 ===")
        results.forEach { println("  ${describeSupport(it)}") }
    }

    @Test
    fun `远古版不会返回伪造的构建列表`() = runBlocking {
        // 这个测试防的正是上游 FabricVersionService.getBuilds() 的 bug：
        // 它对任何版本都返回全量加载器版本（实测 a1.2.6 -> 255 个）。
        // 我们绕开它直查官方 API，所以远古版必须得到 Unsupported 或 Failed，
        // 绝不能是 Available。
        val results = ModLoaderService.querySupport("a1.2.6")
        println("=== a1.2.6 加载器支持情况 ===")
        results.forEach { println("  ${describeSupport(it)}") }

        results.forEach { support ->
            assertTrue(
                support !is ModLoaderService.Support.Available,
                "远古版 a1.2.6 不应有任何加载器可用，实际: ${describeSupport(support)}",
            )
        }
    }

    @Test
    fun `旧版本不应被判为查询失败`() = runBlocking {
        // Fabric 对不支持的 MC 版本返回 **HTTP 400 + body `[]`**，不是 404 也不是空 200。
        // 实测：1.13.2 / 1.8.9 / 1.7.10 / 1.0 全部如此。
        //
        // 第一版实现把「非 2xx」一律当失败，结果用户在看 1.0 的详情页时
        // 看到「Fabric 查询失败：HTTP 400」+ 一个永远点不好的重试按钮 ——
        // 而正确语义是「不支持」，置灰即可，不该给重试。
        for (version in listOf("1.0", "1.8.9", "1.12.2")) {
            val results = ModLoaderService.querySupport(version)
            val fabric = results.first { it.loaderOrNull() == ModLoaderService.Loader.FABRIC }
            println("=== $version Fabric: ${describeSupport(fabric)} ===")

            assertTrue(
                fabric !is ModLoaderService.Support.Failed,
                "$version 的 Fabric 不应是 Failed（400+[] 是「不支持」而非失败），" +
                    "实际: ${describeSupport(fabric)}",
            )
            assertTrue(
                fabric is ModLoaderService.Support.Unsupported,
                "$version 的 Fabric 应为 Unsupported，实际: ${describeSupport(fabric)}",
            )
        }
    }

    @Test
    fun `Fabric 查询能区分不支持与网络失败`() = runBlocking {
        // 同一个加载器对「不存在的版本」和「网络断了」必须给出不同结果：
        //   不存在 -> Unsupported（界面置灰，用户不用重试）
        //   网络断 -> Failed（界面提示重试）
        // 混为一谈会让用户对远古版反复点刷新。
        val results = ModLoaderService.querySupport("a1.2.6")
        val fabric = results.first { it.loaderOrNull() == ModLoaderService.Loader.FABRIC }
        println("=== 远古版 Fabric 结果: ${describeSupport(fabric)} ===")

        assertTrue(
            fabric is ModLoaderService.Support.Unsupported ||
                fabric is ModLoaderService.Support.Failed,
            "远古版 Fabric 应是 Unsupported 或 Failed，实际: ${describeSupport(fabric)}",
        )
    }
}

private fun ModLoaderService.Support.loaderOrNull(): ModLoaderService.Loader? = when (this) {
    is ModLoaderService.Support.Available -> loader
    is ModLoaderService.Support.Unsupported -> loader
    is ModLoaderService.Support.Failed -> loader
}

private fun describeSupport(support: ModLoaderService.Support): String = when (support) {
    is ModLoaderService.Support.Available ->
        "${support.loader.displayName}: ${support.builds.size} 个构建，最新 ${support.builds.firstOrNull()}"

    is ModLoaderService.Support.Unsupported ->
        "${support.loader.displayName}: 不支持"

    is ModLoaderService.Support.Failed ->
        "${support.loader.displayName}: 查询失败（${support.reason}）"
}
