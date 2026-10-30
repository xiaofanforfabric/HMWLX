package org.cn.xiaofan.launch

import io.github.headlesshq.headlessmc.auth.Account
import io.github.headlesshq.headlessmc.launcher.client.ClientLauncher
import io.github.headlesshq.headlessmc.launcher.process.McProcess
import io.github.headlesshq.headlessmc.launcher.process.ProcessLauncher
import io.github.headlesshq.headlessmc.launcher.profile.LaunchOptions
import io.github.headlesshq.headlessmc.launcher.profile.Profile
import io.github.headlesshq.headlessmc.version.arg.VersionArg
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.cn.xiaofan.version.GameVersionService
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Optional
import java.util.concurrent.TimeUnit

/**
 * 一次启动的状态。
 *
 * 整体是不可变快照，靠 [LaunchController.state] 的 `StateFlow` 推送。
 * Compose 侧直接 `collectAsState` 即可。
 */
data class LaunchState(
    /** 当前阶段。 */
    val stage: LaunchStage = LaunchStage.PREPARING_DEPS,

    /**
     * 已脱敏的日志行。
     *
     * **存的是已经过 [LogSanitizer] 的文本**。在数据进入状态机的那一刻脱敏，
     * 出口只有一个，漏不了。
     */
    val logs: List<String> = emptyList(),

    /**
     * 当前正在进行的子任务进度。
     *
     * 下载几百 MB 资源时，界面必须有东西在动 —— 否则用户会以为卡死了。
     * null 表示当前没有可量化的任务（比如正在解析 JSON）。
     */
    val progress: LaunchProgress? = null,

    /**
     * **启动流程**是否已经结束（成功、失败或取消）。
     *
     * 注意语义：`finished = true` 表示"启动这件事有了结论"，
     * **不代表游戏进程已经退出**。游戏窗口加载成功后就会置 true，
     * 此时进程还在跑 —— 判断进程是否存活请看 [processAlive]。
     *
     * 这两个概念必须分开：用户要的是"窗口出来了就告诉我启动成功"，
     * 而不是"等我玩完关掉游戏才告诉我启动成功"。
     */
    val finished: Boolean = false,

    /**
     * 游戏进程是否还活着。
     *
     * 与 [finished] 独立：启动成功后它仍为 true，退出后才变 false。
     * 界面据此决定要不要显示「停止游戏」按钮。
     */
    val processAlive: Boolean = false,

    /** 失败原因。非 null 表示启动失败。 */
    val error: String? = null,

    /** 游戏进程的 PID。未启动时为 null。 */
    val pid: Long? = null,

    /** 是否被用户主动取消。 */
    val cancelled: Boolean = false,
) {
    /** 是否成功启动到"窗口已加载"。 */
    val succeeded: Boolean get() = finished && error == null && !cancelled
}

/**
 * 一个可量化的子任务进度。
 *
 * @param taskName 任务名，headlessmc 给的是英文（`Downloading Assets`），
 *   展示前会翻译
 * @param current 已完成量，单位见 [unitName]
 * @param total 总量，-1 表示未知
 * @param unitName 单位名（`mb` / null 表示按个数）
 */
data class LaunchProgress(
    val taskName: String,
    val current: Long,
    val total: Long,
    val unitName: String?,
) {
    /** 完成比例 0..1，总量未知时返回 null。 */
    val fraction: Double? get() = if (total > 0) (current.toDouble() / total).coerceIn(0.0, 1.0) else null

    /** 人类可读的进度描述，如 `468/485mb`。 */
    val description: String
        get() {
            val unit = unitName ?: ""
            return if (total > 0) "$current/$total$unit" else "$current$unit"
        }
}

