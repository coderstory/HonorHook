package top.honorhook.xposed

import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 双轨目标解析共享入口（从 HookEntry.resolveTargetMethod 提取为公开基建，供各 TargetHook 接线）。
 * 第一轨直连反射（最快、零 so 依赖）；未命中且提供 [apkPath] 时第二轨 DexKit 按特征兜底
 * （磁盘 dex 扫描 + 包名白名单 + 方法体字符串特征），对抗 OTA 混淆漂移（单字母类名被重映射后直连失效）。
 * **第二轨的范围纪律（2026-10-10 审计）**：包名可作白名单时按包名限定；**默认包类**（无包名，如 `u15`/`o52`）
 * 无法限定范围，**必须由调用方提供 `usingStrings` 字符串特征**才允许兜底，否则跳过（避免全 APK 同名方法误命中）。
 * 两轨失败返回 null，调用方归入 NOT_RESOLVED 懒加载重试，只记日志不炸宿主。
 *
 * 纪律：类解析只走 loadClass、不触发 <clinit>——boot-loop 纪律（v2.3 事故模式）；
 * 只在挂载/重试线程调用，禁止在 intercept 热路径调用（DexKit bridge 创建为秒级重操作）。
 * 缓存：第二轨结果（含 null）进程级缓存（[dexkitHits]/[dexkitMisses]）——版本漂移场景每轮重试
 * 不对同 key 重复做秒级磁盘扫描（挂载线程并发读写安全）；第一轨**不缓存**——调用便宜，
 * 且重试机制正靠它赌类稍后可加载（缓存 null 会让重试失去意义）。
 */
object MethodResolver {

    private const val TAG = "HonorHook"

    /** DexKit 兜底命中缓存（key = "$className#$methodName#${paramCount}_${returnType}"）。 */
    private val dexkitHits = ConcurrentHashMap<String, Method>()

    /** DexKit 兜底未命中 key 集合（null 结果也缓存；ConcurrentHashMap 禁 null value，未命中单记）——命中直返 null，免重复磁盘扫描。 */
    private val dexkitMisses = ConcurrentHashMap.newKeySet<String>()

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
            loader.loadClass(className).declaredMethods.firstOrNull { m ->
                m.name == methodName &&
                    (paramCount == null || m.parameterCount == paramCount) &&
                    (returnType == null || m.returnType.name == returnType)
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
        // 原实现把 packages 传成 null ⇒ DexKit 退化为"全 APK 按同名+同签名找第一个" ⇒ 极易命中无关类的方法，
        // 属于"看起来有兜底、实际有害"的假兜底。现改为：**必须提供"内容性锚点"才允许兜底**——即至少有一项
        // 混淆不改的约束（方法体字符串 / 参数类型序列 / 所在类字符串）；否则诚实跳过（仍可依赖直连轨 + 懒加载重试）。
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
        if (dexkitMisses.contains(cacheKey)) return null
        dexkitHits[cacheKey]?.let { return it }
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
        if (hit != null) dexkitHits[cacheKey] = hit else dexkitMisses += cacheKey
        return hit
    }

    /**
     * 解析目标构造器（仅直连；构造器场景 DexKit 收益低未提供——玻璃 ctor 任务直连已命中基线）。
     * declareConstructors 解析不触发类初始化（与 loadClass 同纪律）。
     */
    fun resolveCtor(loader: ClassLoader, className: String, paramCount: Int): Constructor<*>? = runCatching {
        loader.loadClass(className).declaredConstructors.firstOrNull { it.parameterCount == paramCount }
    }.getOrNull()
}
