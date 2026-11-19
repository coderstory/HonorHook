package top.honorhook.xposed.target.appmarket

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.HostVersion
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 页签/入口级 Activity 整页拦截（tamer 1.4.11 ActivityBlocker 移植，2026-11-19）。
 *
 * 选点：android.app.Activity#onCreate(Bundle)/#onResume()（AOSP 共同祖先，跨版本稳），
 * 回调期按 thisObject 类名命中 BLOCKED_ACTIVITIES 表才 finish()——表全为
 * com.hihonor.appmarket.* 未混淆类名（tamer 同源），宿主 OTA 不漂移。
 * 命中即 finish 吞异常；类名表在 install() 按开关构建，回调期不再查开关。
 */
object TamerActivityBlocker : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"
    private const val ID_ON_CREATE = "market-tamer:activity.onCreate"
    private const val ID_ON_RESUME = "market-tamer:activity.onResume"

    /** tamer BLOCKED_ACTIVITIES 全表（38 项，key→activity 全类名；全未混淆）。 */
    private val BLOCKED_ACTIVITIES: Map<String, List<String>> = mapOf(
        FeatureCatalog.KEY_MARKET_PURIFY_TAB_GAME to listOf(
            // 16.1.11.302：QuickGameDetailActivity 类已删（adapt-report.md §2），只留 2 项
            "$PKG.launcher.entrance.shortcut.LauncherHotGameActivity",
            "$PKG.launcher.entrance.shortcut.LauncherQuickGameActivity",
        ),
        FeatureCatalog.KEY_MARKET_PURIFY_QUICK_GAME to listOf(
            "$PKG.launcher.entrance.quickgame.QuickGameSettingActivity",
        ),
        FeatureCatalog.KEY_MARKET_PURIFY_TAB_CATEGORY to listOf(
            "$PKG.main.classification.ClassificationForOverseasActivity",
            "$PKG.main.classification.ClassificationMoreActivity",
            "$PKG.main.classification.ThirdCategoryDetailActivity",
        ),
        FeatureCatalog.KEY_MARKET_PURIFY_TAB_MINE to listOf(
            "$PKG.module.mine.property.PropertyActivity",
            "$PKG.module.mine.property.MineGiftLIstActivity",
            "$PKG.module.mine.property.MineCouponActivity",
            "$PKG.module.mine.reserve.MyReserveActivity",
            "$PKG.module.mine.wishlist.WishListActivity",
        ),
        FeatureCatalog.KEY_MARKET_PURIFY_RANK_PAGE to listOf(
            "$PKG.module.common.SellingRankActivity",
            "$PKG.popularapps.activity.PopularAppsSettingActivity",
            "$PKG.popularapps.activity.PopularAppsSortRuleActivity",
            "$PKG.module.topapps.TopAppsSettingActivity",
        ),
        FeatureCatalog.KEY_MARKET_PURIFY_CHILDREN to listOf(
            "$PKG.module.main.children.ChildrenAppActivity",
            "$PKG.module.common.ChildrenAssemblyListActivity",
        ),
        FeatureCatalog.KEY_MARKET_PURIFY_CLEAN to listOf(
            "$PKG.biz_clean.clean.activity.CleanAccelerateActivity",
            "$PKG.biz_clean.clean.activity.DeepCleanActivity",
        ),
        FeatureCatalog.KEY_MARKET_PURIFY_SAFETY to listOf(
            "$PKG.biz_safety_check_core.activity.SafetyCheckActivityV2",
            "$PKG.biz_safety_check_core.activity.RiskAppDetailActivityV2",
            "$PKG.biz_safety_check_core.activity.SafetyIgnoreActivity",
            "$PKG.biz_safety_check_core.activity.SafetyReportActivity",
            "$PKG.biz_safety_check_core.activity.SafetySettingActivity",
            "$PKG.biz_safety_check_core.activity.UnUsedAppUninstallActivity",
        ),
        FeatureCatalog.KEY_MARKET_PURIFY_APP_MANAGE to listOf(
            "$PKG.app.manage.download.InstallManagerActivity",
            "$PKG.app.manage.record.InstallRecordActivity",
            "$PKG.app.manage.uninstall.AppUninstallActivity",
            "$PKG.app.manage.uninstall.AppUninstallOverSeaActivity",
            "$PKG.archive.ui.ArchiveAppManageActivity",
            "$PKG.archive.ui.CanArchiveAppActivity",
        ),
        FeatureCatalog.KEY_MARKET_PURIFY_MSG_CENTER to listOf(
            "$PKG.msgcenterview.MsgCenterActivityV2",
            "$PKG.msgcenterview.MsgInteractionActivity",
            "$PKG.msgcenterview.EuMsgListActivity",
        ),
    )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val enabledTable = HashMap<String, String>()
        BLOCKED_ACTIVITIES.forEach { (key, acts) ->
            if (RemoteConfig.isEnabled(key)) acts.forEach { enabledTable[it] = key }
        }
        if (enabledTable.isEmpty()) return // tamer 同语义：空表跳过不挂

        val pending = listOf(
            MountEngine.SimpleTask(
                id = ID_ON_CREATE, className = "android.app.Activity", methodName = "onCreate",
                paramCount = 1, returnType = "void",
                description = "净化页签/入口 Activity onCreate 后 finish（类名表 ${enabledTable.size} 项）",
            ),
            MountEngine.SimpleTask(
                id = ID_ON_RESUME, className = "android.app.Activity", methodName = "onResume",
                paramCount = 0, returnType = "void",
                description = "净化页签/入口 Activity onResume 后 finish（兜底，tamer 同款双点）",
            ),
        )
        val session = MountEngine.open(
            "TamerActivityBlocker", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pending, module, param.defaultClassLoader) { task ->
            session.mount(task, module, param.defaultClassLoader) { chain ->
                chain.proceed()
                // 探针前移：页签条 ItemView 在 fragment 视图创建期就构造，早于 g 回调；
                // 借 Activity#onCreate（最早的稳定回调）把 nav 探针挂上，才能截到装配栈
                runCatching { TamerTabStripper.installNavProbesOnce(module, param.defaultClassLoader) }
                val name = TamerUtil.className(chain.thisObject)
                val key = enabledTable[name]
                if (key != null) {
                    runCatching { TamerUtil.callMethod(chain.thisObject, "finish") }
                    session.judge(module, task, "market-tamer", "已关闭 $name（$key）")
                }
                null
            }
        }
    }
}
