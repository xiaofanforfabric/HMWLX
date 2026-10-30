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
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
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
 */
@Composable
fun AccountBar(
    profile: AccountProfile,
    modifier: Modifier = Modifier,
    onOpenSwitch: () -> Unit = {},
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
            AccountAvatar(profile = profile, avatar = avatar)

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
                    )
                    SourceBadge(profile.sourceLabel())
                }
            }
        }
    }
}

/**
 * 头像：优先显示加载成功的皮肤头，否则用玩家名首字母占位。
 */
@Composable
private fun AccountAvatar(profile: AccountProfile, avatar: ImageBitmap?) {
    val size = 36.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
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
