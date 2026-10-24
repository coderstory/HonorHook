package top.honorhook.xposed.target.ads

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 荣耀亲选电商模块（cn.honor.qinxuan）通杀净化。
 *
 * 实证（health 17.24.0.302 MT MCP 静态分析，docs/reverse/health.md）：运动健康内嵌「荣耀亲选」
 * 电商模块 496 类，首页营销信息（推荐商品流/banner/秒杀/运营位）唯一订阅收口 =
 * `RecommendModel.getHomeModulesData()`（HomeRegion + SecKill + Recommend 三路合流管道的头部，
 * 方法名未混淆直连可挂）。挂它一个点 = 亲选营销区整体空。
 *
 * 与 adsdk 通杀（[HonorAdsHook]）的关系：亲选自建 View（HomeBannerView extends RelativeLayout）
 * **不经过** adsdk BaseAdView，两钩子互补；osoperationsdk（推荐卡）在 health 不存在，无需挂。
 *
 * 拦截语义：返回 `Observable.empty()`（反射构造——模块代码零 rx 类型，同 XhsSavePivot hostShortUrl
 * 教训：宿主 rx 类不可由模块 classloader 解析）。UI 侧空态由宿主自处理（RecyclerView 空模块不渲染）。
 *
 * 类名/方法名未混淆 → 直连反射命中；无 SDK 的包解析失败 → NOT_RESOLVED 随类加载重试后放弃。
 * 真机判据：`HonorQinxuanHook[包名]: installed 1/1`。
 */
class HonorQinxuanHook(override val packageName: String) : TargetHook {

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_ADS_BLOCK)) {
            logDisabled(module, FeatureCatalog.KEY_ADS_BLOCK, "HonorQinxuanHook")
            return
        }
        val session = MountEngine.open("HonorQinxuanHook", packageName, param.applicationInfo?.sourceDir)

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_RECOMMEND_MODEL,
                methodName = "getHomeModulesData",
                paramCount = 0,
                returnType = "io.reactivex.rxjava3.core.Observable",
                id = "qinxuan:RecommendModel.getHomeModulesData",
                description = "亲选营销总入口收口：返回 Observable.empty()，首页推荐流/banner/秒杀/运营位整体空（数据源级，非 UI 裁剪）",
            ),
        )
        session.attemptMount(pending, module, loader) { task -> mountOne(session, task, module, loader) }
    }

    private fun mountOne(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            // 反射构造 rx3 Observable.empty()：宿主 loader 的 rx 类型，模块零引用（见类 KDoc）
            val empty = runCatching {
                val rx = loader.loadClass(RX_OBSERVABLE)
                rx.getMethod("empty").invoke(null)
            }.getOrNull()
            if (empty == null) {
                // 反射失败（rx 不在默认 loader？赌插件/子 loader 不成立时）——放行原逻辑保底，不炸宿主
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX Observable.empty 反射失败，放行原生（不 crash）")
                return@mount chain.proceed()
            }
            session.judge(module, task, JUDGE_PREFIX)
            empty
        }

    /** 判据日志前缀 + 常量。 */
    private companion object {
        const val JUDGE_PREFIX = "qinxuan-hook"
        const val CLS_RECOMMEND_MODEL = "cn.honor.qinxuan.honorchoice.home.recommend.RecommendModel"
        const val RX_OBSERVABLE = "io.reactivex.rxjava3.core.Observable"
    }
}
