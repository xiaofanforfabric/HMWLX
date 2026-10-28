package org.cn.xiaofan.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cn.xiaofan.account.AccountProfile
import org.cn.xiaofan.account.AccountResolver
import org.cn.xiaofan.account.sourceLabel
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import org.cn.xiaofan.ui.component.AccountBar
import org.cn.xiaofan.ui.component.AppBackground
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
 * 布局：顶部固定账号栏，下方是内容区（版本列表）。
 * 账号栏在容器就绪后才显示 —— 因为账号信息要通过 CDI 从 headlessmc 读取。
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

    LaunchedEffect(Unit) {
        state = bootstrap()
    }

    MaterialTheme {
        // 背景图铺满整窗，且**不压全屏遮罩**：
        // 没有 UI 元素的区域直接露出原图，图片才是背景而不是底色。
        // 可读性由各块的局部面板解决（AccountBar、内容卡片各自带底色），
        // 全屏压一层浅色会让整张图糊成一片，等于白放。
        Box(modifier = Modifier.fillMaxSize()) {
            AppBackground()
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.Transparent,
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                when (val current = state) {
                    is BootState.Loading -> ContentArea {
                        LoadingScreen()
                    }
                    is BootState.Ready -> {
                        if (showAccountSwitch) {
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
                        } else {
                            AccountBar(
                                profile = current.account,
                                onOpenSwitch = { showAccountSwitch = true },
                            )
                            // weight(1f) 让内容区吃掉账号栏之外的**剩余**高度。
                            // 这里不能用 fillMaxSize()：Column 内的 fillMaxSize 会按父约束的
                            // 完整高度测量，总高变成 账号栏 + 整窗高，超出部分被裁掉，
                            // 内容会偏到看不见的位置。
                            //
                            // 版本列表不走 [ContentArea]：它自己是左右两栏的布局，
                            // 塞进居中的 Box 里只会得到一个按内容测量、尺寸不定的中间块。
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                            ) {
                                VersionListScreen(
                                    onLaunch = { version ->
                                        // TODO(human): 启动链路。
                                        // 现在只把选中版本和当前账号落进日志，确认参数传对了；
                                        // 真正的实现分三步，各自独立：
                                        //   1. 用 headlessmc 的版本服务把 <id>.json 和它的
                                        //      inheritsFrom 链展开成完整启动参数
                                        //   2. 拼 JVM 参数（-Xmx、natives 目录、--assetIndex）
                                        //      并用当前账号（AccountService.currentAccount）生成
                                        //      --uuid / --accessToken / --userType
                                        //   3. 起子进程并把 stdout 接到界面上的日志面板
                                        // 三步都能单独测：1 和 2 是纯函数，只有 3 需要真起进程。
                                        println(
                                            "[HMWLX] 请求启动版本 ${version.id} " +
                                                "(类型=${version.type}, 父版本=${version.inheritsFrom})"
                                        )
                                    },
                                )
                            }
                        }
                    }
                    is BootState.Failed -> ContentArea {
                        FailedScreen(current)
                    }
                }
                }
            }
        }
    }
}

/**
 * 内容区：占满 [Column] 里的剩余高度，内容居中。
 *
 * 抽出来是因为三个分支都要同一份约束逻辑，避免复制粘贴时再次写错成 `fillMaxSize()`。
 *
 * <p>这里**不加底板**：内容直接浮在背景图上，图片才是主体。
 * 代价是文字必须自带可读性措施（浅色 + 阴影），见 [VersionListScreen]。
 */
@Composable
private fun ColumnScope.ContentArea(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .weight(1f)
            .fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        content()
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
