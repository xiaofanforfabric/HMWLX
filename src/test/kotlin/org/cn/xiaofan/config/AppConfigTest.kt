package org.cn.xiaofan.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * [AppConfig] 的解析与写回逻辑测试。
 *
 * <p>用 [AppConfig.overrideDirectory] 把配置目录指到临时目录。
 * 不用改环境变量的方式：JDK 17+ 的模块系统禁止反射修改
 * `java.lang.ProcessEnvironment`，那条路走不通。
 * 也不为此给测试 JVM 加 `--add-opens`：为测试放宽生产环境的模块限制不值得。
 */
class AppConfigTest {

    @Test
    fun `缺失配置文件时视为未同意`(@TempDir tmp: Path) = withOverride(tmp) {
        assertFalse(AppConfig.isLegalAccepted(), "配置文件不存在时必须返回 false（倾向于弹窗）")
    }

    @Test
    fun `LEGAL 为 false 时视为未同意`(@TempDir tmp: Path) = withOverride(tmp) {
        writeConfig(tmp, "LEGAL=false")
        assertFalse(AppConfig.isLegalAccepted())
    }

    @Test
    fun `LEGAL 为 true 时视为已同意`(@TempDir tmp: Path) = withOverride(tmp) {
        writeConfig(tmp, "LEGAL=true")
        assertTrue(AppConfig.isLegalAccepted())
    }

    @Test
    fun `LEGAL 值非法时视为未同意`(@TempDir tmp: Path) = withOverride(tmp) {
        writeConfig(tmp, "LEGAL=yes-please")
        assertFalse(AppConfig.isLegalAccepted(), "无法解析的值必须回退为未同意")
    }

    @Test
    fun `忽略注释与空行`(@TempDir tmp: Path) = withOverride(tmp) {
        writeConfig(tmp, "# 这是注释\n\n  LEGAL = true  \n")
        assertTrue(AppConfig.isLegalAccepted(), "带空格的 key=value 也应能解析")
    }

    @Test
    fun `写回保留已有键`(@TempDir tmp: Path) = withOverride(tmp) {
        writeConfig(tmp, "SOMETHING=1\nLEGAL=false")
        assertTrue(AppConfig.setLegalAccepted(true))

        val text = configFile(tmp).readText()
        assertTrue(text.contains("SOMETHING=1"), "已有键被覆盖掉了：\n$text")
        assertTrue(text.contains("LEGAL=true"), "LEGAL 未更新：\n$text")
    }

    @Test
    fun `写回后能读回`(@TempDir tmp: Path) = withOverride(tmp) {
        assertFalse(AppConfig.isLegalAccepted())
        assertTrue(AppConfig.setLegalAccepted(true))
        assertTrue(AppConfig.isLegalAccepted())
    }

    @Test
    fun `目录不存在时自动创建`(@TempDir tmp: Path) = withOverride(tmp) {
        val dir = configFile(tmp).parentFile
        assertFalse(dir.exists(), "前置条件：目录不该存在")
        assertTrue(AppConfig.setLegalAccepted(true))
        assertTrue(dir.isDirectory, "配置目录未被创建")
        assertTrue(AppConfig.isLegalAccepted())
    }

    @Test
    fun `写回不残留临时文件`(@TempDir tmp: Path) = withOverride(tmp) {
        AppConfig.setLegalAccepted(true)
        val leftover = configFile(tmp).parentFile.listFiles().orEmpty().filter { it.name.endsWith(".tmp") }
        assertEquals(emptyList<File>(), leftover, "原子写用的临时文件没有被清理")
    }

    private fun configFile(tmp: Path) = tmp.resolve("HMWLX/config.properties").toFile()

    private fun writeConfig(tmp: Path, content: String) {
        val file = configFile(tmp)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    /** 把 [AppConfig] 的目录指到临时目录，执行完还原。 */
    private fun withOverride(tmp: Path, block: () -> Unit) = synchronized(LOCK) {
        val original = AppConfig.overrideDirectory
        AppConfig.overrideDirectory = tmp.resolve("HMWLX").toFile()
        try {
            block()
        } finally {
            AppConfig.overrideDirectory = original
        }
    }

    private companion object {
        val LOCK = Any()
    }
}
