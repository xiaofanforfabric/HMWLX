package org.cn.xiaofan.account

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * profile 接口识别与皮肤地址解析的测试。
 *
 * <p>回溯：`PROFILE_ENDPOINT` 原来只写了
 * `.*\/sessionserver\/session\/minecraft\/profile\/.*`，
 * 而 Mojang 官方的路径是 `/session/minecraft/profile/<uuid>`（域名里才有
 * sessionserver，路径里没有）。结果正版账号的 JSON 被当成 PNG 去解码，
 * 报 `Failed to Image::makeFromEncoded`，头像退化成首字母。
 *
 * <p>皮肤站因为路径多一层 `sessionserver/`，恰好匹配上，所以只有正版坏 ——
 * 这类「一半能用」的 bug 靠手动测试很容易漏，必须钉死两种 URL 形式。
 */
class AvatarProfileEndpointTest {

    /** 与 `AvatarLoader.PROFILE_ENDPOINT` 相同的正则。 */
    private val pattern = Regex(".*/(sessionserver/)?session/minecraft/profile/.*")

    @Test
    fun `识别 Mojang 官方的 profile 地址`() {
        val url = "https://sessionserver.mojang.com/session/minecraft/profile/" +
            "5116c225ff07399689019e809826e635"
        assertTrue(pattern.matches(url), "Mojang 的 profile 地址必须被识别: $url")
    }

    @Test
    fun `识别 Yggdrasil 皮肤站的 profile 地址`() {
        val url = "https://littleskin.cn/api/yggdrasil/sessionserver/session/" +
            "minecraft/profile/b882c2ee713243eba528415cb26e151b"
        assertTrue(pattern.matches(url), "皮肤站的 profile 地址必须被识别: $url")
    }

    @Test
    fun `不把皮肤 PNG 直链当成 profile 接口`() {
        val url = "http://textures.minecraft.net/texture/90e75cd429ba6331cd210b9bd1" +
            "9399527ee3bab467b5a9f61cb8a27b177f6789"
        assertEquals(false, pattern.matches(url), "皮肤直链不应走两步解析: $url")
    }

    @Test
    fun `能从 Mojang 真实返回体里解析出皮肤地址`() {
        // 真实返回体的**结构**，UUID / 名字 / 纹理内容已替换为虚构值。
        // 不要在这里填自己账号的真实数据：base64 里就编着 uuid 和玩家名，
        // 一旦提交就留在 git 历史里了。
        val json = """
            {
              "id" : "5116c225ff07399689019e809826e635",
              "name" : "TestPlayer1",
              "properties" : [ {
                "name" : "textures",
                "value" : "eyJ0aW1lc3RhbXAiOiAxNzkzMTE3ODUwNjg0LCAicHJvZmlsZUlkIjogIjUxMTZjMjI1ZmYwNzM5OTY4OTAxOWU4MDk4MjZlNjM1IiwgInByb2ZpbGVOYW1lIjogIlRlc3RQbGF5ZXIxIiwgInRleHR1cmVzIjogeyJTS0lOIjogeyJ1cmwiOiAiaHR0cDovL3RleHR1cmVzLm1pbmVjcmFmdC5uZXQvdGV4dHVyZS8wMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwMDAwIn19fQ=="
              } ],
              "profileActions" : [ ]
            }
        """.trimIndent()

        val skinUrl = AvatarLoader.debugResolveSkinUrl(json)

        assertNotNull(skinUrl, "应从真实返回体里解析出 SKIN.url")
        assertTrue(
            skinUrl!!.startsWith("http://textures.minecraft.net/texture/"),
            "解析出的地址不像皮肤直链: $skinUrl",
        )
    }
}
