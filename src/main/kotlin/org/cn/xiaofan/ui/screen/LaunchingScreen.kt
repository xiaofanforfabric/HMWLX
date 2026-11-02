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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
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
import org.cn.xiaofan.ui.component.AppBackground

/**
 * 启动中／启动结果的界面。
 *
 * ## 布局
 *
 * ```
 * ┌──────────────────────────────────────────┐
 * │  正在启动游戏                     v1.21.4 │  ← 标题
 * ├──────────────────────────────────────────┤
 * │  ✓ 正在处理游戏依赖                       │
 * │  ✓ 正在处理账号                           │  ← 粗略进度（上）
 * │  ◐ 正在拉起游戏进程                       │
 * │     468/485mb  96%  ████████████████░     │
 * │  ○ 游戏进程已拉起正在启动                 │
 * │  ○ 游戏进程已成功设置用户名               │
 * │  ○ 游戏窗口已加载！启动成功               │
 * ├──────────────────────────────────────────┤
 * │  21:52:57 Setting user: xxx               │
 * │  21:53:09 Created window using SDL ...    │  ← 详细日志（下）
 * │  21:53:34 Sound engine started            │
 * │                                           │
 * │                    [停止游戏] [返回]      │  ← 按钮在右下角
 * └──────────────────────────────────────────┘
 * ```
 *
 * ## 为什么改成半透明
 *
 * 以前是一整块不透明白板，和主界面（背景图 + 半透明卡片）完全割裂 ——
 * 切过去像是打开了另一个程序。
 *
 * 现在沿用登录页那套：同一张 `AppBackground`，上面压一层
 * [SCRIM_ALPHA] 的浅色遮罩。既保住背景的连贯性，又把底图的噪点压下去
 * 保证文字可读。遮罩比登录页更浓一点（0.28 → 0.62），因为这里是
 * **日志**，等宽小字压在花纹背景上会看不清；登录页那些字大得多。
 *
 * ## 为什么日志区还是实底
 *
 * 日志底板用更实的一层（[LOG_PANEL_ALPHA]）。日志是密集的等宽小字，
 * 只靠遮罩不够 —— 底图的花纹会从字缝里透出来，读几行眼睛就累了。
 * 进度区文字少、留白多，可以直接浮在遮罩上。
 *
 * @param state 启动状态，由 [org.cn.xiaofan.launch.LaunchController] 提供
 * @param versionId 正在启动的版本
 * @param onBack 回到主界面。**不结束游戏**，会话收到后台，
 *   可从齿轮按钮的抽屉里点回来
 * @param onStop 停止游戏进程（真的结束）
 */
@Composable
fun LaunchingScreen(
    state: LaunchState,
    versionId: String,
    onBack: () -> Unit,
    onStop: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 与主界面、登录页同一张背景，保证切换时视觉连贯。
        //
        // 说明：主界面最外层已经画过一次（`HmwLxApp` 的
        // `Box { AppBackground(); ... }`），本界面是它的子节点，
        // 所以这里再画一次其实是重复的 —— 多一次解码。
        //
        // 之所以仍然这么写：与 `AccountSwitchScreen` 保持一致，
        // 两个界面切换时视觉完全一样。重复解码的问题在那边也存在，
        // 应该统一在 `AppBackground` 内部做缓存来解决，
        // 而不是让每个调用方自己判断"上层画没画过" —— 那样太容易出错。
        AppBackground()

        // 浅色遮罩：压住背景噪点，同时保留"这是同一张图"的连续性。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = SCRIM_ALPHA)),
        )

        Column(modifier = Modifier.fillMaxSize()) {
            LaunchHeader(state = state, versionId = versionId)

            HorizontalDivider(color = Color.Black.copy(alpha = 0.06f))

            // 上半：粗略进度。按内容高度自然撑开，不占权重 ——
            // 六个阶段是固定高度，给它 weight 只会在屏幕高时留大片空白。
            StagePanel(state = state)

            HorizontalDivider(color = Color.Black.copy(alpha = 0.06f))

            // 下半：详细日志，吃掉剩余全部高度。
            LogPanel(
                state = state,
                modifier = Modifier.weight(1f),
            )

            HorizontalDivider(color = Color.Black.copy(alpha = 0.06f))

            // 底部操作条：两个按钮都在右下角。
            ActionBar(state = state, onBack = onBack, onStop = onStop)
        }
    }
}

/** 顶部标题条。 */
@Composable
private fun LaunchHeader(state: LaunchState, versionId: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                state.succeeded -> text(TextKey.LAUNCH_TITLE_DONE)
                state.finished -> text(TextKey.LAUNCH_TITLE_FAILED)
                else -> text(TextKey.LAUNCH_TITLE_RUNNING)
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )

        // 版本名做成小标签，和标题拉开层级 ——
        // 它是上下文信息，不该和标题抢注意力。
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = Color.Black.copy(alpha = 0.06f),
        ) {
            Text(
                text = versionId,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            )
        }
    }
}

/**
 * 粗略进度：六个阶段 + 当前子任务进度条。
 *
 * 阶段全部列出来（而不是只显示当前一个），让用户知道还有几步、走到哪儿了。
 * 已完成打勾，当前转圈，未开始显示空心圆。
 */
