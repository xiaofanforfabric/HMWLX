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

import java.util.concurrent.TimeUnit

/**
 * 调整游戏进程的 CPU 优先级。
 *
 * <h2>为什么在进程启动后才调</h2>
 *
 * Java 的 `ProcessBuilder` **没有**设置 nice 值的 API，而 JVM 也不认
 * `-XX:` 形式的 nice 参数。唯一可行的路子是进程起来之后用外部命令改，
 * 于是就有了这个类。
 *
 * <h2>为什么不只用 `renice`</h2>
 *
 * `renice` 需要目标 PID 存在且归当前用户所有 —— 两个条件启动瞬间都满足，
 * 但它**只能调低不能调高**（非 root 用户无法把 nice 改成负数）。
 * 降到 0..19 用 `renice` 没问题；想提到负数就是 `Permission denied`。
 * 所以这里先试，失败就如实报告，不假装成功。
 *
 * <h2>失败不是错误</h2>
 *
 * 优先级调整纯属锦上添花：调不动说明系统不让，游戏照样能跑。
 * 因此所有失败都返回结果值而不是抛异常 —— 调用方把提示写进日志即可，
 * 绝不能因为改不了 nice 就让启动流程失败。
 */
object ProcessPriority {

    /**
     * 调整结果。
     *
     * @param applied 是否真的改成功了
     * @param message 给用户看的说明，成功时也为非空（写进启动日志）
     */
    data class Result(val applied: Boolean, val message: String)

    /**
     * 把 PID 的 nice 值改成 [priority]。
     *
     * @param pid 游戏进程 PID
     * @param priority 目标 nice 值，-20..19
     * @return 结果。`priority == 0` 时直接返回「未调整」。
     */
    fun apply(pid: Long, priority: Int): Result {
        if (priority == 0) return Result(false, "优先级：默认（未调整）")
        if (pid <= 0) return Result(false, "优先级：无法调整（PID 无效）")

        // 只能靠 renice。
        //
        // 曾经这里还有个 nice -n <prio> -p <pid> 的兜底分支，删掉了：
        // nice 只能调整**即将执行的命令**的优先级，procps 虽然提供了
        // -p 扩展，但作用于已在运行的进程时会被内核静默忽略 ——
        // 命令返回 0，优先级却没变，属于"看起来成功实则没做"。
        // 与其留一个骗人的分支，不如让它明确失败。
        val attempt = runRenice(pid, priority)
            ?: return Result(false, "优先级：调整失败（系统缺少 renice 命令）")

        return if (attempt.exitCode == 0) {
            Result(true, "优先级：已设为 nice=$priority")
        } else {
            // 最常见的是把 nice 调成负数时的 Permission denied。
            // 把原始报错带上，用户查起来有据可依。
            val detail = attempt.errorText.ifBlank { "退出码 ${attempt.exitCode}" }
            Result(false, "优先级：调整失败（$detail）")
        }
    }

    /** 一次命令执行的结果。 */
    internal data class Attempt(val exitCode: Int, val errorText: String)

    /**
     * `renice -n <priority> -p <pid>`。
     *
     * <p>返回 null 表示命令根本执行不了（不存在 / 被权限策略拦住）。
     */
    private fun runRenice(pid: Long, priority: Int): Attempt? =
        runCommand(listOf("renice", "-n", priority.toString(), "-p", pid.toString()))

    /**
     * 执行外部命令并等待。
     *
     * <p>5 秒超时：`renice` 是瞬间完成的，卡住说明系统状态异常，
     * 这时宁可放弃也不要挂住启动线程。超时按失败处理。
     */
    private fun runCommand(command: List<String>): Attempt? = runCatching {
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .start()
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return@runCatching Attempt(-1, "命令超时")
        }
        val output = runCatching {
            process.inputStream.readBytes().toString(Charsets.UTF_8).trim()
        }.getOrDefault("")
        Attempt(process.exitValue(), output)
    }.getOrNull()
}
