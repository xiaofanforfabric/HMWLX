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

package org.cn.xiaofan.download

import androidx.compose.runtime.getValue
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.cn.xiaofan.account.AuthPreflight
import org.cn.xiaofan.account.isMicrosoftAccount
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import io.github.headlesshq.headlessmc.auth.Account

/**
 * 下载中心的准入状态。
 *
 * <p>**这是硬门槛，不是提示。** Minecraft 本体受 Mojang EULA 保护，
 * 只有购买了正版的微软账号才有权获取。没有有效微软账号时，
 * 下载页整页只显示一句拒绝文案，**不提供任何下载入口、不提供绕过按钮**。
 */
@Stable
sealed interface DownloadAccess {

    /** 正在校验账号。界面应显示进度，不要让用户以为卡住了。 */
    data object Checking : DownloadAccess

    /**
     * 放行。
     *
     * @param playerName 微软账号的玩家名，界面上显示「以 xxx 的身份下载」，
     *   让用户确认用的是哪个账号。
     */
    data class Allowed(val playerName: String) : DownloadAccess

    /**
     * 拒绝。
     *
     * <p>只有两种情况会走到这里，但它们对用户的意义不同，所以分开：
     * 压根没有微软账号时，用户该去登录；账号有但 token 失效时，
     * 用户该重新登录同一个账号。
     *
     * @param reason 拒绝原因，用于日志。界面**永远只显示同一句**
     *   `download.require.ms.account`，不区分原因 —— 一是没必要告诉
     *   未满 18 岁或非购买用户「差在哪一步」，二是任何更细的提示都会
     *   暗示"还差一点就能下载"，那是误导。
     */
    data class Denied(val reason: DenyReason) : DownloadAccess

    /** 拒绝的具体原因，仅用于日志和内部诊断，不直接展示给用户。 */
    enum class DenyReason {
        /** 当前账号根本不是微软账号（离线 / 皮肤站）。 */
        NOT_MICROSOFT,

        /** 有微软账号，但 token 刷新失败（凭据失效 / 被撤销）。 */
        CREDENTIAL_INVALID,

        /** 微软服务器连不上，无法确认账号有效性。 */
        NETWORK_UNAVAILABLE,

        /** 请求被微软限流，无法确认。 */
        RATE_LIMITED,

        /** 其它未预期错误。 */
        UNEXPECTED,
    }
}

/**
 * 下载中心的状态与准入校验。
 *
 * <p>为什么单独一个类而不是写在 Composable 里：
 * 校验要发 HTTP 请求（刷新 token + 拉 profile），耗时且可能失败；
 * 而且用户可能反复进出来回切页面。状态放在这里，切换页面不会重置，
 * 也不会因为重组触发重复校验。
 */
class DownloadState internal constructor() {
    /** 当前准入状态。初始为 [DownloadAccess.Checking]，进页面就开始校验。 */
    var access by mutableStateOf<DownloadAccess>(DownloadAccess.Checking)
        private set

    /** 校验失败时的错误详情（网络错误信息等），供日志和「重新校验」时展示。 */
    var errorDetail by mutableStateOf<String?>(null)
        private set

    /**
     * 触发令牌，供界面订阅。
     *
     * <p>和 [org.cn.xiaofan.version.VersionState] 同一条路子：页面 `LaunchedEffect(token)`
     * 一变就重跑。只用 `LaunchedEffect(Unit)` 的话，Compose 复用同一处调用点
     * 时 key 恒等，换页回来不会重跑 —— 这里必须能真的重新校验。
     */
    internal var checkToken by mutableStateOf(0)
        private set

    /** 详见 [checkToken]。 */
    internal val token: Int get() = checkToken

    /** 防止重复校验。 */
    private var checking = false

