package top.honorhook.xposed.target

import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import top.honorhook.xposed.data.RemoteConfig

/**
 * 系统设置（14.0.0.210）：
 * S1 应用详情页「停用」按钮恒可点；
 * S2 首页账户名（HONOR ID）后的小红点隐藏。
 */
object SettingsHook : TargetHook {

    override val packageName = "com.android.settings"

    /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("SettingsHook", packageName)

    /** 类名可读；方法名 Q3 为 R8 单字母混淆（OTA 漂移风险点，DexKit 锚点兜底）。 */
    private const val CLS_APP_DETAILS = "com.android.settings.applications.InstalledAppDetails"

    /**
     * S2 首页 HWID tile 控制器（HwidEnabler，dashboard/u.java）。类名 u 混淆但方法体日志串
     * 「data.badge is false」全库唯一（强内容锚点）；消费点 u.g(d)：d.badge 真 → 给名字 TextView
     * 挂 ic_hwid_badge 复合红点（u.java:128-131）。g 前置把 badge 置 false，宿主走 else 分支
     * setCompoundDrawables(0,0,0,0) 自清红点——只动数据不动视图，title/summary/icon 不受影响。
     */
    private const val CLS_HWID_ENABLER = "com.android.settings.dashboard.u"

    private const val ID_DISABLE_BTN = "settings:AppDetails.enableDisableButton"
    private const val ID_ACCOUNT_DOT = "settings:hwidTile.badge"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName))

        // 挂载表（**以真机 `installed N/M` 的 M 为准**；每个 feature 独立门控）
        val pending = mutableListOf<MountTask>()
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SETTINGS_DISABLE_BTN)) {
            pending += MountTask(
                className = CLS_APP_DETAILS,
                methodName = "Q3",
                paramCount = 0,
                returnType = "void",
                id = ID_DISABLE_BTN,
                description = "S1 应用详情页「停用」按钮恒可点（Q3 决策汇聚点 after 强制启用+挂监听）",
                // 锚点：Q3 方法体内独有的输入法包名字符串（强内容锚点）
                dex = DexHint(strings = listOf("com.baidu.input_hihonor", "com.sohu.inputmethod.sogou.honor")),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_SETTINGS_DISABLE_BTN, "SettingsHook")
        }
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SETTINGS_HIDE_ACCOUNT_DOT)) {
            pending += MountTask(
                className = CLS_HWID_ENABLER,
                methodName = "g",
                paramCount = 1,
                returnType = "void",
                id = ID_ACCOUNT_DOT,
                description = "S2 首页账户名后小红点隐藏（HWID tile badge 置 false，宿主自清 ic_hwid_badge 复合图标）",
                // 锚点：g 方法体内独有的 HwidEnabler 日志串（强内容锚点；badge/title 两串同现只在 g）
                dex = DexHint(strings = listOf("data.badge is false", "data.title is")),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_SETTINGS_HIDE_ACCOUNT_DOT, "SettingsHook")
        }
        if (pending.isEmpty()) return
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /** 停用按钮的视图 id（布局层级 header_view → control_buttons_panel → left_button；运行时 getIdentifier 取，抗资源漂移）。 */
    private const val ID_BUTTON_PANEL = "control_buttons_panel"
    private const val ID_BUTTON_DISABLE = "left_button"

    private class MountTask(
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        override val id: String,
        override val description: String,
        override val dex: DexHint? = null,
    ) : MountEngine.Task

    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        when (task.id) {
            ID_ACCOUNT_DOT -> mountHideAccountDot(task, module, loader)
            else -> mountEnableDisable(task, module, loader)
        }

    private fun mountEnableDisable(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // after 语义：原 Q3 跑完（全部业务门控保留副作用）后，强制启用停用按钮并补挂点击监听
            //（原逻辑仅在最终 enabled 时挂监听）。按钮视图按 id 名运行时查找。
            runCatching {
                        val self = chain.thisObject
                        val root = self.javaClass.getMethod("getView").invoke(self) as? android.view.View
                            ?: return@runCatching
                        val res = self.javaClass.getMethod("getResources").invoke(self) as? android.content.res.Resources
                            ?: return@runCatching
                        val panel = root.findViewById<View>(res.getIdentifier(ID_BUTTON_PANEL, "id", packageName))
                            as? android.view.ViewGroup ?: return@runCatching
                        val btn: android.view.View =
                            panel.findViewById<View>(res.getIdentifier(ID_BUTTON_DISABLE, "id", packageName))
                                ?: return@runCatching
                        btn.isEnabled = true
                        val listener = self as? android.view.View.OnClickListener
                        if (listener != null) btn.setOnClickListener(listener)
                engine.judge(module, task, JUDGE_PREFIX)
            }.onFailure { t ->
                module.log(Log.WARN, MountEngine.TAG, "停用按钮启用失败（放行原状态）: ${t.message}")
            }
            null
        }

    /**
     * S2：u.g(tileData) 前置改数据。tileData 是 u 的嵌套 parcelable u.d，四字段
     * Drawable/String/String/boolean（u.java:423-434），**唯一 boolean 即 badge**——按字段类型定位，
     * 不依赖混淆名 f22223d（版本漂移安全）。置 false 后 proceed，宿主 else 分支自清红点。
     */
    private fun mountHideAccountDot(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // void 方法：回调必须显式返回 null
            val data = runCatching { chain.getArg(0) }.getOrNull()
            if (data != null) {
                val badgeField = data.javaClass.declaredFields.firstOrNull { it.type == java.lang.Boolean.TYPE }
                if (badgeField != null) {
                    runCatching {
                        badgeField.isAccessible = true
                        badgeField.setBoolean(data, false)
                    }.onSuccess {
                        engine.judge(module, task, JUDGE_PREFIX, "tile badge 置 false")
                    }
                } else {
                    // 字段结构漂移：留痕不猜（纪律：无锚点诚实跳过）
                    module.log(Log.WARN, MountEngine.TAG, "hwid tile 数据类无 boolean 字段（结构漂移），红点未隐藏")
                }
            }
            chain.proceed()
            null
        }

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "settings-hook"
}
