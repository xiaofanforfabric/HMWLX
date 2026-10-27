package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.Account
import io.github.headlesshq.headlessmc.auth.AuthProvider
import io.github.headlesshq.headlessmc.auth.AuthService
import io.github.headlesshq.headlessmc.auth.AuthStore
import io.github.headlesshq.headlessmc.auth.LastUsedAccountService
import io.github.headlesshq.headlessmc.auth.cdi.Offline
import jakarta.enterprise.util.TypeLiteral
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import java.lang.reflect.Proxy
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
     * @return 成功时返回新建账号；失败时返回带原因的错误
     */
    fun addOfflineAccount(name: String): OfflineAccountResult {
        val trimmed = name.trim()

        validateName(trimmed)?.let { return OfflineAccountResult.InvalidName(it) }

        // 重名直接拒绝：headlessmc 的 AuthStore 以名字为键，同名会覆盖已有账号，
        // 用户的旧 UUID 会悄悄丢掉。反过来说，同名账号的 UUID 本来就一样，
        // 再存一份也没有意义。
        val exists = groupedAccounts()[AccountGroup.OFFLINE]
            .orEmpty()
            .any { it.name.equals(trimmed, ignoreCase = true) }
        if (exists) return OfflineAccountResult.DuplicateName(trimmed)

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

            offlineAccountStore().add(account.name, account)

            OfflineAccountResult.Success(account.toProfile())
        }.getOrElse { OfflineAccountResult.Failed(it.message ?: it.javaClass.simpleName) }
    }

    /**
     * 取离线账号的存储。
     *
     * `AuthStore` 由 `AuthFactory.createOfflineAccountStore` 产出，带 `@Produces` 与
     * `@Offline` 两个注解 —— 因此必须按「泛型类型 + 限定符」查找，只看类型会撞上
     * Yggdrasil 的同类型 bean，判定为不唯一。
     *
     * 不要走 `AuthProvider.getAuthStore()`：CDI 返回的是代理对象，代理上只有接口
     * 声明的方法，`getAuthStore` 是 `OfflineAuthProvider` 的实现类方法，反射调用
     * 会抛 `NoSuchMethodException`，表现为「无法取得离线认证源的存储」。
     */
    private fun offlineAccountStore(): AuthStore<Account> =
        HeadlessMcBootstrap.beanOfType(
            typeLiteral = object : TypeLiteral<AuthStore<Account>>() {},
            typeName = "AuthStore<Account> @Offline",
            qualifiers = arrayOf(offlineQualifier()),
        )

    /**
     * 取 `io.github.headlesshq.headlessmc.auth.cdi.Offline` 限定符的实例。
     *
     * Java 的注解是接口，**不能** `newInstance()` 实例化（那会抛
     * `NoSuchMethodException: Offline.<init>()`）。CDI 的 `select` 需要的是
     * 注解实例，标准做法是用动态代理伪造一个：所有 member 方法返回默认值，
     * `annotationType()` 返回注解类本身，`equals`/`hashCode` 与真正的注解实例一致。
     *
     * `@Offline` 没有成员，所以代理只需要处理
     * `annotationType()`、`equals`、`hashCode`、`toString` 四个方法。
     */
    private fun offlineQualifier(): Annotation =
        Proxy.newProxyInstance(
            Offline::class.java.classLoader,
            arrayOf(Offline::class.java),
        ) { proxy, method, args ->
            when (method.name) {
                "annotationType" -> Offline::class.java
                "equals" -> proxy === (args?.firstOrNull())
                "hashCode" -> 0
                "toString" -> "@${Offline::class.java.name}()"
                // 无成员注解，走到这里说明引用了不存在的属性
                else -> error("Unexpected annotation member: ${method.name}")
            }
        } as Annotation

    /**
     * 仅供测试：暴露 [offlineAccountStore] 的结果，用于验证 CDI 解析链路。
     *
     * 失败时把原因打到 stdout —— 否则测试只能看到「取不到」，无法定位是
     * 索引缺失、限定符不匹配还是别的原因。
     */
    internal fun debugOfflineAccountStore(): AuthStore<Account>? =
        runCatching { offlineAccountStore() }
            .onFailure { it.printStackTrace() }
            .getOrNull()

    /**
     * 校验离线账号的用户名。
     *
     * 规则：
     * - 长度 1–16（不能为空），按 [String.length]（UTF-16 code unit）计算。
     *   Minecraft 协议的字符串长度上限就是这个算法，`Login Start` 的名字字段是
     *   `String (16)`，所以上限与协议保持一致。emoji 等代理对占 2 个单位，
     *   也是协议的算法，不是缺陷。
     * - 字符集：除「控制字符与格式字符」外全部放行 —— 中文、日文、韩文、
     *   emoji、符号都允许。原版服务端在离线模式下不做字符集校验，而协议本身
     *   规定字符串是 UTF-8 编码的 Unicode，因此中文名在协议层是合法的。
     *
     * 放行中文是实际需求：部分服务器（如 2b2t.xin）明确要求离线玩家用中文名加入。
     *
     * 只挡两类字符，理由都是功能性的而非风格：
     * - 控制字符（`Character.isISOControl`）：`\u0000` 之类会破坏协议字符串，
     *   或让名字在界面上无法显示。
     * - 格式字符（`Character.FORMAT`）：零宽字符、双向覆盖符等，肉眼不可见，
     *   会让两个看起来一样的名字对应不同 UUID，属于典型的名字欺骗手段。
     *
     * @return 不合法时返回原因，合法时返回 null
     */
    private fun validateName(name: String): String? {
        if (name.isEmpty()) return "用户名不能为空"
        if (name.length > 16) return "用户名最多 16 个字符"

        val bad = name.firstOrNull { it.isIllegalNameChar() }
        if (bad != null) {
            return "不能包含控制字符或不可见字符（U+%04X）".format(bad.code)
        }
        return null
    }

    /** 是否为不允许出现在用户名里的字符。 */
    private fun Char.isIllegalNameChar(): Boolean =
        isISOControl() || Character.getType(this) == Character.FORMAT.toInt()

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
 * 新增离线账号的结果。
 *
 * 用 sealed 类型而不是 `AccountProfile?`：界面对每种失败要给出不同的提示
 * （名字不合法 / 重名 / 存储写不进去），只回 null 的话用户只能看到「失败」，
 * 不知道要改什么。
 */
sealed interface OfflineAccountResult {
    /** 创建成功。 */
    data class Success(val profile: AccountProfile) : OfflineAccountResult

    /** 用户名不符合规则。`reason` 可直接展示给用户。 */
    data class InvalidName(val reason: String) : OfflineAccountResult

    /** 该名字的离线账号已存在。 */
    data class DuplicateName(val name: String) : OfflineAccountResult

    /** 写入失败，`reason` 为底层原因。 */
    data class Failed(val reason: String) : OfflineAccountResult
}

/**
 * [Account] → [AccountProfile]。
 *
 * 头像地址由 [AccountResolver.fromAccount] 统一解析，避免两处各写一套推导规则。
 */
private fun Account.toProfile(): AccountProfile =
    AccountResolver.fromAccount(name = name, provider = provider, uuid = uuid)
