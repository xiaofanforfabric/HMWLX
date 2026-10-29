package org.cn.xiaofan.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import org.cn.xiaofan.util.DesktopOpener
import org.cn.xiaofan.version.GameVersion
import org.cn.xiaofan.version.VersionState
import org.cn.xiaofan.version.VersionType
import org.jetbrains.skia.Image as SkiaImage

/**
 * 主界面：版本列表 + 版本详情。
 *
 * <p>布局照抄主流启动器的骨架 —— 左边一列版本，右边是选中版本的详情和启动按钮。
 * 分成左右两块而不是一个大列表，是因为「选版本」和「启动」是两件事：
 * 列表只用来挑，启动的上下文（游戏目录、加载器、是否可以启动）全在右边。
 *
 * <p>列表数据来自 [GameVersionService]，是**读磁盘**的操作，因此在协程里加载，
 * 加载期间显示进度条，失败时显示原因并提供重试 —— 不静默吞掉。
 *
 * @param onLaunch 启动回调。当前由调用方留空并提示「还没接」，
 *   等启动链路（拼参数 → 起 JVM → 接日志）做完再填。
 */
@Composable
fun VersionListScreen(
    state: VersionState,
    onLaunch: (GameVersion) -> Unit = {},
) {
    val versions = state.versions
    val selected = state.selected
    val loading = state.loading
    val error = state.error
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        // 主界面不留整屏遮罩，靠局部面板压住背景图上的噪点。
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 左：版本列表
            // 删掉了AI的废话,AI老写这种为什么要改这块地方,而这种废话注释对我和别人阅读没有意义!
            //这个Modifier.width(xxx.dp)是版本列表宽度,如果你想改宽度可以改这个值.
            Column(modifier = Modifier.width(200.dp).fillMaxHeight()) {
                ListHeader(
                    count = versions.size,
                    loading = loading,
                    onRefresh = { state.reload() },
                )
                HorizontalDivider()
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        loading -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )
                        error != null -> Text(
                            text = Messages.get(TextKey.VERSION_SCAN_FAILED, error),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(16.dp),
                        )
                        versions.isEmpty() -> Text(
                            text = text(TextKey.VERSION_EMPTY),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                        else -> VersionList(
                            versions = versions,
                            selected = selected,
                            onSelect = { state.select(it) },
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.width(1.dp).fillMaxHeight())

            // 右：选中版本的详情
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                val current = selected
                if (current == null) {
                    Text(
                        text = text(TextKey.VERSION_SELECT_HINT),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    VersionDetail(
                        version = current,
                        gameDirPath = state.gameDirPath,
                        onLaunch = {
                            // 启动链路还没接：先把选中版本交出去，调用方决定怎么提示。
                            // 这里不自己弹「敬请期待」——那是界面该说的话，不是列表该说的话。
                            onLaunch(current)
                        },
                        onRescan = { state.reload() },
                        onOpenGameDir = {
                            // 打不开目录（目录被删 / 没有文件管理器）只是不响应，不弹错误框：
                            // 用户点这个按钮的目的就是「自己去看看」，弹窗反而挡住他。
                            // 丢到 IO 线程：Desktop.open 会同步调起文件管理器，某些桌面环境下
                            // 这一步能卡几百毫秒。
                            state.gameDirPath?.let { path ->
                                scope.launch { DesktopOpener.open(path) }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ListHeader(count: Int, loading: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = Messages.get(TextKey.VERSION_LIST_TITLE, count),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRefresh, enabled = !loading) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = text(TextKey.VERSION_REFRESH),
            )
        }
    }
}

@Composable
private fun VersionList(
    versions: List<GameVersion>,
    selected: GameVersion?,
    onSelect: (GameVersion) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(versions, key = { it.id }) { version ->
            VersionRow(
                version = version,
                selected = version.id == selected?.id,
                onClick = { onSelect(version) },
            )
        }
    }
}

@Composable
private fun VersionRow(version: GameVersion, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface.copy(alpha = 0f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VersionLogo(version)

        Spacer(Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = version.id,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = typeLabel(version),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (!version.launchable) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = text(TextKey.VERSION_MISSING_PARENT),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/**
 * 版本行左侧的图标。
 *
 * <p>按版本类型选方块：正式版草方块、快照工作台、远古版基岩、愚人节命令方块。
 *
 * <p>愚人节必须**先于** [VersionType.SNAPSHOT] 判断 —— 它们在版本 JSON 里
 * `type` 写的也是 `snapshot`，靠版本号对照表（[VersionType.isAprilFools]）区分。
 * 顺序反了就会一律显示成快照图标。
 *
 * <p>认不出类型（加载器写的自定义 `type`）时用基岩兜底，
 * 比误标成「正式版草方块」诚实。
 *
 * <p>走 ClassLoader 读 classpath 资源，跟 [AppBackground] 一致 ——
 * 项目没启用 Compose Resources 代码生成，`painterResource` 用不了。
 */
@Composable
private fun VersionLogo(version: GameVersion) {
    val resource = when {
        // 愚人节优先判：它们的 type 也是 snapshot，不先排会被归进快照。
        version.isAprilFools -> "/images/icon-aprilfools.png"
        version.type == VersionType.RELEASE -> "/images/icon-release.png"
        version.type == VersionType.SNAPSHOT -> "/images/icon-snapshot.png"
        version.type == VersionType.OLD_BETA || version.type == VersionType.OLD_ALPHA ->
            "/images/icon-old.png"
        // 加载器写的自定义 type：认不出真实版本类型，用基岩这个中性图标，
        // 比误标成「正式版草方块」诚实。
        else -> "/images/icon-old.png"
    }

    var bitmap by remember(resource) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(resource) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                VersionListScreenLogo.javaClass
                    .getResourceAsStream(resource)
                    ?.use { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
            }.getOrNull()
        }
    }

    val image = bitmap ?: return

    Image(
        bitmap = image,
        contentDescription = null,
        // 源图 128~512px 缩到 45dp：必须开高质量过滤，否则纹理糊成噪点。
        filterQuality = FilterQuality.High,
        modifier = Modifier.size(45.dp),
    )
}

/** 只为拿到 classloader 而存在的锚点。 */
private object VersionListScreenLogo

@Composable
private fun VersionDetail(
    version: GameVersion,
    gameDirPath: String?,
    onLaunch: () -> Unit,
    onRescan: () -> Unit,
    onOpenGameDir: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(24.dp)) {
        Text(
            text = version.id,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = typeLabel(version),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )

        Spacer(Modifier.height(20.dp))
        DetailLine(TextKey.VERSION_DETAIL_TYPE, typeLabel(version))
        DetailLine(
            TextKey.VERSION_DETAIL_LOADER,
            version.inheritsFrom ?: text(TextKey.VERSION_DETAIL_LOADER_VANILLA),
        )
        DetailLine(
            TextKey.VERSION_DETAIL_MAIN_CLASS,
            version.mainClass ?: text(TextKey.VERSION_DETAIL_UNKNOWN),
        )
        DetailLine(
            TextKey.VERSION_DETAIL_RELEASE_TIME,
            version.releaseTime ?: text(TextKey.VERSION_DETAIL_UNKNOWN),
        )
        DetailLine(
            TextKey.VERSION_DETAIL_GAME_DIR,
            gameDirPath ?: text(TextKey.VERSION_DETAIL_UNKNOWN),
        )

        if (!version.launchable) {
            Spacer(Modifier.height(12.dp))
            Text(
                // 父版本缺失是唯一能确定「一定起不来」的情况，必须说清楚原因，
                // 而不是给个灰按钮让用户猜。
                text = Messages.get(TextKey.VERSION_MISSING_PARENT_HINT, version.inheritsFrom.orEmpty()),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = onRescan) {
                Text(text(TextKey.VERSION_RESCAN))
            }
        }

        Spacer(Modifier.weight(1f))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onLaunch, enabled = version.launchable) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(text(TextKey.VERSION_LAUNCH))
            }
            OutlinedButton(onClick = onOpenGameDir) {
                Text(text(TextKey.VERSION_OPEN_DIR))
            }
            OutlinedButton(onClick = {}, enabled = false) {
                // 占位：版本设置（内存 / JVM 参数 / 是否开独立窗口）还没做。
                // 先留按钮而不是留空位，是为了让「以后在这儿加」这件事显式地看得见。
                // TODO(human): 版本设置面板，做完把 enabled 打开并接上 onClick。
                Text(text(TextKey.VERSION_SETTINGS))
            }
        }
    }
}

@Composable
private fun DetailLine(labelKey: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            text = text(labelKey),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 版本类型的展示名。
 *
 * <p>原版的四个值走语言包，其余（加载器自己写的）**原样显示**原始字符串：
 * 猜成「正式版」会把一个 Forge 版本说成 vanilla，猜成「未知」又等于把信息丢了。
 */
@Composable
private fun typeLabel(version: GameVersion): String = when {
    // 愚人节优先判：它们 JSON 里的 type 也是 snapshot，
    // 不先排就会显示成「快照」，用户看不出这是特殊版本。
    version.isAprilFools -> text(TextKey.VERSION_TYPE_APRIL_FOOLS)
    version.type == VersionType.RELEASE -> text(TextKey.VERSION_TYPE_RELEASE)
    version.type == VersionType.SNAPSHOT -> text(TextKey.VERSION_TYPE_SNAPSHOT)
    version.type == VersionType.OLD_BETA -> text(TextKey.VERSION_TYPE_OLD_BETA)
    version.type == VersionType.OLD_ALPHA -> text(TextKey.VERSION_TYPE_OLD_ALPHA)
    else -> version.rawType ?: text(TextKey.VERSION_TYPE_OTHER)
}
