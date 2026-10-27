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