    /**
     * 请求重新校验。
     *
     * <p>只推进令牌，真正的校验由页面 `LaunchedEffect(token)` 执行。
     *
     * <p>**不要在这里 `checkToken++` 之后又自己 `scope.launch`：**
     * 令牌变了会让 effect 重跑，而 effect 里又调 recheck()，
     * 两者互相触发。页面侧订阅令牌、状态侧只负责推进，职责分开。
     */
    fun recheck() {
        if (checking) return
        checkToken++
    }

    // ------------------------------------------------------------------
    // 版本列表与下载
    // ------------------------------------------------------------------

    /**
     * 版本列表的加载状态。
     *
     * <p>只在 [DownloadAccess.Allowed] 之后才有意义：拒绝态不该去拉清单，
     * 那是把受 EULA 保护的数据提前准备好，等于变相提供下载。
     */
    var listState by mutableStateOf<VersionListUi>(VersionListUi.Idle)
        private set

    /** 版本清单加载状态。 */
    sealed interface VersionListUi {
        /** 还没开始拉。 */
        data object Idle : VersionListUi

        /** 正在拉取清单。 */
        data object Loading : VersionListUi

        /** 拉到了。 */
        data class Loaded(
            val versions: List<MinecraftDownloadService.DownloadableVersion>,
        ) : VersionListUi

        /** 拉失败。`reason` 可直接展示。 */
        data class Failed(val reason: String) : VersionListUi
    }

    /**
     * 某个版本的下载进度。
     *
     * <p>同时只允许一个版本在下载：并发下载会争抢同一个游戏目录，
     * 装出来的目录结构可能互相覆盖。上游的安装器也不是为并发设计的。
     */
    var installing by mutableStateOf<InstallingUi?>(null)
        private set

    /** 下载中状态。 */
    data class InstallingUi(
        /** 正在下载的 MC 版本号（不带加载器修饰），列表用它匹配高亮。 */
        val versionId: String,
        /** 带加载器修饰的展示文案，如 `Fabric 0.16.9 / 1.21.4`。 */
        val label: String,
        /** 当前阶段描述，来自上游的进度回调。 */
        val stage: String,
    )

    /** 最近一次下载的结果。成功/失败都放这里，界面展示后由用户关闭。 */
    var installResult by mutableStateOf<InstallOutcome?>(null)
        private set

    /** 下载结果提示。 */
    sealed interface InstallOutcome {
        data class Success(val versionId: String) : InstallOutcome
        data class Failed(val versionId: String, val reason: String) : InstallOutcome
    }

    /**
     * 某个 MC 版本的加载器支持情况（详情页用）。
     *
     * <p>缓存键是 MC 版本号：用户可能来回切详情页，重复查三次 API 没意义。
     * 不设过期 —— 加载器对某个已发布 MC 版本的构建列表基本不再变（
     * Fabric 会追加新 loader 版本，但那不影响「支不支持」这个判断）。
     */
    var loaderSupport by mutableStateOf<Map<String, LoaderSupportUi>>(emptyMap())
        private set

    /** 加载器查询的界面状态。 */
    sealed interface LoaderSupportUi {
        /** 正在查询三个加载器。 */
        data object Loading : LoaderSupportUi

        /** 查询完成。每个加载器一条，顺序与 [ModLoaderService.Loader.entries] 一致。 */
        data class Loaded(val supports: List<ModLoaderService.Support>) : LoaderSupportUi
    }

    /**
     * 查询某个 MC 版本的加载器支持情况（带缓存）。
     *
     * <p>并行查三个加载器，见 [ModLoaderService.querySupport]。
     * 重复调用已查询过的版本会直接返回 —— 调用方不需要自己判重。
     */
    suspend fun loadLoaderSupport(versionId: String) {
        if (loaderSupport[versionId] is LoaderSupportUi.Loaded) return

        loaderSupport = loaderSupport + (versionId to LoaderSupportUi.Loading)

        val supports = ModLoaderService.querySupport(versionId)

        loaderSupport = loaderSupport + (versionId to LoaderSupportUi.Loaded(supports))
    }

