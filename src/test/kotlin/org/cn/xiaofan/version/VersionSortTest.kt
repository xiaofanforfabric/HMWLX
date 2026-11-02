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

package org.cn.xiaofan.version

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [GameVersionService.sortByReleaseTimeDesc] 的排序规则测试。
 *
 * <p>这里只碰 `internal` 的纯函数，不启动容器、不读磁盘 —— 排序错了会让整个列表
 * 看起来像乱序，值得单独钉住。
 */
class VersionSortTest {

    @Test
    fun `发布时间倒序且缺时间的排在最后`() {
        fun v(id: String, time: String?) =
            GameVersion(id = id, type = VersionType.RELEASE, releaseTime = time)

        val sorted = GameVersionService.sortByReleaseTimeDesc(
            listOf(
                v("old", "2020-01-01T00:00:00+00:00"),
                v("none", null),
                v("new", "2024-01-01T00:00:00+00:00"),
            )
        )
        assertEquals(listOf("new", "old", "none"), sorted.map { it.id })
    }

    @Test
    fun `同为缺时间时按 id 排且顺序稳定`() {
        val versions = listOf(
            GameVersion(id = "b", type = VersionType.OTHER),
            GameVersion(id = "A", type = VersionType.OTHER),
        )
        assertEquals(
            listOf("A", "b"),
            GameVersionService.sortByReleaseTimeDesc(versions).map { it.id },
        )
        assertEquals(
            listOf("A", "b"),
            GameVersionService.sortByReleaseTimeDesc(versions.reversed()).map { it.id },
            "输入顺序不该影响结果",
        )
    }
}
