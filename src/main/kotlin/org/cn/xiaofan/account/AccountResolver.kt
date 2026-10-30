package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.AuthProvider
import io.github.headlesshq.headlessmc.auth.AuthService
import io.github.headlesshq.headlessmc.auth.LastUsedAccountService
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey

/**
 * 账号信息与头像来源。
 *
 * headlessmc 的账号模型（见 `Account` 类）：
 * - `provider`：账号所属认证源，取值为 [AuthProvider.OFFLINE] / [AuthProvider.YGGDRASIL] /
 *   [AuthProvider.DEFAULT]（Microsoft 正版）
 * - `name`：玩家名
 * - `uuid`：不带连字符的 32 位十六进制
 * - `type`：`msa` 或 `legacy`
 *
 * 头像来源按 provider 分流：
 * - **正版（Microsoft）**：走 Mojang 官方会话服务 `sessionserver.mojang.com`。
 *   这是官方接口，不需要额外 OAuth，只要 UUID 就能取到皮肤头部。
 * - **皮肤站（Yggdrasil）**：各皮肤站自己提供纹理接口，地址随站点不同。
 *   这里按账号记录的实际 serverUrl 拼 `.../sessionserver/session/minecraft/profile/<uuid>`，
 *   Blessed skin 站（如 littleskin、ely.by）都兼容这个路径。
 * - **离线**：headlessmc 的默认离线账号 UUID 是 Steve 的
 *   `22689332a7fd41919600b0fe1135ee34`，直接走 Mojang 接口即可拿到 Steve 头像。
 *
 * 头像来源解析不出来时返回 null（界面用首字母），[AvatarSource.Unavailable]
 * 表示「确定没有」，两者都不发请求。
 */
data class AccountProfile(
    /** 玩家名。 */
    val name: String,
    /** 认证源，见 [AuthProvider] 常量。 */
    val provider: String,
    /** 不带连字符的 UUID。 */
    val uuid: String,
    /**
     * 头像来源。
     *
     * - 网络地址：正版走 Mojang 会话服务，皮肤站走站点自己的纹理接口
     * - [AvatarSource.BuiltInSkin]：离线账号用内置皮肤，不发起任何网络请求
     * - null / [AvatarSource.Unavailable]：取不到，界面回退到首字母占位
     */
    val avatar: AvatarSource?,
) {
    /** 头像的网络地址，内置皮肤或缺失时为 null。 */
    val avatarUrl: String? get() = (avatar as? AvatarSource.Remote)?.url

    /**
     * 头像的记忆键。
     *
     * 界面用它做 `remember` / `LaunchedEffect` 的 key，而**不能**直接用 [avatar]：
     * [AvatarSource.BuiltInSkin.STEVE] 是所有离线账号共用的同一个实例，
     * `data class` 的 `equals` 会把「小凡」和「小明」的头像判成相等，
     * 于是切到另一个离线账号时 key 不变，头像状态不重置。
     * 现在离线共用 Steve 所以看不出问题，但只要 [AvatarSource.BuiltInSkin]
     * 多一个字段（比如按名字分配不同皮肤），这个 bug 就会当场现形。
     *
     * 身份 = provider + uuid：同一个账号才算同一张头像，与头像来源无关。
     */
    val avatarKey: String get() = "$provider:$uuid"

    /** 是否为离线账号。 */
    val isOffline: Boolean get() = provider == AuthProvider.OFFLINE

    /** 是否为正版账号。 */
    val isMicrosoft: Boolean get() = provider == AuthProvider.DEFAULT

    /** 是否为皮肤站账号。 */
    val isYggdrasil: Boolean get() = provider == AuthProvider.YGGDRASIL
}

/**
 * 头像的来源。
 *
 * 用 sealed 类型区分「网络下载」与「内置资源」，是为了让离线账号不去访问网络：
 * 离线 UUID 是 `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")` 算出来的，
 * Mojang 数据库里没有这个 ID，强行请求只会拿到 204 空响应。
 */
sealed interface AvatarSource {
    /**
     * 从网络下载皮肤。
     *
     * @property url 可以是皮肤 PNG 直链，也可以是 Yggdrasil / Mojang 的 profile 接口
     */
    data class Remote(val url: String) : AvatarSource

    /**
     * 已知取不到头像，不必再发请求。
     *
     * 与 null 的区别只在语义：null 是「没配」，这里是「配了但确定没有」。
     * 两者对界面都是首字母占位。留着这个分支是为了让「没有」这件事显式化，
     * 而不是靠一个看不出原因的 null 去猜。
     */
    data object Unavailable : AvatarSource

