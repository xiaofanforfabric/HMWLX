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
 * 判定方式：路径含 `session/minecraft/profile`（前面可带可选的 `sessionserver/`）
 * 的视为 profile 接口 —— Mojang 与皮肤站两家都命中，见 [PROFILE_ENDPOINT]。
 *
 * 失败时返回 null，由界面回退到首字母占位。失败原因打到 stderr：
 * 「没皮肤」（204/404）静默，「被限流」（429）等真失败会留一行。
 */
object AvatarLoader {

    /** 头部在皮肤贴图中的位置与尺寸。 */
    private const val HEAD_X = 8
    private const val HEAD_Y = 8
    private const val HEAD_SIZE = 8

    /** 网络超时（毫秒）。头像加载失败不应阻塞界面。 */
    private const val TIMEOUT_MS = 8000

    /** Mojang 在短时间内重复请求时会返回这个状态码。 */
    private const val HTTP_TOO_MANY_REQUESTS = 429

    /**
     * 识别 profile 接口（返回 JSON，需要两步解析）。
     *
     * 两家的路径不同，必须都覆盖：
     * - Mojang 官方：`sessionserver.mojang.com/session/minecraft/profile/<uuid>`
     *   —— 注意路径里**没有** `sessionserver` 这一段，域名里才有
     * - Yggdrasil 皮肤站：`<站点>/api/yggdrasil/sessionserver/session/minecraft/profile/<uuid>`
     *   —— 路径里多一层 `sessionserver`
     *
     * 早期只写了后者的形式，导致正版账号的 JSON 被当成 PNG 去解码，
     * 报 `Failed to Image::makeFromEncoded`，头像退化成首字母。
     */
    private val PROFILE_ENDPOINT =
        Pattern.compile(".*/(sessionserver/)?session/minecraft/profile/.*")

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
    suspend fun load(url: String): ImageBitmap? = load(AvatarSource.Remote(url))

    /**
     * 按来源加载头像。
     *
     * 内置皮肤直接读 jar 里的资源，不产生任何网络请求。
     */
    suspend fun load(source: AvatarSource?): ImageBitmap? = withContext(Dispatchers.IO) {
        when (source) {
            null, AvatarSource.Unavailable -> null
            is AvatarSource.BuiltInSkin -> loadBuiltIn(source.resourcePath)
            is AvatarSource.Remote -> loadRemote(source.url)
        }
    }

    /** 读内置皮肤并裁出头部。 */
    private fun loadBuiltIn(resourcePath: String): ImageBitmap? = try {
        val bytes = AvatarLoader::class.java.getResourceAsStream(resourcePath)?.use { it.readBytes() }
        if (bytes == null) {
            System.err.println("[Avatar] 内置皮肤资源缺失: $resourcePath")
            null
        } else {
            cropHead(Image.makeFromEncoded(bytes).toComposeImageBitmap())
        }
    } catch (t: Throwable) {
        System.err.println("[Avatar] 内置皮肤加载失败 $resourcePath: ${t.javaClass.simpleName}: ${t.message}")
        null
    }

    /** 从网络加载皮肤并裁出头部。 */
    private fun loadRemote(url: String): ImageBitmap? {
        var failure: String? = null
        return try {
            val skinBytes = if (PROFILE_ENDPOINT.matcher(url).matches()) {
                // 两步：profile JSON → 皮肤地址 → 下载皮肤
                // null 有两种：没有皮肤（204/404）或真失败，后者已写进 failure
                val profileJson = downloadBytes(url) { failure = it }?.toString(Charsets.UTF_8)
                    ?: return failOrNot(failure)
                // 没配皮肤：Mojang 对「没换过皮肤」的账号就是这么返回的，不算失败
                val skinUrl = resolveSkinUrl(profileJson) ?: return null
                downloadBytes(skinUrl) { failure = it }
            } else {
                downloadBytes(url) { failure = it }
            } ?: return failOrNot(failure)

            val skin = Image.makeFromEncoded(skinBytes).toComposeImageBitmap()
            cropHead(skin)
        } catch (t: Throwable) {
            // 头像加载失败不该影响主流程，只在控制台留一行线索
            System.err.println("[Avatar] 加载失败 $url: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    /**
     * 失败原因要不要往上报。
     *
     * 没有原因就是「这账号本来就没皮肤」（接口 204 / 404），正常情况，静默返回 null；
     * 有原因说明是真失败（429、超时、403），打一行日志再返回 null。
     * 两种情况对界面来说都是「没有头像」，区别只在控制台。
     */
    private fun failOrNot(reason: String?): ImageBitmap? {
        if (reason != null) System.err.println("[Avatar] $reason")
        return null
    }

    /**
     * 仅供测试：暴露 profile JSON 的皮肤地址解析。
     *
     * 解析逻辑是头像链路里最容易出错的一环（字段顺序、base64、嵌套结构），
     * 用真实返回体测它比测 URL 字符串有意义得多。
     */
    internal fun debugResolveSkinUrl(profileJson: String): String? = resolveSkinUrl(profileJson)

    /** 仅供测试：判定一个 URL 是否走两步解析。 */
    internal fun debugIsProfileEndpoint(url: String): Boolean =
        PROFILE_ENDPOINT.matcher(url).matches()

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
     * 而 headlessmc 的 Jackson 版本与 HMWLX 不一定一致，避免依赖冲突。
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

    /**
     * 下载字节，失败时返回 null。
     *
     * 失败原因通过 [onFailure] 回调交给调用方：仅凭「返回 null」无法区分
     * 404（这账号没皮肤，正常）、429（请求太快，等一下就好）和 403（UA 被拦）。
     */
    private fun downloadBytes(url: String, onFailure: (String) -> Unit): ByteArray? {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.instanceFollowRedirects = true
        // Mojang 与部分皮肤站对 UA 有校验，缺失可能返回 403
        connection.setRequestProperty("User-Agent", "HMWLX/1.0")
        return try {
            val code = connection.responseCode
            when {
                code == HttpURLConnection.HTTP_OK -> connection.inputStream.use { it.readBytes() }

                code == HTTP_TOO_MANY_REQUESTS -> {
                    onFailure("请求过于频繁（429），稍后重试: $url")
                    null
                }

                // 204 / 404：Mojang 对没有皮肤的账号就是这么回的，不是错误
                code == HttpURLConnection.HTTP_NO_CONTENT || code == HttpURLConnection.HTTP_NOT_FOUND -> null

                else -> {
                    onFailure("HTTP $code: $url")
                    null
                }
            }
        } catch (t: Throwable) {
            onFailure("下载失败 ${t.javaClass.simpleName}: ${t.message}")
            null
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
