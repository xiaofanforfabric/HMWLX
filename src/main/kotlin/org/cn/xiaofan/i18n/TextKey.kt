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

package org.cn.xiaofan.i18n

/**
 * 界面文案的 key 清单。
 *
 * 用常量而不是裸字符串，key 写错在编译期就发现；改名时也能一处改到处生效。
 * 值必须与语言包里的 key 完全一致。
 */
object TextKey {
    /** 启动中提示。 */
    const val BOOT_LOADING = "boot.loading"

    /** 启动失败标题。 */
    const val BOOT_FAILED_TITLE = "boot.failed.title"

    /** 应用名（大写标识）。 */
    const val APP_NAME = "app.name"

    /** 应用副标题。 */
    const val APP_SUBTITLE = "app.subtitle"

    /** 窗口标题。 */
    const val WINDOW_TITLE = "app.window.title"

    /** 账号来源：正版。 */
    const val SOURCE_MICROSOFT = "account.source.microsoft"

    /** 账号来源：皮肤站。 */
    const val SOURCE_YGGDRASIL = "account.source.yggdrasil"

    /** 账号来源：离线。 */
    const val SOURCE_OFFLINE = "account.source.offline"

    /** 玩家头像的描述。`{0}` 为玩家名。 */
    const val ACCOUNT_AVATAR_DESCRIPTION = "account.avatar.description"

    // --- 账号切换 ---

    /** 账号切换界面标题。 */
    const val ACCOUNT_SWITCH_TITLE = "account.switch.title"

    /** 返回按钮。 */
    const val ACCOUNT_SWITCH_BACK = "account.switch.back"

    /** 当前账号标记。 */
    const val ACCOUNT_CURRENT = "account.current"

    /** 切换账号失败。`{0}` 为用户名。 */
    const val ACCOUNT_SWITCH_FAILED = "account.switch.failed"

    /** 分组标题：微软正版。 */
    const val ACCOUNT_GROUP_MICROSOFT = "account.group.microsoft"

    /** 分组标题：离线。 */
    const val ACCOUNT_GROUP_OFFLINE = "account.group.offline"

    /** 分组标题：外置登录。 */
    const val ACCOUNT_GROUP_YGGDRASIL = "account.group.yggdrasil"

    /** 分组为空时的提示。 */
    const val ACCOUNT_GROUP_EMPTY = "account.group.empty"

    /** 右侧登录区标题。 */
    const val ACCOUNT_LOGIN_TITLE = "account.login.title"

    /** 登录微软账号按钮。 */
    const val ACCOUNT_LOGIN_MICROSOFT = "account.login.microsoft"

    /** 微软登录：正在向微软申请设备码。 */
    const val ACCOUNT_LOGIN_MICROSOFT_REQUESTING = "account.login.microsoft.requesting"

    /** 微软登录：提示用户打开链接完成授权。 */
    const val ACCOUNT_LOGIN_MICROSOFT_HINT = "account.login.microsoft.hint"

    /** 微软登录：等待用户完成授权。 */
    const val ACCOUNT_LOGIN_MICROSOFT_WAITING = "account.login.microsoft.waiting"

    /** 微软登录：重新打开浏览器按钮。 */
    const val ACCOUNT_LOGIN_MICROSOFT_REOPEN = "account.login.microsoft.reopen"

    /** 微软登录：关闭按钮。 */
    const val ACCOUNT_LOGIN_MICROSOFT_CLOSE = "account.login.microsoft.close"

    /** 微软登录失败。`{0}` 为原因。 */
    const val ACCOUNT_LOGIN_MICROSOFT_FAILED = "account.login.microsoft.failed"

    /** 登录离线账号按钮。 */
    const val ACCOUNT_LOGIN_OFFLINE = "account.login.offline"

    /** 登录第三方外置登录账号按钮。 */
    const val ACCOUNT_LOGIN_YGGDRASIL = "account.login.yggdrasil"

    /** 外置登录：说明文字。 */
    const val ACCOUNT_LOGIN_YGGDRASIL_DESC = "account.login.yggdrasil.desc"

    /** 外置登录：服务器地址字段。 */
    const val ACCOUNT_LOGIN_YGGDRASIL_SERVER = "account.login.yggdrasil.server"