    /** 清掉某个版本的加载器缓存，让下次进入详情页重新查（网络失败后用）。 */
    fun invalidateLoaderSupport(versionId: String) {
        loaderSupport = loaderSupport - versionId
    }

    /** 关闭结果提示。 */
    fun dismissInstallResult() {
        installResult = null
    }

    /**
     * 本地已安装版本需要重扫的信号。
     *
     * <p>界面订阅它来决定何时调 `VersionState.reload()`。不直接持有
     * `VersionState` 是因为那会让下载模块依赖版本模块，而版本模块
     * 是纯读磁盘、不依赖容器的，不该被下载功能带脏。
     */
    var localVersionsToken by mutableStateOf(0)
        private set

    /**
     * 拉取版本清单。
     *
     * <p>只有放行态才拉：拒绝态拉清单等于把受保护的数据提前准备好。
     * 重复调用时若已在加载则忽略，避免连点触发多次请求。
     */
    suspend fun loadVersions() {
        if (access !is DownloadAccess.Allowed) return
        if (listState is VersionListUi.Loading) return

        listState = VersionListUi.Loading
        listState = when (val result = MinecraftDownloadService.listVersions()) {
            is MinecraftDownloadService.VersionListResult.Success ->
                VersionListUi.Loaded(result.versions)

            is MinecraftDownloadService.VersionListResult.Failed ->
                VersionListUi.Failed(result.reason)
        }
    }

    /**
     * 下载并安装一个版本。
     *
     * <p>**只有 [MinecraftDownloadService.install] 确认版本真的落到磁盘上
     * （它内部会重扫本地版本）**，这里才推进 [localVersionsToken]。
     * 否则会出现「界面提示安装完成，版本列表里却没有」—— 比直接报错更让人困惑。
     *
     * <p>同样只允许放行态调用 —— 这是最后一道闸，界面层不该是唯一的守卫。
     */
    suspend fun install(versionId: String) {
        installWith(versionId, loader = null, build = null)
    }

    /**
     * 下载并安装指定加载器 + 构建的版本。
     *
     * <p>与原版安装共用同一套状态机：同时只允许一个下载、成功才推
     * [localVersionsToken]。区别只在于装出来的版本名由上游决定
     * （如 `fabric-loader-0.16.9-1.21.4`），所以 [InstallOutcome.Success]
     * 里带的是**上游实际装出来的版本名**，不是用户点的版本号。
     *
     * @param loader 加载器；null 表示原版
     * @param build 加载器构建标识；null 表示让上游挑最新
     */
    suspend fun installWith(
        versionId: String,
        loader: ModLoaderService.Loader?,
        build: String?,
    ) {
        if (access !is DownloadAccess.Allowed) return
        if (installing != null) return

        // 界面上显示的名字：带加载器时要写清楚装的是哪个组合，
        // 否则用户看到「正在下载 1.21.4」会以为在下原版。
        val label = if (loader == null) {
            versionId
        } else {
            "${loader.displayName} $build / $versionId"
        }

        installing = InstallingUi(versionId = versionId, label = label, stage = "准备中…")
        installResult = null

        val result = MinecraftDownloadService.installWith(versionId, loader, build) { stage ->
            // 进度回调在 IO 线程上触发，写 Compose 状态是线程安全的
            // （mutableStateOf 的写入会被转发到主线程的快照系统）。
            installing = installing?.copy(stage = stage)
        }

        installing = null
        installResult = when (result) {
            is MinecraftDownloadService.InstallResult.Success -> {
                // 装完了，让版本列表重新扫一遍磁盘。
                localVersionsToken++
                InstallOutcome.Success(result.versionId)
            }

            is MinecraftDownloadService.InstallResult.Failed ->
                InstallOutcome.Failed(label, result.reason)
        }
    }

