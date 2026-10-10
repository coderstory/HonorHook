package top.honorhook.xposed.target

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig

/**
 * 荣耀桌面 `com.hihonor.android.launcher`：隐藏卡片堆栈标题（如「YOYO 建议 · 明日天气预报」）。
 *
 * 单点 = `ThemeUtilImpl#isHiddenTitle()Z` 恒 true。
 *
 * 「那行文本」是卡片堆栈（Card Stack）自己的标题栏 `card_indicator_title`，由插件
 * `YOYOCardStackPlugin.apk` 的 `cardstack.view.HnIndicator2X4StackView#setTitle` 渲染；
 * 其空态开关是插件内 `cardstack.utils.z.d()` —— 它唯一的取值出口为
 * `ILauncherService.getThemeUtil().isHiddenTitle()`，而该方法实现在
 * `com.hihonor.android.plugin.service.theme.ThemeUtilImpl`（**桌面 base 包** classes3.dex，
 * 非插件 ClassLoader）⇒ 直连反射即可命中，无需插件 ClassLoader 捕获/重试基建。
 *
 * 与桌面设置「隐藏应用名称」的区别：官方开关写 SP `launcher_icon_title_hidden`，
 * 由 `HiddenTitleManager#isHiddenTitle()` 读，该读点**同时**被图标名路径
 * （BubbleTextView / Workspace / Folder / AllAppsGridAdapter …）消费 ⇒ 会连带隐藏图标名。
 * 本 hook **不改 SP、不动 HiddenTitleManager**，只把 ITheme 出口置 true ⇒ 仅卡片标题空态，
 * 桌面应用图标名保持显示。
 *
 * 效果 = 走宿主自带空态分支：`setTitle` 中 `z.d()` 为真 → 主/副标题 `setText("")`；
 * 2X2 的 `setTitleVisibility` 直接整行 INVISIBLE。判定是读取式（每次 setTitle / 新实例构造都重读），
 * 拉活桌面进程后即生效。
 */
object LauncherCardTitleHook : TargetHook {

    override val packageName = "com.hihonor.android.launcher"

    /** 共享挂载基建（重试循环/失败分级/汇总日志/双轨解析）。 */
    private val engine = MountEngine("LauncherCardTitleHook", packageName)

    /** 卡片标题隐藏的唯一决策出口（桌面 base 包；类名/方法名均未混淆）。 */
    private const val CLS_THEME_UTIL = "com.hihonor.android.plugin.service.theme.ThemeUtilImpl"

    /** hook 编写时的宿主版本基线（dumpsys package 实证）；OTA 超出时 MountEngine 首轮挂载打 WARN。 */
    private const val HOST_VERSION_BASELINE = "18.0.0.234"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_LAUNCHER_HIDE_TITLE)) {
            logDisabled(module, FeatureCatalog.KEY_LAUNCHER_HIDE_TITLE, "LauncherCardTitleHook")
            return
        }
        engine.begin(param.applicationInfo?.sourceDir, HOST_VERSION_BASELINE)

        // 挂载表 1 项；**以真机 `installed N/M` 的 M 为准。**
        val pending = listOf(
            MountTask(
                className = CLS_THEME_UTIL,
                methodName = "isHiddenTitle",
                paramCount = 0,
                returnType = "boolean",
                id = "launcher:themeUtil.isHiddenTitle",
                description = "L1 卡片标题隐藏（ThemeUtilImpl.isHiddenTitle→true：主/副标题置空 + 2X2 整行 INVISIBLE；桌面图标名不受影响）",
            ),
        )
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    private class MountTask(
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        override val id: String,
        override val description: String,
    ) : MountEngine.Task

    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { _ ->
            engine.judge(module, task, JUDGE_PREFIX)
            // 不 proceed、不改 SP：直接对 ITheme 出口返回 true（决定卡片标题空态）。
            // 图标名走 HiddenTitleManager 另一条读链，不受此处影响。
            true
        }

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "launcher-hook"
}
