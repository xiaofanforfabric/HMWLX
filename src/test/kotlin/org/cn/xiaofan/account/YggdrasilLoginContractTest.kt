package org.cn.xiaofan.account

import java.io.File
import io.github.headlesshq.headlessmc.auth.AuthProvider
import io.github.headlesshq.headlessmc.auth.AuthProvider as AuthProviderBean
import io.github.headlesshq.headlessmc.auth.AuthService
import io.github.headlesshq.headlessmc.console.Console
import org.cn.xiaofan.bootstrap.HeadlessMcBootstrap
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Yggdrasil 登录的反射契约测试。
 *
 * <p>GUI 靠反射调 `YggdrasilAuthenticator.login(String, String, String, Console)`，
 * 这是 private 方法，上游一改签名就失效。本测试不真的登录（需要真实账号），
 * 只锁定：
 * 1. 那个方法确实存在，且参数类型符合预期
 * 2. 外置登录 provider 能从容器里取到
 *
 * <p>方法一旦被改名或改签名，这里立刻报红，而不是等到用户点登录才失败。
 */
class YggdrasilLoginContractTest {

    private val expectedParams = arrayOf(
        String::class.java,
        String::class.java,
        String::class.java,
        Console::class.java,
    )

    /**
     * 启动容器，失败时把完整异常链打到 stdout 再重新抛出。
     *
     * <p>Gradle 对测试失败只打印最外层一行（`IllegalStateException at ...:78`），
     * 容器到底卡在哪一步完全看不出来。CI 上尤其致命：日志拿不到本地复现，
     * 只能靠这一段输出定位。用 stdout 而不是断言信息，是因为 Gradle 会把
     * 标准输出原样保留在 `--info` 级别的日志里。
     */
    private fun startContainer() {
        try {
            HeadlessMcBootstrap.start()
        } catch (t: Throwable) {
            println("=== 容器启动失败 ===")
            println("状态: ${HeadlessMcBootstrap.currentState()}")
            println("java.home: ${System.getProperty("java.home")}")
            println("java.version: ${System.getProperty("java.version")}")
            println("user.dir: ${System.getProperty("user.dir")}")
            println("user.home: ${System.getProperty("user.home")}")
            println("java.class.path 条目数: ${System.getProperty("java.class.path")?.split(File.pathSeparator)?.size}")
            println("--- 异常链 ---")
            var cur: Throwable? = t
            var depth = 0
            while (cur != null && depth < 10) {
                println("[$depth] ${cur.javaClass.name}: ${cur.message}")
                if (depth < 3) cur.printStackTrace()
                cur = cur.cause?.takeIf { it !== cur }
                depth++
            }
            println("=== 容器启动失败 END ===")
            throw t
        }
    }

    @Test
    fun `YggdrasilAuthenticator 仍然有预期的 private login 方法`() {
        startContainer()

        val authenticator = provider().authenticator

        val method = authenticator.javaClass.declaredMethods.firstOrNull {
            it.name == "login" && it.parameterTypes.contentEquals(expectedParams)
        }

        assertNotNull(
            method,
            "找不到 login(String, String, String, Console)。" +
                "上游可能改了 YggdrasilAuthenticator 的实现，需要同步更新 YggdrasilLogin。\n" +
                "现有方法：${authenticator.javaClass.declaredMethods.map { it.name }.distinct().sorted()}",
        )

        // 必须是 private：这正是我们走反射的原因
        assertTrue(
            java.lang.reflect.Modifier.isPrivate(method!!.modifiers),
            "该方法已不是 private，说明上游可能主动开放了 API，可以改回直接调用",
        )

        // 返回类型应当是 Account
        assertEquals(
            io.github.headlesshq.headlessmc.auth.Account::class.java,
            method.returnType,
            "方法返回类型变了，反射调用的强转会失败",
        )
    }

    @Test
    fun `能从容器取到 yggdrasil 认证源`() {
        startContainer()

        val p = provider()
        assertEquals(
            AuthProvider.YGGDRASIL,
            p.name,
            "认证源名字应为 yggdrasil，实际是 ${p.name}",
        )
    }

