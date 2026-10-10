package top.honorhook.xposed.core

import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** 双轨目标解析共享入口（从 HookEntry.resolveTargetMethod 提取为公开基建，供各 TargetHook 接线）。 */
object MethodResolver {

    private const val TAG = "HonorHook"

    /**
     * DexKit 兜底命中缓存：**按 ClassLoader 分桶**（弱键，不阻止 loader 被回收）。
     *
     * 分桶的理由：同一次特征查询在不同 ClassLoader 下可能命中不同类，混在一个平表里会串味；
     * 弱键的理由：缓存持有 Method ⇒ Class ⇒ ClassLoader 强引用链，进程内 loader 若被回收会泄漏。
     */
    private val hitBuckets = java.util.WeakHashMap<ClassLoader, ConcurrentHashMap<String, Method>>()

    /** DexKit 兜底未命中 key 集合（null 结果也缓存；ConcurrentHashMap 禁 null value，未命中单记）——命中直返 null，免重复磁盘扫描。 */
    private val missBuckets = java.util.WeakHashMap<ClassLoader, MutableSet<String>>()

    private fun hitBucket(loader: ClassLoader): ConcurrentHashMap<String, Method> =
        synchronized(hitBuckets) { hitBuckets.getOrPut(loader) { ConcurrentHashMap() } }

    private fun missBucket(loader: ClassLoader): MutableSet<String> = synchronized(missBuckets) {
        missBuckets.getOrPut(loader) {
            java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        }
    }

    /**
     * 解析目标方法（双轨；缓存语义见类注释）。
     * @param className 直连失败时其包名自动作为 DexKit 包名白名单
     * @param apkPath   目标 APK 的 sourceDir（`param.applicationInfo?.sourceDir`）；null 时跳过 DexKit 兜底
     * @param usingStrings 方法体内**引用的字符串常量**（混淆不改字符串，是最稳的特征；可选）
     * @param paramTypes   **参数类型序列**（按声明顺序；可含未被混淆的可读类名，比 paramCount 强得多；可选）
     * @param declaredClassStrings **所在类**引用的字符串常量（类名+方法名都被混淆时的兜底锚点；可选）
     * @param declaredClassFieldTypes **所在类的字段类型集合**（字段类型含可读类名，如 `common.DownloadInfo`；可选）
     */
    fun resolveMethod(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int? = null,
        returnType: String? = null,
        apkPath: String? = null,
        usingStrings: List<String>? = null,
        paramTypes: List<String>? = null,
        declaredClassStrings: List<String>? = null,
        declaredClassFieldTypes: List<String>? = null,
    ): Method? {
        // 第一轨：直连反射（照 Rain ck0.S 语义：loadClass + 按名匹配，异常/查不到只记日志转兜底；不缓存——重试靠它赌类稍后可加载）
        val direct = runCatching {
            val byName = loader.loadClass(className).declaredMethods.filter { m ->
                m.name == methodName &&
                    matchesParameters(m, paramCount, paramTypes) &&
                    (returnType == null || m.returnType.name == returnType)
            }
            // 桥接/合成方法与真实方法同签名，会把"唯一命中"判定搅成歧义 ⇒ 优先只看真实方法；
            // 仅当没有任何真实方法时才回退到合成方法（如协变返回值的桥接实现）。
            val candidates = byName.filter { !it.isSynthetic && !it.isBridge }.ifEmpty { byName }
            when (candidates.size) {
                0 -> null
                1 -> candidates[0]
                else -> {
                    // 同参数量、同返回值的重载且无 paramTypes 锚点：保留 firstOrNull 的既有权衡
                    // （不改变已可用目标的行为），但不再静默 —— 挂错方法是最难查的一类失效。
                    Log.w(
                        TAG,
                        "直连反射命中 ${candidates.size} 个同名同参数量方法（$className#$methodName），" +
                            "paramTypes 锚点不足，取第一个: ${DexKitHelper.signatureOf(candidates[0])}",
                    )
                    candidates[0]
                }
            }
        }.onFailure { t ->
            Log.w(TAG, "直连反射未命中 $className#$methodName（${t::class.java.simpleName}），转 DexKit 兜底", t)
        }.getOrNull()
        if (direct != null) return direct

        // 第二轨：DexKit 按特征兜底（apkPath 缺失时无法走磁盘 dex 扫描）
        val apk = apkPath ?: run {
            Log.w(TAG, "直连反射未命中且未提供 apkPath，跳过 DexKit 兜底: $className#$methodName")
            return null
        }
        // ★ 2026-10-10 审计修正：**默认包类**（className 不含 "."，如混淆名 `u15` / `o52`）无法用包名限定搜索范围，
        val pkg = className.substringBeforeLast('.', "").takeIf { it.isNotBlank() }
        val hasAnchor = !usingStrings.isNullOrEmpty() ||
            !paramTypes.isNullOrEmpty() ||
            !declaredClassStrings.isNullOrEmpty() ||
            !declaredClassFieldTypes.isNullOrEmpty()
        if (pkg == null && !hasAnchor) {
            Log.w(
                TAG,
                "DexKit 兜底跳过（默认包类且无内容锚点，避免全 APK 误命中）: $className#$methodName",
            )
            return null
        }
        // 兜底缓存（仅第二轨；null 也缓存——防版本漂移场景每轮重试对同 key 重复做秒级磁盘扫描）
        val cacheKey = buildString {
            append(className).append('#').append(methodName).append('#').append(paramCount).append('_').append(returnType)
            append("|p=").append(paramTypes?.joinToString(","))
            append("|s=").append(usingStrings?.joinToString(","))
            append("|cs=").append(declaredClassStrings?.joinToString(","))
            append("|cf=").append(declaredClassFieldTypes?.joinToString(","))
        }
        val missSet = missBucket(loader)
        if (missSet.contains(cacheKey)) return null
        val hitMap = hitBucket(loader)
        hitMap[cacheKey]?.let { return it }
        val hit = DexKitHelper.withBridge(apk) { bridge ->
            DexKitHelper.findMethodByFeatures(
                bridge = bridge,
                loader = loader,
                packages = pkg?.let { listOf(it) },
                methodName = methodName,
                returnType = returnType,
                paramCount = paramCount,
                paramTypes = paramTypes,
                usingStrings = usingStrings,
                declaredClassStrings = declaredClassStrings,
                declaredClassFieldTypes = declaredClassFieldTypes,
            ).firstOrNull() // 兜底语义：类名可能已被混淆重映射，按方法特征命中即采纳
        }
        if (hit != null) hitMap[cacheKey] = hit else missSet += cacheKey
        return hit
    }