    /** 外置登录：账号字段。 */
    const val ACCOUNT_LOGIN_YGGDRASIL_USERNAME = "account.login.yggdrasil.username"

    /** 外置登录：密码字段。 */
    const val ACCOUNT_LOGIN_YGGDRASIL_PASSWORD = "account.login.yggdrasil.password"

    /** 外置登录：登录中。 */
    const val ACCOUNT_LOGIN_YGGDRASIL_WAITING = "account.login.yggdrasil.waiting"

    /** 外置登录：确定按钮。 */
    const val ACCOUNT_LOGIN_YGGDRASIL_CONFIRM = "account.login.yggdrasil.confirm"

    /** 外置登录：角色选择标题。 */
    const val ACCOUNT_LOGIN_YGGDRASIL_PROFILE_TITLE = "account.login.yggdrasil.profile.title"

    /** 外置登录：角色选择说明。 */
    const val ACCOUNT_LOGIN_YGGDRASIL_PROFILE_DESC = "account.login.yggdrasil.profile.desc"

    /** 外置登录失败。`{0}` 为原因。 */
    const val ACCOUNT_LOGIN_YGGDRASIL_FAILED = "account.login.yggdrasil.failed"

    // --- 添加离线账号 ---

    /** 对话框标题。 */
    const val ACCOUNT_ADD_OFFLINE_TITLE = "account.add.offline.title"

    /** 对话框说明文字。 */
    const val ACCOUNT_ADD_OFFLINE_DESC = "account.add.offline.desc"

    /** 用户名输入框标签。 */
    const val ACCOUNT_ADD_OFFLINE_LABEL = "account.add.offline.label"

    /** 用户名过长。 */
    const val ACCOUNT_ADD_OFFLINE_TOO_LONG = "account.add.offline.too.long"

    /** 用户名含非法字符。 */
    const val ACCOUNT_ADD_OFFLINE_INVALID_CHARS = "account.add.offline.invalid.chars"

    /** 重名。`{0}` 为用户名。 */
    const val ACCOUNT_ADD_OFFLINE_DUPLICATE = "account.add.offline.duplicate"

    /** 创建失败。`{0}` 为原因。 */
    const val ACCOUNT_ADD_OFFLINE_FAILED = "account.add.offline.failed"

    /** 确定按钮。 */
    const val ACCOUNT_ADD_OFFLINE_CONFIRM = "account.add.offline.confirm"

    // --- 退出登录（移除账号） ---

    /** 行内删除按钮的无障碍描述。`{0}` 为用户名。 */
    const val ACCOUNT_REMOVE_DESCRIPTION = "account.remove.description"

    /** 确认对话框标题。`{0}` 为用户名。 */
    const val ACCOUNT_REMOVE_TITLE = "account.remove.title"

    /** 确认对话框正文。`{0}` 为用户名。 */
    const val ACCOUNT_REMOVE_BODY = "account.remove.body"

    /** 确认按钮。 */
    const val ACCOUNT_REMOVE_CONFIRM = "account.remove.confirm"

    /** 取消按钮。 */
    const val ACCOUNT_REMOVE_CANCEL = "account.remove.cancel"

    /** 删除失败。`{0}` 为用户名。 */
    const val ACCOUNT_REMOVE_FAILED = "account.remove.failed"

    /** 删除的是当前账号时的额外提示。 */
    const val ACCOUNT_REMOVE_CURRENT_HINT = "account.remove.current.hint"

    /** 取消按钮。 */
    const val ACCOUNT_ADD_OFFLINE_CANCEL = "account.add.offline.cancel"

    // --- 版本列表 ---

    /** 列表标题。`{0}` 为版本数量。 */
    const val VERSION_LIST_TITLE = "version.list.title"

    /** 右侧面板页签：版本。 */
    const val PANEL_VERSIONS = "panel.versions"

    /** 右侧面板页签：下载中心。 */
    const val PANEL_DOWNLOAD = "panel.download"

    // --- 下载中心 ---
    //
    // 没有有效微软正版账号时，下载页整页只显示拒绝文案。
    // 这不是「提示后仍可继续」，是硬门槛：Minecraft 本体受 Mojang EULA
    // 保护，只有购买了正版的微软账号才有权下载。