/**
 * 启动控制器：负责从"点按钮"到"游戏跑起来"的全过程。
 *
 * ## 进度是怎么来的（重要）
 *
 * 早期版本把阶段当成"我们代码的顺序"来推进，结果是灾难：
 * 进入「正在拉起游戏进程」时，真正的依赖下载才刚开始，而下载要 12 分钟。
 * 用户盯着「正在处理游戏依赖」一句静态文字等了 12 分钟，以为程序挂了。
 *
 * **根本原因**：`ClientLauncher.launcher()` 是同步阻塞方法，
 * 中间过程没有回调，所以「阶段」不能靠我们自己的代码顺序推。
 *
 * 现在的做法：
 *
 * | 阶段 | 驱动力 |
 * |---|---|
 * | 处理游戏依赖 | headlessmc 的 `ProgressbarService` 上报的真实下载事件 |
 * | 处理账号 | 我们自己的边界（refresh 已完成，这里只是交接） |
 * | 拉起进程 | `launcher()` 返回后（此时依赖已就绪） |
 * | 游戏启动中 | `launch()` 返回 `McProcess` |
 * | 设置用户名 / 窗口就绪 | 解析游戏 stdout |
 *
 * 第 1 阶段是**真正的实时进度**：`AssetDownloader` 每下载一块就 `stepBy()`，
 * 通过 [LaunchProgressBridge] 流到界面。
 *
 * ## 为什么必须自己读 stdout
 *
 * `launch(pipeIO = true)` 会把子进程输出重定向到 PIPE。
 * 我们必须主动读 —— 否则管道缓冲区（通常 64KB）写满后游戏会阻塞在写日志上，
 * 表现为"卡死在启动画面"。这不是优化，是必须做的。
 */
class LaunchController(private val scope: CoroutineScope) {

    private val _state = MutableStateFlow(LaunchState())
    val state: StateFlow<LaunchState> = _state.asStateFlow()

    /** 当前在读日志的任务。 */
    private var logJob: Job? = null

    /** 主启动任务。取消时要连它一起取消，见 [cancel]。 */
    private var launchJob: Job? = null

    /** 游戏进程。用于停止。 */
    @Volatile
    private var process: Process? = null

    /** 用户是否已经要求取消。取消后不再启动进程。 */
    @Volatile
    private var cancelRequested = false

