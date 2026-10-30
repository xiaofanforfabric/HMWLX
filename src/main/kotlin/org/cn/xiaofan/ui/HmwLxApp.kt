package org.cn.xiaofan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cn.xiaofan.account.AccountProfile
import org.cn.xiaofan.account.AccountResolver
import org.cn.xiaofan.account.AuthPreflight
import org.cn.xiaofan.account.blocksLaunch
import org.cn.xiaofan.account.shouldWarn
import org.cn.xiaofan.account.sourceLabel
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import org.cn.xiaofan.launch.BackgroundSessionPolicy
import org.cn.xiaofan.launch.LaunchController
import org.cn.xiaofan.ui.component.AccountBar
import org.cn.xiaofan.ui.component.AppBackground
import org.cn.xiaofan.ui.component.AuthFailureDialog
import org.cn.xiaofan.ui.component.BACKGROUND_DRAWER_WIDTH
import org.cn.xiaofan.ui.component.BackgroundGameEntry
import org.cn.xiaofan.ui.component.BackgroundGamesButton
import org.cn.xiaofan.ui.component.BackgroundGamesDrawer
import org.cn.xiaofan.ui.component.GEAR_BUTTON_SIZE
import org.cn.xiaofan.ui.component.LaunchButtonWithVersion
import org.cn.xiaofan.ui.component.LaunchOverlay
import org.cn.xiaofan.ui.component.LaunchTransition
import org.cn.xiaofan.ui.component.VersionDrawer
import org.cn.xiaofan.ui.component.rememberLaunchAnimationState
import org.cn.xiaofan.ui.screen.AccountSwitchScreen
import org.cn.xiaofan.ui.screen.LaunchingScreen
import org.cn.xiaofan.ui.screen.VersionListScreen
import org.cn.xiaofan.version.rememberVersionState

/**
 * 启动状态。UI 只依赖这个 sealed 类型，不直接碰 Quarkus。
 */
sealed interface BootState {
    /** 正在启动 Quarkus 容器。 */
    data object Loading : BootState

    /**
     * 容器已就绪。
     *
     * @param account 当前登录账号（含头像来源），未登录时为默认离线账号
     */
    data class Ready(val account: AccountProfile) : BootState

    /** 启动失败。 */
    data class Failed(val message: String, val cause: Throwable?) : BootState
}

/**
 * 应用主界面。
 *
 * 布局：背景图铺满，左侧留空让图自己露出来，右侧是一块半透明面板放所有操作。
 *
 * <p>为什么面板靠右而不是居中：背景图的主体（海面、山脊、「CrazyWaterWorldislaunched」(这个叫疯狂水世界,启动!!!)
 * 那种构图）通常在左侧或中部，面板压右边最不挡画面，也符合"启动器"的操作习惯
 * ——眼睛先看图，手往右下角找按钮。
 *
 * <p>版本列表不依赖容器：它读的是游戏目录里的文件，游戏目录能自己推出来。
 * 容器挂了也照样能列出本机装了什么。
 */
