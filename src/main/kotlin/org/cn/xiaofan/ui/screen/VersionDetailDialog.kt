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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.cn.xiaofan.download.DownloadState
import org.cn.xiaofan.download.ModLoaderService
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text
import org.cn.xiaofan.ui.component.GameLaunchSettingsPanel
import org.cn.xiaofan.ui.component.VersionIconImage
import org.cn.xiaofan.ui.component.VersionIcons

/**
 * 版本详情弹窗：选安装方式（原版 / Fabric / Forge / NeoForge）+ 加载器构建。
 *
 * <h2>为什么是弹窗</h2>
 *
 * 详情页的目标单一 —— 选一个组合然后下载。页内切换会让用户失去
 * 「我在哪个版本上」的上下文（列表看不见了）。全屏弹窗把注意力集中在
 * 一件事上，关掉就回到列表。
 *
 * <h2>加载器选项必须按具体版本查</h2>
 *
 * **不能**按「正式版 / 快照」页签决定：远古版三个加载器全都不支持，
 * 而「最新快照」可能只有 Fabric 跟上。进弹窗就并行查三个，
 * 查完之前该项显示 loading、不可选。
 *
 * @param versionId 要安装的 MC 版本号
 * @param state 持有加载器缓存与安装状态
 * @param onDismiss 关闭弹窗
 */
