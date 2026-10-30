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
}
