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

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.cn.xiaofan.config.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * 模组加载器查询。
 *
 * <h2>为什么不走上游的 VersionService.getBuilds()</h2>
 *
 * 因为它是坏的。反编译 `FabricVersionService`（0df01d2043）的实际字节码是：
 *
 * ```java
 * public SequencedSet<PlatformVersion> getBuilds(VanillaVersion v) {
 *     return getVersions();      // ← 参数完全没用上
 * }
 * ```
 *
 * 也就是说它返回的是**加载器自身的全部版本**（fabric/0.20.1、fabric/0.20.0…），
 * 而不是「针对某个 MC 版本的构建」。实测对 `a1.2.6`（最老的 alpha 之一）
 * 会返回 255 个构建 —— 全部是假的。拿它当兼容性判据，界面会给用户
 * 提供根本装不上的选项。
 *
 * <p>处理方式和版本列表一样：**上游给的数据不可靠，就自己拉官方 API**。
 * （版本列表那边也是因为上游 `VanillaVersionServiceImpl.map()` 丢了 type 字段
 * 才自己拉 Mojang manifest 的。）
 *
 * <h2>三个加载器各自的 API</h2>
 *
 * | 加载器 | 查询方式 | 「不支持」的表现 |
 * |---|---|---|
 * | Fabric | `meta.fabricmc.net/v2/versions/loader/<mc>` | 返回 `[]` |
 * | Forge | Maven 元数据里过滤 `<mc>-<forge>` 形式的版本 | 过滤后为空 |
 * | NeoForge | Maven 元数据按 `<mcMinor>.<mcPatch>.` 前缀推导 | 过滤后为空 |
 *
 * <p>关键是**「不支持」和「网络失败」必须严格区分**：
 * 前者是正常结果（远古版本来就没有任何人做加载器），界面应置灰；
 * 后者要让用户知道可以重试。
 */
object ModLoaderService {

    /**
     * 客户端模组加载器。
     *
     * <p>`platformName` 必须与上游 `Platform.getName()` 完全一致（小写），
     * 安装时会用它构造 `VersionArg.withPlatform()`。
     */
    enum class Loader(val platformName: String, val displayName: String) {
        FABRIC("fabric", "Fabric"),
        FORGE("forge", "Forge"),
        NEOFORGE("neoforge", "NeoForge"),
    }

    /** 某个加载器对某个 MC 版本的支持情况。 */
    sealed interface Support {
        /** 支持。`builds` 从新到旧，元素是构建标识（如 Fabric 的 `0.16.9`）。 */
        data class Available(
            val loader: Loader,
            val builds: List<String>,
        ) : Support

        /** 该加载器不提供这个 MC 版本的构建。正常结果，不是错误。 */
        data class Unsupported(val loader: Loader) : Support

        /** 查询失败（网络问题等）。与 [Unsupported] 严格区分。 */
        data class Failed(val loader: Loader, val reason: String) : Support
    }

    private const val FABRIC_LOADER_URL = "https://meta.fabricmc.net/v2/versions/loader"
    private const val FORGE_METADATA_URL =
        "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml"
    private const val NEOFORGE_METADATA_URL =
        "https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge"

    private const val TIMEOUT_MS = 15_000

    /**
     * 并行查询三个加载器对某个 MC 版本的支持情况。
     *
     * <p>并行而不是串行：一次详情页要查三个，串行的话三个 RTT 叠加，
     * 用户会觉得「点开详情页要转很久」。三个请求互不依赖。
     *
     * @param vanillaVersion MC 版本号，如 `1.21.4`、`a1.2.6`
     * @return 每个加载器一条结果，顺序与 [Loader.entries] 一致
     */
    suspend fun querySupport(vanillaVersion: String): List<Support> =
        withContext(Dispatchers.IO) {
            coroutineScope {
                Loader.entries.map { loader ->
                    async { queryOne(loader, vanillaVersion) }
                }.map { it.await() }
            }
        }

    private fun queryOne(loader: Loader, vanillaVersion: String): Support = try {
        val builds = when (loader) {
            Loader.FABRIC -> fetchFabricBuilds(vanillaVersion)
            Loader.FORGE -> fetchForgeBuilds(vanillaVersion)
            Loader.NEOFORGE -> fetchNeoForgeBuilds(vanillaVersion)
        }

        if (builds.isEmpty()) {
            Support.Unsupported(loader)
        } else {
            Support.Available(loader, builds)
        }
    } catch (e: Exception) {
        Support.Failed(loader, e.describeModLoaderError())
    }

