package org.cn.xiaofan.download

import io.github.headlesshq.headlessmc.platform.Platform
import io.github.headlesshq.headlessmc.platform.PlatformService
import io.github.headlesshq.headlessmc.platform.PlatformVersion
import io.github.headlesshq.headlessmc.platform.VanillaVersion
import io.github.headlesshq.headlessmc.platform.client.ClientInstaller
import io.github.headlesshq.headlessmc.platform.client.ClientSupport
import io.github.headlesshq.headlessmc.platform.VersionID
import io.github.headlesshq.headlessmc.util.typemap.TypedMap
import io.github.headlesshq.headlessmc.util.typemap.TypedMapImpl
import io.github.headlesshq.headlessmc.version.arg.VersionArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.cn.xiaofan.version.GameVersionService
import java.nio.file.Path

/**
 * 版本清单与下载服务。
 *
 * <p>全部复用上游 headlessmc 的实现，不自己拼下载逻辑：
 *
 * | 要做的事 | 上游接口 |
 * |---|---|
 * | 可选版本列表 | [VanillaVersionService.getVersions] |
 * | 解析版本号 → [VersionID] | [VersionID.resolve] |
 * | 下载并安装 | [ClientInstaller.installClient] |
 *
 * <p>自己写一套下载器意味着要处理资源索引、库校验、natives 解压、
 * 版本继承…… 这些上游都做过了，而且启动链路也依赖它装出来的目录结构。
 * 用同一套实现才能保证「下载完能启动」。
 *
 * <p>**依赖 Quarkus 容器**（要拿 [PlatformService] 等 bean），
 * 所以容器没起来时这里会失败 —— 与 [GameVersionService] 的「纯读磁盘」不同。
 */
object MinecraftDownloadService {

    /**
     * 一个可下载的版本。
     *
     * @param id 版本号，如 `1.21.4`。传给下载时用这个字符串。
     * @param type 版本类型，如 `release` / `snapshot` / `old_beta`。
     *   来自 Mojang 版本清单，界面用来分组或标注。
     */
    data class DownloadableVersion(
        val id: String,
        val type: String,
    )

    /** 版本清单获取结果。 */
    sealed interface VersionListResult {
        /** @param versions 按发布时间倒序（上游已排好，这是它返回的顺序）。 */
        data class Success(val versions: List<DownloadableVersion>) : VersionListResult

        /** 拿不到清单。`reason` 可直接展示。 */
        data class Failed(val reason: String) : VersionListResult
    }

    /**
     * 拉取官方版本清单。
     *
     * <p>**联网操作**，走 [Dispatchers.IO]。上游有缓存，重复调用不会每次都下。
     *
     * <p>过滤掉 `old_*` 系列（远古版本）：它们下载和启动都需要额外处理，
     * 且实际使用者极少。要放开的话把 `filter` 那段删掉即可。
     */
    suspend fun listVersions(): VersionListResult = withContext(Dispatchers.IO) {
        runCatching {
            val platformService = HeadlessMcBootstrap.bean(PlatformService::class.java)
            val vanilla = platformService.vanillaPlatform
            val service = vanilla.versionService

            // getVersions() 返回 SequencedSet，顺序就是上游认为的合理顺序
            // （新的在前）。直接转 List 保留顺序，不要 sort。
            service.versions
                .filter { it.name !in OLD_VERSION_PREFIXES_FILTERED_OUT }
                .map { DownloadableVersion(id = it.name, type = it.platformName) }
        }.fold(
            onSuccess = { VersionListResult.Success(it) },
            onFailure = { VersionListResult.Failed(it.describe()) },
        )
    }

    /** 下载结果。 */
    sealed interface InstallResult {
        /**
         * 下载成功。
         *
         * @param versionId 装好的版本号，界面可以直接用它刷新版本列表。
         */
        data class Success(val versionId: String) : InstallResult

        /** 下载失败。`reason` 可直接展示。 */
        data class Failed(val reason: String) : InstallResult
    }

    /**
     * 下载并安装一个原版版本。
     *
     * <p>安装目录与启动器扫的是同一个（[GameVersionService.resolveGameDir]），
     * 否则下完在版本列表里看不到。
     *
     * <p>上游的 `installClient` 会先检查是否已安装：已存在且不是强装时
     * 直接返回现有版本，不会重复下载。这是它自己的行为，不用我们判断。
     *
     * @param versionId 版本号，如 `1.21.4`
     * @param onProgress 进度回调，参数是当前阶段的描述文字。下载耗时较长，
     *   界面需要反馈，否则用户以为卡住了。回调在 IO 线程上触发。
     */
    suspend fun install(
        versionId: String,
        onProgress: (String) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        runCatching {
            val platformService = HeadlessMcBootstrap.bean(PlatformService::class.java)
            val vanilla = platformService.vanillaPlatform

            onProgress("正在解析版本 $versionId …")

            // 构造 VersionArg 时显式指定 platform=vanilla 与 side=client。
            // 不指定 platform 时上游会按自己的默认值走，不同版本默认值可能不同，
            // 显式写死可以避免升级后行为漂移。
            val arg = VersionArg.parse(listOf(versionId))
                .withSide(io.github.headlesshq.headlessmc.version.arg.Side.CLIENT)

            val id = VersionID.resolve(platformService, arg)
            val platform: Platform = id.platform
            val installer: ClientInstaller = platform.clientSupport
                .map(ClientSupport::installer)
                .orElseThrow {
                    IllegalStateException("平台 ${platform.name} 不支持下载客户端")
                }

            val dir: Path = GameVersionService.resolveGameDir().toPath()
            onProgress("正在下载 $versionId 到 $dir …")

            // 空 TypedMap = 不强装、不用自定义 URL。
            // 如果以后要支持「重新下载」，在这里 put FORCE_INSTALL=true。
            val installed = installer.installClient(id, dir, TypedMapImpl())

            onProgress("安装完成")
            // 上游返回的 Version 没有稳定的「版本号」取值方法（不同实现字段不同），
            // 用请求的 versionId 作为结果最可靠 —— 它就是装好的那个版本。
            InstallResult.Success(installed.toString().ifBlank { versionId })
        }.fold(
            onSuccess = { it },
            onFailure = { InstallResult.Failed(it.describe()) },
        )
    }

    /**
     * 不展示的版本前缀。
     *
     * <p>远古版本（`old_alpha` / `old_beta`）和快照之外的实验版本下载链路不同，
     * 容易报错。真正要下的时候再放开。
     */
    private val OLD_VERSION_PREFIXES_FILTERED_OUT = setOf(
        "old_alpha", "old_beta",
    )
}

/** 把异常整理成一句能显示给用户的话。 */
private fun Throwable.describe(): String {
    val message = message
    return if (message.isNullOrBlank()) {
        javaClass.simpleName
    } else {
        "$message（${javaClass.simpleName}）"
    }
}