    /**
     * 启动游戏。
     *
     * @param versionId 要启动的版本
     * @param account 已通过预检的账号，token 是刷新过的
     * @return 启动任务
     */
    fun launch(versionId: String, account: Account): Job {
        launchJob = scope.launch {
            // 把自己注册为进度接收方。必须在 launcher() 之前 ——
            // 依赖下载就发生在那个调用里。
            LaunchProgressBridge.current = progressListener()

            try {
                // ---- 阶段 1：处理游戏依赖（由真实进度事件驱动）----
                //
                // 阶段顺序必须反映**真实耗时**，不能按"我们写代码的顺序"排：
                //
                //   launcher() 内部 = 解析版本 → 下载资源/库 → 打补丁 → 构造命令
                //
                // 这一段在冷启动时要十几分钟。所以「处理账号」绝不能放在它前面
                // —— 否则下载进度会显示在「处理账号」阶段底下，
                // 用户看到的是"处理账号"在跑进度条，完全对不上正在发生的事。
                //
                // 正确顺序：依赖（下载进度）→ 账号 → 拉起进程。
                _state.update { it.copy(stage = LaunchStage.PREPARING_DEPS) }
                appendLog("正在准备启动环境，版本: $versionId")
                appendLog("首次启动需要下载游戏资源，请耐心等待…")

                // 依赖下载 + 构造命令都在这一个调用里完成，
                // 期间进度通过 LaunchProgressBridge 实时流到界面，
                // 挂在**当前阶段（处理游戏依赖）**下面。
                //
                // 但上游在「资源全部已缓存」时会整个跳过下载分支
                // （`AssetDownloader`: `if (!tasks.isEmpty()) download(...)`），
                // 一个进度事件都不会有。那时候界面会静悄悄几十秒，
                // 用户又会以为卡死了。所以额外起一个心跳，见 watchStall()。
                val watchdog = watchStall()
                val mcProcess = try {
                    withContext(Dispatchers.IO) { buildAndLaunch(versionId, account) }
                } finally {
                    watchdog.cancel()
                }

                // ---- 阶段 2：账号 ----
                //
                // 放在依赖**之后**是有意的：
                //
                // 1. 顺序上真实 —— 上游确实是先准备好游戏文件，再用账号生成启动参数
                // 2. 账号这一步是瞬间完成的（token 在 AuthPreflight 已刷新过），
                //    把它放在下载之后，用户看到的是一个快速划过的确认，
                //    而不是一个挂着进度条、其实在下载文件的假阶段
                _state.update { it.copy(stage = LaunchStage.PREPARING_ACCOUNT) }
                appendLog("账号: ${account.name} (${account.provider})，${tokenStatus(account)}")

                // 用户在下载期间点了返回：不启动进程。
                if (cancelRequested) {
                    appendLog("已取消启动，不再拉起游戏进程")
                    _state.update { it.copy(finished = true, cancelled = true) }
                    return@launch
                }

                // ---- 阶段 3 已完成（launcher() 返回即依赖就绪）----
                // ---- 阶段 4：进程已拉起 ----
                val rawProcess = mcProcess.process.orElse(null)
                process = rawProcess

                _state.update {
                    it.copy(
                        stage = LaunchStage.PROCESS_LAUNCHED,
                        pid = rawProcess?.pid(),
                        progress = null,
                        processAlive = rawProcess != null,
                    )
                }
                appendLog("进程已启动，PID: ${rawProcess?.pid() ?: "未知"}")

                if (rawProcess == null) {
                    fail("进程对象为空，无法读取日志")
                    return@launch
                }

                // ---- 阶段 5–6：读日志推断 ----
                pumpOutput(rawProcess)

                val exit = withContext(Dispatchers.IO) { rawProcess.waitFor() }
                finish(exit)
            } catch (e: kotlinx.coroutines.CancellationException) {
                appendLog("启动已取消")
                _state.update { it.copy(finished = true, cancelled = true) }
                throw e
            } catch (t: Throwable) {
                fail(t.message ?: t.javaClass.simpleName)
            } finally {
                LaunchProgressBridge.clear()
            }
        }
        return launchJob!!
    }

    /**
     * 取消启动。
     *
     * **必须真的停下来**，不能只是关掉界面。
     *
     * 早期版本的 bug：`cancel()` 只取消了读日志的任务，
     * 而正在 `launcher()` 里下载资源的协程继续跑完、起了进程 ——
     * 用户点了"返回"、等了十几分钟，游戏窗口自己冒了出来。
     *
     * 现在的做法：
     * 1. 置 `cancelRequested`，让下载完成后不再启动进程
     * 2. 取消主任务（`ClientLauncher.launcher()` 内部的阻塞调用不会立即中断，
     *    但返回后会被 `cancelRequested` 拦住）
     * 3. 如果进程已经起来了，杀掉它
     */
    fun cancel() {
        cancelRequested = true
        appendLog("正在取消启动…")

        // 进程已经起来了：给游戏一个体面退出的机会再杀。
        process?.let { p ->
            if (p.isAlive) {
                appendLog("游戏已启动，正在结束进程")
                scope.launch(Dispatchers.IO) {
                    p.destroy()
                    if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
                }
            }
        }

        logJob?.cancel()
        logJob = null
        launchJob?.cancel()
    }

