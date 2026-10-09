package top.honorhook.xposed

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * DexKit 查找工具类（照 HC ScreenRecorderModule 实码 + Rain v00 过滤逻辑；docs/rain-hook-architecture.md §2.4）：
 * 双轨解析的"按特征兜底"线，特征 = 包名白名单/方法名/返回类型/参数个数/usingString，
 * 与直连反射结果按签名串去重合并再挂 hook。
 *
 * Bridge 进程级缓存：[withBridge] 同 apkPath 复用已创建的 [DexKitBridge]，防止版本漂移场景
 * （多轮挂载重试 × 多次查询）重复 create + 全 dex 索引重建（秒级重操作）；缓存后不 close，
 * 进程持有期释放交给系统（原 HC finally-close 的 use 语义因缓存复用而废弃）；
 * 创建处 synchronized + @Volatile 双重检查保证只 create 一次（调用方为 MountScheduler 后台线程）。
 */
object DexKitHelper {

    private const val TAG = "HonorHook"

    /** libdexkit.so 懒加载是否已成功（只试一次；失败则本轨整体不可用，但不影响直连反射轨）。 */
    @Volatile
    private var dexKitLoaded = false

    /** 进程级缓存的 DexKitBridge（缓存后不 close，释放交给系统）。 */
    @Volatile
    private var cachedBridge: DexKitBridge? = null

    /** [cachedBridge] 对应的 apkPath（命中同 apkPath 直复用；不同路径才重建缓存）。 */
    @Volatile
    private var cachedBridgeApkPath: String? = null

    /**
     * 懒加载 libdexkit.so。
     * 照 HC 实码：`System.loadLibrary("dexkit")`（Rain 另有按 nativeLibraryDir/libdexkit.so 绝对路径 System.load 的备选，未采用）。
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
     * 获取（或创建并缓存）DexKitBridge 后执行 [block]。
     * 同 [apkPath] 直复用缓存实例（防漂移场景重复 create + 全 dex 索引重建）；缓存后不 close
     * （原 HC finally-close 的 use 语义因缓存复用废弃），进程内目标 APK 恒定；真出现不同 apkPath
     * 时缓存整体替换为新桥（旧桥不 close，由系统回收）。
     *
     * @param apkPath 目标 APK 的 sourceDir（`param.applicationInfo.sourceDir`）——按磁盘 dex 分析，不走内存
     * @return block 返回值；库加载失败 / 桥创建失败 / 查询抛错时返回 null（兜底线失败不炸主流程）
     */
    fun <R> withBridge(apkPath: String, block: (DexKitBridge) -> R): R? {
        ensureDexKitLoaded()
        if (!dexKitLoaded) return null
        val bridge = bridgeFor(apkPath) ?: return null
        return try {
            block(bridge)
        } catch (t: Throwable) {
            Log.e(TAG, "DexKit 查询执行失败: $apkPath", t)
            null
        }
    }

    /** 取（或创建）DexKitBridge：命中已缓存 apkPath 直复用；未命中在 synchronized + @Volatile 双重检查下创建并缓存（并发首次只 create 一次）。 */
    private fun bridgeFor(apkPath: String): DexKitBridge? {
        if (cachedBridgeApkPath == apkPath) cachedBridge?.let { return it }
        synchronized(this) {
            if (cachedBridgeApkPath == apkPath) return cachedBridge
            val bridge = try {
                // HC 实码（dexkit 2.2.0）：DexKitBridge.create(sourceDir)；Rain 旧版用的是 new DexKitBridge(...)
                DexKitBridge.create(apkPath)
            } catch (t: Throwable) {
                Log.e(TAG, "DexKitBridge.create 失败: $apkPath", t)
                return null
            }
            cachedBridge = bridge
            cachedBridgeApkPath = apkPath
            return bridge
        }
    }

    /**
     * 按特征查找方法并过滤。
     *
     * 特征全可选、AND 组合：[packages] 包名白名单 / [methodName] 方法名（混淆后可能失配，可留空）/
     * [returnType] 返回类型（DexKit 内部类型名，如 "boolean"、"java.lang.String"）/ [paramCount] 参数个数 /
     * [usingStrings] 方法体引用字符串（版本适配最稳，照 HC FRAME_METHOD_MARKER 用法）。
     *
     * API 实证状态（对照 HC 实码 reference/HyperChanger/.../ScreenRecorderModule.kt）：
     * ✅ 已验：`DexKitBridge.create`、`bridge.findMethod(FindMethod)`、`FindMethod.create()`、
     *   `MethodMatcher.create().usingStrings(...)`、`MethodInfo.getMethodInstance(loader)`、`bridge.close()`
     * ⚠️ 待真机：`FindMethod.searchPackages(...)`、`MethodMatcher.name(...)/returnType(...)/paramCount(...)`
     *   （DexKit 2.x 通用 API 编写，HC 实码未出现）
     *
     * @return 已反射解析并过滤的 [Method] 列表（非接口/非抽象/非 native/非 static + 签名串去重），可能为空
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

        // 已验证（HC）：bridge.findMethod(FindMethod) 返回 List，命中对象经 getMethodInstance(loader) 惰性解析出反射 Method
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