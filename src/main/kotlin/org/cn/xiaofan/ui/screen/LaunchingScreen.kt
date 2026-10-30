package org.cn.xiaofan.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import org.cn.xiaofan.launch.LaunchProgress
import org.cn.xiaofan.launch.LaunchStage
import org.cn.xiaofan.launch.LaunchState

/**
 * 启动中／启动结果的独立界面。
 *
 * <p>布局按需求：**上半部分是阶段进度，下半部分是游戏实时日志**。
 * 白底，与主界面（背景图 + 半透明侧栏）明确区分 —— 这是个"进行中的任务"，
 * 不是主界面的一部分。
 *
 * <p>为什么用整屏而不是弹窗：日志可能有几百行，弹窗放不下也没法滚动阅读；
 * 而且启动是个持续几十秒的过程，用户可能想切出去干别的，整屏更从容。
 *
 * <p>日志区**可以选中复制**（[SelectionContainer]）：用户遇到问题时
 * 需要把日志贴给别人看。脱敏已经在 [org.cn.xiaofan.launch.LogSanitizer]
 * 做过了，复制出去的内容不含令牌。
 *
 * @param state 启动状态，由 [org.cn.xiaofan.launch.LaunchController] 提供
 * @param versionId 正在启动的版本，显示在标题里
 * @param onBack 返回主界面。启动中返回会**取消日志读取但不杀进程**
 *   （游戏已经在跑了，不该因为切了个画面就把人踢出来）
 * @param onStop 停止游戏进程
 */
@Composable
fun LaunchingScreen(
    state: LaunchState,
    versionId: String,
    onBack: () -> Unit,
    onStop: () -> Unit,
) {
    // 白底：与主界面的背景图明确区分。
    // 用 surface 而不是纯白，让它在深色系统主题下也不刺眼 ——
    // 但这里明确要求白底，所以固定用 Color.White 系。
    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFFAFAFA)) {
        Column(modifier = Modifier.fillMaxSize()) {
            LaunchHeader(
                state = state,
                versionId = versionId,
                onBack = onBack,
                onStop = onStop,
            )
            HorizontalDivider(color = Color.Black.copy(alpha = 0.08f))

            // ---- 上半：阶段进度 ----
            StagePanel(state = state)
            HorizontalDivider(color = Color.Black.copy(alpha = 0.08f))

            // ---- 下半：日志 ----
            LogPanel(state = state, modifier = Modifier.weight(1f))
        }
    }
}

/** 顶部：标题 + 版本 + 操作按钮。 */
@Composable
private fun LaunchHeader(
    state: LaunchState,
    versionId: String,
    onBack: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (state.finished) {
                    if (state.succeeded) text(TextKey.LAUNCH_TITLE_DONE)
                    else text(TextKey.LAUNCH_TITLE_FAILED)
                } else {
                    text(TextKey.LAUNCH_TITLE_RUNNING)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1A1A1A),
            )
            Text(
                text = versionId,
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black.copy(alpha = 0.5f),
            )
        }

        // 进程还活着就给「停止游戏」——不管启动流程有没有结束。
        // 启动成功后进程仍在跑，此时用户最需要的恰恰是这个按钮。
        // 注意判断的是 processAlive 而不是 !finished：
        // 窗口加载后 finished 就是 true 了，但游戏还在运行。
        if (state.processAlive) {
            OutlinedButton(onClick = onStop) {
                Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(text(TextKey.LAUNCH_STOP))
            }
            Spacer(Modifier.width(8.dp))
        }

        OutlinedButton(onClick = onBack) {
            Text(text = if (state.finished) text(TextKey.LAUNCH_CLOSE) else text(TextKey.LAUNCH_BACK))
        }
    }
}

/**
 * 阶段进度。
 *
 * <p>六个阶段全部列出来（而不是只显示当前一个），让用户知道还有几步、
 * 走到哪儿了。已完成打勾，当前转圈，未开始显示空心圆。
 *
 * <p>当某个阶段被跳过时（靠日志推断的阶段 5/6 可能不触发），
 * 界面上它会一直显示"未开始"，直到整个流程结束 —— 这是可以接受的降级：
 * 用户看到"启动成功"就说明成事了，不必纠结中间某步有没有打勾。
 */