    /**
     * 停止游戏进程。
     *
     * 先 `destroy()` 给游戏保存存档的机会，等不到才 `destroyForcibly()`。
     * 直接强杀会丢存档。
     */
    fun stop() {
        val p = process ?: return
        appendLog("正在停止游戏…")
        scope.launch(Dispatchers.IO) {
            p.destroy()
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                appendLog("游戏未在 15 秒内退出，强制结束")
                p.destroyForcibly()
            }
        }
    }

    // -----------------------------------------------------------------------
    // 进度监听
    // -----------------------------------------------------------------------

    /**
     * 构造进度监听器。
     *
     * 把 headlessmc 的原始事件翻译成界面能显示的东西：
     * - `onStart` → 记录任务名和总量，界面显示进度条
     * - `onStep` → 累加，更新百分比
     * - `onFinish` → 清掉进度（下一个任务会重新 onStart）
     *
     * **节流**：下载时 `stepBy()` 每块都会调，可能有几千次。
     * 每次都更新 StateFlow 会让界面重组过于频繁。所以只在
     * 百分比变化超过 1% 或者每 200ms 时才推送一次。
     *
     * ## 并发安全（重要）
     *
     * `AssetDownloader` 用 `ParallelTaskService` **多线程**并发调
     * `stepBy()`，而它拿到的是**同一个** listener 实例（见
     * `LaunchProgressBridge` 的类注释）。
     *
     * 所以这里的 `current`/`total`/`lastEmitAt`/`lastPercent` 都是
     * 被多线程共享的可变状态，不能直接读写：
     *
     * - `current += delta` 是非原子的读-改-写，并发下会**丢计数**，
     *   表现为进度条数字偏小、百分比长期落后于实际。
     * - `onStart()` 里 `current = 0` 会把另一路正在累加的计数**抹掉**。
     * - `lastEmitAt`/`lastPercent` 的"读-判断-写"不是原子的，
     *   多个线程同时判断为"该推送"就会重复 emit。
     *
     * 这些错误都**不会报错、不会崩溃**，只会让进度数字悄悄偏小 ——
     * 正是最难发现的那类 bug。所以所有状态访问统一用
     * [progressLock] 串行化。
     *
     * 用一把锁而不是 AtomicLong：这几个字段必须**作为一个整体**
     * 一起被读（`emit` 要同时看 current 和 total 才算得出百分比），
     * 分开的原子变量会让它们之间出现不一致的中间态。
     */
    private fun progressListener(): LaunchProgressListener {
        // 所有可变状态都在锁内访问。
        val progressLock = Any()

        var current = 0L
        var total = -1L
        var name = ""
        var unit: String? = null
        var lastEmitAt = 0L
        var lastPercent = -1

        /**
         * 按需推送一次快照。**调用方必须已持有 [progressLock]**。
         *
         * 这样设计是为了让"读状态 → 判断是否该推 → 更新节流字段 → 推送"
         * 成为一个不可分割的整体。如果在这里自己加锁，会和外层重复加锁
         * （Kotlin 的 synchronized 可重入，不会死锁，但语义更绕）。
         */
        fun emitLocked(force: Boolean) {
            val now = System.currentTimeMillis()
            val percent = if (total > 0) ((current * 100) / total).toInt() else -1

            val shouldEmit = force ||
                percent != lastPercent ||
                now - lastEmitAt > 200

            if (!shouldEmit) return
            lastEmitAt = now
            lastPercent = percent

            _state.update {
                it.copy(progress = LaunchProgress(name, current, total, unit))
            }
        }

        return object : LaunchProgressListener {
            override fun onStart(taskName: String, initialMax: Long, unitName: String?, unitSize: Long) {
                synchronized(progressLock) {
                    name = taskName
                    total = initialMax
                    unit = unitName
                    current = 0
                    lastPercent = -1
                    emitLocked(force = true)
                }
                // 日志在锁外写：它走 _state.update（本身就是线程安全的），
                // 且不该拖长持锁时间。
                appendLog("开始: ${translateTaskName(taskName)}")
            }

            override fun onStep(delta: Long) {
                synchronized(progressLock) {
                    current += delta
                    emitLocked(force = false)
                }
            }

            override fun onAbsolute(value: Long) {
                synchronized(progressLock) {
                    current = value
                    emitLocked(force = false)
                }
            }

            override fun onMaxHint(max: Long) {
                synchronized(progressLock) {
                    total = max
                    emitLocked(force = true)
                }
            }

            override fun onFinish(taskName: String) {
                synchronized(progressLock) {
                    lastPercent = -1
                    // 清掉进度条：下一个任务会重新 onStart。
                    _state.update { it.copy(progress = null) }
                }
                appendLog("完成: ${translateTaskName(taskName)}")
            }
        }
    }

    /**
     * 把 headlessmc 的英文任务名翻成中文。
     *
     * 这些名字是上游硬编码的，不是 i18n key —— 所以只能按字符串匹配。
     * 匹配不到的保留原文（总比显示空好，也便于排查）。
     */
    private fun translateTaskName(name: String): String = when {
        name.contains("Assets", ignoreCase = true) -> "下载游戏资源"
        name.contains("Libraries", ignoreCase = true) || name.contains("Library", ignoreCase = true) ->
            "下载依赖库"
        name.contains("Java", ignoreCase = true) -> "下载 Java 运行时"
        else -> name
    }

    // -----------------------------------------------------------------------
    // 内部实现
    // -----------------------------------------------------------------------

    /**
     * 在「处理游戏依赖」阶段没有进度事件时，周期性提示"还在干活"。
     *
     * <h2>为什么需要这个</h2>
     *
     * 上游 `AssetDownloader` 在资源全部已缓存时**整个跳过**下载分支：
     * ```java
     * if (!tasks.isEmpty()) { download(config, tasks, totalSize); }
     * ```
     * 于是 `displayProgressBar()` 根本没被调用，一个进度事件都没有。
     *
     * 这时 `launcher()` 仍要花几十秒做别的事（校验收、打补丁、构建 classpath）。
     * 界面如果只显示一句静态的「正在处理游戏依赖」，用户完全分辨不出
     * 是"在干活"还是"卡死了"—— 这正是之前那个 12 分钟黑屏投诉的心理根源。
     *
     * 心跳解决的就是这个问题：只要界面上的秒数在涨，用户就知道程序活着。
     * 它不是假进度 —— 不假装知道完成了多少，只如实报告"已经做了 N 秒"。
     *
     * @return 心跳任务，调用方负责取消
     */
    private fun watchStall(): Job = scope.launch {
        var seconds = 0
        while (isActive && !cancelRequested) {
            delay(HEARTBEAT_MILLIS)
            seconds += (HEARTBEAT_MILLIS / 1000).toInt()

            // 已经有真实下载进度了就不插话 —— 进度条比心跳有用得多。
            if (_state.value.progress != null) continue

            // 只在还停在依赖阶段时才提示。已经推进到后面就不该再刷。
            if (_state.value.stage != LaunchStage.PREPARING_DEPS) continue

            appendLog("正在校验与准备本地依赖… 已用时 ${seconds}s")
        }
    }

    /**
     * 构造 Profile 并启动进程。
     *
     * 与 `LaunchCommand.call()` 走同一套 `ClientLauncher`，区别只是
     * 我们自己构造 Profile，不经过 picocli 解析命令行。
     */
    private suspend fun buildAndLaunch(versionId: String, account: Account): McProcess {
        val clientLauncher = HeadlessMcBootstrap.bean(ClientLauncher::class.java)

        val versionArg = VersionArg.parse(versionId)
        val gameDir = GameVersionService.resolveGameDir().toPath()

        val profile = Profile("hmwlx-$versionId", versionArg, gameDir).withOptions(
            LaunchOptions(Optional.empty(), Optional.empty(), Optional.empty(), false)
        )

        // launcher() 内部完成：解析版本 → 下载资源 → 下载库 → 打补丁 → 构造命令。
        // 这段时间的进度通过 LaunchProgressBridge 流到界面。
        val launcher: ProcessLauncher = clientLauncher.launcher(profile, account)

        // 进到这一步说明依赖已就绪，可以报告"正在拉起进程"了。
        _state.update { it.copy(stage = LaunchStage.STARTING_PROCESS, progress = null) }
        appendLog("依赖已就绪，正在拉起游戏进程…")

        return launcher.launch(true)
    }

    /**
     * 持续读取进程输出，逐行脱敏后推送，并按关键字推进阶段。
     */
    private fun pumpOutput(process: Process) {
        val reader = BufferedReader(InputStreamReader(process.inputStream, StandardCharsets.UTF_8))

        logJob = scope.launch(Dispatchers.IO) {
            try {
                while (isActive) {
                    val line = reader.readLine() ?: break
                    onLine(line)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (t: Throwable) {
                appendLog("读取进程输出失败: ${t.message}")
            } finally {
                runCatching { reader.close() }
            }
        }
    }

    /** 处理一行日志：**先脱敏，再判断阶段**。 */
    private fun onLine(rawLine: String) {
        appendLog(LogSanitizer.sanitize(rawLine))
        advanceStage(rawLine)
    }

    /**
     * 按日志关键字推进阶段。只前进不后退。
     *
     * 关键字取自**实际游戏日志**（1.21.4 / 26.3 实测），按出现时间排序：
     *
     * ```
     * [21:52:57] Setting user: xiaofanbot              ← 用户名
     * [21:53:09] Created window using SDL video driver ← 窗口创建（关键证据）
     * [21:53:10] Reloading ResourceManager: vanilla    ← 开始加载资源
     * [21:53:34] OpenAL initialized on device ...      ← 音频
     * [21:53:34] Sound engine started                  ← 声音就绪
     * [21:53:34] Created: 512x256x0 ...textures/...    ← 贴图图集
     * ```
     *
     * 注意 `Setting user` 到窗口创建之间有 **12 秒**，到声音/贴图就绪又有
     * **25 秒**。以前只认后面那几个，导致"启动成功"要等到资源加载完才显示
     * —— 用户以为卡住了；而在进程异常退出时，又容易被残留日志行骗到。
     */
    private fun advanceStage(rawLine: String) {
        val current = _state.value.stage
        if (current.ordinal >= LaunchStage.WINDOW_READY.ordinal) return

        val next = when {
            isWindowReadyLine(rawLine) -> LaunchStage.WINDOW_READY
            isUserSetLine(rawLine) -> LaunchStage.USER_SET
            else -> return
        }

        if (next.ordinal > current.ordinal) {
            _state.update {
                it.copy(
                    stage = next,
                    // 窗口一加载就宣布启动完成 —— 这才是用户要的"启动成功"。
                    //
                    // 以前这里只改 stage，`finished` 要等到进程退出（玩完关掉游戏）
                    // 才置 true，于是界面标题一直是"启动中"，用户以为没成功。
                    // 启动完成 ≠ 进程结束，这两个状态必须分开。
                    finished = if (next == LaunchStage.WINDOW_READY) true else it.finished,
                    // 进程显然还活着（窗口都出来了）。
                    processAlive = if (next == LaunchStage.WINDOW_READY) true else it.processAlive,
                    // 启动完了就没有"进行中的下载"了。
                    progress = if (next == LaunchStage.WINDOW_READY) null else it.progress,
                )
            }
            if (next == LaunchStage.WINDOW_READY) {
                appendLog("✅ 游戏窗口已加载，启动成功")
            }
        }
    }

    private fun isUserSetLine(line: String): Boolean =
        line.contains("Setting user:", ignoreCase = true)

    /**
     * 窗口是否**真的已经创建**。
     *
     * 按可靠性排序，任意一条命中即算就绪：
     *
     * 1. `Created window using SDL video driver` —— 窗口创建的直接证据（1.21.4+）
     * 2. `Sound engine started` —— 音频初始化，前提是窗口已存在
     * 3. `OpenAL initialized` —— 同上，更早一点
     * 4. `Created: <w>x<h>x<d> ... textures` —— 贴图图集，渲染管线在跑了
     *
     * 为什么放宽到 4 条：不同版本/加载器打印的日志不一样。
     * 宁可多认几个后备，也不要因为某个版本少打一行就永远停在"启动中"。
     * 这四条都只在**窗口真的存在之后**才会出现，不会误报。
     */
    private fun isWindowReadyLine(line: String): Boolean {
        if (line.contains("Created window", ignoreCase = true)) return true
        if (line.contains("Sound engine started", ignoreCase = true)) return true
        if (line.contains("OpenAL initialized", ignoreCase = true)) return true
        // 贴图图集：必须同时有 "Created:" 和 "textures"，避免误匹配无关日志。
        if (line.contains("Created:") && line.contains("textures", ignoreCase = true)) return true
        return false
    }

    /** 追加一行日志（已脱敏）。 */
    private fun appendLog(line: String) {
        _state.update { s ->
            val next = if (s.logs.size >= MAX_LOG_LINES) {
                val keep = s.logs.subList(s.logs.size - MAX_LOG_LINES + 1, s.logs.size)
                keep + line
            } else {
                s.logs + line
            }
            s.copy(logs = next)
        }
    }

    /**
     * 进程已结束。
     *
     * 这里只管"进程生命周期的收尾"，**不再重新判定启动成败** ——
     * 如果窗口已经加载过，`finished` 早就是 true 了，这里只需要把
     * [LaunchState.processAlive] 置 false，让界面收起「停止游戏」按钮。
     *
     * 只有从未走到窗口就绪的进程退出，才算启动失败。
     */
    private fun finish(exitCode: Int) {
        val stage = _state.value.stage
        when {
            // 窗口已经出来了：无论退出码是什么，启动这一步都是成功的。
            // 之后游戏崩不崩是"运行期问题"，不该倒扣成"启动失败"。
            stage.ordinal >= LaunchStage.WINDOW_READY.ordinal -> {
                appendLog("游戏已退出（退出码 $exitCode）")
                _state.update {
                    it.copy(
                        finished = true,
                        processAlive = false,
                        error = null,
                        progress = null,
                    )
                }
            }
            exitCode == 0 -> {
                // 没走到窗口就退出码 0：游戏自己决定退出（比如立刻被关）。
                // 不报错，但也不是"启动成功" —— stage 会停在原处，
                // 界面不会显示成功，符合事实。
                appendLog("游戏进程已退出（退出码 0），但未检测到窗口加载")
                _state.update {
                    it.copy(finished = true, processAlive = false, progress = null)
                }
            }
            else -> {
                appendLog("游戏进程异常退出，退出码 $exitCode")
                _state.update {
                    it.copy(
                        finished = true,
                        processAlive = false,
                        progress = null,
                        error = "游戏进程异常退出（退出码 $exitCode），请查看上方日志",
                    )
                }
            }
        }
    }

    /** 启动失败。 */
    private fun fail(reason: String) {
        appendLog("启动失败: $reason")
        _state.update {
            it.copy(
                finished = true,
                error = reason,
                progress = null,
                processAlive = false,
            )
        }
    }

    /** 令牌状态的一句话描述，不含令牌本身。 */
    private fun tokenStatus(account: Account): String = when {
        account.token.isNullOrBlank() -> "无令牌（按离线模式启动）"
        else -> "令牌已刷新"
    }

    private companion object {
        /** 状态下保留的最大日志行数。 */
        const val MAX_LOG_LINES = 2000

        /**
         * 没有真实进度时的心跳间隔。
         *
         * 5 秒是个折中：再短会刷屏焦虑，再长用户就觉得卡住了。
         */
        const val HEARTBEAT_MILLIS = 5_000L
    }
}
