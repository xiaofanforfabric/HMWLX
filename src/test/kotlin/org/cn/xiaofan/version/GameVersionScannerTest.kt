package org.cn.xiaofan.version

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

/**
 * [GameVersionScanner] 的目录解析规则测试。
 *
 * <p>**不启动 Quarkus**：扫描是纯文件操作，把游戏目录指到临时目录就能测。
 * 这一点是刻意的 —— 版本列表必须在容器挂掉时也能用，测试结构就该照着这个
 * 不变式写，而不是顺手先用容器。
 */
class GameVersionScannerTest {

    @Test
    fun `没有 versions 目录时返回空列表`(@TempDir tmp: Path) {
        assertEquals(emptyList<GameVersion>(), GameVersionScanner.scan(tmp.toFile()))
    }

    @Test
    fun `gameDir 为 null 时返回空列表`() {
        assertEquals(emptyList<GameVersion>(), GameVersionScanner.scan(null))
    }

    @Test
    fun `只有文件夹没有 json 的版本不算已安装`(@TempDir tmp: Path) {
        versionDir(tmp, "1.21.4")
        assertTrue(GameVersionScanner.scan(tmp.toFile()).isEmpty(), "空目录不能被当成可用版本")
    }

    @Test
    fun `解析原版版本并取出类型与主类`(@TempDir tmp: Path) {
        version(tmp, "1.21.4", """{"id":"1.21.4","type":"release","mainClass":"net.minecraft.client.main.Main","releaseTime":"2024-12-03T10:12:57+00:00"}""")

        val version = GameVersionScanner.scan(tmp.toFile()).single()
        assertEquals("1.21.4", version.id)
        assertEquals(VersionType.RELEASE, version.type)
        assertEquals("net.minecraft.client.main.Main", version.mainClass)
        assertEquals("2024-12-03T10:12:57+00:00", version.releaseTime)
        assertNull(version.inheritsFrom)
        assertTrue(version.launchable)
    }

    @Test
    fun `加载器自己写的类型原样保留在 rawType 里`(@TempDir tmp: Path) {
        version(tmp, "fabric-loader-0.16.9-1.21.4", """{"id":"x","type":"fabric","inheritsFrom":"1.21.4"}""")
        version(tmp, "1.21.4", """{"id":"1.21.4","type":"release"}""")

        val loader = GameVersionScanner.scan(tmp.toFile()).first { it.id.startsWith("fabric") }
        assertEquals(VersionType.OTHER, loader.type)
        assertEquals("fabric", loader.rawType, "未知类型必须带上原值，不能只说「未知」")
    }

    @Test
    fun `父版本在本机时版本可用`(@TempDir tmp: Path) {
        version(tmp, "1.21.4", """{"id":"1.21.4","type":"release"}""")
        version(tmp, "fabric-1.21.4", """{"id":"fabric-1.21.4","inheritsFrom":"1.21.4"}""")

        val loader = GameVersionScanner.scan(tmp.toFile()).first { it.id == "fabric-1.21.4" }
        assertEquals("1.21.4", loader.inheritsFrom)
        assertFalse(loader.missingParent)
        assertTrue(loader.launchable)
    }

    @Test
    fun `父版本缺失时标记为不可启动`(@TempDir tmp: Path) {
        version(tmp, "fabric-1.21.4", """{"id":"fabric-1.21.4","inheritsFrom":"1.21.4"}""")

        val loader = GameVersionScanner.scan(tmp.toFile()).single()
        assertTrue(loader.missingParent, "父版本不在本机，必须标记出来")
        assertFalse(loader.launchable, "拼不出完整启动参数的版本不许点启动")
    }

    @Test
    fun `坏掉的 json 不拖垮整张列表`(@TempDir tmp: Path) {
        version(tmp, "1.21.4", """{"id":"1.21.4","type":"release"}""")
        version(tmp, "broken", """{"id": "broken", """)

        val versions = GameVersionScanner.scan(tmp.toFile())
        assertEquals(listOf("1.21.4"), versions.map { it.id }, "一个坏文件不该让其他版本消失")
    }

    @Test
    fun `按 id 排序且大小写不敏感`(@TempDir tmp: Path) {
        listOf("b1", "A1", "a2").forEach { version(tmp, it, """{"id":"$it","type":"release"}""") }
        assertEquals(
            listOf("A1", "a2", "b1"),
            GameVersionScanner.scan(tmp.toFile()).map { it.id },
        )
    }

    /** 建出 `versions/<id>/<id>.json`，内容为 [json]。 */
    private fun version(tmp: Path, id: String, json: String) {
        versionDir(tmp, id).apply { File(this, "$id.json").writeText(json) }
    }

    private fun versionDir(tmp: Path, id: String): File =
        File(tmp.toFile(), "versions/$id").apply { mkdirs() }
}
