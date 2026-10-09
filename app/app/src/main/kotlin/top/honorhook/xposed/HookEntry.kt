package top.honorhook.xposed

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import java.lang.reflect.Method
import top.honorhook.data.KEY_HOOK_ENABLED
import top.honorhook.data.REMOTE_PREFERENCE_GROUP
import top.honorhook.xposed.target.TargetRegistry

/**
 * libxposed 102 模块入口（注册于 META-INF/xposed/java_init.list）。
 * UI↔hook 链路：onPackageLoaded 每次现读远程 prefs（daemon 快照缓存于本地 map，LOGCAT 可验；
 * 照 HC L945-974/L1305-1337 热监听 + DockWmsHook.kt:113-115 每次现读模式）——UI 切开关不用重启进程。
 */
class HookEntry : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        log(Log.DEBUG, TAG, "onPackageLoaded: ${param.packageName}")
        // TODO(逆向后): 早期 hook 点（如 ActivityThread 阶段）在此注册
        runCatching {
            // 照 HC HyperSystemUiModule L663：每次回调现读远程 prefs（hook 侧只读实现）
            val prefs = getRemotePreferences(REMOTE_PREFERENCE_GROUP)
            val enabled = prefs.getBoolean(KEY_HOOK_ENABLED, true)
            log(Log.INFO, TAG, "hook_enabled = $enabled (${param.packageName})")
            registerHookEnabledListener(prefs)
            installDemoHook(param.defaultClassLoader, prefs)
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install hooks for ${param.packageName}", error)
        }

        // 目标分发（融合 Rain 入口链 + HC 防重装）：每包回调 find + install，**无 isFirstPackage 门控**
        // （GlassRestoreHook 3 键共享进程时首包门控会拦掉其余键）；防重装靠 TargetRegistry 对象级判定
        // （installed 存目标对象，多键共享；setId 幂等防同进程重复挂载）。新增目标只改 TargetRegistry，此处零改动。
        runCatching {
            val target = TargetRegistry.find(param.packageName)
            if (target != null && !TargetRegistry.isInstalled(target)) {
                target.install(this, param)
                TargetRegistry.markInstalled(target)
                log(Log.INFO, TAG, "target installed: ${target.packageName}")
            }
        }.onFailure { error ->
            log(Log.ERROR, TAG, "target install failed for ${param.packageName}", error)
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        log(Log.DEBUG, TAG, "onPackageReady: ${param.packageName}")
        // TODO(逆向后): packageReady 阶段目标分发（校验包名 → 加载 hook 实现，照 NG GuardModule.onPackageReady）
    }

    private var hookEnabledListenerInstalled = false
    private var demoHookInstalled = false

    /** 注册 KEY_HOOK_ENABLED 热监听（回调线程 = daemon binder 线程，仅 log；涉 UI 须 Handler(Looper.getMainLooper()).post，照 HC L1329）。 */
    private fun registerHookEnabledListener(prefs: SharedPreferences) {
        if (hookEnabledListenerInstalled) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_HOOK_ENABLED) {
                log(Log.INFO, TAG, "hook_enabled changed to ${prefs.getBoolean(KEY_HOOK_ENABLED, true)}")
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        hookEnabledListenerInstalled = true
        log(Log.DEBUG, TAG, "hook_enabled change listener registered")
    }

    /**
     * UI↔hook 链路验收最小样例（照 DockWmsHook.kt:113-115：每次 intercept 现读开关并**不缓存**
     * 到静态变量——切开关后下次触发即生效，无需重启进程）。
     */
    private fun installDemoHook(loader: ClassLoader, prefs: SharedPreferences) {
        if (demoHookInstalled) return
        val appClass = runCatching { loader.loadClass("android.app.Application") }.getOrNull() ?: return
        val onCreate = appClass.declaredMethods.firstOrNull {
            it.name == "onCreate" && it.parameterCount == 0
        } ?: return
        hook(onCreate)
            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE) // 拦截器抛异常不炸宿主
            .setId("honorhook:demo-switch-link") // 幂等 ID（框架用它防重复/日志标识）
            .intercept { chain ->
                val result = chain.proceed()
                if (prefs.getBoolean(KEY_HOOK_ENABLED, true)) {
                    log(Log.INFO, TAG, "[demo] hook_enabled=true: 演示负载执行（仅日志，无副作用）")
                }
                result
            }
        demoHookInstalled = true
        log(Log.DEBUG, TAG, "demo hook installed on Application.onCreate")
    }

    // 双轨目标解析见 MethodResolver（从本入口提取的公开基建）：直连反射优先，失败转 DexKit 按特征兜底（照 Rain x00.c，docs/rain-hook-architecture.md §2.4）。

    /**
     * 双轨解析目标方法（直连优先，失败转 DexKit 特征兜底；同构实现见 MethodResolver.resolveMethod）。
     * @param className 直连失败时其包名自动作为 DexKit 包名白名单
     * @param apkPath   目标 APK 的 sourceDir；null 时跳过 DexKit 兜底
     */
    private fun resolveTargetMethod(
        loader: ClassLoader,
        className: String,
        methodName: String,
        apkPath: String? = null,
    ): Method? {
        // 第一轨：直连反射优先（照 Rain ck0.S 语义：loadClass + 按方法名找，异常/查不到都只记日志转兜底）
        val direct = runCatching {
            loader.loadClass(className).declaredMethods.firstOrNull { it.name == methodName }
        }.onFailure { t ->
            log(Log.WARN, TAG, "直连反射未命中 ${className}#$methodName (${t::class.java.simpleName})，转 DexKit 兜底", t)
        }.getOrNull()
        if (direct != null) return direct

        // 第二轨：DexKit 按特征兜底（照 Rain v00.e / HC ScreenRecorderModule.findConfigMethods）
        val apk = apkPath ?: run {
            log(Log.WARN, TAG, "直连反射未命中且未提供 apkPath，跳过 DexKit 兜底: ${className}#$methodName")
            return null
        }
        // TODO(目标确定后调参): 可追加 returnType/paramCount/usingStrings 特征收紧命中面（见 DexKitHelper.findMethodByFeatures）
        return DexKitHelper.withBridge(apk) { bridge ->
            DexKitHelper.findMethodByFeatures(
                bridge = bridge,
                loader = loader,
                packages = className.substringBeforeLast('.', "").takeIf { it.isNotBlank() }?.let { listOf(it) },
                methodName = methodName,
            ).firstOrNull()
        }
    }

    private companion object {
        const val TAG = "HonorHook"
    }
}