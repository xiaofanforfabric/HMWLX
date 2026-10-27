package org.cn.xiaofan.account

import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * 设备码捕获的实测。
 *
 * <p>这是整个微软登录方案唯一不确定的地方：设备码是 headlessmc 通过
 * `Console.write("To login visit <url>")` 输出的，我们靠前缀抓取。
 * 一旦 headlessmc 改了这行文本，登录就静默失效。
 *
 * <p>本测试只验证「能拿到链接」，不去完成授权：拿到链接后主动中断，
 * 不让它真的等到超时。
 */
class MicrosoftDeviceCodeTest {

    @Test
    @Timeout(value = 90, unit = TimeUnit.SECONDS)
    fun `能捕获到微软设备码链接`() {
        HeadlessMcBootstrap.start()

        val captured = AtomicReference<String?>(null)

        // 登录会一直阻塞等待授权，这里用线程 + 中断来「拿到链接就撤」
        val thread = Thread {
            runCatching {
                MicrosoftLogin.login { url -> captured.set(url) }
            }
        }
        thread.isDaemon = true
        thread.start()

        // 给足申请设备码的时间（含网络往返）
        val deadline = System.currentTimeMillis() + 60_000
        while (captured.get() == null && System.currentTimeMillis() < deadline && thread.isAlive) {
            Thread.sleep(200)
        }
        thread.interrupt()

        val url = captured.get()
        println("=== 捕获到的设备码链接: $url ===")

        if (url == null) {
            error(
                "没抓到设备码链接。若 headlessmc 改了输出格式，需要同步更新 " +
                    "MicrosoftLogin.DEVICE_CODE_PREFIX"
            )
        }

        // 设备码链接应当指向微软的登录地址
        assert(url.contains("microsoft.com") || url.contains("live.com")) {
            "设备码链接的域名不像微软登录页: $url"
        }
    }
}
