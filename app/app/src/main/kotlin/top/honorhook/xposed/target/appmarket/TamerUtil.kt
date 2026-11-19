package top.honorhook.xposed.target.appmarket

/**
 * 荣耀市场净化·tamer 1.4.11 移植共用小工具（反射兜底，全部吞异常语义由调用方保证）。
 * tamer 原实现用 XposedHelpers.callMethod/getObjectField；libxposed 102 无此门面，等价反射。
 */
internal object TamerUtil {

    /** 按名字+参数个数+运行时类型匹配公开方法调用；找不到/失败返回 null。 */
    fun callMethod(target: Any?, name: String, vararg args: Any?): Any? {
        if (target == null) return null
        return runCatching {
            val methods = target.javaClass.methods.filter { it.name == name && it.parameterCount == args.size }
            for (m in methods) {
                if (argsMatches(m.parameterTypes, args)) return@runCatching m.invoke(target, *args)
            }
            null
        }.getOrNull()
    }

    /** 公开字段优先、Declared 兜底读字段；失败返回 null。 */
    fun readField(target: Any?, name: String): Any? {
        if (target == null) return null
        return runCatching { target.javaClass.getField(name).get(target) }
            .recoverCatching { target.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(target) }
            .getOrNull()
    }

    fun className(obj: Any?): String = obj?.javaClass?.name ?: ""

    fun argsMatches(paramTypes: Array<Class<*>>, args: Array<out Any?>): Boolean =
        paramTypes.indices.all { i ->
            val a = args.getOrNull(i) ?: return@all true
            val boxed = paramTypes[i].takeIf { it.isPrimitive }?.let {
                when (it) {
                    java.lang.Boolean.TYPE -> java.lang.Boolean::class.java
                    java.lang.Integer.TYPE -> java.lang.Integer::class.java
                    java.lang.Long.TYPE -> java.lang.Long::class.java
                    java.lang.Double.TYPE -> java.lang.Double::class.java
                    java.lang.Float.TYPE -> java.lang.Float::class.java
                    else -> it
                }
            } ?: paramTypes[i]
            boxed.isInstance(a)
        }
}
