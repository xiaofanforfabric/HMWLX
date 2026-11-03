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

package org.cn.xiaofan.ui.component

import org.cn.xiaofan.download.ModLoaderService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * 加载器图标识别测试。
 *
 * <p>判据是从版本目录名里扫关键字，所以这里的关键是**不能认错** ——
 * 认错的结果是列表里给用户看了一个错误的品牌 LOGO，比不显示更糟。
 */
class VersionIconsTest {

    @Test
    fun `认得各加载器的实际版本目录名`() {
        // Fabric 的实际命名
        assertEquals(
            VersionIcons.FABRIC,
            VersionIcons.loaderIconFor("fabric-loader-0.16.9-1.21.4"),
        )
        assertEquals(
            VersionIcons.FABRIC,
            VersionIcons.loaderIconFor("fabric-loader-0.15.11-1.20.1"),
        )

        // Forge 的实际命名（带不带连字符都有）
        assertEquals(
            VersionIcons.FORGE,
            VersionIcons.loaderIconFor("1.21.4-forge-54.0.16"),
        )
        assertEquals(
            VersionIcons.FORGE,
            VersionIcons.loaderIconFor("1.20.1-forge-47.2.0"),
        )

        // NeoForge
        assertEquals(
            VersionIcons.NEOFORGE,
            VersionIcons.loaderIconFor("1.21.4-neoforge-21.4.161"),
        )
        assertEquals(
            VersionIcons.NEOFORGE,
            VersionIcons.loaderIconFor("neoforge-21.1.113"),
        )
    }

    @Test
    fun `NeoForge 不会被误认成 Forge`() {
        // 这是最容易写错的一处："neoforge" 里包含 "forge"。
        // 如果先判 forge，所有 NeoForge 版本都会显示成 Forge 的铁砧图标。
        assertEquals(
            VersionIcons.NEOFORGE,
            VersionIcons.loaderIconFor("neoforge-21.4.161"),
        )
        assertEquals(
            VersionIcons.NEOFORGE,
            VersionIcons.loaderIconFor("1.21.4-NeoForge-21.4.161"),
            "大小写混合也要认出来",
        )
    }

    @Test
    fun `原版版本不认成加载器`() {
        // 原版版本号里不该命中任何关键字，回落到类型图标。
        for (id in listOf(
            "1.21.4",
            "1.20.1",
            "26.3",
            "24w14a",
            "a1.2.6",
            "b1.7.3",
            "1.0",
        )) {
            assertNull(
                VersionIcons.loaderIconFor(id),
                "$id 是原版，不该认出加载器图标",
            )
        }
    }

    @Test
    fun `加载器枚举都能映射到图标`() {
        ModLoaderService.Loader.entries.forEach { loader ->
            val icon = VersionIcons.iconFor(loader)
            assertEquals(
                when (loader) {
                    ModLoaderService.Loader.FABRIC -> VersionIcons.FABRIC
                    ModLoaderService.Loader.FORGE -> VersionIcons.FORGE
                    ModLoaderService.Loader.NEOFORGE -> VersionIcons.NEOFORGE
                },
                icon,
            )
        }
    }

    @Test
    fun `图标资源文件都存在`() {
        // 资源路径写错时界面什么都不会显示（VersionIconImage 静默返回），
        // 很难靠肉眼发现。这里直接把路径读一遍。
        val resources = listOf(
            VersionIcons.FABRIC,
            VersionIcons.FORGE,
            VersionIcons.NEOFORGE,
            VersionIcons.RELEASE,
            VersionIcons.SNAPSHOT,
            VersionIcons.APRIL_FOOLS,
            VersionIcons.OLD,
        )

        for (path in resources) {
            val stream = VersionIcons::class.java.getResourceAsStream(path)
            assertEquals(
                true,
                stream != null,
                "图标资源不存在: $path",
            )
            stream?.close()
        }
    }
}
