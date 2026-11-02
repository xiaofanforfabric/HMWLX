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

package org.cn.xiaofan.util

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * [Browser] 的可用性检查。
 *
 * <p>只在有图形环境时跑：无头 CI 上打开浏览器必然失败，那是环境限制而非代码缺陷，
 * 用系统属性 `hmwlx.test.browser=true` 显式开启。
 *
 * <p>测试用 `about:blank`，不会真的访问网络，也不会弹出需要用户操作的页面。
 */
class BrowserTest {

    @Test
    @EnabledIfSystemProperty(named = "hmwlx.test.browser", matches = "true")
    fun `能拉起默认浏览器`() {
        val opened = Browser.open("about:blank")
        assertTrue(opened, "无法拉起默认浏览器，检查 xdg-open / Desktop 支持")
    }

    @Test
    fun `非图形环境下不抛异常`() {
        // 无论开得开不了，都必须安全返回，不能把调用方炸掉
        val result = runCatching { Browser.open("about:blank") }
        assertTrue(result.isSuccess, "Browser.open 不应抛异常: ${result.exceptionOrNull()}")
    }
}
