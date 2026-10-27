package org.cn.xiaofan.util

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfSystemProperty

/**
 * [Browser] 的可用性检查。
 *
 * <p>只在有图形环境时跑：无头 CI 上打开浏览器必然失败，那是环境限制而非代码缺陷，
 * 用系统属性 `hmwlx.test.browser=true` 显式开启。
 *
 * <p>测试用 `about:blank`，不会真的访问网络，也不会弹出需要用户操作的页面。
 */
class BrowserTest {

    @Test
    @EnabledIfSystemProperty(named = "hmwlx.test.browser", matches = "true")
    fun `能拉起默认浏览器`() {
        val opened = Browser.open("about:blank")
        assertTrue(opened, "无法拉起默认浏览器，检查 xdg-open / Desktop 支持")
    }

    @Test
    fun `非图形环境下不抛异常`() {
        // 无论开得开不了，都必须安全返回，不能把调用方炸掉
        val result = runCatching { Browser.open("about:blank") }
        assertTrue(result.isSuccess, "Browser.open 不应抛异常: ${result.exceptionOrNull()}")
    }
}
