package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.AuthProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 离线账号头像来源的测试。
 *
 * <p>回溯：离线账号原先走 Mojang 会话服务，用
 * `UUID.nameUUIDFromBytes("OfflinePlayer:<name>")` 算出的 UUID 去查 ——
 * Mojang 数据库里没有这个 ID，接口返回 204，头像加载失败最终显示成首字母。
 *
 * <p>现在离线账号用内置 Steve 皮肤，不发起任何网络请求。
 */
class OfflineAvatarTest {

    @Test
    fun `离线账号使用内置皮肤而非网络地址`() {
        val profile = AccountResolver.fromAccount(
            name = "小凡",
            provider = AuthProvider.OFFLINE,
            uuid = offlineUuid("小凡"),
        )

        assertTrue(profile.isOffline, "provider 应为离线")
        assertNull(profile.avatarUrl, "离线账号不应有任何网络头像地址")
        assertEquals(
            AvatarSource.BuiltInSkin.STEVE,
            profile.avatar,
            "离线账号应使用内置 Steve 皮肤",
        )
    }

    @Test
    fun `内置皮肤资源存在且可解码`() {
        val path = AvatarSource.BuiltInSkin.STEVE.resourcePath
        val bytes = OfflineAvatarTest::class.java.getResourceAsStream(path)?.use { it.readBytes() }

        assertNotNull(bytes, "内置皮肤资源缺失: $path，检查 src/main/resources$path 是否打进 jar")
        // PNG 魔数
        assertTrue(bytes!!.size > 8, "资源内容为空")
        assertEquals(0x89.toByte(), bytes[0], "不是 PNG（魔数首字节不符）")
        assertEquals('P'.code.toByte(), bytes[1])
        assertEquals('N'.code.toByte(), bytes[2])
        assertEquals('G'.code.toByte(), bytes[3])
    }

    @Test
    fun `正版账号仍走 Mojang 会话服务`() {
        val uuid = "069a79f444e94726a5befca90e38aaf5"  // Notch
        val profile = AccountResolver.fromAccount(
            name = "Notch",
            provider = AuthProvider.DEFAULT,
            uuid = uuid,
        )

        assertEquals(
            AvatarSource.Remote("https://sessionserver.mojang.com/session/minecraft/profile/$uuid"),
            profile.avatar,
            "正版账号应走 Mojang 会话服务",
        )
    }

    /** 与 `AccountService.offlineUuid` 相同的算法。 */
    private fun offlineUuid(name: String): String =
        UUID.nameUUIDFromBytes("OfflinePlayer:$name".toByteArray(Charsets.UTF_8))
            .toString()
            .replace("-", "")
}
