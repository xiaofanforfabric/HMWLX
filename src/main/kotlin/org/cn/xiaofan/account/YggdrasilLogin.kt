package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.AuthProvider
import io.github.headlesshq.headlessmc.auth.AuthService
import io.github.headlesshq.headlessmc.auth.Authenticator
import io.github.headlesshq.headlessmc.console.Console
import io.github.headlesshq.headlessmc.console.Password
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap

/**
 * 第三方外置登录（Yggdrasil / authlib-injector 兼容）。
 *
 * <p>headlessmc 的 [io.github.headlesshq.headlessmc.auth.yggdrasil.YggdrasilAuthenticator]
 * 只对外暴露「按 [Console] 交互式登录」这一个入口，收集输入与执行登录写死在一起：
 * ```
 * private Account login(String serverUrl, String username, String password, Console console)
 * private Method<Console> loginWithUserInput()   // 先问三个问题，再调上面那个
 * ```
 *
 * <p>GUI 已经有结构化的表单，不需要「先问问答再登录」这一层。直接反射调用
 * `login(serverUrl, username, password, console)`，传入一个只返回空串的
 * [SilentConsole]，既跳过问答，又保留这个方法的全部登录逻辑。
 *
 * <p>**反射的代价要说清楚**：这是 private 方法，headlessmc 改签名就会失效。
 * 所以这里显式锁定参数类型，并在失败时给出可诊断的错误信息，而不是笼统地
 * 抛 NoSuchMethodException。
 */
object YggdrasilLogin {

    /**
     * 没配置服务器地址时的默认值。
     *
     * 与上游 `YggdrasilAuthenticator.configuredOrDefaultServer()` 的兜底值一致，
     * 避免 GUI 显示的地址和实际连接的地址不是同一个。
     */
    const val DEFAULT_SERVER = "https://littleskin.cn"

    /** 读配置项 `hmc.auth.yggdrasil.url` 的键名。 */
    private const val CONFIG_SERVER_URL = "hmc.auth.yggdrasil.url"

