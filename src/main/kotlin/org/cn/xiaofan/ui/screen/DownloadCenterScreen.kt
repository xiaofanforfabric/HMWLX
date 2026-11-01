package org.cn.xiaofan.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.cn.xiaofan.download.DownloadAccess
import org.cn.xiaofan.download.DownloadState
import org.cn.xiaofan.download.MinecraftDownloadService
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/**
 * 下载中心。
 *
 * <h2>准入是硬门槛</h2>
 *
 * Minecraft 本体受 Mojang EULA 保护，只有购买了正版的微软账号才有权获取。
 * 因此本页面分两种形态，**没有中间态**：
 *
 * | 状态 | 界面 |
 * |---|---|
 * | 没有有效微软账号 | 整页只有一句拒绝文案，没有任何下载入口 |
 * | 有有效微软账号 | 下载列表 |
 *
 * <p>**不要**给拒绝态加「仍然继续」「稍后再说」之类的按钮。
 * 那等于告诉用户「其实能下」，既误导又违背协议。
 *
 * <p>服务器连不上也算拒绝：无法确认账号有效性时，宁可不放行。
 *
 * @param state 准入状态与校验逻辑，由调用方持有以跨页面切换保持。
 */
@Composable
fun DownloadCenterScreen(
    state: DownloadState,
) {
    // 重新校验挂在 state.token 上，而不是 Unit。
    //
    // key 为 Unit 时，Compose 复用同一处调用点（本页是整个替换掉的，
    // 位置固定），key 恒等就不会重跑 —— 结果是第一次校验成功后，
    // 之后进多少次都用缓存状态。挂在 token 上，每次进页面 recheck()
    // 推进令牌，这次 effect 必然重跑。
    //
    // 注意 recheck() 只推进令牌、不自己 launch：否则令牌变化让 effect 重跑，
    // effect 又调 recheck()，两者互相触发。这里订阅、那边推进，职责分开。
    LaunchedEffect(state.token) {
        state.runCheck()
    }

    // 下载和重新拉清单都用这个 scope：页面切走时协程自动取消，
    // 不会出现「用户已经去了别的页面，下载结果才弹出来」。
    val scope = rememberCoroutineScope()

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
    ) {
        when (val access = state.access) {
            is DownloadAccess.Checking -> CheckingView()

            is DownloadAccess.Allowed -> AllowedView(access, state, scope)

            is DownloadAccess.Denied -> DeniedView(state)
        }
    }
}

/**
 * 校验中。
 *
 * <p>只转圈不给内容 —— 校验期间露出任何下载入口都等于提前放行。
 */
@Composable
private fun CheckingView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = text(TextKey.DOWNLOAD_CHECKING),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 拒绝页。
 *
 * <p>**按原因分流文案，但每种原因都只有一段话、没有任何按钮。**
 *
 * <p>为什么必须分流：用户明明有正版号，只是微软服务器挂了或自己断网，
 * 却看到「请先登录正版账号」—— 他会以为账号被删了，跑去重新登录，
 * 登录也失败，于是彻底懵。网络问题必须说成网络问题。
 *
 * <p>为什么一个按钮都不给：
 * 「没登录」时该做的是去账号栏登录，重试没用；
 * 「网络问题」时该做的是检查网络或等微软恢复，重试只是反复点同一件事。
 * 真正需要重新校验时，切走再回到本页就会触发（见前面 LaunchedEffect）。
 * 给一个多半无效的按钮，比不给更让人困惑 —— 用户会以为卡住了。
 */
@Composable
private fun DeniedView(state: DownloadState) {
    val denied = state.access as? DownloadAccess.Denied
    val messageKey = when (denied?.reason) {
        DownloadAccess.DenyReason.NETWORK_UNAVAILABLE -> TextKey.DOWNLOAD_NETWORK_UNAVAILABLE
        DownloadAccess.DenyReason.RATE_LIMITED -> TextKey.DOWNLOAD_RATE_LIMITED
        // 其余情况（没账号 / 凭据失效 / 未预期）统一用同一句：
        // 对用户来说结论都一样 —— 现在没有可用正版账号。
        else -> TextKey.DOWNLOAD_REQUIRE_MS_ACCOUNT
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text(messageKey),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
    }
}

