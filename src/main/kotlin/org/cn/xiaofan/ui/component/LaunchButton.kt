package org.cn.xiaofan.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import kotlin.math.PI
import kotlin.math.sin

/**
 * 启动按钮的动画状态。由宿主页面持有，按钮和火箭各读各的。
 */
class LaunchAnimationState {
    /** 0 → 1 的整段进度。 */
    internal val progress = Animatable(0f)

    /** 按钮在窗口坐标系里的实际位置，用来决定火箭从哪里起飞。 */
    internal var origin by mutableStateOf(Rect.Zero)

    /** 动画播放期间为 true，此时不接受新的点击。 */
    internal var busy by mutableStateOf(false)
        private set

    /**
     * 播放一遍启动动画。
     */
    suspend fun play() {
        if (busy) return
        busy = true
        try {
            progress.snapTo(0f)
            progress.animateTo(
                targetValue = 1f,
                animationSpec = tween(ANIMATION_MILLIS, easing = LinearEasing),
            )
        } finally {
            progress.snapTo(0f)
            busy = false
        }
    }
}

/** 记住一份动画状态。 */
@Composable
fun rememberLaunchAnimationState(): LaunchAnimationState =
    remember { LaunchAnimationState() }

/**
 * 首页左下角的启动按钮。
 *
 * <p>本身不做任何分裂动画 —— 点击后外壳只是被"撑"一下（外壳鼓起、
 * 裂缝从中心亮起），然后交棒给 [LaunchOverlay] 里的火箭。
 * 按钮不碎、不飞走：碎掉就再也点不了了，而且用户要的是
 * "火箭冲破按钮"，不是"按钮自己炸开"。按钮应该是被穿透的靶子，
 * 不是被炸掉的容器。
 *
 * <p>尺寸做得比普通按钮大得多（260×72dp）：它是这张界面唯一的主操作，
 * 尺寸本身就是"点这个"的提示。小按钮压在背景图上会被照片吃掉。
 *
 * <p>白底黑字：背景是亮调海景，白底靠明度对比从图上立出来；
 * 黑字在白底上对比度最高，也不跟背景的冷色相抢。
 */
@Composable
fun LaunchButton(
    state: LaunchAnimationState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = state.progress.value

    // 外壳受压：按下去再弹回来。
    val press = t.seg(PRESS_START, PRESS_END)
    val scale = 1f - 0.05f * sin(press * PI).toFloat()

    // 裂缝余晖：火箭冲出后按钮表面留一圈余热，然后熄灭。
    val scorch = t.seg(CRACK_START, CRACK_END) * (1f - t.seg(SPLIT_END, SPLIT_END + 0.10f))

    Box(
        modifier = modifier
            .width(BUTTON_WIDTH)
            .height(BUTTON_HEIGHT)
            // 把按钮在窗口里的真实位置记下来，火箭从这里起飞。
            // 必须放在 graphicsLayer 之前：graphicsLayer 会把子树的坐标
            // 变换掉，位置要在变换之前取才准。
            .onGloballyPositioned { state.origin = it.boundsInWindow() }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(BUTTON_CORNER))
            .background(SHELL_FILL)
            .drawBehind { if (scorch > 0f) drawScorch(scorch) }
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = {
                    if (!state.busy) onClick()
                },
            ),
    ) {
        // 按钮自身不启动动画：谁调用它谁负责 play()，
        // 因为它还要触发覆盖层的火箭，两件事必须一起安排。
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                modifier = Modifier.size(30.dp),
                tint = SHELL_CONTENT,
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text(TextKey.HOME_LAUNCH),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                color = SHELL_CONTENT,
                fontSize = 22.sp,
            )
        }
    }
}

/**
 * 全屏覆盖层：火箭从按钮位置起飞，一直飞到屏幕顶部之外才消失。
 *
 * <p>为什么要单独一层：火箭必须能飞出按钮的边界、也飞出主区域的内边距，
 * 一路升到窗口顶边之外。任何"把火箭放进按钮内部"的做法都会被
 * 祖先节点的 clip 和布局边界卡住，所以只能在最外层铺一张
 * 不受任何约束的画布。
 *
 * <p>覆盖层不接收任何输入（没有 clickable / pointerInput），
 * 所以它盖在界面上时不会挡住底下的按钮。
 *
 * @param state 动画状态，坐标和进度都从这里读
 */