    /**
     * 执行一次校验。由页面在 `LaunchedEffect(token)` 里调用。
     *
     * <p>校验协程挂在页面的 effect 上，页面切走时自动取消 ——
     * 不会出现「页面已经走了，结果才回来」覆盖新状态。
     *
     * <p>用 suspend 而不是 `scope.launch`：后者挂在 `HmwLxApp` 的 scope 上，
     * 页面切走不会取消，用户快速来回切面板会并发跑多个校验，
     * 最后谁覆盖谁不确定。
     */
    suspend fun runCheck() {
        if (checking) return
        checking = true
        access = DownloadAccess.Checking
        errorDetail = null
        try {
            access = checkAccess()
        } finally {
            checking = false
        }
    }

    /**
     * 核心判定：启动器里是否有**任意一个**有效的微软正版账号。
     *
     * <h2>为什么判定「有正版账号」而不是「当前选中正版账号」</h2>
     *
     * 最早写成检查当前账号，结果用户切到离线号去玩，回来发现下载页不给进了 ——
     * 正版号明明没退出。这是典型的判定对象搞错：
     *
     *   - 下载权属于**这个启动器里有没有已购买 Minecraft 的微软账号**
     *   - 不属于**此刻选中的是哪个账号**
     *
     * 用户完全可能挂着正版号、切离线号玩整合包。要求他"为了下载先切回正版号"
     * 是没必要的刁难，而且切号还会让正在跑的游戏掉线。
     *
     * <h2>为什么仍需真实校验 token</h2>
     *
     * `isMicrosoftAccount` 只看 provider 字段，那是磁盘上的 JSON，可以手改；
     * token 也可能早已被微软撤销。所以对每个微软账号都真的刷新一次，
     * 只要有一个成功就放行。
     *
     * <p>账号列表来自 `AuthService.accounts`，与账号切换界面读的是同一份数据。
     */
    private suspend fun checkAccess(): DownloadAccess = withContext(Dispatchers.IO) {
        // 读账号列表本身可能失败（容器未就绪、读盘出错、CDI 解析异常）。
        // 这和「用户没登录」是完全不同的两件事，必须分开报 ——
        // 否则用户会被引导去反复重登一个根本没问题的流程。
        val microsoftAccounts = try {
            listMicrosoftAccounts()
        } catch (t: Throwable) {
            errorDetail = "读取账号列表失败：${t.describeShort()}"
            return@withContext DownloadAccess.Denied(DownloadAccess.DenyReason.UNEXPECTED)
        }

        if (microsoftAccounts.isEmpty()) {
            errorDetail = "账号列表里没有微软正版账号"
            return@withContext DownloadAccess.Denied(DownloadAccess.DenyReason.NOT_MICROSOFT)
        }

        // 逐个尝试刷新，任意一个成功就放行。
        //
        // 顺序：先试**当前选中**的那个。用户多半就是用它下的，
        // 而且 headlessmc 对最近用过的账号 token 往往还是新鲜的，
        // 能省掉一次网络往返。
        val current = readCurrentAccount()
        val ordered = buildList {
            if (current != null && current.isMicrosoftAccount) add(current)
            addAll(microsoftAccounts.filter { it != current })
        }

        // 失败原因按**类别**记录，不按「最后一次」记录。
        //
        // 为什么：循环里"最后一次"往往是噪声最少的那个，专门用来覆盖掉前面的信息。
        // 一个账号网络断了、后面跟着两个凭据失效，「最后一次」就成了凭据失效，
        // 用户被引导去重登一个根本没问题的账号。所以分开记：
        // 只要有任何一次是网络/限流，就说明「我们没能验证」，
        // 这个结论与别的账号失效与否无关，优先级最高。
        var sawNetwork = false
        var sawRateLimit = false
        var sawCredentialInvalid = false
        var sawUnexpected = false
        var lastDetail: String? = null

        for (account in ordered) {
            when (val result = refreshAccount(account)) {
                is AuthPreflight.Result.Ready -> {
                    // 校验通过。用**这个账号**的名字显示，
                    // 而不是当前选中的账号 —— 用户看到"以 A 的身份下载"，
                    // 实际用的就是 A，不会对不上。
                    val ok = result.account
                    if (!ok.isMicrosoftAccount) {
                        // refresh 不该换掉 provider。真发生了说明数据被改过，跳过这个继续试。
                        // 这是「账号数据坏了」，不是「你网络断了」，归到未预期更诚实。
                        sawUnexpected = true
                        lastDetail = "账号 ${ok.name} 刷新后 provider 变了：${ok.provider}"
                        continue
                    }
                    // Account.name 是非空 String（上游字段声明就是 String），
                    // 不需要 Elvis 兜底 —— 写了编译器会警告「左边恒非空」。
                    // 也不能显示空串，用 isNotBlank 兜住上游可能给的空白名。
                    val playerName = ok.name.takeIf { it.isNotBlank() } ?: "玩家"
                    return@withContext DownloadAccess.Allowed(playerName)
                }

                is AuthPreflight.Result.NetworkFailure -> {
                    // 网络问题不代表账号无效，只是这次没验证成。
                    sawNetwork = true
                    lastDetail = result.detail
                }

                is AuthPreflight.Result.RateLimited -> {
                    // 限流同理：请求送到了，只是被挡回来。
                    sawRateLimit = true
                    lastDetail = result.detail
                }

                is AuthPreflight.Result.CredentialInvalid -> {
                    // 这个账号失效了，试下一个。只有当所有账号都失效才整体拒绝。
                    sawCredentialInvalid = true
                    lastDetail = result.detail
                }

                is AuthPreflight.Result.SessionExpired -> {
                    sawCredentialInvalid = true
                    lastDetail = "会话已过期"
                }

                is AuthPreflight.Result.ProviderUnavailable -> {
                    sawUnexpected = true
                    lastDetail = result.detail
                }

                is AuthPreflight.Result.Unexpected -> {
                    sawUnexpected = true
                    lastDetail = result.detail
                }

                is AuthPreflight.Result.NoAccount -> {
                    // 不应该发生：我们从账号列表里取的。真到了这里当账号失效处理。
                    sawCredentialInvalid = true
                    lastDetail = "刷新时报告无账号"
                }
            }
        }

        // 收敛：网络 > 限流 > 凭据失效 > 其它。
        //
        // 前两类都属于「我们没能验证」，不能告诉用户「你的账号不行」——
        // 他会白白去重登一个本来好好的账号。凭据失效只有在
        // 所有账号都明确失效时才是事实。
        val reason = when {
            sawNetwork -> DownloadAccess.DenyReason.NETWORK_UNAVAILABLE
            sawRateLimit -> DownloadAccess.DenyReason.RATE_LIMITED
            sawCredentialInvalid -> DownloadAccess.DenyReason.CREDENTIAL_INVALID
            else -> DownloadAccess.DenyReason.UNEXPECTED
        }

        errorDetail = lastDetail ?: "所有微软账号均校验失败"
        DownloadAccess.Denied(reason)
    }