/**
 * 放行页：版本列表 + 下载。
 *
 * <p>只有通过微软正版校验的用户能看到这里 —— 版本清单本身就是受
 * EULA 保护的数据，不能提前拉好等着。
 *
 * <p>清单在进入本页时才拉（`LaunchedEffect(Unit)`），而不是在
 * [DownloadCenterScreen] 顶层：拒绝态不该产生任何下载相关的网络请求。
 */
@Composable
private fun AllowedView(
    access: DownloadAccess.Allowed,
    state: DownloadState,
    scope: CoroutineScope,
) {
    // 进入放行页才拉清单。key 用 Unit 是安全的：
    // 这个 composable 只在 access 变成 Allowed 后进入组合，
    // 而 loadVersions() 内部对 Loading 态有幂等保护。
    LaunchedEffect(Unit) {
        state.loadVersions()
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = text(TextKey.DOWNLOAD_TITLE),
            style = MaterialTheme.typography.headlineSmall,
        )
        // 显示账号名，让用户能确认用的是哪个账号下载的。
        Text(
            text = Messages.get(TextKey.DOWNLOAD_ALLOWED_AS, access.playerName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )

        // 下载中 / 下载结果提示
        val installing = state.installing
        val outcome = state.installResult
        when {
            installing != null -> Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Text(
                    text = Messages.get(TextKey.DOWNLOAD_INSTALLING, installing.versionId) +
                        "  " + installing.stage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            outcome is DownloadState.InstallOutcome.Success -> ResultBanner(
                text = Messages.get(TextKey.DOWNLOAD_INSTALL_OK, outcome.versionId),
                isError = false,
                onDismiss = { state.dismissInstallResult() },
            )

            outcome is DownloadState.InstallOutcome.Failed -> ResultBanner(
                text = Messages.get(
                    TextKey.DOWNLOAD_INSTALL_FAILED,
                    outcome.versionId,
                    outcome.reason,
                ),
                isError = true,
                onDismiss = { state.dismissInstallResult() },
            )
        }

        HorizontalDivider()

        // 版本列表
        when (val list = state.listState) {
            is DownloadState.VersionListUi.Idle,
            is DownloadState.VersionListUi.Loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator()
                    Text(
                        text = text(TextKey.DOWNLOAD_LIST_LOADING),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            is DownloadState.VersionListUi.Failed -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = Messages.get(TextKey.DOWNLOAD_LIST_FAILED, list.reason),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.widthIn(max = 560.dp),
                    )
                    OutlinedButton(onClick = { scope.launch { state.loadVersions() } }) {
                        Text(text(TextKey.DOWNLOAD_RELOAD_LIST))
                    }
                }
            }

            is DownloadState.VersionListUi.Loaded -> {
                if (list.versions.isEmpty()) {
                    Text(
                        text = text(TextKey.DOWNLOAD_LIST_EMPTY),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = Messages.get(TextKey.DOWNLOAD_LIST_TITLE, list.versions.size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    DownloadableVersionList(
                        versions = list.versions,
                        installingId = installing?.versionId,
                        enabled = installing == null,
                        onInstall = { version ->
                            scope.launch { state.install(version.id) }
                        },
                    )
                }
            }
        }
    }
}

/**
 * 版本列表。
 *
 * <p>用 LazyColumn 而不是普通 Column：官方版本有几百个，
 * 一次性全部组合出来会明显卡顿。
 */
@Composable
private fun DownloadableVersionList(
    versions: List<MinecraftDownloadService.DownloadableVersion>,
    installingId: String?,
    enabled: Boolean,
    onInstall: (MinecraftDownloadService.DownloadableVersion) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(versions, key = { it.id }) { version ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = version.id,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.width(180.dp),
                )
                Text(
                    text = version.type,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                // 正在下载的那个显示进度，其余显示按钮。
                // 同时只允许一个下载：并发写同一个游戏目录会互相覆盖。
                if (installingId == version.id) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                } else {
                    TextButton(
                        onClick = { onInstall(version) },
                        enabled = enabled,
                    ) {
                        Text(text(TextKey.DOWNLOAD_ACTION))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

/** 结果提示条。 */
@Composable
private fun ResultBanner(
    text: String,
    isError: Boolean,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDismiss) {
            Text(text(TextKey.DOWNLOAD_DISMISS))
        }
    }
}
