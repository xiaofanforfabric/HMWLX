package org.cn.xiaofan.version

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 愚人节版本的识别。
 *
 * <p>这些版本在 Mojang 的版本 JSON 里 `type` 写的也是 `snapshot`，
 * 没有任何字段能区分，只能靠版本号对照表。本测试同时锁定三件事：
 * 1. 已知的愚人节版本号能被识别 —— **用官方原始大小写**。
 *    本机目录名照抄版本 id（`20W14infinite`、`23W13a_or_b` 这些是大写的），
 *    只测小写等于在照镜子，锁不住真实路径。
 * 2. 大小写两个方向都能命中。
 * 3. 普通快照**不会**被误判 —— 表写宽了（比如正则匹配 `20w14`）就会出这种错。
 */
class AprilFoolsVersionTest {

    /** 官方原始大小写的版本 id，与 `versions/<id>/` 目录名一致。 */
    private val known = listOf(
        "15w14a",                 // 2015
        "1.RV-Pre1",              // 2016
        "1.RV-Pre2",              // 2016 后续预览
        "3D Shareware v1.34",     // 2019
        "20W14infinite",          // 2020
        "22w13oneblockatatime",   // 2022
        "23W13a_or_b",            // 2023
        "24W14potato",            // 2024
        "25w14craftmine",         // 2025
    )

    @Test
    fun `官方原始大小写的版本都能识别`() {
        known.forEach { id ->
            assertTrue(VersionType.isAprilFools(id), "$id 应被识别为愚人节版本")
        }
    }

    @Test
    fun `全大写与全小写都能命中`() {
        known.forEach { id ->
            assertTrue(VersionType.isAprilFools(id.uppercase()), "${id.uppercase()} 应命中")
            assertTrue(VersionType.isAprilFools(id.lowercase()), "${id.lowercase()} 应命中")
        }
    }

    @Test
    fun `普通快照不会被误判`() {
        val normal = listOf(
            "20w14a",          // 和 20W14infinite 同周，但不是愚人节版
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
        // 背景说明，不是实现细节：如果哪天 Mojang 给了独立 type，
        // 上面那张表就可以删掉，从 from() 直接映射。
        assertTrue(VersionType.from("snapshot") == VersionType.SNAPSHOT)
    }

    @Test
    fun `GameVersion 的 isAprilFools 走同一张表`() {
        // type 仍是 SNAPSHOT —— 这正是必须靠版本号额外判断的原因
        val fools = GameVersion(
            id = "20W14infinite",
            type = VersionType.SNAPSHOT,
            rawType = "snapshot",
        )
        assertTrue(fools.isAprilFools)
        assertFalse(fools.copy(id = "20w14a").isAprilFools)
    }
}
