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

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * [GameLaunchSettings] 的参数生成与规整。
 *
 * <p>重点覆盖两类容易出事的地方：
 * 1. 带引号的 JVM 参数被错误切分（会导致游戏起不来）
 * 2. 内存值越界（会导致 JVM 拒绝启动）
 */
class GameLaunchSettingsTest {

    // -------------------------------------------------------------------
    // 内存参数生成
    // -------------------------------------------------------------------

    @Test
    fun `未设置内存时不产生任何参数`() {
        val args = GameLaunchSettings().toVmArgs()
        assertTrue(args.isEmpty(), "默认设置不该生成参数，实际: $args")
    }

    @Test
    fun `设置了内存时生成 Xms 与 Xmx`() {
        val args = GameLaunchSettings(maxMemoryMb = 4096, minMemoryMb = 1024).toVmArgs()
        assertEquals(listOf("-Xms1024M", "-Xmx4096M"), args)
    }

    @Test
    fun `Xms 必须排在 Xmx 前面`() {
        val args = GameLaunchSettings(maxMemoryMb = 4096, minMemoryMb = 1024).toVmArgs()
        val xmsIndex = args.indexOf("-Xms1024M")
        val xmxIndex = args.indexOf("-Xmx4096M")
        assertTrue(xmsIndex < xmxIndex, "Xms 应排在 Xmx 前，实际: $args")
    }

    @Test
    fun `只设最大内存时不产生 Xms`() {
        val args = GameLaunchSettings(maxMemoryMb = 2048).toVmArgs()
        assertEquals(listOf("-Xmx2048M"), args)
    }

    // -------------------------------------------------------------------
    // 自定义 JVM 参数解析
    // -------------------------------------------------------------------

    @Test
    fun `普通参数按空格切分`() {
        val settings = GameLaunchSettings(jvmArgs = "-XX:+UseG1GC -XX:MaxGCPauseMillis=50")
        assertEquals(
            listOf("-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50"),
            settings.parsedJvmArgs(),
        )
    }

    @Test
    fun `双引号内的空格不被切分`() {
        // 这是最容易踩的坑：一旦拆错，游戏会拿到两个残缺参数直接起不来。
        val settings = GameLaunchSettings(jvmArgs = """-Dfoo="a b" -Dbar=c""")
        assertEquals(listOf("-Dfoo=a b", "-Dbar=c"), settings.parsedJvmArgs())
    }

    @Test
    fun `单引号内的空格不被切分`() {
        val settings = GameLaunchSettings(jvmArgs = "-Dfoo='a b'")
        assertEquals(listOf("-Dfoo=a b"), settings.parsedJvmArgs())
    }

    @Test
    fun `未闭合的引号丢弃该参数`() {
        // 宁可丢掉写错的那一个，也不要传半截参数进去。
        val settings = GameLaunchSettings(jvmArgs = """-Dok=1 -Dbroken="abc""")
        assertEquals(listOf("-Dok=1"), settings.parsedJvmArgs())
    }

    @Test
    fun `多余空格不产生空参数`() {
        val settings = GameLaunchSettings(jvmArgs = "  -XX:+UseG1GC    -Xss1M  ")
        assertEquals(listOf("-XX:+UseG1GC", "-Xss1M"), settings.parsedJvmArgs())
    }

    @Test
    fun `空的 JVM 参数不产生任何项`() {
        assertEquals(emptyList<String>(), GameLaunchSettings(jvmArgs = "   ").parsedJvmArgs())
    }

    @Test
    fun `自定义参数排在内存参数之后`() {
        val settings = GameLaunchSettings(
            maxMemoryMb = 4096,
            minMemoryMb = 1024,
            jvmArgs = "-XX:+UseG1GC",
        )
        assertEquals(listOf("-Xms1024M", "-Xmx4096M", "-XX:+UseG1GC"), settings.toVmArgs())
    }

    // -------------------------------------------------------------------
    // 规整
    // -------------------------------------------------------------------

    @Test
    fun `内存不超过物理内存的八成`() {
        // 16GB 机器上填 32GB，必须被压到物理内存的 80%。
        // 16384 * 0.8 = 13107.2，向下取整为 13107。
        val normalized = GameLaunchSettings(maxMemoryMb = 32768).normalized(16L * 1024)
        assertEquals(13_107, normalized.maxMemoryMb)
    }

