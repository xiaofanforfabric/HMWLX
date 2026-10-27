package org.cn.xiaofan.i18n

import java.util.Properties

/**
 * 界面文案的读取入口。
 *
 * 文案全部放在 `src/main/resources/i18n/<语言>.properties`，代码里只留 key。
 * 没有引第三方库：Compose Desktop 的 `compose.components.resources` 需要额外插件与
 * 资源生成流程，对现在这个 Demo 来说不值当。
 *
 * 缺 key 不会抛异常，而是返回 `!key!` —— 这样漏翻译在界面上看得见，也不会白屏。
 */
object Messages {

    private const val DIR = "i18n"

    /** 已加载的语言包，key 是 [Language.code]。 */
    private val bundles = mutableMapOf<Language, Properties>()

    /** 当前语言，切换后界面重绘即可生效。 */
    @Volatile
    var current: Language = Language.DEFAULT
        private set

    /** 切换当前语言。 */
    fun setLanguage(language: Language) {
        current = language
    }

    /**
     * 取文案。
     *
     * @param key 语言包里的 key，见 [TextKey] 常量
     * @param args 占位符参数，语言包里用 `{0}`、`{1}` 表示
     */
    fun get(key: String, vararg args: Any?): String {
        val raw = bundle(current).getProperty(key)?.takeIf { it.isNotBlank() } ?: "!$key!"
        if (args.isEmpty()) return raw
        return args.foldIndexed(raw) { index, acc, arg ->
            acc.replace("{$index}", arg?.toString() ?: "")
        }
    }

    private fun bundle(language: Language): Properties = synchronized(bundles) {
        bundles.getOrPut(language) { load(language) }
    }

    private fun load(language: Language): Properties {
        val props = Properties()
        val path = "/$DIR/${language.code}.properties"
        val stream = Messages::class.java.getResourceAsStream(path) ?: return props
        stream.use { props.load(it.reader(Charsets.UTF_8)) }
        return props
    }
}
