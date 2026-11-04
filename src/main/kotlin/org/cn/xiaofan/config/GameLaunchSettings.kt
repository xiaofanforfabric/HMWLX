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

package org.cn.xiaofan.config

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 单个游戏版本的启动设置：内存、进程优先级、自定义 JVM 参数。
 *
 * <h2>为什么按版本存，而不是全局一套</h2>
 *
 * 整合包和原版对内存的需求差着一个数量级：原版 1G 够用，200 个模组的
 * 整合包 8G 都不一定够。全局一套的话，用户每次换版本都得回来改一遍，
 * 忘了改就是 `OutOfMemoryError` —— 而这个错误信息对普通玩家毫无指导意义。
 *
 * <h2>存哪里</h2>
 *
 * 与 [AppConfig] 同一个配置文件，键名带版本前缀：
 * ```
 * GAME.<versionId>.MAX_MEMORY_MB=4096
 * GAME.<versionId>.MIN_MEMORY_MB=1024
 * GAME.<versionId>.PRIORITY=0
 * GAME.<versionId>.JVM_ARGS=-XX:+UseG1GC
 * ```
 *
 * 不单独开一个文件：配置项少，多一个文件就多一处可能写坏的地方。
 * 复用 [AppConfig] 的读写与原子替换逻辑，风险最低。
 *
 * <h2>默认值全部是「不干预」</h2>
 *
 * 默认 [maxMemoryMb] 为 null，表示**不传 `-Xmx`**，让 JVM 用自己
 * 的默认值（物理内存的 1/4）；默认优先级 0，即不调 `nice`。
 * 理由：没被用户动过的设置不该悄悄改变游戏行为，否则出了性能问题
 * 根本不知道是模组还是启动器干的。
 *
 * @param maxMemoryMb 最大堆内存（MB）。null = 使用 JVM 默认值
 * @param minMemoryMb 初始堆内存（MB）。null = 使用 JVM 默认值
 * @param priority 进程优先级（nice 值），-20..19。0 = 不调整
 * @param jvmArgs 自定义 JVM 参数，原样追加
 */
