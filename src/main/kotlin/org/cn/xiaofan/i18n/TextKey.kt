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

    /** 读到容器状态。`{0}` 为状态值。 */
    const val READY_CONTAINER_STATE = "ready.container.state"

    /** 读到根命令名。`{0}` 为命令名。 */
    const val READY_ROOT_COMMAND = "ready.root.command"

    /** 读到子命令数量。`{0}` 为数量。 */
    const val READY_SUBCOMMAND_COUNT = "ready.subcommand.count"

    /** 没取到根命令。 */
    const val READY_ROOT_COMMAND_MISSING = "ready.root.command.missing"

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

    /** 登录离线账号按钮。 */
    const val ACCOUNT_LOGIN_OFFLINE = "account.login.offline"

    /** 登录第三方外置登录账号按钮。 */
    const val ACCOUNT_LOGIN_YGGDRASIL = "account.login.yggdrasil"

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

    /** 取消按钮。 */
    const val ACCOUNT_ADD_OFFLINE_CANCEL = "account.add.offline.cancel"

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
