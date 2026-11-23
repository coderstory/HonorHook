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
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig

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
    // 卡片判据与驱动共用一份：只认「笔记,」时整页视频的作者主页判不出「有卡片」，
    // 按钮不出现（2026-11-22 用户实测）。口径见 XhsBatchDriver.isCardDesc。


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

    /**
     * 面板跟随看门狗（2026-11-22 用户：「信息窗时不时就消失」）。
     *
     * 面板挂在**某一个 Activity 的 decorView** 上：宿主每次换页（列表 ↔ 详情）都是新 decor，旧的随
     * Activity 销毁。原来只靠 onResume + 布局监听补挂，中间有几百 ms 空窗，看起来就是「时不时消失」。
     * 这里每 500ms 比一次「当前前台 Activity 的 decor」与「面板所在 decor」，不一致立刻摘掉重挂；
     * 只在运行期间跑，收工即停（不额外耗电）。
     */
    @Volatile private var panelGuard: Runnable? = null
    @Volatile private var mainHandler: android.os.Handler? = null
    @Volatile private var installedModule: XposedModule? = null
    /** 消息容器：每行一个 TextView，按成败上色（用户 2026-11-22：嗅探失败的要用红色系）。 */
    @Volatile private var panelLog: android.widget.LinearLayout? = null
    @Volatile private var panelStatus: TextView? = null
    /** 暂停/继续按钮（用户 2026-11-23 要求），文案随状态切 ⏸/▶。 */
    @Volatile private var panelPause: TextView? = null
    @Volatile private var panelStatValues: List<TextView> = emptyList()
    @Volatile private var panelDetail: TextView? = null
    @Volatile private var panelScroll: android.widget.ScrollView? = null
    @Volatile private var panelDecor: ViewGroup? = null
    /** 用时每秒都在变，顶栏靠一个 1 秒心跳刷新（其余数字由驱动事件驱动）。 */
    @Volatile private var ticker: Runnable? = null
    /** 面板最近一次内容：面板晚于消息建出来时用它补渲染（首条消息早于注入很常见）。 */
    @Volatile private var lastLines: List<String> = emptyList()
    @Volatile private var renderedLines: List<String> = emptyList()
    /** 用户点过面板上的 ×：本轮不再自动挂回来，下次点「采集」开新一轮才复位。 */
    @Volatile private var panelDismissed = false
    @Volatile private var lastRunning = false
    /** 上一次点击按钮的时刻（双击防抖，避免 start 后第二下立刻 stop）。 */
    @Volatile private var lastToggleAt = 0L

    /** 当前挂着的全局布局观察者（哪棵 decor、哪个 listener、上次检查时间）。 */
    @Volatile private var watchedDecor: ViewGroup? = null
    /** 当前那次注入轮询的 Runnable（销毁时精确取消，不误伤别的定时器）。 */
    @Volatile private var retryRunnable: Runnable? = null
    @Volatile private var watchedListener: android.view.ViewTreeObserver.OnGlobalLayoutListener? = null
    /** 监听器所属的 Activity：decor.context 是 DecorContext，反推不回 Activity，只能自己记。 */
    @Volatile private var watchedActivity: Activity? = null
    @Volatile private var lastCheckAt = 0L

    private val installedOnce = java.util.concurrent.atomic.AtomicBoolean(false)

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        // 主页列表请求常在首屏渲染**之后**才回来：那之后没有新布局事件，光靠 globalLayout
        // 注入会永远等不到按钮（2026-11-22 审计 P1 + 用户实测「按钮出现又立马隐藏」）。
        val ui = android.os.Handler(android.os.Looper.getMainLooper())
        XhsAuthorTrack.onProfileSignal = {
            ui.post { runCatching { XhsBatchSave.activityOrNull()?.let { a -> syncButton(a, module) } } }
        }
        // 进程级只装一次：install 被调两次时生命周期回调与广播会各注册一遍（回调跑两次、
        // 两条注入轮询、STOP 处理两次），而这两条都没有反注册路径（2026-11-22 审计 P2）。
        if (!installedOnce.compareAndSet(false, true)) return
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
                // 这里一旦抛就是宿主主线程未捕获异常（本文件其它主线程路径全包了 runCatching）
                mainHandler = main
                installedModule = module
                XhsBatchDriver.stateListener = { main.post { runCatching { applyLabel(); panelSync(); ensurePanelGuard(module) } } }
                // 过程消息 → 悬浮面板（不再逐条 Toast：用户 2026-11-22「toast 不太行」）
                XhsBatchDriver.uiListener = { lines -> main.post { runCatching { renderLines(lines) } } }
                // 用户 2026-11-23：「验证码页面能显示出来吗？做一个弹窗，让用户处理」
                XhsBatchDriver.captchaPrompt = { url -> main.post { runCatching { showCaptchaDialog(url) } } }
                runCatching { registerStopReceiver(app, module, main) }
                    .onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 停止接收器注册失败: ${it.message}") }
                runCatching {
                    app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                        override fun onActivityResumed(a: Activity) {
                            // 主页瀑布流首帧晚于 onResume（真机实测几百 ms~数秒），且可能
                            // 靠网络分页才出现 —— 单靠轮询要么慢要么超时收手（用户 2026-11-22
                            // 反馈「按钮展示不灵光」）。改成「布局观察者为主 + 轮询兜底」。
                            watchDecor(a, module, main)
                            // 立刻补一拍：换页时面板要马上跟到新 decor（等轮询第一拍就是肉眼可见的「消失」）
                            main.post { runCatching { syncButton(a, module) } }
                            retrySync(a, module, main, attempts = 30, intervalMs = 500)
                        }

                        override fun onActivityPaused(a: Activity) {
                            if (watchedActivity === a || watchedDecor === a.window?.decorView) {
                                unwatch()
                            }
                            // 只删本页的轮询，不用 removeCallbacksAndMessages(null) 全清（审计 #18）；
                            // 删完要清字段，否则静态字段一直握着含 Activity 的闭包（审计 P2）
                            retryRunnable?.let { main.removeCallbacks(it) }
                            retryRunnable = null
                        }
                        override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                        override fun onActivityStarted(a: Activity) {}
                        override fun onActivityStopped(a: Activity) {}
                        override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                        override fun onActivityDestroyed(a: Activity) {
                            // 宿主 Activity 销毁必须摘按钮并取消未跑的延迟挂载，否则静态引用
                            // 拖住整棵 decor 视图树（2026-11-21 审计 #5）
                            // decorView 可能已被替换/置空，身份比较之外再按 context 判一次（审计 #17）
                            // decor.context 是 DecorContext，`as? Activity` 恒 null（本文件 38-41 行已实证），
                            // 身份比较只能用自己记下的 watchedActivity/attachedActivity
                            if (watchedActivity === a || watchedDecor === a.window?.decorView) {
                                unwatch()
                            }
                            if (attachedActivity === a || attachedDecor === a.window?.decorView) {
                                retryRunnable?.let { main.removeCallbacks(it) }
                                retryRunnable = null
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
            watchedActivity = activity
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
        watchedActivity = null
    }

    /** 每 [intervalMs] 试一次注入，最多 [attempts] 次（现在 30×500ms ≈15s）：主页渲染慢也能等到。 */
    private fun retrySync(activity: Activity, module: XposedModule, main: android.os.Handler, attempts: Int, intervalMs: Long) {
        // 分屏/小窗下两个 Activity 可同时 resumed（没有 onPause），不取消上一条链就会有两条
        // 6 秒轮询同时改 attachedButton/attachedDecor，把按钮挂到不可见的那一页（审计 P2）
        retryRunnable?.let { main.removeCallbacks(it) }
        retryRunnable = null
        val r = object : Runnable {
            private var left = attempts
            override fun run() {
                if (left-- <= 0) return
                // 页已退出/被替换就不必再试
                if (activity.isFinishing || (attachedDecor === activity.window?.decorView && attachedButton != null)) return
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
                // 广播线程兜底：onReceive 里任何异常都会以「未捕获异常」杀掉宿主进程
                // （stop() 里要打 module.log，模块日志一抛就带崩小红书）——2026-11-22 审计 #1
                runCatching {
                    when (i.action) {
                        AutoActions.STOP -> {
                            XhsBatchDriver.stop(module, app)
                            main.post { applyLabel() }
                        }
                        AutoActions.PAUSE -> {
                            XhsBatchDriver.togglePause()
                            main.post { runCatching { applyLabel() } }
                        }
                        AutoActions.RETRY -> {
                            XhsBatchDriver.retryFailed(module, app)
                        }
                        AutoActions.QUERY -> {
                            // 模块侧进程与驱动不同进程：面板每次进页都问一次真实状态
                            XhsBatchDriver.publishState()
                        }
                        AutoActions.CONFIG -> {
                            XhsBatchDriver.setPace(
                                i.getIntExtra(AutoActions.EXTRA_PACE_MIN_DS, 20),
                                i.getIntExtra(AutoActions.EXTRA_PACE_MAX_DS, 40),
                            )
                        }
                    }
                }.onFailure {
                    runCatching { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 广播处理异常: ${it.javaClass.simpleName}") }
                }
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            app, receiver,
            android.content.IntentFilter().apply {
                addAction(AutoActions.STOP)
                addAction(AutoActions.PAUSE)
                addAction(AutoActions.RETRY)
                addAction(AutoActions.QUERY)
                addAction(AutoActions.CONFIG)
            },
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    /** 主页判据：decorView 内存在 desc 以「笔记,」开头的视图（瀑布流卡片）。深度优先，找到即返回。 */

    /**
     * 主页 ⇒ 确保按钮在；非主页 ⇒ 摘除。
     * **运行中例外（2026-11-22 用户要求「取消按钮需要一直显示」）**：采集会自己翻进详情页，
     * 那几页也要留按钮，否则跑到一半没地方取消。
     */
    private fun syncButton(activity: Activity, module: XposedModule) {
        // 功能开关（FeatureCatalog.KEY_XHS_BATCH，默认关）：关着就彻底不注入按钮/面板，
        // 也不参与驱动——宿主页面零改动（2026-11-22 用户要求「还缺了功能开关，默认关闭」）。
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_XHS_BATCH)) {
            if (attachedButton != null) detach()
            return
        }
        val decor = activity.window?.decorView as? ViewGroup ?: return
        // 判据 = 内容（卡片 + 资料区计数），不再依赖宿主请求信号：信号在列表来自缓存时为假，
        // 按钮会永不出现（2026-11-23 用户实测「旧版本能识别主页」）。运行中一律保留（取消入口）。
        if (!XhsBatchDriver.isRunning() && !XhsBatchDriver.looksLikeProfileContent(decor)) {
            // 判据失效时按钮会静默消失（用户和诊断都看不出原因）：摘除前记一次
            if (attachedButton != null) {
                XhsBatchDriver.logEntry(
                    "按钮摘除：这页不像作者主页（无卡片或无资料区计数；${XhsAuthorTrack.snapshot()} 前台=${activity.javaClass.simpleName}）"
                )
            }
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
    /**
     * 一键复制诊断（用户 2026-11-22）：剪贴板 + 落一份文件，异常时直接粘给 AI 分析。
     * 面板右上角「⧉」触发，采集前后都能点。
     */
    private fun copyDiagnostics(activity: Activity) {
        // 读最多 512KB 的日志 + 写盘都是外部存储 I/O：主线程做会掉帧甚至 ANR（审计 P2）。
        // 后台拼好文本，再回主线程写剪贴板 + 提示。
        val app = activity.applicationContext
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        Thread({
            val text = runCatching { XhsBatchDriver.diagnostics(app) }.getOrNull()
            main.post {
                if (text == null) {
                    runCatching {
                        android.widget.Toast.makeText(app, "诊断生成失败", android.widget.Toast.LENGTH_SHORT).show()
                    }
                    return@post
                }
                runCatching {
                    val cm = app.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as? android.content.ClipboardManager
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("HonorHook诊断", text))
                    android.widget.Toast.makeText(app, "诊断信息已复制（${text.length} 字符）", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
        }, "HonorHook-xhs-diag").start()
    }

    private fun applyLabel() {
        val running = XhsBatchDriver.isRunning()
        if (running && !lastRunning) panelDismissed = false   // 新一轮：面板重新回来
        if (!running && lastRunning) {
            // 收工：心跳停掉 + 顶栏立刻刷一次（header 在 !running 时用 endedAt 冻住用时，
            // 否则面板会永远显示「已结束 · 用时 3:41 还在涨」——2026-11-22 审计 #13）
            stopTicker()
            refreshPanel()
        }
        lastRunning = running
        // 运行中保证看门狗在跑；收工后它会自己退出（见 ensurePanelGuard）
        if (running) installedModule?.let { ensurePanelGuard(it) }
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
    /** 运行期间持续把面板跟到当前前台页（见 [panelGuard] 注释）。 */
    /**
     * 风控验证弹窗：把 WebView 重定向到的验证码页直接展示出来，用户在宿主里自己过。
     * 用户 2026-11-23：「这个验证码页面能显示出来吗？做一个弹窗，让用户处理」。
     * 验证码只对「真人 + 宿主 cookie」有意义，所以我们不伪造不绕过，只负责展示，
     * 并在用户点「已验证」后解除暂停（失败篇由用户点面板 ↻ 重试）。
     */
    private fun showCaptchaDialog(url: String) {
        val activity = attachedActivity ?: XhsBatchSave.activityOrNull() ?: return
        val dp = activity.resources.displayMetrics.density
        fun px(v: Float): Int = (v * dp).toInt()
        val root = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(14f), px(14f), px(14f), px(10f))
        }
        val title = TextView(activity).apply {
            text = "需要人工验证（小红书风控）"
            textSize = 15f
            setTextColor(0xFF1A1A1A.toInt())
            setPadding(0, 0, 0, px(8f))
        }
        val hint = TextView(activity).apply {
            text = "在下面把验证过掉，然后点「已验证，继续采集」"
            textSize = 12f
            setTextColor(0xFF666666.toInt())
            setPadding(0, 0, 0, px(8f))
        }
        val web = android.webkit.WebView(activity).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = XhsVideoSave.XHS_WEB_UA
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT, px(420f),
            )
            runCatching { loadUrl(url) }
        }
        val dialog = android.app.Dialog(activity, android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar)
        val done = TextView(activity).apply {
            text = "已验证，继续采集"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(0xFFEC407A.toInt())
            setPadding(0, px(10f), 0, px(10f))
            setOnClickListener {
                runCatching { dialog.dismiss() }
                runCatching { XhsBatchDriver.setPaused(false, null) }
                runCatching { refreshPanel() }
            }
        }
        root.addView(title)
        root.addView(hint)
        root.addView(web)
        root.addView(done)
        dialog.setContentView(root)
        dialog.setCancelable(true)
        dialog.setOnDismissListener { runCatching { web.destroy() } }
        runCatching { dialog.show() }
    }

    private fun ensurePanelGuard(module: XposedModule) {
        if (panelGuard != null || !XhsBatchDriver.isRunning()) return
        val main = mainHandler ?: return
        val r = object : Runnable {
            override fun run() {
                if (!XhsBatchDriver.isRunning()) {
                    panelGuard = null
                    return
                }
                runCatching {
                    val act = XhsBatchSave.activityOrNull()
                    if (act != null && !act.isFinishing) syncButton(act, module)
                }
                main.postDelayed(this, 500)
            }
        }
        panelGuard = r
        main.postDelayed(r, 500)
    }

    private fun panelSync() {
        val decor = attachedDecor
        val act = attachedActivity
        if (decor == null || act == null) {
            XhsBatchDriver.logEntry("面板未挂：还没有按钮宿主")
            return
        }
        if (panelDismissed) return
        // 没在采集就不要凭空造面板（2026-11-22 用户：还没开始采集时别显示信息窗）；
        // 已在屏幕上的那份保持不动——收工不自动隐藏，只有 × 能关。
        if (!XhsBatchDriver.isRunning()) return
        ensurePanel(act, decor)
    }

    /**
     * 面板布局（2026-11-22 用户指定）：**顶栏是汇总**（状态/已处理/已存/失败，第二行待处理/用时），
     * 下面才是滚动的过程消息；右上角 × 由用户主动关闭。汇总常驻不滚，过程消息自动滚到底。
     */
    private fun ensurePanel(activity: Activity, decor: ViewGroup) {
        val existing = attachedPanel
        if (existing != null) {
            if (panelDecor === decor) {
                renderLines(lastLines)
                // 统计要一直跳：心跳只在「新建面板」时启动过，换页走的是搬家分支，
                // 早先这里直接 return ⇒ 顶栏数字停住不动（用户 2026-11-22「统计不实时更新」）
                startTicker()
                return
            }
            // **换页不重建，直接搬家**（2026-11-22 用户：「切换列表页和详情页会消失，然后又刷出来」）。
            // 旧做法是先 removePanel 再 addView 到新 decor：中间隔着一次布局，看起来就是闪一下；
            // 现在把同一个 View 从旧父摘掉立刻挂到新父上，同一帧完成，内容/滚动位置都不丢。
            val moved = runCatching {
                (existing.parent as? ViewGroup)?.removeView(existing)
                decor.addView(existing, existing.layoutParams)
            }
            if (moved.isSuccess) {
                panelDecor = decor
                renderLines(lastLines)
                startTicker()
                return
            }
            removePanel()
        }
        val dp = activity.resources.displayMetrics.density
        fun px(v: Float): Int = (v * dp).toInt()

        // 顶部：状态徽标 + 复制/关闭（用户 2026-11-22：「信息窗好好设计一下，目前我都不能好好查看」）
        val status = TextView(activity).apply {
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(0xFF8CFFC1.toInt())
            letterSpacing = 0.04f
            text = "采集中"
        }
        val pause = TextView(activity).apply {
            text = if (XhsBatchDriver.isPaused()) "▶" else "⏸"
            textSize = 15f
            setTextColor(0xEEFFFFFF.toInt())
            setPadding(px(9f), px(2f), px(9f), px(2f))
            setOnClickListener {
                val nowPaused = XhsBatchDriver.togglePause()
                text = if (nowPaused) "▶" else "⏸"
                refreshPanel()
            }
        }
        val retry = TextView(activity).apply {
            text = "↻"
            textSize = 16f
            setTextColor(0xEEFFFFFF.toInt())
            setPadding(px(9f), px(2f), px(9f), px(2f))
            setOnClickListener {
                val m = installedModule
                if (m == null) {
                    android.widget.Toast.makeText(activity, "模块未就绪，无法重试", android.widget.Toast.LENGTH_SHORT).show()
                } else {
                    XhsBatchDriver.retryFailed(m, activity)
                }
            }
        }
        val copy = TextView(activity).apply {
            text = "⧉"
            textSize = 17f
            setTextColor(0xEEFFFFFF.toInt())
            setPadding(px(9f), px(2f), px(9f), px(2f))
            setOnClickListener { copyDiagnostics(activity) }
        }
        val close = TextView(activity).apply {
            text = "×"
            textSize = 19f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(px(9f), px(2f), px(4f), px(2f))
            setOnClickListener {
                panelDismissed = true
                removePanel()
            }
        }
        val titleRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(status, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(pause)
            addView(retry)
            addView(copy)
            addView(close)
        }

        // 四列大数字：一行能扫完，比长句好读
        val statRow = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, px(6f), 0, px(2f))
        }
        val statValues = ArrayList<TextView>(4)
        listOf("已处理 篇", "累计 资源", "失败", "用时").forEach { label ->
            val value = TextView(activity).apply {
                textSize = 19f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
                text = "0"
            }
            val cap = TextView(activity).apply {
                textSize = 10f
                setTextColor(0xB3FFFFFF.toInt())
                text = label
            }
            val col = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                addView(value)
                addView(cap)
            }
            statRow.addView(col, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            statValues += value
        }

        val detail = TextView(activity).apply {
            textSize = 11.5f
            setTextColor(0xCCFFFFFF.toInt())
            text = "当前文章 0 个资源 · 本屏待处理 0 · 跳过 0"
        }

        val linesBox = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
        }
        val scroll = android.widget.ScrollView(activity).apply {
            isVerticalScrollBarEnabled = false
            addView(
                linesBox,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
            isClickable = false
            isFocusable = false
        }

        val metrics = activity.resources.displayMetrics
        val box = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(px(14f), px(10f), px(14f), px(10f))
            // 暗红→暗粉渐变（用户要红粉底），压暗保证白字对比度
            background = GradientDrawable().apply {
                cornerRadius = 16 * dp
                gradientType = GradientDrawable.LINEAR_GRADIENT
                orientation = GradientDrawable.Orientation.TL_BR
                colors = intArrayOf(0xF0A31515.toInt(), 0xF0AD1457.toInt())
            }
            addView(titleRow)
            addView(statRow)
            addView(detail)
            addView(
                View(activity).apply { setBackgroundColor(0x40FFFFFF) },
                android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    1,
                ).apply { topMargin = px(7f); bottomMargin = px(5f) },
            )
            addView(scroll, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            layoutParams = FrameLayout.LayoutParams(
                metrics.widthPixels - px(20f),
                (metrics.heightPixels * 0.42f).toInt(),
            ).apply {
                gravity = android.view.Gravity.START or android.view.Gravity.BOTTOM
                leftMargin = px(10f)
                bottomMargin = px(100f)
            }
            elevation = 14 * dp
        }
        val added = runCatching { decor.addView(box) }
        if (added.isFailure) {
            // 挂失败就别登记为「已挂」：否则 panelSync 认为在树上，本轮面板永远不再重建
            XhsBatchDriver.logEntry("面板 addView 失败：${added.exceptionOrNull()?.javaClass?.simpleName}: ${added.exceptionOrNull()?.message}")
            return
        }
        added.onSuccess {
            XhsBatchDriver.logEntry(
                "面板已挂：${box.width}x${box.height} 父=${decor.javaClass.simpleName} 父宽高=${decor.width}x${decor.height} " +
                    "子数=${decor.childCount} 屏=${activity.resources.displayMetrics.widthPixels}x${activity.resources.displayMetrics.heightPixels}",
            )
        }
        attachedPanel = box
        panelLog = linesBox
        panelStatus = status
        panelPause = pause
        panelStatValues = statValues
        panelDetail = detail
        panelScroll = scroll
        panelDecor = decor
        renderLines(lastLines)
        startTicker()
        XhsBatchDriver.logEntry("悬浮面板已注入 ${activity.javaClass.simpleName}（汇总顶栏 + 过程消息）")
    }

    /** 顶栏每秒刷一次（用时在走，其余数字由驱动事件刷新）。 */
    /** 顶栏（状态/四列数字/当前文章）统一从这里刷，ticker 与数据事件共用。 */
    private fun refreshPanel() {
        runCatching {
            val st = XhsBatchDriver.stats()
            panelPause?.text = if (XhsBatchDriver.isPaused()) "▶" else "⏸"
            panelStatus?.apply {
                text = XhsBatchDriver.stateLabel()
                setTextColor(
                    when {
                        XhsBatchDriver.isStoppingNow() -> 0xFFFFD166.toInt()
                        XhsBatchDriver.isRunning() -> 0xFF8CFFC1.toInt()
                        else -> 0xE6FFFFFF.toInt()
                    },
                )
            }
            panelStatValues.getOrNull(0)?.text = st[0].toString()
            panelStatValues.getOrNull(1)?.text = st[1].toString()
            panelStatValues.getOrNull(2)?.text = st[2].toString()
            // 用时统一两位：00:15 而不是 0:15（用户 2026-11-22）
            panelStatValues.getOrNull(3)?.text =
                "${(st[6] / 60).toString().padStart(2, '0')}:${(st[6] % 60).toString().padStart(2, '0')}"
            panelDetail?.text = "当前文章 ${st[4]} 个资源 · 本屏待处理 ${st[5]} · 跳过 ${st[3]}"
        }
    }

    private fun startTicker() {
        stopTicker()
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val r = object : Runnable {
            override fun run() {
                // 只认「当前这一条」心跳：换页会重建面板，旧 Runnable 不能复活
                if (ticker !== this) return
                refreshPanel()
                if (attachedPanel != null) handler.postDelayed(this, 1000) else ticker = null
            }
        }
        ticker = r
        handler.postDelayed(r, 1000)
    }

    /**
     * 真取消：只置 null 的话旧 Runnable 到点还会跑，且它看的是共享的 attachedPanel
     * （新面板已挂上就 postDelayed 自己），每翻一页多一条 1s 心跳（2026-11-22 审计 P1）。
     */
    private fun stopTicker() {
        val r = ticker
        ticker = null
        if (r != null) android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(r)
    }

    /** 行级配色：嗅探/落盘失败红、跳过黄、成功绿、其余白。 */
    private fun lineColor(line: String): Int = when {
        listOf("失败", "异常", "解析不出", "未挂", "超时", "错误").any { line.contains(it) } -> 0xFFFF6B6B.toInt()
        listOf("跳过", "已下载过").any { line.contains(it) } -> 0xFFFFC857.toInt()
        listOf("已保存", "成功", "落盘结束").any { line.contains(it) } -> 0xFF6EE7A8.toInt()
        else -> 0xEEFFFFFF.toInt()
    }

    private fun renderLines(lines: List<String>) {
        // 同一份列表每秒会被 header 心跳重复推一遍：内容没变就别 removeAllViews 重建
        // （重建会触发下一次 onGlobalLayout，形成 4Hz 自激全量重建，长跑后主线程掉帧）
        if (lines === renderedLines) {
            refreshPanel()
            return
        }
        renderedLines = lines
        lastLines = lines
        refreshPanel()
        val box = panelLog ?: return
        runCatching {
            val act = attachedActivity ?: return@runCatching
            val dp = act.resources.displayMetrics.density
            box.removeAllViews()
            lines.forEach { raw ->
                val line = raw.removePrefix("· ").trim()
                box.addView(TextView(act).apply {
                    textSize = 12.5f
                    setTextColor(lineColor(line))
                    setLineSpacing(4 * dp, 1f)
                    setPadding(0, (2 * dp).toInt(), 0, (2 * dp).toInt())
                    text = if (line.startsWith("已保存") || line.startsWith("落盘") || line.startsWith("台账")) "✔ $line" else "· $line"
                })
            }
            (attachedPanel as? ViewGroup)?.post {
                runCatching {
                    panelScroll?.fullScroll(View.FOCUS_DOWN)
                }
            }
        }
    }

    private fun removePanel() {
        stopTicker()
        // 去重游标必须一起清：不清的话重建面板时 renderLines(lastLines) 传进来的还是同一个
        // List 实例，直接 return，新面板只有顶栏、日志区空白（2026-11-22 审计 #14）
        renderedLines = emptyList()
        runCatching { panelDecor?.let { d -> attachedPanel?.let { d.removeView(it) } } }
        attachedPanel = null
        panelLog = null
        panelStatus = null
        panelPause = null
        panelStatValues = emptyList()
        panelDetail = null
        panelScroll = null
        panelDecor = null
    }

    private fun detach() {
        // 不要在这里置空 XhsAuthorTrack.onProfileSignal：install 只跑一次（installedOnce），
        // 置空后「主页列表请求晚于首屏布局」的补注入就永久失效（2026-11-22 复核 P1-5）。
        runCatching { attachedDecor?.let { d -> attachedButton?.let { d.removeView(it) } } }
        attachedButton = null
        attachedDecor = null
        attachedActivity = null
        removePanel()
    }

    private fun toggle(ctx: android.content.Context, module: XposedModule) {
        // 双击防抖：start 是同步置 running=true，第二下点击会立刻走 stop，变成「刚开就收尾」
        val now = System.currentTimeMillis()
        if (now - lastToggleAt < 600) return
        lastToggleAt = now
        // 这是宿主主线程的点击回调：抛出去就是宿主崩溃（2026-11-22 审计 #2）
        runCatching {
            if (!XhsBatchDriver.isRunning() && !RemoteConfig.isEnabled(FeatureCatalog.KEY_XHS_BATCH)) {
                // 开关关着时按钮不该存在；万一残留，点了也不跑（不静默吞，写一行日志）
                XhsBatchDriver.logEntry("批量采集开关是关的，忽略点击（功能页开启 xhs.batch 后生效）")
                return@runCatching
            }
            if (XhsBatchDriver.isRunning()) XhsBatchDriver.stop(module, ctx) else XhsBatchDriver.start(module, ctx)
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 启停失败: ${it.javaClass.simpleName}: ${it.message}")
        }
        runCatching { applyLabel() }
        mainHandler?.let { ensurePanelGuard(module) }
    }

}
