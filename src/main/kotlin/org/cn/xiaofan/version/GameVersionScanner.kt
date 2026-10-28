package org.cn.xiaofan.version

import org.cn.xiaofan.config.Json
import java.io.File

/**
 * 扫描游戏目录，列出已安装的版本。
 *
 * <p>只看磁盘，不碰 headlessmc、不联网 —— 因此这个对象是纯函数集合，
 * 测试拿一个临时目录就能跑，不需要先把 Quarkus 容器拉起来。
 *
 * <p>目录结构（与官方启动器一致）：
 * ```
 * <游戏目录>/
 *   versions/
 *     1.21.4/1.21.4.json
 *     fabric-loader-0.16.9-1.21.4/fabric-loader-0.16.9-1.21.4.json
 * ```
 *
 * <p>**为什么不全交给 headlessmc**：它的版本服务是给 CLI 的 `download` / `launch`
 * 用的，读取路径与 GUI 想要的「列目录」不是一回事；而且能不能取到 bean 取决于
 * 容器状态，界面在容器挂掉时至少得能显示本机装了什么。所以目录判断自己来，
 * headlessmc 只负责回答「游戏目录在哪」。
 */
object GameVersionScanner {

    /** 版本根目录名。 */
    private const val VERSIONS_DIR = "versions"

    /** 版本 JSON 的后缀。 */
    private const val JSON_SUFFIX = ".json"

    /** 单个版本 JSON 的大小上限。正常不到 100 KB，超过这个数肯定不是版本清单。 */
    private const val MAX_JSON_BYTES = 8L * 1024 * 1024

    /**
     * 列出 `versions/` 下所有可用的版本，按 [GameVersion.id] 排序。
     *
     * <p>按 id 字符串排序而不是按发布时间：用户找的是「1.21.4」这个名字，
     * 按时间排会把 1.9 排到 1.10 后面。真正的「最新在前」交给界面层决定。
     *
     * @param gameDir 游戏目录（`versions` 的父目录）
     * @return 已安装的版本；目录不存在或读不到时返回空列表，不抛异常
     */
    fun scan(gameDir: File?): List<GameVersion> {
        if (gameDir == null) return emptyList()
        val versionsDir = File(gameDir, VERSIONS_DIR)
        if (!versionsDir.isDirectory) return emptyList()

        val dirs = versionsDir.listFiles()?.filter { it.isDirectory }.orEmpty()

        // 先收全部 id，再逐个解析：inheritsFrom 的父版本可能排在自己后面，
        // 分两趟才能判断父版本到底在不在本机。
        val ids = dirs.map { it.name }.toSet()

        return dirs.mapNotNull { dir -> readVersion(dir, ids) }
            .sortedBy { it.id.lowercase() }
    }

    /**
     * 读单个版本隔离目录。
     *
     * @param ids 当前游戏目录里所有版本 id，用于判断 [GameVersion.inheritsFrom] 是否存在
     * @return 不是合法版本（缺 JSON / JSON 坏掉）时返回 null
     */
    fun readVersion(versionDir: File, ids: Set<String>): GameVersion? {
        val id = versionDir.name
        val jsonFile = File(versionDir, "$id$JSON_SUFFIX")
        if (!jsonFile.isFile) return null

        val root = runCatching { parseJsonFile(jsonFile) }.getOrNull() ?: return null

        // 版本 JSON 的 id 字段可能与目录名不一致（用户手工改名）。
        // 以**目录名**为准：启动时拼的路径用的是目录名，JSON 里的 id 只是自述。
        val inherits = Json.stringAt(root, "inheritsFrom")?.takeIf { it.isNotBlank() }
        val rawType = Json.stringAt(root, "type")

        return GameVersion(
            id = id,
            type = VersionType.from(rawType),
            rawType = rawType,
            mainClass = Json.stringAt(root, "mainClass"),
            inheritsFrom = inherits,
            releaseTime = Json.stringAt(root, "releaseTime"),
            // 父版本不在本机 → 这个版本没有完整的启动参数，界面要标出来并且不许点启动。
            missingParent = inherits != null && inherits !in ids,
        )
    }

    /**
     * 读并解析版本 JSON。
     *
     * 体积上限是防呆：`versions/1.21.4/1.21.4.json` 正常几十 KB，
     * 有人把整个游戏装进同名文件里的话，读进内存只会把界面卡死。
     */
    private fun parseJsonFile(file: File): Any? {
        if (file.length() > MAX_JSON_BYTES) {
            error("版本清单过大（${file.length()} 字节）: ${file.name}")
        }
        return Json.parse(file.readText(Charsets.UTF_8))
    }
}
