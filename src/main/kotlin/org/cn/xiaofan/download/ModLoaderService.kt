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
import java.net.SocketException
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

    /** 网络层瞬时失败的重试次数（含首次）。 */
    private const val MAX_ATTEMPTS = 3

    /** 重试间隔基数，实际间隔按次数递增。 */
    private const val RETRY_DELAY_MS = 600L

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
     * <h2>这套规则比看上去复杂</h2>
     *
     * NeoForge 2023 年从 Forge 分叉，版本号规则**改过两次**，
     * 而且 Minecraft 自己的版本号方案也在变。以下是核实过的对应关系：
     *
     * | MC 版本 | NeoForge 版本号 | 说明 |
     * |---|---|---|
     * | 1.20.1 | `47.1.x` | 沿用 Forge 47.x 的号，唯一特例 |
     * | 1.20.2 ~ 1.21.11 | `20.2.x` ~ `21.11.x` | 三段：`<minor>.<patch>.<build>` |
     * | **26.1 及以后** | `26.1.0.x` | **四段**：前三段是 MC 版本，第四段是构建号 |
     *
     * <p>最后一条来自 NeoForge 官方 26.1 发布说明原文：
     * "NeoForge versions now have four components to match the new versioning
     * system of Minecraft! The first three components identify the Minecraft
     * version, the last the NeoForge release." —— 从这时起 MC 自己的版本号
     * 也从 `1.21.4` 变成了 `26.1` 这种两段式。
     *
     * <p>前两版实现分别栽在这两处：先漏了 1.20.1 的 47.x 特例，
     * 又假设 MC 版本永远是三段。所以这里对每个分支都写清依据。
     *
     * @return 版本前缀（如 `21.4.`、`26.1.0.`、`47.1.`）；不可换算返回 null
     */
    internal fun neoforgePrefix(vanillaVersion: String): String? {
        val parts = vanillaVersion.split('.')

        // 快照（24w14a）、远古（a1.2.6）等非数字格式直接放弃。
        if (parts.any { it.toIntOrNull() == null }) return null

        val nums = parts.mapNotNull { it.toIntOrNull() }

        // ---- 新方案：两段式 MC 版本（26.1、26.3 …）----
        //
        // NeoForge 对应版本是四段：`26.1.0.10`、`26.3.0.101`。
        // 官方说明：「前三段标识 MC 版本，第四段是 NeoForge 构建号」。
        // 也就是说 MC `26.1` 对应 NeoForge 的 `26.1.0.<build>` —— 第三段是
        // MC 的 hotfix 号，`26.1` 本身没有 hotfix 所以是 0。
        //
        // 前缀取三段 `26.1.0.` 而不是两段 `26.1.`：后者是 26.10.x 的子串，
        // 会把 MC 26.10 的构建误算进 MC 26.1。
        if (nums.size == 2) {
            // 只有 26 以后才用两段式；`1.21` 这种两段写法是非法的 MC 版本。
            return if (nums[0] >= 26) "${nums[0]}.${nums[1]}.0." else null
        }

        // ---- 中间过渡：三段式但已进入新方案（26.1.1 这类 MC hotfix）----
        //
        // 这一步是推断，未经实测确认。依据是官方「前三段标识 MC 版本」——
        // 若 MC 出了 26.1.1，NeoForge 版本号应是 `26.1.1.<build>`。
        // 由于建这个功能时 maven.neoforged.net 不可达，无法验证；
        // 万一规则不同，最多表现为这个版本显示「不支持」，不会给出错误的
        // 构建列表（前缀匹配不到就是空集）。宁可少列，不可错列。
        if (nums.size == 3 && nums[0] >= 26) {
            return "${nums[0]}.${nums[1]}.${nums[2]}."
        }

        // ---- 老方案：三段式 MC 版本（1.21.4 …）----
        if (nums.size != 3) return null

        val major = nums[0]
        // NeoForge 从 MC 1.20.1 才开始，主版本号必然是 1。
        if (major != 1) return null

        val minor = nums[1]
        val patch = nums[2]

        // 早于 1.20.1 的没有 NeoForge（那时它还是 Forge 的一部分）。
        if (minor < 20 || (minor == 20 && patch < 1)) return null

        // 特例：1.20.1 沿用 Forge 的 47.1.x 版本号。
        // NeoForge 首个发布版就叫 47.1.3，为了和 Forge 47.1.x 对应。
        // 从 1.20.2 起才改成 <minor>.<patch>.<build>。
        if (minor == 20 && patch == 1) return "47.1."

        return "$minor.$patch."
    }

    // ------------------------------------------------------------------ HTTP

    /**
     * 拉取文本，带重试。
     *
     * <p>`maven.minecraftforge.net` 经常在 TLS 握手阶段直接 reset 连接
     * （实测 `Connection reset`，不是超时也不是 4xx）。这类瞬时失败
     * 重试一次通常就好了，而让用户手动点「重新查询」体验很差。
     *
     * <p>只对**网络层异常**重试；HTTP 状态码错误（4xx/5xx）不重试 ——
     * 那是服务端明确的答复，重试也只会得到同样结果。
     */
    private fun fetchText(url: String): String {
        var lastError: Exception? = null

        repeat(MAX_ATTEMPTS) { attempt ->
            try {
                return fetchTextOnce(url)
            } catch (e: SocketException) {
                // Connection reset / Connection refused / Broken pipe
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    // 退避一点点：立刻重连往往会撞上同一台后端
                    runCatching { Thread.sleep(RETRY_DELAY_MS * (attempt + 1)) }
                }
            } catch (e: java.io.IOException) {
                // 超时、TLS 握手失败等同样属于瞬时问题
                lastError = e
                if (attempt < MAX_ATTEMPTS - 1) {
                    runCatching { Thread.sleep(RETRY_DELAY_MS * (attempt + 1)) }
                }
            }
        }

        throw lastError ?: IllegalStateException("请求失败：$url")
    }

    /**
     * 拉取文本（单次尝试）。
     *
     * <p>非 2xx 时会读 body：**Fabric 用 `400` 表达「这个 MC 版本我没构建」**，
     * body 是 `[]`。这是「不支持」而不是「查询失败」——
     * 把它当失败会让用户在远古版上反复点重试，永远好不了。
     *
     * <p>判别规则：4xx + body 是空 JSON 数组 → 视为「没有数据」，
     * 返回 `[]` 交给上层判为 Unsupported；其余非 2xx → 真失败。
     */
    private fun fetchTextOnce(url: String): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("User-Agent", "HMWLX")
            instanceFollowRedirects = true
        }

        try {
            val code = connection.responseCode
            if (code in 200..299) {
                return connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            }

            // 非 2xx：先把 body 读出来再说。
            // 注意要读 errorStream 而不是 inputStream —— 后者在 4xx/5xx 上会抛
            // FileNotFoundException，拿不到 body。
            val body = connection.errorStream
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                ?.trim()
                .orEmpty()

            // 4xx + 空数组 = 服务端明确说「这个版本没有对应的东西」。
            // Fabric 就是这么实现的（实测 1.13.2 / 1.8.9 / 1.0 均返回 400 + []）。
            if (code in 400..499 && body == "[]") {
                return "[]"
            }

            // 其它非 2xx 就是真失败，把 body 带上以便排查
            // （限流、认证失败等都会带一段说明文字）。
            throw IllegalStateException(
                if (body.isEmpty()) "HTTP $code"
                else "HTTP $code：${body.take(120)}",
            )
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
