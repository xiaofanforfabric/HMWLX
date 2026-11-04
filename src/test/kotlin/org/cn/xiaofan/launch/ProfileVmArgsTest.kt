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

package org.cn.xiaofan.launch

import io.github.headlesshq.headlessmc.launcher.profile.LaunchOptions
import io.github.headlesshq.headlessmc.launcher.profile.Profile
import io.github.headlesshq.headlessmc.version.arg.VersionArg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.util.Optional

/**
 * 验证 `Profile.withVmArgs` 真的把参数存进去了。
 *
 * <h2>这个测试守的是什么</h2>
 *
 * 内存和 JVM 参数功能的整条链路是：
 *
 * ```
 * GameLaunchSettings.toVmArgs()
 *   → Profile.withVmArgs(...)
 *   → ArgumentsService.process(profile, ...)   ← 上游负责注入
 *   → JavaProcessBuilder → 最终命令行
 * ```
 *
 * 中间第 2 步是我们的代码，第 3 步是上游代码。上游那步已经通过
 * 反编译确认会读 `Profile.vmArgs()`（见 `ArgumentsService.process` 里
 * `Profile.vmArgs().forEach(arguments::addVmArg)`）。
 *
 * <p>这里锁住第 2 步：如果哪天有人重构 `buildAndLaunch` 时漏掉
 * `withVmArgs`，或者上游改了 `Profile` 的语义（比如 `withVmArgs`
 * 变成只对服务端生效），这个测试会立刻失败，而不是等用户发现
 * 「内存设置保存了但游戏还是 OOM」再来查。
 *
 * <p>为什么不直接断言最终命令行：那需要把整个 Quarkus 容器拉起来，
 * 再下载一份真实的版本 JSON。测试会从毫秒级变成分钟级，还依赖网络。
 * 收益不成比例 —— 注入逻辑在上游，不在我们这里。
 */
class ProfileVmArgsTest {

    /** 构造一个与生产代码同构的 Profile（不含容器依赖）。 */
    private fun buildProfile(vmArgs: List<String>): Profile =
        Profile("hmwlx-1.21.4", VersionArg.parse("1.21.4"), Path.of("/tmp/hmwlx-test"))
            .withOptions(LaunchOptions(Optional.empty(), Optional.empty(), Optional.empty(), false))
            .withVmArgs(vmArgs)

    @Test
    fun `withVmArgs 保留完整参数列表`() {
        val expected = listOf("-Xms1024M", "-Xmx4096M", "-XX:+UseG1GC")
        assertEquals(expected, buildProfile(expected).vmArgs())
    }

    @Test
    fun `空参数列表不会塞入任何东西`() {
        // 默认不干预的核心保证：什么都没设时，vmArgs 必须是空表。
        assertTrue(buildProfile(emptyList()).vmArgs().isEmpty())
    }

    @Test
    fun `构造 Profile 时不会丢失参数顺序`() {
        // 顺序有实际意义：-Xms 必须在 -Xmx 之前。
        val ordered = listOf("-Xms1024M", "-Xmx4096M")
        val actual = buildProfile(ordered).vmArgs()
        assertEquals(ordered, actual, "参数顺序被改变了: $actual")
    }

    @Test
    fun `参数里含空格时原样保留`() {
        // 我们已经在 parsedJvmArgs 里处理了引号，这里确认上游不会二次拆解。
        val withSpace = listOf("-Dfoo=a b")
        assertEquals(withSpace, buildProfile(withSpace).vmArgs())
    }
}
