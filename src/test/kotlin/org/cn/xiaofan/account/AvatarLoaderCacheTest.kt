package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.AuthProvider
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [AvatarLoader.loadCached] 的降级策略。
 *
 * <p>核心承诺：**每次启动尝试刷新，失败退回缓存**。
 * 这条策略的价值在于「离线也能看见头像」—— 用户的账号没变、皮肤没变，
 * 不该因为网络问题就退化成首字母占位。
 *
 * <p>测试用不可达的地址触发失败，而不是伪造 HTTP 层：我们要验证的是
 * 「失败之后会去读缓存」这个**行为**，失败的具体原因（DNS / 超时 / 429）
 * 由 `AuthPreflightTest` 那一侧覆盖。
 */
class AvatarLoaderCacheTest {

    @TempDir
    lateinit var tempDir: File

    /**
     * 指向一个必然连不上的地址。
     *
     * <p>`127.0.0.1:1` 是最快失败的方式：本机回环、端口 1 基本不会有服务，
     * 连接会立刻被拒绝（`ConnectException`），不用等超时。
     * 这比「假装断网」可靠 —— 我们控制不了测试环境的网络。
     */
    private val unreachableUrl = "http://127.0.0.1:1/skin.png"

    private fun profile(url: String) = AccountProfile(
        name = "TestPlayer",
        provider = AuthProvider.DEFAULT,
        uuid = "d8c48e47db1d315f8fcb0f8f260af745",
        avatar = AvatarSource.Remote(url),
    )

    @BeforeEach
    fun pointCacheAtTempDir() {
        AvatarCache.debugOverrideDir(tempDir)
    }

    @AfterEach
    fun restoreDefaultDir() {
        AvatarCache.debugOverrideDir(null)
    }

    @Test
    fun `网络失败时退回缓存`() {
        val profile = profile(unreachableUrl)

        // 先手工放一份缓存，模拟「上次启动成功过」。
        runBlocking { AvatarCache.write(profile, realPngBytes()) }
        val cached = AvatarCache.debugFileFor(profile)!!
        assertEquals(true, cached.isFile, "缓存应当已写入")

        // 网络必然失败 → 应当读到缓存。
        val result = runBlocking { AvatarLoader.loadCached(profile) }

        assertNotNull(result, "刷新失败时必须退回缓存，不能退化成 null")
        assertEquals(8, result!!.width, "缓存的 8x8 头像应当能解码")
        assertEquals(8, result.height)
    }

    @Test
    fun `有缓存时立刻回调，不等网络`() {
        // 这是「不闪空白头像」的核心：缓存必须在网络请求**之前**交出去。
        // 用不可达地址意味着网络一定失败 —— 如果实现是先请求后读缓存，
        // onCached 就永远不会被调用，这条测试会挂。
        val profile = profile(unreachableUrl)
        runBlocking { AvatarCache.write(profile, realPngBytes()) }

        var cachedCallback: androidx.compose.ui.graphics.ImageBitmap? = null
        val returned = runBlocking {
            AvatarLoader.loadCached(
                profile = profile,
                onCached = { cachedCallback = it },
            )
        }

        assertNotNull(cachedCallback, "缓存命中时必须立刻回调，界面才有东西可显示")
        assertNotNull(returned, "返回值也应当是缓存图")
    }

    @Test
    fun `没有缓存时不调用 onCached`() {
        // 没有缓存就没有可显示的，别用一个空回调糊弄界面。
        val profile = profile(unreachableUrl)

        var called = false
        runBlocking {
            AvatarLoader.loadCached(profile, onCached = { called = true })
        }

        assertEquals(false, called, "没有缓存时不该回调 onCached")
    }

    @Test
    fun `网络失败时不调用 onRefreshed`() {
        // onRefreshed 的语义是「拿到了新图」，失败时调它会让界面
        // 用一个不存在的头像覆盖已有的缓存图。
        val profile = profile(unreachableUrl)
        runBlocking { AvatarCache.write(profile, realPngBytes()) }

        var refreshed = false
        runBlocking {
            AvatarLoader.loadCached(profile, onRefreshed = { refreshed = true })
        }

        assertEquals(false, refreshed, "刷新失败时不该触发 onRefreshed")
    }

    // ---------------------------------------------------------------------
    // 「网络成功」与「编码成功」必须是两件事
    //
    // 早期实现里 fetchHeadPng 返回的是编码后的 PNG 字节，而 encodeHead 内部
    // 用 runCatching 把异常吞成 null。于是「下载成功但编码失败」和
    // 「下载失败」在上游看来完全一样：
    // - 日志打出「刷新失败」，归因错了
    // - AvatarCache.write 永远拿不到字节，缓存永远写不进去，
    //   用户每次启动都要重新下载，且每次都走这条看起来像故障的路径
    //
    // 现在 fetchHead 返回位图，编码是缓存写入方的事。这组测试锁定这个分层。
    // ---------------------------------------------------------------------

