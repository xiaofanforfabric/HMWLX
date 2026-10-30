package org.cn.xiaofan.account

import io.github.headlesshq.headlessmc.auth.AuthProvider
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.io.File

/**
 * [AvatarCache] 的读写逻辑。
 *
 * <p>**不测网络**，只测磁盘这一侧：写入、读取、原子性、损坏恢复。
 * 头像的下载与裁切在 `AvatarLoader` 里，那部分需要真实皮肤图，
 * 已经在 `OfflineAvatarTest` 等处覆盖。
 *
 * <p>为什么值得单独测缓存：它是一条**静默失败**的路径。缓存坏了、写不进去，
 * 界面只是退回首字母，没有任何报错提示用户，问题会一直藏着。
 */
class AvatarCacheTest {

    /**
     * 每个测试用自己的临时目录。
     *
     * <p>**不能让测试写用户真实目录**：默认路径是
     * `~/.config/headlessmc/avatars`，跑一次测试就会在那里留一堆文件，
     * 更糟的是可能覆盖用户真实账号的头像缓存。
     */
    @TempDir
    lateinit var tempDir: File

    private val profile = AccountProfile(
        name = "TestPlayer",
        provider = AuthProvider.DEFAULT,
        uuid = "d8c48e47db1d315f8fcb0f8f260af745",
        avatar = AvatarSource.Remote("https://example.invalid/skin"),
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
    fun `缓存文件名用 provider 加 uuid，不用玩家名`() {
        // 改名后 UUID 不变，头像不该跟着丢；不同 provider 可能撞 UUID，
        // 加 provider 前缀彻底避免歧义。
        val file = AvatarCache.debugFileFor(profile)

        assertTrue(file != null, "应当能算出缓存路径")
        val name = file!!.name
        assertTrue(name.contains(AuthProvider.DEFAULT), "文件名应含 provider: $name")
        assertTrue(name.contains(profile.uuid), "文件名应含 uuid: $name")
        assertTrue(!name.contains("TestPlayer"), "文件名不该用玩家名（改名会丢头像）: $name")
    }

    @Test
    fun `文件名里不含冒号`() {
        // avatarKey 是 "provider:uuid"，冒号在 Windows 上不是合法文件名字符。
        val name = AvatarCache.debugFileFor(profile)!!.name

        assertTrue(!name.contains(':'), "冒号会让 Windows 写文件失败: $name")
    }

    @Test
    fun `不同 provider 的同一 uuid 落到不同文件`() {
        // 离线 UUID 是按名字算的，理论上可能与别的来源撞上。
        val a = profile.copy(provider = AuthProvider.DEFAULT)
        val b = profile.copy(provider = AuthProvider.YGGDRASIL)

        val nameA = AvatarCache.debugFileFor(a)!!.name
        val nameB = AvatarCache.debugFileFor(b)!!.name

        assertTrue(nameA != nameB, "provider 不同必须分开存: $nameA vs $nameB")
    }

    @Test
    fun `写进去的字节能原样读出来`() {
        // 这里直接测文件层面：write 存的是 PNG 字节，
        // 不经过解码就能验证「存了什么、读出来是不是什么」。
        val png = tinyPng()
        runBlocking { AvatarCache.write(profile, png) }

        val file = AvatarCache.debugFileFor(profile)!!
        assertTrue(file.isFile, "缓存文件应当存在")
        assertArrayEquals(png, file.readBytes(), "写入后读回应完全一致")
    }

    @Test
    fun `空字节不写缓存`() {
        // 空文件会被 read 当成损坏而删掉，写它纯属浪费。
        runBlocking { AvatarCache.write(profile, ByteArray(0)) }

        val file = AvatarCache.debugFileFor(profile)!!
        assertTrue(!file.isFile, "空字节不该产生缓存文件")
    }

    @Test
    fun `损坏的缓存读取返回 null 并删掉文件`() {
        // 模拟「写到一半进程被杀」留下的半截 PNG。
        val file = AvatarCache.debugFileFor(profile)!!
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x00))

        val result = runBlocking { AvatarCache.read(profile) }

