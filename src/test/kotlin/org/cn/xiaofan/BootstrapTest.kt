package org.cn.xiaofan

import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * 验证 headlessmc 的 Quarkus 容器能否在普通 JVM 进程中启动。
 *
 * 这是整个架构的关键假设：如果容器起不来，GUI 内嵌方案就得推翻。
 */
class BootstrapTest {

    @Test
    fun `容器能启动并取到 CommandLine bean`() {
        HeadlessMcBootstrap.start()

        assertEquals(
            HeadlessMcBootstrap.State.READY,
            HeadlessMcBootstrap.currentState(),
            "容器应处于 READY 状态"
        )

        val commandLine = HeadlessMcBootstrap.bean(picocli.CommandLine::class.java)
        assertFalse(
            commandLine.subcommands.isEmpty(),
            "根命令应有子命令"
        )

        println("=== headlessmc 容器启动成功 ===")
        println("根命令: ${commandLine.commandSpec.name()}")
        println("子命令数: ${commandLine.subcommands.size}")
        commandLine.subcommands.keys.sorted().take(20).forEach { println("  - $it") }
    }
}
