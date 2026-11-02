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

package org.cn.xiaofan.bootstrap

import org.cn.xiaofan.config.AppConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 在 GUI 进程内启动 headlessmc 的 Quarkus 容器。
 *
 * headlessmc 本身是一个 Quarkus 应用：`CommandLine`、`ConfigFileProvider`、
 * `FileService` 等都是 CDI bean。GUI 想用它们，必须先把容器拉起来。
 *
 * 这里**不调用** `Quarkus.run(...)`，因为那会阻塞调用线程直到应用退出。
 * 改用 `manualInitialize()` + `manualStart()`：构建并启动容器，返回后主线程继续。
 *
 * 用法：
 * ```kotlin
 * // 后台线程
 * HeadlessMcBootstrap.start()
 * val cli = HeadlessMcBootstrap.bean(picocli.CommandLine::class.java)
 * ```
 */
object HeadlessMcBootstrap {

    /** 容器启动超时（秒）。首次启动要扫描 CDI，给足时间。 */
    private const val STARTUP_TIMEOUT_SECONDS = 120L

    private val state = AtomicReference(State.NEW)
    private val started = CountDownLatch(1)

    @Volatile
    private var startupFailure: Throwable? = null

    /** 容器状态。 */
    enum class State {
        /** 尚未启动。 */
        NEW,

        /** 启动中。 */
        STARTING,

        /** 已就绪，可以取 bean。 */
        READY,

        /** 启动失败，此时 [startupFailure] 有值。 */
        FAILED,
    }

