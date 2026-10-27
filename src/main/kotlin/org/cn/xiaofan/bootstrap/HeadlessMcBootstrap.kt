package org.cn.xiaofan.bootstrap

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
                QuarkusLauncher.initializeAndStart()
                state.set(State.READY)
                started.countDown()
            } catch (t: Throwable) {
                startupFailure = t
                state.set(State.FAILED)
                started.countDown()
                throw IllegalStateException("headlessmc 容器启动失败", t)
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
            State.FAILED -> throw IllegalStateException("headlessmc 容器启动失败", startupFailure)
            State.READY -> Unit
            else -> throw IllegalStateException("headlessmc 容器状态异常: $current")
        }
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