@Composable
private fun StagePanel(state: LaunchState) {
    val stages = LaunchStage.entries
    val current = state.stage

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        stages.forEachIndexed { index, stage ->
            // 完成判定只看 stage 自己走到哪，不看 finished ——
            // 用 finished 会让六个阶段在窗口加载后同时变成完成态，
            // 包括根本没执行到的那些。
            //
            // 最后一个阶段（窗口就绪）特殊：它自身达成即完成，
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
                        modifier = Modifier.size(17.dp),
                        tint = Color(0xFF2E7D32),
                    )

                    active -> CircularProgressIndicator(
                        modifier = Modifier.size(17.dp),
                        strokeWidth = 2.dp,
                        color = Color(0xFF1976D2),
                    )

                    else -> Icon(
                        Icons.Filled.RadioButtonUnchecked,
                        contentDescription = null,
                        modifier = Modifier.size(17.dp),
                        tint = Color.Black.copy(alpha = 0.20f),
                    )
                }

                Spacer(Modifier.width(10.dp))

                Text(
                    text = stage.label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        done -> Color(0xFF2E7D32)
                        active -> MaterialTheme.colorScheme.onSurface
                        else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    },
                )
            }

            // 有可量化的子任务（下载资源/库）时，把进度画在该阶段下面 ——
            // 归属关系一目了然，不用让用户在两个地方之间找对应。
            if (active && state.progress != null) {
                StageProgressRow(state.progress)
            }
        }

        if (state.error != null) {
            Spacer(Modifier.height(2.dp))
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
 * 这是**真实进度**，不是动画：数据来自 headlessmc 的 `AssetDownloader`
 * 每下载一块就上报的 `stepBy()`。
 *
 * 首次启动要下载几百 MB 资源，实测十几分钟。只给一句静态文字的话，
 * 用户完全无法判断是卡死了还是在干活。
 */
@Composable
private fun StageProgressRow(progress: LaunchProgress) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            // 左缩进对齐阶段文字（图标 17 + 间距 10 = 27），
            // 让进度看起来是那个阶段的子项而不是同级项。
            .padding(start = 27.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = progress.description,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
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
 * 详细日志面板。
 *
 * 等宽字体 + 可选中：日志是给排查用的，等宽让异常堆栈对齐，
 * 可选中让用户能复制出来求助（脱敏已在 LogSanitizer 做过）。
 *
 * 自动滚到底部，但**只在用户本来就在底部时**滚动 ——
 * 如果用户往上翻着看历史，新日志不该把他拽回去。
 */
@Composable
private fun LogPanel(state: LaunchState, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.logs.size) {
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        val nearBottom = lastVisible >= state.logs.size - 3
        if (nearBottom && state.logs.isNotEmpty()) {
            listState.scrollToItem(state.logs.size - 1)
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            // 日志区用更实的一层底：密集的等宽小字经不起背景花纹干扰。
            // 圆角 + 内缩，把它和外面的进度区分开。
            .padding(horizontal = 24.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = LOG_PANEL_ALPHA),
            modifier = Modifier.fillMaxSize(),
        ) {
            if (state.logs.isEmpty()) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = text(TextKey.LAUNCH_LOG_EMPTY),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                    )
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        items(state.logs) { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                // 日志行不换行更整齐，但异常堆栈会超长。
                                // 这里用默认换行，宁可多占几行也别看不全。
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.86f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 底部操作条：两个按钮都在右下角。
 *
 * 「停止游戏」只在进程还活着时出现 —— 判断的是 `processAlive` 而不是
 * `finished`：窗口加载后 `finished` 就是 true 了，但游戏还在跑，
 * 此时用户最需要的恰恰是这个按钮。
 *
 * 「返回」始终存在。它的语义是"我不想盯着了"，不是"结束游戏" ——
 * 会话会收到后台，可以从齿轮抽屉点回来。
 */
@Composable
private fun ActionBar(
    state: LaunchState,
    onBack: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (state.processAlive) {
            // 危险操作用描边 + 红色文字，不用实心红块 ——
            // 实心红太抢眼，这是次要操作，不该压过"返回"。
            OutlinedButton(onClick = onStop) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = Color(0xFFC62828),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = text(TextKey.LAUNCH_STOP),
                    color = Color(0xFFC62828),
                )
            }
            Spacer(Modifier.width(10.dp))
        }

        // 主操作放最右：符合"确认/继续"在右的习惯。
        Button(onClick = onBack) {
            Text(
                text = if (state.finished) text(TextKey.LAUNCH_CLOSE) else text(TextKey.LAUNCH_BACK),
            )
        }
    }
}

/**
 * 背景遮罩的不透明度。
 *
 * 比登录页（0.28）浓得多：这里是日志界面，等宽小字压在花纹背景上会看不清。
 * 0.62 能在"保留背景存在感"和"文字可读"之间取平衡 ——
 * 低于 0.5 时背景的花纹会明显干扰阅读。
 */
private const val SCRIM_ALPHA = 0.62f

/**
 * 日志面板底色的不透明度。
 *
 * 比遮罩更实：日志是密集小字，需要一块干净底板。
 * 但也不做成纯白 —— 那样就退回原来那个"白板"的老问题了。
 */
private const val LOG_PANEL_ALPHA = 0.55f
