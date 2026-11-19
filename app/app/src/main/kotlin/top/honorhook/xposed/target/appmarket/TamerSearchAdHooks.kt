package top.honorhook.xposed.target.appmarket

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import top.honorhook.xposed.target.appmarket.TamerUtil.callMethod

/**
 * 搜索/联搜页广告展位/「装机必备」清洗（tamer 1.4.11 SearchAdBlocker 移植，2026-11-19）。
 * 数据装载入口（16.1.11.302 实测名，adapt-report.md §10）args[0] List 原地剔除
 * FocusBoothAppListInfo（展位）与 title 含「装机必备」的项——改数据源而非改 UI。
 * AssSearchResultAdapter E1/O0（唯一调用方 SearchResultLogicHandler）；
 * CommAssAdapter 4 个 List 入口全挂（多页共用，类型清洗对无展位页面无副作用）。
 */
object TamerSearchAdHooks : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"
    private const val BOOTH_BEAN = "$PKG.card.bean.FocusBoothAppListInfo"
    private const val SEARCH_ADAPTER = "$PKG.search.adapter.AssSearchResultAdapter"
    private const val COMM_ASS_ADAPTER = "$PKG.module.main.adapter.CommAssAdapter"

    private fun task(id: String, cls: String, method: String, argc: Int, desc: String) =
        MountEngine.SimpleTask(
            id = id, className = cls, methodName = method, paramCount = argc, returnType = "void",
            description = desc,
        )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val boothOn = RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_SEARCH_AD_BOOTH)
        val mustOn = RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_SEARCH_MUST)
        if (!boothOn && !mustOn) {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_SEARCH_AD_BOOTH, "TamerSearchAdHooks")
            return
        }
        val pending = listOf(
            task("market-tamer:srad.as.E1", SEARCH_ADAPTER, "E1", 2, "搜索结果装载数据清洗（E1）"),
            task("market-tamer:srad.as.O0", SEARCH_ADAPTER, "O0", 2, "搜索结果装载数据清洗（O0）"),
            task("market-tamer:srad.comm.I0", COMM_ASS_ADAPTER, "I0", 1, "联搜装载数据清洗（I0）"),
            task("market-tamer:srad.comm.N0", COMM_ASS_ADAPTER, "N0", 1, "联搜装载数据清洗（N0）"),
            task("market-tamer:srad.comm.O0", COMM_ASS_ADAPTER, "O0", 2, "联搜装载数据清洗（O0）"),
            task("market-tamer:srad.comm.n1", COMM_ASS_ADAPTER, "n1", 3, "联搜装载数据清洗（n1）"),
        )
        val session = MountEngine.open(
            "TamerSearchAdHooks", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pending, module, param.defaultClassLoader) { t ->
            session.mount(t, module, param.defaultClassLoader) { chain ->
                val list = chain.args.getOrNull(0) as? MutableList<Any?>
                if (list == null) {
                    chain.proceed()
                } else {
                    runCatching {
                        val it = list.iterator()
                        var removed = 0
                        while (it.hasNext()) {
                            val item = it.next() ?: continue
                            val drop = (boothOn && TamerUtil.className(item) == BOOTH_BEAN) ||
                                (mustOn && titleOf(item)?.contains("装机必备") == true)
                            if (drop) {
                                it.remove()
                                removed++
                            }
                        }
                        if (removed > 0) session.judge(module, t, "market-tamer", "搜索广告已清 $removed 项")
                    }.getOrElse {
                        module.log(android.util.Log.WARN, MountEngine.TAG, "market-tamer 搜索清洗异常（已吞）: ${it.message}")
                    }
                    chain.proceed()
                }
            }
        }
    }

    /** tamer 取 title：getTitleName()/getDynamicTitle() 非空 String。 */
    private fun titleOf(item: Any): String? =
        callMethod(item, "getTitleName") as? String ?: callMethod(item, "getDynamicTitle") as? String
}
