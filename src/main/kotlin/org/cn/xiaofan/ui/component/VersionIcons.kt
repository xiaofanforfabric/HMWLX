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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.cn.xiaofan.download.ModLoaderService
import org.jetbrains.skia.Image as SkiaImage

/**
 * 版本图标资源路径。
 *
 * <p>分两类：
 * - **加载器图标**：Fabric / Forge / NeoForge 的官方 LOGO，只在能确定是哪个
 *   加载器时才用
 * - **原版方块**：草方块 / 工作台 / 命令方块 / 基岩，按 MC 版本类型分
 *
 * <p>资源来源（都是各项目官方站点，不是自己画的）：
 * - Fabric：`https://fabricmc.net/assets/logo.png`
 * - Forge：`https://files.minecraftforge.net/static/images/favicon.ico`（转 PNG）
 * - NeoForge：`https://neoforged.net/img/authors/neoforged.png`（官方品牌形象）
 *
 * <p>三个原图都统一裁剪成 128×128（正方形居中、透明补边，不拉伸）。
 */
object VersionIcons {

    const val FABRIC = "/images/icon-fabric.png"
    const val FORGE = "/images/icon-forge.png"
    const val NEOFORGE = "/images/icon-neoforge.png"

    const val RELEASE = "/images/icon-release.png"
    const val SNAPSHOT = "/images/icon-snapshot.png"
    const val APRIL_FOOLS = "/images/icon-aprilfools.png"
    const val OLD = "/images/icon-old.png"

    /**
     * 从已安装版本的**名字**里认出加载器。
     *
     * <h2>为什么靠名字而不是靠 type 字段</h2>
     *
     * 各加载器写进版本 JSON 的 `type` 值不统一（Fabric 写 `fabric`，
     * Forge 写 `release` 或自造值，NeoForge 又不一样），而 `id` 的命名
     * 是有规律的：
     *
     * | 加载器 | 版本目录名 |
     * |---|---|
     * | Fabric | `fabric-loader-0.16.9-1.21.4` |
     * | Forge | `1.21.4-forge-54.0.16` 或 `1.21.4-forge54.0.16` |
     * | NeoForge | `1.21.4-neoforge-21.4.161` 或 `neoforge-21.4.161` |
     *
     * <p>所以扫 id 里的关键字是最可靠的判据。顺序有讲究：
     * **neoforge 必须在 forge 之前判** —— "neoforge" 里包含 "forge"，
     * 反过来判会把 NeoForge 全认成 Forge。
     *
     * @return 对应的图标路径；认不出返回 null（调用方回落到原版方块图标）
     */
    fun loaderIconFor(versionId: String): String? {
        val lower = versionId.lowercase()
        return when {
            // 必须先判 neoforge：它含 "forge" 子串。
            "neoforge" in lower -> NEOFORGE
            "fabric" in lower -> FABRIC
            "forge" in lower -> FORGE
            else -> null
        }
    }

    /** 加载器枚举 -> 图标路径。用于下载页里「用户正选着哪个加载器」。 */
    fun iconFor(loader: ModLoaderService.Loader): String = when (loader) {
        ModLoaderService.Loader.FABRIC -> FABRIC
        ModLoaderService.Loader.FORGE -> FORGE
        ModLoaderService.Loader.NEOFORGE -> NEOFORGE
    }
}

/**
 * 按资源路径加载并显示一个图标。
 *
 * <p>走 ClassLoader 读 classpath 资源，与 `AppBackground` 一致 ——
 * 项目没启用 Compose Resources 代码生成，`painterResource` 用不了。
 *
 * <p>加载失败时**什么都不画**（不占位、不报错）。图标是装饰，
 * 缺了不该影响布局稳定 —— 所以调用方要给固定尺寸的容器。
 *
 * @param resource classpath 路径，如 `/images/icon-fabric.png`
 * @param size 显示尺寸
 * @param filterQuality 缩放质量。像素风图标（Fabric 羊毛、NeoForge 狐狸）
 *   用 [FilterQuality.None] 保持硬边；会缩到 24dp 左右时用 High 更平滑
 */
@Composable
fun VersionIconImage(
    resource: String,
    size: Dp,
    filterQuality: FilterQuality = FilterQuality.High,
) {
    var bitmap by remember(resource) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(resource) {
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                VersionIconAnchor.javaClass
                    .getResourceAsStream(resource)
                    ?.use { SkiaImage.makeFromEncoded(it.readBytes()).toComposeImageBitmap() }
            }.getOrNull()
        }
    }

    val image = bitmap ?: return

    Image(
        bitmap = image,
        contentDescription = null,
        filterQuality = filterQuality,
        modifier = Modifier.size(size),
    )
}

/** 只为拿到 classloader 而存在的锚点。 */
private object VersionIconAnchor