    /**
     * 参数匹配：[paramTypes] 非空时按**参数类型名序列**精确匹配（可消解同 arity 的重载，
     * 如 `SystemUiHook` 的 `updateSubs(List)` / `updateSubs(MobileCallBack)`），
     * 为空时退化为按 [paramCount] 匹配。
     *
     * 类型名比较前先按 [PRIMITIVE_ALIASES] 归一：DexHint 里写 `int` 而实际声明是 `java.lang.Integer`
     * （或反之）时不该判为不匹配 —— 否则该挂载点会从"直连命中"退化成"未解析"，
     * 白吃一轮重试再交给 DexKit（而 DexKit 用的是同一份特征，同样是空转）。
     */
    private fun matchesParameters(m: Method, paramCount: Int?, paramTypes: List<String>?): Boolean {
        if (!paramTypes.isNullOrEmpty()) {
            val actual = m.parameterTypes
            return actual.size == paramTypes.size &&
                actual.indices.all { canonicalTypeName(actual[it].name) == canonicalTypeName(paramTypes[it]) }
        }
        return paramCount == null || m.parameterCount == paramCount
    }

    /** 基本类型与其包装类型的等价归一（`int` / `java.lang.Integer` 视为同一类型）。 */
    private fun canonicalTypeName(name: String): String = PRIMITIVE_ALIASES[name] ?: name

    private val PRIMITIVE_ALIASES = mapOf(
        "java.lang.Integer" to "int",
        "java.lang.Long" to "long",
        "java.lang.Boolean" to "boolean",
        "java.lang.Double" to "double",
        "java.lang.Float" to "float",
        "java.lang.Short" to "short",
        "java.lang.Byte" to "byte",
        "java.lang.Character" to "char",
    )
}
