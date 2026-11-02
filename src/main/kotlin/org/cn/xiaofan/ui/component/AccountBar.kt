package org.cn.xiaofan.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.cn.xiaofan.account.AccountProfile
import org.cn.xiaofan.account.AvatarLoader
import org.cn.xiaofan.account.sourceLabel
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/**
 * 顶部账号栏：显示当前登录的账号。
 *
 * 头像来源由 [AccountProfile.avatar] 决定（见 `AccountResolver`）：
 * 正版走 Mojang 官方接口，离线用内置 Steve 皮肤，皮肤站走对应站点。
 *
 * 头像加载是异步的：加载完成前显示首字母占位，失败也回退到占位符，
 * 不会让界面卡住或出现空白。
 *
 * 整条都可点击：点击进入账号切换界面。用整条而不是只绑头像，
 * 是因为头像只有 36dp，单独点它太小，用户很容易点空。
 *
 * @param onOpenSwitch 点击账号栏时触发，由调用方切换到账号界面
 * @param invalid 当前账号是否已确认失效（登录信息过期/被撤销）。
 *   失效时头像变黑白、名字加红加删除线、并显示「已失效」标志。
 */
@Composable
fun AccountBar(
    profile: AccountProfile,
    modifier: Modifier = Modifier,
    onOpenSwitch: () -> Unit = {},
    invalid: Boolean = false,
) {
    // key 必须与下面的 LaunchedEffect 完全一致，都用 avatarKey（provider + uuid）。
    // 不用 avatar 本身：离线账号的 BuiltInSkin.STEVE 是共用单例，「小凡」与「小明」
    // 的 avatar 相等，换号时 key 不变，上一张头像会留在界面上。
    var avatar by remember(profile.avatarKey) {
        mutableStateOf<ImageBitmap?>(null)
    }

    LaunchedEffect(profile.avatarKey) {
        // 先清空再加载：避免来源切换后新旧图混在一起
        avatar = null
        // 两阶段：缓存命中时立刻显示（onCached），网络拿到新图后再替换
        // （onRefreshed）。否则每次启动都要先闪一下首字母占位再跳成头像。
        AvatarLoader.loadCached(
            profile = profile,
            onCached = { avatar = it },
            onRefreshed = { avatar = it },
        )
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenSwitch)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AccountAvatar(profile = profile, avatar = avatar, invalid = invalid)

            Spacer(Modifier.width(12.dp))

            Box(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = profile.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        // 失效：红色 + 删除线。两者一起用而不是只用一样 ——
                        // 只变色对色弱用户不友好，只加删除线容易被长名字分散注意。
                        color = if (invalid) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        textDecoration = if (invalid) {
                            TextDecoration.LineThrough
                        } else {
                            null
                        },
                    )
                    SourceBadge(profile.sourceLabel())
                    if (invalid) {
                        InvalidBadge()
                    }
                }
            }
        }
    }
}

/**
 * 头像：优先显示加载成功的皮肤头，否则用玩家名首字母占位。
 *
 * <p>`invalid` 时把头像去色：用 `ColorFilter.colorMatrix` 把饱和度降到 0。
 * 不用直接叠一层灰色遮罩 —— 那样会把皮肤细节盖掉，看起来像加载失败。
 */
@Composable
private fun AccountAvatar(profile: AccountProfile, avatar: ImageBitmap?, invalid: Boolean) {
    val size = 36.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(
                width = 1.dp,
                color = if (invalid) {
                    MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                } else {
                    MaterialTheme.colorScheme.outlineVariant
                },
                shape = RoundedCornerShape(6.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (avatar != null) {
            Image(
                bitmap = avatar,
                contentDescription = text(TextKey.ACCOUNT_AVATAR_DESCRIPTION, profile.name),
                modifier = Modifier.size(size),
                // 像素画必须用最近邻，否则会糊
                filterQuality = FilterQuality.None,
                colorFilter = if (invalid) GrayscaleFilter else null,
            )
        } else {
            Text(
                text = profile.name.take(1).uppercase(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/**
 * 去色滤镜：饱和度降为 0。
 *
 * <p>矩阵是标准的 luminance 权重（人眼对绿色最敏感），
 * 不是简单的平均值 —— 用平均值会让红/蓝色头像偏暗。
 */
private val GrayscaleFilter = ColorFilter.colorMatrix(
    ColorMatrix().apply { setToSaturation(0f) },
)

/** 「已失效」标志。用错误色而不是灰色：这是需要用户处理的状态，不是普通信息。 */
@Composable
private fun InvalidBadge() {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = text(TextKey.ACCOUNT_INVALID_BADGE),
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

/**
 * 账号来源标签（正版 / 皮肤站 / 离线）。
 */
@Composable
private fun SourceBadge(label: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}
