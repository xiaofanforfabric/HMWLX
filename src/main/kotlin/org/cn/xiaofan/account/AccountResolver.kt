package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.AuthProvider
import io.github.headlesshq.headlessmc.auth.AuthService
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap

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
 */
data class AccountProfile(
    /** 玩家名。 */
    val name: String,
    /** 认证源，见 [AuthProvider] 常量。 */
    val provider: String,
    /** 不带连字符的 UUID。 */
    val uuid: String,
    /** 头像 URL，取不到时为 null（界面回退到默认图标）。 */
    val avatarUrl: String?,
) {
    /** 是否为离线账号。 */
    val isOffline: Boolean get() = provider == AuthProvider.OFFLINE

    /** 是否为正版账号。 */
    val isMicrosoft: Boolean get() = provider == AuthProvider.DEFAULT

    /** 是否为皮肤站账号。 */
    val isYggdrasil: Boolean get() = provider == AuthProvider.YGGDRASIL

    /** 展示用的来源标签。 */
    val sourceLabel: String
        get() = when {
            isMicrosoft -> "正版"
            isYggdrasil -> "皮肤站"
            isOffline -> "离线"
            else -> provider
        }
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
     * headlessmc 的 `AuthService.getAccounts()` 返回已保存的账号列表。
     * 若列表为空，说明用户还没登录过，此时回退到默认离线账号（Steve），
     * 与 headlessmc 自己的行为一致。
     */
    fun current(): AccountProfile {
        val account = runCatching {
            val service = HeadlessMcBootstrap.bean(AuthService::class.java)
            service.accounts.firstOrNull()
        }.getOrNull()

        if (account == null) {
            return AccountProfile(
                name = "Offline",
                provider = AuthProvider.OFFLINE,
                uuid = STEVE_UUID,
                avatarUrl = "$MOJANG_SESSION/$STEVE_UUID",
            )
        }

        return fromAccount(
            name = account.name,
            provider = account.provider,
            uuid = account.uuid,
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
            avatarUrl = avatarUrlFor(provider, cleanUuid),
        )
    }

    /**
     * 按 provider 决定头像地址。
     *
     * 正版 / 离线都走 Mojang：这是唯一稳定且无需鉴权的官方接口。
     * 皮肤站走账号自己记录的 serverUrl（YggdrasilAccount 里保存的），
     * 因为不同皮肤站的纹理接口可能不同，只有它自己的地址才可靠。
     */
    private fun avatarUrlFor(provider: String, uuid: String): String? = when (provider) {
        AuthProvider.DEFAULT, AuthProvider.OFFLINE -> "$MOJANG_SESSION/$uuid"
        AuthProvider.YGGDRASIL -> yggdrasilAvatarUrl(uuid)
        else -> null
    }

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
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val dir = when {
            os.contains("win") -> System.getenv("APPDATA")?.let { java.io.File(it, "headlessmc") }
            os.contains("mac") -> java.io.File(System.getProperty("user.home"), "Library/Application Support/headlessmc")
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) java.io.File(xdg, "headlessmc")
                else java.io.File(System.getProperty("user.home"), ".config/headlessmc")
            }
        } ?: return null
        return java.io.File(dir, "config.properties")
    }
}