    /** 下载页标题。 */
    const val DOWNLOAD_TITLE = "download.title"

    /** 未登录有效微软账号时的唯一文案（整页只显示这一句）。 */
    const val DOWNLOAD_REQUIRE_MS_ACCOUNT = "download.require.ms.account"

    /**
     * 连不上微软验证服务器时的文案。
     *
     * <p>必须和 [DOWNLOAD_REQUIRE_MS_ACCOUNT] 分开：用户明明有正版号，
     * 只是微软挂了或自己断网，却看到「请先登录正版账号」—— 会以为账号被删了。
     */
    const val DOWNLOAD_NETWORK_UNAVAILABLE = "download.network.unavailable"

    /** 被微软限流、无法完成校验时的文案。 */
    const val DOWNLOAD_RATE_LIMITED = "download.rate.limited"

    /**
     * 有微软账号但凭据已失效时的文案。
     *
     * <p>必须和 [DOWNLOAD_REQUIRE_MS_ACCOUNT] 分开：用户明明登录着，
     * 只是 token 过期了，却被告知「你没有登录正版账号」—— 他会以为
     * 账号被删了，跑去重新登录又发现账号还在，彻底懵。
     */
    const val DOWNLOAD_CREDENTIAL_INVALID = "download.credential.invalid"

    /**
     * 校验过程本身出错（读账号列表失败等）时的文案。
     *
     * <p>和上面两类都不同：这不是「你没号」，也不是「号坏了」，
     * 是「我们没能完成校验」。用户该做的是重启或看日志。
     */
    const val DOWNLOAD_CHECK_ERROR = "download.check.error"

    /** 正在校验账号时的提示。 */
    const val DOWNLOAD_CHECKING = "download.checking"

    // --- 版本列表与下载 ---

    /** 版本清单加载中。 */
    const val DOWNLOAD_LIST_LOADING = "download.list.loading"

    /** 版本清单加载失败。`{0}` 为原因。 */
    const val DOWNLOAD_LIST_FAILED = "download.list.failed"

    /** 已通过校验，显示账号名。`{0}` 为玩家名。 */
    const val DOWNLOAD_ALLOWED_AS = "download.allowed.as"

    /** 下载按钮。 */
    const val DOWNLOAD_ACTION = "download.action"

    /** 列表行按钮：打开详情页（原来是「下载」二字）。 */
    const val DOWNLOAD_DETAILS = "download.details"

    /** 详情页：标题。 */
    const val DOWNLOAD_DETAIL_TITLE = "download.detail.title"

    /** 详情页：关闭。 */
    const val DOWNLOAD_DETAIL_CLOSE = "download.detail.close"

    /** 详情页：「安装方式」分组标题。 */
    const val DOWNLOAD_CHOOSE_LOADER = "download.choose.loader"

    /** 详情页：原版选项。 */
    const val DOWNLOAD_LOADER_VANILLA = "download.loader.vanilla"

    /** 详情页：加载器版本选择标题。 */
    const val DOWNLOAD_CHOOSE_BUILD = "download.choose.build"

    /** 详情页：加载器正在查询。 */
    const val DOWNLOAD_LOADER_LOADING = "download.loader.loading"

    /** 详情页：加载器不支持这个版本。`{0}` 为加载器名，`{1}` 为 MC 版本。 */
    const val DOWNLOAD_LOADER_UNSUPPORTED = "download.loader.unsupported"

    /**
     * 详情页：加载器不支持这个版本，但邻近版本有。
     * `{0}` 加载器名，`{1}` 当前 MC 版本，`{2}` 建议的 MC 版本。
     */
    const val DOWNLOAD_LOADER_UNSUPPORTED_WITH_NEARBY =
        "download.loader.unsupported.nearby"

    /** 详情页：加载器查询失败。`{0}` 名称，`{1}` 原因。 */
    const val DOWNLOAD_LOADER_FAILED = "download.loader.failed"

    /** 详情页：三个加载器全部查询失败。`{0}` 第一个失败的原因。 */
    const val DOWNLOAD_LOADERS_ALL_FAILED = "download.loaders.all.failed"

    /** 详情页：全部失败时的排查提示。 */
    const val DOWNLOAD_LOADERS_ALL_FAILED_HINT = "download.loaders.all.failed.hint"

