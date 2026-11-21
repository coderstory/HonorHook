package top.honorhook.ui.auto

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import top.honorhook.xposed.target.xhs.XhsAutoEntry

/**
 * 批量采集编排引擎（2026-11-21）：无障碍服务在**模块自身进程**跑「点卡片→详情→长按→保存→
 * 返回→滚动」循环，触发宿主内 XhsBatchSave hook 接管下载（去重/过滤全部复用现有管线）。
 *
 * 编排判据全部来自 2026-11-21 真机 Shizuku 实测（作者主页 1272x2800）：
 *   - 主页卡片 = contentDescription 前缀「笔记,」的可点节点（瀑布流）
 *   - 详情页   = 出现 text「说点什么...」（评论框占位）
 *   - 长按目标 = desc 前缀「图片」节点中心，900ms 手势（语义 longClick 实测不可用）
 *   - 保存项   = text「保存」节点的最近可点祖先（长按面板）
 *   - 滚动     = 下半屏上滑手势；滚动后无新卡片 = 到底收工
 * 悬浮按钮广播（XhsAutoEntry.ACTION_XHS_AUTO_TOGGLE，宿主进程发来）启停；UI 页停止按钮同路。
 *
 * 半途取消（用户拍板）：volatile 旗标，循环各步间与长延时点检查；停止后停在当前页不回主页。
 */
/** UI 页可观察状态（Compose mutableStateOf 可从引擎线程安全写）。顶层 object：跨文件引用省一层 companion。 */
object XhsAutoState {
    var running = mutableStateOf(false)
    var phase = mutableStateOf("待机")
    var done = mutableStateOf(0)          // 本轮已处理笔记数
    var skippedLowQ = mutableStateOf(0)   // 弹卡「不用了」/ 未命中保存项的跳过数（含低画质思路的兜底计数）
    val logs = mutableStateListOf<String>()
}

class XhsAutoService : AccessibilityService() {

    companion object {
        private const val PREFS = "honorhook_settings"
        private const val KEY_MIN_S = "xhs_auto_min_s"
        private const val KEY_MAX_S = "xhs_auto_max_s"
        private const val DEF_MIN_S = 2
        private const val DEF_MAX_S = 4

        private const val CARD_DESC_PREFIX = "笔记,"
        private const val IMAGE_DESC_PREFIX = "图片"
        private const val DETAIL_TEXT = "说点什么..."
        private const val SAVE_TEXT = "保存"
        private const val HOST_PACKAGE = "com.xingin.xhs"
        private const val MAX_NOTES = 200          // 防呆上限：一个作者跑不完也不无限滚
        private const val DETAIL_WAIT_MS = 8_000L
        private const val MENU_WAIT_MS = 5_000L
        private const val LONG_PRESS_MS = 900L

        fun prefs(context: Context): SharedPreferences =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    /** 引擎代次：每次 start +1；旧线程下一检查点见代次不符即自退（防 stop→立刻 start 双引擎并发）。 */
    @Volatile private var gen = 0
    private var engine: Thread? = null

    private fun live(g: Int) = gen == g

    /** 宿主进程悬浮按钮广播（显式 setPackage，API 33+ 需 exported receiver 才收得到）。 */
    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == XhsAutoEntry.ACTION_XHS_AUTO_TOGGLE) toggle()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        androidx.core.content.ContextCompat.registerReceiver(
            this, toggleReceiver, IntentFilter(XhsAutoEntry.ACTION_XHS_AUTO_TOGGLE),
            androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(toggleReceiver) }
        stop("服务断开")
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---- 启停（广播 / UI 页同一入口；引擎线程单例）----

    fun toggle() {
        if (running) stop("已停止") else start()
    }

