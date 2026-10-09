package top.honorhook.xposed

import android.util.Log
import java.lang.reflect.Constructor
import java.lang.reflect.Method

/**
 * 双轨目标解析共享入口（从 HookEntry.resolveTargetMethod 提取为公开基建，供各 TargetHook 接线）。
 *
 * - 第一轨：直连反射（loader.loadClass + 方法名/参数个数/返回类型过滤）——最快、零 so 依赖；
 * - 第二轨：直连未命中且提供 [apkPath] 时，DexKit 按特征兜底（DexKitHelper.withBridge 磁盘 dex
 *   扫描 + 包名白名单/方法名/返回类型/参数个数过滤），对抗 OTA 混淆漂移（单字母类名被重新
 *   映射后直连失效的场景）。
 * 两轨全部失败返回 null（调用方归入 NOT_RESOLVED 懒加载重试，只记日志不炸宿主）。
 *
 * 纪律：所有类解析走 loadClass（不触发 <clinit>）——boot-loop 纪律（v2.3 事故模式）；
 * 本入口只在挂载/重试线程调用，禁止在 intercept 热路径调用（DexKit bridge 创建为秒级重操作）。
 */
object MethodResolver {

    private const val TAG = "HonorHook"

    /**
     * 解析目标方法（双轨）。
     *
     * @param loader     目标进程 ClassLoader（install 的 param.defaultClassLoader）
     * @param className  已知类名；直连失败时其包名自动作为 DexKit 包名白名单
     * @param methodName 方法名（DexKit 兜底同特征）
     * @param paramCount 参数个数（可选过滤，两轨同特征）
     * @param returnType 返回类型全名（可选过滤，如 "boolean"/"java.lang.String"，两轨同特征）
     * @param apkPath    目标 APK 的 sourceDir（`param.applicationInfo?.sourceDir`）；null 时跳过 DexKit 兜底
     */
    fun resolveMethod(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int? = null,
        returnType: String? = null,
        apkPath: String? = null,
    ): Method? {
        // 第一轨：直连反射（照 Rain ck0.S 语义：loadClass + 按名匹配，异常/查不到都只记日志转兜底）
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
        return DexKitHelper.withBridge(apk) { bridge ->
            DexKitHelper.findMethodByFeatures(
                bridge = bridge,
                loader = loader,
                packages = className.substringBeforeLast('.', "").takeIf { it.isNotBlank() }?.let { listOf(it) },
                methodName = methodName,
                returnType = returnType,
                paramCount = paramCount,
            ).firstOrNull() // 兜底语义：类名可能已被混淆重映射，按方法特征命中即采纳（findMethodByFeatures 已按特征过滤）
        }
    }

    /**
     * 解析目标构造器（仅直连；构造器场景 DexKit 收益低未提供——玻璃 ctor 任务直连已命中基线）。
     * 声明构造器解析不触发类初始化（与 loadClass 同纪律）。
     */
    fun resolveCtor(loader: ClassLoader, className: String, paramCount: Int): Constructor<*>? = runCatching {
        loader.loadClass(className).declaredConstructors.firstOrNull { it.parameterCount == paramCount }
    }.getOrNull()
}