    /**
     * 当前配置的服务器地址，未配置时返回 [DEFAULT_SERVER]。
     *
     * 供界面预填输入框：用户能一眼看出会连到哪个站，也可以直接改。
     */
    fun configuredServerUrl(): String {
        // 先看系统属性（便于测试与临时覆盖），再读 headlessmc 的配置文件
        System.getProperty(CONFIG_SERVER_URL)?.takeIf { it.isNotBlank() }?.let { return it }

        val file = headlessMcConfigFile() ?: return DEFAULT_SERVER
        val value = runCatching {
            if (!file.isFile) return@runCatching null
            file.readLines(Charsets.UTF_8)
                .asSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("$CONFIG_SERVER_URL=") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()

        return value ?: DEFAULT_SERVER
    }

    /**
     * 定位 headlessmc 的配置文件。
     *
     * 路径规则与 `AccountResolver` 里的保持一致（遵循 XDG）：
     * Linux `~/.config/headlessmc`、Windows `%APPDATA%/headlessmc`、
     * macOS `~/Library/Application Support/headlessmc`。
     */
    private fun headlessMcConfigFile(): java.io.File? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val dir = when {
            os.contains("win") ->
                System.getenv("APPDATA")?.let { java.io.File(it, "headlessmc") }

            os.contains("mac") ->
                java.io.File(System.getProperty("user.home"), "Library/Application Support/headlessmc")

            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) java.io.File(xdg, "headlessmc")
                else System.getProperty("user.home")?.let { java.io.File(it, ".config/headlessmc") }
            }
        } ?: return null
        return java.io.File(dir, "config.properties")
    }

    /** 目标方法签名：(String, String, String, Console) -> Account */
    private val LOGIN_PARAM_TYPES = arrayOf(
        String::class.java,
        String::class.java,
        String::class.java,
        Console::class.java,
    )

    /**
     * 用账号密码登录外置皮肤站。
     *
     * <p>这是**阻塞调用**（含网络往返），必须在后台线程执行。
     *
     * @param serverUrl 皮肤站地址，如 `https://littleskin.cn`。空串表示用配置里的默认值。
     * @param username 皮肤站账号（邮箱或用户名）
     * @param password 密码
     * @return 登录结果
     */
    fun login(serverUrl: String, username: String, password: String): LoginResult = runCatching {
        performLogin(serverUrl, username, password, profileChoice = null)
    }.getOrElse { t -> toFailure(t) }

    /**
     * 登录，并允许指定角色序号。
     *
     * @param profileIndex 角色序号，从 **1** 开始；null 表示没有指定，
     *                     此时若账号有多个角色，会返回 [LoginResult.ProfileRequired]
     */
    fun login(
        serverUrl: String,
        username: String,
        password: String,
        profileIndex: Int,
    ): LoginResult = runCatching {
        performLogin(serverUrl, username, password, profileChoice = profileIndex.toString())
    }.getOrElse { t -> toFailure(t) }

    /**
     * 真正执行登录。
     *
     * @param profileChoice 角色选择时回答的内容，null 表示「还没决定」
     */
    private fun performLogin(
        serverUrl: String,
        username: String,
        password: String,
        profileChoice: String?,
    ): LoginResult {
        if (username.isBlank()) return LoginResult.Failed("请输入皮肤站账号")
        if (password.isEmpty()) return LoginResult.Failed("请输入密码")

        val provider = HeadlessMcBootstrap.bean(AuthService::class.java)
            .getProvider(AuthProvider.YGGDRASIL)
            .orElse(null)
            ?: return LoginResult.Failed(
                "headlessmc 未启用外置登录。请在 config.properties 里设置 hmc.auth.yggdrasil.url"
            )

        // isEnabled() 上游用于「没配置服务器地址就不启用」，
        // 提前判断能给出比反射失败更友好的提示。
        val enabled = runCatching {
            provider.javaClass.getMethod("isEnabled").invoke(provider) as? Boolean
        }.getOrNull()
        if (enabled == false) {
            return LoginResult.Failed(
                "外置登录未启用。请在 config.properties 里设置 hmc.auth.yggdrasil.url"
            )
        }

        val authenticator = provider.authenticator
        val method = authenticator.javaClass.declaredMethods.firstOrNull {
            it.name == "login" && it.parameterTypes.contentEquals(LOGIN_PARAM_TYPES)
        } ?: return LoginResult.Failed(
            "headlessmc 的 YggdrasilAuthenticator 没有预期的 login 方法，" +
                "可能上游改了实现（${authenticator.javaClass.name}）"
        )

        method.isAccessible = true

        val console = AnsweringConsole(profileChoice)
        val account = method.invoke(
            authenticator,
            serverUrl.trim(),
            username.trim(),
            password,
            console,
        ) as? io.github.headlesshq.headlessmc.auth.Account
            ?: return LoginResult.Failed("登录返回了空账号")

        return LoginResult.Success(
            AccountResolver.fromAccount(
                name = account.name,
                provider = account.provider,
                uuid = account.uuid,
            )
        )
    }

    /**
     * 把异常翻译成界面可展示的失败原因。
     *
     * 反射会把业务异常包在 `InvocationTargetException` 里，剥一层再判断。
     */
    private fun toFailure(t: Throwable): LoginResult {
        val cause = (t as? java.lang.reflect.InvocationTargetException)?.targetException ?: t

        // 多角色时 AnsweringConsole 会抛这个信号，转成「请选择角色」
        (cause as? ProfileSelectionRequired)?.let { signal ->
            return LoginResult.ProfileRequired(signal.profiles)
        }

        return LoginResult.Failed(cause.message ?: cause.javaClass.simpleName)
    }

    /**
     * 回答登录过程中提问的 Console。
     *
     * <p>需要回答的只有角色选择，而 `selectProfile` 的解析是
     * `Integer.parseInt(read().trim())` —— 要求从 **1** 开始的序号。
     * 返回空串会走到 `parseInt("")` 并抛异常，被包装成
     * `Invalid profile selection: ''`。
     *
     * <p>两种模式：
     * - [profileChoice] 为 null：还没确定角色。读到角色选择时抛出
     *   [ProfileSelectionRequired]，把角色列表带出去让界面弹窗。
     * - [profileChoice] 非 null：直接回答该序号。
     *
     * <p>角色列表从 [selectProfile] 打印的提示文本里解析 —— 这是唯一能拿到列表的
     * 途径，因为上游没有把它作为参数传进来。格式见 [PROFILE_LINE]。
     */
    private class AnsweringConsole(private val profileChoice: String?) : Console {

        /** 从提示文本里抓到的角色名，按显示顺序。 */
        private val profiles = mutableListOf<String>()

        override fun write(message: String) {
            println("[HMWLX][yggdrasil] $message")
            // groupValues[1] 是序号，[2] 才是角色名 —— 取错就会把 "1"、"2" 当成角色
            PROFILE_LINE.findAll(message).forEach { profiles += it.groupValues[2].trim() }
        }

        override fun write(message: String, level: String) {
            write(message)
        }

        /**
         * 回答输入。
         *
         * 只有角色选择会读到输入。没指定角色时抛信号中断整个登录流程，
         * 让界面拿到列表后重新发起；指定了就直接回答序号。
         */
        override fun read(): String {
            if (profileChoice != null) return profileChoice

            // 上游在提问前会先逐行打印候选角色，此时列表已收集完毕
            throw ProfileSelectionRequired(profiles.toList())
        }

        override fun readPassword(): Password = Password(charArrayOf())
    }

    /** 角色选择时用来中断登录、把列表带回界面的信号。 */
    private class ProfileSelectionRequired(val profiles: List<String>) :
        RuntimeException("需要用户选择角色")

    /**
     * 匹配上游打印的候选角色行。
     *
     * 上游格式（实测 0df01d2043）：
     * ```
     * Found 2 profiles:
     *   1. xiaofanforMC
     *   2. xiaofanbot
     * Select a profile (1-2):
     * ```
     * 注意序号后面是**点号**不是冒号，且行首有缩进。
     *
     * 只匹配「缩进 + 数字 + 点号 + 名字」这种严格形式，避免把
     * `Select a profile (1-2):` 或统计行误当成角色。
     */
    private val PROFILE_LINE = Regex("""^\s*(\d+)\.\s+(.+?)\s*$""", RegexOption.MULTILINE)
}
