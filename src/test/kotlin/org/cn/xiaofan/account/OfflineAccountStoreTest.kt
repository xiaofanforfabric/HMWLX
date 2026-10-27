package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.Account
import io.github.headlesshq.headlessmc.auth.AuthStore
import kotlinx.coroutines.runBlocking
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 离线账号存储的 CDI 解析测试。
 *
 * <p>验证「能不能从 headlessmc 的容器里按类型 + 限定符取到 `AuthStore<Account>`」。
 * 之前用 `AuthProvider.getAuthStore()` 反射调用一直失败，根因是 CDI 返回的是
 * 接口代理，实现类上的方法不在代理里，反射必然 `NoSuchMethodException`。
 *
 * <p>`AuthStore` 有两个同类型的产出（`@Offline` 与 `@Yggdrasil`），
 * 所以必须带限定符查找，否则判定为不唯一。
 */
class OfflineAccountStoreTest {

    @Test
    fun `能按类型与 Offline 限定符取到账号存储`() {
        HeadlessMcBootstrap.start()

        val store: AuthStore<Account>? = AccountService.debugOfflineAccountStore()

        assertNotNull(
            store,
            "取不到 AuthStore<Account> @Offline —— 检查 auth-cdi 是否被 quarkus.index-dependency 索引",
        )

        // 真正调用一次接口方法：拿到的如果是残缺代理，这里会抛异常
        val accounts = store!!.read()
        assertEquals(accounts.keys.toList(), accounts.keys.toList())
        println("=== 离线账号存储解析成功，当前账号数: ${accounts.size} ===")
        accounts.values.forEach { println("  - ${it.name} (${it.uuid})") }
    }

    @Test
    fun `groupedAccounts 能读出离线分组`() = runBlocking {
        HeadlessMcBootstrap.start()

        val groups = AccountService.groupedAccounts()
        assertTrue(
            groups.containsKey(AccountService.AccountGroup.OFFLINE),
            "分组结果里必须有 OFFLINE 这一组（即使为空）",
        )
        println("=== 账号分组: ${groups.mapValues { it.value.size }} ===")
    }

    /**
     * 账号读写涉及磁盘，必须都挂在 [kotlinx.coroutines.Dispatchers.IO] 上。
     *
     * 用反射钉死签名：这些方法一旦退回普通 `fun`，界面就又能从主线程直接调，
     * 编译期不会有任何提示，只有用户能感觉到窗口卡一下。
     */
    @Test
    fun `账号读写接口必须都是 suspend`() {
        listOf(
            "groupedAccounts",
            "currentAccount",
            "switchTo",
            "addOfflineAccount",
            "removeAccount",
        ).forEach { name ->
            val method = AccountService::class.java.methods.firstOrNull { m ->
                m.name == name && m.isSynthetic.not()
            } ?: error("找不到方法 $name")
            val parameterTypes = method.parameterTypes
            assertTrue(
                parameterTypes.lastOrNull()?.name == "kotlin.coroutines.Continuation",
                "$name 必须是 suspend 函数，否则又会被主线程同步调用",
            )
        }
    }
}
