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
}