    /**
     * 刷新单个账号的 token。
     *
     * <p>委托给 [AuthPreflight.refreshMicrosoft]，它专门为此加了一个
     * 「刷新指定账号」的入口 —— 异常分类（凭据失效 / 网络失败 / 限流）
     * 因此只有一处实现，下载中心和启动前预检的判定口径不会分叉。
     */
    private suspend fun refreshAccount(account: Account): AuthPreflight.Result =
        AuthPreflight.refreshMicrosoft(account)

    /**
     * 取全部微软账号（provider == DEFAULT）。
     *
     * <p>用 `AuthService.accounts` 而不是遍历 {@link AccountGroup}：
     * 后者会带上默认离线账号 Steve，那不是用户登录过的账号。
     *
     * <h2>为什么异常不能吞成「空列表」</h2>
     *
     * 上一版是 `runCatching { ... }.getOrElse { emptyList() }`，
     * 于是「容器没就绪 / 读盘失败 / CDI 解析出错」全都表现为空列表，
     * 界面就报「你没有登录正版账号」—— 用户明明登录着，被这么一说
     * 只会去反复重登，越试越糊涂。
     *
     * <p>现在把异常**往上抛**，由 [checkAccess] 归到
     * [DownloadAccess.DenyReason.UNEXPECTED]，文案说「校验出错」而不是
     * 「你没登录」。两种情况对用户的意义完全不同。
     */
    private fun listMicrosoftAccounts(): List<Account> {
        val service = HeadlessMcBootstrap.bean(AuthServiceAccess::class.java)

        // 诊断日志：每次校验都打出「总共几个账号、各是什么 provider」。
        //
        // 为什么要打：曾经出现过用户明明登录着微软号、界面却说没登录的情况。
        // 上游链路（AuthServiceImpl.getAccounts → enabledProviders →
        // McAuthProvider.getAccounts → authStore.stream）每一环都可能出问题，
        // 只看结果分不清是「真的空」还是「被过滤掉了」。
        // 这行日志能让下次复现时一眼看出卡在哪。
        val all = service.accounts
        if (all.isEmpty()) {
            System.err.println("[HMWLX] 账号列表为空：AuthService.accounts 返回 0 条")
        } else {
            // 只打名字和 provider，不打 token —— 这是会进日志文件的内容。
            val summary = all.joinToString(", ") { "${it.name}(${it.provider})" }
            System.err.println("[HMWLX] 账号列表：$summary")
        }

        return all.filter { it.isMicrosoftAccount }
    }