    /**
     * 使用打进 jar 的内置皮肤。
     *
     * 目前只有离线账号用，取 [BuiltInSkin.STEVE]。
     */
    data class BuiltInSkin(val resourcePath: String) : AvatarSource {
        companion object {
            /** Steve 皮肤，位于 `src/main/resources/skins/steve.png`。 */
            val STEVE = BuiltInSkin("/skins/steve.png")
        }
    }
}

/**
 * 账号来源的展示标签。
 *
 * 放在这里而不是 [AccountProfile] 里：语言包是 UI 层的事，账号模型不该为了
 * 显示名称去依赖 i18n —— 否则切换当前语言时会连数据层一起受影响。
 * 未知 provider 直接显示原值，免得把内部标识也塞进语言包。
 */
fun AccountProfile.sourceLabel(): String = when {
    isMicrosoft -> Messages.get(TextKey.SOURCE_MICROSOFT)
    isYggdrasil -> Messages.get(TextKey.SOURCE_YGGDRASIL)
    isOffline -> Messages.get(TextKey.SOURCE_OFFLINE)
    else -> provider
}

/**
 * 读取当前登录账号，并解析其头像地址。
 *
 * 需要在 [HeadlessMcBootstrap] 容器就绪后调用。
 */
object AccountResolver {

    /** Mojang 官方会话服务，正版与离线（Steve）都用它。 */
    private const val MOJANG_SESSION = "https://sessionserver.mojang.com/session/minecraft/profile"

    /** Steve 的 UUID，与 headlessmc `Account.defaultOfflineAccount()` 保持一致。 */
    private const val STEVE_UUID = "22689332a7fd41919600b0fe1135ee34"

    /**
     * 取当前账号。
     *
     * <p>**必须优先读 [LastUsedAccountService]**，这是 headlessmc `account select`
     * 和本工程 `AccountService.switchTo` 共同写入的位置 —— 界面顶部显示的账号、
     * 启动时实际使用的账号，都必须是这一条。
     *
     * <p>早期版本这里写的是 `AuthService.accounts.firstOrNull()`，那是个 bug：
     * `accounts` 是按名字排序的**全部**账号列表，取第一个等于「名字字典序最小的那个」，
     * 跟用户选了谁毫无关系。表现是用户切到离线账号，界面还显示微软账号，
     * 或者反过来 —— 启动前的账号预检去刷新了一个用户根本没选的账号。
     *
     * <p>回退顺序：
     * 1. [LastUsedAccountService] 的当前账号 —— 正常路径
     * 2. 账号列表的第一个 —— headlessmc 还没写过 lastUsed 时兜底
     * 3. 默认离线账号（Steve）—— 一个账号都没有时，与 headlessmc 行为一致
     */
    fun current(): AccountProfile {
        // 1. 优先读「上次选中的账号」。
        runCatching {
            HeadlessMcBootstrap.bean(LastUsedAccountService::class.java)
                .lastUsedAccount
                .orElse(null)
        }.getOrNull()?.let { account ->
            return fromAccount(
                name = account.name,
                provider = account.provider,
                uuid = account.uuid,
            )
        }

        // 2. 兜底：列表第一个。
        val fallback = runCatching {
            HeadlessMcBootstrap.bean(AuthService::class.java).accounts.firstOrNull()
        }.getOrNull()

        if (fallback == null) {
            // 3. 一个都没有：默认离线账号。
            return AccountProfile(
                name = "Offline",
                provider = AuthProvider.OFFLINE,
                uuid = STEVE_UUID,
                avatar = AvatarSource.BuiltInSkin.STEVE,
            )
        }

        return fromAccount(
            name = fallback.name,
            provider = fallback.provider,
            uuid = fallback.uuid,
        )
    }

    /**
     * 由账号三元组构造 [AccountProfile]，并解析头像地址。
     */
    fun fromAccount(name: String, provider: String, uuid: String): AccountProfile {
        val cleanUuid = uuid.replace("-", "")
        return AccountProfile(
            name = name,
            provider = provider,
            uuid = cleanUuid,
            avatar = avatarFor(provider, cleanUuid),
        )
    }

    /**
     * 按 provider 决定头像来源。
     *
     * - **正版**：Mojang 会话服务，有 UUID 就能取到皮肤，无需鉴权
     * - **离线**：内置 Steve 皮肤，**不访问网络**。离线 UUID 是
     *   `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")` 的结果，Mojang 不认识，
     *   请求只会得到 204 空响应 —— 之前头像一直显示首字母就是这个原因
     * - **皮肤站**：站点自己的纹理接口，地址随站点不同
     */
    private fun avatarFor(provider: String, uuid: String): AvatarSource? = when (provider) {
        AuthProvider.DEFAULT -> AvatarSource.Remote("$MOJANG_SESSION/$uuid")
        AuthProvider.OFFLINE -> AvatarSource.BuiltInSkin.STEVE
        AuthProvider.YGGDRASIL -> yggdrasilAvatarUrl(uuid)?.let { AvatarSource.Remote(it) }
        else -> null
    }

