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

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.cn.xiaofan.config.ProxyConfig
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/** 设置页内的子页。 */
enum class SettingsSection {
    /** 空：显示子菜单列表。 */
    MENU,

    /** 网络设置（代理）。 */
    NETWORK,

    /** 开发者设置。 */
    DEVELOPER,

    /** 嘉豪终端。 */
    CLI_TERMINAL,
}

/**
 * 设置页。
 *
 * <p>布局照抄版本页的骨架：左边一列子项，右边是选中子项的内容。
 * 目前只有一个「开发者设置」子项，但结构摆好了，以后加「界面」「下载」
 * 这类分组不用重排。
 *
 * @param debugMode 调试模式当前是否开启
 * @param onDebugModeChange 用户切换开关时回调
 * @param proxyUrl 界面上设置的代理地址，null 表示未设置（走环境变量）
 * @param proxyDisabled 是否禁用代理
 * @param onProxyChange 用户修改代理设置时回调（url, disabled）
 * @param onEnterCli 点击「嘉豪终端 - 进入」时回调
 * @param cliContent 嘉豪终端的界面内容。由调用方提供，因为终端状态
 *   （进程、输出缓冲）不该由设置页持有 —— 用户可能切到别的页面再回来。
 */
@Composable
fun SettingsScreen(
    section: SettingsSection,
    onSectionChange: (SettingsSection) -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    proxyUrl: String?,
    proxyDisabled: Boolean,
    onProxyChange: (url: String?, disabled: Boolean) -> Unit,
    onEnterCli: () -> Unit,
    cliContent: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
    ) {
        when (section) {
            // 终端占满整个设置区：它需要尽可能多的空间显示输出。
            SettingsSection.CLI_TERMINAL -> cliContent()

            else -> Row(modifier = Modifier.fillMaxSize()) {
                // 左：子菜单
                Column(modifier = Modifier.width(200.dp).fillMaxHeight()) {
                    Text(
                        text = text(TextKey.SETTINGS_TITLE),
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.padding(16.dp),
                    )
                    HorizontalDivider()
                    SettingsEntry(
                        label = text(TextKey.SETTINGS_NETWORK_ENTRY),
                        selected = section == SettingsSection.NETWORK,
                        onClick = { onSectionChange(SettingsSection.NETWORK) },
                    )
                    SettingsEntry(
                        label = text(TextKey.SETTINGS_DEV_ENTRY),
                        selected = section == SettingsSection.DEVELOPER,
                        onClick = { onSectionChange(SettingsSection.DEVELOPER) },
                    )
                }

                HorizontalDivider(modifier = Modifier.width(1.dp).fillMaxHeight())

                // 右：子页内容
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    when (section) {
                        SettingsSection.NETWORK -> NetworkSettings(
                            proxyUrl = proxyUrl,
                            proxyDisabled = proxyDisabled,
                            onProxyChange = onProxyChange,
                        )

                        SettingsSection.DEVELOPER -> DeveloperSettings(
                            debugMode = debugMode,
                            onDebugModeChange = onDebugModeChange,
                            onEnterCli = onEnterCli,
                        )

                        // MENU 时右侧留空：子菜单还没选，没有内容可显示。
                        // CLI_TERMINAL 已在上面处理，不会到这里。
                        else -> Unit
                    }
                }
            }
        }
    }
}

