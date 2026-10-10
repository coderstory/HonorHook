package top.honorhook.xposed.target

import android.util.Log
import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import top.honorhook.xposed.data.RemoteConfig

/** 系统设置（14.0.0.210）：应用详情页「停用」按钮恒可点。 */
object SettingsHook : TargetHook {

    override val packageName = "com.android.settings"

    /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("SettingsHook", packageName)

    /** 类名可读；方法名 Q3 为 R8 单字母混淆（OTA 漂移风险点，DexKit 锚点兜底）。 */
    private const val CLS_APP_DETAILS = "com.android.settings.applications.InstalledAppDetails"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SETTINGS_DISABLE_BTN)) {
            logDisabled(module, FeatureCatalog.KEY_SETTINGS_DISABLE_BTN, "SettingsHook")
            return
        }
        engine.begin(param.applicationInfo?.sourceDir, HOST_VERSION_BASELINE)

        // 挂载表 1 项；**以真机 `installed N/M` 的 M 为准。**
        val pending = mutableListOf<MountTask>()
        pending += MountTask(
            kind = MountKind.ENABLE_DISABLE_BUTTON,
            className = CLS_APP_DETAILS,
            methodName = "Q3",
            paramCount = 0,
            returnType = "void",
            id = "settings:AppDetails.enableDisableButton",
            description = "S1 应用详情页「停用」按钮恒可点（Q3 决策汇聚点 after 强制启用+挂监听）",
            // 锚点：Q3 方法体内独有的输入法包名字符串（强内容锚点）
            dex = DexHint(strings = listOf("com.baidu.input_hihonor", "com.sohu.inputmethod.sogou.honor")),
        )
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /** hook 编写时的宿主版本基线（pm 实证）；OTA 超出此版本时 MountEngine 首轮挂载打 WARN。 */
    private const val HOST_VERSION_BASELINE = "14.0.0.210"

    /** 停用按钮的视图 id（布局层级 header_view → control_buttons_panel → left_button；运行时 getIdentifier 取，抗资源漂移）。 */
    private const val ID_BUTTON_PANEL = "control_buttons_panel"
    private const val ID_BUTTON_DISABLE = "left_button"

    /** 挂载任务类型：[ENABLE_DISABLE_BUTTON] after 语义——原决策跑完后强制启用停用按钮。 */
    private enum class MountKind { ENABLE_DISABLE_BUTTON }

    private class MountTask(
        val kind: MountKind,
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        override val id: String,
        override val description: String,
        override val dex: DexHint? = null,
    ) : MountEngine.Task

    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            when (task.kind) {
                // after 语义：原 Q3 跑完（全部业务门控保留副作用）后，强制启用停用按钮并补挂点击监听
                //（原逻辑仅在最终 enabled 时挂监听）。按钮视图按 id 名运行时查找。
                MountKind.ENABLE_DISABLE_BUTTON -> {
                    chain.proceed()
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
            }
        }

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "settings-hook"
}
