package org.cn.xiaofan.account

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.regex.Pattern

/**
 * 头像加载器。
 *
 * 两条路径：
 *
 * 1. **直链**（Mojang / 离线 Steve）
 *    URL 直接返回 PNG 皮肤贴图（64x64），裁出 (8,8) 处 8x8 的头部即可。
 *
 * 2. **Yggdrasil profile 接口**（皮肤站，如 littleskin）
 *    URL 返回的是 JSON，皮肤地址藏在 base64 编码的 `textures` 字段里。
 *    需要先解析 JSON → base64 解码 → 再取 `SKIN.url`。
 *
 * 判定方式：路径含 `sessionserver/session/minecraft/profile` 的视为 Yggdrasil 接口。
 */
object AvatarLoader {

    /** 头部在皮肤贴图中的位置与尺寸。 */
    private const val HEAD_X = 8
    private const val HEAD_Y = 8
    private const val HEAD_SIZE = 8

    /** 网络超时（毫秒）。头像加载失败不应阻塞界面。 */
    private const val TIMEOUT_MS = 8000

    /** 识别 Yggdrasil profile 接口。 */
    private val PROFILE_ENDPOINT =
        Pattern.compile(".*/sessionserver/session/minecraft/profile/.*")

    /**
     * 从 profile JSON 里提取 base64 的 textures 字段。
     *
     * 实际 JSON 结构（注意字段顺序）：
     * ```json
     * { "properties": [ { "name": "textures", "value": "<base64>" } ] }
     * ```
     * 即先 `"name"` 后 `"value"`，两者之间可能有空白。
     */
    private val TEXTURES_VALUE = Pattern.compile(
        "\"name\"\\s*:\\s*\"textures\"\\s*,\\s*\"value\"\\s*:\\s*\"([^\"]+)\""
    )

    /** 从 base64 解码后的 textures JSON 里提取皮肤地址。 */
    private val SKIN_URL = Pattern.compile(
        "\"SKIN\"\\s*:\\s*\\{\\s*\"url\"\\s*:\\s*\"([^\"]+)\""
    )

    /**
     * 下载并裁出头像。
     *
     * @return 8x8 的头部图，失败时返回 null（界面回退到占位符）
     */
    suspend fun load(url: String): ImageBitmap? = withContext(Dispatchers.IO) {
        try {
            val skinBytes = if (PROFILE_ENDPOINT.matcher(url).matches()) {
                // 两步：profile JSON → 皮肤地址 → 下载皮肤
                val profileJson = downloadBytes(url)?.toString(Charsets.UTF_8)
                    ?: return@withContext null
                val skinUrl = resolveSkinUrl(profileJson) ?: run {
                    System.err.println("[Avatar] 未能从 profile 解析出 SKIN.url: $url")
                    return@withContext null
                }
                downloadBytes(skinUrl)
            } else {
                downloadBytes(url)
            } ?: return@withContext null

            val skin = Image.makeFromEncoded(skinBytes).toComposeImageBitmap()
            cropHead(skin)
        } catch (t: Throwable) {
            // 头像加载失败不该影响主流程，只在控制台留一行线索
            System.err.println("[Avatar] 加载失败 $url: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * 从 Yggdrasil profile JSON 里解析皮肤地址。
     *
     * 结构：
     * ```json
     * { "id": "...", "name": "...",
     *   "properties": [ { "name": "textures", "value": "<base64>" } ] }
     * ```
     * base64 解码后：
     * ```json
     * { "textures": { "SKIN": { "url": "https://..." } } }
     * ```
     *
     * 手写正则而非引入 Jackson：这里只需要两个字段，
     * 而 headlessmc 的 Jackson 版本与 HMYLX 不一定一致，避免依赖冲突。
     */
    private fun resolveSkinUrl(profileJson: String): String? {
        val matcher = TEXTURES_VALUE.matcher(profileJson)
        if (!matcher.find()) return null
        val base64 = matcher.group(1) ?: return null

        val decoded = runCatching {
            String(Base64.getDecoder().decode(base64), Charsets.UTF_8)
        }.getOrNull() ?: return null

        val skinMatcher = SKIN_URL.matcher(decoded)
        return if (skinMatcher.find()) skinMatcher.group(1) else null
    }

    private fun downloadBytes(url: String): ByteArray? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        // Mojang 与部分皮肤站对 UA 有校验，缺失可能返回 403
        connection.setRequestProperty("User-Agent", "HMYLX/1.0")
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 从皮肤贴图裁出头部。
     *
     * 用 Skia 的 Bitmap 做像素级拷贝：
     * 先读出原图头部的 ARGB 像素，写入一张 8x8 的新 Bitmap，再转回 [ImageBitmap]。
     *
     * 不用 [ImageBitmap] 自己写像素，因为 Compose Desktop 没有公开的写像素 API；
     * 走 Skia 是官方支持路径。
     */
    private fun cropHead(skin: ImageBitmap): ImageBitmap {
        // 尺寸不足时（不是合法皮肤）直接返回原图，交给界面缩放
        if (skin.width < HEAD_X + HEAD_SIZE || skin.height < HEAD_Y + HEAD_SIZE) {
            return skin
        }

        val pixels = IntArray(HEAD_SIZE * HEAD_SIZE)
        skin.readPixels(
            buffer = pixels,
            startX = HEAD_X,
            startY = HEAD_Y,
            width = HEAD_SIZE,
            height = HEAD_SIZE,
        )

        val bytes = intArrayToBytes(pixels)
        val headBitmap = org.jetbrains.skia.Bitmap().apply {
            allocN32Pixels(HEAD_SIZE, HEAD_SIZE)
            installPixels(bytes)
        }
        return org.jetbrains.skia.Image.makeFromBitmap(headBitmap).toComposeImageBitmap()
    }

    /** 把 ARGB int 数组按 Skia N32 要求的 BGRA 字节序展开。 */
    private fun intArrayToBytes(pixels: IntArray): ByteArray {
        val bytes = ByteArray(pixels.size * 4)
        pixels.forEachIndexed { index, argb ->
            val offset = index * 4
            bytes[offset] = (argb and 0xFF).toByte()              // B
            bytes[offset + 1] = ((argb shr 8) and 0xFF).toByte()  // G
            bytes[offset + 2] = ((argb shr 16) and 0xFF).toByte() // R
            bytes[offset + 3] = ((argb shr 24) and 0xFF).toByte() // A
        }
        return bytes
    }
}
