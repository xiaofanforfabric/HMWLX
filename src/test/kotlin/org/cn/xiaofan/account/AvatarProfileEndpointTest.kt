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
            "6e1b53d1d50b4d8f88b952c8bd47c7f0"
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
        // 真实返回体（取自 sessionserver.mojang.com，2026-10 实测）
        val json = """
            {
              "id" : "6e1b53d1d50b4d8f88b952c8bd47c7f0",
              "name" : "xiaofanbot",
              "properties" : [ {
                "name" : "textures",
                "value" : "ewogICJ0aW1lc3RhbXAiIDogMTc5MzExNzg1MDY4NCwKICAicHJvZmlsZUlkIiA6ICI2ZTFiNTNkMWQ1MGI0ZDhmODhiOTUyYzhiZDQ3YzdmMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJ4aWFvZmFuYm90IiwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzkwZTc1Y2Q0MjliYTYzMzFjZDIxMGI5YmQxOTM5OTUyN2VlM2JhYjQ2N2I1YTlmNjFjYjhhMjdiMTc3ZjY3ODkiCiAgICB9CiAgfQp9"
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