        assertNull(result, "损坏的文件应该读不出来")
        assertTrue(!file.isFile, "坏文件应当被删掉，下次重新缓存")
    }

    @Test
    fun `没有缓存时读取返回 null`() {
        runBlocking { AvatarCache.remove(profile) }

        assertNull(runBlocking { AvatarCache.read(profile) })
    }

    @Test
    fun `写入会覆盖旧缓存`() {
        val first = tinyPng()
        // 造一份不同的字节，确认第二次写真的覆盖了第一次。
        val second = tinyPng().copyOf().also { it[it.size - 1] = 0x00 }

        runBlocking {
            AvatarCache.write(profile, first)
            AvatarCache.write(profile, second)
        }

        val bytes = AvatarCache.debugFileFor(profile)!!.readBytes()
        assertArrayEquals(second, bytes, "应当是最新写入的那一份")
    }

    @Test
    fun `没有残留的临时文件`() {
        // 原子移动之后临时文件必须消失，否则缓存目录会慢慢堆满垃圾。
        runBlocking { AvatarCache.write(profile, tinyPng()) }

        val dir = AvatarCache.debugFileFor(profile)!!.parentFile!!
        val leftovers = dir.listFiles()?.filter { it.name.endsWith(".tmp") }.orEmpty()

        assertTrue(leftovers.isEmpty(), "不该有残留的 .tmp: ${leftovers.map { it.name }}")
    }

    /**
     * 一张最小的合法 PNG（1x1 透明像素）。
     *
     * <p>手写字节而不是找测试资源文件：只为了验证「存取一致」，
     * 内容是什么无所谓，没必要为此加一个二进制文件。
     */
    private fun tinyPng(): ByteArray = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG 签名
        0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,             // IHDR 长度 + 类型
        0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,             // 1x1
        0x08, 0x06, 0x00, 0x00, 0x00, 0x1F.toByte(), 0x15, 0xC4.toByte(),
        0x89.toByte(), 0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54, // IDAT
        0x78, 0x9C.toByte(), 0x63, 0x00, 0x01, 0x00, 0x00, 0x05,
        0x00, 0x01, 0x0D, 0x0A, 0x2D, 0xB4.toByte(),
        0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44, 0xAE.toByte(), 0x42, 0x60, 0x82.toByte(),
    )

    // ---------------------------------------------------------------------
    // 编解码往返
    //
    // 这是整条缓存链路最容易出错的一环：像素格式（ARGB / BGRA / RGBA）、
    // 步长、alpha 预乘与否，任何一处错了都会得到一张颜色诡异的头像，
    // 而且不会抛异常 —— 只会「看起来不对劲」。
    // ---------------------------------------------------------------------

    @Test
    fun `头像编码成 PNG 再解码回来尺寸不变`() {
        val head = solidHead(width = 8, height = 8, argb = 0xFF3A7BD5.toInt())

        val png = AvatarLoader.debugEncodeHead(head)
        assertTrue(png != null && png.isNotEmpty(), "应当编码出 PNG 字节")

        val decoded = AvatarLoader.debugDecodeHead(png!!)
        assertTrue(decoded != null, "应当能解码回来")
        assertEquals(8, decoded!!.width)
        assertEquals(8, decoded.height)
    }

    @Test
    fun `头像编码解码后颜色不变`() {
        // 用一个有色不透明的像素：如果通道顺序搞错（B 和 R 互换），
        // 蓝色会变成红色，这个断言能抓住。
        val blue = 0xFF3A7BD5.toInt()
        val head = solidHead(8, 8, blue)

        val png = AvatarLoader.debugEncodeHead(head)!!
        val decoded = AvatarLoader.debugDecodeHead(png)!!

        val pixels = IntArray(decoded.width * decoded.height)
        decoded.readPixels(pixels, 0, 0, decoded.width, decoded.height)
        assertEquals(blue, pixels[0], "颜色在往返后应当保持不变（通道顺序不能错）")
    }

    @Test
    fun `透明像素往返后仍然透明`() {
        // alpha 通道丢失会让本该透明的背景变成黑块，在深色背景上一眼可见。
        val transparent = 0x00000000
        val head = solidHead(8, 8, transparent)

        val png = AvatarLoader.debugEncodeHead(head)!!
        val decoded = AvatarLoader.debugDecodeHead(png)!!

        val pixels = IntArray(decoded.width * decoded.height)
        decoded.readPixels(pixels, 0, 0, decoded.width, decoded.height)
        assertEquals(0, pixels[0] and 0xFF000000.toInt(), "alpha 应当仍是 0")
    }

    /**
     * 造一张纯色位图，模拟裁好的头部。
     *
     * <p>用 Skia 直接构造而不是 `ImageBitmap(width, height).writePixels(...)`：
     * Compose 的 `ImageBitmap` 接口没有公开的写像素方法（只有 readPixels），
     * 构造带内容的位图只能走 Skia 的 `Bitmap.installPixels` 或 `Image.makeRaster`。
     */
    private fun solidHead(width: Int, height: Int, argb: Int) =
        org.jetbrains.skia.Image
            .makeRaster(
                org.jetbrains.skia.ImageInfo(
                    width,
                    height,
                    org.jetbrains.skia.ColorType.BGRA_8888,
                    org.jetbrains.skia.ColorAlphaType.UNPREMUL,
                ),
                // Skia 这一侧要 BGRA 排列的字节，从 argb 拆出来。
                ByteArray(width * height * 4).also { bytes ->
                    for (i in 0 until width * height) {
                        val o = i * 4
                        bytes[o] = (argb and 0xFF).toByte()
                        bytes[o + 1] = ((argb shr 8) and 0xFF).toByte()
                        bytes[o + 2] = ((argb shr 16) and 0xFF).toByte()
                        bytes[o + 3] = ((argb shr 24) and 0xFF).toByte()
                    }
                },
                width * 4,
            )
            .toComposeImageBitmap()
}
