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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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

    // 进弹窗即查。key 用 versionId：关掉再打开同一版本时，
    // DownloadState 的缓存会让 loadLoaderSupport 立刻返回。
    LaunchedEffect(versionId) {
        state.loadLoaderSupport(versionId)
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
                        supportUi.supports.forEach { support ->
                            when (support) {
                                is ModLoaderService.Support.Available -> LoaderOption(
                                    label = "${support.loader.displayName}" +
                                        "  (${support.builds.size})",
                                    selected = selectedLoader == support.loader,
                                    enabled = !busy,
                                    onSelect = {
                                        selectedLoader = support.loader
                                        // 默认选最新构建：绝大多数用户不会去挑旧版本，
                                        // 让他们少点一次。
                                        selectedBuild = support.builds.firstOrNull()
                                    },
                                )

                                is ModLoaderService.Support.Unsupported -> LoaderUnavailable(
                                    // 「不支持」是正常结果，不是错误 —— 用弱化色而不是红色。
                                    label = Messages.get(
                                        TextKey.DOWNLOAD_LOADER_UNSUPPORTED,
                                        support.loader.displayName,
                                        versionId,
                                    ),
                                    isError = false,
                                )

                                is ModLoaderService.Support.Failed -> {
                                    LoaderUnavailable(
                                        label = Messages.get(
                                            TextKey.DOWNLOAD_LOADER_FAILED,
                                            support.loader.displayName,
                                            support.reason,
                                        ),
                                        isError = true,
                                    )
                                    TextButton(
                                        onClick = { state.invalidateLoaderSupport(versionId) },
                                        enabled = !busy,
                                    ) {
                                        Text(text(TextKey.DOWNLOAD_LOADER_RETRY))
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

/** 单选项：安装方式的一行。 */
@Composable
private fun LoaderOption(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = onSelect,
            enabled = enabled,
        )
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