@Composable
fun HmwLxApp() {
    var state by remember { mutableStateOf<BootState>(BootState.Loading) }
    // 是否正在展示账号切换界面。账号切换是覆盖整个内容区的独立页面，
    // 而不是弹窗：它的列表可能很长，弹窗放不下。
    var showAccountSwitch by remember { mutableStateOf(false) }
    // 右侧面板当前所在的页。默认是首页，点入口才进具体功能。
    var panel by remember { mutableStateOf(MainPanel.HOME) }
    // 启动按钮的动画状态。提到这一层是因为火箭要飞到窗口顶部之外，
    // 覆盖层必须画在最外层 Box 里，而按钮在很深的内层 ——
    // 两处只能靠这个共享状态通信。
    val launchAnim = rememberLaunchAnimationState()
    val launchScope = rememberCoroutineScope()

    // 版本状态提到这一层：首页的启动按钮要显示当前版本，
    // 版本页要展示全部。两处共用一份，才能保证在一处改了选择另一处立刻知道。
    val versions = rememberVersionState()

    // 版本抽屉是否展开。
    var showVersionDrawer by remember { mutableStateOf(false) }

    // 启动前的账号预检结果。非 null 时弹提示框。
    var authFailure by remember { mutableStateOf<AuthPreflight.Result?>(null) }

    // 后台游戏会话的注册表。
    //
    // 「关闭」启动界面不会结束游戏 —— 会话留在 registry 里继续跑，
    // 用户可以随时从齿轮按钮的抽屉里点回来。只有「停止游戏」
    // 和进程自行退出才真正把它移出列表。
    val sessions = remember { SessionRegistry() }

    // 当前显示的会话。非 null 时启动界面盖住整个主界面。
    val launching = sessions.active

    // 齿轮抽屉是否展开。
    var showSessions by remember { mutableStateOf(false) }

    // 过渡层：火箭飞出窗口之后、启动页完全显示之前的黑场。
    //
    // 为什么单独一个状态：这段时间启动页必须**已经在下面渲染并开始工作**
    // （拉日志、跑协程），只是被遮着。如果等遮罩结束才创建 session，
    // 玩家会先看到一秒钟空白的白页，然后日志才开始滚。
    var transitioning by remember { mutableStateOf(false) }
    var transitionVersion by remember { mutableStateOf("") }

    // 真正开始启动。提到这一层是因为它有两个调用方：按钮的点击回调
    // 和提示框里的「仍然启动」—— 后者挂在最外层，拿不到内层局部变量。
    //
    // account 可空：网络失败时拿不到「刷新过的」账号，
    // 只能用存储里的旧账号（由 headlessmc 自己读取）。
    val proceed: (io.github.headlesshq.headlessmc.auth.Account?) -> Unit = { account ->
        val version = versions.selected
        when {
            account == null -> {
                // 不该发生：调用方都在拿到账号之后才走到这里。
                // 真发生了就说明流程接错了，不要静默吞掉。
                println("[HMWLX] 内部错误：启动时没有账号")
            }
            version == null -> {
                println("[HMWLX] 内部错误：启动时没有选中版本")
            }
            else -> {
                println("[HMWLX] 开始启动: 账号=${account.name}, 版本=${version.id}")
                transitionVersion = version.id

                // 动画播到「火箭飞出窗口顶部」时才切页 —— 这是关键。
                //
                // 以前是动画和切页同时开始，结果火箭还没起飞画面就换了，
                // 用户根本看不到火箭升空。现在由 play() 在正确的时刻回调。
                launchScope.launch {
                    launchAnim.play {
                        // 火箭已出画：立刻创建启动会话（让它在遮罩下面
                        // 开始干活），同时拉起黑场过渡。
                        val session = sessions.create(version.id, account)
                        sessions.active = session
                        transitioning = true
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        state = bootstrap()
    }

    // 定期刷新后台会话状态，让齿轮角标与实际在跑的游戏数量一致。
    //
    // 为什么需要轮询：进程退出是**异步**的，Compose 没法"订阅"一个
    // 外部进程的死亡。会话的 processAlive 是在读日志的协程里改的，
    // 而 StateFlow 只有在被 collect 时才触发重组 ——
    // 为每个会话常驻一个 collect 协程代码更绕。
    //
    // 1 秒一次足够：这是"角标数字早晚对一下"的活跃度，不是硬实时。
    // 空闲时（没有会话）循环不跑，零开销。
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            sessions.refresh()
        }
    }

    MaterialTheme {
        // 背景图铺满整窗，且**不压全屏遮罩**：
        // 没有 UI 元素的区域直接露出原图，图片才是背景而不是底色。
        // 可读性由右侧那块半透明面板独立解决。
        Box(modifier = Modifier.fillMaxSize()) {
            AppBackground()

            // 启动画面优先于一切：它盖住背景、侧栏、账号栏。
            // 判断放在账号切换之前，因为启动中用户不该切账号 ——
            // 那次启动用的就是启动时那个账号。
            val session = launching
            if (session != null) {
                val launchState by session.controller.state.collectAsState()
                LaunchingScreen(
                    state = launchState,
                    versionId = session.versionId,
                    // 返回主界面。
                    //
                    // **不结束游戏，只把它收到后台。**
                    // 用户点"返回"的意思是"我不想盯着日志了"，
                    // 不是"把游戏杀掉"。以前这里调 cancel()，
                    // 虽然对已启动的进程只是不杀，但会话对象被直接丢弃，
                    // 界面上再也找不回来 —— 现在它留在 sessions 里。
                    //
                    // 唯一要处理的是「启动还没走完」的情况：此时用户确实
                    // 不想继续启动了，那才需要中止（否则下载会在后台跑完
                    // 自己起一个游戏出来）。
                    onBack = {
                        val st = session.controller.state.value
                        if (!st.processAlive) {
                            // 还没起来：真的取消，并从列表移除。
                            session.controller.cancel()
                            sessions.sessions.remove(session)
                        }
                        // 转到后台，界面回到主界面。
                        sessions.active = null
                        transitioning = false
                    },
                    onStop = { session.controller.stop() },
                )

                // 过渡层盖在启动页**上面**。
                //
                // 位置很关键：必须放在 LaunchingScreen 之后（z 序更高），
                // 这样下面的启动页已经在正常渲染、跑协程、收日志，
                // 只是被黑场遮住。等遮罩淡出时，用户直接看到内容，
                // 而不是先看到一秒钟空页面。
                LaunchTransition(
                    visible = transitioning,
                    versionId = transitionVersion,
                    onFinished = { transitioning = false },
                )
            } else if (showAccountSwitch) {
                // 账号切换是整屏页面，需要自己的遮罩保证列表可读，
                // 所以盖在面板逻辑之上而不是塞进右侧。
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
                    val current = state
                    if (current is BootState.Ready) {
                        AccountSwitchScreen(
                            current = current.account,
                            onSwitched = { profile ->
                                // 切换成功后立刻回到主界面，并用新账号刷新状态。
                                // 不重新启动容器：账号只是 headlessmc 里的一个字段，
                                // 容器本身不需要重启。
                                state = current.copy(account = profile)
                                showAccountSwitch = false
                            },
                            onClose = { showAccountSwitch = false },
                        )
                    }
                }
            } else {
                // 账号栏在顶部通栏，属于全局状态。
                Column(modifier = Modifier.fillMaxSize()) {
                    val ready = state as? BootState.Ready
                    if (ready != null) {
                        AccountBar(
                            profile = ready.account,
                            onOpenSwitch = { showAccountSwitch = true },
                        )
                    }

                    Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        // 主区域：功能页的内容放这里，占满除侧栏外的全部空间。
                        // 内边距加在这一层而不是 Row 上：Row 加了 padding 会把侧栏
                        // 一起往里推，侧栏就接不到窗口右边界了。
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .padding(16.dp),
                            // 加载与失败居中显示。这两个状态没有内容页可言，
                            // 居中是唯一合理的排布；首页/版本页各自管自己的布局，
                            // 所以居中约束只加在下面两个分支上，不影响它们。
                        ) {
                            when (val current = state) {
                                // 容器还在起：主区域必须给出反馈。只在侧栏里转个
                                // 20dp 的圈，用户会以为界面挂了。
                                is BootState.Loading -> Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    LoadingScreen()
                                }

                                // 失败原因必须占满主区域：报错文案可能很长
                                // （类找不到、依赖冲突的整条异常链），
                                // 挤在 168dp 的侧栏里根本读不了。
                                is BootState.Failed -> Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    FailedScreen(current)
                                }

                                // 就绪后主区域交给当前页。首页时这里是空的，
                                // 背景图直接露出来 —— 那正是首页该有的样子。
                                is BootState.Ready -> when (panel) {
                                    // 首页：只放一个左下角的启动按钮。
                                    // 不加背景板：背景图是这张界面的主体，
                                    // 按钮靠自身的大尺寸和阴影从图上立起来就够了。
                                    MainPanel.HOME -> Box(
                                        modifier = Modifier.fillMaxSize(),
                                        contentAlignment = Alignment.BottomStart,
                                    ) {
                                        // 启动按钮 + 后台游戏按钮排成一行。
                                        //
                                        // 后台按钮放在启动按钮的**右侧**，
                                        // 仍在主区域内（不是侧栏里）——
                                        // 它管的是"当前在跑的游戏"，属于内容区的事。
                                        Row(
                                            verticalAlignment = Alignment.Bottom,
                                            horizontalArrangement = Arrangement.spacedBy(440.dp),
                                        ) {
                                            LaunchButtonWithVersion(
                                                state = launchAnim,
                                                currentVersion = versions.selected,
                                                onClick = {
                                                    // 启动前的账号检查。AuthPreflight 内部按
                                                    // 「微软 → 皮肤站 → 离线」的顺序走，
                                                    // 微软必须最先检查（LEGAL/用户协议 承诺了
                                                    // 正版账号走微软官方鉴权）。
                                                    //
                                                    // 这里只负责把结果分流到 UI：
                                                    // - blocksLaunch：账号有问题，拦住并说明
                                                    // - shouldWarn：可以启动，但要让用户知道
                                                    // - 其它：静默放行
                                                    launchScope.launch {
                                                        val check = AuthPreflight.check()
                                                        when {
                                                            check is AuthPreflight.Result.Ready ->
                                                                proceed(check.account)

                                                            check.blocksLaunch -> {
                                                                // 凭据失效 / 无账号 / 认证源不可用。
                                                                authFailure = check
                                                            }

                                                            check.shouldWarn -> {
                                                                // 网络类问题（微软或皮肤站），
                                                                // 以及皮肤站会话失效。
                                                                // 都不拦启动，只提示。
                                                                authFailure = check
                                                            }

                                                            else -> {
                                                                println(
                                                                    "[HMWLX] 账号检查无影响，" +
                                                                        "直接启动: ${check.javaClass.simpleName}"
                                                                )
                                                                proceed(null)
                                                            }
                                                        }
                                                    }
                                                },
                                                onOpenVersions = { showVersionDrawer = true },
                                            )

                                            // 圆形齿轮：后台游戏入口。
                                            //
                                            // 抽屉作为 overlay 传进去（而不是放外面），
                                            // 这样 Popup 的锚点就是这个按钮本身。
                                            // 放外面的话锚点会变成整行，抽屉会飞到别处。
                                            BackgroundGamesButton(
                                                count = sessions.aliveCount,
                                                onClick = {
                                                    sessions.refresh()
                                                    showSessions = !showSessions
                                                },
                                                // 底部留 8dp：启动按钮高 72dp、齿轮 56dp，
                                                // 不做偏移的话齿轮会贴到更下面去。
                                                modifier = Modifier.padding(bottom = 8.dp),
                                                overlay = {
                                                    if (showSessions) {
                                                        BackgroundGamesDrawer(
                                                            entries = sessions.sessions.map { s ->
                                                                val st = s.controller.state.value
                                                                BackgroundGameEntry(
                                                                    id = s.id,
                                                                    versionId = s.versionId,
                                                                    pid = st.pid,
                                                                    running = st.processAlive,
                                                                )
                                                            },
                                                            onEnter = { entry ->
                                                                sessions.sessions
                                                                    .firstOrNull { it.id == entry.id }
                                                                    ?.let { sessions.active = it }
                                                                showSessions = false
                                                            },
                                                            onStop = { entry ->
                                                                sessions.sessions
                                                                    .firstOrNull { it.id == entry.id }
                                                                    ?.let { it.controller.stop() }
                                                                showSessions = false
                                                            },
                                                            onDismiss = { showSessions = false },
                                                        )
                                                    }
                                                },
                                            )
                                        }

                                        // 版本抽屉：贴在按钮正上方弹出。
                                        // 只在打开时进组合树，关掉就整个卸掉，
                                        // 免得一个看不见的 Popup 还在监听点击。
                                        if (showVersionDrawer) {
                                            VersionDrawer(
                                                versions = versions.versions,
                                                selected = versions.selected,
                                                onSelect = { version ->
                                                    versions.select(version)
                                                    showVersionDrawer = false
                                                },
                                                onDismiss = { showVersionDrawer = false },
                                            )
                                        }
                                    }

                                    MainPanel.VERSIONS -> VersionListScreen(
                                        state = versions,
                                        onLaunch = { version ->
                                            println(
                                                "[HMWLX] 请求启动版本 ${version.id} " +
                                                    "(类型=${version.type}, 父版本=${version.inheritsFrom})"
                                            )
                                        },
                                    )
                                }
                            }
                        }

                        // 侧栏：只放导航入口。宽度固定且窄，不随窗口拉伸 ——
                        // 它是菜单，不是 GUI 区域。撑宽了会把主区域挤没。
                        SideMenu(
                            state = state,
                            panel = panel,
                            onSelectPanel = { panel = it },
                        )
                    }
                }
            }

            // 启动动画的覆盖层：必须在最外层 Box 的最后，
            // 这样火箭能飞出主区域内边距、飞出侧栏，一路升到窗口顶边之外。
            // 放在内层任何一个节点里都会被祖先的布局边界裁掉。
            LaunchOverlay(state = launchAnim)

            // 账号预检失败的提示。同样挂在最外层，理由和火箭一样：
            // 对话框要压在包括侧栏和账号栏在内的所有内容之上。
            AuthFailureDialog(
                result = authFailure,
                onLaunchAnyway = {
                    // 用户选择继续启动。两种来源：
                    // - NetworkFailure / RateLimited：token 没刷新，但大概率还有效
                    // - SessionExpired：上游已把 token 清空，会按离线启动
                    // 两种都用上游给出的账号（SessionExpired 里就是那个空 token 的）。
                    val current = authFailure
                    authFailure = null
                    val account = when (current) {
                        is AuthPreflight.Result.NetworkFailure -> current.account
                        is AuthPreflight.Result.RateLimited -> current.account
                        is AuthPreflight.Result.SessionExpired -> current.account
                        else -> null
                    }
                    println("[HMWLX] 账号检查未通过，用户选择继续启动: ${current?.javaClass?.simpleName}")
                    proceed(account)
                },
                onRelogin = {
                    authFailure = null
                    showAccountSwitch = true
                },
                onDismiss = { authFailure = null },
            )
        }
    }
}

