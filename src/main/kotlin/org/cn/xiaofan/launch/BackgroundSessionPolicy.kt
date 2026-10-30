package org.cn.xiaofan.launch

/**
 * 后台会话的存活判定。
 *
 * <h2>为什么单独抽出来</h2>
 *
 * `finished` 和 `processAlive` 的关系是这个功能里最容易搞错的地方，
 * 而它已经错过一次：
 *
 * - 最初只有 `finished`，"启动成功"要等游戏退出才显示
 * - 加上 `processAlive` 后，两者必须**分开用**：
 *   - 界面显示"启动成功"看 `finished`
 *   - 后台列表/停止按钮看 `processAlive`
 *
 * 搞错的后果很直接：关掉日志界面后游戏还在跑，但齿轮抽屉里空了 ——
 * 用户以为游戏没了，又点一次启动，于是开了两个实例。
 *
 * 抽成纯函数是为了能测。真正的 `SessionRegistry` 依赖会启动游戏的
 * `LaunchSession`，没法在单测里构造。
 */
object BackgroundSessionPolicy {

    /**
     * 这个会话是否应该留在后台列表里。
     *
     * <p>判据是**进程还活着**，不是"启动流程结束了"。
     *
     * <table>
     * <tr><th>场景</th><th>finished</th><th>processAlive</th><th>留在列表</th></tr>
     * <tr><td>正在下载依赖</td><td>false</td><td>false</td><td><b>是</b></td></tr>
     * <tr><td>进程已起、窗口未加载</td><td>false</td><td>true</td><td>是</td></tr>
     * <tr><td>游戏正常运行中</td><td>true</td><td>true</td><td>是</td></tr>
     * <tr><td>游戏已退出</td><td>true</td><td>false</td><td>否</td></tr>
     * <tr><td>启动失败</td><td>true</td><td>false</td><td>否</td></tr>
     * </table>
     *
     * <p>注意第一行：**还在下载的会话也必须留着**。用户点了返回之后，
     * 下载如果被中止就白费了；留着的话他可以点回来继续看进度。
     * 这也是为什么判据不能简单写成 `processAlive` ——
     * 下载阶段进程还不存在，但会话是活的。
     *
     * <p>所以真正的判据是「没有结束」= `!finished || processAlive`：
     * - 下载中：finished=false → 留着
     * - 运行中：finished=true 但 processAlive=true → 留着
     * - 已退出：finished=true 且 processAlive=false → 移除
     */
    fun shouldKeep(finished: Boolean, processAlive: Boolean, cancelled: Boolean): Boolean {
        // 用户主动取消的，立刻移出 —— 它不会再有进展了。
        if (cancelled) return false
        return !finished || processAlive
    }

    /**
     * 齿轮按钮上的角标数字。
     *
     * <p>与 [shouldKeep] 分开，因为语义不同：角标想说的是
     * "有几个游戏正开着"，所以只数进程活着的那些。
     *
     * <p>而列表要"所有还能点进去看的" —— 包括还在下载的。
     * 用户点进去能看到进度，这就是有价值的入口。
     */
    fun badgeCount(aliveCount: Int): Int = aliveCount.coerceAtLeast(0)
}