    /**
     * 启动 Quarkus 容器。可重复调用，第二次起会等待首次启动完成。
     *
     * 此方法**阻塞**直到容器就绪或失败。GUI 里请在后台线程调用，别阻塞 Compose 主线程。
     *
     * @throws IllegalStateException 容器启动失败或超时
     */
    fun start() {
        if (state.compareAndSet(State.NEW, State.STARTING)) {
            try {
                applyDebugModeIfEnabled()
                QuarkusLauncher.initializeAndStart()
                state.set(State.READY)
                started.countDown()
            } catch (t: Throwable) {
                startupFailure = t
                state.set(State.FAILED)
                started.countDown()
                // 把根因链写进 message：只用 IllegalStateException 包一层的话，
                // CI 日志里只剩 "容器启动失败"，看不出到底卡在哪一步
                // （找不到 Quarkus 类 / bean 冲突 / 配置文件写不进去……）。
                throw IllegalStateException("headlessmc 容器启动失败: ${describeChain(t)}", t)
            }
            return
        }

        // 别人正在启动或已启动，等它完成
        try {
            if (!started.await(STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw IllegalStateException(
                    "等待 headlessmc 容器启动超时（${STARTUP_TIMEOUT_SECONDS}s）"
                )
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("等待容器启动时被中断", e)
        }

        when (val current = state.get()) {
            State.FAILED -> throw IllegalStateException(
                "headlessmc 容器启动失败: ${startupFailure?.let { describeChain(it) }}",
                startupFailure,
            )
            State.READY -> Unit
            else -> throw IllegalStateException("headlessmc 容器状态异常: $current")
        }
    }

    /**
     * 按用户配置切换日志级别。
     *
     * <h2>为什么必须在这里、且必须在容器启动前</h2>
     *
     * headlessmc 的 `LogConfig` 明确写了 `@implNote`：除了 stacktraces，
     * 其余各项**只在启动时建立 log handler 的那一刻读一次**，
     * 运行期改配置没有效果。所以开关只能在这里落到系统属性上，
     * 而且用户改完开关必须重启应用。
     *
     * <h2>两个键分别管什么</h2>
     *
     * | 键 | 作用 |
     * |---|---|
     * | `hmc.log.console-level` | headlessmc 自己的输出打到控制台的门槛（默认 WARN） |
     * | `quarkus.log.level` | Quarkus 与其它依赖的根日志级别（默认 INFO） |
     *
     * <p>只设前者的话，headlessmc 的日志是放开了，但 Quarkus 的 DEBUG
     * 仍然被根级别卡住 —— 用户要的是「全部详细日志」，所以两个都要设。
     *
     * <p>另外把 `min-level` 一起放宽：Quarkus 有个独立的记录下限，
     * 低于它的级别连记录都不会发生，后面的 level 配置再高也没用。
     */
    private fun applyDebugModeIfEnabled() {
        if (!AppConfig.isDebugMode()) return

        // 用 System.setProperty 而不是改配置文件：
        // 这是运行期决策，不该污染用户的 config.properties
        // （那样关掉开关后还要记得把写进去的值删掉）。
        System.setProperty("hmc.log.console-level", "DEBUG")
        System.setProperty("quarkus.log.level", "DEBUG")
        System.setProperty("quarkus.log.min-level", "DEBUG")

        // 直接打到 stderr：此时日志系统还没起来，用 println 反而能被用户看到。
        // 不打印敏感信息，只说「开了」。
        System.err.println("[HMWLX] 调试模式已开启：日志级别 = DEBUG")
    }

    /**
     * 把异常链压成一行，用于日志和断言信息。
     *
     * <p>Quarkus 启动失败往往嵌套三五层（DeploymentException → RuntimeException →
     * ClassNotFoundException），只看最外层完全无法定位。这里保留每层的类名和 message。
     */
    private fun describeChain(t: Throwable): String {
        val parts = mutableListOf<String>()
        var cur: Throwable? = t
        var depth = 0
        while (cur != null && depth < 6) {
            val msg = cur.message?.take(200)
            parts += if (msg.isNullOrBlank()) cur.javaClass.simpleName
            else "${cur.javaClass.simpleName}: $msg"
            cur = cur.cause?.takeIf { it !== cur }
            depth++
        }
        return parts.joinToString(" <- ")
    }

    /** 容器是否已就绪。 */
    val isReady: Boolean
        get() = state.get() == State.READY

    /** 当前状态。 */
    fun currentState(): State = state.get()

    /** 启动失败时的异常，未失败则为 null。 */
    fun startupFailure(): Throwable? = startupFailure

    /**
     * 按类型取一个 CDI bean。
     *
     * @throws IllegalStateException 容器未就绪，或该类型没有可解析 / 不唯一的 bean
     */
    fun <T : Any> bean(type: Class<T>): T {
        if (!isReady) {
            throw IllegalStateException(
                "headlessmc 容器尚未就绪（当前状态: ${state.get()}），无法获取 ${type.name}"
            )
        }

        val instances = ArcInstances.byType(type)
        if (instances.isUnsatisfied) {
            throw IllegalStateException("找不到 bean: ${type.name}")
        }
        if (instances.isAmbiguous) {
            throw IllegalStateException("bean 不唯一: ${type.name}")
        }
        return instances.get()
    }

    /**
     * 按泛型类型取一个 CDI bean，用于 `AuthStore<Account>` 这类带类型参数的 bean。
     *
     * @param typeLiteral 用 `object : TypeLiteral<AuthStore<Account>>() {}` 构造
     * @param typeName 仅用于错误信息，便于定位是哪个 bean 没解析到
     * @param qualifiers 限定符，如离线账号存储需要 `@Offline`
     * @throws IllegalStateException 容器未就绪，或该类型没有可解析 / 不唯一的 bean
     */
    fun <T : Any> beanOfType(
        typeLiteral: jakarta.enterprise.util.TypeLiteral<T>,
        typeName: String,
        qualifiers: Array<Annotation> = emptyArray(),
    ): T {
        if (!isReady) {
            throw IllegalStateException(
                "headlessmc 容器尚未就绪（当前状态: ${state.get()}），无法获取 $typeName"
            )
        }

        val instances = ArcInstances.byTypeLiteral(typeLiteral, qualifiers)
        if (instances.isUnsatisfied) {
            throw IllegalStateException("找不到 bean: $typeName")
        }
        if (instances.isAmbiguous) {
            throw IllegalStateException("bean 不唯一: $typeName")
        }
        return instances.get()
    }

    /** 关闭容器。在后台触发，不阻塞调用线程。 */
    fun shutdown() {
        if (state.getAndSet(State.NEW) == State.READY) {
            QuarkusLauncher.asyncExit()
        }
    }
}
