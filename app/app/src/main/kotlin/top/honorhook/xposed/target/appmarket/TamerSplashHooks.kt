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
 * 开屏广告拦截（tamer 1.4.11 SplashAdBlocker 移植，2026-11-19；同日收口重构）。
 *
 * 原版 5 个混淆名散点（v3.a#d/splash.a#H/utils.a#h/cached.a#c/d）全部弃用——16.1.11.302
 * 上 4 个点惰性不挂载（installed 1/5）。收口点改为响应模型SplashData 的两个
 * 未混淆 Kotlin getter（smali 实证 network.response.SplashData）：
 * - getMediaList() → 空 ArrayList：v3 strategy c/d 与 cached
 *   CachedSplashScreenService$tryFetchSplashDataConfig$2 三处都遍历它构造
 *   AdSplashData（SplashReportUtils.b(SplashBase)），列表空 = 应用自然走「无广告」分支
 * - getMedia() → null：单媒体 fallback，Kotlin 可空字段
 * 类名/方法名/签名均未混淆，跨版本稳定性远高于原散点。
 */
object TamerSplashHooks : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val MODEL = "com.hihonor.appmarket.network.response.SplashData"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_SPLASH)) {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_SPLASH, "TamerSplashHooks")
            return
        }
        val pending = listOf(
            MountEngine.SimpleTask(
                id = "market-tamer:splash.mediaList", className = MODEL,
                methodName = "getMediaList", paramCount = 0, returnType = "java.util.ArrayList",
                description = "开屏素材列表→空（数据源收口）",
            ),
            MountEngine.SimpleTask(
                id = "market-tamer:splash.media", className = MODEL,
                methodName = "getMedia", paramCount = 0,
                returnType = "com.hihonor.appmarket.network.response.SplashBase",
                description = "开屏单媒体 fallback→null",
            ),
        )
        val session = MountEngine.open(
            "TamerSplashHooks", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pending, module, param.defaultClassLoader) { t ->
            session.mount(t, module, param.defaultClassLoader) {
                session.judge(module, t, "market-tamer", "开屏数据源已掐")
                when (t.id) {
                    "market-tamer:splash.mediaList" -> ArrayList<Any?>()
                    else -> null
                }
            }
        }
    }
}
