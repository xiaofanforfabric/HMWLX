package org.cn.xiaofan.ui.screen

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import org.cn.xiaofan.util.DesktopOpener
import org.cn.xiaofan.version.GameVersion
import org.cn.xiaofan.version.GameVersionService
import org.cn.xiaofan.version.VersionType

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
    onLaunch: (GameVersion) -> Unit = {},
) {
    var selected by remember { mutableStateOf<GameVersion?>(null) }
    var versions by remember { mutableStateOf<List<GameVersion>>(emptyList()) }
    var gameDirPath by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    // 每次要重扫 +1，LaunchedEffect 以它为 key。刷新按钮与「选了不可启动版本时
    // 顺手重扫一次」共用这条路径，避免两处各写一套加载逻辑。
    var reloadToken by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(reloadToken) {
        loading = true
        error = null
        when (val result = GameVersionService.scan()) {
            is GameVersionService.VersionScanResult.Success -> {
                versions = result.versions
                gameDirPath = result.gameDir.absolutePath
                // 选中的版本被删掉（或第一次进来）时，落到列表第一个。
                // 不这么做的后果是右边一直显示一个已经不存在的版本。
                if (selected == null || versions.none { it.id == selected?.id }) {
                    selected = versions.firstOrNull()
                }
            }
            is GameVersionService.VersionScanResult.Failed -> {
                versions = emptyList()
                selected = null
                error = result.reason
            }
        }
        loading = false
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        // 主界面不留整屏遮罩，靠局部面板压住背景图上的噪点。
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
    ) {
        Row(modifier = Modifier.fillMaxSize()) {
            // 左：版本列表
            Column(modifier = Modifier.width(300.dp).fillMaxHeight()) {
                ListHeader(
                    count = versions.size,
                    loading = loading,
                    onRefresh = { if (!loading) reloadToken++ },
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
                            onSelect = { selected = it },
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
                        gameDirPath = gameDirPath,
                        onLaunch = {
                            // 启动链路还没接：先把选中版本交出去，调用方决定怎么提示。
                            // 这里不自己弹「敬请期待」——那是界面该说的话，不是列表该说的话。
                            onLaunch(current)
                        },
                        onRescan = { if (!loading) reloadToken++ },
                        onOpenGameDir = {
                            // 打不开目录（目录被删 / 没有文件管理器）只是不响应，不弹错误框：
                            // 用户点这个按钮的目的就是「自己去看看」，弹窗反而挡住他。
                            // 丢到 IO 线程：Desktop.open 会同步调起文件管理器，某些桌面环境下
                            // 这一步能卡几百毫秒。
                            gameDirPath?.let { path -> scope.launch { DesktopOpener.open(path) } }
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
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
private fun typeLabel(version: GameVersion): String = when (version.type) {
    VersionType.RELEASE -> text(TextKey.VERSION_TYPE_RELEASE)
    VersionType.SNAPSHOT -> text(TextKey.VERSION_TYPE_SNAPSHOT)
    VersionType.OLD_BETA -> text(TextKey.VERSION_TYPE_OLD_BETA)
    VersionType.OLD_ALPHA -> text(TextKey.VERSION_TYPE_OLD_ALPHA)
    VersionType.OTHER -> version.rawType ?: text(TextKey.VERSION_TYPE_OTHER)
}
