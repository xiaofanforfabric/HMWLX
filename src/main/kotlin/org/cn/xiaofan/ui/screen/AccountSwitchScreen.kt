package org.cn.xiaofan.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.cn.xiaofan.account.AccountProfile
import org.cn.xiaofan.account.AccountService
import org.cn.xiaofan.account.AvatarLoader
import org.cn.xiaofan.account.sourceLabel
import org.cn.xiaofan.i18n.Messages
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/**
 * 账号切换界面。
 *
 * <p>布局：左侧是账号列表（头像 + 用户名 + 类型标签），右侧是三个登录入口。
 * 点击左侧任意一行即切换当前账号；右侧按钮用于新增账号。
 *
 * <p>已实现离线账号与微软正版登录（设备码流程）。Yggdrasil 外置登录还没接，
 * 按钮保持置灰，避免点了没反应。
 *
 * <p>新增账号的收尾统一走 [onAccountAdded]：刷新列表、切到新账号、通知外层，
 * 三条路径（离线、微软、以后的 Yggdrasil）共用一份逻辑，不会再出现
 * 「登录成功了但列表没变」。
 *
 * @param current 当前使用的账号，用于高亮选中项
 * @param onSwitched 切换成功后的回调，由调用方刷新主界面
 * @param onClose 关闭本界面
 */
@Composable
fun AccountSwitchScreen(
    current: AccountProfile,
    onSwitched: (AccountProfile) -> Unit,
    onClose: () -> Unit,
) {
    var groups by remember { mutableStateOf(emptyMap<AccountService.AccountGroup, List<AccountProfile>>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showAddOffline by remember { mutableStateOf(false) }
    var showMicrosoftLogin by remember { mutableStateOf(false) }
    // 切换中：切号要写 headlessmc 的 lastUsedAccount 落盘，期间再点一次列表
    // 会并发写同一个文件，结果是两边都不生效。干脆挡住重复点击。
    var switching by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /**
     * 新增账号（离线 / 微软）之后的收尾：刷新列表 → 把新账号设为当前 → 通知外面。
     *
     * 微软那边尤其不能省这一步：登录成功后 [MicrosoftLoginDialog] 只回调一行账号信息，
     * 账号列表还是进界面时读的那份快照，用户会看到「登录成功了但列表里没有」。
     */
    fun onAccountAdded(created: AccountProfile, close: () -> Unit) {
        close()
        scope.launch {
            if (AccountService.switchTo(created)) {
                // 先切号再读列表：切换会写 lastUsedAccount 落盘，
                // 顺序反过来的话列表里「当前」标记可能落后一拍。
                groups = AccountService.groupedAccounts()
                onSwitched(created)
            } else {
                // 切换失败也要刷新：账号已经写进存储了，列表得如实显示
                groups = AccountService.groupedAccounts()
                error = Messages.get(TextKey.ACCOUNT_SWITCH_FAILED, created.name)
            }
        }
    }

    // 读账号列表要读 headlessmc 的存储文件，交给 AccountService 自己切到 IO。
    LaunchedEffect(Unit) {
        groups = AccountService.groupedAccounts()
        loading = false
    }

    /** 切到指定账号；成功时回调出去，失败把原因留在界面上。 */
    fun switchTo(profile: AccountProfile, onSuccess: () -> Unit) {
        if (switching) return
        switching = true
        error = null
        scope.launch {
            if (AccountService.switchTo(profile)) {
                onSuccess()
            } else {
                error = Messages.get(TextKey.ACCOUNT_SWITCH_FAILED, profile.name)
            }
            switching = false
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenHeader(onClose = onClose)
            HorizontalDivider()

            Row(modifier = Modifier.fillMaxSize().padding(24.dp)) {
                // 左侧：账号列表
                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    when {
                        loading -> CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                        )

                        else -> AccountList(
                            groups = groups,
                            current = current,
                            enabled = !switching,
                            onSelect = { profile -> switchTo(profile) { onSwitched(profile) } },
                        )
                    }
                }

                Spacer(Modifier.width(24.dp))
                HorizontalDivider(modifier = Modifier.width(1.dp).fillMaxSize())
                Spacer(Modifier.width(24.dp))

                // 右侧：登录入口
                Column(modifier = Modifier.width(240.dp)) {
                    Text(
                        text = text(TextKey.ACCOUNT_LOGIN_TITLE),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(12.dp))

                    Button(
                        onClick = { showMicrosoftLogin = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text(TextKey.ACCOUNT_LOGIN_MICROSOFT))
                    }
                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { showAddOffline = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text(TextKey.ACCOUNT_LOGIN_OFFLINE))
                    }
                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = { },
                        enabled = false,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(text(TextKey.ACCOUNT_LOGIN_YGGDRASIL))
                    }

                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (showAddOffline) {
        // 新建后直接切过去：用户点了「添加离线账号」就是打算用它，
        // 建完还得再点一次列表太啰嗦。
        AddOfflineAccountDialog(
            onCreated = { created -> onAccountAdded(created) { showAddOffline = false } },
            onDismiss = { showAddOffline = false },
        )
    }

    if (showMicrosoftLogin) {
        MicrosoftLoginDialog(
            onLoggedIn = { loggedIn -> onAccountAdded(loggedIn) { showMicrosoftLogin = false } },
            // 这里的 onDismiss 只负责关闭。别顺手塞切号/刷新：它也会在用户点关闭时
            // 触发，那时光是关个窗，不该产生任何副作用。
            onDismiss = { showMicrosoftLogin = false },
        )
    }
}

@Composable
private fun ScreenHeader(onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text(TextKey.ACCOUNT_SWITCH_TITLE),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        OutlinedButton(onClick = onClose) {
            Text(text(TextKey.ACCOUNT_SWITCH_BACK))
        }
    }
}

@Composable
private fun AccountList(
    groups: Map<AccountService.AccountGroup, List<AccountProfile>>,
    current: AccountProfile,
    enabled: Boolean,
    onSelect: (AccountProfile) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        AccountService.AccountGroup.entries.forEach { group ->
            val accounts = groups[group].orEmpty()

            item(key = "header-${group.name}") {
                GroupHeader(group = group, count = accounts.size)
            }

            if (accounts.isEmpty()) {
                item(key = "empty-${group.name}") {
                    Text(
                        text = text(TextKey.ACCOUNT_GROUP_EMPTY),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp, bottom = 12.dp),
                    )
                }
            } else {
                items(accounts, key = { "${it.provider}:${it.name}" }) { profile ->
                    AccountRow(
                        profile = profile,
                        selected = profile.name == current.name && profile.provider == current.provider,
                        enabled = enabled,
                        onClick = { onSelect(profile) },
                    )
                }
            }
        }
    }
}