    /** 详情页：重试查询加载器。 */
    const val DOWNLOAD_LOADER_RETRY = "download.loader.retry"

    /** 详情页：最新构建标记。 */
    const val DOWNLOAD_LOADER_LATEST = "download.loader.latest"

    /** 下载中。`{0}` 为版本号。 */
    const val DOWNLOAD_INSTALLING = "download.installing"

    /** 下载成功。`{0}` 为版本号。 */
    const val DOWNLOAD_INSTALL_OK = "download.install.ok"

    /** 下载失败。`{0}` 为版本号，`{1}` 为原因。 */
    const val DOWNLOAD_INSTALL_FAILED = "download.install.failed"

    /** 关闭提示。 */
    const val DOWNLOAD_DISMISS = "download.dismiss"

    /** 重新拉取版本清单。 */
    const val DOWNLOAD_RELOAD_LIST = "download.reload.list"

    // --- 版本分类页签 ---

    /** 页签：全部。 */
    const val DOWNLOAD_TAB_ALL = "download.tab.all"

    /** 页签：正式版。 */
    const val DOWNLOAD_TAB_RELEASE = "download.tab.release"

    /** 页签：快照版。 */
    const val DOWNLOAD_TAB_SNAPSHOT = "download.tab.snapshot"

    /** 页签：愚人节版。 */
    const val DOWNLOAD_TAB_APRIL_FOOLS = "download.tab.april.fools"

    /** 页签：远古版。 */
    const val DOWNLOAD_TAB_LEGACY = "download.tab.legacy"

    /** 当前分类下没有版本。 */
    const val DOWNLOAD_TAB_EMPTY = "download.tab.empty"

    // --- 设置 ---

    /** 侧栏入口：设置。 */
    const val PANEL_SETTINGS = "panel.settings"

    /** 设置页标题。 */
    const val SETTINGS_TITLE = "settings.title"

    /** 子菜单：开发者设置。 */
    const val SETTINGS_DEV_TITLE = "settings.dev.title"

    /** 子菜单入口：开发者设置。 */
    const val SETTINGS_DEV_ENTRY = "settings.dev.entry"

    /** 侧栏：网络设置入口。 */
    const val SETTINGS_NETWORK_ENTRY = "settings.network.entry"

    /** 网络设置页标题。 */
    const val SETTINGS_NETWORK_TITLE = "settings.network.title"

    /** 网络设置：当前生效状态标签。 */
    const val SETTINGS_NETWORK_CURRENT = "settings.network.current"

    /** 代理地址字段标题。 */
    const val SETTINGS_PROXY_URL = "settings.proxy.url"

    /** 代理地址说明。 */
    const val SETTINGS_PROXY_URL_DESC = "settings.proxy.url.desc"

    /** 代理地址输入框占位符。 */
    const val SETTINGS_PROXY_URL_HINT = "settings.proxy.url.hint"

    /** 代理地址格式错误提示。 */
    const val SETTINGS_PROXY_URL_INVALID = "settings.proxy.url.invalid"

    /** 保存按钮。 */
    const val SETTINGS_PROXY_SAVE = "settings.proxy.save"

    /** 撤销修改按钮。 */
    const val SETTINGS_PROXY_RESET = "settings.proxy.reset"

    /** 禁用代理开关标题。 */
    const val SETTINGS_PROXY_DISABLE = "settings.proxy.disable"

    /** 禁用代理开关说明。 */
    const val SETTINGS_PROXY_DISABLE_DESC = "settings.proxy.disable.desc"

    /** 当前状态：直连（主动禁用）。 */
    const val SETTINGS_PROXY_DIRECT_DISABLED = "settings.proxy.direct.disabled"

    /** 当前状态：直连（未配置且无环境变量）。 */
    const val SETTINGS_PROXY_DIRECT_NO_ENV = "settings.proxy.direct.noenv"

    /** 代理来源：配置文件。 */
    const val SETTINGS_PROXY_SOURCE_CONFIG = "settings.proxy.source.config"

    /** 代理来源：环境变量。 */
    const val SETTINGS_PROXY_SOURCE_ENV = "settings.proxy.source.env"