@Composable
private fun StagePanel(state: LaunchState) {
    val stages = LaunchStage.entries
    val current = state.stage

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        stages.forEachIndexed { index, stage ->
            // 阶段完成的判定只看 stage 自己走到哪了，不看 finished。
            //
            // 以前这里把 finished 也算进去，结果窗口一加载（finished 变 true）
            // 六个阶段全部同时变成完成态 —— 包括根本没执行到的那些，
            // 看起来像"全都做完了"，掩盖了某步其实跳过去的事实。
            //
            // 最后一个阶段（窗口就绪）比较特殊：它自身达成即为完成，
            // 不存在"走到它之后的下一个阶段"。
            val isLast = index == stages.lastIndex
            val done = index < current.ordinal ||
                (isLast && current == stage && state.succeeded)
            val active = index == current.ordinal && !state.finished

            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    done -> Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color(0xFF2E7D32),
                    )
                    active -> CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF1976D2),
                    )
                    else -> Icon(
                        Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = Color.Black.copy(alpha = 0.22f),
                    )
                }
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stage.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        done -> Color(0xFF2E7D32)
                        active -> Color(0xFF1A1A1A)
                        else -> Color.Black.copy(alpha = 0.42f)
                    },
                )
            }

            // 正在这个阶段里有可量化的子任务（下载资源/库）时，
            // 就地把进度画在该阶段下面 —— 归属关系一目了然，
            // 不用让用户在两个地方之间找对应。
            if (active && state.progress != null) {
                StageProgressRow(state.progress)
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = state.error,
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFC62828),
            )
        }
    }
}

/**
 * 阶段内的子任务进度。
 *
 * <p>这是**真实进度**，不是动画：数据来自 headlessmc 的 `AssetDownloader`
 * 每下载一块就上报的 `stepBy()`。
 *
 * <p>为什么必须显示它：首次启动要下载几百 MB 资源，实测十几分钟。
 * 只给一句"正在处理游戏依赖"的静态文字，用户完全无法判断是卡死了还是在干活。
 * 有了百分比和速度感（哪怕只是数字在跳），等待的心理成本差一个数量级。
 *
 * @param progress 进度快照，非空（调用方已判空）
 */
@Composable
private fun StageProgressRow(progress: LaunchProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 28.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = progress.description,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = Color.Black.copy(alpha = 0.6f),
                modifier = Modifier.weight(1f),
            )
            // 总量未知时不显示百分比 —— 显示一个假的 0% 比不显示更糟。
            progress.fraction?.let { f ->
                Text(
                    text = "${(f * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF1976D2),
                )
            }
        }

        val fraction = progress.fraction
        if (fraction != null) {
            LinearProgressIndicator(
                progress = { fraction.toFloat() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = Color(0xFF1976D2),
                trackColor = Color.Black.copy(alpha = 0.08f),
            )
        } else {
            // 总量未知：用不确定式进度条，至少让它动起来。
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = Color(0xFF1976D2),
                trackColor = Color.Black.copy(alpha = 0.08f),
            )
        }
    }
}

/**
 * 日志面板。
 *
 * <p>等宽字体 + 可选中：日志是给排查用的，等宽让异常堆栈对齐，
 * 可选中让用户能复制出来求助。
 *
 * <p>自动滚到底部，但**只在用户本来就在底部时**滚动 ——
 * 如果用户往上翻着看历史，新日志不该把他拽回去。
 */
@Composable
private fun LogPanel(state: LaunchState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    // 自动滚到底：只在已经贴近底部时做，避免打断用户翻阅。
    LaunchedEffect(state.logs.size) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val nearBottom = lastVisible >= state.logs.size - 3
        if (nearBottom && state.logs.isNotEmpty()) {
            listState.scrollToItem(state.logs.size - 1)
        }
    }

    Box(modifier = modifier.fillMaxWidth()) {
        if (state.logs.isEmpty()) {
            Text(
                text = text(TextKey.LAUNCH_LOG_EMPTY),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Black.copy(alpha = 0.42f),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color(0xFFF5F5F5))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    items(state.logs) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            // 日志行不换行更整齐，但异常堆栈会超长。
                            // 这里用默认换行，宁可多占几行也别看不全。
                            color = Color(0xFF212121),
                        )
                    }
                }
            }
        }
    }
}
