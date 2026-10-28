package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.AuthProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * 头像加载失败的分类。
 *
 * <p>回溯：`downloadBytes` 只把 200 当成功，其余状态码一律吞成 null，
 * 界面上和「这账号没皮肤」长得一模一样。Mojang 的 profile 接口对短时间内的
 * 重复请求会回 429，正版账号列表一刷新就是几行并发请求 —— 结果就是
 * 「头像时有时无」，控制台里只有一行 `加载失败`，连是被限流了都看不出来。
 *
 * <p>现在 204 / 404 被当成「没有皮肤」静默放过，429 / 其它非 200 才留一行日志。
 * 这里不联网，只钉解析与「正版地址一定命中两步解析」这两条不变式。
 */
class AvatarErrorTest {

    @Test
    fun `没有 textures 字段的返回体不算失败`() {
        // 账号存在但没配皮肤时，Mojang 返回的正是这种结构
        val json = """{"id":"5116c225ff07399689019e809826e635","name":"TestPlayer1","properties":[]}"""

        assertNull(AvatarLoader.debugResolveSkinUrl(json), "没有 textures 应该解析出 null")
    }

    @Test
    fun `textures 不是合法 base64 时返回 null 而不是抛异常`() {
        val json = """
            {"properties":[{"name":"textures","value":"这不是 base64 啊"}]}
        """.trimIndent()

        assertNull(AvatarLoader.debugResolveSkinUrl(json))
    }

    @Test
    fun `正版账号一定有头像地址，且命中两步解析`() {
        // 两条不变式：地址要构造出来（否则根本没得加载），
        // 且要命中 profile 正则（否则 JSON 会被当 PNG 解码）。
        val uuid = "069a79f444e94726a5befca90e38aaf5"
        val source = AccountResolver.debugAvatarSource(AuthProvider.DEFAULT, uuid)

        assertTrue(source is AvatarSource.Remote, "正版账号的头像来源应为网络地址: $source")
        val url = (source as AvatarSource.Remote).url
        assertEquals("https://sessionserver.mojang.com/session/minecraft/profile/$uuid", url)
        assertTrue(AvatarLoader.debugIsProfileEndpoint(url), "正版 profile 地址必须走两步解析: $url")
    }

    @Test
    fun `取不到头像的来源不会再发请求`() {
        // Unavailable 是「试过了、别再试」的标记，界面拿到它就该安静显示首字母
        val profile = AccountProfile(
            name = "Notch",
            provider = AuthProvider.DEFAULT,
            uuid = "069a79f444e94726a5befca90e38aaf5",
            avatar = AvatarSource.Unavailable,
        )

        assertNull(profile.avatarUrl, "不可用的头像不应暴露网络地址，否则界面会当直链去下")
    }
}