    /** 代理来源：直连。 */
    const val SETTINGS_PROXY_SOURCE_DIRECT = "settings.proxy.source.direct"

    /** 代理风险提示。 */
    const val SETTINGS_PROXY_SECURITY = "settings.proxy.security"

    /** 代理修改后需重启。 */
    const val SETTINGS_PROXY_RESTART = "settings.proxy.restart"

    /** 调试模式开关标签。 */
    const val SETTINGS_DEBUG_MODE = "settings.debug.mode"

    /** 调试模式说明。 */
    const val SETTINGS_DEBUG_MODE_DESC = "settings.debug.mode.desc"

    /** 调试模式需要重启才生效的提示。 */
    const val SETTINGS_DEBUG_MODE_RESTART = "settings.debug.mode.restart"

    /** 嘉豪终端按钮。 */
    const val SETTINGS_CLI_TERMINAL = "settings.cli.terminal"

    /** 嘉豪终端说明。 */
    const val SETTINGS_CLI_TERMINAL_DESC = "settings.cli.terminal.desc"

    /** 嘉豪终端：进入。 */
    const val SETTINGS_CLI_ENTER = "settings.cli.enter"

    /** 嘉豪终端页标题。 */
    const val CLI_TERMINAL_TITLE = "cli.terminal.title"

    /** 嘉豪终端：输入框提示。 */
    const val CLI_TERMINAL_INPUT_HINT = "cli.terminal.input.hint"

    /** 嘉豪终端：清空。 */
    const val CLI_TERMINAL_CLEAR = "cli.terminal.clear"

    /** 嘉豪终端：关闭终端。 */
    const val CLI_TERMINAL_STOP = "cli.terminal.stop"

    /** 嘉豪终端：返回。 */
    const val CLI_TERMINAL_BACK = "cli.terminal.back"

    /** 嘉豪终端：已停止。 */
    const val CLI_TERMINAL_STOPPED = "cli.terminal.stopped"

    /** 嘉豪终端：启动失败后重新启动。 */
    const val CLI_TERMINAL_RETRY = "cli.terminal.retry"

    /** 首页左下角的启动按钮。 */
    const val HOME_LAUNCH = "home.launch"

    /** 启动按钮右侧抽屉的无障碍描述。 */
    const val HOME_PICK_VERSION = "home.pick.version"

    /** 启动按钮下方：还没选出版本。 */
    const val HOME_NO_VERSION = "home.no.version"

    /** 启动按钮下方：选中版本的父版本缺失，不能启动。`{0}` 为版本 id。 */
    const val HOME_VERSION_MISSING_PARENT = "home.version.missing.parent"

    /** 版本抽屉标题。 */
    const val HOME_VERSION_DRAWER = "home.version.drawer"

    // ---- 启动前的账号预检 ----
    //
    // 网络类文案只在**微软账号**上出现：只有它的 token 需要找微软续期。
    // 离线账号没有 token，皮肤站走自己的服务器，微软连不上跟它们无关。

    /** 网络问题导致令牌无法刷新的标题。 */
    const val AUTH_NETWORK_FAILED_TITLE = "auth.network.failed.title"

    /** 网络问题正文。`{0}` 为账号名。 */
    const val AUTH_NETWORK_FAILED_BODY = "auth.network.failed.body"

    /** 网络问题的补充说明：后果是什么、可以怎么办。 */
    const val AUTH_NETWORK_FAILED_HINT = "auth.network.failed.hint"

    /** 网络类失败时仍然启动。 */
    const val AUTH_NETWORK_LAUNCH_ANYWAY = "auth.network.launch.anyway"

    /** 网络类失败时取消。 */
    const val AUTH_NETWORK_CANCEL = "auth.network.cancel"

    /** 429 限流正文（知道等待时长）。`{0}` 为账号名，`{1}` 为等待描述。 */
    const val AUTH_RATE_LIMITED_BODY = "auth.rate.limited.body"

    /** 429 限流正文（不知道等待时长）。`{0}` 为账号名。 */
    const val AUTH_RATE_LIMITED_BODY_NO_DELAY = "auth.rate.limited.body.no.delay"

    /** 凭据失效对话框标题。`{0}` 为账号名。 */
    const val AUTH_CREDENTIAL_INVALID_TITLE = "auth.credential.invalid.title"

