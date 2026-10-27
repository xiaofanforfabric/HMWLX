package org.cn.xiaofan.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 离线账号 UUID 算法的校验。
 *
 * <p>Minecraft 离线模式的 UUID 规则是
 * `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")`，然后去掉连字符。
 * 这个值必须和原版服务端算出来的一致，否则同一个玩家在不同环境里
 * 会得到两个 UUID，服务器白名单、领地数据之类全部对不上。
 *
 * <p>直接调 `AccountService.offlineUuid`（`internal`，测试同模块可见），
 * 而不是在测试里复制一份实现：副本本身就是最容易被连生产代码一起改掉的东西，
 * 那样「交叉验证」等于对着自己验自己，什么都验不出来。
 */
class OfflineUuidTest {

    private fun offlineUuid(name: String): String = AccountService.offlineUuid(name)

    @Test
    fun `已知玩家的离线 UUID 稳定`() {
        // 基线由 JDK 的 UUID.nameUUIDFromBytes 实际算得，锁死防止实现漂移。
        // 离线模式 UUID 是 v3（MD5），第 13 位固定为 3。
        assertEquals("b50ad385829d3141a2167e7d7539ba7f", offlineUuid("Notch"))
        assertEquals("5627dd98e6be3c21b8a8e92344183641", offlineUuid("Steve"))
        assertEquals("d9c9e2a79d863b5bbb7967ab835ee760", offlineUuid("xiaofanbot"))
    }

    @Test
    fun `UUID 格式合法`() {
        val uuid = offlineUuid("xiaofanbot")
        assertEquals(32, uuid.length, "去掉连字符后应为 32 位")
        assertEquals(uuid.lowercase(), uuid, "应全小写")
        assertEquals('3', uuid[12], "v3 UUID 的第 13 位固定为 3")
    }

    @Test
    fun `不同名字得到不同 UUID`() {
        val a = offlineUuid("Alice")
        val b = offlineUuid("Bob")
        assertEquals(false, a == b, "不同名字不应撞 UUID")
    }

    @Test
    fun `大小写不同的名字视为不同玩家`() {
        // Minecraft 用户名本身不区分大小写，但离线 UUID 的算法区分。
        // 这是原版行为，不在这里纠正：改了就和原版服务端对不上。
        assertEquals(false, offlineUuid("Steve") == offlineUuid("steve"))
    }
}
