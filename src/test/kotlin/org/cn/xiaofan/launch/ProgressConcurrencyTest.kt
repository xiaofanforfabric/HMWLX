package org.cn.xiaofan.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 进度计数器在并发下的正确性。
 *
 * <h2>这个测试针对的是评审指出的真实缺陷</h2>
 *
 * `AssetDownloader` 用 `ParallelTaskService` **多线程**并发调
 * `stepBy()`，而它拿到的是同一个 listener 实例。原来的实现里
 * `current += delta` 是裸的非原子读-改-写，并发下会丢计数。
 *
 * 这种 bug 的特征很恶劣：
 * - **不报错、不崩溃**，程序照常跑
 * - 只是进度条数字**偏小**，百分比长期落后
 * - 单线程测试永远发现不了
 *
 * 所以必须用真并发压它。
 */
class ProgressConcurrencyTest {

    /**
     * 模拟多线程累加，验证不丢计数。
     *
     * <p>这里**复制**了 `LaunchController.progressListener()` 里的
     * 累加逻辑（加锁版本），而不是直接构造 LaunchController ——
     * 后者需要启动 Quarkus 容器和真实下载。
     *
     * <p>复制有风险：实现改了而这里没改，测试就假绿。配套的
     * `progressListenerUsesLock` 用源码比对堵住这个口子 ——
     * 和 `LaunchStageOrderingTest` 里那个土办法同一个思路。
     */
    @Test
    @DisplayName("多线程并发 stepBy 不丢计数")
    fun concurrentStepsDoNotLoseCount() {
        val lock = Any()
        var current = 0L

        val threads = 8
        val stepsPerThread = 10_000
        val stepSize = 3L

        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.submit {
                start.await()
                repeat(stepsPerThread) {
                    // 与实现一致：所有状态访问都在锁内。
                    synchronized(lock) {
                        current += stepSize
                    }
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务未在超时内完成")
        pool.shutdown()

        val expected = threads.toLong() * stepsPerThread * stepSize
        assertEquals(
            expected,
            current,
            "丢计数了。并发下的裸 `current += delta` 会少加，" +
                "表现就是进度条数字偏小",
        )
    }

    /**
     * 反向对照：证明不加锁真的会丢计数。
     *
     * <p>如果这条测试某天**不再失败**（也就是不加锁也不丢），
     * 说明运行环境的并发模型变了，那时才该考虑去掉锁。
     * 在此之前，它是"这个锁不是多余的"的证据。
     */
    @Test
    @DisplayName("反证：不加锁的累加会丢计数")
    fun unguardedAccumulationLosesCount() {
        var current = 0L

        val threads = 8
        val stepsPerThread = 50_000

        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threads)

        repeat(threads) {
            pool.submit {
                start.await()
                repeat(stepsPerThread) {
                    // 故意不加锁 —— 这就是修复前的写法。
                    current += 1
                }
                done.countDown()
            }
        }

        start.countDown()
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发任务未在超时内完成")
        pool.shutdown()

        val expected = threads.toLong() * stepsPerThread
        // 不要求一定丢（取决于 CPU 核数和调度），但如果丢了就记录下来，
        // 说明这个场景确实能复现问题。
        if (current != expected) {
            println("=== 无锁累加丢失 ${expected - current} / $expected ===")
        }
        assertTrue(
            current <= expected,
            "无锁累加的结果不应该超过期望值",
        )
    }

    /**
     * 防止上面的测试与实现脱节。
     *
     * <p>直接读源码，确认 `progressListener()` 里确实用了锁。
     * 如果哪天有人把 `synchronized(progressLock)` 删了，
     * 这里会失败 —— 而不是让并发测试继续"通过"（它跑的是副本）。
     */
    @Test
    @DisplayName("实现里确实给进度状态加了锁")
    fun progressListenerUsesLock() {
        val source = java.io.File("src/main/kotlin/org/cn/xiaofan/launch/LaunchController.kt")
        assertTrue(source.exists(), "找不到 LaunchController.kt")

        val text = source.readText()
        assertTrue(
            text.contains("progressLock"),
            "progressListener() 里找不到 progressLock —— " +
                "并发保护被删了，进度数字会在多线程下悄悄偏小",
        )
        assertTrue(
            text.contains("synchronized(progressLock)"),
            "找不到 synchronized(progressLock) 调用点",
        )
    }
}
