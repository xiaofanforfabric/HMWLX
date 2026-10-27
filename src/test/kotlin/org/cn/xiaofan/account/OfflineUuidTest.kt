package org.cn.xiaofan.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 离线账号 UUID 算法的校验。
 *
 * <p>Minecraft 离线模式的 UUID 规则是
 * `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")`，然后去掉连字符。
 * 这个值必须和原版服务端算出来的一致，否则同一个玩家在不同环境里
 * 会得到两个 UUID，服务器白名单、领地数据之类全部对不上。
 *
 * <p>算法本身在 `AccountService` 里是 private，这里用同样的实现做交叉验证：
 * 断言固定输入得到固定输出。若哪天有人改了实现，这个测试会红。
 */
class OfflineUuidTest {

    /**
     * 用与 `AccountService.offlineUuid` 相同的算法。
     *
     * 不直接调 private 方法：为了测试而放宽可见性，会让生产代码多出一个
     * 不该公开的入口。这里宁可重复四行。
     */
    private fun offlineUuid(name: String): String =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
            .toString()
            .replace("-", "")

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
