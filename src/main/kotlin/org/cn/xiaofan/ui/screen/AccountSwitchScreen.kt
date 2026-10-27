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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.cn.xiaofan.account.AccountProfile
import org.cn.xiaofan.account.AccountService
import org.cn.xiaofan.account.AvatarLoader
import org.cn.xiaofan.account.sourceLabel
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/**
 * 账号切换界面。
 *
 * <p>布局：左侧是账号列表（头像 + 用户名 + 类型标签），右侧是三个登录入口。
 * 点击左侧任意一行即切换当前账号；右侧按钮用于新增账号。
 *
 * <p>当前只实现了离线账号的新增：微软与 Yggdrasil 的登录需要走
 * headlessmc 的 `Authenticator`（WebView / 浏览器回调），属于独立的登录流程，
 * 这里先把按钮摆出来并置灰，避免点了没反应。
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

    // 读账号列表是 IO 操作（要读 headlessmc 的存储文件），
    // 但这里数据量很小、又在容器已就绪之后，直接在主线程读不会造成可感知的卡顿。
    LaunchedEffect(Unit) {
        groups = AccountService.groupedAccounts()
        loading = false
    }

    /** 重新拉一次列表 —— 新增/删除账号后都要调用。 */
    fun reloadGroups() {
        groups = AccountService.groupedAccounts()
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
                            onSelect = { profile ->
                                if (AccountService.switchTo(profile)) {
                                    onSwitched(profile)
                                } else {
                                    error = "切换账号失败：${profile.name}"
                                }
                            },
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
                        onClick = { },
                        enabled = false,
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
        AddOfflineAccountDialog(
            onCreated = { created ->
                showAddOffline = false
                reloadGroups()
                // 新建后直接切过去：用户点了「添加离线账号」就是打算用它，
                // 建完还得再点一次列表太啰嗦。
                if (AccountService.switchTo(created)) {
                    onSwitched(created)
                } else {
                    error = "账号已创建，但切换失败：${created.name}"
                }
            },
            onDismiss = { showAddOffline = false },
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
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer
    else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(bg)
            .clickable(onClick = onClick)
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
    var avatar by remember(profile.uuid, profile.avatarUrl) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(profile.uuid, profile.avatarUrl) {
        avatar = null
        avatar = profile.avatarUrl?.let { AvatarLoader.load(it) }
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
