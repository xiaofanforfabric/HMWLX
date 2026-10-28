package org.cn.xiaofan.ui.component

import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image as SkiaImage

/**
 * 应用背景图。
 *
 * <p>资源是 `images/background.jpg`（1920×1054，JPEG q82，约 88KB）。
 * 原图是 3MB 的 PNG 截图，压成 JPEG 后体积降到 3%，肉眼几乎无差别 ——
 * 背景这种大面积渐变画面用 JPEG 比 PNG 划算得多。
 *
 * <p>加载失败时什么都不显示，由调用方给 [Surface] 一个兜底色，
 * 不会因为缺个装饰图就白屏。
 *
 * @param dimOverlay 压暗遮罩的浓度，0 表示不压暗。
 *                   背景本身偏暗，但正文是深色文字时仍需要压暗才能保证对比度。
 */
@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    dimOverlay: Float = 0f,
) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(Unit) {
        // 解码是 CPU 活，放 IO 线程；图不大但没必要占着 UI 线程
        bitmap = withContext(Dispatchers.IO) { loadBackground() }
    }

    Box(modifier = modifier.fillMaxSize()) {
        val image = bitmap
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                // Crop：铺满整个窗口，允许裁掉超出部分。
                // 用 Fit 会留黑边，用 FillBounds 会拉伸变形。
                contentScale = ContentScale.Crop,
                // 背景是照片，用双线性插值；像素画才需要 None
                filterQuality = FilterQuality.Low,
            )
        }

        if (dimOverlay > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(dimOverlay.coerceIn(0f, 1f))
                    .background(Color.Black),
            )
        }
    }
}

/** 从 classpath 读背景图并解码。缺失或损坏时返回 null。 */
private fun loadBackground(): ImageBitmap? = runCatching {
    val bytes = AppBackgroundLoader::class.java
        .getResourceAsStream("/images/background.jpg")
        ?.use { it.readBytes() }
        ?: run {
            System.err.println("[UI] 背景图缺失: /images/background.jpg")
            return null
        }
    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrElse { t ->
    System.err.println("[UI] 背景图加载失败: ${t.javaClass.simpleName}: ${t.message}")
    null
}

/**
 * 仅用于从 classpath 定位资源。
 *
 * 不用 `AppBackground::class` —— 那是 Composable 函数，取不到 class 字面量
 * （Kotlin 会报 "Function invocation expected"）。
 */
private object AppBackgroundLoader