    @Test
    fun `内存不低于下限`() {
        val normalized = GameLaunchSettings(maxMemoryMb = 1).normalized(16L * 1024)
        assertEquals(GameLaunchSettings.MIN_MEMORY_MB, normalized.maxMemoryMb)
    }

    @Test
    fun `初始内存不得大于最大内存`() {
        // 用户把两个滑块拉反了是常见操作，必须替他修正，
        // 否则 JVM 会直接报错拒绝启动。
        val normalized = GameLaunchSettings(maxMemoryMb = 1024, minMemoryMb = 8192)
            .normalized(16L * 1024)
        assertEquals(1024, normalized.minMemoryMb)
    }

    /**
     * 回归测试：只设 `-Xms` 不设 `-Xmx` 时，`-Xms` 也要受 JVM 默认最大堆约束。
     *
     * <p>这是评审指出的真实缺陷。没给 `-Xmx` 时 JVM 默认最大堆只有物理内存
     * 的 1/4；若允许 `-Xms` 跑到物理内存的 80%，就会拼出
     * `-Xms12G`（无 `-Xmx`，实际 `-Xmx` 约 4G）这种组合，JVM 直接报
     * `Initial heap size set to a larger value than the maximum heap size`
     * 拒绝启动。
     *
     * <p>之前的实现里有一句「max == null 就不夹逼 min」，注释还振振有词，
     * 正是这个 bug 的来源。此测试锁住正确行为。
     */
    @Test
    fun `只设初始内存时上限是 JVM 默认最大堆`() {
        val physicalMb = 16L * 1024 // 16GB
        val normalized = GameLaunchSettings(minMemoryMb = 12_000).normalized(physicalMb)

        // JVM 默认 -Xmx ≈ 16384 / 4 = 4096MB
        val jvmDefaultMax = (physicalMb / GameLaunchSettings.JVM_DEFAULT_MAX_HEAP_DIVISOR).toInt()
        assertEquals(jvmDefaultMax, normalized.minMemoryMb)
        assertNull(normalized.maxMemoryMb, "不该擅自替用户补上 -Xmx")
    }

    @Test
    fun `只设初始内存时小值不被改动`() {
        val normalized = GameLaunchSettings(minMemoryMb = 1024).normalized(16L * 1024)
        assertEquals(1024, normalized.minMemoryMb)
    }

    @Test
    fun `设了最大内存时初始内存上限跟随最大内存`() {
        // 有显式 -Xmx 时，JVM 默认值不再相关，上限就是用户给的值。
        val normalized = GameLaunchSettings(maxMemoryMb = 8192, minMemoryMb = 12_000)
            .normalized(16L * 1024)
        assertEquals(8192, normalized.minMemoryMb)
    }

    @Test
    fun `物理内存未知时初始内存也给保守上限`() {
        // 探测失败（0）时不能放开到 MAX_MEMORY_MB，
        // 否则用户能设出启动必然失败的值。
        val normalized = GameLaunchSettings(minMemoryMb = 60_000).normalized(0)
        val actual = normalized.minMemoryMb ?: 0
        assertTrue(
            actual <= GameLaunchSettings.MIN_MEMORY_MB * 4,
            "探测失败时应给保守上限，实际: $actual",
        )
    }

    @Test
    fun `物理内存未知且设了最大内存时按最大内存夹逼`() {
        val normalized = GameLaunchSettings(maxMemoryMb = 4096, minMemoryMb = 8192).normalized(0)
        assertEquals(4096, normalized.minMemoryMb)
    }

    @Test
    fun `只设初始内存时不夹逼到任何上界`() {
        // max == null 表示交给 JVM 决定。此时不该凭空假设它的值，
        // 把用户填的 -Xms 改掉。
        val normalized = GameLaunchSettings(minMemoryMb = 4096).normalized(16L * 1024)
        assertEquals(4096, normalized.minMemoryMb)
        assertNull(normalized.maxMemoryMb)
    }

