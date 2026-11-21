package top.honorhook.ui.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import top.honorhook.AutoActions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * UI 页可观察状态（驱动在**宿主进程**，进度经广播回传到本进程后写这里）。
 */
object XhsAutoState {
    var running = mutableStateOf(false)
    var phase = mutableStateOf("待机")
    var ok = mutableStateOf(0)
    var skipped = mutableStateOf(0)
    var failed = mutableStateOf(0)
    val logs = mutableStateListOf<String>()
}

/**
 * 批量采集的**滚动器**（2026-11-21 架构定案，职责已收窄）。
 *
 * 2026-11-21 之前这里是完整的「点卡片→详情→长按→保存→返回」编排引擎，用户实测被否：9.54 详情页
 * 没有稳定的长按锚点（`desc=图片` 节点不存在），一篇点空就整轮折返。现架构把「抓取」搬到宿主进程的
 * 旁路（`XhsFeedIndex` 从 `/note/user/posted` 响应登记 noteId，`XhsBatchDriver` 直下），
 * **本服务只剩一件事：把主页往下滚，让列表接口继续被调用**。
 *
 * 好处是可失败面极小：滚动只是 `ACTION_SCROLL_FORWARD`，失败退化为上滑手势；滚不动 = 到底，
 * 宿主侧驱动 20s 无新笔记自然收工，不需要服务判断结束条件。
 *
 * 通道（常量见 [AutoActions]）：收 STATE（开/停滚 + 文案）、PROGRESS（进度计数）、DONE（收工）。
 */
class XhsAutoService : AccessibilityService() {

    companion object {
        private const val PREFS = "honorhook_settings"
        private const val KEY_MIN_S = "xhs_auto_min_s"
        private const val KEY_MAX_S = "xhs_auto_max_s"
        private const val DEF_MIN_S = 2
        private const val DEF_MAX_S = 4
        private const val HOST_PACKAGE = "com.xingin.xhs"
        /** logcat 判据 tag（本机 logcat 被荣耀 HKS 加密，真正可用的是 [fileLog]）。 */
        const val LOG_TAG = "HonorHookXhsAuto"

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var scrolling = false
    private var scroller: Thread? = null

    // ---- 日志：logcat 本机取不到明文（HKS 加密），文件日志是唯一可靠通道 ----
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    private fun log(msg: String) {
        android.util.Log.i(LOG_TAG, msg)
        fileLog(msg)
        main.post {
            XhsAutoState.logs.add("${timeFmt.format(Date())} $msg")
            while (XhsAutoState.logs.size > 60) XhsAutoState.logs.removeAt(0)
        }
    }

    private fun fileLog(msg: String) {
        runCatching {
            val dir = getExternalFilesDir(null) ?: return
            val f = java.io.File(dir, "xhs_auto.log")
            if (f.length() > 512 * 1024) f.delete()
            f.appendText("${timeFmt.format(Date())} $msg\n")
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val text = intent.getStringExtra(AutoActions.EXTRA_TEXT)
            when (intent.action) {
                AutoActions.STATE -> {
                    if (intent.getBooleanExtra(AutoActions.EXTRA_RUNNING, false)) startScroll(text) else stopScroll(text)
                }
                AutoActions.PROGRESS -> {
                    XhsAutoState.ok.value = intent.getIntExtra("ok", 0)
                    XhsAutoState.skipped.value = intent.getIntExtra("skipped", 0)
                    XhsAutoState.failed.value = intent.getIntExtra("failed", 0)
                    text?.let {
                        XhsAutoState.phase.value = it
                        log(it)
                    }
                }
                AutoActions.DONE -> stopScroll(text)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        androidx.core.content.ContextCompat.registerReceiver(
            this, receiver,
            IntentFilter().apply {
                addAction(AutoActions.STATE)
                addAction(AutoActions.PROGRESS)
                addAction(AutoActions.DONE)
            },
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        log("无障碍服务已连接")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        scrolling = false
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- 滚动器 ----

    private fun startScroll(reason: String?) {
        if (scrolling) return
        scrolling = true
        XhsAutoState.running.value = true
        XhsAutoState.phase.value = reason ?: "滚动主页中…"
        log("开始滚动主页")
        Thread({
            // 节奏读设置（用户可在 UI 页调）；每轮滚一屏
            var guard = 0
            while (scrolling && guard++ < 4000) {
                val rootPkg = rootInActiveWindow?.packageName?.toString()
                if (rootPkg != HOST_PACKAGE) {
                    // 用户切走了：不滚，但也不退出（切回来继续），驱动会因无新笔记自行收工
                    log("当前不是小红书（$rootPkg），暂停滚动")
                    sleep(1000)
                    continue
                }
                scrollOnce()
                val p = prefs(this)
                val min = p.getInt(KEY_MIN_S, DEF_MIN_S)
                val max = p.getInt(KEY_MAX_S, DEF_MAX_S)
                sleep((1000L * (min.coerceAtLeast(1)..max.coerceIn(min.coerceAtLeast(1), 30)).random()))
            }
        }, "HonorHook-xhs-scroll").also { scroller = it }.start()
    }

    private fun stopScroll(reason: String?) {
        if (scrolling) log("停止滚动${reason?.let { "：$it" } ?: ""}")
        scrolling = false
        XhsAutoState.running.value = false
        XhsAutoState.phase.value = reason ?: "待机"
    }

    private fun sleep(ms: Long) = runCatching { Thread.sleep(ms) }

    /** 滚一屏：优先真滚动动作，找不到可滚节点才用上滑手势（两种都要能失败兜住）。 */
    private fun scrollOnce() {
        val root = rootInActiveWindow ?: return
        val target = findScrollable(root)
        if (target != null) {
            target.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            return
        }
        // 手势回落：下半屏上滑（与真机实测的列表滚动区域一致）
        val dm = resources.displayMetrics
        val x = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, dm.heightPixels * 0.75f)
            lineTo(x, dm.heightPixels * 0.35f)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        dispatchGesture(gesture, null, null)
    }

    /** 视口内面积最大的可滚动节点（主页瀑布流容器）。 */
    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var best: AccessibilityNodeInfo? = null
        var bestArea = 0L
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30) return
            if (n.isScrollable) {
                val r = android.graphics.Rect().also { n.getBoundsInScreen(it) }
                val area = r.width().toLong() * r.height()
                if (area > bestArea) { bestArea = area; best = n }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        runCatching { walk(root, 0) }
        return best
    }
}
