package org.cn.xiaofan.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * [Json] 的解析规则测试。
 *
 * <p>只覆盖版本清单真正用到的语法（对象、数组、字符串、数字、字面量）
 * 与路径取值。不追求完整的 JSON 一致性 —— 这是一个内部工具，
 * 用不上的语法不必测，测了反而锁死了实现。
 */
class JsonTest {

    @Test
    fun `解析对象数组与标量`() {
        val root = Json.parse(
            """{"a":1,"b":-2.5,"c":"x","d":true,"e":null,"f":[1,"2"],"g":{"h":"深"}}"""
        ) as Map<*, *>
        assertEquals(1L, root["a"])
        assertEquals(-2.5, root["b"])
        assertEquals("x", Json.stringAt(root, "c"))
        assertEquals("深", Json.stringAt(root, "g", "h"))
        assertNull(Json.stringAt(root, "nope"), "缺失的字段返回 null")
        assertNull(Json.stringAt(root, "a"), "不是字符串的字段要返回 null，不能硬转")
    }

    @Test
    fun `解析转义字符`() {
        // 原始字符串里不处理反斜杠，所以 JSON 文本、期望值可以原样写，
        // 不必再叠一层 Kotlin 转义。
        assertEquals("q\"w", Json.stringAt(Json.parse("""{"k":"q\"w"}"""), "k"))
        assertEquals("a\\b", Json.stringAt(Json.parse("""{"k":"a\\b"}"""), "k"))
        assertEquals("行\n尾", Json.stringAt(Json.parse("""{"k":"行\n尾"}"""), "k"))
        assertEquals("中", Json.stringAt(Json.parse("""{"k":"\u4e2d"}"""), "k"))
    }

    @Test
    fun `忽略结构外的空白`() {
        assertEquals("v", Json.stringAt(Json.parse(" \n { \"k\" : \"v\" } \t "), "k"))
    }

    @Test
    fun `非法 json 抛异常并带位置`() {
        val e = assertThrows(Json.JsonParseException::class.java) { Json.parse("""{"a":}""") }
        assertEquals(5, e.offset)
    }

    @Test
    fun `对象结束后有多余内容也算非法`() {
        assertThrows(Json.JsonParseException::class.java) { Json.parse("""{} {}""") }
    }
}