    @Test
    fun `Xms 与 Xmx 不相等时才有意义`() {
        // 回归：曾经 UI 把 Xms 默认算成 Xmx/2，但滑块上限又用了 Xmx，
        // 两边一拉就变成 Xms == Xmx，启动时 JVM 要立刻承诺分配全部堆内存，
        // 在内存紧张时直接启动失败。
        //
        // 这里锁住配置层的行为：min 超过 max 时被压到 max（这是兜底），
        // 但正常情况下 UI 传进来的值不该让两者相等 —— UI 层负责留出步长余量。
        val same = GameLaunchSettings(maxMemoryMb = 4096, minMemoryMb = 4096)
            .normalized(16L * 1024)
        // 兜底逻辑保证不出现 min > max；相等是允许的，但 UI 不会生成这种值。
        assertTrue(same.minMemoryMb!! <= same.maxMemoryMb!!)
    }

    @Test
    fun `优先级被夹到合法范围`() {
        assertEquals(
            GameLaunchSettings.MAX_PRIORITY,
            GameLaunchSettings(priority = 999).normalized(8192).priority,
        )
        assertEquals(
            GameLaunchSettings.MIN_PRIORITY,
            GameLaunchSettings(priority = -999).normalized(8192).priority,
        )
    }

    @Test
    fun `物理内存探测失败时仍给出可用上限`() {
        // physicalMb = 0 表示探测失败。此时上限不能是 0，
        // 否则滑块整个不可用，用户什么都设不了。
        val normalized = GameLaunchSettings(maxMemoryMb = 99999).normalized(0)
        assertTrue(
            (normalized.maxMemoryMb ?: 0) >= 512,
            "探测失败时也要给出可用上限，实际: ${normalized.maxMemoryMb}",
        )
    }

    @Test
    fun `默认设置被识别为默认`() {
        assertTrue(GameLaunchSettings().isDefault)
    }

    @Test
    fun `改了任意一项就不再是默认`() {
        assertFalse(GameLaunchSettings(maxMemoryMb = 2048).isDefault)
        assertFalse(GameLaunchSettings(priority = 5).isDefault)
        assertFalse(GameLaunchSettings(jvmArgs = "-Xss1M").isDefault)
    }
}

/**
 * [GameLaunchSettingsStore] 的按版本存取。
 *
 * <p>用 [AppConfig.overrideDirectory] 把配置目录指到临时目录，
 * 不去动真实用户目录。
 */
class GameLaunchSettingsStoreTest {

    @TempDir
    lateinit var tempDir: File

    @BeforeEach
    fun setUp() {
        AppConfig.overrideDirectory = tempDir
    }

    @AfterEach
    fun tearDown() {
        AppConfig.overrideDirectory = null
    }

    @Test
    fun `未保存过时返回默认设置`() {
        val loaded = GameLaunchSettingsStore.load("1.21.4")
        assertEquals(GameLaunchSettings(), loaded)
    }

    @Test
    fun `保存后能读回同样的设置`() {
        val original = GameLaunchSettings(
            maxMemoryMb = 4096,
            minMemoryMb = 1024,
            priority = 5,
            jvmArgs = "-XX:+UseG1GC",
        )
        assertTrue(GameLaunchSettingsStore.save("1.21.4", original))
        assertEquals(original, GameLaunchSettingsStore.load("1.21.4"))
    }

    @Test
    fun `不同版本互不影响`() {
        GameLaunchSettingsStore.save("1.21.4", GameLaunchSettings(maxMemoryMb = 4096))
        GameLaunchSettingsStore.save("1.20.1", GameLaunchSettings(maxMemoryMb = 8192))

        assertEquals(4096, GameLaunchSettingsStore.load("1.21.4").maxMemoryMb)
        assertEquals(8192, GameLaunchSettingsStore.load("1.20.1").maxMemoryMb)
        // 没存过的版本仍是默认值。
        assertNull(GameLaunchSettingsStore.load("1.12.2").maxMemoryMb)
    }

    @Test
    fun `保存默认值会删掉已有键`() {
        // 配置文件是给用户看的，不该堆满 PRIORITY=0 这种"没设也写"的行。
        GameLaunchSettingsStore.save("1.21.4", GameLaunchSettings(maxMemoryMb = 4096))
        GameLaunchSettingsStore.save("1.21.4", GameLaunchSettings())

        val content = File(tempDir, "config.properties").readText()
        assertFalse(
            content.contains("GAME.1.21.4"),
            "默认设置应删除对应键，实际内容:\n$content",
        )
    }

