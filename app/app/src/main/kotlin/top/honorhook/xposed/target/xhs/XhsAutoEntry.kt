package top.honorhook.xposed.target.xhs

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.xposed.core.MountEngine

/**
 * 批量采集入口（2026-11-21 用户拍板「加主页悬浮按钮」）：作者主页注入「采集」悬浮按钮，
 * 点击广播启停模块侧无障碍编排服务（XhsAutoService，进程 io.github.coderstory.honorhook）。
 *
 * 主页判据（2026-11-21 真机实证，无混淆类名依赖——跨 OTA 稳）：decorView 内存在
 * contentDescription 以「笔记,」开头的可点卡片（用户主页瀑布流卡片），详情页/其它页没有。
 * 不 hook 任何宿主类 ⇒ 挂载层零漂移风险，只消费 ActivityLifecycleCallbacks（同
 * XhsBatchSave.ensureActivityTracker 模式，install 期轮询拿 Application）。
 *
 * 按钮点击只发广播（ACTION_XHS_AUTO_TOGGLE，setPackage 到模块包名）；无障碍服务未开启时
 * 广播无人接收 = 点了没反应（模块 UI「批量采集」页有开启引导）。启停反馈由服务侧 Toast。
 */
object XhsAutoEntry {

    private const val JUDGE_PREFIX = "XhsHook·Auto"
    private const val CARD_DESC_PREFIX = "笔记,"

    /** 广播 action：模块 UI 侧 XhsAutoService 注册接收（包名 = applicationId，跨进程唯一契约） */
    const val ACTION_XHS_AUTO_TOGGLE = "top.honorhook.action.XHS_AUTO_TOGGLE"
    const val MODULE_PACKAGE = "io.github.coderstory.honorhook"

    /** 当前按钮宿主（activity 的 decorView）；离开主页/页销毁即摘除。 */
    @Volatile private var attachedButton: TextView? = null
    @Volatile private var attachedDecor: ViewGroup? = null

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val tracked = java.util.concurrent.atomic.AtomicBoolean(false)
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        main.post(object : Runnable {
            private var attempt = 0
            override fun run() {
                val app = runCatching {
                    Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null) as? android.app.Application
                }.getOrNull() ?: run {
                    if (attempt++ < 60) main.postDelayed(this, 500)
                    return
                }
                runCatching {
                    app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                        override fun onActivityResumed(a: Activity) {
                            // 延迟一拍：主页瀑布流首帧还没渲染完（800ms 实测量级，误判晚一拍无碍）
                            main.postDelayed({ runCatching { syncButton(a, module) } }, 800)
                        }
                        override fun onActivityPaused(a: Activity) {}
                        override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                        override fun onActivityStarted(a: Activity) {}
                        override fun onActivityStopped(a: Activity) {}
                        override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                        override fun onActivityDestroyed(a: Activity) {
                            // 宿主 Activity 销毁必须摘按钮并取消未跑的延迟挂载，否则静态引用
                            // 拖住整棵 decor 视图树（2026-11-21 审计 #5）
                            if (attachedDecor === a.window?.decorView) {
                                main.removeCallbacksAndMessages(null)
                                detach()
                            }
                        }
                    })
                    module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX installed 1/1")
                }.onFailure {
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 生命周期注册失败: ${it.message}")
                }
            }
        })
    }

    /** 主页 ⇒ 确保按钮在；非主页 ⇒ 摘除。任何异常吞掉，绝不炸宿主。 */
    private fun syncButton(activity: Activity, module: XposedModule) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (!isProfilePage(decor)) {
            detach()
            return
        }
        if (attachedButton != null && attachedDecor === decor) return
        detach()
        val ctx = activity
        val dp = ctx.resources.displayMetrics.density
        val btn = TextView(ctx).apply {
            text = "采集"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCCFF2E4D.toInt())
            }
            val size = (52 * dp).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                rightMargin = (16 * dp).toInt()
            }
            // 按钮比内容树高一层已天然在顶；点击只发广播，无状态回读（启停反馈在服务侧 Toast）
            setOnClickListener { toggle(ctx) }
        }
        decor.addView(btn)
        attachedButton = btn
        attachedDecor = decor
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 主页悬浮按钮已注入")
    }

    private fun detach() {
        runCatching { attachedDecor?.let { d -> attachedButton?.let { d.removeView(it) } } }
        attachedButton = null
        attachedDecor = null
    }

    private fun toggle(ctx: android.content.Context) {
        runCatching {
            ctx.sendBroadcast(android.content.Intent(ACTION_XHS_AUTO_TOGGLE).setPackage(MODULE_PACKAGE))
        }
    }

    /** 主页判据：decorView 内存在 desc 以「笔记,」开头的视图（瀑布流卡片）。深度优先，找到即返回。 */
    private fun isProfilePage(root: View): Boolean {
        if (root.contentDescription?.startsWith(CARD_DESC_PREFIX) == true) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                if (isProfilePage(root.getChildAt(i))) return true
            }
        }
        return false
    }
}
