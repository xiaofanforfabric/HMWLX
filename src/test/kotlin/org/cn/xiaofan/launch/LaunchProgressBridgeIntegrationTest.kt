package org.cn.xiaofan.launch

import io.github.headlesshq.headlessmc.launcher.assets.AssetDownloader
import io.github.headlesshq.headlessmc.launcher.libraries.LibraryDownloader
import io.github.headlesshq.headlessmc.progressbar.ProgressBar
import io.github.headlesshq.headlessmc.progressbar.ProgressbarService
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

/**
 * 验证进度桥接在真实 CDI 容器里真的被选中。
 *
 * <p>**为什么不只在单测里测**：单元测试直接 `new LaunchProgressBridge()`，
 * 绕过了整个 bean 解析过程。但在真实容器里，`AssetDownloader` 是通过
 * **构造器注入单个 `ProgressbarService`** 拿到进度条的，
 * 而 headlessmc 自己也注册了一个 `JLineProgressBarService`。
 *
 * <p>这两者共存会导致
 * `AmbiguousResolutionException: Ambiguous dependencies for type ProgressbarService`
 * —— 构建期就炸，或者（更糟）容器悄悄选了 jline 那个，
 * 于是进度画到已经没人看的 stdout 上，界面依旧一片死寂。
 *
 * <p>修复靠 `@Alternative` + `@Priority`。这个测试就是钉死这个修复：
 * 如果哪天有人把 `@Alternative` 删了，或者改了 `@Priority`，
 * 这里会立刻失败 —— 而不是等用户又投诉“显示正在拉起进程但其实在下载”。
 *
 * <p>顺带一提，选中我们也修掉了一个隐患：jline 会往 stdout 写 ANSI 进度条，
 * 而 stdout 是我们解析「设置用户名／窗口就绪」这两个阶段的关键字来源。
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LaunchProgressBridgeIntegrationTest {

    @BeforeAll
    fun startContainer() {
        HeadlessMcBootstrap.start()
    }

    @Test
    @DisplayName("容器解析出的 ProgressbarService 是我们的实现，不是 jline")
    fun ourImplementationWinsInjection() {
        val service = HeadlessMcBootstrap.bean(ProgressbarService::class.java)

        assertInstanceOf(
            LaunchProgressBridge::class.java,
            service,
            "注入的不是 LaunchProgressBridge —— 说明 @Alternative/@Priority 失效了，" +
                "进度会画到 stdout 上，界面永远看不到下载百分比",
        )
        assertFalse(service.isDummy(), "选中的服务不能是 dummy，否则进度不显示")
    }

    @Test
    @DisplayName("AssetDownloader 能正常解析（无 bean 歧义）")
    fun assetDownloaderReceivesOurService() {
        // 只要容器能解析出这个 bean 就说明没有歧义。
        // 解析失败会直接抛 AmbiguousResolutionException。
        val downloader = HeadlessMcBootstrap.bean(AssetDownloader::class.java)

        assertNotNull(downloader, "AssetDownloader 解析失败")
    }

    @Test
    @DisplayName("LibraryDownloader 能正常解析（无 bean 歧义）")
    fun libraryDownloaderReceivesOurService() {
        val downloader = HeadlessMcBootstrap.bean(LibraryDownloader::class.java)

        assertNotNull(downloader, "LibraryDownloader 解析失败")
    }

    @Test
    @DisplayName("通过容器拿到的实例能正确转发进度")
    fun containerInstanceForwardsProgress() {
        val recorder = CollectingListener()
        LaunchProgressBridge.current = recorder

        try {
            val service = HeadlessMcBootstrap.bean(ProgressbarService::class.java)
            val bar = service.displayProgressBar(
                ProgressBar.Configuration(
                    "Downloading Assets",
                    485,
                    ProgressBar.Configuration.Unit.MB,
                )
            )

            bar.stepBy(120)
            bar.stepTo(200)
            bar.close()

            assertEquals("Downloading Assets", recorder.taskName)
            assertEquals(485L, recorder.initialMax)
            assertEquals(320L, recorder.stepped, "stepBy 与 stepTo 的推进都应被记录")
            assertTrue(recorder.finished, "close 应当上报结束")
        } finally {
            LaunchProgressBridge.clear()
        }
    }

    private class CollectingListener : LaunchProgressListener {
        var taskName: String = ""
        var initialMax: Long = -1
        var stepped: Long = 0
        var finished = false

        override fun onStart(taskName: String, initialMax: Long, unitName: String?, unitSize: Long) {
            this.taskName = taskName
            this.initialMax = initialMax
        }

        override fun onStep(delta: Long) {
            stepped += delta
        }

        override fun onAbsolute(value: Long) {
            // stepTo(200) 覆盖前面的 stepBy(120)，等价于多推进 200。
            stepped += value
        }

        override fun onMaxHint(max: Long) = Unit

        override fun onFinish(taskName: String) {
            finished = true
        }
    }
}