    /** 凭据失效时的提示：重试没用，要重新登录。 */
    const val AUTH_CREDENTIAL_INVALID_HINT = "auth.credential.invalid.hint"

    /** 凭据失效时的按钮：去重新登录。 */
    const val AUTH_CREDENTIAL_RELOGIN = "auth.credential.relogin"

    /** 皮肤站会话失效对话框标题。`{0}` 为账号名。 */
    const val AUTH_SESSION_EXPIRED_TITLE = "auth.session.expired.title"

    /** 皮肤站会话失效说明：登录失效，需要重新登录，但可以继续启动。 */
    const val AUTH_SESSION_EXPIRED_HINT = "auth.session.expired.hint"

    /** 皮肤站会话失效时的主按钮：继续启动（转离线）。 */
    const val AUTH_SESSION_EXPIRED_LAUNCH = "auth.session.expired.launch"

    /** 没有可用账号，需要先登录。 */
    const val AUTH_NO_ACCOUNT = "auth.no.account"

    /** 认证源不可用对话框标题。`{0}` 为 provider 名。 */
    const val AUTH_PROVIDER_UNAVAILABLE_TITLE = "auth.provider.unavailable.title"

    /** 未归类错误的对话框标题。 */
    const val AUTH_UNEXPECTED_TITLE = "auth.unexpected.title"

    /** 通用的关闭按钮。 */
    const val AUTH_DISMISS = "auth.dismiss"

    // ---- 启动中界面 ----

    /** 标题：正在启动。 */
    const val LAUNCH_TITLE_RUNNING = "launch.title.running"

    /** 标题：启动成功。 */
    const val LAUNCH_TITLE_DONE = "launch.title.done"

    /** 标题：启动失败。 */
    const val LAUNCH_TITLE_FAILED = "launch.title.failed"

    /** 日志为空时的占位。 */
    const val LAUNCH_LOG_EMPTY = "launch.log.empty"

    /** 启动中返回主界面。 */
    const val LAUNCH_BACK = "launch.back"

    /** 启动结束后关闭界面。 */
    const val LAUNCH_CLOSE = "launch.close"

    /** 停止游戏进程。 */
    const val LAUNCH_STOP = "launch.stop"

    /** 刷新按钮的无障碍描述。 */
    const val VERSION_REFRESH = "version.refresh"

    /** 读取版本列表失败。`{0}` 为原因。 */
    const val VERSION_SCAN_FAILED = "version.scan.failed"

    /** 一个版本都没装。 */
    const val VERSION_EMPTY = "version.empty"

    /** 还没选版本时的提示。 */
    const val VERSION_SELECT_HINT = "version.select.hint"

    /** 启动按钮。 */
    const val VERSION_LAUNCH = "version.launch"

    /** 版本设置按钮（占位）。 */
    const val VERSION_SETTINGS = "version.settings"

    /** 打开游戏目录按钮。 */
    const val VERSION_OPEN_DIR = "version.open.dir"

    /** 父版本缺失时重新扫描。 */
    const val VERSION_RESCAN = "version.rescan"

    /** 版本类型：正式版。 */
    const val VERSION_TYPE_RELEASE = "version.type.release"

    /** 版本类型：快照。 */
    const val VERSION_TYPE_SNAPSHOT = "version.type.snapshot"

    /** 版本类型：愚人节。 */
    const val VERSION_TYPE_APRIL_FOOLS = "version.type.april.fools"

    /** 版本类型：远古 Beta。 */
    const val VERSION_TYPE_OLD_BETA = "version.type.old.beta"

    /** 版本类型：远古 Alpha。 */
    const val VERSION_TYPE_OLD_ALPHA = "version.type.old.alpha"

    /** 版本类型：未知。 */
    const val VERSION_TYPE_OTHER = "version.type.other"

    /** 详情：版本类型。 */
    const val VERSION_DETAIL_TYPE = "version.detail.type"

    /** 详情：加载器（继承的父版本）。 */
    const val VERSION_DETAIL_LOADER = "version.detail.loader"

    /** 详情：原版（没有父版本）。 */
    const val VERSION_DETAIL_LOADER_VANILLA = "version.detail.loader.vanilla"

