package org.cn.xiaofan.dev

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

/**
 * 「嘉豪终端」：以 CLI 模式直接驱动上游 headlessmc。
 *
 * <h2>为什么起独立进程而不是在容器里调 bean</h2>
 *
 * headlessmc 的交互式 shell 是围绕 `System.in` / `System.out` 写的
 * （`Console` → `SystemInputProvider` → `BufferedReader(System.in)`）。
 * 想在同一个 JVM 里接管它，就得替换全局 `System.in/out` —— 那会
 * 破坏 GUI 自己的 stdout，而且进程退出时无法干净还原。
 *
 * <p>起独立进程还额外获得两个好处：
 * - CLI 里的崩溃不会连累 GUI
 * - 用户能真的 Ctrl+C 一类地「结束终端」，而不影响主程序
 *
 * <h2>为什么用 `HeadlessMcMain` 而不是 `Quarkus.run`</h2>
 *
 * `HeadlessMcMain` 是上游的 `@QuarkusMain` 入口，它自己会处理
 * `-Dkey=value` 形式的参数解析。直接沿用它，行为与用户从命令行敲
 * `headlessmc` 完全一致 —— 这正是「体验没有接入 GUI 的功能」的要求。
 */
object CliTerminalService {

    /** 终端的运行状态。 */
    enum class State {
        /** 尚未启动，或已正常退出。 */
        STOPPED,

        /** 进程正在启动/运行。 */
        RUNNING,
    }

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * 输出行。
     *
     * <p>用 SharedFlow 而不是拼接字符串：CLI 输出可能很多，
     * 界面上需要按行追加、可滚动、可清空。
     */
    private val _output = MutableSharedFlow<String>(extraBufferCapacity = 512)
    val output: SharedFlow<String> = _output.asSharedFlow()

    private var process: Process? = null
    private var writer: BufferedWriter? = null

    /**
     * 启动 CLI 进程。
     *
     * @return 启动失败的原因；成功返回 null。
     */
    suspend fun start(debugMode: Boolean): String? = withContext(Dispatchers.IO) {
        if (_state.value == State.RUNNING) return@withContext null

        runCatching {
            val java = javaExecutable()
            val classpath = currentClasspath()

            val cmd = mutableListOf(
                java,
                // 与主程序一致的编码，否则中文命令行输出会是乱码
                "-Dfile.encoding=UTF-8",
                // CLI 里不需要 Quarkus 的 HTTP 层，省点启动时间与端口占用
                "-Dquarkus.http.host-enabled=false",
            )
            if (debugMode) {
                // 与 GUI 的调试模式保持一致：CLI 里的日志也要详细
                cmd += listOf(
                    "-Dhmc.log.console-level=DEBUG",
                    "-Dquarkus.log.level=DEBUG",
                    "-Dquarkus.log.min-level=DEBUG",
                )
            }
            cmd += listOf("-cp", classpath, HEADLESSMC_MAIN)

            val proc = ProcessBuilder(cmd)
                .redirectErrorStream(true) // stderr 也走同一路，顺序不会乱
                .start()

            process = proc
            writer = BufferedWriter(
                OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8),
            )
            _state.value = State.RUNNING
            _output.tryEmit("[HMWLX] 终端已启动（PID ${proc.pid()}）")
            _output.tryEmit("[HMWLX] 输入 help 查看可用命令，输入 exit 退出")
            _output.tryEmit("")

            // 后台读输出。读线程随进程结束而退出，不需要额外管理生命周期。
            Thread({ pump(proc) }, "hmwlx-cli-reader").apply {
                isDaemon = true
                start()
            }

            null
        }.getOrElse { e ->
            _state.value = State.STOPPED
            e.describe()
        }
    }

    /** 往终端写一行并回车。 */
    fun send(line: String) {
        val w = writer ?: return
        runCatching {
            w.write(line)
            w.newLine()
            w.flush()
        }.onFailure {
            _output.tryEmit("[HMWLX] 写入失败：${it.describe()}")
        }
        // 回显用户输入：CLI 进程的 stdout 不包含自己的输入，
        // 不回显的话界面看起来像是没接收到用户敲的东西。
        _output.tryEmit("> $line")
    }

    /** 结束终端进程。 */
    fun stop() {
        val p = process ?: return
        val w = writer

        // 【不能在调用线程上等】stop() 是从按钮 onClick 直接调的，
        // 等于在 Compose 主线程上同步阻塞，窗口会卡死。
        // 所以状态同步先做（界面立即响应），真正的关闭放到后台线程。
        process = null
        writer = null
        _state.value = State.STOPPED
        _output.tryEmit("[HMWLX] 终端已关闭")

        Thread({
            // 先关 stdin，给 CLI 自己的 exit 逻辑一个清理机会
            runCatching { w?.close() }
            // 最多等 2 秒，超时就直接杀 —— 但这是在后台线程上等，不阻塞 UI
            val exited = runCatching { p.waitFor(2, TimeUnit.SECONDS) }.getOrDefault(false)
            if (!exited) {
                runCatching { p.destroy() }
                // destroy() 只是发 SIGTERM，进程赖着不走再强杀
                val killed = runCatching { p.waitFor(1, TimeUnit.SECONDS) }.getOrDefault(false)
                if (!killed) runCatching { p.destroyForcibly() }
            }
        }, "hmwlx-cli-stopper").apply {
            isDaemon = true
            start()
        }
    }

    /** 读进程输出，逐行推到 flow。 */
    private fun pump(proc: Process) {
        runCatching {
            BufferedReader(
                InputStreamReader(proc.inputStream, StandardCharsets.UTF_8),
            ).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    _output.tryEmit(line)
                }
            }
        }
        // 进程结束（正常退出或被 stop()）：同步状态，避免界面一直显示"运行中"
        _state.value = State.STOPPED
        process = null
        writer = null
        _output.tryEmit("[HMWLX] 进程已退出（code ${runCatching { proc.exitValue() }.getOrDefault(-1)}）")
    }

    /**
     * 当前 JVM 的可执行文件路径。
     *
     * <p>用 `java.home` 而不是 `java.class.path` 猜：打包成 jlink 运行时后
     * （`build/HMWLX-linux-x64/runtime/bin/java`）路径不同，
     * 但 `java.home` 始终指向正确的 bin 目录。
     */
    private fun javaExecutable(): String {
        val home = System.getProperty("java.home") ?: error("拿不到 java.home")
        val name = if (isWindows()) "java.exe" else "java"
        val candidate = File(home, "bin/$name")
        return if (candidate.canExecute()) candidate.absolutePath else name
    }

    /**
     * 当前 JVM 的完整 classpath。
     *
     * <p>开发态（Gradle run）与打包态（fat jar）都可以直接读
     * `java.class.path` —— 我们自己的进程就是这么被启动的，
     * 子进程沿用同一份，能保证依赖完全一致。
     */
    private fun currentClasspath(): String =
        System.getProperty("java.class.path")
            ?.takeIf { it.isNotBlank() }
            ?: error("拿不到 java.class.path，无法启动终端")

    private fun isWindows(): Boolean =
        System.getProperty("os.name").orEmpty().lowercase().contains("win")

    /** 上游 CLI 入口。 */
    private const val HEADLESSMC_MAIN = "io.github.headlesshq.headlessmc.application.HeadlessMcMain"

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
