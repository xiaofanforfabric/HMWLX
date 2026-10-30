package org.cn.xiaofan.launch

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * 后台会话存活判定的测试。
 *
 * <p>这套判定的错误后果很具体：关掉日志界面后游戏还在跑，
 * 但抽屉里空了 —— 用户以为游戏没了，又点一次启动，开了两个实例。
 */
class BackgroundSessionPolicyTest {

    @Test
    @DisplayName("正在下载依赖的会话必须保留")
    fun downloadingSessionIsKept() {
        // finished=false, processAlive=false —— 进程还不存在，但会话是活的。
        // 如果这里判成"该移除"，用户点返回后进度就彻底看不到了。
        assertTrue(
            BackgroundSessionPolicy.shouldKeep(
                finished = false,
                processAlive = false,
                cancelled = false,
            ),
            "还在下载的会话不该被移除：用户可能想点回来看进度",
        )
    }

    @Test
    @DisplayName("游戏正常运行中必须保留")
    fun runningSessionIsKept() {
        // 这是最关键的场景：窗口已加载（finished=true）但游戏在跑。
        // 用 finished 判定的话这里会被移除，用户就再也找不回游戏了。
        assertTrue(
            BackgroundSessionPolicy.shouldKeep(
                finished = true,
                processAlive = true,
                cancelled = false,
            ),
            "游戏还在跑，绝不能因为它'启动完成'就移出列表",
        )
    }

    @Test
    @DisplayName("进程已拉起但窗口未加载，保留")
    fun launchedButNotReadyIsKept() {
        assertTrue(
            BackgroundSessionPolicy.shouldKeep(
                finished = false,
                processAlive = true,
                cancelled = false,
            ),
        )
    }

    @Test
    @DisplayName("游戏已退出，移除")
    fun exitedSessionIsRemoved() {
        assertFalse(
            BackgroundSessionPolicy.shouldKeep(
                finished = true,
                processAlive = false,
                cancelled = false,
            ),
            "进程没了就没有可点进去看的东西了",
        )
    }

    @Test
    @DisplayName("启动失败，移除")
    fun failedSessionIsRemoved() {
        assertFalse(
            BackgroundSessionPolicy.shouldKeep(
                finished = true,
                processAlive = false,
                cancelled = false,
            ),
        )
    }

    @Test
    @DisplayName("主动取消的立刻移除")
    fun cancelledSessionIsRemoved() {
        // 取消意味着不会再有进展，留着只是噪音。
        assertFalse(
            BackgroundSessionPolicy.shouldKeep(
                finished = false,
                processAlive = false,
                cancelled = true,
            ),
        )
        // 即使进程还活着（取消时刚好起来），取消也优先。
        assertFalse(
            BackgroundSessionPolicy.shouldKeep(
                finished = true,
                processAlive = true,
                cancelled = true,
            ),
            "用户明确取消的，不该继续占据列表",
        )
    }

    @Test
    @DisplayName("角标不为负")
    fun badgeCountNeverNegative() {
        assertEquals(0, BackgroundSessionPolicy.badgeCount(-1))
        assertEquals(0, BackgroundSessionPolicy.badgeCount(0))
        assertEquals(3, BackgroundSessionPolicy.badgeCount(3))
    }
}
