/*
 * HMWLX - Hello My World Launcher X
 * Copyright (C) 2026 xiaofan
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * SPDX-License-Identifier: GPL-3.0-only
 */

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
import org.cn.xiaofan.version.VersionType
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
     * <p>用 `piston-meta` 而不是老的 `launchermeta`：后者是旧的入口，
     * 官方正在往下迁，早晚失效。数据内容一致。
     */
    private const val MANIFEST_URL =
        "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"

    /**
     * 按 Mojang 的 type 与版本号算出界面分类。
     *
     * <p>愚人节判定复用 [VersionType.isAprilFools]，**不在这里另维护一张表**：
     * 两张表迟早会不同步，同一个版本会在「已安装列表」和「下载列表」里
     * 显示成两种分类，用户看着莫名其妙。
     */
    fun classify(id: String, type: String): VersionCategory = when (type) {
        "release" -> VersionCategory.RELEASE
        "old_beta", "old_alpha" -> VersionCategory.LEGACY
        else -> if (VersionType.isAprilFools(id)) {
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
     * <h2>重复安装的行为（已核对上游源码）</h2>
     *
     * `AbstractClientInstaller.installClient` 有两层判断：
     *
     * 1. `versionMatcherService.match(id, installed, ...)` —— 已装的版本命中后
     *    **直接 return**，不下载、不写文件。这是常态路径。
     * 2. `VanillaInstallerImpl.downloadVersion` 里的 `Files.exists(version.json)`
     *    —— 命中只跳过下载 JSON，仍会走 `installClient` 写文件、解 natives。
     *    这层只在「JSON 在、但 matcher 没认出来」时才走到。
     *
     * <p>所以重复点同一个版本一般不会重下几百 MB。但上游返回的 `Version`
     * 没有可靠的「本次是否真的写盘了」标志，我们**不猜**：装完后真的去本地
     * 扫一遍，扫到才报成功（见 [verifyInstalledAfter]）。
     *
     * @param versionId 版本号，如 `1.21.4`
     * @param onProgress 进度回调，参数是当前阶段的描述文字。下载耗时较长，
     *   界面需要反馈，否则用户以为卡住了。回调在 IO 线程上触发。
     */
    suspend fun install(
        versionId: String,
        onProgress: (String) -> Unit = {},
    ): InstallResult = installWith(versionId, loader = null, build = null, onProgress = onProgress)

    /**
     * 安装指定加载器 + 构建的版本。
     *
     * <h2>和原版安装的区别</h2>
     *
     * 原版只需要版本号；带加载器时还要给 `platform` 和 `build`，
     * 因为同一个 MC 版本下可能有很多加载器构建（Fabric 0.16.9、0.16.8…）。
     *
     * `VersionArg.withPlatform("fabric").withBuild("0.16.9")` 表达的就是
     * 「fabric 加载器、构建 0.16.9、针对版本 X」。
     *
     * <h2>为什么校验版本号不能沿用原版那套</h2>
     *
     * 原版安装后本地多出一个叫 `1.21.4` 的目录。但 Fabric 装出来的是
     * **另一个版本**，名字由上游决定（通常形如 `fabric-loader-0.16.9-1.21.4`），
     * 不是 `1.21.4`。所以不能拿 `versionId` 去本地扫 —— 得用上游返回的
     * `Version` 对象里能确定的标识去扫，扫不到就退化为「只要本地版本数变多了
     * 就算成功」，并且明确告诉用户装出来叫什么。
     *
     * @param loader 加载器；null 表示原版
     * @param build 加载器构建标识；`loader != null` 时应给出，
     *   null 表示让上游自己挑最新
     */
    suspend fun installWith(
        versionId: String,
        loader: ModLoaderService.Loader?,
        build: String?,
        onProgress: (String) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        runCatching {
            val platformService = HeadlessMcBootstrap.bean(PlatformService::class.java)

            onProgress("正在解析版本 $versionId …")

            // 构造 VersionArg 时显式指定 side=client。
            // 不指定 platform 时上游会按自己的默认值走，不同版本默认值可能不同，
            // 显式写死可以避免升级后行为漂移。
            //
            // 带加载器时再补 platform 和 build —— 顺序有讲究：
            // 先 withPlatform 再 withBuild，与上游 builder 的字段语义一致。
            var arg = VersionArg.parse(listOf(versionId))
                .withSide(io.github.headlesshq.headlessmc.version.arg.Side.CLIENT)

            if (loader != null) {
                arg = arg.withPlatform(loader.platformName)
                if (build != null) {
                    arg = arg.withBuild(build)
                }
            }

            val id = VersionID.resolve(platformService, arg)
            val platform: Platform = id.platform
            val installer: ClientInstaller = platform.clientSupport
                .map(ClientSupport::installer)
                .orElseThrow {
                    IllegalStateException("平台 ${platform.name} 不支持下载客户端")
                }

            val dir: Path = GameVersionService.resolveGameDir().toPath()
            val what = if (loader == null) versionId else "${loader.displayName} $build / $versionId"
            onProgress("正在下载 $what 到 $dir …")

            // 装之前先记下本地已有版本，用于装完之后判断「多了什么」。
            // 原版可以直接按版本号扫，但加载器装出来的名字上游自有规则，
            // 差集是唯一可靠的办法。
            val before = scanVersionIds()

            // 空 TypedMap = 不强装、不用自定义 URL。
            // 如果以后要支持「重新下载」，在这里 put FORCE_INSTALL=true。
            installer.installClient(id, dir, TypedMapImpl())

            onProgress("正在确认安装结果 …")
            verifyInstalledAfter(versionId, loader, before, dir)
        }.fold(
            onSuccess = { it },
            onFailure = { InstallResult.Failed(it.describe()) },
        )
    }

    /** 扫出本地全部版本号；扫描失败返回空集（只用于算差集，不必报错）。 */
    private suspend fun scanVersionIds(): Set<String> =
        when (val found = GameVersionService.scan()) {
            is GameVersionService.VersionScanResult.Success -> found.versions.map { it.id }.toSet()
            is GameVersionService.VersionScanResult.Failed -> emptySet()
        }

    /**
     * 装完后的确认。
     *
     * <p>两条路径：
     * - **原版**：直接查本地有没有 [versionId]，与旧逻辑一致。
     * - **带加载器**：上游装出来的版本名不是 [versionId]（Fabric 是
     *   `fabric-loader-<build>-<mc>`），所以用「装前装后的差集」判断新增了
     *   哪个版本，并把那个名字回报给界面 —— 用户需要知道它叫什么。
     */
    private suspend fun verifyInstalledAfter(
        versionId: String,
        loader: ModLoaderService.Loader?,
        before: Set<String>,
        dir: Path,
    ): InstallResult = withContext(Dispatchers.IO) {
        when (val found = GameVersionService.scan()) {
            is GameVersionService.VersionScanResult.Success -> {
                val now = found.versions.map { it.id }.toSet()

                if (loader == null) {
                    return@withContext if (versionId in now) {
                        InstallResult.Success(versionId)
                    } else {
                        InstallResult.Failed(
                            "安装流程结束了，但在 $dir 里找不到版本 $versionId。" +
                                "可能是磁盘权限或上游安装器异常，请查看日志。",
                        )
                    }
                }

                // 带加载器：优先看差集。差集为空时，可能是重复安装（上游
                // matcher 命中直接 return）—— 此时本地已有该版本，不算失败。
                val added = now - before
                when {
                    added.isNotEmpty() -> InstallResult.Success(added.first())

                    // 没新增，但本地版本数没变：多半是重复安装，上游幂等跳过了。
                    // 具体是哪个只能靠名字猜（上游命名规则见各平台 installer），
                    // 猜不到就如实说「没有变化」而不是谎报成功。
                    else -> {
                        val guess = now.firstOrNull {
                            it.contains(loader.platformName, ignoreCase = true) &&
                                it.contains(versionId)
                        }
                        if (guess != null) {
                            InstallResult.Success(guess)
                        } else {
                            InstallResult.Failed(
                                "安装流程结束了，但本地版本没有变化，也找不到 $versionId 的" +
                                    "${loader.displayName} 版本。请查看日志确认上游行为。",
                            )
                        }
                    }
                }
            }

            is GameVersionService.VersionScanResult.Failed -> InstallResult.Failed(
                "安装流程结束了，但扫描本地版本失败：${found.reason}",
            )
        }
    }

    /**
     * 确认版本真的落到磁盘上了。
     *
     * <p>为什么要这一步：上游 `installClient` 不抛异常时也可能什么都没装
     * （matcher 认错、目录不可写但错误被吞）。直接报「安装完成」而版本列表里
     * 没有，比报错更让人困惑 —— 用户会以为列表坏了，反复重试。
     *
     * <p>扫的是与界面**同一个目录、同一套扫描逻辑**（[GameVersionService.scan]），
     * 所以这里能扫到就等于界面能扫到。这同时验证了
     * 「安装目录与扫描目录一致」这个前提 —— 万一不一致，会在这里暴露，
     * 而不是等用户发现列表是空的。
     */
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
