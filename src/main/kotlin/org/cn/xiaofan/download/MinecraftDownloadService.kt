package org.cn.xiaofan.download

import io.github.headlesshq.headlessmc.platform.Platform
import io.github.headlesshq.headlessmc.platform.PlatformService
import io.github.headlesshq.headlessmc.platform.client.ClientInstaller
import io.github.headlesshq.headlessmc.platform.client.ClientSupport
import io.github.headlesshq.headlessmc.platform.VersionID
import io.github.headlesshq.headlessmc.util.typemap.TypedMapImpl
import io.github.headlesshq.headlessmc.version.arg.VersionArg
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.cn.xiaofan.config.Json
import org.cn.xiaofan.version.GameVersionService
import java.nio.file.Path

/**
 * 版本清单与下载服务。
 *
 * <p>分两层协作：
 *
 * | 要做的事 | 谁做 |
 * |---|---|
 * | 版本列表 | **自己拉 Mojang manifest**（上游丢掉了 type 字段） |
 * | 下载安装 | **上游** `ClientInstaller.installClient` |
 *
 * <p>列表自己拉是因为上游 `VanillaVersionServiceImpl.map()` 只保留了版本号，
 * 把 manifest 里的 `type` 丢掉了 —— 没有 type 就没法分正式版 / 快照 / 远古版。
 *
 * <p>下载**必须**走上游：那边要处理资源索引、库校验、natives 解压、版本继承，
 * 而且启动链路依赖它装出来的目录结构。自己写一套的话，装出来的东西可能起不来。
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
     * 版本分类。
     *
     * <h2>为什么分这五类</h2>
     *
     * Mojang 官方清单里 `type` 只有四个值：`release` / `snapshot` /
     * `old_beta` / `old_alpha`。**愚人节版本没有独立 type**，它们是
     * `snapshot` 的一部分，只能靠版本号识别 —— 所以这里多出一个分类。
     *
     * | 分类 | 判定 |
     * |---|---|
     * | [RELEASE] | `type == release` |
     * | [SNAPSHOT] | `type == snapshot` 且不在愚人节名单里 |
     * | [APRIL_FOOLS] | `type == snapshot` 且版本号命中 [APRIL_FOOLS_IDS] |
     * | [LEGACY] | `type == old_beta` 或 `old_alpha` |
     * | [ALL] | 不分类，全都要（仅用于界面上的「全部」页签，不是数据分类） |
     */
    enum class VersionCategory {
        RELEASE,
        SNAPSHOT,
        APRIL_FOOLS,
        LEGACY,

        /** 仅用于界面「全部」页签；[classify] 永远不会返回它。 */
        ALL,
    }

    /**
     * 一个可下载的版本。
     *
     * @param id 版本号，如 `1.21.4`。下载时传这个字符串。
     * @param type Mojang 原始 type：`release` / `snapshot` / `old_beta` / `old_alpha`
     * @param category 按 [classify] 算出的界面分类
     */
    data class DownloadableVersion(
        val id: String,
        val type: String,
        val category: VersionCategory,
    )

    /** 版本清单获取结果。 */
    sealed interface VersionListResult {
        /** @param versions 按发布时间倒序（Mojang 清单本身就是这个顺序）。 */
        data class Success(val versions: List<DownloadableVersion>) : VersionListResult

        /** 拿不到清单。`reason` 可直接展示。 */
        data class Failed(val reason: String) : VersionListResult
    }

    /**
     * 官方版本清单地址。
     *
     * <p>**必须与上游 headlessmc 用同一个 URL**
     * （见上游 `VanillaFactory`）—— 两边数据源不一致的话，界面列出来的版本
     * 有可能下载时解析不到，用户点了就报错。
     */
    private const val MANIFEST_URL =
        "https://launchermeta.mojang.com/mc/game/version_manifest.json"

    /**
     * 愚人节版本名单。
     *
     * <p>Mojang 不给愚人节版本单独的 type，只能靠版本号识别。
     * 清单来自历年的实际发布，新增年份时在这里补一行。
     *
     * <p>为什么不按「版本号是否含字母」之类的规则猜：
     * 普通快照也是 `25w14a` 这种格式，规则一写就误伤一大片。
     */
    private val APRIL_FOOLS_IDS = setOf(
        "15w14a",                  // 2015 爱情与拥抱更新
        "1.RV-Pre1",               // 2016 时尚更新
        "3D Shareware v1.34",      // 2019 3D 共享软件
        "20w14infinite",           // 2020 无限维度
        "22w13oneBlockAtATime",    // 2022 一次一个方块
        "23w13a_or_b",             // 2023 投票更新
        "24w14potato",             // 2024 有毒土豆
        "25w14craftmine",          // 2025 合成我的世界
    )

    /** 按 Mojang 的 type 与版本号算出界面分类。 */
    fun classify(id: String, type: String): VersionCategory = when (type) {
        "release" -> VersionCategory.RELEASE
        "old_beta", "old_alpha" -> VersionCategory.LEGACY
        else -> if (id in APRIL_FOOLS_IDS) {
            VersionCategory.APRIL_FOOLS
        } else {
            VersionCategory.SNAPSHOT
        }
    }

    /**
     * 拉取官方版本清单。
     *
     * <h2>为什么自己拉而不直接用上游的 getVersions()</h2>
     *
     * 上游 `VanillaVersionServiceImpl.map()` 只保留了版本号，
     * **把 manifest 里的 `type` 字段丢掉了**（`createVanillaVersion(version.id())`
     * 只传 id）。没有 type 就无法区分正式版 / 快照 / 远古版 —— 而这正是
     * 界面要的分类。
     *
     * <p>下载仍然走上游的 installer：那边需要完整的版本 JSON 解析能力，
     * 我们只负责列表这一层。
     *
     * <p>**联网操作**，走 [Dispatchers.IO]。
     */
    suspend fun listVersions(): VersionListResult = withContext(Dispatchers.IO) {
        runCatching {
            val text = fetchText(MANIFEST_URL)
            val root = Json.parse(text)
            // 清单结构：{ "latest": {...}, "versions": [ {id, type, url, time}, ... ] }
            val versions = (root as? Map<*, *>)?.get("versions") as? List<*>
                ?: error("版本清单格式异常：缺少 versions 数组")

            versions.mapNotNull { item ->
                val id = Json.stringAt(item, "id") ?: return@mapNotNull null
                val type = Json.stringAt(item, "type") ?: return@mapNotNull null
                DownloadableVersion(
                    id = id,
                    type = type,
                    category = classify(id, type),
                )
            }
        }.fold(
            onSuccess = { VersionListResult.Success(it) },
            onFailure = { VersionListResult.Failed(it.describe()) },
        )
    }

    /** 取一段文本。超时给足 —— 版本清单有几百 KB。 */
    private fun fetchText(url: String): String {
        val conn = java.net.URI(url).toURL().openConnection().apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "HMWLX")
        }
        return conn.getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
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