    /** 仅供测试：按 provider 推导头像来源，钉死「正版一定有地址」这条不变式。 */
    internal fun debugAvatarSource(provider: String, uuid: String): AvatarSource? =
        avatarFor(provider, uuid)

    /**
     * 皮肤站头像。
     *
     * Yggdrasil 的 profile 接口路径是：
     * `{站点}{apiPath}/sessionserver/session/minecraft/profile/{uuid}`
     *
     * 其中 `apiPath` 由各皮肤站定义，headlessmc 的 `YggdrasilConfig.apiPath()`
     * 默认返回 `/api/yggdrasil`。实测 littleskin：
     * - `https://littleskin.cn/sessionserver/...`      → 404（缺 apiPath）
     * - `https://littleskin.cn/api/yggdrasil/...`      → 200 ✓
     *
     * 站点地址与 apiPath 都优先从 headlessmc 的配置读取
     * （`hmc.auth.yggdrasil.url` / `hmc.auth.yggdrasil.apiPath`）。
     */
    private fun yggdrasilAvatarUrl(uuid: String): String? {
        val base = yggdrasilConfig("hmc.auth.yggdrasil.url") ?: return null
        val apiPath = yggdrasilConfig("hmc.auth.yggdrasil.apiPath") ?: "/api/yggdrasil"
        return "${base.trimEnd('/')}${apiPath.trimEnd('/')}" +
            "/sessionserver/session/minecraft/profile/$uuid"
    }

    /**
     * 读 headlessmc 的配置项。
     *
     * headlessmc 的配置来自 `~/.config/headlessmc/config.properties`，
     * 由 `ConfigFileProvider` 加载后注入到各项 `DynamicConfig` 里。
     *
     * 这里直接读文件而不是走 CDI：`YggdrasilConfig` 是通过
     * `Holder<YggdrasilConfig>` 间接注入的，不是可直接 `bean()` 取到的类型。
     * 配置文件路径与 headlessmc 的 `AppFiles.getConfigDir()` 保持一致。
     */
    private fun yggdrasilConfig(key: String): String? {
        // 1) 系统属性优先（便于测试与临时覆盖）
        System.getProperty(key)?.takeIf { it.isNotBlank() }?.let { return it }

        // 2) 读 headlessmc 的配置文件
        val configFile = headlessMcConfigFile() ?: return null
        return runCatching {
            if (!configFile.exists()) return@runCatching null
            configFile.readLines()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /**
     * 定位 headlessmc 的配置文件。
     *
     * 与 headlessmc 的 `DefaultAppFiles.getConfigDir()` 保持一致。
     * 该方法的字节码是：`XdgBaseDirectories.configHome().resolve("headlessmc")`，
     * 即**遵循 XDG 规范**：
     * - Linux：`$XDG_CONFIG_HOME/headlessmc`，未设置时默认 `~/.config/headlessmc`
     *   （实测 headlessmc 3.0.0-RC10-cn.* 的配置就在 `~/.config/headlessmc/config.properties`，
     *   `~/.headlessmc` 并不存在）
     * - Windows：`%APPDATA%/headlessmc`
     * - macOS：`~/Library/Application Support/headlessmc`
     */
    private fun headlessMcConfigFile(): java.io.File? {
        val dir = headlessMcConfigDir() ?: return null
        return java.io.File(dir, "config.properties")
    }

    /**
     * headlessmc 的配置根目录。
     *
     * <p>从 [headlessMcConfigFile] 里拆出来，是因为除了配置文件本身，
     * 还有别的东西要往同一个目录下放（比如 [AvatarCache]）。
     * 拆开之后「配置目录在哪」只有一处定义，避免两边推导规则漂移。
     *
     * @return 无法确定时返回 null（拿不到 user.home 之类的极端情况）
     */
    internal fun configRoot(): java.io.File {
        // 兜底用当前目录：正常环境不会走到这里，但返回一个确定的值
        // 比让调用方处理 null 更省事 —— 缓存目录建不出来时写缓存自然失败，
        // 那条路径已经有错误处理。
        return headlessMcConfigDir() ?: java.io.File(".")
    }

    /**
     * 按平台推导 headlessmc 的配置目录。
     */
    private fun headlessMcConfigDir(): java.io.File? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return when {
            os.contains("win") -> System.getenv("APPDATA")?.let { java.io.File(it, "headlessmc") }
            os.contains("mac") -> java.io.File(System.getProperty("user.home"), "Library/Application Support/headlessmc")
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) java.io.File(xdg, "headlessmc")
                else java.io.File(System.getProperty("user.home"), ".config/headlessmc")
            }
        }
    }
}
