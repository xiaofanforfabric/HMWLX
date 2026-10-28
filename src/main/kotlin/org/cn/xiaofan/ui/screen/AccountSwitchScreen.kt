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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import org.cn.xiaofan.ui.component.AppBackground

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
    var showYggdrasilLogin by remember { mutableStateOf(false) }
    // 切换中：切号要写 headlessmc 的 lastUsedAccount 落盘，期间再点一次列表
    // 会并发写同一个文件，结果是两边都不生效。干脆挡住重复点击。
    var switching by remember { mutableStateOf(false) }
    // 待确认删除的账号。非空即弹出确认框 —— 删账号是不可逆的，不能点一下就没。
    var removing by remember { mutableStateOf<AccountProfile?>(null) }
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

    /**
     * 确认后真正移除账号。
     *
     * <p>删的若是当前账号，headlessmc 的 `lastUsedAccount` 就指向了一个不存在的账号，
     * 之后读 [AccountService.currentAccount] 会退回到列表第一个或默认离线账号。
     * 那种「悄悄换了人」比直接报错更难排查，所以这里主动切到剩下的第一个账号并通知外层。
     *
     * <p>删的不是当前账号时，列表刷新后当前账号不变，不需要惊动主界面。
     */
    fun removeAccount(profile: AccountProfile) {
        if (switching) return
        switching = true
        error = null
        scope.launch {
            removing = null
            if (!AccountService.removeAccount(profile)) {
                error = Messages.get(TextKey.ACCOUNT_REMOVE_FAILED, profile.name)
                switching = false
                return@launch
            }

            val wasCurrent = profile.name == current.name && profile.provider == current.provider
            groups = AccountService.groupedAccounts()

            if (wasCurrent) {
                // 优先待在原分组里就近选一个，没有才退到全表第一个。
                val sameGroup = groups.entries
                    .firstOrNull { it.key.provider == profile.provider }
                    ?.value
                    .orEmpty()
                val next = sameGroup.firstOrNull()
                    ?: groups.values.flatten().firstOrNull()
                if (next != null && AccountService.switchTo(next)) {
                    onSwitched(next)
                } else {
                    // 一个账号都不剩：currentAccount() 会退到默认离线账号，
                    // 通知外层重新读一次，避免主界面还显示刚删掉的名字。
                    onSwitched(AccountService.currentAccount())
                }
            }
            switching = false
        }
    }

    // 与主界面用同一张背景。主界面靠局部卡片保证可读性，
    // 但这里是整屏的列表 + 表单，底下的文字直接压在图上会花 ——
    // 所以保留一层很薄的遮罩（0.28），既压住噪点又不把图冲淡。
    Box(modifier = Modifier.fillMaxSize()) {
        AppBackground()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.28f)),
        )
        Surface(modifier = Modifier.fillMaxSize(), color = Color.Transparent) {
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
                            onRequestRemove = { profile -> removing = profile },
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
                        onClick = { showYggdrasilLogin = true },
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

    if (showYggdrasilLogin) {
        YggdrasilLoginDialog(
            onLoggedIn = { loggedIn -> onAccountAdded(loggedIn) { showYggdrasilLogin = false } },
            onDismiss = { showYggdrasilLogin = false },
        )
    }

    removing?.let { target ->
        RemoveAccountDialog(
            profile = target,
            isCurrent = target.name == current.name && target.provider == current.provider,
            onConfirm = { removeAccount(target) },
            onDismiss = { removing = null },
        )
    }
}

/**
 * 退出登录（移除账号）的确认框。
 *
 * <p>删账号不可逆，且对微软/皮肤站账号来说意味着下次要重新走一遍授权流程，
 * 所以必须二次确认，并把「会丢掉登录信息」讲清楚。
 */
@Composable
private fun RemoveAccountDialog(
    profile: AccountProfile,
    isCurrent: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text(TextKey.ACCOUNT_REMOVE_TITLE)) },
        text = {
            Column {
                Text(Messages.get(TextKey.ACCOUNT_REMOVE_BODY, profile.name))
                if (isCurrent) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        // 当前账号被删会自动换人，用户得先知道，不然会觉得"账号自己变了"
                        text = text(TextKey.ACCOUNT_REMOVE_CURRENT_HINT),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = text(TextKey.ACCOUNT_REMOVE_CONFIRM),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text(TextKey.ACCOUNT_REMOVE_CANCEL))
            }
        },
    )
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
    onRequestRemove: (AccountProfile) -> Unit,
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
                        onRemove = { onRequestRemove(profile) },
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
    onRemove: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
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
            Spacer(Modifier.width(4.dp))
        }

        // 垃圾桶一直在，不靠 hover 才出现：桌面端用户不会去"探索"行内的隐藏操作，
        // 而且 Compose 的 hover 检测要额外铺 pointerMoveFilter，收益不值这点代码。
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier.size(32.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Delete,
                contentDescription = Messages.get(TextKey.ACCOUNT_REMOVE_DESCRIPTION, profile.name),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
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
