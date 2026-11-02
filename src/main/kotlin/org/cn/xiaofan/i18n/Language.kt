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

package org.cn.xiaofan.i18n

/**
 * 界面语言。
 *
 * 当前只提供中文一种语言包，其余语言留到真有人用再说。
 * 新增语言时：在这里加一个枚举值，再在 `src/main/resources/i18n/<code>.properties` 放一份语言包。
 */
enum class Language(val code: String) {
    /** 简体中文。 */
    ZH_CN("zh-CN"),
    ;

    companion object {
        /** 默认语言：中文。 */
        val DEFAULT: Language = ZH_CN

        /** 按 code 查找，找不到时回退到 [DEFAULT]。 */
        fun fromCode(code: String?): Language =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: DEFAULT
    }
}
