package org.cn.xiaofan.config

/**
 * 极简 JSON 读取器，只够读配置文件与版本清单。
 *
 * <p>为什么不引 Gson / Jackson：HMWLX 主工程里两者都只是 headlessmc 的传递依赖，
 * 版本随它变动；为了读三个字段把这份传递依赖变成显式契约不值当。
 * 而 Flatten 之后要的东西就三样 —— 对象、数组、标量 —— 一百行够了。
 *
 * <p>**只支持读取**，不做写回。需要写 JSON 的地方（目前没有）再单独考虑。
 *
 * <p>解析出来的类型是 Kotlin 原生值：
 * - 对象 → `Map<String, Any?>`
 * - 数组 → `List<Any?>`
 * - 字符串 → [String]；整数 → [Long]；小数 → [Double]；`true`/`false` → [Boolean]
 * - `null` → null
 *
 * <p>遇到不合法的 JSON 直接抛 [JsonParseException]，调用方自己决定是崩还是降级。
 * 这里不吞异常：读不清楚的数据硬解释成默认值，比报错更难查。
 */
object Json {

    /** 解析失败。 */
    class JsonParseException(message: String, val offset: Int) : Exception("$message (位置 $offset)")

    /** 解析一段 JSON 文本。 */
    fun parse(text: String): Any? = Reader(text).run {
        skipWhitespace()
        val value = readValue()
        skipWhitespace()
        if (pos != text.length) fail("JSON 结束后还有多余内容")
        value
    }

    /**
     * 从解析结果里按路径取子对象。
     *
     * 路径上任何一层缺失或类型不对都返回 null —— 缺字段是正常情况
     * （版本 JSON 里 `inheritsFrom`、`mainClass` 都可能没有），
     * 为此抛异常会让每个调用点都得写 try。
     */
    @Suppress("UNCHECKED_CAST")
    fun objectAt(root: Any?, vararg path: String): Map<String, Any?>? {
        var cur: Any? = root
        for (key in path) {
            cur = (cur as? Map<String, Any?>)?.get(key) ?: return null
        }
        return cur as? Map<String, Any?>
    }

    /** 按路径取字符串，缺失或不是字符串时返回 null。 */
    fun stringAt(root: Any?, vararg path: String): String? {
        var cur: Any? = root
        for (key in path) {
            cur = (cur as? Map<String, Any?>)?.get(key) ?: return null
        }
        return cur as? String
    }

    private class Reader(private val text: String) {
        var pos = 0

        fun fail(message: String): Nothing = throw JsonParseException(message, pos)

        fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            if (pos >= text.length) fail("JSON 意外结束")
            return when (val c = text[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (c == '-' || c.isDigit()) readNumber() else fail("无法识别的字符 '$c'")
            }
        }

        private fun readObject(): Map<String, Any?> {
            val result = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWhitespace()
            if (peek() == '}') { pos++; return result }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("对象的键必须是字符串")
                val key = readString()
                skipWhitespace()
                if (peek() != ':') fail("键之后缺少 ':'")
                pos++
                skipWhitespace()
                result[key] = readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return result }
                    else -> fail("对象里缺少 ',' 或 '}'")
                }
            }
        }

        private fun readArray(): List<Any?> {
            val result = ArrayList<Any?>()
            pos++ // [
            skipWhitespace()
            if (peek() == ']') { pos++; return result }
            while (true) {
                skipWhitespace()
                result += readValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return result }
                    else -> fail("数组里缺少 ',' 或 ']'")
                }
            }
        }

        private fun readString(): String {
            pos++ // 开头的引号
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) fail("字符串没有闭合")
                when (val c = text[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= text.length) fail("转义符后没有字符")
                        when (val esc = text[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > text.length) fail("\\u 转义不足 4 位")
                                val hex = text.substring(pos, pos + 4)
                                pos += 4
                                sb.append(
                                    hex.toIntOrNull(16)?.toChar() ?: fail("非法的 \\u 转义: $hex")
                                )
                            }
                            else -> fail("未知转义 \\$esc")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun readNumber(): Number {
            val start = pos
            if (peek() == '-') pos++
            while (pos < text.length && text[pos].isDigit()) pos++
            var isDouble = false
            if (pos < text.length && text[pos] == '.') {
                isDouble = true
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                isDouble = true
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            val raw = text.substring(start, pos)
            if (raw.isEmpty() || raw == "-") fail("非法的数字")
            return if (isDouble) raw.toDoubleOrNull() ?: fail("非法的数字: $raw")
            else raw.toLongOrNull() ?: fail("非法的数字: $raw")
        }

        private fun <T : Any> readLiteral(literal: String, value: T?): T? {
            if (!text.startsWith(literal, pos)) fail("非法的字面量")
            pos += literal.length
            return value
        }

        private fun peek(): Char {
            if (pos >= text.length) fail("JSON 意外结束")
            return text[pos]
        }
    }
}
