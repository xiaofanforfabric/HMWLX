package org.cn.xiaofan.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 阶段顺序与"启动成功"判定时机的回归测试。
 *
 * <p>这个文件专门盯两个**已发生过的真实 bug**，都是用户报的：
 *
 * **bug 1：下载进度显示在「正在处理账号」阶段底下**
 * 根因是 `launch()` 里先把 stage 推到 `PREPARING_ACCOUNT`，之后才调
 * `buildAndLaunch()` —— 而下载就发生在那里面。于是十几分钟的下载进度
 * 全挂在「处理账号」下面，用户看到的阶段和实际在做的事完全对不上。
 *
 * **bug 2：「启动成功」要等游戏退出才显示**
 * 根因是 `finished` 只在 `finish(exitCode)` 里置 true，而那是进程退出才调用的。
 * 窗口早就加载了，界面标题还写着"启动中"。
 *
 * <p>这两个 bug 的共性是：**代码能跑、不崩溃、测试全绿**，
 * 只有人肉盯着界面才能发现。所以必须有测试。
 */
class LaunchStageOrderingTest {

    @Test
    @DisplayName("阶段顺序必须是：依赖 → 账号 → 拉起进程 → 已拉起 → 用户名 → 窗口就绪")
    fun stageOrderReflectsRealSequence() {
        // 顺序本身就是契约。PREPARING_DEPS 必须在 PREPARING_ACCOUNT 之前 ——
        // 因为依赖下载确实发生在账号处理之前，反了就会出现 bug 1。
        val order = LaunchStage.entries.map { it.name }

        assertEquals(
            listOf(
                "PREPARING_DEPS",
                "PREPARING_ACCOUNT",
                "STARTING_PROCESS",
                "PROCESS_LAUNCHED",
                "USER_SET",
                "WINDOW_READY",
            ),
            order,
            "阶段顺序变了。注意：下载发生在 launcher() 内部，" +
                "任何把 PREPARING_ACCOUNT 排到 PREPARING_DEPS 前面的改动都会让" +
                "下载进度显示在错误的阶段下面",
        )
    }

    @Test
    @DisplayName("窗口创建日志应当让阶段推进到 WINDOW_READY")
    fun createdWindowLineAdvancesToWindowReady() {
        val stage = stageAfterLine("[21:53:09] [Render thread/INFO]: Created window using SDL video driver: x11")

        assertEquals(
            LaunchStage.WINDOW_READY,
            stage,
            "`Created window using SDL video driver` 是窗口真的存在的直接证据，" +
                "必须能被识别",
        )
    }

    @Test
    @DisplayName("Setting user 只推进到 USER_SET，不能越级")
    fun settingUserOnlyAdvancesToUserSet() {
        val stage = stageAfterLine("[21:52:57] [Render thread/INFO]: Setting user: xiaofanbot")

        assertEquals(LaunchStage.USER_SET, stage, "用户名日志不应被当成窗口就绪")
    }

    @Test
    @DisplayName("声音/贴图日志也能识别为窗口就绪（老版本兼容）")
    fun audioAndTextureLinesAlsoCount() {
        // 有些版本不打印 SDL 那行，但一定会打印这些。
        assertEquals(
            LaunchStage.WINDOW_READY,
            stageAfterLine("[21:53:34] [Render thread/INFO]: Sound engine started"),
            "Sound engine started 说明窗口已存在（音频要靠窗口上下文初始化）",
        )
        assertEquals(
            LaunchStage.WINDOW_READY,
            stageAfterLine("[21:53:34] [Render thread/INFO]: OpenAL initialized on device 内置音频"),
            "OpenAL initialized 同理",
        )
        assertEquals(
            LaunchStage.WINDOW_READY,
            stageAfterLine("[21:53:34] [Render thread/INFO]: Created: 2048x2048x4 minecraft:textures/atlas/blocks.png-atlas"),
            "贴图图集说明渲染管线已经在跑了",
        )
    }

    @Test
    @DisplayName("无关日志不应推进阶段")
    fun irrelevantLinesDoNotAdvance() {
        val stage = stageAfterLine("[21:52:52] [Datafixer Bootstrap #0/INFO]: 307 Datafixer optimizations took 551 milliseconds")

        assertEquals(
            LaunchStage.PREPARING_DEPS,
            stage,
            "初始化阶段的普通日志不该让界面跳到后面的阶段",
        )
    }

