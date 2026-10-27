package org.cn.xiaofan.config

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * HMWLX 自己的配置。
 *
 * <p>与 headlessmc 的配置分开存放：headlessmc 的配置是它自己的 `DynamicConfig`
 * 体系，路径由 `AppFiles.getConfigDir()` 决定（`~/.config/headlessmc`）；
 * HMWLX 的配置属于 GUI 层，改坏了不该影响 headlessmc 的启动。
 *
 * <p>路径遵循 XDG 规范，与 headlessmc 的位置保持一致的风格：
 * - Linux：`$XDG_CONFIG_HOME/HMWLX`，未设置时 `~/.config/HMWLX`
 * - Windows：`%APPDATA%/HMWLX`
 * - macOS：`~/Library/Application Support/HMWLX`
 *
 * <p>当前只有一项 [KEY_LEGAL]：用户是否已同意开屏用户协议。
 * 后续新增配置项时，在这里加 key 常量与读写方法。
 */
object AppConfig {

    /** 配置文件所在目录名（大写，与产品名一致）。 */
    private const val DIR_NAME = "HMWLX"

    /** 文件名。 */
    private const val FILE_NAME = "config.properties"

    /**
     * 是否已同意用户协议。
     *
     * `true` 表示已同意，开屏不再弹窗；`false` 或缺失都表示需要弹窗。
     */
    const val KEY_LEGAL = "LEGAL"

    /**
     * 测试专用的目录覆盖。设为非空时，[configFile] 直接用它，不再按平台推。
     *
     * 生产环境保持 `null`。这样测试不用去反射改 `XDG_CONFIG_HOME`——
     * JDK 17+ 的模块系统会拦住对 `java.lang.ProcessEnvironment` 的修改。
     */
    @Volatile
    internal var overrideDirectory: File? = null

    /**
     * 读取「是否已同意用户协议」。
     *
     * 任何读不到的情况——文件不存在、格式坏掉、权限不足——都返回 `false`，
     * 也就是**倾向于弹窗**。协议必须让用户看到，宁可多弹一次，不能漏掉。
     */
    fun isLegalAccepted(): Boolean {
        val file = configFile() ?: return false
        return runCatching { readAll(file)[KEY_LEGAL]?.toBooleanStrictOrNull() ?: false }
            .getOrDefault(false)
    }

    /**
     * 记录用户已同意协议。
     *
     * 只改动 [KEY_LEGAL] 一项，其余已有的键原样保留 —— 用户可能手工往里加过东西，
     * 直接覆盖会把人家的内容冲掉。
     *
     * @return 写入是否成功。失败不抛异常：写不进去最多下次再弹一次，
     *         不该因此让用户用不了软件。
     */
    fun setLegalAccepted(accepted: Boolean = true): Boolean {
        val file = configFile() ?: return false
        return runCatching {
            val existing = if (file.isFile) readAll(file) else LinkedHashMap()
            existing[KEY_LEGAL] = accepted.toString()
            writeAll(file, existing)
            true
        }.getOrDefault(false)
    }

    /**
     * 配置文件路径，仅供诊断与日志使用。
     *
     * 拿不到用户目录时返回 `null`。
     */
    fun configPath(): String? = configFile()?.absolutePath

    /** 按平台解析配置文件位置。 */
    private fun configFile(): File? {
        overrideDirectory?.let { return File(it, FILE_NAME) }

        val os = System.getProperty("os.name").orEmpty().lowercase()
        val dir = when {
            os.contains("win") ->
                System.getenv("APPDATA")?.let { File(it, DIR_NAME) }

            os.contains("mac") ->
                File(System.getProperty("user.home"), "Library/Application Support/$DIR_NAME")

            else -> {
                val xdg = System.getenv("XDG_CONFIG_HOME")
                if (!xdg.isNullOrBlank()) File(xdg, DIR_NAME)
                else System.getProperty("user.home")?.let { File(it, ".config/$DIR_NAME") }
            }
        } ?: return null
        return File(dir, FILE_NAME)
    }

    /**
     * 读成有序 map。
     *
     * 不用 [java.util.Properties]：它会吞掉注释、重排顺序，
     * 而这份文件用户可能会手动编辑，保留原始顺序和注释更友好。
     * 解析规则只认 `key=value`，`#` 开头的行与空行跳过。
     */
    private fun readAll(file: File): MutableMap<String, String> {
        val result = LinkedHashMap<String, String>()
        if (!file.isFile) return result
        file.readLines(StandardCharsets.UTF_8).forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val idx = line.indexOf('=')
            if (idx <= 0) return@forEach
            result[line.substring(0, idx).trim()] = line.substring(idx + 1).trim()
        }
        return result
    }

    /**
     * 写回文件。
     *
     * 先写临时文件再原子替换：写到一半断电或被杀进程时，原文件不会被截断成空。
     */
    private fun writeAll(file: File, values: Map<String, String>) {
        val parent = file.parentFile ?: error("配置文件没有父目录: $file")
        if (!parent.isDirectory && !parent.mkdirs()) {
            error("无法创建配置目录: $parent")
        }

        val content = buildString {
            appendLine("# HMWLX 配置文件")
            appendLine("# 由程序自动维护，也可手动编辑。删除本文件等同于恢复默认设置。")
            appendLine("#")
            appendLine("# LEGAL=true  已同意用户协议，启动时不再弹窗")
            appendLine("# LEGAL=false 或缺失  启动时显示用户协议")
            values.forEach { (k, v) -> appendLine("$k=$v") }
        }

        val tmp = File(parent, "${file.name}.tmp")
        tmp.writeText(content, StandardCharsets.UTF_8)
        Files.move(
            tmp.toPath(),
            file.toPath(),
            StandardCopyOption.REPLACE_EXISTING,
            StandardCopyOption.ATOMIC_MOVE,
        )
    }
}