    /**
     * 构建期的类路径契约：`AuthFactory.createYggdrasilAuthProvider` 用到的类型都得在。
     *
     * <p>这条比容器里数 provider 更早失败、也更直白。ArC 处理 `@Produces` 方法时，
     * 只要有一个参数类型解析不到，就把整个方法**静默丢掉**（不打日志、不报错），
     * 运行期表现就是「provider 莫名没注册」。最容易漏的是
     * `org.eclipse.microprofile.rest.client.RestClientBuilder`：它由
     * `io.quarkus:quarkus-rest-client` 提供，JitPack 上的 headlessmc 元数据把它标成
     * runtime，主工程不显式依赖就根本不在编译类路径上。
     */
    @Test
    fun `yggdrasil provider 工厂依赖的类型都在类路径上`() {
        val required = listOf(
            "org.eclipse.microprofile.rest.client.RestClientBuilder",
            "io.github.headlesshq.headlessmc.auth.yggdrasil.YggdrasilAuthProvider",
            "io.github.headlesshq.headlessmc.auth.yggdrasil.YggdrasilClientFactory",
            "io.github.headlesshq.headlessmc.auth.yggdrasil.YggdrasilAccount",
            "io.github.headlesshq.headlessmc.auth.cdi.AuthFactory",
        )

        val missing = required.filter {
            runCatching { Class.forName(it, false, javaClass.classLoader) }.isFailure
        }

        assertTrue(
            missing.isEmpty(),
            "类路径上缺这些类型，AuthFactory.createYggdrasilAuthProvider 会被 ArC 静默跳过：" +
                missing.joinToString("、") +
                "。RestClientBuilder 缺失时在 build.gradle 补 implementation 'io.quarkus:quarkus-rest-client'。",
        )
    }

    /**
     * 取 yggdrasil 认证源。取不到时把容器里的实际情况一并写进异常信息。
     *
     * <p>只抛一句"容器里没有 yggdrasil 认证源"在 CI 上没法定位问题 ——
     * 到底是容器没起来、provider 没注册、还是注册了但名字不同，三种情况
     * 现场看到的异常都一样。这里把所有已知 provider 打出来，一眼就能区分。
     */
    private fun provider(): AuthProviderBean {
        val service = try {
            HeadlessMcBootstrap.bean(AuthService::class.java)
        } catch (e: Throwable) {
            throw AssertionError(
                "取不到 AuthService bean，容器状态=${HeadlessMcBootstrap.currentState()}",
                e,
            )
        }

        val found = service.getProvider(AuthProvider.YGGDRASIL).orElse(null)
        if (found != null) return found

        // 走到这里说明 provider 没注册。把容器里实际有哪些 provider 列出来，
        // 顺便暴露 classpath 上是否真的有 auth-yggdrasil 的类。
        val providerClassOnPath = runCatching {
            Class.forName("io.github.headlesshq.headlessmc.auth.yggdrasil.YggdrasilAuthProvider").name
        }.getOrElse { "<不在 classpath 上: ${it.javaClass.simpleName}>" }

        throw AssertionError(
            buildString {
                appendLine("容器里没有 yggdrasil 认证源（查找键: ${AuthProvider.YGGDRASIL}）。")
                appendLine("容器状态: ${HeadlessMcBootstrap.currentState()}")
                appendLine("注册的 provider: ${runCatching { service.providers.map { it.name } }}")
                appendLine("默认 provider: ${runCatching { service.defaultProvider.name }}")
                appendLine("provider 数量: ${runCatching { service.providers.size }}")
                appendLine("YggdrasilAuthProvider 类: $providerClassOnPath")
                appendLine("YggdrasilClientFactory 类: ${runCatching {
                    Class.forName("io.github.headlesshq.headlessmc.auth.yggdrasil.YggdrasilClientFactory")
                        .name
                }.getOrElse { "<不在 classpath 上: ${it.javaClass.simpleName}>" }}")
                appendLine("user.home: ${System.getProperty("user.home")}")
                appendLine("java.version: ${System.getProperty("java.version")}")
                append(
                    "如果 YggdrasilAuthProvider 不在 classpath 上，说明 auth-yggdrasil 没被依赖进去；" +
                        "如果在但 provider 缺失，多半是 AuthFactory.createYggdrasilAuthProvider 的某个" +
                        "参数类型解析不到（例如缺 io.quarkus:quarkus-rest-client），" +
                        "ArC 会把整个 @Produces 方法静默跳过 —— 不打日志、不报错，只能这样反查。",
                )
            },
        )
    }
}
