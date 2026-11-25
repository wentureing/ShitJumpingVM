package com.nextvm.core.virtualization.diag

/**
 * 反射辅助：沿类继承链取字段并设为可访问。诊断代码共用一份。
 *
 * 语义来自 `VirtualApplicationManager` 原实现：**遍历父类**，都找不到才抛
 * `NoSuchFieldException`。不要改成只查本类——那会让继承字段的调用点开始抛异常。
 *
 * 详见 docs/代码镜像/core/virtualization/diag/Reflect.md
 */
internal fun fieldOf(cls: Class<*>, name: String): java.lang.reflect.Field {
    var c: Class<*>? = cls
    while (c != null) {
        try {
            return c.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            c = c.superclass
        }
    }
    throw NoSuchFieldException(name + " on " + cls.name)
}
