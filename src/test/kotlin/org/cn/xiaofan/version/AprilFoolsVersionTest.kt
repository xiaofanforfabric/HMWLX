package org.cn.xiaofan.version

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 愚人节版本的识别。
 *
 * <p>这些版本在 Mojang 的版本 JSON 里 `type` 写的也是 `snapshot`，
 * 没有任何字段能区分，只能靠版本号对照表。本测试同时锁定两件事：
 * 1. 已知的愚人节版本号能被识别
 * 2. 普通快照**不会**被误判 —— 表写宽了（比如用正则匹配 `20w14`）就会出这种错
 */
class AprilFoolsVersionTest {

    @Test
    fun `已知的愚人节版本都能识别`() {
        val known = listOf(
            "15w14a",
            "1.rv-pre1",
            "3D Shareware v1.34",
            "20w14infinite",
            "22w13oneblockatatime",
            "23w13a_or_b",
            "24w14potato",
            "25w14craftmine",
        )
        known.forEach { id ->
            assertTrue(VersionType.isAprilFools(id), "$id 应被识别为愚人节版本")
        }
    }

    @Test
    fun `大小写不敏感`() {
        assertTrue(VersionType.isAprilFools("20W14INFINITE"))
        assertTrue(VersionType.isAprilFools("3D Shareware v1.34"))
    }

    @Test
    fun `普通快照不会被误判`() {
        val normal = listOf(
            "20w14a",          // 和 20w14infinite 同周，但不是愚人节版
            "23w13a",
            "24w14a",
            "25w14a",
            "1.21.4",
            "1.21.4-rc1",
            "fabric-loader-0.16.9-1.21.4",
        )
        normal.forEach { id ->
            assertFalse(VersionType.isAprilFools(id), "$id 不应被识别为愚人节版本")
        }
    }

    @Test
    fun `愚人节版本的类型字段仍是 snapshot`() {
        // 这条是背景说明，不是实现细节：如果哪天 Mojang 给了独立 type，
        // 上面那张表就可以删掉，从 from() 直接映射。
        assertTrue(VersionType.from("snapshot") == VersionType.SNAPSHOT)
    }

    @Test
    fun `GameVersion 的 isAprilFools 走同一张表`() {
        val fools = GameVersion(
            id = "20w14infinite",
            type = VersionType.SNAPSHOT,
            rawType = "snapshot",
        )
        assertTrue(fools.isAprilFools)
        assertFalse(fools.copy(id = "20w14a").isAprilFools)
    }
}