/** 侧栏式的一个设置入口。 */
@Composable
private fun SettingsEntry(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        } else {
            MaterialTheme.colorScheme.surface
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

/**
 * 网络设置：代理服务器。
 *
 * <h2>为什么需要显式让用户填</h2>
 *
 * Java **不读** `http_proxy` / `https_proxy` 环境变量（实测
 * `ProxySelector.select()` 返回 `[DIRECT]`）。也就是说，终端里
 * `export https_proxy=...` 之后再启动 HMWLX，**请求依然从真实 IP 发出**。
 *
 * <p>这不是性能问题 —— 微软的账号风控会把「同一账号短时间内从两个
 * 相隔很远的 IP 登录」判为可疑并冻结账号。这就是之前那次「账号被改密码」
 * 的真实原因，不是被盗。
 *
 * <p>所以这里提供两种入口：
 * 1. 环境变量自动继承（不填任何东西，启动时读 `https_proxy` 等）
 * 2. 手动填写（覆盖环境变量）
 *
 * @param proxyUrl 已保存的代理地址；null 表示未设置
 * @param proxyDisabled 是否禁用代理（连环境变量也不用）
 * @param onProxyChange 修改回调
 */
@Composable
private fun NetworkSettings(
    proxyUrl: String?,
    proxyDisabled: Boolean,
    onProxyChange: (url: String?, disabled: Boolean) -> Unit,
) {
    // 输入框的草稿状态：用户边打边改，只有点「保存」才写盘。
    // 每敲一个字符就写配置文件既慢又容易写出半截 URL。
    var draft by remember(proxyUrl) { mutableStateOf(proxyUrl.orEmpty()) }

    // 当前实际生效的代理（含环境变量）。用于展示「你现在走哪儿」。
    // 每次进页面重新解析一次：用户可能刚在终端里改了环境变量。
    val effective = remember(proxyUrl, proxyDisabled) { ProxyConfig.resolve() }
    val draftEntry = remember(draft) {
        if (draft.isBlank()) null else ProxyConfig.parse(draft)
    }
    val draftInvalid = draft.isNotBlank() && draftEntry == null

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(
            text = text(TextKey.SETTINGS_NETWORK_TITLE),
            style = MaterialTheme.typography.headlineSmall,
        )

        // ---- 当前生效状态 ----
        Surface(
            shape = RoundedCornerShape(6.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().widthIn(max = 620.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = text(TextKey.SETTINGS_NETWORK_CURRENT),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = when {
                        effective != null ->
                            "${effective.display}（${text(effective.source.labelKey())}）"

                        proxyDisabled -> text(TextKey.SETTINGS_PROXY_DIRECT_DISABLED)

                        else -> text(TextKey.SETTINGS_PROXY_DIRECT_NO_ENV)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (effective != null) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }

        // ---- 代理地址 ----
        Column(modifier = Modifier.fillMaxWidth().widthIn(max = 620.dp)) {
            Text(
                text = text(TextKey.SETTINGS_PROXY_URL),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = text(TextKey.SETTINGS_PROXY_URL_DESC),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )

            OutlinedTextField(
                value = draft,
                onValueChange = { draft = it },
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                singleLine = true,
                isError = draftInvalid,
                enabled = !proxyDisabled,
                placeholder = {
                    Text(
                        text = text(TextKey.SETTINGS_PROXY_URL_HINT),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                supportingText = if (draftInvalid) {
                    {
                        Text(
                            text = text(TextKey.SETTINGS_PROXY_URL_INVALID),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                } else {
                    null
                },
            )

            // 保存按钮：只在草稿有效且与已保存值不同时可用。
            if (draft.trim() != proxyUrl.orEmpty().trim()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            draft = proxyUrl.orEmpty()
                        },
                    ) {
                        Text(text(TextKey.SETTINGS_PROXY_RESET))
                    }
                    Button(
                        onClick = { onProxyChange(draft.trim().ifBlank { null }, proxyDisabled) },
                        enabled = !draftInvalid,
                    ) {
                        Text(text(TextKey.SETTINGS_PROXY_SAVE))
                    }
                }
            }
        }

        HorizontalDivider(modifier = Modifier.widthIn(max = 620.dp))

        // ---- 禁用开关 ----
        Row(
            modifier = Modifier.fillMaxWidth().widthIn(max = 620.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text(TextKey.SETTINGS_PROXY_DISABLE),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = text(TextKey.SETTINGS_PROXY_DISABLE_DESC),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Switch(
                checked = proxyDisabled,
                onCheckedChange = { onProxyChange(proxyUrl, it) },
            )
        }

        // ---- 风险提示 ----
        Text(
            text = text(TextKey.SETTINGS_PROXY_SECURITY),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.widthIn(max = 620.dp),
        )

        Text(
            text = text(TextKey.SETTINGS_PROXY_RESTART),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** 代理来源对应的文案键。 */
private fun ProxyConfig.Source.labelKey(): String = when (this) {
    ProxyConfig.Source.CONFIG -> TextKey.SETTINGS_PROXY_SOURCE_CONFIG
    ProxyConfig.Source.ENVIRONMENT -> TextKey.SETTINGS_PROXY_SOURCE_ENV
    ProxyConfig.Source.DIRECT -> TextKey.SETTINGS_PROXY_SOURCE_DIRECT
}

/**
 * 开发者设置。
 *
 * <p>两项：调试模式开关、嘉豪终端入口。
 *
 * <p>两项都带**醒目的风险说明**：调试模式会让运行变慢，
 * 终端能直接操作上游 CLI（可能改坏东西）。这不是形式主义 ——
 * 不写清楚的话，用户点进去出问题会以为是软件 bug。
 */
@Composable
private fun DeveloperSettings(
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    onEnterCli: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = text(TextKey.SETTINGS_DEV_TITLE),
            style = MaterialTheme.typography.headlineSmall,
        )

        // ---- 调试模式 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text(TextKey.SETTINGS_DEBUG_MODE),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = text(TextKey.SETTINGS_DEBUG_MODE_DESC),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.widthIn(max = 520.dp).padding(top = 6.dp),
                )
                Text(
                    text = text(TextKey.SETTINGS_DEBUG_MODE_RESTART),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Switch(
                checked = debugMode,
                onCheckedChange = onDebugModeChange,
            )
        }

        HorizontalDivider()

        // ---- 嘉豪终端 ----
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = text(TextKey.SETTINGS_CLI_TERMINAL),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = text(TextKey.SETTINGS_CLI_TERMINAL_DESC),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.widthIn(max = 520.dp).padding(top = 6.dp),
                )
            }
            Button(onClick = onEnterCli) {
                Text(text(TextKey.SETTINGS_CLI_ENTER))
            }
        }
    }
}
