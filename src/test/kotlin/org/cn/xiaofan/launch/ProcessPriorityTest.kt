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

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [ProcessPriority] 的行为。
 *
 * <p>核心断言不是"能调成功"（这取决于运行环境有没有权限），
 * 而是「**任何情况下都不抛异常**」—— 优先级调整是锦上添花，
 * 它失败绝不能让启动流程崩掉。
 */
class ProcessPriorityTest {

    @Test
    fun `优先级为 0 时不执行任何命令`() {
        val result = ProcessPriority.apply(pid = 1, priority = 0)
        assertFalse(result.applied)
        assertTrue(result.message.contains("默认"), "应说明未调整，实际: ${result.message}")
    }

    @Test
    fun `无效 PID 返回失败而不是抛异常`() {
        val result = ProcessPriority.apply(pid = -1, priority = 10)
        assertFalse(result.applied)
        assertTrue(result.message.isNotEmpty())
    }

    @Test
    fun `不存在的 PID 返回失败而不是抛异常`() {
        // PID 用一个大到不存在的值。renice 会报找不到进程，
        // 但对我们来说只是「没调成功」，不是错误。
        val result = ProcessPriority.apply(pid = 999_999_999, priority = 10)
        assertFalse(result.applied)
        assertTrue(result.message.isNotEmpty(), "失败时也必须给出说明")
    }

    @Test
    fun `调整自身进程的优先级不会抛异常`() {
        val self = ProcessHandle.current().pid()
        // 结果可能是成功也可能失败（取决于权限与系统），
        // 唯一要求是：不抛异常，且给出可读的说明。
        val result = ProcessPriority.apply(self, priority = 19)
        assertTrue(result.message.isNotEmpty(), "无论成败都要有说明")
    }
}
