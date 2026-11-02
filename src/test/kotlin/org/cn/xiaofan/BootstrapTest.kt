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

package org.cn.xiaofan

import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * 验证 headlessmc 的 Quarkus 容器能否在普通 JVM 进程中启动。
 *
 * 这是整个架构的关键假设：如果容器起不来，GUI 内嵌方案就得推翻。
 */
class BootstrapTest {

    @Test
    fun `容器能启动并取到 CommandLine bean`() {
        HeadlessMcBootstrap.start()

        assertEquals(
            HeadlessMcBootstrap.State.READY,
            HeadlessMcBootstrap.currentState(),
            "容器应处于 READY 状态"
        )

        val commandLine = HeadlessMcBootstrap.bean(picocli.CommandLine::class.java)
        assertFalse(
            commandLine.subcommands.isEmpty(),
            "根命令应有子命令"
        )

        println("=== headlessmc 容器启动成功 ===")
        println("根命令: ${commandLine.commandSpec.name()}")
        println("子命令数: ${commandLine.subcommands.size}")
        commandLine.subcommands.keys.sorted().take(20).forEach { println("  - $it") }
    }
}
