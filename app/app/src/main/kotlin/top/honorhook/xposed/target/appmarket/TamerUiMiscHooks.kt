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
import top.honorhook.xposed.target.appmarket.TamerUtil.className

/**
 * 杂项 UI：首页滚动词 + 工具页推广入口（tamer 1.4.11 RollWordBlocker /
 * ToolPageEntryBlocker 移植，2026-11-19）。
 */
object TamerUiMiscHooks : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"
    private const val TITLE_VIEW = "$PKG.widgets.CommonMainTitleView"
    private const val ENTRY_VIEW = "$PKG.operation.toolpage.ToolPageActivityEntryView"

    private fun task(id: String, cls: String, method: String, argc: Int, desc: String) =
        MountEngine.SimpleTask(
            id = id, className = cls, methodName = method, paramCount = argc, returnType = "void",
            description = desc,
        )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val pending = mutableListOf<MountEngine.SimpleTask>()

        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_HOME_ROLLWORD)) {
            pending += listOf(
                // 16.1.11.302：D(5)/A(5)/I(0) 形状已变（adapt-report.md §9）——数据装载收口为
                // y(self,List,3串)（类内唯一 List 参数方法，static）；F(0) 仍活。
                task("market-tamer:roll.y", TITLE_VIEW, "y", 5, "滚动词数据装载 args[1] 清空"),
                task("market-tamer:roll.F", TITLE_VIEW, "F", 0, "滚动词入口→null"),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_HOME_ROLLWORD, "TamerUiMiscHooks")
        }
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_TOOLPAGE_ENTRY)) {
            pending += task("market-tamer:toolentry.b", ENTRY_VIEW, "b", 1, "工具页推广入口→null")
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_TOOLPAGE_ENTRY, "TamerUiMiscHooks")
        }
        if (pending.isEmpty()) return

        val session = MountEngine.open(
            "TamerUiMiscHooks", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pending, module, param.defaultClassLoader) { t ->
            session.mount(t, module, param.defaultClassLoader) { chain ->
                runCatching {
                    if (t.id == "market-tamer:roll.y") {
                        // 仅当 args[1] 是 List 时清空（y 是 static：thisObject 为 null；
                        // 2026-11-19 实测 proceedWith(类对象, args) 被框架拒（"must be an instance of
                        // CommonMainTitleView"）—— static 换参走 chain.proceed(args)，不带 receiver）
                        val args = chain.args?.toTypedArray()
                        if (args?.getOrNull(1) is List<*>) {
                            args[1] = java.util.ArrayList<Any>()
                            session.judge(module, t, "market-tamer", "滚动词已清空")
                            return@mount chain.proceed(args)
                        }
                        chain.proceed()
                    } else {
                        session.judge(module, t, "market-tamer", "入口已拦")
                        null
                    }
                }.getOrElse {
                    module.log(android.util.Log.WARN, MountEngine.TAG, "market-tamer misc 拦截异常（已吞）: ${it.message}")
                    chain.proceed()
                }
            }
        }
    }
}
