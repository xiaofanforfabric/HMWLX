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

import io.github.headlesshq.headlessmc.platform.PlatformService
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 验证模组加载器平台是否被 Quarkus 正确发现。
 *
 * <h2>这个测试防的是什么</h2>
 *
 * 引入 `headlessmc-platform-fabric` 之类的依赖后，**如果忘了在
 * application.properties 里加 `quarkus.index-dependency`**，Quarkus
 * 就不会索引那些 jar 里的 `@ApplicationScoped` Factory。
 *
 * 后果是 [PlatformService.getPlatforms] 只返回 vanilla —— **不报错、不警告**，
 * 界面上的三个加载器页签全部消失。没有测试的话只能靠人工点界面发现，
 * 而且很容易误以为是网络问题（「Fabric 版本列表查询失败」看起来像 API 挂了）。
 *
 * <p>所以这里断言的是「平台存在」，不是「能查到版本」——
 * 前者是本地装配问题，后者才会受网络影响。测试必须只依赖前者。
 */
class ModLoaderPlatformTest {

    @Test
    fun `容器能发现全部模组加载器平台`() {
        HeadlessMcBootstrap.start()

        val service = HeadlessMcBootstrap.bean(PlatformService::class.java)
        assertNotNull(service, "应能取到 PlatformService")

        val names = service.platforms.map { it.name.lowercase() }
        println("=== 已发现的平台: $names ===")

        // vanilla 一直都有，作为基准：如果它也没了，说明容器根本没装配好，
        // 而不是 platform 模块的问题。
        assertTrue(
            names.contains("vanilla"),
            "原版平台应该始终存在，实际: $names",
        )

        // 三个客户端加载器。缺任何一个都说明 build.gradle 的依赖或
        // application.properties 的 index-dependency 漏了。
        val expected = listOf("fabric", "forge", "neoforge")
        val missing = expected.filterNot { expectedName ->
            names.any { it == expectedName }
        }

        assertTrue(
            missing.isEmpty(),
            "以下模组加载器未被发现: $missing\n" +
                "实际发现的平台: $names\n" +
                "检查：1) build.gradle 是否声明了对应 platform 依赖 " +
                "2) application.properties 是否加了对应的 quarkus.index-dependency",
        )
    }

    @Test
    fun `每个加载器都有版本服务与客户端支持`() {
        HeadlessMcBootstrap.start()

        val service = HeadlessMcBootstrap.bean(PlatformService::class.java)

        for (name in listOf("fabric", "forge", "neoforge")) {
            val platform = service.getPlatform(name).orElse(null)
            if (platform == null) {
                // 上一个测试会报缺哪个，这里只跳过，避免重复噪音。
                println("=== 跳过 $name（平台不存在）===")
                continue
            }

            // 版本服务是查「某 MC 版本下有哪些加载器构建」的入口，
            // 没有它就没法在详情页里列可选构建。
            assertNotNull(platform.versionService, "$name 应有 versionService")

            // ClientSupport 提供 installer，缺了就只能查版本不能装。
            assertTrue(
                platform.clientSupport.isPresent,
                "$name 应有 clientSupport（否则无法安装）",
            )

            // 名字要能往返：getPlatform(name) 拿到的对象 name 应一致，
            // 否则 VersionID.resolve 之类按名字查的地方会错位。
            println(
                "=== $name: capitalize=${platform.capitalizedName}, " +
                    "repos=${platform.repositories.size} ===",
            )
        }
    }
}
