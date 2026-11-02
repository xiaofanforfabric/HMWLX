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

package org.cn.xiaofan.launch

import io.github.headlesshq.headlessmc.progressbar.ProgressBar
import io.github.headlesshq.headlessmc.progressbar.ProgressbarService
import jakarta.annotation.Priority
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Alternative

/**
 * 把 headlessmc 的下载进度桥接到界面。
 *
 * <h2>为什么需要这个 bean</h2>
 *
 * 启动游戏时，`ClientLauncher.launcher()` 会做大量阻塞工作：
 * 解析版本 JSON、下载资源索引、**下载几百 MB 资源**、下载库、打补丁。
 * 实测一次冷启动要 12 分钟，几乎全花在下载上。
 *
 * 这个方法是同步阻塞的，中间没有任何回调 —— 如果不接管进度，
 * 界面上只能显示一句静态的「正在处理游戏依赖」，用户会以为程序挂了。
 *
 * headlessmc 自己是有进度的：`AssetDownloader` / `LibraryDownloader` 通过
 * `ProgressbarService.displayProgressBar(...)` 拿一个进度条，然后逐块 `stepBy()`。
 * 默认实现是 `DummyProgressBarService`（什么都不做），因为 CLI 场景下
 * 有 jline 的进度条，不需要这个。
 *
 * GUI 场景下没有终端，`ProgressBarServiceManagerImpl` 会
 * `filter(!isDummy())` 后回退到 dummy。所以我们提供一个
 * **`isDummy() == false`** 的实现 —— 它就会被优先选中，进度就流到我们这里了。
 *
 * <h2>为什么必须用 @Alternative</h2>
 *
 * `AssetDownloader` / `LibraryDownloader` 并不通过 `ProgressBarServiceManager`
 * 拿进度条，而是**构造器直接注入单个 `ProgressbarService`**。
 *
 * headlessmc 里已经有一个 `JLineProgressBarService`（普通 `@ApplicationScoped`）。
 * 我们一旦也注册一个，容器就会报
 * `AmbiguousResolutionException: Ambiguous dependencies for type ProgressbarService`
 * —— 构建直接失败。
 *
 * 所以必须标 `@Alternative` + `@Priority`：`@Alternative` 让我们的 bean
 * 成为候选中的“优选”，而带 `@Priority` 的 alternative 在所有注入点全局生效
 * （不需要在 `beans.xml` 里逐个启用）。jline 那个仍是普通 bean，被自动排除。
 *
 * 这也顺便修掉了一个隐患：jline 版本会往 stdout 画进度条，
 * 而 stdout 是我们用来解析阶段的关键字来源，混进去的 ANSI 控制字符会干扰匹配。
 *
 * <h2>为什么 isDummy() 要返回 false</h2>
 *
 * `ProgressBarServiceManagerImpl` 不是唯一消费者，但 `DummyProgressBarService`
 * 会作为兜底。我们返回 false 表示“这是个真正的、能画进度条的实现”，
 * 这样管理器那条路径也会选中我们。
 *
 * <h2>线程安全</h2>
 *
 * `stepBy()` 会被 `ParallelTaskService` 的多个线程并发调用，
 * 而 [LaunchProgressListener] 可能被替换（每次启动新建一个）。
 * 所以用 `@Volatile` 持有引用，读的时候取一次局部变量。
 */
@ApplicationScoped
@Alternative
@Priority(100)
// 必须 open：ArC 会为每个 bean 生成 `_ClientProxy` 子类，
// 而 Kotlin 的 class 默认是 final —— 不 open 就会在容器启动时抛
// `IncompatibleClassChangeError: LaunchProgressBridge_ClientProxy
//  cannot inherit from final class`。
//
// 这个问题只在**打包后**才暴露：`gradlew run` 用的是未经 Quarkus
// 构建期增强的类，不会生成代理子类。所以开发期一切正常，
// 打出来的安装包一启动就炸。
open class LaunchProgressBridge : ProgressbarService {

    override fun getName(): String = "hmwlx-gui"

    /**
     * 不做成 dummy —— `ProgressBarServiceManagerImpl` 会过滤掉 dummy 服务。
     * 这是让进度流到 GUI 的**唯一开关**。
     */
    override fun isDummy(): Boolean = false

    override fun displayProgressBar(configuration: ProgressBar.Configuration): ProgressBar {
        val listener = current ?: return ProgressBar.dummy()

        // 通知监听方"有一个新任务开始了"，界面可以据此显示任务名和总量。
        listener.onStart(
            taskName = configuration.taskName(),
            initialMax = configuration.initialMax(),
            unitName = configuration.unit()?.name(),
            unitSize = configuration.unit()?.size() ?: 1L,
        )

        return BridgedProgressBar(listener, configuration)
    }

    /**
     * 一个正在上报的进度条。
     *
     * 每个任务（资源下载、库下载）各自一个新的实例，
     * 因为它们并行度不同、总量不同。
     */
    private class BridgedProgressBar(
        private val listener: LaunchProgressListener,
        private val configuration: ProgressBar.Configuration,
    ) : ProgressBar {

        @Volatile
        private var closed = false

        override fun isDummy(): Boolean = false

        override fun stepBy(n: Long) {
            if (closed) return
            listener.onStep(n)
        }

        override fun stepTo(n: Long) {
            if (closed) return
            listener.onAbsolute(n)
        }

        override fun step() = stepBy(1)

        override fun maxHint(n: Long) {
            // headlessmc 用它修正总量（先不知道总数时）。界面可以不理，
            // 也可以用来更新"总量"显示。这里转给监听方。
            if (!closed) listener.onMaxHint(n)
        }

        override fun close() {
            if (closed) return
            closed = true
            listener.onFinish(configuration.taskName())
        }
    }

    companion object {
        /**
         * 当前的进度监听方。
         *
         * <p>用静态字段而不是 CDI 注入：这个 bean 由 Quarkus 创建，
         * 而监听方是每次启动动态生灭的 —— 从 [LaunchController] 那边
         * 直接赋值最直接。`@Volatile` 保证 IO 线程能看到最新值。
         *
         * <p>为 null 时不注册进度条（返回 dummy），行为与改造前一致，
         * 不会因为没接监听方而出错。
         */
        @Volatile
        var current: LaunchProgressListener? = null

        /** 清掉监听方。启动结束后调用，避免持有已废弃的控制器。 */
        fun clear() {
            current = null
        }
    }
}

/**
 * 进度事件的接收方。
 *
 * <p>单开一个接口而不是让 [LaunchController] 直接实现，
 * 是为了让这个文件不依赖控制器 —— 控制器改起来频繁，
 * 桥接层应该保持稳定。
 */
interface LaunchProgressListener {
    /**
     * 一个新任务开始。
     *
     * @param taskName 任务名，如 `Downloading Assets`（headlessmc 给的英文原文）
     * @param initialMax 总量，-1 表示未知
     * @param unitName 单位名，如 `mb`；null 表示按个数
     * @param unitSize 一个单位多少字节，`mb` 是 1_000_000
     */
    fun onStart(taskName: String, initialMax: Long, unitName: String?, unitSize: Long)

    /** 增量推进。 */
    fun onStep(delta: Long)

    /** 绝对位置。 */
    fun onAbsolute(value: Long)

    /** 总量修正。 */
    fun onMaxHint(max: Long)

    /** 任务结束。 */
    fun onFinish(taskName: String)
}
