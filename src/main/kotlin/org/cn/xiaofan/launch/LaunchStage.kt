package org.cn.xiaofan.launch

/**
 * 启动阶段。
 *
 * <p>这些阶段**不是** headlessmc 提供的 —— 它只暴露"进程启动成功 / 退出码"
 * 两件事，中间过程全在 `ClientLauncher.launcher()` 这个同步方法里，
 * 没有回调可挂。所以阶段只能靠两路信息推断：
 *
 * 1. **我们自己控制的步骤**：依赖处理、账号检查、构造命令，这几步的边界我们清楚
 * 2. **游戏进程的 stdout**：进程起来之后，靠解析日志行判断走到了哪一步
 *
 * <p>因此某些阶段是**估算**而非精确观测，见各枚举项的说明。
 *
 * ## 关于「处理游戏依赖」这一阶段（重要历史）
 *
 * 早期版本把这一阶段当成纯标记 —— 进到它之后立刻就把阶段推到「拉起进程」，
 * 然后才去调 `launcher()`。结果是界面显示「正在拉起游戏进程」时，
 * 真正的资源下载才刚开始，而下载要十几分钟。用户盯着一句静态文字干等，
 * 完全无法判断是卡死了还是在干活。
 *
 * 现在这一阶段由 `AssetDownloader` / `LibraryDownloader` 上报的**真实进度**
 * 驱动（见 `LaunchProgressBridge`），会上报百分比和已下载量。
 */
enum class LaunchStage(
    /** 界面上的完整描述。 */
    val label: String,
    /**
     * 是否已确认完成。
     *
     * <p>`false` 表示这是靠日志推断出来的，可能跳过去
     * （比如游戏没输出某行日志，或者输出格式变了）。
     */
    val confirmed: Boolean,
) {
    /**
     * 处理游戏依赖：解析版本 JSON、解析库清单、下载资源与库文件。
     *
     * <p>这是最耗时的阶段（首次启动十几分钟），期间会通过
     * `LaunchProgressBridge` 上报实时下载进度。
     */
    PREPARING_DEPS("正在处理游戏依赖", confirmed = true),

    /** 正在刷新账号令牌、生成 auth 参数。 */
    PREPARING_ACCOUNT("正在处理账号", confirmed = true),

    /** 已构造好完整命令，正在 fork 子进程。 */
    STARTING_PROCESS("正在拉起游戏进程", confirmed = true),

    /**
     * 子进程已创建，但游戏主类还在初始化（LWJGL、资源加载）。
     *
     * <p>这一阶段的边界是「我们已经拿到 Process 对象」，是精确的。
     */
    PROCESS_LAUNCHED("游戏进程已拉起正在启动", confirmed = true),

    /**
     * 游戏已设置玩家名。
     *
     * <p>**靠日志推断**：Minecraft 会输出 `Setting user: <name>`。
     * 如果客户端用了自定义启动参数、或者改了这行日志的格式，
     * 这个阶段可能在界面上被跳过 —— 但不会卡住，因为下一个阶段的条件
     * （窗口出现）也能单独触发。
     */
    USER_SET("游戏进程已成功设置用户名", confirmed = false),

    /**
     * 游戏窗口已加载。
     *
     * <p>**靠日志推断**：Minecraft 输出 `Sound engine started` 或
     * `Created: 1024x1024 textures`，说明渲染循环已经跑起来了。
     *
     * <p>为什么不用「进程存活 N 秒」这种近似：那样启动慢的机器会误判成功，
     * 启动快的机器要白等。
     */
    WINDOW_READY("游戏窗口已加载！启动成功", confirmed = false),
    ;

    /** 是不是终态（成功）。 */
    val isSuccess: Boolean get() = this == WINDOW_READY
}
