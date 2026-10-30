package org.cn.xiaofan.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 会话快照的相等性与更新语义。
 *
 * <h2>这个测试针对的是评审指出的第二个缺陷</h2>
 *
 * 抽屉原来直接读 `controller.state.value`，而那是普通 `StateFlow`，
 * 只有被 `collectAsState` 的地方才会因它重组。主界面只 collect
 * **当前显示**那个会话，所以后台会话推进阶段不会引起任何重组 ——
 * 抽屉开着时，存活灯和 PID 会一直停在打开那一刻的旧值。
 *
 * 修法是每个会话挂一个 collect 协程，把关心的字段同步到
 * `mutableStateOf`。而"只在真的变了才写"依赖 data class 的
 * 相等性 —— 如果相等性坏了，要么漏更新，要么每次都触发重组。
 */
class SessionSnapshotTest {

    @Test
    @DisplayName("相同字段的两个快照相等")
    fun equalWhenFieldsMatch() {
        val a = SessionSnapshot(pid = 1234, processAlive = true, finished = true)
        val b = SessionSnapshot(pid = 1234, processAlive = true, finished = true)

        assertEquals(a, b, "相等性坏了会导致每次都触发重组（白耗性能）")
    }

    @Test
    @DisplayName("PID 变化会被认为是不同的快照")
    fun pidChangeIsDetected() {
        val before = SessionSnapshot(pid = null, processAlive = false)
        val after = SessionSnapshot(pid = 1234, processAlive = true)

        assertNotEquals(
            before,
            after,
            "PID 从 null 变成真实值必须被检测到 —— 否则抽屉里的 " +
                "`PID —` 永远不会更新",
        )
    }

    @Test
    @DisplayName("只有进程存活状态变化也算变化")
    fun survivalChangeIsDetected() {
        val downloading = SessionSnapshot(pid = null, processAlive = false, finished = false)
        val exited = SessionSnapshot(pid = 1234, processAlive = false, finished = true)

        // 这两个的 processAlive 都是 false，但 finished 不同。
        assertNotEquals(downloading, exited)
    }

    @Test
    @DisplayName("默认快照表示尚未开始")
    fun defaultIsPristine() {
        val fresh = SessionSnapshot()

        assertEquals(null, fresh.pid, "还没启动时不该有 PID")
        assertTrue(!fresh.processAlive, "还没启动时进程不该算活着")
        assertTrue(!fresh.finished, "还没启动时启动流程不该算结束")
        assertTrue(!fresh.cancelled, "还没启动时不该算取消")
    }
}
