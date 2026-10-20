package top.honorhook.xposed.core

import android.util.Log
import org.luckypray.dexkit.DexKitBridge
import org.luckypray.dexkit.query.FindMethod
import org.luckypray.dexkit.query.matchers.ClassMatcher
import org.luckypray.dexkit.query.matchers.MethodMatcher
import java.lang.reflect.Method

/** DexKit 特征查找工具（方法体字符串/参数类型/类字段类型四维锚点）。 */
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
     * @return block 返回值；库加载失败 / 桥创建失败 / 查询抛错时返回 null（兜底线失败不炸主流程）。
     *  **调用方注意：null ≠ "查了但没有命中"**——瞬时失败（native 未就绪/OOM）与真实空结果是
     *  两回事，把 null 混进空命中会把"待重试"永久缓存成"未命中"（评审 SHOULD-FIX #3）。
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
        synchronized(this) {
            // 快路径判定收进 synchronized：cachedBridge/cachedBridgeApkPath 双字段非原子，锁外读可能
            // 撞上"桥已换、路径未写"的中间态而复用错桥（评审 P4；同 apk 命中本就几乎必然持锁方刚写过）。
            if (cachedBridgeApkPath == apkPath) return cachedBridge
            val bridge = try {
                // HC 实码（dexkit 2.2.0）：DexKitBridge.create(sourceDir)；Rain 旧版用的是 new DexKitBridge(...)
                DexKitBridge.create(apkPath)
            } catch (t: Throwable) {
                Log.e(TAG, "DexKitBridge.create 失败: $apkPath", t)
                return null
            }
            // 旧桥的 native 句柄不随 GC 立即释放：apkPath 变化时显式关掉。
            // （进程内目标 APK 恒定，正常路径几乎不会走到这里。）
            val previous = cachedBridge
            cachedBridge = bridge
            cachedBridgeApkPath = apkPath
            if (previous != null) runCatching { previous.close() }
            return bridge
        }
    }

    /** DexKit 单次查询结果。[methods] 为已解析/已过滤/已去重的候选；[unresolvedCount] = 命中了但反射实例化失败而被丢弃的条数——非零说明候选集**不完整**，调用方不得据此做"唯一/并列"判定。 */
    internal data class FindResult(val methods: List<Method>, val unresolvedCount: Int)

    /**
     * 按查询计划查找方法并做可挂载性过滤（abstract/native 不可 hook；static **允许**）。
     *
     * [packages] 为空表示全 APK 搜索 —— 宽松轨（[QuerySpec] 只有内容锚点）必须这样：
     * 目标类可能已被 R8 挪到别的包（installer 的 `r12` → `com.android.packageinstaller.PackageInstallerActivity`）。
     *
     * 2026-10-20：删掉历史 `Modifier.isStatic` 过滤。那条是从 Rain 的 helper 原样抄来的场景取舍，
     * 在本模块是纯损失：14.0.38.301 的 installer 有 8 个实现改成了 static，DexKit 命中也全被丢弃。
     */
    internal fun findMethods(
        bridge: DexKitBridge,
        loader: ClassLoader,
        spec: QuerySpec,
        packages: List<String>? = null,
    ): FindResult {
        val matcher = MethodMatcher.create()
        // 方法体引用的字符串常量（最稳特征）
        spec.usingStrings?.takeIf { it.isNotEmpty() }
            ?.let { matcher.usingStrings(*it.toTypedArray()) }
        // 参数类型序列（比 paramCount 强：可含未被混淆的可读类名，如 common.DownloadInfo）
        spec.paramTypes?.takeIf { it.isNotEmpty() }
            ?.let { matcher.paramTypes(*it.toTypedArray()) }
        // 所在类特征（类名+方法名都被混淆时的兜底锚点）：类内字符串 / 类字段类型
        if (!spec.classStrings.isNullOrEmpty() || !spec.classFieldTypes.isNullOrEmpty()) {
            val classMatcher = ClassMatcher.create()
            spec.classStrings?.takeIf { it.isNotEmpty() }
                ?.let { classMatcher.usingStrings(*it.toTypedArray()) }
            spec.classFieldTypes?.forEach { classMatcher.addFieldForType(it) }
            matcher.declaredClass(classMatcher)
        }
        // 2026-10-10：以下三个为 DexKit 2.x 通用 API，已对 2.2.0-api.jar javap 核对存在
        spec.methodName?.let { matcher.name(it) }
        spec.returnType?.let { matcher.returnType(it) }
        spec.paramCount?.let { matcher.paramCount(it) }

        val findMethod = FindMethod.create()
        // searchPackages 为 DexKit 2.x 通用 API（Rain 旧版语义为 searchPackages 白名单）
        packages?.takeIf { it.isNotEmpty() }
            ?.let { findMethod.searchPackages(*it.toTypedArray()) }
        findMethod.matcher(matcher)

        // 已验证（HC）：bridge.findMethod(FindMethod) 返回 List，命中对象经 getMethodInstance(loader) 惰性解析出反射 Method
        val hits = bridge.findMethod(findMethod)
        val seen = HashSet<String>()
        // isHookable 过滤与签名去重是**确定性**排除（该条永远挂不上/重复同一条），不算候选集不完整；
        // 只有 getMethodInstance 失败（类型没加载齐/冷类未就绪）会留下"看不见的候选"，必须计数上报。
        var unresolved = 0
        val methods = hits.mapNotNull { hit ->
            val method = runCatching { hit.getMethodInstance(loader) }
                .getOrElse { t ->
                    unresolved++
                    Log.w(TAG, "DexKit 命中反射解析失败（跳过该条，候选集不完整）", t)
                    return@mapNotNull null
                }
            if (!DexHitSelector.isHookable(method)) return@mapNotNull null
            // 签名串去重（照 Rain v00.d）
            if (!seen.add(signatureOf(method))) {
                return@mapNotNull null
            }
            method
        }
        return FindResult(methods, unresolved)
    }

    /**
     * 方法签名串，作为去重/合并 key（照 Rain v00.d：`declaringClass#name(p1,p2):ret`）。
     * 直连反射结果与 DexKit 结果合并时用同一 key 取并集。
     * static 前缀：宽松轨已挂集合与「挂载点漂移命中」日志都靠它区分实例/静态实现——
     * 旧实例点被挂到 static 上时 `thisObject` 为 null，依赖 thisObject 的拦截器会静默空转，日志是唯一现场。
     */
    fun signatureOf(method: Method): String = buildString {
        if (java.lang.reflect.Modifier.isStatic(method.modifiers)) append("static ")
        append(method.declaringClass.name)
        append('#').append(method.name).append('(')
        method.parameterTypes.forEachIndexed { index, parameterType ->
            if (index > 0) append(',')
            append(parameterType.name)
        }
        append("):").append(method.returnType.name)
    }
}