    /** 详情：主类。 */
    const val VERSION_DETAIL_MAIN_CLASS = "version.detail.main.class"

    /** 详情：发布时间。 */
    const val VERSION_DETAIL_RELEASE_TIME = "version.detail.release.time"

    /** 详情：游戏目录。 */
    const val VERSION_DETAIL_GAME_DIR = "version.detail.game.dir"

    /** 详情：字段缺失。 */
    const val VERSION_DETAIL_UNKNOWN = "version.detail.unknown"

    /** 列表行标记：父版本缺失。 */
    const val VERSION_MISSING_PARENT = "version.missing.parent"

    /** 详情：父版本缺失的说明。`{0}` 为父版本 id。 */
    const val VERSION_MISSING_PARENT_HINT = "version.missing.parent.hint"

    // --- 用户协议 ---

    /** 开屏标题，如「请先阅读用户协议」。 */
    const val AGREEMENT_WELCOME = "agreement.welcome"

    /** 协议版本号。`{0}` 为版本号。 */
    const val AGREEMENT_VERSION = "agreement.version"

    /** 同意按钮。 */
    const val AGREEMENT_ACCEPT = "agreement.accept"

    /** 拒绝按钮。 */
    const val AGREEMENT_DECLINE = "agreement.decline"

    /** 拒绝确认弹窗标题。 */
    const val AGREEMENT_DECLINE_CONFIRM_TITLE = "agreement.decline.confirm.title"

    /** 拒绝确认弹窗正文。 */
    const val AGREEMENT_DECLINE_CONFIRM_BODY = "agreement.decline.confirm.body"

    /** 拒绝确认弹窗的确认按钮。 */
    const val AGREEMENT_DECLINE_CONFIRM_OK = "agreement.decline.confirm.ok"

    /** 拒绝确认弹窗的取消按钮。 */
    const val AGREEMENT_DECLINE_CONFIRM_CANCEL = "agreement.decline.confirm.cancel"

    // --- 游戏启动设置（按版本） ---

    /** 分组标题。 */
    const val LAUNCH_SETTINGS_TITLE = "launch.settings.title"

    /** 作用域说明：`{0}` 为版本号。 */
    const val LAUNCH_SETTINGS_SCOPE = "launch.settings.scope"

    /** 最大内存勾选项。 */
    const val LAUNCH_SETTINGS_MAX_MEMORY = "launch.settings.max.memory"

    /** 初始内存勾选项。 */
    const val LAUNCH_SETTINGS_MIN_MEMORY = "launch.settings.min.memory"

    /** 内存提示：`{0}` 物理内存 MB，`{1}` 上限 MB。 */
    const val LAUNCH_SETTINGS_MEMORY_HINT = "launch.settings.memory.hint"

    /** 探测不到物理内存时的提示。 */
    const val LAUNCH_SETTINGS_MEMORY_UNKNOWN = "launch.settings.memory.unknown"

    /** 重置内存按钮。 */
    const val LAUNCH_SETTINGS_RESET_MEMORY = "launch.settings.reset.memory"

    /** 进程优先级标题。 */
    const val LAUNCH_SETTINGS_PRIORITY = "launch.settings.priority"

    /** 优先级说明。 */
    const val LAUNCH_SETTINGS_PRIORITY_HINT = "launch.settings.priority.hint"

    /** 优先级：低。 */
    const val LAUNCH_SETTINGS_PRIORITY_LOW = "launch.settings.priority.low"

    /** 优先级：正常。 */
    const val LAUNCH_SETTINGS_PRIORITY_NORMAL = "launch.settings.priority.normal"

    /** 优先级：高。 */
    const val LAUNCH_SETTINGS_PRIORITY_HIGH = "launch.settings.priority.high"

    /** 自定义 JVM 参数标题。 */
    const val LAUNCH_SETTINGS_JVM_ARGS = "launch.settings.jvm.args"

    /** 自定义 JVM 参数占位符。 */
    const val LAUNCH_SETTINGS_JVM_ARGS_PLACEHOLDER = "launch.settings.jvm.args.placeholder"

    /** 自定义 JVM 参数说明。 */
    const val LAUNCH_SETTINGS_JVM_ARGS_HINT = "launch.settings.jvm.args.hint"
}
