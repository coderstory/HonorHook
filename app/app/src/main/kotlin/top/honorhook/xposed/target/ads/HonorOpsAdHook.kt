package top.honorhook.xposed.target.ads

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 日历专属运营推荐位净化（com.hihonor.osoperationsdk，仅日历内置 228 类，第二广告通路）。
 *
 * 实证（calendar jadx 反编译）：设置里的「个性化广告」开关（prefs key `key_personal_info`，
 * PersonalServiceActivity → PrivacyUtil.a0.d/n）gate 的是 HosexHelper（com.hihonor.calendar.hosex.c）
 * 里 `RecommendCardClient.getInstance().init(...)` + `getRecommendView(context, 1, callback)` ——
 * 服务卡/资讯推荐位，不走 adsdk 的 BaseAdView 渲染层（HonorAdsHook 管不到），故独立收口。
 *
 * 收口点：`RecommendCardClient.getRecommendView(Context, int, RecommendCallback)`（public 未混淆）。
 * intercept 不调 proceed() = 原方法不执行 → 内部 ConfigDownloadManager 拉配置、AdManager 拉广告、
 * AdViewFactory 出视图、TrackerManager 埋点整链全部跳过。
 * 必须主动回调 `loadFail(1000)`：原方法体内的超时兜底也被跳过，不回调宿主 UI 会挂等待态；
 * 1000 = 「sdk not initialized」，宿主照常走失败分支隐藏推荐位。
 * 仅挂 3 参 getRecommendView：日历唯一调用点是 HosexHelper.h()（getRecommendViewOs 全仓无调用方，不可达）。
 *
 * 日志判据前缀 `ops-hook`；真机判据 `installed 1/1`。
 */
class HonorOpsAdHook(override val packageName: String) : TargetHook {


    private val CLS_CLIENT = "com.hihonor.osoperationsdk.manager.RecommendCardClient"
    private val CLS_CALLBACK = "com.hihonor.osoperationsdk.listener.RecommendCallback"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_ADS_BLOCK)) {
            logDisabled(module, FeatureCatalog.KEY_ADS_BLOCK, "HonorOpsAdHook")
            return
        }
        val session = MountEngine.open("HonorOpsAdHook", packageName, param.applicationInfo?.sourceDir)

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_CLIENT,
                methodName = "getRecommendView",
                paramCount = 3,
                returnType = "void",
                id = "ops:RecommendCardClient.getRecommendView",
                description = "运营推荐位请求收口：不执行原加载链，直接回调 loadFail(1000) 隐藏推荐位",
            ),
        )
        session.attemptMount(pending, module, loader) { task -> mountOne(session, task, module, loader) }
    }

    private fun mountOne(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            // api 102 Chain：不调 proceed() = 原方法不执行（配置拉取/广告请求/埋点整链跳过）。
            // args[2] = RecommendCallback（宿主匿名实现），必须回调 loadFail 让宿主立即走失败分支。
            runCatching {
                val callback = chain.args[2]
                val iface = loader.loadClass(CLS_CALLBACK)
                iface.getMethod("loadFail", Int::class.javaPrimitiveType).invoke(callback, 1000)
            }
            session.judge(module, task, JUDGE_PREFIX)
            null
        }

    /** 判据日志前缀（便于按前缀过滤真机日志）。 */
    private companion object {
        const val JUDGE_PREFIX = "ops-hook"
    }
}
