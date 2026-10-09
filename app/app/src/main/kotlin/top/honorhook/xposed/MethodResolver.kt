package top.honorhook.xposed

import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/**
 * 双轨目标解析共享入口（从 HookEntry.resolveTargetMethod 提取为公开基建，供各 TargetHook 接线）。
 * 第一轨直连反射（最快、零 so 依赖）；未命中且提供 [apkPath] 时第二轨 DexKit 按特征兜底
 * （磁盘 dex 扫描 + 包名白名单等过滤），对抗 OTA 混淆漂移（单字母类名被重映射后直连失效）。
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
     */
    fun resolveMethod(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int? = null,
        returnType: String? = null,
        apkPath: String? = null,
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
        // 兜底缓存（仅第二轨；null 也缓存——防版本漂移场景每轮重试对同 key 重复做秒级磁盘扫描）
        val cacheKey = "$className#$methodName#${paramCount}_${returnType}"
        if (dexkitMisses.contains(cacheKey)) return null
        dexkitHits[cacheKey]?.let { return it }
        val hit = DexKitHelper.withBridge(apk) { bridge ->
            DexKitHelper.findMethodByFeatures(
                bridge = bridge,
                loader = loader,
                packages = className.substringBeforeLast('.', "").takeIf { it.isNotBlank() }?.let { listOf(it) },
                methodName = methodName,
                returnType = returnType,
                paramCount = paramCount,
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
