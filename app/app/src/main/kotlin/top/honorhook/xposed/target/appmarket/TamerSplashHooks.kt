package top.honorhook.xposed.target.appmarket

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 开屏广告拦截（tamer 1.4.11 SplashAdBlocker 移植，2026-11-19）。
 * 12 点全为混淆名（R8 单字母），宿主 OTA 必漂移——NOT_RESOLVED 随类加载重试、
 * 失败只体现在 installed N/M，不炸宿主。16.1.11.302 存活性见 scratch/tamer/adapt-report.md。
 */
object TamerSplashHooks : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"

    private fun task(id: String, cls: String, method: String, argc: Int, desc: String) =
        MountEngine.SimpleTask(
            id = id, className = cls, methodName = method, paramCount = argc, returnType = "void",
            description = desc,
        )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_SPLASH)) {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_SPLASH, "TamerSplashHooks")
            return
        }
        val pending = listOf(
            // 16.1.11.302 实测（scratch/tamer/adapt-report.md §3）：CachedSplashScreenService 主类
            // 反编译产物缺失标 UNKNOWN（cached.a#c/d 已覆盖缓存路径，放弃）；v3.a#d(3) 并入 d(5)；
            // fg7/kg7/utils.a#o 类/方法已删。
            task("market-tamer:splash.v3a.d5", "$PKG.main.splash.v3.a", "d", 5, "网络开屏协程 5 参→null"),
            task("market-tamer:splash.a.H", "$PKG.main.splash.a", "H", 1, "开屏展示入口→null"),
            task("market-tamer:splash.utils.h", "$PKG.main.splash.utils.a", "h", 3, "开屏工具（h）→null"),
            task("market-tamer:splash.cached.a.c", "$PKG.main.splash.cached.a", "c", 2, "缓存开屏（a#c）→null"),
            task("market-tamer:splash.cached.a.d", "$PKG.main.splash.cached.a", "d", 2, "缓存开屏（a#d）→null"),
        )
        val session = MountEngine.open(
            "TamerSplashHooks", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pending, module, param.defaultClassLoader) { t ->
            session.mount(t, module, param.defaultClassLoader) {
                session.judge(module, t, "market-tamer", "开屏已拦")
                null
            }
        }
    }
}