    private fun start() {
        if (running) return
        running = true
        XhsAutoState.running.value = true
        XhsAutoState.done.value = 0
        XhsAutoState.skippedLowQ.value = 0
        XhsAutoState.logs.clear()
        log("批量采集开始（节奏 ${prefs(this).getInt(KEY_MIN_S, DEF_MIN_S)}~${prefs(this).getInt(KEY_MAX_S, DEF_MAX_S)}s）")
        val my = ++gen
        engine = Thread({ runCatching { loop(my) }.onFailure { log("异常终止: ${it.message}") ; finish("异常") } }, "XhsAutoEngine").apply { start() }
    }

    fun stop(why: String) {
        if (!running) return
        running = false
        XhsAutoState.running.value = false       // UI 即刻反馈，不等引擎走到下一检查点（审计 #4）
        XhsAutoState.phase.value = "停止中…"
        log(why)
        // engine 线程在下一检查点自退；不 join（可能长在手势回调里）
    }

    private fun finish(why: String) {
        running = false
        XhsAutoState.running.value = false
        XhsAutoState.phase.value = "结束（$why，共 ${XhsAutoState.done.value} 篇）"
        log("结束：$why，共处理 ${XhsAutoState.done.value} 篇")
    }

    // ---- 主循环 ----

    private fun loop(my: Int) {
        XhsAutoState.phase.value = "运行中"
        val processed = HashSet<String>()
        var sinceNew = 0
        while (live(my) && XhsAutoState.done.value < MAX_NOTES) {
            // 1) 找一张未处理卡片
            val card = findCard(processed)
            if (card == null) {
                // 视口内没有新卡片 → 滚动找；连滚 3 屏仍无新货 = 到底
                if (sinceNew >= 3) { finish("没有更多笔记"); return }
                sinceNew++
                XhsAutoState.phase.value = "滚动列表（${XhsAutoState.done.value} 已处理）"
                scrollDown()
                sleep(randomPaceMs() / 2)
                continue
            }
            sinceNew = 0
            val desc = card.desc
            processed.add(desc)
            XhsAutoState.phase.value = "打开第 ${XhsAutoState.done.value + 1} 篇"
            if (!card.node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                XhsAutoState.skippedLowQ.value++
                log("卡片点击失败，跳过")
                continue
            }
            // 2) 等详情页（评论框占位出现）
            XhsAutoState.phase.value = "等待详情页"
            if (!waitFor({ findDetailRoot() != null }, DETAIL_WAIT_MS, my)) {
                XhsAutoState.skippedLowQ.value++
                log("详情页未出现（可能视频/广告卡），返回跳过")
                performGlobalAction(GLOBAL_ACTION_BACK)
                sleep(randomPaceMs())
                continue
            }
            // 3) 长按图片唤原生面板
            XhsAutoState.phase.value = "长按图片"
            if (!longPressImage()) {
                XhsAutoState.skippedLowQ.value++
                log("没找到图片节点，返回跳过")
                performGlobalAction(GLOBAL_ACTION_BACK)
                sleep(randomPaceMs())
                continue
            }
            // 4) 点「保存」（hook 接管 → 原图直下）
            XhsAutoState.phase.value = "点保存"
            // 长按手势是异步的：先等菜单真的出现再点（2026-11-21 审计 #2，缺这一步每篇都会空点跳过）
            if (!waitFor({ findSaveNode() != null }, MENU_WAIT_MS, my) || !clickSave()) {
                XhsAutoState.skippedLowQ.value++
                log("保存菜单未出现，返回跳过")
                performGlobalAction(GLOBAL_ACTION_BACK)
                sleep(randomPaceMs())
                continue
            }
            XhsAutoState.done.value++
            // 5) 等保存菜单消失 + 节奏延时（hook 下载是异步的，给足落盘时间）
            waitFor({ findSaveNode() == null }, MENU_WAIT_MS, my)
            val extra = if (XhsAutoState.done.value == 1) 3_000L else 0L   // 首篇多等 3s（Fresco 冷启）
            sleep(randomPaceMs() + extra)
            // 6) 返回主页
            XhsAutoState.phase.value = "返回列表"
            performGlobalAction(GLOBAL_ACTION_BACK)
            sleep(1200)
        }
        finish(if (XhsAutoState.done.value >= MAX_NOTES) "达到上限 $MAX_NOTES 篇" else "已停止")
    }

