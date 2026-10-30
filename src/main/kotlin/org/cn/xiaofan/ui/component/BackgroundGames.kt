package org.cn.xiaofan.ui.component

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties

/**
 * 后台游戏入口：一枚圆形齿轮按钮。
 *
 * <h2>位置</h2>
 *
 * 挂在**主区域内**的左下角，位于启动按钮的右侧、与之同一水平线。
 * 注意它不在侧栏里面 —— 侧栏是"导航菜单"，这里放的是"当前在跑的东西"，
 * 两件事不该混在一起。视觉上它贴着启动按钮那一排，但和窗口右边缘
 * 之间还隔着一整个侧栏的宽度，所以不会跟侧栏抢位置。
 *
 * <h2>为什么是齿轮</h2>
 *
 * 齿轮在这套界面里只承担"管理/设置类的东西"这一含义。后台进程列表
 * 属于管理性质 —— 不是主操作（启动），也不是导航（侧栏）。
 * 用图标而不是文字，是因为它低频；低频操作不该占据视线重心。
 *
 * <h2>数量角标</h2>
 *
 * 有一个以上后台进程时，右上角显示数字。这是**唯一**提示
 * "还有游戏在跑"的地方 —— 没有它，用户关掉日志界面后就完全
 * 不知道后台还有东西，容易重复启动多个实例。
 *
 * @param count 后台会话数量。为 0 时按钮半透明，表示没什么可管理的
 * @param onClick 点击展开抽屉
 * @param overlay 浮层内容（抽屉）。放在按钮**内部的 Box 里**，
 *   这样 Popup 的锚点就是这个按钮本身 —— 如果放在按钮外面
 *   （比如同一行的兄弟位置），锚点会变成父容器，抽屉就飞到别处去
 */
@Composable
fun BackgroundGamesButton(
    count: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    overlay: @Composable () -> Unit = {},
) {
    // 有后台进程时完全不透明，没有时压到 0.45 ——
    // 不确定"有没有东西"的用户会去点它，点开是空的会困惑。
    val alpha by animateFloatAsState(
        targetValue = if (count > 0) 1f else 0.45f,
        animationSpec = tween(220),
        label = "bgButtonAlpha",
    )

    Box(
        modifier = modifier
            .size(56.dp)
            .graphicsLayer { this.alpha = alpha },
    ) {
        Surface(
            shape = CircleShape,
            color = Color.White.copy(alpha = 0.92f),
            shadowElevation = 6.dp,
            modifier = Modifier
                .size(56.dp)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "后台游戏",
                    tint = Color(0xFF2A2A2A),
                    modifier = Modifier.size(24.dp),
                )
            }
        }

        // 角标：只统计"正在跑"的会话。
        if (count > 0) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF1976D2))
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (count > 9) "9+" else count.toString(),
                    color = Color.White,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        // 浮层（抽屉）声明在按钮内部：Popup 的 anchorBounds 取的是
        // **调用点的布局边界**，也就是这个 56dp 的 Box。
        // 放在外面的话锚点会变成整行，抽屉位置就完全不对了。
        overlay()
    }
}

/**
 * 后台游戏列表抽屉。
 *
 * <p>交互参照版本抽屉：贴在按钮上方弹出、点外部关闭、点条目进入。
 * 这样两个抽屉的手感一致，用户不用学两套。
 *
 * <p>每条显示版本 + PID。**PID 是必要的**：同时开两个同版本的实例
 * （很常见，多开小号），只显示版本名根本分不清哪个是哪个。
 * PID 用等宽字体，便于比对。
 *
 * @param entries 后台会话。空列表时显示占位文字而不是空白框
 * @param onEnter 点击某条后进入它的进度/日志界面
 * @param onStop 结束某个后台游戏（不进界面直接杀）
 * @param onDismiss 关闭抽屉
 */
