package org.cn.xiaofan.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 日志脱敏。
 *
 * <p>这些测试的断言方式是「**原文不能出现在输出里**」，
 * 而不是「输出了 [已隐藏]」。后者太弱：漏遮一处，另一处遮住了也能通过。
 *
 * <p>为什么值得认真测：日志会显示在界面上，用户遇到问题会截图发群。
 * 一枚有效的 accessToken 能让任何人以该账号身份登录任何正版服。
 * 这不是"最佳实践"，是账号安全。
 */
class LogSanitizerTest {

    private val jwt =
        "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9." +
            "eyJ4NXUiOiJtb25leSIsInN1YiI6IjEyMyJ9." +
            "c2lnbmF0dXJlLXZhbHVlLWhlcmUteWVzLXNpci1sb25nLWVub3VnaA"

    // ---------------------------------------------------------------------
    // 命令行参数
    // ---------------------------------------------------------------------

    @Test
    fun `accessToken 的值被遮住`() {
        val line = "--accessToken $jwt --username Steve --version 1.21.4"

        val safe = LogSanitizer.sanitize(line)

        assertFalse(safe.contains(jwt), "令牌原文不能出现在输出里: $safe")
        assertTrue(safe.contains("--accessToken"), "参数名应当保留，它对排查有用")
    }

    @Test
    fun `uuid 被遮住`() {
        val uuid = "d8c48e47db1d315f8fcb0f8f260af745"
        val safe = LogSanitizer.sanitize("--uuid $uuid --accessToken abc")

        assertFalse(safe.contains(uuid), "UUID 不该出现在日志里: $safe")
    }

    @Test
    fun `等号写法也被遮住`() {
        // 有些启动器/日志格式会写成 --key=value
        val safe = LogSanitizer.sanitize("--accessToken=$jwt")

        assertFalse(safe.contains(jwt), "等号分隔也要遮: $safe")
    }

    @Test
    fun `参数名大小写不敏感`() {
        val line = "--ACCESSTOKEN $jwt"

        val safe = LogSanitizer.sanitize(line)

        assertFalse(safe.contains(jwt), "大小写变体也要遮: $safe")
    }

    @Test
    fun `session 和 clientToken 也被遮住`() {
        val line = "--session abc123secret --clientToken def456secret"

        val safe = LogSanitizer.sanitize(line)

        assertFalse(safe.contains("abc123secret"), "session 要遮: $safe")
        assertFalse(safe.contains("def456secret"), "clientToken 要遮: $safe")
    }

    // ---------------------------------------------------------------------
    // 兜底：JWT 形状
    // ---------------------------------------------------------------------

    @Test
    fun `参数名未知但形状是 JWT 的串也会被遮住`() {
        // 这是兜底那层存在的意义：上游改了参数名、或者令牌出现在
        // 自由文本/异常堆栈里，按参数名匹配就失效了。
        val line = "--someNewFlagName $jwt"

        val safe = LogSanitizer.sanitize(line)

        assertFalse(safe.contains(jwt), "JWT 形状的串必须兜住: $safe")
    }

    @Test
    fun `异常堆栈里的 JWT 也被遮住`() {
        val line = "Caused by: java.io.IOException: failed to load $jwt chunk"

        val safe = LogSanitizer.sanitize(line)

        assertFalse(safe.contains(jwt), "堆栈里的令牌同样敏感: $safe")
    }

    @Test
    fun `JSON 里的令牌被遮住`() {
        val line = """{"accessToken":"$jwt","name":"Steve"}"""

        val safe = LogSanitizer.sanitize(line)

        assertFalse(safe.contains(jwt), "JSON 格式也要遮: $safe")
    }

    // ---------------------------------------------------------------------
    // 长 hex 串
    // ---------------------------------------------------------------------

    @Test
    fun `32 位 hex 串被遮住`() {
        val token = "0123456789abcdef0123456789abcdef"

        val safe = LogSanitizer.sanitize("session=$token end")

        assertFalse(safe.contains(token), "长 hex 可能是 token: $safe")
    }

    @Test
    fun `64 位 hex 串被遮住`() {
        val token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        val safe = LogSanitizer.sanitize("token $token")

        assertFalse(safe.contains(token), "64 位 hex 要遮: $safe")
    }

    // ---------------------------------------------------------------------
    // 不误伤
    // ---------------------------------------------------------------------

    @Test
    fun `普通日志不被改动`() {
        val line = "[12:00:00] [main/INFO]: Loading Minecraft 1.21.4"

        assertEquals(line, LogSanitizer.sanitize(line))
    }

    @Test
    fun `正常参数不被误遮`() {
        val line = "--version 1.21.4 --gameDir /home/user/.minecraft"

        assertEquals(line, LogSanitizer.sanitize(line))
    }

    @Test
    fun `Setting user 那行保留玩家名以外的内容`() {
        // 玩家名其实不算高度敏感，但它旁边的其他信息可能敏感。
        // 这里只确认这行不会被整体吞掉。
        val line = "[12:00:01] [main/INFO]: Setting user: Steve"

        val safe = LogSanitizer.sanitize(line)

        assertTrue(safe.contains("Setting user"), "这行是判断阶段用的，不能整行删掉")
    }

    @Test
    fun `短十六进制串不被误遮`() {
        // 32 位是下限。短的不该动，否则模组名、版本号里的 hex 全被遮。
        val line = "Loading chunk 1a2b3c4d"

        assertEquals(line, LogSanitizer.sanitize(line))
    }

    @Test
    fun `空串安全`() {
        assertEquals("", LogSanitizer.sanitize(""))
    }

    @Test
    fun `leaks 辅助方法能正确判断`() {
        // 这个方法存在的意义是让其他测试能写「原文不在输出里」这种断言。
        //
        // 语义是「脱敏后的结果里还残留 secret 吗」：
        // - 脱敏正确时返回 false
        // - 如果正则漏了，返回 true
        assertFalse(
            LogSanitizer.leaks("--accessToken $jwt", jwt),
            "脱敏正确时不该判定为泄露",
        )
        // 反向验证：对一个不含敏感内容的输入，任何 secret 都不该被判泄露。
        assertFalse(LogSanitizer.leaks("普通日志", jwt))
    }
}