    /**
     * 读当前账号。
     *
     * <p>直接走 headlessmc 的 [LastUsedAccountService]，与 GUI 切号、
     * CLI `account select` 是同一条路径。不经过 AccountService.currentAccount()，
     * 因为那个方法在没有账号时会回退到 `Steve` 离线账号，
     * 而这里需要区分「没有账号」和「有离线账号」。
     */
    private fun readCurrentAccount(): Account? = runCatching {
        HeadlessMcBootstrap.bean(LastUsedAccountAccess::class.java)
            .lastUsedAccount
            .orElse(null)
    }.getOrNull()
}

// ---------------------------------------------------------------------------
// headlessmc 类型别名。
//
// 直接 import headlessmc 的类名在这里会和本文件的 Kotlin 名字打架，
// 用别名把依赖收敛到这一处：以后 headlessmc 升级改包名，只改这里。
// ---------------------------------------------------------------------------

private typealias AuthServiceAccess = io.github.headlesshq.headlessmc.auth.AuthService
private typealias LastUsedAccountAccess =
    io.github.headlesshq.headlessmc.auth.LastUsedAccountService

/**
 * 把异常压成一句能塞进界面的话。
 *
 * <p>只取最外层 message，不拼整条 cause 链：这里要显示给用户看，
 * 异常链留给日志。链太长会把界面撑爆，而且用户也读不懂。
 */
private fun Throwable.describeShort(): String =
    message?.takeIf { it.isNotBlank() }?.take(160) ?: javaClass.simpleName

/**
 * 创建并记住下载中心状态。
 *
 * <p>提到 [androidx.compose.runtime.Composable] 调用方持有，而不是在页面内部
 * `remember`：用户来回切页面会重组，状态放页面里每次都会重置，
 * 导致每次都重新校验账号（要发两次 HTTP 请求）。
 *
 * <p>**注意这里不主动触发校验。** 校验由 [DownloadCenterScreen] 的
 * `LaunchedEffect` 在进入页面时调 —— 没人进下载页就不该发网络请求，
 * 那是白花的流量，也是不必要的账号信息外发。
 */
@androidx.compose.runtime.Composable
fun rememberDownloadState(): DownloadState {
    return androidx.compose.runtime.remember { DownloadState() }
}
