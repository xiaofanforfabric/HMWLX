package org.cn.xiaofan.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * 火箭飞出屏幕后、启动页出现前的过渡动画。
 *
 * <h2>为什么需要这一段</h2>
 *
 * 火箭飞到窗口顶部就消失了。如果下一帧直接换成白色启动页，
 * 中间是**硬切** —— 视觉上像闪屏或卡了一下，而不是"进去了"。
 *
 * 用户的诉求是"火箭发射到顶后进入启动，而且有一个过渡动画"，
 * 所以这里做三件事：
 *
 * 1. 火箭出画后留一小段**黑场**（[BLACK_HOLD_MILLIS]），让眼睛跟上"飞走了"
 * 2. 黑场上浮出一个极简标识 + 版本名，给转场一个**落脚点**
 * 3. 整体从黑场淡向启动页的白底（[FADE_MILLIS]），完成明暗交接
 *
 * <h2>为什么是黑场而不是白场</h2>
 *
 * 主界面是亮调海景背景，启动页是白底。如果过渡也用白色，
 * 三者都是亮的，火箭飞走那一下就没有"离开"的重量感。
 * 黑场提供一次呼吸，接下来的白页才显得是"新的空间"。
 *
 * <h2>为什么不用 AnimatedContent</h2>
 *
 * `AnimatedContent` 做的是两个完整页面的交叉淡入，而启动页很重
 * （要立即开始拉日志、跑协程）。让它提前参与动画会拖慢首帧。
 * 这里做成一个独立的、纯绘制的遮罩层，启动页在**它下面**正常初始化，
 * 遮罩淡出时下面已经准备好了。
 *
 * <h2>时长与火箭动画的配合</h2>
 *
 * 总时长约 [BLACK_HOLD_MILLIS] + 460 + [TEXT_HOLD_MILLIS] + [FADE_MILLIS] ≈ 1.6s。
 * 火箭动画第二段（焦痕余韵）约 0.17s，远早于黑场结束 ——
 * 所以黑场完全出现时，底下那层按钮动画已经收干净了，不会透出来。
 *
 * @param visible 是否显示过渡层。为 false 时本组件不渲染任何东西
 * @param versionId 正在启动的版本，显示在黑场中央
 * @param onFinished 过渡结束回调
 */
@Composable
fun LaunchTransition(
    visible: Boolean,
    versionId: String,
    onFinished: () -> Unit,
) {
    if (!visible) return

    // 0 → 1 的遮罩不透明度：1 是全黑，0 是完全透明（露出启动页）。
    //
    // 从 0 起步而不是 1：火箭"出画"是按固定进度点判定的，
    // 那一刻火箭鼻锥刚好越界，但尾焰可能还有几像素挂在边上。
    // 瞬间铺满黑场会显得火箭是被"拍黑"的；用一个极短的黑入
    // 盖住这层落差，看起来才是自然飞出画面。
    val veil = remember { Animatable(0f) }

    // 中央文字的悬浮位移（dp 感觉的位移，用 px 由 graphicsLayer 处理）。
    val rise = remember { Animatable(0f) }

    var textVisible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 0) 黑入：把火箭尾部残影盖掉。
        veil.animateTo(
            targetValue = 1f,
            animationSpec = tween(BLACK_IN_MILLIS, easing = FastOutSlowInEasing),
        )

        // 1) 黑场停留：让"火箭飞走了"这件事被看见。
        delay(BLACK_HOLD_MILLIS)

        // 2) 文字浮出。
        textVisible = true
        rise.animateTo(
            targetValue = 1f,
            animationSpec = tween(420, easing = FastOutSlowInEasing),
        )

        // 3) 文字稍作停留，再整体淡出到启动页。
        delay(TEXT_HOLD_MILLIS)
        veil.animateTo(
            targetValue = 0f,
            animationSpec = tween(FADE_MILLIS, easing = FastOutSlowInEasing),
        )

        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = veil.value }
            .background(Color(0xFF07080A)),
    ) {
        if (textVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth()
                    .graphicsLayer {
                        // 从下往上浮 12px，同时淡入。
                        translationY = (1f - rise.value) * 12.dp.toPx()
                        alpha = rise.value
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "HMWLX",
                    color = Color.White.copy(alpha = 0.94f),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 6.sp,
                )
                Spacer(Modifier.height(10.dp))
                Text(
                    text = versionId,
                    color = Color.White.copy(alpha = 0.44f),
                    fontSize = 13.sp,
                    letterSpacing = 1.sp,
                )
                Spacer(Modifier.height(22.dp))
                // 一条极细的进度感横线：不是为了谎报进度，
                // 纯粹是让画面在静止中有一点点动，避免"卡住了"的错觉。
                TransitionHairline()
            }
        }
    }
}

/**
 * 一条来回扫动的细线。
 *
 * <p>它**不代表真实进度** —— 真实进度在启动页里。这里只是让
 * 1 秒左右的等待看起来是"正在进行"而不是"卡死了"。
 * 用无限循环的位移动画，成本极低。
 */
@Composable
private fun TransitionHairline() {
    val shift = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        while (true) {
            shift.snapTo(0f)
            shift.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        }
    }

    Box(
        modifier = Modifier
            .width(120.dp)
            .height(2.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.35f)
                .height(2.dp)
                .graphicsLayer {
                    // 让那 35% 宽的亮块在 120dp 里从左扫到右。
                    translationX = shift.value * (size.width / 0.35f - size.width)
                }
                .background(Color(0xFF4A9EFF).copy(alpha = 0.9f)),
        )
    }
}

/** 黑入时长。极短，纯粹用来盖住火箭飞出瞬间的残影。 */
private const val BLACK_IN_MILLIS = 140

/** 完全黑场停留的时长。 */
private const val BLACK_HOLD_MILLIS = 240L

/** 标识文字浮出后停留的时长。 */
private const val TEXT_HOLD_MILLIS = 420L

/** 淡出到启动页的时长。 */
private const val FADE_MILLIS = 480
