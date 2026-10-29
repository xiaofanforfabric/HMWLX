package org.cn.xiaofan.version

/**
 * 一个已安装的游戏版本。
 *
 * <p>「已安装」的判定标准只有一条：`<游戏目录>/versions/<id>/<id>.json` 存在且能解析。
 * 版本隔离目录只有文件夹不算 —— 下载到一半中断、或者用户手动塞了个空文件夹，
 * 在列表里显示成一个点了就崩的版本，比不显示更糟。
 *
 * <p>字段全部来自版本 JSON，不做任何网络请求：能看到的版本一定装在本机。
 *
 * @property id 版本 id，与版本隔离目录名一致（如 `1.21.4`、`fabric-loader-0.16.9-1.21.4`）
 * @property type 版本类型，见 [VersionType]
 * @property rawType JSON 里的原始 `type` 值，缺失时为 null。加载器经常写自己的值
 *   （`fabric`、`forge`……），展示时优先用它，别拿 [VersionType.OTHER] 糊过去
 * @property mainClass 主类，`inheritsFrom` 的版本可能没有这个字段
 * @property inheritsFrom 继承的父版本 id。Forge / Fabric / 光影整合包都是这种结构：
 *   自己的 JSON 只写改动，登录参数与资源索引全都得从父版本拿
 * @property releaseTime ISO-8601 时间字符串，原样保留，展示交给界面格式化
 * @property missingParent 父版本在本机找不到。这种版本**不能启动**：
 *   光有子版本的 JSON 拼不出完整的启动参数
 */
data class GameVersion(
    val id: String,
    val type: VersionType,
    val rawType: String? = null,
    val mainClass: String? = null,
    val inheritsFrom: String? = null,
    val releaseTime: String? = null,
    val missingParent: Boolean = false,
) {
    /** 是否可以启动。当前只有「父版本缺失」一种否决理由。 */
    val launchable: Boolean get() = !missingParent

    /**
     * 是否是愚人节版本。
     *
     * <p>不能和 [type] 合成一个枚举值：愚人节版本在 Mojang 的 JSON 里
     * `type` 写的就是 `snapshot`，判定依据完全不同（版本号对照表），
     * 混进 [VersionType] 会让「从 JSON 解析类型」这件事失真。
     */
    val isAprilFools: Boolean get() = VersionType.isAprilFools(id)
}

/**
 * 版本类型，对应 Mojang 版本 JSON 里的 `type` 字段。
 *
 * <p>原版的值是 `release` / `snapshot` / `old_beta` / `old_alpha`；
 * 加载器（Forge、Fabric、NeoForge、Quilt、OptiFine）会自己写别的值，甚至不写。
 * 加载器的值没法穷举，所以留 [OTHER] 兜底并把原始字符串显式带出来，
 * 而不是硬塞进某个已知分类里 —— 猜错了比不猜更误导人。
 */
enum class VersionType {
    RELEASE,
    SNAPSHOT,
    OLD_BETA,
    OLD_ALPHA,

    /** 加载器写的值或缺失 type。原始字符串由 [GameVersion.rawType] 带着。 */
    OTHER,
    ;

    companion object {
        /**
         * 从 Mojang 的值解析。
         *
         * @param raw 版本 JSON 里的 `type`，缺失时传 null
         */
        fun from(raw: String?): VersionType = when (raw?.lowercase()) {
            "release" -> RELEASE
            "snapshot" -> SNAPSHOT
            "old_beta" -> OLD_BETA
            "old_alpha" -> OLD_ALPHA
            else -> OTHER
        }

        /**
         * Mojang 历年愚人节版本的版本号。
         *
         * <p>这些版本在版本 JSON 里的 `type` 就是 `snapshot`，**没有任何字段标记它们是愚人节版**，
         * 所以只能按版本号硬编码。清单来自 Mojang 官方 wiki，每年新增一条。
         *
         * <p>不覆盖的代价：新一年的愚人节版本会被当成普通快照显示。
         * 这是可接受的降级 —— 比维护一张会过期的正则好，也比猜错强。
         */
        private val APRIL_FOOLS_VERSIONS = setOf(
            "15w14a",                 // 2015 Love and Hugs Update
            "1.rv-pre1",              // 2016 Trendy Update
            "3d shareware v1.34",     // 2019
            "20w14infinite",          // 2020 无限维度
            "22w13oneblockatatime",   // 2022 单方块
            "23w13a_or_b",            // 2023 投票更新
            "24w14potato",            // 2024 毒马铃薯
            "25w14craftmine",         // 2025
        )

        /** 版本号是否是愚人节版本（大小写不敏感）。 */
        fun isAprilFools(id: String): Boolean =
            id.lowercase() in APRIL_FOOLS_VERSIONS
    }
}
