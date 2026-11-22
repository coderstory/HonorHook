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
    /**
     * 按钮/面板所属的 Activity。**不能从 decor.context 反推**：API 31+ 的 decorView.context 是
     * `com.android.internal.policy.DecorContext`（2026-11-22 真机实证），`as? Activity` 恒为 null，
     * 面板就这么静默挂不上。
     */
    @Volatile private var attachedActivity: Activity? = null

    /** 过程消息面板（同一个 View 持续刷新，替代逐条 Toast）；只在采集期间显示。 */
    @Volatile private var attachedPanel: View? = null
    /** 消息容器：每行一个 TextView，按成败上色（用户 2026-11-22：嗅探失败的要用红色系）。 */
    @Volatile private var panelLog: android.widget.LinearLayout? = null
    @Volatile private var panelHeader: TextView? = null
    @Volatile private var panelDecor: ViewGroup? = null
    /** 用时每秒都在变，顶栏靠一个 1 秒心跳刷新（其余数字由驱动事件驱动）。 */
    @Volatile private var ticker: Runnable? = null
    /** 面板最近一次内容：面板晚于消息建出来时用它补渲染（首条消息早于注入很常见）。 */
    @Volatile private var lastLines: List<String> = emptyList()
    /** 用户点过面板上的 ×：本轮不再自动挂回来，下次点「采集」开新一轮才复位。 */
    @Volatile private var panelDismissed = false
    @Volatile private var lastRunning = false

    /** 当前挂着的全局布局观察者（哪棵 decor、哪个 listener、上次检查时间）。 */
    @Volatile private var watchedDecor: ViewGroup? = null
    /** 当前那次注入轮询的 Runnable（销毁时精确取消，不误伤别的定时器）。 */
    @Volatile private var retryRunnable: Runnable? = null
    @Volatile private var watchedListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    @Volatile private var lastCheckAt = 0L

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
                XhsBatchDriver.stateListener = { main.post { applyLabel(); panelSync() } }
                // 过程消息 → 悬浮面板（不再逐条 Toast：用户 2026-11-22「toast 不太行」）
                XhsBatchDriver.uiListener = { lines -> main.post { renderLines(lines) } }
                runCatching { registerStopReceiver(app, module, main) }
                    .onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 停止接收器注册失败: ${it.message}") }
                runCatching {
                    app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                        override fun onActivityResumed(a: Activity) {
                            // 主页瀑布流首帧晚于 onResume（真机实测几百 ms~数秒），且可能
                            // 靠网络分页才出现 —— 单靠轮询要么慢要么超时收手（用户 2026-11-22
                            // 反馈「按钮展示不灵光」）。改成「布局观察者为主 + 轮询兜底」。
                            watchDecor(a, module, main)
                            retrySync(a, module, main, attempts = 30, intervalMs = 500)
                        }

                        override fun onActivityPaused(a: Activity) {
                            if (watchedDecor === a.window?.decorView || (watchedDecor?.context as? Activity) === a) {
                                unwatch()
                            }
                            // 只删本页的轮询，不用 removeCallbacksAndMessages(null) 全清（审计 #18）
                            retryRunnable?.let { main.removeCallbacks(it) }
                        }
                        override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                        override fun onActivityStarted(a: Activity) {}
                        override fun onActivityStopped(a: Activity) {}
                        override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                        override fun onActivityDestroyed(a: Activity) {
                            // 宿主 Activity 销毁必须摘按钮并取消未跑的延迟挂载，否则静态引用
                            // 拖住整棵 decor 视图树（2026-11-21 审计 #5）
                            // decorView 可能已被替换/置空，身份比较之外再按 context 判一次（审计 #17）
                            if (watchedDecor === a.window?.decorView || (watchedDecor?.context as? Activity) === a) {
                                unwatch()
                            }
                            if (attachedDecor === a.window?.decorView || (attachedDecor?.context as? Activity) === a) {
                                retryRunnable?.let { main.removeCallbacks(it) }
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

    /**
     * 盯住当前 Activity 的布局：每次布局完成都试一次注入（250ms 节流）。
     * 这样「瀑布流晚到 / 换 tab 后才有卡片 / 从详情页返回」都能自动补上按钮，
     * 不依赖某个固定时刻的轮询是否还活着。监听器在 pause/destroy 时摘掉。
     */
    private fun watchDecor(activity: Activity, module: XposedModule, main: android.os.Handler) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (watchedDecor === decor && watchedListener != null) return
        unwatch()
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            if (activity.isFinishing) return@OnGlobalLayoutListener
            val now = System.currentTimeMillis()
            if (now - lastCheckAt < 250L) return@OnGlobalLayoutListener
            lastCheckAt = now
            runCatching { syncButton(activity, module) }
        }
        runCatching {
            decor.viewTreeObserver.addOnGlobalLayoutListener(listener)
            watchedDecor = decor
            watchedListener = listener
        }
    }

    private fun unwatch() {
        runCatching {
            val d = watchedDecor
            val l = watchedListener
            if (d != null && l != null && d.viewTreeObserver.isAlive) {
                d.viewTreeObserver.removeOnGlobalLayoutListener(l)
            }
        }
        watchedDecor = null
        watchedListener = null
    }

    /** 每 400ms 试一次注入，最多 [attempts] 次（≈6s）：主页渲染慢也能等到，非主页则安静放弃。 */
    private fun retrySync(activity: Activity, module: XposedModule, main: android.os.Handler, attempts: Int, intervalMs: Long) {
        val r = object : Runnable {
            private var left = attempts
            override fun run() {
                if (left-- <= 0) return
                // 页已退出/被替换就不必再试
                if (activity.isFinishing || attachedDecor === activity.window?.decorView && attachedButton != null) return
                val hit = runCatching {
                    syncButton(activity, module)
                    attachedButton != null
                }.getOrDefault(false)
                if (!hit) main.postDelayed(this, intervalMs)
            }
        }
        retryRunnable = r
        main.postDelayed(r, intervalMs)
    }

    /**
     * 模块 UI 页的控制广播（按钮与驱动同进程，UI 跨不过来）：
     * STOP = 停止；CONFIG = 下发每篇之间的随机节奏范围（分秒）；QUERY = 面板问一次运行态。
     *
     * 暴露面说明（2026-11-22 审计 #19，暂按现有设计接受）：RECEIVER_EXPORTED 意味着设备上任一
     * 应用都能发 STOP/CONFIG（打断采集、改慢节奏）。跨应用没有可靠的白名单手段（伪造 sender 包名
     * 挡不住），危害仅限「启停与节奏」，不含数据外泄——真机环境单一，先接受并在此登记。
     */
    private fun registerStopReceiver(app: android.app.Application, module: XposedModule, main: android.os.Handler) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context, i: android.content.Intent) {
                when (i.action) {
                    AutoActions.STOP -> {
                        XhsBatchDriver.stop(module, app)
                        main.post { applyLabel() }
                    }
                    AutoActions.QUERY -> {
                        // 模块侧进程与驱动不同进程：面板每次进页都问一次真实状态（审计 #13）
                        XhsBatchDriver.publishState()
                    }
                    AutoActions.CONFIG -> {
                        XhsBatchDriver.setPace(
                            i.getIntExtra(AutoActions.EXTRA_PACE_MIN_DS, 20),
                            i.getIntExtra(AutoActions.EXTRA_PACE_MAX_DS, 40),
                        )
                    }
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            app, receiver,
            android.content.IntentFilter().apply {
                addAction(AutoActions.STOP)
                addAction(AutoActions.QUERY)
                addAction(AutoActions.CONFIG)
            },
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** 主页判据：decorView 内存在 desc 以「笔记,」开头的视图（瀑布流卡片）。深度优先，找到即返回。 */
    private fun isProfilePage(root: View, depth: Int = 0): Boolean {
        // 挂在 OnGlobalLayout 上每 250ms 走一遍整棵树：必须限深 + 只认可见节点，
        // 否则首页瀑布流这种超深树会持续吃掉主线程（审计 #15）
        if (depth > 30) return false
        if (root.isShown && root.contentDescription?.startsWith(CARD_DESC_PREFIX) == true) return true
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val c = root.getChildAt(i) ?: continue
                if (isProfilePage(c, depth + 1)) return true
            }
        }
        return false
    }

    /**
     * 主页 ⇒ 确保按钮在；非主页 ⇒ 摘除。
     * **运行中例外（2026-11-22 用户要求「取消按钮需要一直显示」）**：采集会自己翻进详情页，
     * 那几页也要留按钮，否则跑到一半没地方取消。
     */
    private fun syncButton(activity: Activity, module: XposedModule) {
        val decor = activity.window?.decorView as? ViewGroup ?: return
        if (!isProfilePage(decor) && !XhsBatchDriver.isRunning()) {
            detach()
            return
        }
        if (attachedButton != null && attachedDecor === decor) {
            applyLabel()
            panelSync()
            return
        }
        detach()
        val ctx = activity
        val dp = ctx.resources.displayMetrics.density
        val btn = TextView(ctx).apply {
            text = if (XhsBatchDriver.isRunning()) "取消\n${XhsBatchDriver.processedCount()}" else "采集"
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
        attachedActivity = activity
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 悬浮按钮已注入")
        panelSync()
    }

    /**
     * 按钮文案/配色跟随运行态：运行中「取消」（灰底，带已处理篇数——静默下载没有其它进度提示，
     * 用户 2026-11-22 反馈「自动下载没有任何提示」），待机「采集」（红底）。
     */
    private fun applyLabel() {
        val running = XhsBatchDriver.isRunning()
        if (running && !lastRunning) panelDismissed = false   // 新一轮：面板重新回来
        lastRunning = running
        val btn = attachedButton ?: return
        runCatching {
            val n = XhsBatchDriver.processedCount()
            btn.text = when {
                !running -> "采集"
                XhsBatchDriver.isStopping() -> "收尾"
                n > 0 -> "取消\n$n"
                else -> "取消"
            }
            (btn.background as? GradientDrawable)?.setColor(if (running) 0xCC555555.toInt() else 0xCCFF2E4D.toInt())
        }
    }

    /**
     * 面板跟随运行态与当前页：采集期间每翻一页都可能换 decor，面板要跟过去。
     * **关闭只由用户点面板上的 × 决定**（2026-11-22 用户：别自动隐藏/关闭），收工后也留在屏幕上。
     */
    private fun panelSync() {
        val decor = attachedDecor
        val act = attachedActivity
        if (decor == null || act == null) {
            XhsBatchDriver.logEntry("面板未挂：还没有按钮宿主")
            return
        }
        if (panelDismissed) return
        // 收工后也保留最后一份内容（用户要求），只有下一轮开始才重新出现
        ensurePanel(act, decor)
    }

    /**
     * 面板布局（2026-11-22 用户指定）：**顶栏是汇总**（状态/已处理/已存/失败，第二行待处理/用时），
     * 下面才是滚动的过程消息；右上角 × 由用户主动关闭。汇总常驻不滚，过程消息自动滚到底。
     */
    private fun ensurePanel(activity: Activity, decor: ViewGroup) {
        if (attachedPanel != null && panelDecor === decor) {
            renderLines(lastLines)
            return
        }
        removePanel()
        val dp = activity.resources.displayMetrics.density
        val header = TextView(activity).apply {
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            text = XhsBatchDriver.header()
        }
        val close = TextView(activity).apply {
            text = "×"
            textSize = 20f
            setTextColor(0x99FFFFFF.toInt())
            setPadding((10 * dp).toInt(), 0, (6 * dp).toInt(), 0)
            setOnClickListener {
                panelDismissed = true
                removePanel()
            }
        }
        val headerRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(header, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(close)
        }
        val linesBox = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val scroll = android.widget.ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(linesBox, android.widget.FrameLayout.LayoutParams(android.widget.FrameLayout.LayoutParams.MATCH_PARENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT))
            isClickable = false
            isFocusable = false
        }
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply {
                cornerRadius = 14 * dp
                setColor(0xD9000000.toInt())
            }
            addView(headerRow)
            addView(View(activity).apply { setBackgroundColor(0x33FFFFFF) },
                android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 1).apply {
                    topMargin = (6 * dp).toInt(); bottomMargin = (6 * dp).toInt()
                })
            addView(scroll, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            val w = (activity.resources.displayMetrics.widthPixels * 0.86f).toInt()
            layoutParams = FrameLayout.LayoutParams(w, (250 * dp).toInt()).apply {
                gravity = android.view.Gravity.START or android.view.Gravity.BOTTOM
                leftMargin = (12 * dp).toInt()
                bottomMargin = (110 * dp).toInt()
            }
            elevation = (12 * dp)
        }
        val added = runCatching { decor.addView(box) }
        added.onFailure { XhsBatchDriver.logEntry("面板 addView 失败：${it.javaClass.simpleName}: ${it.message}") }
        added.onSuccess {
            XhsBatchDriver.logEntry(
                "面板已挂：${box.width}x${box.height} 父=${decor.javaClass.simpleName} 父宽高=${decor.width}x${decor.height} " +
                    "子数=${decor.childCount} 屏=${activity.resources.displayMetrics.widthPixels}x${activity.resources.displayMetrics.heightPixels}",
            )
        }
        attachedPanel = box
        panelLog = linesBox
        panelHeader = header
        panelDecor = decor
        renderLines(lastLines)
        startTicker(activity)
        XhsBatchDriver.logEntry("悬浮面板已注入 ${activity.javaClass.simpleName}（汇总顶栏 + 过程消息）")
    }

    /** 顶栏每秒刷一次（用时在走，其余数字由驱动事件刷新）。 */
    private fun startTicker(activity: Activity) {
        stopTicker()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                runCatching { panelHeader?.text = XhsBatchDriver.header() }
                if (attachedPanel != null) handler.postDelayed(this, 1000) else ticker = null
            }
        }
        ticker = r
        handler.postDelayed(r, 1000)
    }

    private fun stopTicker() {
        ticker = null
    }

    /** 行级配色：嗅探/落盘失败红、跳过黄、成功绿、其余白。 */
    private fun lineColor(line: String): Int = when {
        listOf("失败", "异常", "解析不出", "未挂", "超时", "错误").any { line.contains(it) } -> 0xFFFF6B6B.toInt()
        listOf("跳过", "已下载过").any { line.contains(it) } -> 0xFFFFC857.toInt()
        listOf("已保存", "成功", "落盘结束").any { line.contains(it) } -> 0xFF6EE7A8.toInt()
        else -> 0xEEFFFFFF.toInt()
    }

    private fun renderLines(lines: List<String>) {
        lastLines = lines
        runCatching { panelHeader?.text = XhsBatchDriver.header() }
        val box = panelLog ?: return
        runCatching {
            val act = attachedActivity ?: return@runCatching
            val dp = act.resources.displayMetrics.density
            box.removeAllViews()
            lines.forEach { line ->
                box.addView(TextView(act).apply {
                    textSize = 13f
                    setTextColor(lineColor(line))
                    setLineSpacing((3 * dp), 1f)
                    text = line
                })
            }
            (attachedPanel as? ViewGroup)?.post {
                runCatching {
                    val sv = (attachedPanel as? ViewGroup)?.getChildAt(2) as? android.widget.ScrollView
                    sv?.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun removePanel() {
        stopTicker()
        runCatching { panelDecor?.let { d -> attachedPanel?.let { d.removeView(it) } } }
        attachedPanel = null
        panelLog = null
        panelHeader = null
        panelDecor = null
    }

    private fun detach() {
        runCatching { attachedDecor?.let { d -> attachedButton?.let { d.removeView(it) } } }
        attachedButton = null
        attachedDecor = null
        attachedActivity = null
        removePanel()
    }

    private fun toggle(ctx: android.content.Context, module: XposedModule) {
        // 这是宿主主线程的点击回调：抛出去就是宿主崩溃（2026-11-22 审计 #2）
        runCatching {
            if (XhsBatchDriver.isRunning()) XhsBatchDriver.stop(module, ctx) else XhsBatchDriver.start(module, ctx)
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 启停失败: ${it.javaClass.simpleName}: ${it.message}")
        }
        runCatching { applyLabel() }
    }

}
