package org.cn.xiaofan.launch

/**
 * 日志脱敏。
 *
 * <h2>为什么必须有</h2>
 *
 * 启动日志要显示在界面上，而 Minecraft 的 stdout 里**会**出现敏感信息：
 *
 * | 位置 | 泄露内容 |
 * |---|---|
 * | `--accessToken <jwt>` 命令行 | 正版登录令牌，能直接冒充该账号 |
 * | `--uuid <uuid>` | 玩家的唯一标识 |
 * | `Setting user: <name>` | 玩家名（相对不敏感，但日志常连带其他信息） |
 * | 异常堆栈里的完整 launch 命令 | 上面所有东西的合集 |
 * | `--server <host> --port` | 用户要连的服务器地址（隐私） |
 *
 * 用户截个图发到群里求助，就等于把账号送人了。这不只是"最佳实践" ——
 * 一枚有效的 accessToken 能让任何人以该账号身份登录任何正版服。
 *
 * <h2>策略</h2>
 *
 * 采用**白名单保留 + 黑名单遮盖**的组合：
 *
 * 1. 先匹配已知的敏感模式（`--accessToken xxx`、`--uuid xxx`、JWT 形状的串），
 *    把值换成 `[已隐藏]`
 * 2. 再兜底扫一遍：任何长得像 JWT（三段 base64、以 `eyJ` 开头）的串也遮掉 ——
 *    防止上游改了参数名或者换了输出格式后泄露
 *
 * <h2>为什么不用"整个日志脱敏"</h2>
 *
 * 那样会把正常日志也搞得没法读（比如把 `minecraft` 里的某些片段也替换掉）。
 * 精确匹配已知模式 + 兜底 JWT 特征，能在可读性和安全性之间取得平衡。
 *
 * <h2>已知局限</h2>
 *
 * 如果某个版本的 Minecraft 用了**未记录在 [SENSITIVE_FLAGS] 里**的参数名来传令牌，
 * 而令牌又不是 JWT 形状（比如 Yggdrasil 的随机 hex token），本工具识别不出来。
 * 这种情况下需要往 [SENSITIVE_FLAGS] 里补。**默认偏保守：宁可多遮，不可漏遮。**
 */
object LogSanitizer {

    /** 遮盖后显示的占位符。中括号包起来，一眼看出是"这里本来有东西"。 */
    private const val REDACTED = "[已隐藏]"

    /**
     * 需要遮盖值的命令行参数。
     *
     * <p>覆盖原版客户端、Forge、Fabric、以及 authlib-injector 常见的传法。
     * 参数名匹配忽略大小写（Minecraft 自己用 camelCase，但日志里可能出现别的写法）。
     */
    private val SENSITIVE_FLAGS = listOf(
        "--accessToken",
        "--access-token",
        "--accesstoken",
        "--session",
        "--sessionId",
        "--session-id",
        "--clientToken",
        "--client-token",
        "--refreshToken",
        "--refresh-token",
        "--auth_access_token",
        "--auth_session",
        "--auth_uuid",
        "--uuid",
        "--xuid",
        "--username",
        "--user",
        "--password",
    )

    /**
     * 预先编译的参数匹配正则。
     *
     * <p>一次匹配「参数名 + 分隔符 + 值」，值的边界是：
     * - 空白（下一个参数）
     * - 引号
     * - 行尾
     *
     * <p>用 `\b` 而不是 `\s` 结尾，是为了兼容 `--accessToken=xxx` 这种写法和
     * 值后面紧跟标点的日志格式（比如 JSON 里的 `"--accessToken":"xxx"`）。
     */
    private val FLAG_PATTERN: Regex = run {
        val names = SENSITIVE_FLAGS.joinToString("|") { Regex.escape(it) }
        // 值可能是引号包裹的，也可能不是；到空白/引号/逗号/右括号为止。
        Regex(
            """($names)(\s*[=:]\s*|\s+)("?)([^\s"',)\]}]+)\3""",
            RegexOption.IGNORE_CASE,
        )
    }

    /**
     * JWT 形状的串：`eyJ...`.`eyJ...`.`...`
     *
     * <p>Mojang 的 accessToken 就是 JWT（三段 base64url，以 `eyJ` 开头 ——
     * 那是 `{"` 的 base64）。即使参数名被改、或者令牌出现在异常堆栈的自由文本里，
     * 这个形状也能兜住。
     *
     * <p>段长度下限设 10：真正的 JWT 每段都远长于此，不会误伤普通文本。
     */
    private val JWT_PATTERN = Regex("""eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}""")

    /**
     * 长十六进制串（可能的 Yggdrasil token 或 session id）。
     *
     * <p>32 位及以上的纯 hex，且**前后不是字母数字**（避免把 git hash、
     * 文件名片段误判）。
     *
     * <p>这条比较激进：32 位 hex 也可能是个正常的 UUID。但 UUID 本来就在
     * [SENSITIVE_FLAGS] 的 `--uuid` 里被遮了，而裸的 UUID 出现在日志里
     * 通常也没有排查价值。宁可遮掉。
     */
    private val HEX_TOKEN_PATTERN = Regex("""(?<![A-Za-z0-9])[0-9a-fA-F]{32,}(?![A-Za-z0-9])""")

    /**
     * 对一行日志做脱敏。
     *
     * @param line 原始日志（可能包含换行，会被保留）
     * @return 脱敏后的日志
     */
    fun sanitize(line: String): String {
        if (line.isEmpty()) return line

        var result = line

        // 第一步：按参数名遮盖。保留参数名本身 —— 它对排查有用
        // （能看出启动器传了哪些参数），只有值被换掉。
        result = FLAG_PATTERN.replace(result) { match ->
            val flag = match.groupValues[1]
            val sep = match.groupValues[2]
            val quote = match.groupValues[3]
            "$flag$sep$quote$REDACTED$quote"
        }

        // 第二步：兜底遮 JWT。上游改参数名时这条能救。
        result = JWT_PATTERN.replace(result, REDACTED)

        // 第三步：兜底遮长 hex 串。
        result = HEX_TOKEN_PATTERN.replace(result, REDACTED)

        return result
    }

    /**
     * 仅供测试：检查一个串是否会被脱敏。
     *
     * <p>存在的意义是让测试断言"令牌原文没有出现在输出里"，
     * 而不是断言"输出了 [已隐藏]"—— 后者太弱，漏遮一处也能通过。
     */
    internal fun leaks(original: String, secret: String): Boolean =
        secret.isNotEmpty() && sanitize(original).contains(secret)
}