data class GameLaunchSettings(
    val maxMemoryMb: Int? = null,
    val minMemoryMb: Int? = null,
    val priority: Int = DEFAULT_PRIORITY,
    val jvmArgs: String = "",
) {

    /** 是否全部保持默认（此时不写盘，也不做任何干预）。 */
    val isDefault: Boolean
        get() = maxMemoryMb == null &&
            minMemoryMb == null &&
            priority == DEFAULT_PRIORITY &&
            jvmArgs.isBlank()

    /**
     * 把自定义 JVM 参数拆成单个 token。
     *
     * <p>按空白切分，但**尊重单双引号**：`-Dfoo="a b"` 必须保住内部空格，
     * 否则 `a b` 会被拆成两个参数，游戏直接启动失败。
     * 这是命令行解析最容易踩的坑，所以单独处理，不图省事用 `split(" ")`。
     *
     * <p>空 token 与未闭合的引号都会被丢弃 —— 未闭合的引号是用户输错了，
     * 与其猜他想写什么，不如让这一项失效，其余的照常传。
     */
    fun parsedJvmArgs(): List<String> {
        val text = jvmArgs.trim()
        if (text.isEmpty()) return emptyList()

        val result = mutableListOf<String>()
        val current = StringBuilder()
        var quote: Char? = null

        for (ch in text) {
            when {
                quote != null -> if (ch == quote) quote = null else current.append(ch)
                ch == '"' || ch == '\'' -> quote = ch
                ch.isWhitespace() -> {
                    if (current.isNotEmpty()) {
                        result += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(ch)
            }
        }
        // 只在引号已闭合时收尾；未闭合则丢弃残留，不传半截参数。
        if (current.isNotEmpty() && quote == null) result += current.toString()
        return result
    }

    /**
     * 生成要注入 `Profile.vmArgs` 的参数列表。
     *
     * <p>顺序有讲究：`-Xms` 必须在 `-Xmx` 前 —— 反过来的话，某些 JVM
     * 实现会先按 `-Xmx` 分配初始堆，再读到更小的 `-Xms` 就报
     * `Initial heap size set to a larger value than the maximum heap size`
     * 之外的反向错误。保持「小到大」的顺序最安全。
     *
     * <p>优先级的**不**在这里生成：nice 值不是 JVM 参数，
     * 要在进程启动后另外调，见 `ProcessPriority`。
     */
    fun toVmArgs(): List<String> {
        val args = mutableListOf<String>()
        minMemoryMb?.let { args += "-Xms${it}M" }
        maxMemoryMb?.let { args += "-Xmx${it}M" }
        args += parsedJvmArgs()
        return args
    }

    /**
     * 规整到合法范围。
     *
     * <p>存在的意义：界面输入框允许用户随便打字（包括负数和天文数字），
     * 而 `-Xmx-5M` 会让 JVM 直接拒绝启动，且拒绝信息在几百行日志里
     * 很难被发现。入口处统一夹逼，比在启动失败后去查日志友好得多。
     *
     * @param physicalMemoryMb 物理内存（MB），用于上限封顶
     */
    fun normalized(physicalMemoryMb: Long): GameLaunchSettings {
        val ceilingMb = if (physicalMemoryMb > 0) {
            (physicalMemoryMb * MAX_MEMORY_RATIO).toInt().coerceIn(512, MAX_MEMORY_MB)
        } else {
            MAX_MEMORY_MB
        }

        val max = maxMemoryMb?.coerceIn(MIN_MEMORY_MB, ceilingMb)
        val min = minMemoryMb?.coerceIn(MIN_MEMORY_MB, ceilingMb)
            // 初始堆不能大于最大堆：JVM 会直接拒绝启动。
            //
            // 只设了 min 没设 max 的情况（`max == null`）不夹逼 —— 此时
            // max 交给 JVM 自己决定，我们无权假设它的值。用户可能故意
            // 用 `-Xms4G` 而不设 `-Xmx`，压成别的值反而错了。
            ?.let { m -> if (max != null && m > max) max else m }

        return copy(
            maxMemoryMb = max,
            minMemoryMb = min,
            priority = priority.coerceIn(MIN_PRIORITY, MAX_PRIORITY),
        )
    }

    companion object {
        /** 默认优先级：不调整。 */
        const val DEFAULT_PRIORITY = 0

        /** nice 值范围。负数需要 root，正常情况下用户只会用到 0..19。 */
        const val MIN_PRIORITY = -20
        const val MAX_PRIORITY = 19

        /** 最小堆内存下限（MB）。再小游戏起不来，给了也是坑。 */
        const val MIN_MEMORY_MB = 256

        /** 最大堆内存硬上限（MB），防止 `-Xmx999999M` 这种输入。 */
        const val MAX_MEMORY_MB = 65536

        /**
         * 物理内存的可用比例。
         *
         * <p>0.8 而不是 1.0：JVM 的 `-Xmx` 只是堆，堆之外还有元空间、
         * 线程栈、堆外缓冲、显卡驱动映射。留给操作系统的部分不够时，
         * Linux 的 OOM Killer 会直接杀掉游戏进程 —— 表现为"游戏无缘无故闪退"，
         * 用户完全猜不到是内存设太大导致的。
         */
        const val MAX_MEMORY_RATIO = 0.8
    }
}

/**
 * 按版本存取 [GameLaunchSettings]。
 *
 * <p>键名前缀 `GAME.<versionId>.`。versionId 里可能有 `-` 和 `.`
 * （如 `1.21.4-forge-54.0.16`），这些字符在 properties 的键里都合法，
 * 不需要转义。
 */
object GameLaunchSettingsStore {

    private const val PREFIX = "GAME."

    /**
     * 读取某版本的设置。
     *
     * <p>读不到、格式坏了、版本号非法 —— 一律返回默认值（也就是"不干预"）。
     * 配置层的任何问题都不该阻止游戏启动。
     */
    fun load(versionId: String): GameLaunchSettings {
        if (!isValidVersionId(versionId)) return GameLaunchSettings()
        val base = "$PREFIX$versionId."

        return runCatching {
            GameLaunchSettings(
                maxMemoryMb = AppConfig.readValue(base + "MAX_MEMORY_MB")?.toIntOrNull(),
                minMemoryMb = AppConfig.readValue(base + "MIN_MEMORY_MB")?.toIntOrNull(),
                priority = AppConfig.readValue(base + "PRIORITY")?.toIntOrNull()
                    ?: GameLaunchSettings.DEFAULT_PRIORITY,
                jvmArgs = AppConfig.readValue(base + "JVM_ARGS").orEmpty(),
            )
        }.getOrDefault(GameLaunchSettings())
    }

    /**
     * 保存某版本的设置。
     *
     * <p>若设置已是默认值，则**删除**对应键而不是写默认值进文件 ——
     * 配置文件是给用户看的，堆满 `PRIORITY=0` 这种无效行只会增加噪音，
     * 也会让人误以为这些项被"设置"过。
     *
     * @return 是否写入成功。失败不抛异常。
     */
    fun save(versionId: String, settings: GameLaunchSettings): Boolean {
        if (!isValidVersionId(versionId)) return false
        val base = "$PREFIX$versionId."

        val updates = mapOf(
            base + "MAX_MEMORY_MB" to settings.maxMemoryMb?.toString(),
            base + "MIN_MEMORY_MB" to settings.minMemoryMb?.toString(),
            base + "PRIORITY" to settings.priority
                .takeIf { it != GameLaunchSettings.DEFAULT_PRIORITY }
                ?.toString(),
            base + "JVM_ARGS" to settings.jvmArgs.trim().takeIf { it.isNotEmpty() },
        )
        return AppConfig.updateValues(updates)
    }

    /**
     * 版本号是否可以作为键的一部分。
     *
     * <p>排除换行、等号、和控制字符：这些会破坏 properties 的行结构，
     * 一个带 `\n` 的版本号能把整个配置文件写坏 —— 而版本号来自网络，
     * 不能无条件信任。
     */
    internal fun isValidVersionId(versionId: String): Boolean {
        if (versionId.isBlank()) return false
        if (versionId.length > 128) return false
        return versionId.none { it == '\n' || it == '\r' || it == '=' || it.isISOControl() }
    }
}

/**
 * 物理内存探测。
 *
 * <p>给界面画滑块上限用。优先用 `cgroup` 的限制值 —— 容器里
 * `OperatingSystemMXBean` 报的是宿主机的内存，直接拿它当上限会让
 * 用户在 2G 的容器里把 `-Xmx` 设成 24G。
 */
object PhysicalMemory {

    /**
     * 探测可用物理内存（MB）。
     *
     * <p>三级回落，任一级成功即返回：
     * 1. cgroup v2 `memory.max`
     * 2. cgroup v1 `memory.limit_in_bytes`
     * 3. `com.sun.management.OperatingSystemMXBean.getTotalMemorySize()`
     *
     * <p>全部失败返回 0，调用方应把 0 当作「未知」处理，不要拿它算上限。
     */
    fun detectMb(): Long {
        cgroupLimitMb()?.let { return it }
        return runCatching {
            val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
            // 必须转型到 com.sun.management 接口再调，不能用反射。
            //
            // 反射拿到的 Method 属于实现类 com.sun.management.internal.OperatingSystemImpl，
            // 那个包在 JPMS 里不是导出包，invoke 会抛 IllegalAccessException；
            // 而接口 com.sun.management.OperatingSystemMXBean 是导出的，
            // 通过它调用不受限制。
            val sunBean = bean as? com.sun.management.OperatingSystemMXBean ?: return 0L
            val bytes = sunBean.totalMemorySize
            if (bytes > 0) bytes / (1024 * 1024) else 0L
        }.getOrDefault(0L)
    }

    /** 读 cgroup 的内存上限，单位换算成 MB。 */
    private fun cgroupLimitMb(): Long? {
        val v2 = readFirstLine("/sys/fs/cgroup/memory.max")
        val v1 = readFirstLine("/sys/fs/cgroup/memory/memory.limit_in_bytes")
        return parseLimitMb(v2) ?: parseLimitMb(v1)
    }

    /**
     * 解析 cgroup 的 limit 文件内容。
     *
     * <p>`max` 表示不限 —— 返回 null 让调用方继续回落。
     * v1 的「不限」是一个天文数字（接近 2^63），同样当作不限。
     */
    private fun parseLimitMb(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty() || text == "max") return null
        val bytes = text.toLongOrNull() ?: return null
        if (bytes <= 0) return null
        // v1 用 9223372036854771712 表示不限，任何超过 1 PiB 的都当作不限。
        if (bytes > 1L shl 50) return null
        return bytes / (1024 * 1024)
    }

    private fun readFirstLine(path: String): String? =
        runCatching { File(path).readLines(StandardCharsets.UTF_8).firstOrNull() }.getOrNull()
}
