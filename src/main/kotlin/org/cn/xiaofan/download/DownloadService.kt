package org.cn.xiaofan.download

import io.github.headlesshq.headlessmc.platform.PlatformService
import io.github.headlesshq.headlessmc.platform.VersionID
import io.github.headlesshq.headlessmc.platform.client.ClientInstaller
import io.github.headlesshq.headlessmc.util.typemap.TypedMap
import io.github.headlesshq.headlessmc.util.typemap.TypedMapImpl
import io.github.headlesshq.headlessmc.version.arg.VersionArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.cn.xiaofan.version.GameVersionService
import java.io.File

/**
 * 可用版本列表 + 下载。
 *
 * <p>**全部复用上游 headlessmc**，不自己实现下载逻辑：
 * 版本清单来自 `Platform#getVersionService()`，安装走 `ClientInstaller#installClient`。
 * 自己再写一遍等于重造 Mojang 的资源索引、校验、库解析 —— 上游已经做对了，
 * 而且和「启动」用的是同一套数据，不会出现「下下来了但拉不起来」。
 *
 * <p>两条路径都发网络请求、写磁盘，因此在 [Dispatchers.IO] 上跑。
 */
object DownloadService {

    /**
     * 可下载版本清单。
     *
     * @param versions 版本 id 列表，上游给的顺序（新的在前）
     * @param gameDir 会装到哪个目录，界面上要显示
     */
    sealed interface VersionListResult {
        data class Success(val versions: List<String>, val gameDir: File) : VersionListResult

        /** 拉取失败。`reason` 可直接展示。 */
        data class Failed(val reason: String) : VersionListResult
    }

    /**
     * 下载结果。
     */
    sealed interface InstallResult {
        /** 下载并安装完成。`versionId` 是最终落盘的版本 id。 */
        data class Success(val versionId: String, val gameDir: File) : InstallResult

        /**
         * 下载失败。
         *
         * @param reason 可直接展示的原因
         * @param cause 原始异常，用于日志
         */
        data class Failed(val reason: String, val cause: Throwable?) : InstallResult
    }

    /**
     * 拉取可下载的原版版本列表。
     *
     * <p>只列原版（vanilla）：加载器版本要基于原版才有意义，
     * 而且第一版下载中心先把「能下到本体」这件事做对。
     * TODO(human): 加载器（Fabric/Forge/NeoForge）列表接入。
     */
    suspend fun listVersions(): VersionListResult = withContext(Dispatchers.IO) {
        runCatching {
            val platform = vanillaPlatform()
            val service = platform.versionService
            // getVersions() 返回 SequencedSet，上游已按「新 → 旧」排好。
            // 显式转成 List<String> 再给界面 —— 直接把 headlessmc 的类型
            // 漏到 UI 层，以后换 API 会牵连一片。
            // 注意：SequencedSet 是 Java 21+ 接口，Kotlin 编译器在这个模块里
            // 认不全它，所以先 toList() 走 Iterable 的通用路径。
            val ids: List<String> = (service.versions as Iterable<*>)
                .mapNotNull { (it as? io.github.headlesshq.headlessmc.platform.PlatformVersion)?.name }
            if (ids.isEmpty()) {
                return@runCatching VersionListResult.Failed("版本列表为空（上游可能改接口了）")
            }
            VersionListResult.Success(ids, resolveGameDir())
        }.getOrElse {
            VersionListResult.Failed(it.describe())
        }
    }

    /**
     * 下载并安装一个原版版本。
     *
     * <p>走 `ClientInstaller#installClient`：
     * 1. 上游先看本地是否已装（除非 [force]），已装直接返回，不重下
     * 2. 装原版本体（版本 JSON、客户端 jar、库、资源）
     * 3. 交回版本对象
     *
     * <p>**这条路会写磁盘，且耗时（原版本体 + 资源约 200MB+）**，
     * 调用方必须在协程里跑并显示进度。目前上传进度还没接，
     * 先只给出「开始/结束」两个状态。
     *
     * @param versionId 要下载的版本，必须是 [listVersions] 返回过的 id
     * @param force 已装时是否强制重下
     */
    suspend fun install(
        versionId: String,
        force: Boolean = false,
    ): InstallResult = withContext(Dispatchers.IO) {
        runCatching {
            val platform = vanillaPlatform()
            val installer = platform.installer
            val gameDir = resolveGameDir().toPath()

            // VersionArg 负责把 "1.21.4" 这样的字符串解析成上游的版本标识。
            // 直接用 VersionID 构造器不行 —— 它的构造器对 Kotlin 不可见。
            val id = VersionID.resolve(
                HeadlessMcBootstrap.bean(PlatformService::class.java),
                VersionArg.parse(versionId),
            )

            // TypedMap 传空即可；force 通过 FORCE_INSTALL 键传，
            // 默认 false 时上游会先查本地已装列表，已装就秒回。
            val args: TypedMap = TypedMapImpl()
            if (force) {
                args.put(ClientInstaller.FORCE_INSTALL, true)
            }

            val version = installer.installClient(id, gameDir, args)
            // 上游 Version 没有可靠的 getName()（字段在注解里声明成可空的）,
            // 用我们传进去的 id 作为结果名 —— 那正是用户点的那个版本，
            // 而且 installClient 成功就意味着磁盘上确实有这个版本的目录。
            InstallResult.Success(versionId, gameDir.toFile())
        }.getOrElse {
            InstallResult.Failed(it.describe(), it)
        }
    }

    // -----------------------------------------------------------------------
    // 内部
    // -----------------------------------------------------------------------

    /** 取原版平台对象。上游所有平台都从 [PlatformService] 里查。 */
    private fun vanillaPlatform() =
        HeadlessMcBootstrap.bean(PlatformService::class.java).vanillaPlatform

    /** 与版本列表页共用同一套目录解析逻辑，保证下载和启动看的是同一个目录。 */
    private suspend fun resolveGameDir(): File = GameVersionService.resolveGameDir()

    /** 把异常压成一句可展示的话。上游的错误信息通常已经有用了。 */
    private fun Throwable.describe(): String {
        val msg = message?.takeIf { it.isNotBlank() }
        return when {
            msg != null -> msg
            else -> "${this::class.simpleName ?: "未知错误"}"
        }
    }
}