@Composable
fun LaunchOverlay(
    state: LaunchAnimationState,
    modifier: Modifier = Modifier,
) {
    val t = state.progress.value
    val flight = t.seg(ROCKET_START, ROCKET_END)

    // 到窗口顶了就该消失，但不必真的把 origin 判空 ——
    // Rect.Zero 意味着按钮还没被测量过，此时没有起点可算。
    if (flight <= 0f || state.origin == Rect.Zero) return

    val origin = state.origin

    Box(modifier = modifier.fillMaxSize()) {
        // 必须用 matchParentSize 而不是 fillMaxSize：
        // 这个 Box 没有内容，fillMaxSize 在无约束的父容器里会量成 0 高，
        // 画布高度为 0，火箭就画不出来（只剩按钮表面的焦痕在动）。
        // matchParentSize 不参与父容器的尺寸计算，直接取父容器的实测尺寸。
        Box(
            modifier = Modifier
                .matchParentSize()
                .drawBehind { drawFlyingRocket(flight, origin) },
        )
    }
}

// ---------------------------------------------------------------------------
// 尺寸与配色
// ---------------------------------------------------------------------------

private val BUTTON_WIDTH = 260.dp
private val BUTTON_HEIGHT = 72.dp
private val BUTTON_CORNER = 12.dp

/** 整段动画总时长。 */
private const val ANIMATION_MILLIS = 1400

/** 白底。留 8% 透明度让背景透出极淡一层，不至于像贴了张纸。 */
private val SHELL_FILL = Color.White.copy(alpha = 0.92f)

/** 按钮上的字色。 */
private val SHELL_CONTENT = Color.Black

/** 内芯/尾焰最内层的白热色。 */
private val CORE_HOT = Color(0xFFFFF3C4)

/** 尾焰中段的橙。 */
private val FLAME_ORANGE = Color(0xFFFF9A3C)

/** 尾焰根部和箭体饰件的红。 */
private val FLAME_RED = Color(0xFFE8452B)

// ---- 时间段。归一化到 0..1。----

private const val PRESS_START = 0.00f
private const val PRESS_END = 0.12f
private const val CRACK_START = 0.08f
private const val CRACK_END = 0.26f
private const val SPLIT_END = 0.34f
private const val ROCKET_START = 0.24f
private const val ROCKET_END = 1.00f

/** 把全局进度映射到区间内的 0..1，区间外夹住。 */
private fun Float.seg(start: Float, end: Float): Float =
    ((this - start) / (end - start)).coerceIn(0f, 1f)

// ---------------------------------------------------------------------------
// 按钮表面的余晖
// ---------------------------------------------------------------------------

/**
 * 按钮被穿透后留在表面的一圈焦痕。
 *
 * <p>一个柔和的径向渐变，中心白热、边缘透明，尺寸随 [amount] 长大。
 * 这比"裂缝"更符合"被从下面顶穿"的叙事：洞是圆的、热的，
 * 不是裂成两半的。
 */
private fun DrawScope.drawScorch(amount: Float) {
    val cx = size.width / 2f
    val cy = size.height * 0.62f
    val radius = size.height * 0.8f * amount
    if (radius <= 0f) return

    val path = Path().apply {
        addOval(
            androidx.compose.ui.geometry.Rect(
                left = cx - radius,
                top = cy - radius,
                right = cx + radius,
                bottom = cy + radius,
            )
        )
    }
    drawPath(
        path,
        Brush.radialGradient(
            0f to CORE_HOT.copy(alpha = 0.95f * amount),
            0.45f to FLAME_ORANGE.copy(alpha = 0.75f * amount),
            1f to FLAME_RED.copy(alpha = 0f),
            center = androidx.compose.ui.geometry.Offset(cx, cy),
            radius = radius,
        ),
    )
}

// ---------------------------------------------------------------------------
// 火箭
// ---------------------------------------------------------------------------

/**
 * 在覆盖层上画一枚正在升空的火箭。
 *
 * <p>起点是按钮的**真实**位置（[origin]，窗口坐标系，跟覆盖层同一套坐标），
 * 所以不管按钮将来挪到哪儿，火箭都从它身上穿出来。终点是窗口顶边
 * **之外**一个火箭高度处 —— 用户要的是"越过顶部之后消失"，
 * 所以终点必须真的在可视区外，不能停在边上淡出。
 *
 * <p>位移用 `t²`：火箭是被点着火的，起步慢、中段极快、到顶仍然高速，
 * 于是"嗖"一下就没影了。用先快后慢的缓动会像气球飘上去。
 */
