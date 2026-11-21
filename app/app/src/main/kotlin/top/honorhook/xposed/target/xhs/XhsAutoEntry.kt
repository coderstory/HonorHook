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
import top.honorhook.AutoActions
import top.honorhook.xposed.core.MountEngine

/**
 * 批量采集入口（2026-11-21 用户拍板「加主页悬浮按钮」）：作者主页注入悬浮按钮，
 * 点击直接启停**本进程内**的 [XhsBatchDriver]，并广播状态给模块侧无障碍服务去滚动页面。
 *
 * 主页判据（2026-11-21 真机实证，无混淆类名依赖——跨 OTA 稳）：decorView 内存在
 * contentDescription 以「笔记,」开头的可点卡片（用户主页瀑布流卡片），详情页/其它页没有。
 * 不 hook 任何宿主类 ⇒ 挂载层零漂移风险，只消费 ActivityLifecycleCallbacks（同
 * XhsBatchSave.ensureActivityTracker 模式，install 期轮询拿 Application）。
 *
 * 按钮与驱动同进程（都在宿主里），所以文案直接读 [XhsBatchDriver.isRunning]，不再需要
 * 跨进程状态广播（旧方案那套 STATE 中转是为了让模块进程的引擎改宿主按钮，现架构反过来了）。
 * 广播只剩「让服务滚动/停滚」和「进度上报给模块 UI」两个方向，常量见 [AutoActions]。
 */
object XhsAutoEntry {

    private const val JUDGE_PREFIX = "XhsHook·Auto"
    private const val CARD_DESC_PREFIX = "笔记,"

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
                if (!tracked.compareAndSet(false, true)) return
                // 驱动状态变化 → 按钮文案（驱动在自己的线程里改状态，回主线程刷 View）
                XhsBatchDriver.stateListener = { main.post { applyLabel() } }
                runCatching { registerStopReceiver(app, module, main) }
                    .onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 停止接收器注册失败: ${it.message}") }
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

    /** 模块 UI 页的「停止」按钮走广播到宿主（按钮与驱动同进程，UI 跨不过来）。 */
    private fun registerStopReceiver(app: android.app.Application, module: XposedModule, main: android.os.Handler) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                if (i.action != AutoActions.STOP) return
                XhsBatchDriver.stop(module, app)
                main.post { applyLabel() }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            app, receiver,
            android.content.IntentFilter(AutoActions.STOP),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** 主页 ⇒ 确保按钮在；非主页 ⇒ 摘除。任何异常吞掉，绝不炸宿主。 */
    private fun syncButton(activity: Activity, module: XposedModule) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (!isProfilePage(decor)) {
            detach()
            return
        }
        if (attachedButton != null && attachedDecor === decor) {
            applyLabel()
            return
        }
        detach()
        val ctx = activity
        val dp = ctx.resources.displayMetrics.density
        val btn = TextView(ctx).apply {
            text = if (XhsBatchDriver.isRunning()) "取消" else "采集"
            textSize = 13f
            setTextColor(Color.WHITE)
            gravity = android.view.Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (XhsBatchDriver.isRunning()) 0xCC555555.toInt() else 0xCCFF2E4D.toInt())
            }
            val size = (52 * dp).toInt()
            layoutParams = FrameLayout.LayoutParams(size, size).apply {
                gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
                rightMargin = (16 * dp).toInt()
            }
            setOnClickListener { toggle(ctx, module) }
        }
        decor.addView(btn)
        attachedButton = btn
        attachedDecor = decor
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 主页悬浮按钮已注入")
    }

    /** 按钮文案/配色跟随运行态：运行中「取消」（灰底），待机「采集」（红底）。 */
    private fun applyLabel() {
        val btn = attachedButton ?: return
        val running = XhsBatchDriver.isRunning()
        runCatching {
            btn.text = if (running) "取消" else "采集"
            (btn.background as? GradientDrawable)?.setColor(if (running) 0xCC555555.toInt() else 0xCCFF2E4D.toInt())
        }
    }

    private fun detach() {
        runCatching { attachedDecor?.let { d -> attachedButton?.let { d.removeView(it) } } }
        attachedButton = null
        attachedDecor = null
    }

    private fun toggle(ctx: android.content.Context, module: XposedModule) {
        if (XhsBatchDriver.isRunning()) {
            XhsBatchDriver.stop(module, ctx)
            return
        }
        // 无障碍服务没开 = 没人滚动页面 = 一条笔记都登记不到，明确告诉用户去哪开
        if (!accessibilityEnabled(ctx)) {
            runCatching {
                android.widget.Toast.makeText(
                    ctx,
                    "请先在 HonorHook → 工具箱 → 批量采集 开启无障碍服务",
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            }
            return
        }
        XhsBatchDriver.start(module, ctx)
    }

    /** 读 secure 表判断本模块无障碍服务是否在启用列表（普通应用可读该表，无需权限）。 */
    private fun accessibilityEnabled(ctx: android.content.Context): Boolean = runCatching {
        val v = android.provider.Settings.Secure.getString(
            ctx.contentResolver, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: return@runCatching false
        v.split(':').any { it.startsWith("${AutoActions.MODULE_PACKAGE}/") }
    }.getOrDefault(false)

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