@Composable
fun VersionDetailDialog(
    versionId: String,
    state: DownloadState,
    onDismiss: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    // 进弹窗即查。
    //
    // key 用 versionId + token：
    //   - versionId 变化 = 换了个版本，当然要重查
    //   - token 变化 = 用户点了「重新查询」，缓存已被清掉但 effect 的 key
    //     没变就不重跑（这正是重试无效的原因，见 DownloadState.retryLoaderSupport）
    LaunchedEffect(versionId, state.loaderSupportToken) {
        state.loadLoaderSupport(versionId)
        // 主查询完成后，再查邻近版本 —— 只为「不支持」的加载器服务，
        // 目的是告诉用户「那该用哪个版本」。
        state.loadNearbySupport(versionId)
    }

    // 安装成功后自动关闭弹窗 —— 用户在弹窗里等下载，装完还留着没意义。
    LaunchedEffect(state.installResult) {
        if (state.installResult is DownloadState.InstallOutcome.Success) {
            onDismiss()
        }
    }

    // 选中的加载器。null = 原版。
    var selectedLoader by remember(versionId) {
        mutableStateOf<ModLoaderService.Loader?>(null)
    }
    // 选中的构建标识。切换加载器时要重置。
    var selectedBuild by remember(versionId) {
        mutableStateOf<String?>(null)
    }
    // 是否展开「启动设置」区域。
    var showLaunchSettings by remember(versionId) { mutableStateOf(false) }

    val supportUi = state.loaderSupport[versionId]
    val installing = state.installing
    val busy = installing != null

    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().padding(48.dp),
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
                // ---- 顶栏 ----
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = Messages.get(TextKey.DOWNLOAD_DETAIL_TITLE, versionId),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = onDismiss, enabled = !busy) {
                        Text(text(TextKey.DOWNLOAD_DETAIL_CLOSE))
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // ---- 安装方式 ----
                Text(
                    text = text(TextKey.DOWNLOAD_CHOOSE_LOADER),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )

                LoaderOption(
                    label = text(TextKey.DOWNLOAD_LOADER_VANILLA),
                    selected = selectedLoader == null,
                    enabled = !busy,
                    // 原版用草方块：与版本列表里的原版图标一致。
                    // 不填图标的话这一行会比下面几行短一截，视觉上参差不齐。
                    icon = VersionIcons.RELEASE,
                    onSelect = {
                        selectedLoader = null
                        selectedBuild = null
                    },
                )

                when (supportUi) {
                    null, DownloadState.LoaderSupportUi.Loading -> Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp))
                        Text(
                            text = text(TextKey.DOWNLOAD_LOADER_LOADING),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    is DownloadState.LoaderSupportUi.Loaded -> {
                        // 三个加载器**全部**失败时，几乎一定是本地网络问题
                        // （DNS 污染 / 出口被墙 / 代理没生效），而不是
                        // 「这三个加载器都不支持此版本」。
                        //
                        // 这两种情况在界面上必须区分开：
                        // - 全部失败 -> 一条网络故障提示 + 一次重试，别刷三条错误
                        // - 部分失败 -> 逐条显示，让用户知道哪个源不通
                        val allFailed = supportUi.supports.all {
                            it is ModLoaderService.Support.Failed
                        }

                        if (allFailed) {
                            val firstReason = supportUi.supports
                                .filterIsInstance<ModLoaderService.Support.Failed>()
                                .firstOrNull()?.reason
                                .orEmpty()

                            LoaderUnavailable(
                                label = Messages.get(TextKey.DOWNLOAD_LOADERS_ALL_FAILED, firstReason),
                                isError = true,
                            )
                            Text(
                                text = text(TextKey.DOWNLOAD_LOADERS_ALL_FAILED_HINT),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 12.dp, top = 2.dp),
                            )
                            TextButton(
                                onClick = { state.retryLoaderSupport(versionId) },
                                enabled = !busy,
                            ) {
                                Text(text(TextKey.DOWNLOAD_LOADER_RETRY))
                            }
                        } else {
                            supportUi.supports.forEach { support ->
                                when (support) {
                                    is ModLoaderService.Support.Available -> LoaderOption(
                                        label = "${support.loader.displayName}" +
                                            "  (${support.builds.size})",
                                        selected = selectedLoader == support.loader,
                                        enabled = !busy,
                                        icon = VersionIcons.iconFor(support.loader),
                                        onSelect = {
                                            selectedLoader = support.loader
                                            // 默认选最新构建：绝大多数用户不会去挑旧版本，
                                            // 让他们少点一次。
                                            selectedBuild = support.builds.firstOrNull()
                                        },
                                    )

                                    is ModLoaderService.Support.Unsupported -> {
                                        // 「不支持」是正常结果，不是错误 —— 用弱化色而不是红色。
                                        //
                                        // 但光说「不支持」没有用 —— 用户接下来要问的是
                                        // 「那我该用哪个」。所以邻近版本查到什么就一并展示。
                                        val nearby = state.nearbySupport[versionId]
                                            ?.get(support.loader)

                                        LoaderUnavailable(
                                            label = if (nearby != null) {
                                                Messages.get(
                                                    TextKey.DOWNLOAD_LOADER_UNSUPPORTED_WITH_NEARBY,
                                                    support.loader.displayName,
                                                    versionId,
                                                    nearby,
                                                )
                                            } else {
                                                Messages.get(
                                                    TextKey.DOWNLOAD_LOADER_UNSUPPORTED,
                                                    support.loader.displayName,
                                                    versionId,
                                                )
                                            },
                                            isError = false,
                                        )
                                    }

                                    is ModLoaderService.Support.Failed -> {
                                        // 部分失败：只列一次重试按钮，不每个加载器都给一个 ——
                                        // 三个红字加三个重试按钮太吵。
                                        if (support == supportUi.supports
                                                .filterIsInstance<ModLoaderService.Support.Failed>()
                                                .first()
                                        ) {
                                            LoaderUnavailable(
                                                label = Messages.get(
                                                    TextKey.DOWNLOAD_LOADER_FAILED,
                                                    support.loader.displayName,
                                                    support.reason,
                                                ),
                                                isError = true,
                                            )
                                            TextButton(
                                                onClick = { state.retryLoaderSupport(versionId) },
                                                enabled = !busy,
                                            ) {
                                                Text(text(TextKey.DOWNLOAD_LOADER_RETRY))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ---- 加载器版本 ----
                val chosenBuilds = (supportUi as? DownloadState.LoaderSupportUi.Loaded)
                    ?.supports
                    ?.filterIsInstance<ModLoaderService.Support.Available>()
                    ?.firstOrNull { it.loader == selectedLoader }
                    ?.builds
                    .orEmpty()

                // 高度分配说明：
                // 上面「安装方式」区域高度随查询结果变化（3 行 or 5 行），
                // 下面构建列表必须占剩余空间并自己滚动 —— 否则 Fabric 那 255 个
                // 构建会把下载按钮顶出屏幕。
                if (selectedLoader != null && chosenBuilds.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                    Text(
                        text = text(TextKey.DOWNLOAD_CHOOSE_BUILD),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(6.dp),
                            ),
                    ) {
                        items(chosenBuilds) { build ->
                            val isLatest = build == chosenBuilds.first()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = !busy) { selectedBuild = build }
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                RadioButton(
                                    selected = selectedBuild == build,
                                    onClick = { selectedBuild = build },
                                    enabled = !busy,
                                )
                                Text(
                                    text = build,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (isLatest) {
                                    Text(
                                        text = text(TextKey.DOWNLOAD_LOADER_LATEST),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    // 没选加载器时给个占位，让 Column 的高度分配稳定，
                    // 避免选中加载器的瞬间布局跳动。
                    Box(modifier = Modifier.weight(1f).fillMaxWidth())
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // ---- 启动设置（默认折叠） ----
                //
                // 折叠的理由：这个弹窗的主任务是「选个加载器然后下载」，
                // 内存/JVM 参数是只有一部分用户才需要改的次要项。
                // 一直展开会把构建列表和下载按钮挤下去，主次颠倒。
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { showLaunchSettings = !showLaunchSettings }
                        .heightIn(min = 36.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (showLaunchSettings) "▾ " else "▸ ",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = text(TextKey.LAUNCH_SETTINGS_TITLE),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (showLaunchSettings) {
                    // 限制最大高度并自己滚动：JVM 参数输入框展开后
                    // 整个面板能超过屏幕，不滚动就会把底部按钮顶出去。
                    //
                    // 用 verticalScroll 而不是 LazyColumn：里面是一个
                    // 高度可变的普通 Column，不是列表；塞进 LazyColumn 的
                    // item 会引入测量约束问题（首行被裁）。
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .border(
                                1.dp,
                                MaterialTheme.colorScheme.outlineVariant,
                                RoundedCornerShape(6.dp),
                            )
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                    ) {
                        GameLaunchSettingsPanel(
                            versionId = versionId,
                            enabled = !busy,
                        )
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                // ---- 底部：进度 / 下载按钮 ----
                if (busy) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text(
                            text = "${installing.label}  ${installing.stage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        OutlinedButton(onClick = onDismiss) {
                            Text(text(TextKey.DOWNLOAD_DETAIL_CLOSE))
                        }
                        Box(modifier = Modifier.width(8.dp))
                        OutlinedButton(
                            onClick = {
                                // 不关弹窗：用户要看进度和结果。
                                // 安装成功后由上面的 LaunchedEffect 自动关闭。
                                scope.launch {
                                    state.installWith(versionId, selectedLoader, selectedBuild)
                                }
                            },
                            // 选了加载器就必须有构建 —— 否则上游会自己挑最新，
                            // 但那样用户不知道自己装了什么。
                            enabled = selectedLoader == null || selectedBuild != null,
                        ) {
                            Text(text(TextKey.DOWNLOAD_ACTION))
                        }
                    }
                }
            }
        }
    }
}

/** 单选项：安装方式的一行。
 *
 * @param icon 图标资源路径；null 表示不显示（原版没有品牌图标）
 */
@Composable
private fun LoaderOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    icon: String? = null,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .heightIn(min = 40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
        )
        // 图标尺寸 20dp 而不是 24：单选项本身高度就小，
        // 图标太大反而会把文字挤下去。
        if (icon != null) {
            VersionIconImage(
                resource = icon,
                size = 20.dp,
                filterQuality = FilterQuality.High,
            )
            Spacer(Modifier.width(8.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/** 不可用的加载器：置灰一行说明。 */
@Composable
private fun LoaderUnavailable(label: String, isError: Boolean) {
    Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (isError) {
                    MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                } else {
                    MaterialTheme.colorScheme.surface
                },
            )
            .padding(start = 12.dp, top = 4.dp, bottom = 4.dp),
    )
}
