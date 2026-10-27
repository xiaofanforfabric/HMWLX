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

    /**
     * 按泛型类型取 [Instance]，用于 `AuthStore<Account>` 这类带类型参数的 bean。
     *
     * [byType] 只接受 `Class`，擦除后变成 `AuthStore<*>`，与声明的
     * `AuthStore<Account>` 不匹配，CDI 会判定为「不满足」。
     *
     * Arc 提供的重载是：
     * `select(jakarta.enterprise.util.TypeLiteral<T>, Annotation...)`
     * —— 注意参数是 **`TypeLiteral`**，不是 `java.lang.reflect.Type`。
     * 传 `Type` 会因为找不到方法而抛 `NoSuchMethodException`。
     *
     * @param typeLiteral 用 `object : TypeLiteral<AuthStore<Account>>() {}` 构造
     * @param qualifiers 限定符，如离线账号存储需要 `@Offline`
     */
    @Suppress("UNCHECKED_CAST")
    fun <T : Any> byTypeLiteral(
        typeLiteral: jakarta.enterprise.util.TypeLiteral<T>,
        qualifiers: Array<Annotation> = emptyArray(),
    ): Instance<T> {
        try {
            val arc = Class.forName(ARC_CLASS)
            val container = arc.getMethod("container").invoke(null)
                ?: throw IllegalStateException("Arc.container() 返回 null，容器可能尚未启动")

            return container.javaClass
                .getMethod(
                    "select",
                    jakarta.enterprise.util.TypeLiteral::class.java,
                    Array<Annotation>::class.java,
                )
                .invoke(container, typeLiteral, qualifiers) as Instance<T>
        } catch (e: ClassNotFoundException) {
            throw IllegalStateException(
                "找不到 $ARC_CLASS，检查 headlessmc 依赖是否完整引入", e
            )
        } catch (e: ReflectiveOperationException) {
            throw IllegalStateException("通过 Arc 查找 bean 失败: $typeLiteral", e)
        }
    }
}
