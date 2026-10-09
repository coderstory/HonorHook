package top.honorhook.xposed

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * DexKit 查找工具类 —— 双轨目标解析的"按特征兜底"线（照 HC ScreenRecorderModule 实码 + Rain v00 过滤逻辑）。
 *
 * 策略背景（见 docs/rain-hook-architecture.md §2.4）：荣耀目标大概率混淆，直接反射优先、
 * DexKit 按特征（包名白名单/方法名/返回类型/参数个数/usingString）兜底，
 * 两类结果合并后按签名串去重再挂 hook。
 *
 * 用法示例：
 * ```
 * DexKitHelper.withBridge(param.applicationInfo.sourceDir) { bridge ->
 *     DexKitHelper.findMethodByFeatures(
 *         bridge = bridge,
 *         loader = param.defaultClassLoader,
 *         packages = listOf("com.hihonor.system"),
 *         methodName = "isVip",
 *         returnType = "boolean",
 *         paramCount = 1,
 *     ).forEach { method -> log(Log.INFO, "HonorHook", "命中: ${DexKitHelper.signatureOf(method)}") }
 * }
 * ```
 */
object DexKitHelper {

    private const val TAG = "HonorHook"

    /** libdexkit.so 懒加载是否已成功（只试一次，失败后本轨整体不可用，但不影响直连反射轨）。 */
    @Volatile
    private var dexKitLoaded = false

    /**
     * 懒加载 libdexkit.so。
     * HC 实码：`System.loadLibrary("dexkit")`（Rain 另有按 nativeLibraryDir/libdexkit.so 绝对路径
     * System.load 的备选加载法，未采用）。失败只记日志不抛——本轨只是兜底，直连反射仍然可用。
     */
    private fun ensureDexKitLoaded() {
        if (dexKitLoaded) return
        synchronized(this) {
            if (dexKitLoaded) return
            try {
                System.loadLibrary("dexkit")
                dexKitLoaded = true
            } catch (t: Throwable) {
                Log.e(TAG, "loadLibrary(\"dexkit\") 失败，DexKit 兜底线不可用", t)
            }
        }
    }

    /**
     * 创建 DexKitBridge 并保证用完关闭（use 模式）。
     *
     * @param apkPath 目标 APK 的 sourceDir（`param.applicationInfo.sourceDir`）——按磁盘 dex 分析，不走内存。
     * @param block   桥使用闭包（内部再调 [findMethodByFeatures]）。
     * @return block 的返回值；库加载失败 / 桥创建失败 / 查询抛错时返回 null（兜底线失败不炸主流程）。
     */
    fun <R> withBridge(apkPath: String, block: (DexKitBridge) -> R): R? {
        ensureDexKitLoaded()
        if (!dexKitLoaded) return null
        val bridge = try {
            // HC 实码（dexkit 2.2.0）：DexKitBridge.create(sourceDir)；Rain 旧版用的是 new DexKitBridge(...)
            DexKitBridge.create(apkPath)
        } catch (t: Throwable) {
            Log.e(TAG, "DexKitBridge.create 失败: $apkPath", t)
            return null
        }
        return try {
            block(bridge)
        } catch (t: Throwable) {
            Log.e(TAG, "DexKit 查询执行失败: $apkPath", t)
            null
        } finally {
            // HC 实码用 finally close 兜底释放
            runCatching { bridge.close() }
                .onFailure { t -> Log.w(TAG, "DexKitBridge.close 失败", t) }
        }
    }

    /**
     * 按特征查找方法并过滤。
     *
     * 特征全可选、AND 组合：
     * - [packages]   包名白名单（缩小扫描范围）
     * - [methodName] 方法名（混淆后可能匹配不到，可留空只靠其他特征）
     * - [returnType] 返回类型（DexKit 内部类型名，如 "boolean"、"java.lang.String"）
     * - [paramCount] 参数个数
     * - [usingStrings] 方法体内引用的字符串（版本适配最稳的特征，照 HC FRAME_METHOD_MARKER 用法）
     *
     * API 签名对照 HC 实码（reference/HyperChanger/app/src/main/java/btm/m/os4/systemuihook/ScreenRecorderModule.kt）：
     * - ✅ 已在 HC 实码验证：`DexKitBridge.create`、`bridge.findMethod(FindMethod)`、
     *   `FindMethod.create()`、`MethodMatcher.create().usingStrings(...)`、`MethodInfo.getMethodInstance(loader)`、`bridge.close()`
     * - ⚠️ 未在 HC 实码出现、按 DexKit 2.x 通用 API 编写：`FindMethod.searchPackages(...)`、
     *   `MethodMatcher.name(...)` / `returnType(...)` / `paramCount(...)` —— 待真机验证
     *
     * @return 已反射解析并过滤的 [Method] 列表（非接口/非抽象/非 native/非 static + 签名串去重），可能为空。
     */
    fun findMethodByFeatures(
        bridge: DexKitBridge,
        loader: ClassLoader,
        packages: List<String>? = null,
        methodName: String? = null,
        returnType: String? = null,
        paramCount: Int? = null,
        usingStrings: List<String>? = null,
    ): List<Method> {
        val matcher = MethodMatcher.create()
        // 已验证（HC）：usingStrings(vararg String)
        usingStrings?.takeIf { it.isNotEmpty() }
            ?.let { matcher.usingStrings(*it.toTypedArray()) }
        // TODO(待真机验证): matcher.name/returnType/paramCount 为 DexKit 2.x 通用 API，HC 实码只见过 usingStrings
        if (methodName != null) matcher.name(methodName)
        if (returnType != null) matcher.returnType(returnType)
        if (paramCount != null) matcher.paramCount(paramCount)

        val findMethod = FindMethod.create()
        // TODO(待真机验证): searchPackages 为 DexKit 2.x 通用 API，HC 实码未见（Rain 旧版语义为 searchPackages 白名单）
        packages?.takeIf { it.isNotEmpty() }
            ?.let { findMethod.searchPackages(*it.toTypedArray()) }
        findMethod.matcher(matcher)

        // 已验证（HC）：bridge.findMethod(FindMethod) 返回 List，命中对象 getMethodInstance(loader) 惰性解析出反射 Method
        val hits = bridge.findMethod(findMethod)
        val seen = HashSet<String>()
        return hits.mapNotNull { hit ->
            val method = runCatching { hit.getMethodInstance(loader) }
                .getOrElse { t ->
                    Log.w(TAG, "DexKit 命中反射解析失败（跳过该条）", t)
                    return@mapNotNull null
                }
            // 过滤（照 Rain v00.java:40-64）：非接口/非抽象/非 native/非 static
            val mod = method.modifiers
            if (Modifier.isInterface(mod) || Modifier.isAbstract(mod) ||
                Modifier.isNative(mod) || Modifier.isStatic(mod)
            ) {
                return@mapNotNull null
            }
            // 签名串去重（照 Rain v00.d）
            if (!seen.add(signatureOf(method))) {
                return@mapNotNull null
            }
            method
        }
    }

    /**
     * 方法签名串，作为去重/合并 key（照 Rain v00.d：`declaringClass#name(p1,p2):ret`）。
     * 直连反射结果与 DexKit 结果合并时用同一 key 取并集。
     */
    fun signatureOf(method: Method): String = buildString {
        append(method.declaringClass.name)
        append('#').append(method.name).append('(')
        method.parameterTypes.forEachIndexed { index, parameterType ->
            if (index > 0) append(',')
            append(parameterType.name)
        }
        append("):").append(method.returnType.name)
    }
}