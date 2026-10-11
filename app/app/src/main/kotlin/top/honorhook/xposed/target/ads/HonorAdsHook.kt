package top.honorhook.xposed.target.ads

import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 荣耀系统广告 SDK（com.hihonor.adsdk）通杀净化。
 *
 * 实证（browser 20.x 基线 jadx 反编译）：所有广告形态（信息流/横幅/开屏/插屏/激励）最终都落在
 * `com.hihonor.adsdk.base.widget.base.BaseAdView`（extends RelativeLayout）。
 * 广告内容唯一注入点是其私有方法 `notifyAllChildView()`（`addView(viewHnadsb, ...)`），
 * 挂载点为公开的 `onAttachedToWindow()`。
 *
 * 收口策略（渲染层短路，不抛异常；api 102 Chain：intercept 不调 proceed() = 原方法不执行）：
 *  - `notifyAllChildView()` 不执行：广告内容注入源头短路；`removeAllViews()` 兜底
 *    hook 生效前已注入的内容，覆盖同步与异步（bindAd 后再 attach）两种加载时序。
 *  - `onAttachedToWindow()` 不执行 + `removeAllViews()` + `visibility = GONE`：折叠占位，
 *    广告槽不显示、不闪。
 * 保留原 BaseAdView 实例（app 后续仍可能调 setDislikeItemClickListener 等），仅清内容。
 *
 * 类名/方法名均 @Keep 未混淆 → 直连反射即可命中（无 DexKit 锚点需求）。
 * 无 SDK 的包（如云音乐走 com.hihonor.hms.ads）解析失败 → NOT_RESOLVED，随类加载重试后放弃，不影响注入。
 *
 * 版本漂移风险：SDK 包名/类名稳定，但内部方法签名随 OTA 可能变；以真机 `installed N/M` 与失败清单为准。
 */
class HonorAdsHook(override val packageName: String) : TargetHook {

    private val engine = MountEngine("HonorAdsHook", packageName)

    /** 所有广告形态共用的真实广告 View 控件（广告内容经此渲染）。 */
    private val CLS_BASE_AD_VIEW = "com.hihonor.adsdk.base.widget.base.BaseAdView"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_ADS_BLOCK)) {
            logDisabled(module, FeatureCatalog.KEY_ADS_BLOCK, "HonorAdsHook")
            return
        }
        engine.begin(param.applicationInfo?.sourceDir)

        // 挂载表 2 项；以真机 installed N/M 的 M 为准。
        val pending = listOf(
            MountTask(
                className = CLS_BASE_AD_VIEW,
                methodName = "notifyAllChildView",
                paramCount = 0,
                returnType = "void",
                id = "ads:BaseAdView.notifyAllChildView",
                description = "广告内容注入收口：原逻辑跑完即 removeAllViews，信息流/横幅/开屏/插屏/激励广告内容清空（覆盖同步+异步加载）",
            ),
            MountTask(
                className = CLS_BASE_AD_VIEW,
                methodName = "onAttachedToWindow",
                paramCount = 0,
                returnType = "void",
                id = "ads:BaseAdView.onAttachedToWindow",
                description = "广告 View 挂载即清空内容+GONE，折叠占位（兜底，防异步加载在 attach 后才注入内容）",
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
        engine.mount(task, module, loader) { chain ->
            // api 102 Chain：不调 proceed() = 原方法不执行（广告注入/原 attach 逻辑整体跳过）；
            // removeAllViews 只兜底 hook 生效前已注入的内容；不抛异常。
            (chain.thisObject as? ViewGroup)?.let { view ->
                view.removeAllViews()
                if (task.id == "ads:BaseAdView.onAttachedToWindow") {
                    view.visibility = View.GONE
                }
            }
            engine.judge(module, task, JUDGE_PREFIX)
            null
        }

    /** 判据日志前缀（便于按前缀过滤真机日志）。 */
    private companion object {
        const val JUDGE_PREFIX = "ads-hook"
    }
}
