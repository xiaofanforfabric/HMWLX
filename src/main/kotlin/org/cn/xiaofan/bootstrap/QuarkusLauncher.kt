package org.cn.xiaofan.bootstrap

/**
 * 对 `io.quarkus.runtime.Quarkus` 的反射封装。
 *
 * 为什么不直接 import：`Quarkus` 的静态方法在部署期才由 Quarkus 的 build step
 * 完整接线，直接引用会让 IDE 索引偶尔解析不到符号。反射集中在这一处，
 * 失败信息明确且可读。
 *
 * 用到的三个方法：
 * - `manualInitialize()` —— 解析并构建容器，不阻塞
 * - `manualStart()` —— 启动容器，不阻塞
 * - `asyncExit(int)` —— 异步关闭容器
 */
internal object QuarkusLauncher {

    private const val QUARKUS_CLASS = "io.quarkus.runtime.Quarkus"

    /** `manualInitialize()` + `manualStart()`。 */
    fun initializeAndStart() {
        val quarkus = loadQuarkus()
        invokeStatic(quarkus, "manualInitialize")
        invokeStatic(quarkus, "manualStart")
    }

    /** `asyncExit(0)`。 */
    fun asyncExit() {
        val quarkus = loadQuarkus()
        try {
            quarkus.getMethod("asyncExit", Int::class.javaPrimitiveType).invoke(null, 0)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("调用 Quarkus.asyncExit 失败", e)
        }
    }

    private fun loadQuarkus(): Class<*> =
        try {
            Class.forName(QUARKUS_CLASS)
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "找不到 $QUARKUS_CLASS，检查 headlessmc 依赖是否完整引入", e
            )
        }

    private fun invokeStatic(type: Class<*>, method: String) {
        try {
            type.getMethod(method).invoke(null)
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("调用 ${type.name}.$method() 失败", e)
        }
    }
}