private fun DrawScope.drawFlyingRocket(
    flight: Float,
    origin: Rect,
) {
    val eased = flight * flight

    // 起飞点：按钮水平中心、垂直中心略靠上（推进器在按钮内部）。
    val cx = origin.center.x
    val startY = origin.center.y
    // 终点：窗口顶边之外，留一个箭身高度确保完全飞出可视区。
    val endY = -ROCKET_BODY_PX * 1.6f
    val cy = startY + (endY - startY) * eased

    // 尾焰抖动：两个互质频率叠加，避免看出周期。
    val flicker = 0.85f + 0.10f * sin(flight * 90f) + 0.05f * sin(flight * 37f)

    // 全程不缩放：火箭是实体，飞远变小反而削弱"冲出去"的力度。
    val bodyW = ROCKET_BODY_PX * 0.62f
    val bodyTop = cy - ROCKET_BODY_PX * 0.58f
    val bodyBottom = cy + ROCKET_BODY_PX * 0.50f

    // 尾焰：从宽到窄、从红到白，最外层最长。长度随速度增长。
    val speed = 0.45f + 0.55f * eased
    drawFlame(cx, bodyBottom, bodyW * 0.80f, ROCKET_BODY_PX * 1.9f * speed * flicker, FLAME_RED.copy(alpha = 0.88f))
    drawFlame(cx, bodyBottom, bodyW * 0.52f, ROCKET_BODY_PX * 1.4f * speed * flicker, FLAME_ORANGE)
    drawFlame(cx, bodyBottom, bodyW * 0.26f, ROCKET_BODY_PX * 0.9f * speed * flicker, CORE_HOT)

    drawRocketBody(cx, bodyTop, bodyBottom, bodyW)
    drawRocketNose(cx, bodyTop, bodyW)
    drawRocketFins(cx, bodyBottom, bodyW)
}

/** 火箭箭身高度（像素）。整枚火箭的比例都按它缩放。 */
private const val ROCKET_BODY_PX = 72f

/** 一条尾焰：上宽下尖的水滴，两条三次贝塞尔拼成。 */
private fun DrawScope.drawFlame(
    cx: Float,
    top: Float,
    width: Float,
    length: Float,
    color: Color,
) {
    val half = width / 2f
    val path = Path().apply {
        moveTo(cx - half, top)
        cubicTo(
            cx - half, top + length * 0.55f,
            cx - half * 0.30f, top + length * 0.85f,
            cx, top + length,
        )
        cubicTo(
            cx + half * 0.30f, top + length * 0.85f,
            cx + half, top + length * 0.55f,
            cx + half, top,
        )
        close()
    }
    drawPath(path, color)
}

/** 箭体：上宽下稍窄的白柱，左侧一条暗面做圆柱感，中间一道红色腰线。 */
private fun DrawScope.drawRocketBody(
    cx: Float,
    top: Float,
    bottom: Float,
    width: Float,
) {
    val halfTop = width / 2f
    val halfBottom = width * 0.40f

    val body = Path().apply {
        moveTo(cx - halfTop, top)
        lineTo(cx + halfTop, top)
        lineTo(cx + halfBottom, bottom)
        lineTo(cx - halfBottom, bottom)
        close()
    }
    drawPath(body, Color.White)

    // 暗面：只取左侧 30%，从顶部一直收到尾部。
    val shade = Path().apply {
        moveTo(cx - halfTop, top)
        lineTo(cx - halfTop * 0.30f, top)
        lineTo(cx - halfBottom * 0.55f, bottom)
        lineTo(cx - halfBottom, bottom)
        close()
    }
    drawPath(shade, Color(0xFFD9DFE7))

    // 横向腰线，打破纯白柱体的单调。
    val stripeY = top + (bottom - top) * 0.55f
    val stripeHeight = (bottom - top) * 0.10f
    val stripe = Path().apply {
        moveTo(cx - halfTop, stripeY)
        lineTo(cx + halfTop, stripeY)
        lineTo(cx + halfBottom, stripeY + stripeHeight)
        lineTo(cx - halfBottom, stripeY + stripeHeight)
        close()
    }
    drawPath(stripe, FLAME_RED.copy(alpha = 0.85f))
}

/** 鼻锥：略宽于箭体顶部的三角，形成一圈台阶。 */
private fun DrawScope.drawRocketNose(
    cx: Float,
    bodyTop: Float,
    width: Float,
) {
    val half = width * 0.54f
    val path = Path().apply {
        moveTo(cx - half, bodyTop)
        lineTo(cx, bodyTop - width * 0.85f)
        lineTo(cx + half, bodyTop)
        close()
    }
    drawPath(path, FLAME_RED)
}

/** 尾翼：左右各一片后掠的三角。 */
private fun DrawScope.drawRocketFins(
    cx: Float,
    bodyBottom: Float,
    width: Float,
) {
    val halfBottom = width * 0.40f
    listOf(-1f, 1f).forEach { sign ->
        val path = Path().apply {
            moveTo(cx + sign * halfBottom, bodyBottom - width * 0.30f)
            lineTo(cx + sign * width * 0.80f, bodyBottom)
            lineTo(cx + sign * halfBottom, bodyBottom)
            close()
        }
        drawPath(path, FLAME_RED)
    }
}
