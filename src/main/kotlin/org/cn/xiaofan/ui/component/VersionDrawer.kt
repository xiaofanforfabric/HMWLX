package org.cn.xiaofan.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import org.cn.xiaofan.version.GameVersion
import org.cn.xiaofan.version.VersionType
import org.jetbrains.skia.Image as SkiaImage

/**
 * 版本选择抽屉。
 *
 * <p>朝上弹出，贴在启动按钮正上方。选上之后立刻收起 ——
 * 这是个单选列表，选完就该结束，没有「确定」按钮的必要。
 *
 * <p>为什么朝上而不是朝下：按钮本身坐在窗口底部，往下弹会到屏幕外面。
 *
 * @param versions 已安装的版本
 * @param selected 当前选中的版本
 * @param onSelect 选中某个版本
 * @param onDismiss 点击外部或按 Esc
 */
@Composable
fun VersionDrawer(
    versions: List<GameVersion>,
    selected: GameVersion?,
    onSelect: (GameVersion) -> Unit,
    onDismiss: () -> Unit,
) {
    // 打开时滚到当前选中项，否则列表一长用户还得自己找。
    val listState = rememberLazyListState()
    LaunchedEffect(Unit) {
        val index = versions.indexOfFirst { it.id == selected?.id }
        if (index >= 0) listState.scrollToItem(index)
    }

    Popup(
        alignment = Alignment.BottomStart,
        // 点别处就关。这个 set 是"不允许焦点回到外部"，但对鼠标点击
        // 依然会走 dismiss，所以点背景能收起。
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Surface(
            modifier = Modifier
                .width(DRAWER_PANEL_WIDTH)
                // 最高六个半条目高，再多就滚动。半个是为了让用户看出"下面还有"。
                .heightIn(max = DRAWER_ROW_HEIGHT * 6.5f),
            shape = RoundedCornerShape(10.dp),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            tonalElevation = 8.dp,
            shadowElevation = 12.dp,
        ) {
            Column {
                Text(
                    text = text(TextKey.HOME_VERSION_DRAWER),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                    modifier = Modifier.padding(start = 14.dp, top = 12.dp, bottom = 8.dp),
                )
                HorizontalDivider(color = Color.Black.copy(alpha = 0.07f))

                if (versions.isEmpty()) {
                    Text(
                        text = text(TextKey.VERSION_EMPTY),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                        modifier = Modifier.padding(14.dp),
                    )
                } else {
                    LazyColumn(state = listState) {
                        items(versions, key = { it.id }) { version ->
                            DrawerRow(
                                version = version,
                                selected = version.id == selected?.id,
                                onClick = { onSelect(version) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 抽屉宽度。比按钮窄一点，看起来是从按钮上长出来的而不是另开一块。 */
private val DRAWER_PANEL_WIDTH = 304.dp

/** 单行高度，用来算列表最大高度。 */
private val DRAWER_ROW_HEIGHT = 52.dp

/**
 * 抽屉里的一行：图标 + 版本号 + 类型，右侧打勾表示当前选中。
 */
@Composable
private fun DrawerRow(
    version: GameVersion,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(DRAWER_ROW_HEIGHT)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VersionIcon(
            version = version,
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(10.dp))

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = version.id,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = text(version.typeTextKey()),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            )
        }

        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * 版本图标：按类型选图。
 *
 * <p>愚人节优先级最高 —— 它们在 JSON 里写的是 `snapshot`，
 * 如果先判类型就永远看不到愚人节的图标。
 *
 * <p>图标走 `ClassLoader.getResourceAsStream`，和 [AppBackground] 一个路子：
 * 项目没开 Compose Resources 代码生成，手写路径反而少一层依赖。
 */
@Composable
private fun VersionIcon(version: GameVersion, modifier: Modifier = Modifier) {
    val resName = when {
        version.isAprilFools -> "images/icon-aprilfools.png"
        version.type == VersionType.RELEASE -> "images/icon-release.png"
        version.type == VersionType.SNAPSHOT -> "images/icon-snapshot.png"
        else -> "images/icon-old.png"
    }
    val bitmap = remember(resName) { loadIcon(resName) }

    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            modifier = modifier,
            filterQuality = FilterQuality.High,
        )
    }
}

/** 读图并解码。失败返回 null，界面上不显示图标而不是崩掉。 */
private fun loadIcon(resName: String): ImageBitmap? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader
        ?.getResourceAsStream(resName)
        ?.use { it.readBytes() }
        ?: return null
    SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
}.getOrNull()

/** 版本类型对应的文案 key。愚人节优先于快照判定。 */
private fun GameVersion.typeTextKey(): String = when {
    isAprilFools -> TextKey.VERSION_TYPE_APRIL_FOOLS
    else -> when (type) {
        VersionType.RELEASE -> TextKey.VERSION_TYPE_RELEASE
        VersionType.SNAPSHOT -> TextKey.VERSION_TYPE_SNAPSHOT
        VersionType.OLD_BETA -> TextKey.VERSION_TYPE_OLD_BETA
        VersionType.OLD_ALPHA -> TextKey.VERSION_TYPE_OLD_ALPHA
        VersionType.OTHER -> TextKey.VERSION_TYPE_OTHER
    }
}