    // ---- 节点查找（DFS，带深度上限防爆炸）----

    private data class CardHit(val node: AccessibilityNodeInfo, val desc: String)

    private fun findCard(processed: Set<String>): CardHit? {
        val root = rootInActiveWindow ?: return null
        // 已处理的也匹配但要在谓词里排除：否则命中第一张旧卡就 return null，
        // 视口内还有新卡也看不见（2026-11-21 审计 #3）
        val hit = dfs(root, 0, 30) { n ->
            n.isClickable && n.contentDescription?.startsWith(CARD_DESC_PREFIX) == true &&
                n.contentDescription.toString() !in processed
        } ?: return null
        val desc = hit.contentDescription?.toString() ?: return null
        return CardHit(hit, desc)
    }

    private fun findDetailRoot(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        if (root.packageName != HOST_PACKAGE) return null
        return dfs(root, 0, 40) { n ->
            n.text == DETAIL_TEXT || n.contentDescription?.contains("评论框") == true
        }
    }

    private fun findSaveNode(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        return dfs(root, 0, 40) { n -> n.text == SAVE_TEXT && n.isVisibleToUser }
    }

    /** 最近可点祖先点击（保存项：TextView 本身不可点，点它的可点容器——真机实证形态）。 */
    private fun clickSave(): Boolean {
        val n = findSaveNode() ?: return false
        var cur: AccessibilityNodeInfo? = n
        repeat(5) {
            val c = cur ?: return false
            if (c.isClickable) return c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            cur = c.parent
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private fun longPressImage(): Boolean {
        val root = rootInActiveWindow ?: return false
        val img = dfs(root, 0, 40) { n -> n.contentDescription?.startsWith(IMAGE_DESC_PREFIX) == true }
            ?: return false
        val r = Rect()
        img.getBoundsInScreen(r)
        if (r.isEmpty) return false
        val path = Path().apply { moveTo(r.exactCenterX(), r.exactCenterY()) }
        val stroke = GestureDescription.StrokeDescription(path, 0, LONG_PRESS_MS)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    private fun scrollDown(): Boolean {
        val metrics = resources.displayMetrics
        val x = metrics.widthPixels / 2f
        val path = Path().apply {
            moveTo(x, metrics.heightPixels * 0.75f)
            lineTo(x, metrics.heightPixels * 0.35f)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 400)
        return dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }

    /** 深度优先找第一个满足条件的节点；深度上限 maxDepth，单层子节点全扫（小红书树深而宽可控）。 */
    private fun dfs(n: AccessibilityNodeInfo, depth: Int, maxDepth: Int, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(n)) return n
        if (depth >= maxDepth) return null
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val hit = dfs(c, depth + 1, maxDepth, pred)
            if (hit != null) return hit
        }
        return null
    }

    /** 轮询等条件成立；随时可被停止旗标打断。 */
    private fun waitFor(probe: () -> Boolean, timeoutMs: Long, g: Int): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (live(g) && System.currentTimeMillis() < deadline) {
            if (probe()) return true
            sleep(300)
        }
        return live(g) && probe()
    }

    private fun randomPaceMs(): Long {
        val p = prefs(this)
        val min = p.getInt(KEY_MIN_S, DEF_MIN_S).coerceAtLeast(1)
        val max = p.getInt(KEY_MAX_S, DEF_MAX_S).coerceAtLeast(min)
        return (min..max).random() * 1000L
    }

    /** 可中断 sleep（停止旗标唤醒后的下一检查点自然退出）。 */
    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
    }

    private fun log(msg: String) {
        main.post {
            XhsAutoState.logs.add(0, "${android.text.format.DateFormat.format("HH:mm:ss", System.currentTimeMillis())}  $msg")
            while (XhsAutoState.logs.size > 60) XhsAutoState.logs.removeAt(XhsAutoState.logs.size - 1)
        }
    }
}
