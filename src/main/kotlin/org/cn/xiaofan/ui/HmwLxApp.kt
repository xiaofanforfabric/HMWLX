package org.cn.xiaofan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.getValue
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cn.xiaofan.account.AccountProfile
import org.cn.xiaofan.account.AccountResolver
import org.cn.xiaofan.account.sourceLabel
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import org.cn.xiaofan.ui.component.AccountBar
import org.cn.xiaofan.ui.component.AppBackground
import org.cn.xiaofan.ui.component.LaunchButton
import org.cn.xiaofan.ui.component.LaunchOverlay
import org.cn.xiaofan.ui.component.rememberLaunchAnimationState
import org.cn.xiaofan.ui.screen.AccountSwitchScreen
import org.cn.xiaofan.ui.screen.VersionListScreen

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

    LaunchedEffect(Unit) {
        state = bootstrap()
    }

    MaterialTheme {
        // 背景图铺满整窗，且**不压全屏遮罩**：
        // 没有 UI 元素的区域直接露出原图，图片才是背景而不是底色。
        // 可读性由右侧那块半透明面板独立解决。
        Box(modifier = Modifier.fillMaxSize()) {
            AppBackground()

            if (showAccountSwitch) {
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
                                        LaunchButton(
                                            state = launchAnim,
                                            onClick = {
                                                // TODO(human): 启动链路。
                                                // 现在只打日志，确认点击通路是通的。
                                                // 需要的信息都在手边：
                                                //   - 当前账号: AccountService.currentAccount()
                                                //   - 选中版本: 由侧栏子菜单维护（selectedVersion）
                                                // 分三步实现，各自独立：
                                                //   1. 用 headlessmc 的版本服务把 <id>.json 和它的
                                                //      inheritsFrom 链展开成完整启动参数
                                                //   2. 拼 JVM 参数（-Xmx、natives 目录、--assetIndex）
                                                //      并生成 --uuid / --accessToken / --userType
                                                //   3. 起子进程并把 stdout 接到界面上的日志面板
                                                println("[HMWLX] 启动按钮被点击（启动链路尚未实现）")
                                                launchScope.launch { launchAnim.play() }
                                            },
                                        )
                                    }

                                    MainPanel.VERSIONS -> VersionListScreen(onLaunch = { version ->
                                        println(
                                            "[HMWLX] 请求启动版本 ${version.id} " +
                                                "(类型=${version.type}, 父版本=${version.inheritsFrom})"
                                        )
                                    })
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
        }
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
