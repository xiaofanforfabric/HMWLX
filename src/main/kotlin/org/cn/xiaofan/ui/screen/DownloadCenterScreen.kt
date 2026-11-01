package org.cn.xiaofan.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.cn.xiaofan.download.DownloadAccess
import org.cn.xiaofan.download.DownloadState
import org.cn.xiaofan.i18n.TextKey
import org.cn.xiaofan.i18n.text

/**
 * 下载中心。
 *
 * <h2>准入是硬门槛</h2>
 *
 * Minecraft 本体受 Mojang EULA 保护，只有购买了正版的微软账号才有权获取。
 * 因此本页面分两种形态，**没有中间态**：
 *
 * | 状态 | 界面 |
 * |---|---|
 * | 没有有效微软账号 | 整页只有一句拒绝文案，没有任何下载入口 |
 * | 有有效微软账号 | 下载列表 |
 *
 * <p>**不要**给拒绝态加「仍然继续」「稍后再说」之类的按钮。
 * 那等于告诉用户「其实能下」，既误导又违背协议。
 *
 * <p>服务器连不上也算拒绝：无法确认账号有效性时，宁可不放行。
 *
 * @param state 准入状态与校验逻辑，由调用方持有以跨页面切换保持。
 */
@Composable
fun DownloadCenterScreen(
    state: DownloadState,
) {
    // 重新校验挂在 state.token 上，而不是 Unit。
    //
    // key 为 Unit 时，Compose 复用同一处调用点（本页是整个替换掉的，
    // 位置固定），key 恒等就不会重跑 —— 结果是第一次校验成功后，
    // 之后进多少次都用缓存状态。挂在 token 上，每次进页面 recheck()
    // 推进令牌，这次 effect 必然重跑。
    LaunchedEffect(state.token) {
        state.recheck()
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
    ) {
        when (val access = state.access) {
            is DownloadAccess.Checking -> CheckingView()

            is DownloadAccess.Allowed -> AllowedView(access)

            is DownloadAccess.Denied -> DeniedView(state)
        }
    }
}

/**
 * 校验中。
 *
 * <p>只转圈不给内容 —— 校验期间露出任何下载入口都等于提前放行。
 */
@Composable
private fun CheckingView() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CircularProgressIndicator()
            Text(
                text = text(TextKey.DOWNLOAD_CHECKING),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 拒绝页。
 *
 * <p>**按原因分流文案，但每种原因都只有一段话、没有任何按钮。**
 *
 * <p>为什么必须分流：用户明明有正版号，只是微软服务器挂了或自己断网，
 * 却看到「请先登录正版账号」—— 他会以为账号被删了，跑去重新登录，
 * 登录也失败，于是彻底懵。网络问题必须说成网络问题。
 *
 * <p>为什么仍然不给按钮：
 * 「没登录」时该做的是去账号栏登录，给「重试」按钮没用；
 * 「网络问题」时用户的正确动作是检查网络或等微软恢复，
 * 给「重试」也只是让他反复点同一件事。真正需要刷新时，
 * 重新进出本页面就会重新校验。
 */
@Composable
private fun DeniedView(state: DownloadState) {
    val denied = state.access as? DownloadAccess.Denied
    val messageKey = when (denied?.reason) {
        DownloadAccess.DenyReason.NETWORK_UNAVAILABLE -> TextKey.DOWNLOAD_NETWORK_UNAVAILABLE
        DownloadAccess.DenyReason.RATE_LIMITED -> TextKey.DOWNLOAD_RATE_LIMITED
        // 其余情况（没账号 / 凭据失效 / 未预期）统一用同一句：
        // 对用户来说结论都一样 —— 现在没有可用正版账号。
        else -> TextKey.DOWNLOAD_REQUIRE_MS_ACCOUNT
    }

    Box(
        modifier = Modifier.fillMaxSize().padding(48.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text(messageKey),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 560.dp),
        )
    }
}

/**
 * 放行页。
 *
 * <p>目前只显示「以谁的身份下载」。真正的版本列表与下载逻辑是下一步的事，
 * 这里先把准入链路跑通 —— 准入没做对就做下载，等于给所有人开门。
 */
@Composable
private fun AllowedView(access: DownloadAccess.Allowed) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.TopStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = text(TextKey.DOWNLOAD_TITLE),
                style = MaterialTheme.typography.headlineSmall,
            )
            // 显示账号名，让用户能确认用的是哪个账号下载的。
            Text(
                text = "已通过微软正版账号校验：${access.playerName}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "版本列表与下载功能待接入。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