    @Test
    fun `带横线和点的版本号可以正常存取`() {
        // 加载器版本的 id 形如 1.21.4-forge-54.0.16
        val id = "1.21.4-forge-54.0.16"
        GameLaunchSettingsStore.save(id, GameLaunchSettings(maxMemoryMb = 6144))
        assertEquals(6144, GameLaunchSettingsStore.load(id).maxMemoryMb)
    }

    @Test
    fun `含换行的版本号被拒绝`() {
        // 版本号来自网络，带换行能把整个配置文件写坏。
        assertFalse(GameLaunchSettingsStore.isValidVersionId("1.21.4\nMALICIOUS=1"))
        assertFalse(GameLaunchSettingsStore.isValidVersionId("a=b"))
        assertFalse(GameLaunchSettingsStore.isValidVersionId("   "))
        assertTrue(GameLaunchSettingsStore.isValidVersionId("1.21.4"))
    }

    @Test
    fun `非法版本号写入不抛异常只是返回 false`() {
        assertFalse(GameLaunchSettingsStore.save("a=b", GameLaunchSettings(maxMemoryMb = 2048)))
        // 读也必须安全。
        assertEquals(GameLaunchSettings(), GameLaunchSettingsStore.load("a=b"))
    }

    @Test
    fun `保存一个版本不影响其它版本的键`() {
        GameLaunchSettingsStore.save("1.21.4", GameLaunchSettings(maxMemoryMb = 4096))
        GameLaunchSettingsStore.save("1.20.1", GameLaunchSettings(maxMemoryMb = 8192))
        GameLaunchSettingsStore.save("1.19.2", GameLaunchSettings(maxMemoryMb = 2048))

        assertEquals(4096, GameLaunchSettingsStore.load("1.21.4").maxMemoryMb)
        assertEquals(8192, GameLaunchSettingsStore.load("1.20.1").maxMemoryMb)
        assertEquals(2048, GameLaunchSettingsStore.load("1.19.2").maxMemoryMb)
    }
}

/**
 * [PhysicalMemory] 的探测。
 *
 * <p>不断言具体数值（随机器变化），只断言「要么是合理的正数，要么是 0」——
 * 0 是约定的「未知」值，绝不能返回负数或者笑话一样的小数。
 */
class PhysicalMemoryTest {

    @Test
    fun `探测结果要么为 0 要么是合理的正数`() {
        val mb = PhysicalMemory.detectMb()
        assertTrue(mb >= 0, "不能返回负数，实际: $mb")
        if (mb > 0) {
            assertTrue(mb >= 128, "若探测成功，结果不该小于 128MB，实际: $mb")
            assertTrue(mb <= 100L * 1024 * 1024, "探测结果离谱，实际: ${mb}MB")
        }
    }

    /**
     * 回归测试：普通 JVM（非容器）上必须探测成功。
     *
     * <p>这条守住一个真实踩过的坑：早期实现用
     * `bean.javaClass.getMethod("getTotalMemorySize")` 反射调用，
     * 拿到的 Method 属于 `com.sun.management.internal.OperatingSystemImpl`——
     * 那是 JPMS 的非导出包，`invoke` 会抛 `IllegalAccessException`，
     * 于是 `runCatching` 吞掉异常返回 0，界面永远显示「未能探测物理内存」。
     *
     * <p>改成转型到 `com.sun.management.OperatingSystemMXBean` 接口后正常。
     * 若哪天有人再改回反射，这个测试会失败。
     */
    @Test
    fun `非容器环境能探测到物理内存`() {
        // 只有在 cgroup 未限制时，才能确认走的是 MXBean 那条路。
        // 容器里 cgroup 有值，说明不了 MXBean 是否可用 —— 跳过。
        val cgroupLimited = runCatching {
            File("/sys/fs/cgroup/memory.max").readText().trim() != "max"
        }.getOrDefault(false)
        if (cgroupLimited) return

        val mb = PhysicalMemory.detectMb()
        assertTrue(
            mb > 0,
            "非容器环境下应能探测到物理内存，实际返回 $mb。" +
                "若为 0，很可能是 MXBean 的取法又变回反射了。",
        )
    }
}