@Composable
private fun GroupHeader(group: AccountService.AccountGroup, count: Int) {
    Text(
        text = "${groupLabel(group)} ($count)",
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 8.dp),
    )
}

@Composable
private fun AccountRow(
    profile: AccountProfile,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowAvatar(profile)
        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.name,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = profile.sourceLabel(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (selected) {
            Text(
                text = text(TextKey.ACCOUNT_CURRENT),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun RowAvatar(profile: AccountProfile) {
    // key 用 avatarKey（provider + uuid），不用 avatar：后者对离线账号是共用单例，
    // 换了账号 key 却不变，这一行复用时会把上一个账号的头像留在原地。
    var avatar by remember(profile.avatarKey) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(profile.avatarKey) {
        avatar = null
        avatar = AvatarLoader.load(profile.avatar)
    }

    val size = 32.dp
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = avatar
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = text(TextKey.ACCOUNT_AVATAR_DESCRIPTION, profile.name),
                modifier = Modifier.size(size),
                filterQuality = FilterQuality.None,
            )
        } else {
            Text(
                text = profile.name.take(1).uppercase(),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun groupLabel(group: AccountService.AccountGroup): String = when (group) {
    AccountService.AccountGroup.MICROSOFT -> text(TextKey.ACCOUNT_GROUP_MICROSOFT)
    AccountService.AccountGroup.OFFLINE -> text(TextKey.ACCOUNT_GROUP_OFFLINE)
    AccountService.AccountGroup.YGGDRASIL -> text(TextKey.ACCOUNT_GROUP_YGGDRASIL)
}