    // ------------------------------------------------------------------ Fabric
    //
    // GET /v2/versions/loader/<mc>
    // 返回数组，每项形如：
    //   { "loader": { "version": "0.16.9", "stable": true, ... }, "intermediary": {...} }
    //
    // 不支持这个 MC 版本时返回 `[]`（已实测 a1.2.6）。
    // 顺序天然是从新到旧。
    private fun fetchFabricBuilds(vanillaVersion: String): List<String> {
        val json = fetchText("$FABRIC_LOADER_URL/${urlEncode(vanillaVersion)}")
        val array = Json.parse(json) as? List<*> ?: return emptyList()

        return array.mapNotNull { item ->
            Json.objectAt(item, "loader")?.let { Json.stringAt(it, "version") }
        }
    }

    // ------------------------------------------------------------------ Forge
    //
    // Forge 没有「按 MC 版本查」的 REST API，只有一份全局 maven-metadata.xml，
    // 里面是所有版本的 `<version>1.21.4-54.0.16</version>`。
    // 过滤条件：以 `<mc>-` 开头。
    //
    // 注意：远古版本 Forge 的命名是 `1.7.10-10.13.4.1614-1.7.10` 这种
    // 双 MC 版本的形式，仍然以 `<mc>-` 开头，能正确命中。
    private fun fetchForgeBuilds(vanillaVersion: String): List<String> {
        val xml = fetchText(FORGE_METADATA_URL)

        val prefix = "$vanillaVersion-"
        return Regex("<version>([^<]+)</version>")
            .findAll(xml)
            .mapNotNull { it.groupValues.getOrNull(1) }
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .toList()
            .reversed() // maven-metadata 是升序，反转让最新的在前
    }

    // ------------------------------------------------------------------ NeoForge
    //
    // NeoForge 的版本号是 `<mcMinor>.<mcPatch>.<build>`，例如 MC 1.21.4 对应
    // `21.4.x`。它不是 `<mc版本>-<构建>` 的形式，所以要换算。
    //
    // 这个换算规则是 NeoForge 官方约定，但对 1.20.1 之前的版本不适用
    // （那些是 Forge 的天下），所以换算失败就直接视为不支持。
    private fun fetchNeoForgeBuilds(vanillaVersion: String): List<String> {
        val prefix = neoforgePrefix(vanillaVersion) ?: return emptyList()

        val json = fetchText(NEOFORGE_METADATA_URL)
        val root = Json.parse(json) ?: return emptyList()
        val versions = Json.objectAt(root, "versions") ?: return emptyList()

        return versions
            .mapNotNull { it as? String }
            .filter { it.startsWith(prefix) }
            .reversed() // 升序 -> 最新在前
    }

    /**
     * 把 MC 版本换算成 NeoForge 的版本前缀。
     *
     * @return 如 `1.21.4` -> `21.4.`；不可换算（远古版、快照、格式不符）返回 null
     */
    internal fun neoforgePrefix(vanillaVersion: String): String? {
        // 只处理纯数字点分的正式版。快照（24w14a）、远古（a1.2.6）返回 null。
        val parts = vanillaVersion.split('.')
        if (parts.size != 3) return null
        if (parts.any { it.toIntOrNull() == null }) return null

        val major = parts[0].toIntOrNull() ?: return null
        // NeoForge 从 MC 1.20.1 才开始，主版本号必然是 1。
        if (major != 1) return null

        val minor = parts[1].toIntOrNull() ?: return null
        val patch = parts[2].toIntOrNull() ?: return null
        // 1.20.1 是 NeoForge 的首个版本，之前的没有。
        if (minor < 20 || (minor == 20 && patch < 1)) return null

        return "$minor.$patch."
    }

    // ------------------------------------------------------------------ HTTP

    private fun fetchText(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "HMWLX")
            instanceFollowRedirects = true
        }

        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code")
            }
            return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8)
}

/** 把异常整理成一句能显示给用户的话。 */
internal fun Throwable.describeModLoaderError(): String {
    val message = message
    return if (message.isNullOrBlank()) {
        javaClass.simpleName
    } else {
        "$message（${javaClass.simpleName}）"
    }
}
