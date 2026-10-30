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
     * 下载并裁出头像（只有 URL，没有账号身份，不写缓存）。
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

    /**
     * 按账号加载头像，**带磁盘缓存，先本地后网络**。
     *
     * <h2>为什么必须先给缓存</h2>
     *
     * 网络请求要几十毫秒到几秒（DNS、TLS、Mojang 偶尔几百毫秒才响应）。
     * 如果等网络返回后才显示头像，用户会在这段时间看到首字母占位 ——
     * 明明本地就有上次缓存的图，却先闪一下空白再跳成头像，观感很差，
     * 像是加载失败了一次。
     *
     * 所以拆成两步：
     * 1. **立刻**回调缓存（如果有），界面马上有头像
     * 2. 后台刷新，成功后回调新头像并写盘；失败就什么都不做
     *
     * 这样两种情况都好看：
     * - 有缓存：头像从头到尾都在，只在皮肤真变了时静默换图
     * - 没缓存：首字母占位 → 网络返回后变头像（无法避免，但只有第一次）
     *
     * <h2>为什么不做 TTL</h2>
     *
     * 头像的唯一失效信号是「用户换了皮肤」，而我们无从感知这个事件。
     * 每次启动都尝试刷新是最保守的策略 —— 有网就跟上，没网就吃老本。
     *
     * <p>内置皮肤（离线账号）不走这条路：它就在 jar 里，直接读。
     *
     * @param profile 账号信息，用 [AccountProfile.avatarKey] 定位缓存
     * @param onCached 缓存命中时立刻回调。在主线程调用，方便直接更新 Compose 状态。
     * @param onRefreshed 网络刷新成功时回调。同样在主线程。
     * @return 最终的头像。缓存命中的情况下这是缓存图（刷新结果通过
     *   [onRefreshed] 另外给出），缓存未命中时是网络结果。
     */
    suspend fun loadCached(
        profile: AccountProfile,
        onCached: (ImageBitmap) -> Unit = {},
        onRefreshed: (ImageBitmap) -> Unit = {},
    ): ImageBitmap? = withContext(Dispatchers.IO) {
        val source = profile.avatar

        // ---- 内置皮肤：也是「立刻可显示」，走同一个回调 ----
        // 这里必须回调 onCached，而不是只返回：
        // 调用方通常写成 `loadCached(profile, onCached = { avatar = it })`
        // 然后忽略返回值（因为它们不 await 结果，只依赖回调更新状态）。
        // 早期版本在这里直接 return 不回调，导致离线账号的头像
        // 永远是 null，界面退化成首字母占位 —— 内置的 Steve 明明就在 jar 里。
        if (source is AvatarSource.BuiltInSkin) {
            val builtIn = loadBuiltIn(source.resourcePath)
            if (builtIn != null) {
                withContext(Dispatchers.Main) { onCached(builtIn) }
            }
            return@withContext builtIn
        }

        // ---- 第一步：本地缓存，立刻交给界面 ----
        // 放在网络请求之前，这是整个策略的核心：用户先看到头像，
        // 拿到新图之后才静默替换。
        val cached = AvatarCache.read(profile)
        if (cached != null) {
            withContext(Dispatchers.Main) { onCached(cached) }
        }

        // 没有可用网络来源：有缓存就够了，没有也只能返回 null。
        // 这种情况理论上少见（来源是推导出来的，不会突然消失），
        // 但如果 provider 配置变了，旧缓存仍比什么都没有强。
        if (source !is AvatarSource.Remote) {
            return@withContext cached
        }

        // ---- 第二步：后台刷新 ----
        val png = fetchHeadPng(source.url)
        if (png != null) {
            // 刷新成功：写盘 + 通知界面替换。
            // 写失败不影响显示，所以不看 write 的结果。
            AvatarCache.write(profile, png)
            val fresh = decodeHead(png)
            if (fresh != null) {
                withContext(Dispatchers.Main) { onRefreshed(fresh) }
                return@withContext fresh
            }
        } else if (cached != null) {
            System.err.println("[Avatar] 刷新失败，使用缓存: ${profile.name}")
        }

        // 刷新失败：有缓存就返回缓存（界面已经显示它了），否则 null。
        cached
    }

    /**
     * 下载皮肤并裁出头部，返回**头部的 PNG 字节**。
     *
     * <p>返回字节而不是 `ImageBitmap`，是为了让调用方既能直接显示、
     * 也能原样写进缓存 —— 不用为了存盘再把 `ImageBitmap` 编码回 PNG。
     */
    private fun fetchHeadPng(url: String): ByteArray? {
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
            encodeHead(cropHead(skin))
        } catch (t: Throwable) {
            // 头像加载失败不该影响主流程，只在控制台留一行线索
            System.err.println("[Avatar] 加载失败 $url: ${t.javaClass.simpleName}: ${t.message}")
            null
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
    private fun loadRemote(url: String): ImageBitmap? =
        fetchHeadPng(url)?.let { decodeHead(it) }

    /**
     * 把裁好的头部编码成 PNG。
     *
     * <p>走 Skia：Compose 的 `ImageBitmap` 在桌面端本来就是个 Skia 位图，
     * 读出 ARGB 像素后重新编码，得到一份独立的字节序列 ——
     * 这样缓存文件不会因为 `ImageBitmap` 被回收而失效。
     *
     * <p>用 `IntArray` 而不是 `ByteBuffer` 传像素：`androidx.compose.ui.graphics`
     * 在桌面端提供的是 `readPixels(IntArray, ...)` 这个重载，
     * 它会按 ARGB_8888 逐像素读出来，比手工算 ByteBuffer 的步长更不容易出错。
     */
    private fun encodeHead(head: ImageBitmap): ByteArray? = runCatching {
        val w = head.width
        val h = head.height
        val pixels = IntArray(w * h)
        head.readPixels(
            buffer = pixels,
            startX = 0,
            startY = 0,
            width = w,
            height = h,
            bufferOffset = 0,
            stride = w,
        )

        // Skia 要的是 BGRA 排列的字节，而 IntArray 里是 ARGB（A 在高位）。
        // 逐像素重排，顺手把 alpha 提到最高位之后的位置对齐 Skia 的预期。
        val bgra = ByteArray(w * h * 4)
        for (i in pixels.indices) {
            val argb = pixels[i]
            val o = i * 4
            bgra[o] = (argb and 0xFF).toByte()                // B
            bgra[o + 1] = ((argb shr 8) and 0xFF).toByte()    // G
            bgra[o + 2] = ((argb shr 16) and 0xFF).toByte()   // R
            bgra[o + 3] = ((argb shr 24) and 0xFF).toByte()   // A
        }

        org.jetbrains.skia.Image
            .makeRaster(
                org.jetbrains.skia.ImageInfo(
                    w,
                    h,
                    org.jetbrains.skia.ColorType.BGRA_8888,
                    org.jetbrains.skia.ColorAlphaType.UNPREMUL,
                ),
                bgra,
                w * 4,
            )
            .encodeToData()
            ?.bytes
    }.getOrNull()

    /** 把 PNG 字节解码成头部图。 */
    private fun decodeHead(png: ByteArray): ImageBitmap? = runCatching {
        Image.makeFromEncoded(png).toComposeImageBitmap()
    }.getOrNull()

    /**
     * 仅供测试：暴露头像的 PNG 编解码。
     *
     * <p>这一对函数是缓存链路里最容易出错的地方 —— 像素格式（ARGB / BGRA）、
     * 步长、alpha 是否预乘，任何一处错了都会得到颜色诡异的头像，
     * 而且**不会抛异常**，只是「看起来不对劲」。必须能单独测。
     */
    internal fun debugEncodeHead(head: ImageBitmap): ByteArray? = encodeHead(head)

    /** 仅供测试：见 [debugEncodeHead]。 */
    internal fun debugDecodeHead(png: ByteArray): ImageBitmap? = decodeHead(png)

    /**
     * 失败原因要不要往上报。
     *
     * 没有原因就是「这账号本来就没皮肤」（接口 204 / 404），正常情况，静默返回 null；
     * 有原因说明是真失败（429、超时、403），打一行日志再返回 null。
     * 两种情况对界面来说都是「没有头像」，区别只在控制台。
     */
    private fun failOrNot(reason: String?): ByteArray? {
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
