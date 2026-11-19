package top.honorhook.xposed.target.appmarket

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import top.honorhook.xposed.target.appmarket.TamerUtil.className

/**
 * 悬浮窗/小部件/个性化广告弹窗拦截（tamer 1.4.11 DialogBlocker 移植，2026-11-19）。
 *
 * op_dialog 三点（k0/j0/i0）**不在本 hook**：主开关 AppMarketHook 已占 n0（16.1.11 换名）
 * 与 j0，同点复挂会 MERGED_CONFLICT；主开关的渲染层拦截已覆盖。
 * widget_tip：BaseUikitDialogFragment#show(2) thisObject 类名门控 AddWidgetDialogFragment
 * （16.1.11.302 上 c0/e0 形状已变，argc 过滤自然失活，不挂）。
 * pers_ad：PersonalizedAdsDialogFragment 类已删——新链路 DialogPriorityManager#d(DialogType,Function0,Z)
 * （未混淆），args[0] 枚举名 == PERSONALIZED_ADS 才拦（枚举名跨版本稳）。
 */
object TamerDialogHooks : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"
    private const val ADD_WIDGET_DIALOG = "$PKG.base.widget.card.dialog.AddWidgetDialogFragment"

    private fun task(id: String, cls: String, method: String, argc: Int, desc: String) =
        MountEngine.SimpleTask(
            id = id, className = cls, methodName = method, paramCount = argc, returnType = "void",
            description = desc,
        )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val widgetTipOn = RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_WIDGET_TIP)
        val persAdOn = RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_PERS_AD)
        val pending = mutableListOf<MountEngine.SimpleTask>()

        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_OP_FLOAT)) {
            pending += listOf(
                task("market-tamer:opfloat.mgr.b", "$PKG.main.features.operfloating.OperationFloatingWindowManager", "b", 2, "悬浮推广窗管理入口→null"),
                task("market-tamer:opfloat.win.k", "$PKG.operation.widget.floatingwindow.window.a", "k", 6, "悬浮窗构建（混淆 a#k）→null"),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_OP_FLOAT, "TamerDialogHooks")
        }
        if (widgetTipOn) {
            pending += task(
                "market-tamer:dlg.base.show", "$PKG.widgets.dialog.BaseUikitDialogFragment", "show", 2,
                "BaseUikitDialogFragment#show(2) 类名门控（小部件推广）",
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_WIDGET_TIP, "TamerDialogHooks")
        }
        if (persAdOn) {
            pending += task(
                "market-tamer:dlg.prio.d", "$PKG.main.util.DialogPriorityManager", "d", 3,
                "弹窗优先级分发（DialogType==PERSONALIZED_ADS→null）",
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_PERS_AD, "TamerDialogHooks")
        }
        if (pending.isEmpty()) return

        val session = MountEngine.open(
            "TamerDialogHooks", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pending, module, param.defaultClassLoader) { t ->
            session.mount(t, module, param.defaultClassLoader) { chain ->
                if (t.id == "market-tamer:dlg.base.show") {
                    // 回调期开关门控：仅 AddWidget 子类拦，其余放行
                    if (className(chain.thisObject) == ADD_WIDGET_DIALOG) {
                        session.judge(module, t, "market-tamer", "弹窗已拦")
                        null
                    } else {
                        chain.proceed()
                    }
                } else if (t.id == "market-tamer:dlg.prio.d") {
                    // DialogType.PERSONALIZED_ADS 枚举名比较（枚举 toString() = name）
                    if (chain.args.getOrNull(0)?.toString() == "PERSONALIZED_ADS") {
                        session.judge(module, t, "market-tamer", "个性化广告弹窗已拦")
                        null
                    } else {
                        chain.proceed()
                    }
                } else {
                    session.judge(module, t, "market-tamer", "悬浮窗已拦")
                    null
                }
            }
        }
    }
}