/** 右侧面板当前显示的页。 */
/**
 * 一次启动会话。
 *
 * <p>把 [LaunchController] 和它需要的独立协程作用域绑在一起：
 *
 * - 作用域在会话创建时新建，会话结束时取消。
 *   不能复用 `rememberCoroutineScope()` —— 那个作用域跟着主界面走，
 *   用户切走启动画面时不该把它取消（游戏还在跑），
 *   但日志读取确实该停。
 *
 * - 用 `remember` 而不是裸构造：Compose 重组时不能每帧新建一个
 *   controller，否则状态会丢、日志会重来。
 */
private class LaunchSession(
    /** 会话的稳定标识。自增而不是用 hashCode —— 对象被回收后
     *  hashCode 可能被复用，导致抽屉里点错条目。 */
    val id: Long,
    val versionId: String,
    account: io.github.headlesshq.headlessmc.auth.Account,
) {
    /** 会话自己的作用域，主界面那一层不共享。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val controller = LaunchController(scope)

    init {
        controller.launch(versionId, account)
    }
}

/**
 * 后台游戏会话的集合。
 *
 * <h2>为什么要单独一个容器</h2>
 *
 * 「关闭」启动界面时**不能结束游戏**。用户点关闭的意图是"我不想一直盯着
 * 日志了"，不是"把游戏杀掉"。以前 onBack 直接 `launching = null`，
 * 会话对象被丢弃 —— 界面上再也找不回来那个进程了，虽然游戏还在跑。
 *
 * 所以会话必须活在一个比「当前显示哪一页」更长寿的地方：这里。
 *
 * <h2>为什么用 Compose 的 mutableStateListOf</h2>
 *
 * 后台进程列表要参与重组（齿轮按钮上的角标、抽屉里的条目）。
 * 用普通 list 的话增删不会触发重组，界面就不更新了。
 */
private class SessionRegistry {
    /** 所有还活着的会话。顺序即创建顺序，抽屉里按这个顺序展示。 */
    val sessions = mutableStateListOf<LaunchSession>()

    /** 当前正在显示的会话。null 表示回到了主界面。 */
    var active by mutableStateOf<LaunchSession?>(null)

    /** 下一个会话 ID。只增不减，保证唯一。 */
    private var nextId = 1L

    /** 创建一个新会话并入表。 */
    fun create(
        versionId: String,
        account: io.github.headlesshq.headlessmc.auth.Account,
    ): LaunchSession {
        val session = LaunchSession(nextId++, versionId, account)
        sessions.add(session)
        return session
    }

    /**
     * 移出已经结束的会话。
     *
     * <p>判据交给 [BackgroundSessionPolicy]：那里把"下载中"/"运行中"/
     * "已退出"三种情形讲清楚了，并有测试钉住。这里只负责调用。
     *
     * <p>最容易踩的坑：用 `processAlive` 单条件判会把**还在下载**的会话
     * 也移掉（那时进程还不存在），用 `finished` 单条件判会把
     * **正在跑的游戏**移掉（窗口加载后就 finished 了）。
     */
    fun prune() {
        sessions.removeAll { s ->
            val st = s.controller.state.value
            !BackgroundSessionPolicy.shouldKeep(
                finished = st.finished,
                processAlive = st.processAlive,
                cancelled = st.cancelled,
            )
        }
    }

    /** 真正在跑的游戏数量。Compose 状态，供角标读取。 */
    var aliveCount by mutableStateOf(0)
        private set

    /**
     * 刷新派生状态。由界面的轮询循环每秒调一次。
     *
     * <p>为什么是轮询而不是订阅：进程死亡发生在外部进程里，
     * 我们只能通过会话的 StateFlow 感知，而 StateFlow 只有在被
     * collect 时才触发重组。为每个会话常驻一个 collect 协程代码更绕，
     * 一秒轮询一次的成本可以忽略。
     */
    fun refresh() {
        prune()
        val count = sessions.count { it.controller.state.value.processAlive }
        if (count != aliveCount) aliveCount = count
    }
}

/** 右侧面板当前显示的页。 */
private enum class MainPanel {
    /** 首页：只有入口按钮。 */
    HOME,

    /** 游戏版本列表。 */
    VERSIONS,
}

// ---------------------------------------------------------------------------
// 侧栏外观。集中放这里，调观感时只改这两行，不用翻到函数体里找魔法数。
// ---------------------------------------------------------------------------

/** 侧栏宽度。固定值：菜单宽度不该跟着窗口走。 */
private val SIDEBAR_WIDTH = 168.dp

/**
 * 侧栏白底不透明度。
 *
 * <p>AI理解反了,我要的是尽量能保证看清字符且尽量凸显背景,不是一团白糊上去。
 * 所以调整val变量到0.40f刚好满足这个要求。
 */
private const val SIDEBAR_ALPHA = 0.40f

/** 侧栏左侧分隔线的颜色，也是唯一一条边框。 */
private val SIDEBAR_DIVIDER = Color.Black.copy(alpha = 0.18f)

/**
 * 右侧导航侧栏：贴边铺满整列。
 *
 * <p>铺满高度而不是只包住内容：它是一条**贴边栏**，上下顶到账号栏与窗口底部，
 * 右边与窗口边界齐平。只包内容时四周留白，看起来像个飘着的小卡片，
 * 跟「侧边栏」这个定位不符。
 *
 * <p>宽度固定 [SIDEBAR_WIDTH]，不随窗口拉伸 —— 菜单里的词就那么几个，
 * 宽度跟着窗口走的话，大窗口下会把主区域挤得只剩一条缝。
 *
 * <p>只在左侧画一条 1dp 分隔线，不画整圈边框：另外三边本来就贴着窗口边，
 * 再描一圈会显得像浮在窗口中间。
 */
@Composable
private fun SideMenu(
    state: BootState,
    panel: MainPanel,
    onSelectPanel: (MainPanel) -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(SIDEBAR_WIDTH)
            .fillMaxHeight(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = SIDEBAR_ALPHA),
        tonalElevation = 0.dp,
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            VerticalDivider(
                modifier = Modifier.fillMaxHeight(),
                thickness = 1.dp,
                color = SIDEBAR_DIVIDER,
            )
            Column(modifier = Modifier.fillMaxHeight().padding(8.dp)) {
                when (state) {
                    is BootState.Loading -> Box(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    }

                    is BootState.Failed -> Text(
                        text = state.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(8.dp),
                    )

                    is BootState.Ready -> {
                        MenuEntry(
                            label = text(TextKey.PANEL_VERSIONS),
                            icon = Icons.AutoMirrored.Outlined.ViewList,
                            selected = panel == MainPanel.VERSIONS,
                            // 再点一次已选中的入口就回首页。侧栏没有"取消选择"这种状态，
                            // 用再点一次回来最直观。
                            onClick = {
                                onSelectPanel(
                                    if (panel == MainPanel.VERSIONS) MainPanel.HOME
                                    else MainPanel.VERSIONS
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

/** 侧栏里的一个入口。选中时用浅灰底标出来，不引入主题色。 */
@Composable
private fun MenuEntry(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (selected) Color.Black.copy(alpha = 0.08f) else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Color.Black.copy(alpha = if (selected) 0.9f else 0.7f),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color.Black.copy(alpha = if (selected) 1f else 0.8f),
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * 在后台线程启动 headlessmc 容器，并尝试取一个 bean 验证可用性。
 *
 * 失败时除了放进 [BootState.Failed] 供界面显示，还打印到控制台 ——
 * 否则窗口一闪而过时，用户完全看不到原因。
 */
private suspend fun bootstrap(): BootState = withContext(Dispatchers.IO) {
    try {
        println("[HMWLX] 正在启动 headlessmc 容器…")
        HeadlessMcBootstrap.start()
        println("[HMWLX] 容器已就绪，状态=${HeadlessMcBootstrap.currentState()}")

        val account = AccountResolver.current()
        println("[HMWLX] 当前账号: ${account.name} (${account.sourceLabel()}) uuid=${account.uuid}")
        println("[HMWLX] 头像来源: ${account.avatar ?: "<无>"}")

        BootState.Ready(account = account)
    } catch (t: Throwable) {
        println("[HMWLX] 容器启动失败: ${t.message}")
        t.printStackTrace()
        BootState.Failed(t.message ?: t.javaClass.simpleName, t)
    }
}

@Composable
private fun LoadingScreen() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(modifier = Modifier.size(48.dp))
        Spacer(Modifier.height(16.dp))
        Text(text(TextKey.BOOT_LOADING), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun FailedScreen(failed: BootState.Failed) {
    Column(
        modifier = Modifier.padding(32.dp).fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = text(TextKey.BOOT_FAILED_TITLE),
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        Spacer(Modifier.height(12.dp))
        Text(text = failed.message, style = MaterialTheme.typography.bodyMedium)
    }
}
