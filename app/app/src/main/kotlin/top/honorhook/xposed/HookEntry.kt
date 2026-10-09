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
 *
 * UI↔hook 配置通信链路（照 HC 模式）：
 * - onPackageLoaded 每次现读远程 prefs（daemon 快照缓存于本地 map，开销极小），对比 LOGCAT 可验链路；
 * - KEY_HOOK_ENABLED 变化经 LSPosedRemotePreferences 推送回调，此处注册热监听并 log（照 HC L945-974 / L1305-1337）；
 * - [DemoHook] 演示 "intercept 内每次现读开关决定放行/短路"（照 DockWmsHook.kt:113-115）——UI 开关切换不用重启进程即生效。
 * 目标分发结构留 TODO —— 待 system-files 逆向分析确定荣耀目标包名/类后填充。
 */
class HookEntry : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        log(Log.DEBUG, TAG, "onPackageLoaded: ${param.packageName}")
        // 目标分发 = 下方 TargetRegistry 段（每包回调分发 + 注册表按包名查 + installed 对象级防重装）
        // TODO(逆向后): 早期 hook 点（如 ActivityThread 阶段）在此注册
        runCatching {
            // 照 HC HyperSystemUiModule L663：每次回调现读远程 prefs（hook 侧只读实现）
            val prefs = getRemotePreferences(REMOTE_PREFERENCE_GROUP)
            val enabled = prefs.getBoolean(KEY_HOOK_ENABLED, false)
            log(Log.INFO, TAG, "hook_enabled = $enabled (${param.packageName})")
            registerHookEnabledListener(prefs)
            installDemoHook(param.defaultClassLoader, prefs)
        }.onFailure { error ->
            log(Log.ERROR, TAG, "Could not install hooks for ${param.packageName}", error)
        }

        // ---------- 目标分发骨架（融合 Rain 入口链 + HC 防重装，docs/rain-hook-architecture.md §1.1 / docs/hc-hook-structure.md §1.2）----------
        // 分发链：每包回调都尝试 find + install（**无 isFirstPackage 门控**——GlassRestoreHook 同一 object 注册 3 包名键，
        //   别名包共享进程时首包回调即被门控拦掉，其余键永不装载；每包回调带各自包的 loader，类解析按包分进程命中）。
        //   防重装交给注册表**对象级判定**（installed 存目标对象，同一对象多键共享安装状态）：find 返回值对象即判定对象。
        //   → TargetRegistry.find 按包名查 → 命中且目标对象未安装 → target.install(this, param) + 标记已装。
        //   跨进程语义不变：每个 hook 进程有独立 TargetRegistry 静态副本（setId 幂等防同进程重复挂载）。
        // 新增目标：新建 TargetHook 实现并注册进 TargetRegistry（静态注册），此处零改动。
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
        // TODO(逆向后): 目标分发主逻辑——校验包名后加载对应 hook 实现类
        // 结构参考（照 NG GuardModule.onPackageReady）:
        // if (param.packageName != "com.hihonor.xxx") return
        // try {
        //     XxxHooks(this, param.classLoader).install()
        // } catch (t: Throwable) {
        //     log(Log.ERROR, TAG, "hook 初始化失败: ${param.packageName}", t)
        // }
    }

    private var hookEnabledListenerInstalled = false
    private var demoHookInstalled = false

    /**
     * 注册 KEY_HOOK_ENABLED 热监听（每进程只注册一次）。
     * 回调线程 = daemon binder 线程；此处仅 log，涉及 UI 时须 Handler(Looper.getMainLooper()).post（照 HC L1329）。
     */
    private fun registerHookEnabledListener(prefs: SharedPreferences) {
        if (hookEnabledListenerInstalled) return
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_HOOK_ENABLED) {
                log(Log.INFO, TAG, "hook_enabled changed to ${prefs.getBoolean(KEY_HOOK_ENABLED, false)}")
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        hookEnabledListenerInstalled = true
        log(Log.DEBUG, TAG, "hook_enabled change listener registered")
    }

    /**
     * 通信链路演示 hook（照 DockWmsHook.kt:113-115 每次现读模式，拦截后放行原逻辑）：
     * 对作用域内任一 app 的 Application.onCreate，每次触发现读 hook_enabled——
     * 关闭 = 短路演示负载（仅放行原始执行），开启 = 放行 + 打印演示日志。
     * 不缓存开关到静态变量：UI 切开关后下一次触发即生效，无需重启进程。
     * 这是 UI↔hook 链路验收用的最小样例；真实功能 hook 替换 installXxxHook 即可。
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
                val result = chain.proceed() // 永远放行原始 onCreate
                if (prefs.getBoolean(KEY_HOOK_ENABLED, false)) {
                    log(Log.INFO, TAG, "[demo] hook_enabled=true: 演示负载执行（仅日志，无副作用）")
                }
                result
            }
        demoHookInstalled = true
        log(Log.DEBUG, TAG, "demo hook installed on Application.onCreate")
    }

    // ---------- 双轨目标解析骨架（照 Rain 番茄适配器 x00.c 策略，docs/rain-hook-architecture.md §2.4）----------
    // 荣耀目标大概率混淆：第一轨直连反射优先（loader.loadClass 按已知类名/方法名，最快、零 so 依赖）；
    // 第一轨失败/查不到时转第二轨 DexKit 按特征兜底（DexKitHelper.withBridge 直读目标 APK sourceDir 的 dex，
    // 包名白名单 + 方法名等特征查询，结果反射过滤后返回）——扛"同一方法被混淆改名/ROM 版本换了位置"。
    // 目标确定后在此调用：
    //   val m = resolveTargetMethod(param.defaultClassLoader, "com.hihonor.xxx.Yyy", "isVip",
    //       apkPath = param.applicationInfo.sourceDir)
    //   if (m != null) hook(m).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
    //       .setId("honorhook:isVip").intercept { chain -> chain.proceedWith(chain.thisObject, arrayOf(true)) }
    //   else log(Log.WARN, TAG, "未解析到目标方法 com.hihonor.xxx.Yyy#isVip")

    /**
     * 双轨解析目标方法：先 `loader.loadClass` 直连反射（try-catch），失败转 DexKit 按特征兜底。
     *
     * @param loader     目标进程 ClassLoader（param.defaultClassLoader / param.classLoader）
     * @param className  已知类名；直连失败时其包名自动作为 DexKit 包名白名单
     * @param methodName 方法名；直连按名匹配 declaredMethods，DexKit 兜底按同名特征查
     * @param apkPath    目标 APK 的 sourceDir（param.applicationInfo.sourceDir）；null 时跳过 DexKit 兜底
     * @return 命中的 [Method]；两条线都失败返回 null（只记日志不炸）
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