@Composable
fun BackgroundGamesDrawer(
    entries: List<BackgroundGameEntry>,
    onEnter: (BackgroundGameEntry) -> Unit,
    onStop: (BackgroundGameEntry) -> Unit,
    onDismiss: () -> Unit,
) {
    // 用 Popup 而不是普通 Composable。
    //
    // 原因：抽屉宽 300dp，齿轮只有 56dp。如果把它放进按钮所在的容器，
    // 容器会被撑到 300dp，把同一行的启动按钮挤变形 —— 实测就是这样
    // "挤成一坨"。Popup 是独立窗口，**完全不参与父级测量**，
    // 齿轮保持 56dp 不动，抽屉浮在它上方。
    //
    // **定位必须用 popupPositionProvider**：`alignment` 参数是相对
    // 整个窗口计算的，不是相对调用点。用了它抽屉就直接飞到窗口
    // 左下角去了。provider 能拿到锚点（按钮）的窗口坐标，才能
    // 真正"贴着按钮"。
    val density = LocalDensity.current
    val gapPx = with(density) { 8.dp.roundToPx() }

    Popup(
        // 点别处收起。抽屉本身是浮层，这个行为是必要的 ——
        // 否则用户没有明显的方式关掉它。
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
        popupPositionProvider = remember(gapPx) {
            object : PopupPositionProvider {
                override fun calculatePosition(
                    anchorBounds: IntRect,
                    windowSize: IntSize,
                    layoutDirection: LayoutDirection,
                    popupContentSize: IntSize,
                ): IntOffset {
                    // anchorBounds 是**齿轮按钮**的窗口坐标
                    // （因为 Popup 声明在按钮内部的 Box 里）。

                    // 水平：抽屉右边缘对齐按钮右边缘，向左展开。
                    //
                    // 为什么向左：按钮在启动按钮的右侧、本来就靠右，
                    // 向右展开会撞到侧栏甚至挤出窗口。
                    val rightAligned = anchorBounds.right - popupContentSize.width

                    // 垂直：抽屉底边落在按钮顶边上方 gapPx 处。
                    val aboveButton = anchorBounds.top - popupContentSize.height - gapPx

                    // 兜底：上方空间不够时改放按钮下方，
                    // 免得抽屉被推出屏幕上边缘看不见。
                    val y = if (aboveButton < 0) {
                        anchorBounds.bottom + gapPx
                    } else {
                        aboveButton
                    }

                    // 再兜底一次：左侧也不够时往右拉回来。
                    val x = rightAligned.coerceAtLeast(gapPx)

                    return IntOffset(x, y)
                }
            }
        },
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.White,
            shadowElevation = 12.dp,
            modifier = Modifier
                .width(BACKGROUND_DRAWER_WIDTH)
                .heightIn(max = 320.dp),
        ) {
            Column(modifier = Modifier.padding(vertical = 8.dp)) {
                Text(
                    text = "后台游戏",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.Black.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )

                if (entries.isEmpty()) {
                    Text(
                        text = "没有正在运行的游戏",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Black.copy(alpha = 0.4f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
                    )
                } else {
                    LazyColumn {
                        items(entries, key = { it.id }) { entry ->
                            BackgroundGameRow(
                                entry = entry,
                                onEnter = { onEnter(entry) },
                                onStop = { onStop(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 抽屉里的一行。 */
@Composable
private fun BackgroundGameRow(
    entry: BackgroundGameEntry,
    onEnter: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onEnter)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.versionId,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFF1A1A1A),
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 存活指示灯：绿点是"在跑"，灰点是"启动中/已结束"。
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(
                            if (entry.running) Color(0xFF2E7D32)
                            else Color.Black.copy(alpha = 0.25f)
                        ),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "PID ${entry.pid ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Color.Black.copy(alpha = 0.5f),
                )
            }
        }

        // 直接结束。给"我就是要关掉它，不想进界面"的场景一条捷径。
        Text(
            text = "停止",
            style = MaterialTheme.typography.labelMedium,
            color = Color(0xFFC62828),
            modifier = Modifier
                .clickable(onClick = onStop)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * 抽屉里的一条后台游戏记录。
 *
 * <p>做成一个扁平的 data class 而不是直接传会话对象：
 * 抽屉只需要这几个字段，把整个 `LaunchSession`（带着 Controller、
 * CoroutineScope、完整状态流）传进来会让这个组件跟业务实现绑死。
 */
data class BackgroundGameEntry(
    /** 稳定标识，用作 LazyColumn 的 key。 */
    val id: Long,
    /** 版本名。 */
    val versionId: String,
    /** 进程 PID。还没起来时为 null。 */
    val pid: Long?,
    /** 进程是否活着。 */
    val running: Boolean,
)

/** 抽屉宽度。调用方对齐右边缘时要用到这个值，所以是公开的。 */
val BACKGROUND_DRAWER_WIDTH = 300.dp

/** 齿轮按钮直径。调用方算上移量时要用。 */
val GEAR_BUTTON_SIZE = 56.dp
