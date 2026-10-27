package org.cn.xiaofan.bootstrap

import jakarta.enterprise.inject.Instance

/**
 * 通过 Arc（Quarkus 的 CDI 实现）按类型查 bean。
 *
 * `io.quarkus.arc.Arc` 在部署期才生成实现，编译期引用容易让 IDE 报红，
 * 因此走反射。这里只有两个调用，失败会明确抛出 [IllegalStateException]。
 *
 * 注意 [io.quarkus.arc.ArcContainer.select] 的签名是
 * `select(Class<T>, Annotation...)` —— **可变参数**。
 * 反射调用时必须显式传一个 `Array<Annotation>`，否则会抛
 * `NoSuchMethodException: ArcContainerImpl.select(java.lang.Class)`。
 */
internal object ArcInstances {

    private const val ARC_CLASS = "io.quarkus.arc.Arc"

    /**
     * 按类型取 [Instance]。
     *
     * 永不返回 null，是否满足用 `isUnsatisfied` 判断。
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> byType(type: Class<T>): Instance<T> {
        try {
            val arc = Class.forName(ARC_CLASS)
            val container = arc.getMethod("container").invoke(null)
                ?: throw IllegalStateException("Arc.container() 返回 null，容器可能尚未启动")

            // select(Class<T>, Annotation...) —— 第二个参数是可变的注解数组
            return container.javaClass
                .getMethod("select", Class::class.java, Array<Annotation>::class.java)
                .invoke(container, type, emptyArray<Annotation>()) as Instance<T>
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "找不到 $ARC_CLASS，检查 headlessmc 依赖是否完整引入", e
            )
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("通过 Arc 查找 bean 失败: ${type.name}", e)
        }
    }
}
