package org.cn.xiaofan.launch

import io.github.headlesshq.headlessmc.progressbar.ProgressBar
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * [LaunchProgressBridge] 的行为测试。
 *
 * <p>这些测试只覆盖纯逻辑（事件转发、关闭幂等、无监听方时的降级），
 * 不启动 Quarkus 容器 —— 那属于集成测试的范畴。
 *
 * <p>**为什么要测这个**：这段代码是"下载 12 分钟但界面显示正在拉起进程"
 * 那个 bug 的修复核心。如果 `isDummy()` 返回 true，或者事件没转发出去，
 * 界面就又会退回一句静态文字，用户会以为程序挂了。
 * 这个退化是**静默**的 —— 不会报错、不会崩溃，只是体验变差。
 * 所以必须有测试盯着。
 */
class LaunchProgressBridgeTest {

    private lateinit var bridge: LaunchProgressBridge
    private lateinit var recorder: RecordingListener

    @BeforeEach
    fun setUp() {
        bridge = LaunchProgressBridge()
        recorder = RecordingListener()
        LaunchProgressBridge.current = recorder
    }

    @AfterEach
    fun tearDown() {
        LaunchProgressBridge.clear()
    }

    @Test
    @DisplayName("isDummy 必须为 false，否则会被管理器过滤掉")
    fun notDummy() {
        // ProgressBarServiceManagerImpl 用 filter(!isDummy()) 挑服务。
        // 一旦这里变成 true，进度就再也不会流到界面 —— 而且是静默失效。
        assertFalse(bridge.isDummy(), "isDummy() 为 true 会导致进度完全不显示")
    }

    @Test
    @DisplayName("没有监听方时退化为 dummy 进度条，不抛异常")
    fun noListenerFallsBackToDummy() {
        LaunchProgressBridge.clear()

        val bar = bridge.displayProgressBar(config("Downloading Assets", 485))

        assertTrue(bar.isDummy(), "无监听方时应当返回 dummy，而不是一个假进度条")
        // dummy 的所有操作都应当是安全的空操作。
        bar.stepBy(10)
        bar.stepTo(100)
        bar.maxHint(200)
        bar.close()
    }

    @Test
    @DisplayName("进度条创建时上报任务名和总量")
    fun onStartCarriesTaskInfo() {
        bridge.displayProgressBar(config("Downloading Assets", 485))

        assertEquals(1, recorder.starts.size)
        val start = recorder.starts.first()
        assertEquals("Downloading Assets", start.taskName)
        assertEquals(485L, start.initialMax)
        assertEquals("mb", start.unitName)
        assertEquals(1_000_000L, start.unitSize)
    }

    @Test
    @DisplayName("stepBy 转发为增量事件")
    fun stepByForwarded() {
        val bar = bridge.displayProgressBar(config("Downloading Assets", 485))
        bar.stepBy(3)
        bar.stepBy(7)

        assertEquals(10L, recorder.totalStepped)
    }

    @Test
    @DisplayName("stepTo 转发为绝对位置")
    fun stepToForwarded() {
        val bar = bridge.displayProgressBar(config("Downloading Assets", 485))
        bar.stepTo(42)

        assertEquals(listOf(42L), recorder.absolute)
    }

    @Test
    @DisplayName("step() 等价于 stepBy(1)")
    fun stepIsSingleUnit() {
        val bar = bridge.displayProgressBar(config("Libraries", 10))
        bar.step()

        assertEquals(1L, recorder.totalStepped)
    }

    @Test
    @DisplayName("maxHint 转发总量修正")
    fun maxHintForwarded() {
        val bar = bridge.displayProgressBar(config("Downloading Assets", -1))
        bar.maxHint(500)

        assertEquals(listOf(500L), recorder.maxHints)
    }

    @Test
    @DisplayName("close 上报任务结束")
    fun closeReportsFinish() {
        val bar = bridge.displayProgressBar(config("Downloading Assets", 485))
        bar.close()

        assertEquals(listOf("Downloading Assets"), recorder.finished)
    }

    @Test
    @DisplayName("close 之后的调用一律忽略")
    fun closeIsIdempotentAndSealsBar() {
        val bar = bridge.displayProgressBar(config("Downloading Assets", 485))
        bar.close()
        bar.close()
        bar.stepBy(5)
        bar.stepTo(99)

        // 必须只上报一次结束 —— 重复上报会让界面误以为又有任务完成了。
        assertEquals(1, recorder.finished.size)
        // 关闭后的推进不应再产生事件，否则界面进度条会"复活"。
        assertEquals(0L, recorder.totalStepped)
        assertEquals(emptyList<Long>(), recorder.absolute)
    }

    @Test
    @DisplayName("total 为未知（-1）时 Configuration 不携带单位")
    fun unknownTotalHasNoUnit() {
        val cfg = ProgressBar.Configuration("Libraries", ProgressBar.Configuration.NO_INITIAL_SIZE)
        bridge.displayProgressBar(cfg)

        val start = recorder.starts.first()
        assertEquals(-1L, start.initialMax)
        assertNull(start.unitName, "没有单位时应当为 null，而不是空串")
    }

    @Test
    @DisplayName("多个进度条互不干扰")
    fun concurrentBarsAreIndependent() {
        // 资源和库是并行下载的，两个进度条同时存在。
        val assets = bridge.displayProgressBar(config("Downloading Assets", 485))
        val libs = bridge.displayProgressBar(config("Libraries", 20))

        assets.stepBy(100)
        libs.stepBy(3)
        assets.close()

        // 关掉资源那个不该影响库那个。
        libs.stepBy(2)
        assertEquals(105L, recorder.totalStepped)
        assertEquals(1, recorder.finished.size)
    }

    @Test
    @DisplayName("进度桥接名固定")
    fun nameIsStable() {
        assertEquals("hmwlx-gui", bridge.name)
    }

    private fun config(taskName: String, max: Long): ProgressBar.Configuration =
        ProgressBar.Configuration(taskName, max, ProgressBar.Configuration.Unit.MB)

    private data class Start(
        val taskName: String,
        val initialMax: Long,
        val unitName: String?,
        val unitSize: Long,
    )

    /** 记录所有事件的监听器。 */
    private class RecordingListener : LaunchProgressListener {
        val starts = mutableListOf<Start>()
        val finished = mutableListOf<String>()
        val absolute = mutableListOf<Long>()
        val maxHints = mutableListOf<Long>()
        var totalStepped = 0L

        override fun onStart(taskName: String, initialMax: Long, unitName: String?, unitSize: Long) {
            starts += Start(taskName, initialMax, unitName, unitSize)
        }

        override fun onStep(delta: Long) {
            totalStepped += delta
        }

        override fun onAbsolute(value: Long) {
            absolute += value
        }

        override fun onMaxHint(max: Long) {
            maxHints += max
        }

        override fun onFinish(taskName: String) {
            finished += taskName
        }
    }
}
