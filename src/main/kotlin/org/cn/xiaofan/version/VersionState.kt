package org.cn.xiaofan.version

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * 全局的版本列表状态。
 *
 * <p>提到这一层是因为版本现在被两个地方同时读：
 * 版本页要展示全部并允许切换，首页的启动按钮要显示「当前选的是哪个」。
 * 如果各自 `remember` 一份，两处会各扫一次磁盘，而且用户在一处改了选择
 * 另一处不知道 —— 表现就是选了版本、回首页按钮上还是旧名字。
 *
 * <p>只读磁盘，不依赖 Quarkus 容器，所以容器挂了这里照样能列出本机装了什么。
 */
@Stable
class VersionState internal constructor() {
    /** 已安装的版本，发布时间倒序。 */
    var versions by mutableStateOf<List<GameVersion>>(emptyList())
        private set

    /** 游戏目录的绝对路径，界面上要显示，让用户知道在扫哪儿。 */
    var gameDirPath by mutableStateOf<String?>(null)
        private set

    /** 是否正在扫描。 */
    var loading by mutableStateOf(true)
        private set

    /** 扫描失败的原因，可直接展示。 */
    var error by mutableStateOf<String?>(null)
        private set

    /**
     * 当前选中的版本。
     *
     * <p>每次扫描后都会校正：选中的版本如果已经不存在了（用户删了目录），
     * 就落到列表第一个。不校正的后果是首页一直显示一个已经装不出来的名字。
     */
    var selected by mutableStateOf<GameVersion?>(null)
        private set

    /** 内部的重扫令牌。 */
    internal var reloadToken by mutableStateOf(0)

    /** 重新扫描。扫描进行中时忽略，避免连点刷新起多个任务。 */
    fun reload() {
        if (!loading) reloadToken++
    }

    /** 选中一个版本。 */
    fun select(version: GameVersion) {
        selected = version
    }

    /** 供 Composable 订阅重扫令牌的只读视图。 */
    internal val token: Int get() = reloadToken

    internal fun applyResult(result: GameVersionService.VersionScanResult) {
        when (result) {
            is GameVersionService.VersionScanResult.Success -> {
                versions = result.versions
                gameDirPath = result.gameDir.absolutePath
                error = null
                if (selected == null || versions.none { it.id == selected?.id }) {
                    selected = versions.firstOrNull()
                }
            }
            is GameVersionService.VersionScanResult.Failed -> {
                versions = emptyList()
                selected = null
                error = result.reason
            }
        }
        loading = false
    }

    internal fun markLoading() {
        loading = true
        error = null
    }
}

/**
 * 记住一份版本状态，并负责在创建时与重扫时触发扫描。
 *
 * <p>扫描用 [LaunchedEffect] 挂在 `reloadToken` 上：令牌一变就重跑，
 * 刷新按钮和「外部触发重扫」共用这条路径，不用各写一套加载逻辑。
 */
@Composable
fun rememberVersionState(): VersionState {
    val state = remember { VersionState() }

    LaunchedEffect(state.token) {
        state.markLoading()
        state.applyResult(GameVersionService.scan())
    }

    return state
}
