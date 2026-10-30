package org.cn.xiaofan.account

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 头像磁盘缓存。
 *
 * <h2>为什么需要它</h2>
 *
 * 头像来自网络（Mojang 会话服务 / 皮肤站 profile 接口）。没网的时候：
 * - 每次启动都显示首字母占位
 * - 断网、微软/皮肤站宕机、被 429 限流时，头像统统消失
 *
 * 这些情况下用户的账号其实**没变**，皮肤也没变，不该因为网络问题就丢掉头像。
 *
 * <h2>策略：每次启动刷新，失败退回缓存</h2>
 *
 * - **有网且成功** → 覆盖缓存。皮肤换了能及时跟上。
 * - **有网但失败**（429 / 超时 / 服务器错误）→ 用缓存。
 * - **完全没网** → 请求很快失败，用缓存。
 * - **没缓存且失败** → 返回 null，界面回退到首字母。
 *
 * 不做 TTL / 过期检查：头像的失效信号只有「用户换了皮肤」，
 * 而这个事件我们无从得知。每次启动都尝试刷新已经是最保守的做法 ——
 * 有网就更新，没网就吃老本。
 *
 * <h2>为什么不缓存在内存</h2>
 *
 * 内存缓存活不过进程重启，而「离线也能看见头像」这个需求恰恰是跨进程的。
 * 磁盘才是唯一选择。
 *
 * <h2>存储位置</h2>
 *
 * `<headlessmc 配置目录>/avatars/<provider>-<uuid>.png`。
 * 与 headlessmc 自己的数据放在一起，卸载时一起清理，不会在用户主目录里
 * 到处留垃圾。
 *
 * 文件名用 provider + uuid 而不是玩家名：改名后 UUID 不变，头像不该跟着丢；
 * 而不同 provider 可能有相同的 UUID（离线 UUID 是按名字算的，理论上有极小概率
 * 与其他来源撞上），加上 provider 前缀彻底避免歧义。
 */
object AvatarCache {

    /** 缓存目录名，位于 headlessmc 的配置根下。 */
    private const val CACHE_DIR_NAME = "avatars"

    /** 缓存文件扩展名。统一 PNG，不管原始皮肤是什么格式。 */
    private const val EXTENSION = "png"

    /**
     * 缓存根目录覆盖，仅测试用。
     *
     * <p>不设时为 null，走「headlessmc 配置根下的 avatars/」。
     * 测试必须能指向临时目录 —— 否则每个测试都会往用户真实的
     * `~/.config/headlessmc/avatars` 里写文件，跑一次测试就在用户目录里
     * 留一堆垃圾，还可能把用户真实的头像缓存弄坏。
     *
     * <p>用 `@Volatile` 是为了让 `Dispatchers.IO` 上的线程能看到主线程的赋值。
     */
    @Volatile
    private var overrideDir: File? = null

    /** 仅供测试：把缓存目录指到临时位置。传 null 恢复默认。 */
    internal fun debugOverrideDir(dir: File?) {
        overrideDir = dir
    }

    /**
     * 读缓存。
     *
     * @param profile 账号信息，用 [AccountProfile.avatarKey] 定位文件
     * @return 缓存的头部图；没有缓存或读取失败时返回 null
     */
    suspend fun read(profile: AccountProfile): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = fileFor(profile) ?: return@withContext null
        if (!file.isFile) return@withContext null

        try {
            val bytes = file.readBytes()
            if (bytes.isEmpty()) return@withContext null
            Image.makeFromEncoded(bytes).toComposeImageBitmap()
        } catch (t: Throwable) {
            // 缓存文件损坏（写入中断、磁盘坏了）不是严重问题，删掉重新缓存就好。
            System.err.println("[AvatarCache] 读取失败 ${file.name}: ${t.javaClass.simpleName}: ${t.message}")
            runCatching { file.delete() }
            null
        }
    }

    /**
     * 写缓存。
     *
     * <p>**先写临时文件再原子移动**：如果直接往目标文件写，写到一半进程被杀
     * （用户强制关窗口），会留下一个半截的 PNG。下次读它就会解码失败，
     * 而 [read] 会把坏文件删掉 —— 结果就是白缓存一次。
     *
     * <p>原子移动保证目标文件要么是完整的旧版本，要么是完整的新版本。
     *
     * @param png 头部图的 PNG 字节（**不是**整张皮肤），直接落盘不用再编码
     */
    suspend fun write(profile: AccountProfile, png: ByteArray) = withContext(Dispatchers.IO) {
        val file = fileFor(profile) ?: return@withContext
        if (png.isEmpty()) return@withContext

        try {
            file.parentFile?.mkdirs()

            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeBytes(png)
            // ATOMIC_MOVE：同一文件系统内保证原子性。缓存目录和临时文件
            // 在同一目录下，不会跨盘。
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (t: Throwable) {
            // 缓存失败不该影响界面：用户至少能看到这次加载出来的头像。
            System.err.println("[AvatarCache] 写入失败 ${file.name}: ${t.javaClass.simpleName}: ${t.message}")
            runCatching { File(file.parentFile, "${file.name}.tmp").delete() }
        }
        Unit
    }

    /** 清掉某个账号的缓存（删账号时用）。 */
    suspend fun remove(profile: AccountProfile) = withContext(Dispatchers.IO) {
        fileFor(profile)?.let { runCatching { it.delete() } }
        Unit
    }

    /** 清掉全部缓存。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        cacheDir()?.let { dir ->
            runCatching { dir.listFiles()?.forEach { it.delete() } }
        }
        Unit
    }

    /** 仅供测试：缓存文件的路径。 */
    internal fun debugFileFor(profile: AccountProfile): File? = fileFor(profile)

    /**
     * 计算某个账号的缓存文件路径。
     *
     * @return 目录解析失败时返回 null（此时缓存功能整体降级为不可用）
     */
    private fun fileFor(profile: AccountProfile): File? {
        val dir = cacheDir() ?: return null
        // avatarKey 是 "provider:uuid"，其中的冒号在 Windows 上不能做文件名，
        // 换成连字符。其余字符（provider 是字母、uuid 是十六进制）都是安全的。
        val safeName = profile.avatarKey.replace(':', '-')
        return File(dir, "$safeName.$EXTENSION")
    }

    /**
     * 缓存目录。
     *
     * <p>默认放在 headlessmc 的配置根下（与 `.accounts.json` 同级），
     * 这样卸载时一起清理。复用 [AccountResolver.configRoot] 已经算好的路径，
     * 避免两处各写一套「配置目录在哪」的推导规则。
     */
    private fun cacheDir(): File? =
        overrideDir ?: runCatching {
            File(AccountResolver.configRoot(), CACHE_DIR_NAME)
        }.getOrNull()
}
