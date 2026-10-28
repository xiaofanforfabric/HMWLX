package org.cn.xiaofan.version

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 版本列表服务：界面只跟这一层打交道。
 *
 * <p>做两件事：
 * 1. 定出游戏目录：`hmc.gamedir`（系统属性或 headlessmc 配置），都没有就用 `~/.minecraft`
 * 2. 把目录交给 [GameVersionScanner] 扫，结果按「发布时间倒序」排好
 *
 * <p>**读磁盘，所以在 [Dispatchers.IO] 上跑**。版本目录里可能塞了几十个加载器版本，
 * 每个都要读一个几十 KB 的 JSON，放主线程会让窗口在打开列表的瞬间卡一下。
 *
 * <p>错误一律收敛成 [VersionScanResult]，不抛异常：
 * 界面要能显示「读取失败」而不是白屏。
 */
object GameVersionService {

    /**
     * 扫描结果。
     */
    sealed interface VersionScanResult {
        /**
         * 扫描完成。
         *
         * @param versions 已安装的版本，发布时间倒序；拿不到发布时间的排在最后
         * @param gameDir 实际使用的游戏目录，界面上要显示，让用户知道在扫哪儿
         */
        data class Success(val versions: List<GameVersion>, val gameDir: File) : VersionScanResult

        /** 读不了游戏目录（权限、路径不存在等）。`reason` 可直接展示。 */
        data class Failed(val reason: String) : VersionScanResult
    }

    /** 兜底游戏目录名，与官方启动器一致。 */
    private const val FALLBACK_DIR_NAME = ".minecraft"

    /** headlessmc 配置里游戏目录的 key。 */
    private const val KEY_GAME_DIR = "hmc.gamedir"

    /**
     * 扫描当前游戏目录。
     *
     * <p>没有单独做「刷新」接口：刷新就是再调一次。缓存留给界面层
     * （`remember` 一份列表），这一层保持无状态，省掉一套失效逻辑。
     */
    suspend fun scan(): VersionScanResult = withContext(Dispatchers.IO) {
        val dir = runCatching { resolveGameDir() }
            .getOrElse { return@withContext VersionScanResult.Failed(it.describe()) }

        runCatching { GameVersionScanner.scan(dir) }
            .map { VersionScanResult.Success(sortByReleaseTimeDesc(it), dir) }
            .getOrElse { VersionScanResult.Failed(it.describe()) }
    }

    /**
     * 解析游戏目录。
     *
     * <p>顺序：
     * 1. 系统属性 `hmc.gamedir` —— 便于测试与临时覆盖，与 `AccountResolver` 读配置的方式一致
     * 2. headlessmc 的配置文件里同名项
     * 3. `~/.minecraft`
     *
     * <p>第 1、2 步都失败不应该让整个扫描失败：绝大多数用户就是默认目录，
     * 找不到配置说明还没改过，直接用默认值才是对的。
     *
     * <p>注意 `hmc.gamedir` 配了但目录不在时**不报错**，当作「还没配」处理 ——
     * 用户可能是换了个盘、还没把游戏搬过来，这时候直接给「目录不存在」的错误
     * 反而挡住了他去看默认目录里装了什么。
     *
     * <p>**不直接注入 headlessmc 的 `GameDirConfig`**：它在不同版本里换过类型
     * （`File` / `Path` / `Holder<…>`），按类型查 bean 很容易在升级后失效。
     * 读配置文件里的同一个键更稳，代价只是多解析一个 properties 文件。
     * TODO(human): 如果 headlessmc 后续提供了稳定的 gamedir 查询接口，换掉这段文件解析。
     */
    suspend fun resolveGameDir(): File {
        val configured = configuredGameDir()
        if (configured != null) return configured

        val home = System.getProperty("user.home") ?: error("拿不到 user.home")
        return File(home, FALLBACK_DIR_NAME)
    }

    /** 从系统属性 / headlessmc 配置读 `hmc.gamedir`，没配或配了个不存在的路径就当没配。 */
    private fun configuredGameDir(): File? {
        val raw = System.getProperty(KEY_GAME_DIR)?.takeIf { it.isNotBlank() }
            ?: readHeadlessMcConfig(KEY_GAME_DIR)
            ?: return null
        return File(raw).takeIf { it.isDirectory }
    }

    /**
     * 读 headlessmc 的配置项。
     *
     * <p>与 [org.cn.xiaofan.account.AccountResolver] 里读 yggdrasil 配置是同一份文件、
     * 同一套路径规则。TODO(human): 两处合并成一个 `HeadlessMcConfig` 工具
     * （`AccountResolver.yggdrasilConfig` 目前是 private，这里不复制它的实现细节）。
     */
    private fun readHeadlessMcConfig(key: String): String? {
        val file = headlessMcConfigFile() ?: return null
        return runCatching {
            if (!file.exists()) return@runCatching null
            file.readLines()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .firstOrNull { it.startsWith("$key=") }
                ?.substringAfter('=')
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    /** headlessmc 配置文件的平台路径，规则见 `AccountResolver.headlessMcConfigFile`。 */
    private fun headlessMcConfigFile(): File? {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        val dir = when {
            os.contains("win") -> System.getenv("APPDATA")?.let { File(it, "headlessmc") }
            os.contains("mac") ->
                File(System.getProperty("user.home"), "Library/Application Support/headlessmc")
            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) File(xdg, "headlessmc")
                else File(System.getProperty("user.home"), ".config/headlessmc")
            }
        } ?: return null
        return File(dir, "config.properties")
    }

    /**
     * 按发布时间倒序，最新的版本排最前面。
     *
     * 没有 `releaseTime` 的（加载器自己生成的 JSON 经常不写）排在后面，组内保持
     * id 顺序 —— 稳定排序，刷新两次不会看到列表跳动。
     */
    internal fun sortByReleaseTimeDesc(versions: List<GameVersion>): List<GameVersion> =
        versions.sortedWith(
            compareByDescending<GameVersion> { it.releaseTime != null }
                .thenByDescending { it.releaseTime.orEmpty() }
                .thenBy { it.id.lowercase() }
        )

    /** 异常压成一句能看的话。 */
    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName
}