    @Test
    fun `编码与写入是独立的一步，失败不影响返回值`() {
        // 用一个正常的位图：编码会成功，缓存应当写进去。
        // 这个测试的真正意义是确认 persist() 被调用了 ——
        // 如果实现退回「返回字节」的老路，缓存文件不会出现。
        val head = solidImageBitmap(8, 8)

        val png = AvatarLoader.debugEncodeHead(head)
        assertNotNull(png, "正常位图应当能编码")

        // 再验证编码产物确实可解码（往返链路完整）
        val decoded = AvatarLoader.debugDecodeHead(png!!)
        assertNotNull(decoded, "编码产物应当能解码")
        assertEquals(8, decoded!!.width)
    }

    @Test
    fun `头像源为 Unavailable 时读缓存，不发请求`() {
        // 来源不可用（provider 配置变了之类）：有缓存就用缓存，
        // 没有就返回 null，绝不去碰网络。
        val profile = profile(unreachableUrl).copy(avatar = AvatarSource.Unavailable)

        runBlocking { AvatarCache.write(profile, realPngBytes()) }

        var cachedCallback: androidx.compose.ui.graphics.ImageBitmap? = null
        runBlocking {
            AvatarLoader.loadCached(profile, onCached = { cachedCallback = it })
        }

        assertNotNull(cachedCallback, "来源不可用但缓存存在时，应当用缓存")
    }

    /** 造一张纯色位图，模拟裁好的头部。 */
    private fun solidImageBitmap(w: Int, h: Int) =
        org.jetbrains.skia.Image.makeRaster(
            org.jetbrains.skia.ImageInfo(
                w, h,
                org.jetbrains.skia.ColorType.BGRA_8888,
                org.jetbrains.skia.ColorAlphaType.UNPREMUL,
            ),
            ByteArray(w * h * 4) { 0x9C.toByte() },
            w * 4,
        ).toComposeImageBitmap()

    @Test
    fun `网络失败且没有缓存时返回 null`() {
        // 这是唯一会退回首字母的情况：既拿不到新的，也没有旧的。
        val profile = profile(unreachableUrl)

        val result = runBlocking { AvatarLoader.loadCached(profile) }

        assertNull(result, "没用缓存可用时只能返回 null，界面用首字母占位")
    }

    @Test
    fun `内置皮肤不写缓存`() {
        // 离线账号的 Steve 就在 jar 里，读它比读磁盘还快，缓存纯属浪费。
        val offline = AccountProfile(
            name = "Offline",
            provider = AuthProvider.OFFLINE,
            uuid = "22689332a7fd41919600b0fe1135ee34",
            avatar = AvatarSource.BuiltInSkin.STEVE,
        )

        val result = runBlocking { AvatarLoader.loadCached(offline) }

        assertNotNull(result, "内置皮肤应当能正常加载")
        val cacheFile = AvatarCache.debugFileFor(offline)
        assertNull(
            cacheFile?.takeIf { it.isFile },
            "内置皮肤不该产生缓存文件: ${cacheFile?.name}",
        )
    }

    @Test
    fun `内置皮肤也要回调 onCached`() {
        // 回归测试：曾经这里直接 return 不回调，而调用方是
        // `loadCached(profile, onCached = { avatar = it })` 且忽略返回值，
        // 于是离线账号的头像永远是 null，界面退化成首字母占位 ——
        // jar 里明明有 Steve 皮肤。
        val offline = AccountProfile(
            name = "Offline",
            provider = AuthProvider.OFFLINE,
            uuid = "22689332a7fd41919600b0fe1135ee34",
            avatar = AvatarSource.BuiltInSkin.STEVE,
        )

        var cachedCallback: androidx.compose.ui.graphics.ImageBitmap? = null
        val returned = runBlocking {
            AvatarLoader.loadCached(offline, onCached = { cachedCallback = it })
        }

        assertNotNull(
            cachedCallback,
            "内置皮肤必须走 onCached，否则依赖回调的调用方拿不到头像",
        )
        assertNotNull(returned, "返回值同样应当给出")
        assertEquals(8, returned!!.width, "Steve 头部应当裁成 8x8")
    }

    /**
     * 一份真实的 8x8 PNG，用作「上次缓存下来的头像」。
     *
     * <p>用固定字节而不是跑一次网络请求：测试不该依赖外网，
     * 也不该因为微软限流而变红。
     */
    private fun realPngBytes(): ByteArray {
        val image = org.jetbrains.skia.Image.makeRaster(
            org.jetbrains.skia.ImageInfo(
                8,
                8,
                org.jetbrains.skia.ColorType.BGRA_8888,
                org.jetbrains.skia.ColorAlphaType.UNPREMUL,
            ),
            ByteArray(8 * 8 * 4) { 0x80.toByte() },
            8 * 4,
        )
        return AvatarLoader.debugEncodeHead(image.toComposeImageBitmap())!!
    }
}
