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

package org.cn.xiaofan.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.cn.xiaofan.config.GameLaunchSettings
import org.cn.xiaofan.config.GameLaunchSettingsStore
import org.cn.xiaofan.config.PhysicalMemory
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/**
 * 单个游戏版本的启动设置面板：内存、进程优先级、自定义 JVM 参数。
 *
 * <h2>为什么用「不勾选 = 不干预」</h2>
 *
 * 内存用复选框控制是否覆盖，而不是直接给个滑块默认 2048。
 * 理由：一旦有默认值写进配置，用户就分不清「这个 2G 是我设的还是启动器设的」，
 * 出了问题也无从排查。勾选代表「我明确要这个值」，不勾选代表「交给 JVM」，
 * 语义干净。
 *
 * @param versionId 版本号，用于读写存储
 * @param enabled 弹窗正在安装时为 false，避免改动与启动竞争
 */
@Composable
fun GameLaunchSettingsPanel(
    versionId: String,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    // 物理内存只在进入时探测一次：它是常量，每帧重算没有意义。
    val physicalMb = remember { PhysicalMemory.detectMb() }

    // 上限按物理内存的 80% 封顶，至少给到 512MB —— 否则在探测失败
    // （physicalMb = 0）时上限会变成 0，滑块整个不可用。
    val ceilingMb = remember(physicalMb) {
        if (physicalMb > 0) {
            (physicalMb * GameLaunchSettings.MAX_MEMORY_RATIO)
                .toInt()
                .coerceIn(512, GameLaunchSettings.MAX_MEMORY_MB)
        } else {
            GameLaunchSettings.MAX_MEMORY_MB
        }
    }

    var settings by remember(versionId) {
        mutableStateOf(GameLaunchSettingsStore.load(versionId))
    }

    /** 落盘：先把文本态解析回设置，规整后保存。 */
    fun persist(next: GameLaunchSettings) {
        val normalized = next.normalized(physicalMb)
        settings = normalized
        GameLaunchSettingsStore.save(versionId, normalized)
    }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = text(TextKey.LAUNCH_SETTINGS_TITLE),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = Messages.get(
                TextKey.LAUNCH_SETTINGS_SCOPE,
                versionId,
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---- 内存 ----
        MemoryOverrideRow(
            label = text(TextKey.LAUNCH_SETTINGS_MAX_MEMORY),
            checked = settings.maxMemoryMb != null,
            enabled = enabled,
            onCheckedChange = { checked ->
                val value = if (checked) {
                    // 默认给物理内存的一半，但不低于 1024 —— 这是绝大多数
                    // 模组整合包的合理起点，比给 512 再让用户往上拖好用。
                    (ceilingMb / 2).coerceAtLeast(1024).coerceAtMost(ceilingMb)
                } else {
                    null
                }
                persist(settings.copy(maxMemoryMb = value))
            },
        )
        if (settings.maxMemoryMb != null) {
            MemorySlider(
                value = settings.maxMemoryMb ?: 1024,
                ceilingMb = ceilingMb,
                enabled = enabled,
                onValueChange = { v ->
                    // 把最大内存往下拖时，初始内存要跟着降 ——
                    // 否则会出现 min > max 的非法组合，虽然 normalized()
                    // 会兜住，但用户看到的是「我明明拖小了，另一个却没变」。
                    val nextMin = settings.minMemoryMb?.let { m ->
                        if (m > v - STEP_MB) (v - STEP_MB).coerceAtLeast(GameLaunchSettings.MIN_MEMORY_MB)
                        else m
                    }
                    persist(settings.copy(maxMemoryMb = v, minMemoryMb = nextMin))
                },
            )
        }

        MemoryOverrideRow(
            label = text(TextKey.LAUNCH_SETTINGS_MIN_MEMORY),
            checked = settings.minMemoryMb != null,
            enabled = enabled,
            onCheckedChange = { checked ->
                val value = if (checked) {
                    // 默认取最大内存的一半 —— Xms == Xmx 意味着启动时就要
                    // 预留全部堆内存，机器上同时跑别的东西会立刻吃紧。
                    // 一半是绝大多数启动器的默认做法。
                    val upper = settings.maxMemoryMb ?: ceilingMb
                    (upper / 2).coerceAtLeast(GameLaunchSettings.MIN_MEMORY_MB)
                } else {
                    null
                }
                persist(settings.copy(minMemoryMb = value))
            },
        )
        if (settings.minMemoryMb != null) {
            // 上限是「最大内存 - 一个步长」，而不是「最大内存」。
            //
            // 允许两者相等会带来一个静默的坑：Xms == Xmx 时 JVM 启动即
            // 承诺分配这么多堆，某些系统上会直接因申请不到连续内存而
            // 启动失败，而失败信息（"Could not reserve enough space"）
            // 在几百行日志里极难被发现。留出一点余量更稳妥。
            val minCeiling = ((settings.maxMemoryMb ?: ceilingMb) - STEP_MB)
                .coerceAtLeast(GameLaunchSettings.MIN_MEMORY_MB)
            MemorySlider(
                value = settings.minMemoryMb ?: GameLaunchSettings.MIN_MEMORY_MB,
                ceilingMb = minCeiling,
                enabled = enabled,
                onValueChange = { v ->
                    persist(settings.copy(minMemoryMb = v))
                },
            )
        }

        // 物理内存提示：让用户知道上限是怎么来的。
        // 探测失败（0）就明确说未知，不要编一个数字。
        Text(
            text = if (physicalMb > 0) {
                Messages.get(TextKey.LAUNCH_SETTINGS_MEMORY_HINT, physicalMb, ceilingMb)
            } else {
                text(TextKey.LAUNCH_SETTINGS_MEMORY_UNKNOWN)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ---- 进程优先级 ----
        Text(
            text = text(TextKey.LAUNCH_SETTINGS_PRIORITY),
            style = MaterialTheme.typography.bodyMedium,
        )
        PrioritySelector(
            priority = settings.priority,
            enabled = enabled,
            onSelect = { p -> persist(settings.copy(priority = p)) },
        )
        Text(
            text = text(TextKey.LAUNCH_SETTINGS_PRIORITY_HINT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

        // ---- 自定义 JVM 参数 ----
        Text(
            text = text(TextKey.LAUNCH_SETTINGS_JVM_ARGS),
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = settings.jvmArgs,
            onValueChange = { v -> persist(settings.copy(jvmArgs = v)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall,
            placeholder = {
                Text(
                    text = text(TextKey.LAUNCH_SETTINGS_JVM_ARGS_PLACEHOLDER),
                    style = MaterialTheme.typography.bodySmall,
                )
            },
            minLines = 2,
            maxLines = 4,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
        )
        Text(
            text = text(TextKey.LAUNCH_SETTINGS_JVM_ARGS_HINT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )

        if (settings.maxMemoryMb != null || settings.minMemoryMb != null) {
            TextButton(
                onClick = {
                    persist(settings.copy(maxMemoryMb = null, minMemoryMb = null))
                },
                enabled = enabled,
            ) {
                Text(text(TextKey.LAUNCH_SETTINGS_RESET_MEMORY))
            }
        }
    }
}

/**
 * 「覆盖某项内存设置」的勾选行。
 *
 * @param checked 是否覆盖；false 表示交给 JVM 默认值
 */
@Composable
private fun MemoryOverrideRow(
    label: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * 内存滑块。
 *
 * <p>步长 128MB：1MB 精度对内存来说毫无意义，反而让滑块难以精确停在整数上。
 * 128 是 MC 社区里最常用的档位粒度（1024/2048/3072…）。
 */
@Composable
private fun MemorySlider(
    value: Int,
    ceilingMb: Int,
    enabled: Boolean,
    onValueChange: (Int) -> Unit,
) {
    val floor = GameLaunchSettings.MIN_MEMORY_MB
    val max = ceilingMb.coerceAtLeast(floor)

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Slider(
                value = value.coerceIn(floor, max).toFloat(),
                onValueChange = { v ->
                    // 量化到 128MB 的整数倍，且至少是下限。
                    val snapped = ((v / STEP_MB).toInt() * STEP_MB)
                        .coerceIn(floor, max)
                    onValueChange(snapped)
                },
                valueRange = floor.toFloat()..max.toFloat(),
                enabled = enabled,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${value}MB",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(72.dp),
            )
        }
    }
}

/**
 * 优先级选择：三档预设，而不是 40 级的滑块。
 *
 * <p>nice 的语义对普通玩家太抽象（数字越小优先级越高，还能是负数），
 * 给一个 -20..19 的滑块只会让人一脸问号。三个有明确意图的档位
 * 覆盖了 99% 的真实需求。
 */
@Composable
private fun PrioritySelector(
    priority: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    val options = listOf(
        PriorityOption(TextKey.LAUNCH_SETTINGS_PRIORITY_LOW, 10),
        PriorityOption(TextKey.LAUNCH_SETTINGS_PRIORITY_NORMAL, 0),
        PriorityOption(TextKey.LAUNCH_SETTINGS_PRIORITY_HIGH, -5),
    )

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            val selected = priority == option.value
            TextButton(
                onClick = { onSelect(option.value) },
                enabled = enabled,
                modifier = Modifier.border(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    },
                    shape = RoundedCornerShape(6.dp),
                ).background(
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    RoundedCornerShape(6.dp),
                ),
            ) {
                Text(
                    text = text(option.labelKey),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** 优先级预设项。 */
private data class PriorityOption(val labelKey: String, val value: Int)

/** 内存滑块步长（MB）。 */
private const val STEP_MB = 128

/**
 * 启动设置弹窗：把 [GameLaunchSettingsPanel] 包进对话框。
 *
 * <p>设置随改随存，所以**没有确认/取消按钮** —— 加了反而让人以为
 * 不点确认就不生效。只有一个「关闭」。
 *
 * @param versionId 正在编辑的版本
 * @param onDismiss 关闭回调
 */
@Composable
fun GameLaunchSettingsDialog(
    versionId: String,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(min = 420.dp, max = 560.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = text(TextKey.LAUNCH_SETTINGS_TITLE),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(text = "关闭")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                // 内容可能超过屏幕高度（JVM 参数输入框展开后最高 4 行），
                // 用可滚动容器兜住，否则小屏幕上底部的关闭按钮会跑出窗口。
                //
                // 用 verticalScroll 而不是 LazyColumn：这里的子项是一个
                // 高度可变的普通 Column，不是列表。塞进 LazyColumn 的
                // item 里会引入测量约束问题 —— 首项被裁掉一部分，
                // 而且滑动行为也不自然。
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                ) {
                    GameLaunchSettingsPanel(
                        versionId = versionId,
                        enabled = true,
                    )
                }
            }
        }
    }
}