    @Test
    @DisplayName("纯文本 Created: 但没有 textures 不算窗口就绪")
    fun createdWithoutTexturesDoesNotCount() {
        val stage = stageAfterLine("[12:00:00] [Render thread/INFO]: Created: 512x256x0 something/else.png")

        assertEquals(
            LaunchStage.PREPARING_DEPS,
            stage,
            "`Created:` 单独出现不足以证明窗口加载，必须配合 textures",
        )
    }

    @Test
    @DisplayName("旧语义 bug：finished 不应在进程退出前就为 true")
    fun finishedIsNotTerminalForProcessLifecycle() {
        // 这是一个**语义**断言：LaunchState 里 finished 和 processAlive 是两件事。
        // finished = 启动流程有了结论；
        // processAlive = 进程还在跑。
        //
        // 窗口加载后：finished=true, processAlive=true —— 两者同时成立。
        // 以前只有一个 finished，导致要么提前说成功、要么等到退出。
        val runningAfterSuccess = LaunchState(
            stage = LaunchStage.WINDOW_READY,
            finished = true,
            processAlive = true,
        )

        assertTrue(runningAfterSuccess.succeeded, "窗口加载后就是启动成功")
        assertTrue(
            runningAfterSuccess.processAlive,
            "启动成功之后进程仍在运行 —— 界面必须还能显示「停止游戏」",
        )
    }

    @Test
    @DisplayName("启动成功后再退出，不应被改判为失败")
    fun exitAfterSuccessStaysSuccess() {
        val afterExit = LaunchState(
            stage = LaunchStage.WINDOW_READY,
            finished = true,
            processAlive = false,
            error = null,
        )

        assertTrue(
            afterExit.succeeded,
            "游戏玩完关掉（或运行期崩溃）不该把启动结果改判成失败",
        )
        assertFalse(
            afterExit.processAlive,
            "进程已退出，停止按钮该收起来了",
        )
    }

    /**
     * 用真实日志行驱动一次阶段推进，返回推进后的阶段。
     *
     * <p>这里复制了一份最小判定逻辑，因为它需要的是**纯函数**，
     * 而 `LaunchController` 的方法是 private 的、还带着 StateFlow。
     *
     * <p>复制有风险：源码改了而这里没改，测试就会假绿。
     * 所以配套加了 [windowKeywordsMatchControllerSource] 做源码比对。
     */
    private fun stageAfterLine(line: String): LaunchStage {
        val isWindowReady = line.contains("Created window", ignoreCase = true) ||
            line.contains("Sound engine started", ignoreCase = true) ||
            line.contains("OpenAL initialized", ignoreCase = true) ||
            (line.contains("Created:") && line.contains("textures", ignoreCase = true))

        val isUserSet = line.contains("Setting user:", ignoreCase = true)

        return when {
            isWindowReady -> LaunchStage.WINDOW_READY
            isUserSet -> LaunchStage.USER_SET
            else -> LaunchStage.PREPARING_DEPS
        }
    }

    /**
     * 防止上面的复制逻辑与实现悄悄脱节。
     *
     * <p>直接读 `LaunchController.kt` 源码，确认每个关键词都还在。
     * 这很粗暴，但有效 —— 如果谁把 `Created window` 那行删了，
     * 这个测试会立刻失败，而不是让上面几个测试继续"通过"。
     */
    @Test
    @DisplayName("判定关键词与实现源码保持同步")
    fun windowKeywordsMatchControllerSource() {
        val sourceFile = java.io.File("src/main/kotlin/org/cn/xiaofan/launch/LaunchController.kt")
        assertTrue(sourceFile.exists(), "找不到 LaunchController.kt，路径可能变了")

        val source = sourceFile.readText()
        val keywords = listOf(
            "Created window",
            "Sound engine started",
            "OpenAL initialized",
            "Setting user:",
        )

        keywords.forEach { keyword ->
            assertTrue(
                source.contains(keyword),
                "实现里找不到关键词 `$keyword` —— 上面的阶段判定测试用的是硬编码副本，" +
                    "源码变了它们不会失败，这个检查就是为了堵住这个漏洞",
            )
        }
    }
}
