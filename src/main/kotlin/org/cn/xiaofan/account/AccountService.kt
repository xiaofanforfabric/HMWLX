package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.Account
import io.github.headlesshq.headlessmc.auth.AuthProvider
import io.github.headlesshq.headlessmc.auth.AuthService
import io.github.headlesshq.headlessmc.auth.LastUsedAccountService
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import java.util.UUID

/**
 * 账号列表与切换。
 *
 * <p>数据全部来自 headlessmc 的 CDI bean，本层只做三件事：
 * 把 [Account] 适配成 [AccountProfile]、按认证源分组、调用
 * [LastUsedAccountService] 切换当前账号。
 *
 * <p>不自己维护账号列表：headlessmc 的 `AuthProvider` 已经把账号持久化到它自己的
 * 存储里，重复维护一份必然两边不同步。增加账号走 `AuthProvider.getAuthStore().add()`，
 * 切换走 `LastUsedAccountService.setLastUsedAccount()`，都是它的官方接口。
 */
object AccountService {

    /**
     * 账号分组。界面按 [AccountGroup] 的顺序分组展示。
     *
     * 顺序固定为「微软 → 离线 → 外置」，与主流启动器一致：
     * 正版放最上面是用户最常找的，离线次之，外置登录属于进阶用法。
     */
    enum class AccountGroup(val provider: String) {
        /** 微软正版账号。 */
        MICROSOFT(AuthProvider.DEFAULT),

        /** 离线账号。 */
        OFFLINE(AuthProvider.OFFLINE),

        /** 第三方外置登录（Yggdrasil / authlib-injector 兼容）。 */
        YGGDRASIL(AuthProvider.YGGDRASIL),
        ;

        companion object {
            /** 按 provider 字符串找分组，未知来源归到外置登录。 */
            fun from(provider: String): AccountGroup =
                entries.firstOrNull { it.provider == provider } ?: YGGDRASIL
        }
    }

    /**
     * 读全部已保存账号，按 [AccountGroup] 分组。
     *
     * 同组内保持 headlessmc 返回的顺序，不额外排序：它内部按名字排序过，
     * 再排一次会让用户找不到上次的位置。
     *
     * 容器未就绪或读取失败时返回空分组，不抛异常 —— 界面要能正常显示「无账号」，
     * 而不是崩掉。
     */
    fun groupedAccounts(): Map<AccountGroup, List<AccountProfile>> {
        val accounts = runCatching {
            HeadlessMcBootstrap.bean(AuthService::class.java).accounts
        }.getOrElse { emptyList() }

        val profiles = accounts.map { it.toProfile() }
        val result = LinkedHashMap<AccountGroup, List<AccountProfile>>()
        // 三组都填上，即使为空：界面要能画出「+ 登录离线账号」这类入口
        AccountGroup.entries.forEach { group ->
            result[group] = profiles.filter { AccountGroup.from(it.provider) == group }
        }
        return result
    }

    /**
     * 当前使用的账号。
     *
     * 优先读 [LastUsedAccountService]，这是 headlessmc `account select` 命令写的同一个位置，
     * 保证 GUI 切过的账号在 CLI 里也生效。
     *
     * 读不到时回退到列表第一个，再回退到默认离线账号（Steve）——
     * 与 headlessmc 自己的行为一致。
     */
    fun currentAccount(): AccountProfile {
        runCatching {
            HeadlessMcBootstrap.bean(LastUsedAccountService::class.java)
                .lastUsedAccount
                .orElse(null)
        }.getOrNull()?.let { return it.toProfile() }

        runCatching {
            HeadlessMcBootstrap.bean(AuthService::class.java).accounts.firstOrNull()
        }.getOrNull()?.let { return it.toProfile() }

        return Account.defaultOfflineAccount().toProfile()
    }

    /**
     * 切换当前账号。
     *
     * 走 [LastUsedAccountService]，与 `account select` 命令是同一条路径。
     *
     * @return 是否切换成功。失败时界面应提示，不能假装成功。
     */
    fun switchTo(profile: AccountProfile): Boolean = runCatching {
        val service = HeadlessMcBootstrap.bean(LastUsedAccountService::class.java)
        val account = findRaw(profile) ?: error("账号不存在: ${profile.name}")
        service.setLastUsedAccount(account)
        true
    }.getOrDefault(false)

    /**
     * 新增离线账号。
     *
     * 离线账号不需要任何验证，指定名字即可，因此可以直接构造 [Account] 写进
     * `AuthStore`，不必经过需要 Console 交互的 `Authenticator`。
     *
     * UUID 按 Minecraft 离线模式的规则生成：`UUID.nameUUIDFromBytes("OfflinePlayer:<name>")`。
     * 这与原版服务端离线模式算出来的 UUID 一致，同一个名字在不同启动器里会得到同一个
     * UUID，服务器白名单之类的场景才对得上。
     *
     * @return 新建的账号，失败时为 null
     */
    fun addOfflineAccount(name: String): AccountProfile? {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return null

        return runCatching {
            val uuid = offlineUuid(trimmed)
            val account = Account(
                AuthProvider.OFFLINE,  // provider
                trimmed,               // name
                uuid,                  // uuid
                "",                    // token：离线账号没有令牌，传空串而非 null
                Account.TYPE_MSA,      // type：与 headlessmc 的 defaultOfflineAccount 一致
                "",                    // xuid：仅正版有，传空串
            )

            val provider = HeadlessMcBootstrap.bean(AuthService::class.java)
                .getProvider(AuthProvider.OFFLINE)
                .orElse(null)
                ?: error("headlessmc 未启用离线认证源")

            // getAuthStore 只在具体实现上暴露，接口没有，所以按实现类型找
            val store = runCatching {
                provider.javaClass.getMethod("getAuthStore").invoke(provider)
            }.getOrNull() ?: error("无法取得离线认证源的存储")

            @Suppress("UNCHECKED_CAST")
            val authStore = store as io.github.headlesshq.headlessmc.auth.AuthStore<Account>
            authStore.add(account.name, account)

            account.toProfile()
        }.getOrNull()
    }

    /**
     * 删除账号。
     *
     * @return 是否删除成功
     */
    fun removeAccount(profile: AccountProfile): Boolean = runCatching {
        val account = findRaw(profile) ?: error("账号不存在: ${profile.name}")
        val provider = HeadlessMcBootstrap.bean(AuthService::class.java)
            .getProvider(profile.provider)
            .orElse(null)
            ?: error("找不到认证源: ${profile.provider}")
        provider.removeAccount(account)
        true
    }.getOrDefault(false)

    /**
     * 把界面持有的 [AccountProfile] 换回 headlessmc 的 [Account]。
     *
     * 按 name + provider 匹配：uuid 在离线账号上可能被用户改过（换名后重算），
     * 而 name 是 headlessmc 存储里的主键。
     */
    private fun findRaw(profile: AccountProfile): Account? = runCatching {
        HeadlessMcBootstrap.bean(AuthService::class.java)
            .accounts
            .firstOrNull { it.name == profile.name && it.provider == profile.provider }
    }.getOrNull()

    /** 离线模式的 UUID 算法，与 Minecraft 服务端一致。 */
    private fun offlineUuid(name: String): String =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
            .toString()
            .replace("-", "")
}

/**
 * [Account] → [AccountProfile]。
 *
 * 头像地址由 [AccountResolver.fromAccount] 统一解析，避免两处各写一套推导规则。
 */
private fun Account.toProfile(): AccountProfile =
    AccountResolver.fromAccount(name = name, provider = provider, uuid = uuid)
