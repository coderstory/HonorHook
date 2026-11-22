package top.honorhook.xposed.target.xhs

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import top.honorhook.AutoActions
import top.honorhook.xposed.core.MountEngine

/**
 * 批量采集驱动（2026-11-22 架构定案 v3，跑在**宿主进程**里）。
 *
 * 为什么是「进程内点 view」而不是无障碍：同一个进程里能直接对宿主自己的视图发事件
 * （`performClick` / `dispatchTouchEvent` / `performLongClick`），宿主自己发请求、自己签名、
 * 自己带 `xsec_token` —— 不需要无障碍服务、不需要系统开关、不需要控件树 dump，也不需要
 * 我们去拼分享链（v2 拼链方案的死因：主页 `/note/user/posted` 响应基本不带
 * `share_info/xsec_token`，实测 145 个 id 只放过 1 个）。
 *
 * 编排（每篇）：主页卡片 → 点开 → 等详情页把分享链登记进 [XhsVideoSave.lastShareNoteId]
 * → 走既有分享链接管线（[XhsSavePivot.resolveByShareLink] + [xhsContentImageUrls]）静默直下
 * → 返回列表。
 *
 * **为什么不走长按面板**（2026-11-22 真机实证）：详情页长按出来的是宿主图片预览面板，
 * 里面的「保存」走图片自身保存而不经过 X5 挂的点击 lambda（modlog 零 X5 行），且面板
 * 不总在 Activity 的视图树里（转储只有详情页）——这是死路。分享链则稳定可得：
 * 每打开一篇，详情页的 `imagefeed` 请求响应就带 `share_info.link`（X4 旁路登记，实测 +1/篇）。
 * 因此去重（[XhsDownloadLedger]）、720p/5MB 过滤、文件命名、HEIC 转码全部原样复用。
 *
 * 失败即跳过：任何一步超时都只记一次失败并返回列表继续，不中断整轮（用户要求「随便一点情况
 * 都不能崩」）。连续 [IDLE_LIMIT] 篇点不开/没进展即收工。
 */
object XhsBatchDriver {

    private const val LOG_LINES = 10

    /** 已处理卡片身份的集合上限（超长主页兜底，清空只是重复查询，不漏）。 */
    private const val SEEN_LIMIT = 4000

    private const val JUDGE_PREFIX = "XhsHook·Batch"
    private const val TAG = "HonorHookXhsBatch"

    private const val NOTE_ID_WAIT_MS = 4000L
    private const val NOTE_RESOLVE_MS = 90_000L
    private const val LIST_BACK_MS = 5000L
    /** 每篇之间的随机节奏默认 2~4s（用户 2026-11-21 定案；UI 页可改，经 CONFIG 广播下发）。 */
    private const val PACE_MIN_MS = 2000L
    private const val PACE_MAX_MS = 4000L
    private const val IDLE_LIMIT = 3

    /**
     * 单篇最多尝试次数（用户 2026-11-22：「下载失败最多重试三次」）。
     * 单文件级重试另有 `XhsBatchSave.FETCH_MAX_ATTEMPTS = 3`；这里管「整篇重来」——
     * 解析超时、详情页没登记、某张图连败三次之后，整篇还有两次机会。
     */
    private const val ITEM_MAX_ATTEMPTS = 3

    /** 无进展看门狗上限（见 loop 里的 lastProgressAt）：2 分钟既不动卡片也不动列表 = 收工。 */
    private const val NO_PROGRESS_LIMIT_MS = 120_000L

    /** 同一列表页重绘等待轮次（700ms/轮 ≈ 7s），超时才收工。 */
    private const val LIST_REDRAW_WAIT = 10

    /** 整轮最多按几次返回（防一路把宿主退到桌面）。 */
    private const val MAX_BACK_PRESSES = 2

    /** 详情页开出后等分享链/裸 token 落表的上限（宿主命中缓存时不发请求，要给足时间）。 */
    private const val LINK_WAIT_MS = 5_000L

    /** 宿主 noteId 形态（24 位小写 hex），与 XhsSavePivot.HEX24 同口径。 */
    private val HEX24 = Regex("[0-9a-f]{24}")

    /** 当前这轮「扫到过卡片的那一页」，返回时用来判「同一列表页重绘」与「能不能 finish」。 */

    /**
     * 列表页的 Activity **类名**（2026-11-22 审计修正）：详情页的「相关推荐」也是 desc 以
     * 「笔记,」开头的卡片，若「有卡片就扫」会把**别人的笔记**当成该作者作品下进相册。
     * 锁定类名后只有列表页才处理卡片；用类名而非实例，宿主重建 Activity 也不会误判。
     */
    @Volatile private var listClassName: String? = null
    /** 卡片锚点：desc 前缀（同 XhsAutoEntry 的主页判据）。 */
    /**
     * 卡片判据（唯一口径，按钮注入与驱动共用）。
     * 宿主给卡片的无障碍描述是「<类型>,<标题…>,来自<作者>」：图文=「笔记,」，视频=「视频,」。
     * 只认「笔记,」时，整页都是视频笔记的作者主页连「这页有卡片」都判不出来（2026-11-22
     * 用户实测：主页头部全是视频 ⇒ 采集按钮不出现）。除类型前缀外再兜一条「含『来自』且够长」，
     * 免得宿主把视频卡换成别的类型词时又全线失判。
     */
    private val CARD_DESC_PREFIXES = arrayOf("笔记,", "视频,", "图文,")

    internal fun isCardDesc(desc: String?): Boolean {
        if (desc.isNullOrEmpty()) return false
        if (CARD_DESC_PREFIXES.any { desc.startsWith(it) }) return true
        return desc.length >= 12 && desc.contains("来自")
    }

    @Volatile private var running = false
    /** 轮次令牌：旧线程的收尾（finish）不能覆盖新一轮的运行时状态。 */
    private val runGen = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile private var stopRequested = false

    /** 已处理篇数（成功+跳过），给悬浮按钮做进度显示用。 */
    @Volatile private var processed = 0

    @Volatile private var paceMinMs = PACE_MIN_MS
    @Volatile private var paceMaxMs = PACE_MAX_MS

    /** 模块 UI 下发节奏范围（**分秒**，0.1s 为单位）；非法值（<1s 或反向）就地钳住，绝不把循环卡死。 */
    fun setPace(minDs: Int, maxDs: Int) {
        val lo = minDs.coerceIn(10, 600)
        val hi = maxDs.coerceIn(lo, 1200)
        paceMinMs = lo * 100L
        paceMaxMs = hi * 100L
    }

    private fun paceMs(): Long =
        if (paceMaxMs <= paceMinMs) paceMinMs else paceMinMs + (Math.random() * (paceMaxMs - paceMinMs)).toLong()

    /** 运行态变化回调（宿主进程内、任意线程调用）——悬浮按钮据此刷新「采集/取消」。 */
    @Volatile var stateListener: (() -> Unit)? = null

    /** 最近若干条过程消息（宿主内悬浮面板显示用，替代逐条 Toast——用户 2026-11-22「toast 不太行」）。 */
    private val logLines = ArrayDeque<String>()
    private val logLock = Any()

    /** 过程消息监听（主线程外调用，落地由 [XhsAutoEntry] 切主线程渲染）。 */
    @Volatile var uiListener: ((List<String>) -> Unit)? = null

    /** 入口侧（按钮/面板）写一行到同一份文件日志：面板到底注入没有，只能靠这里验。 */
    internal fun logEntry(msg: String) {
        val app = currentApp ?: return
        runCatching { vlog(app, msg) }   // 只进文件：注入自述不该占面板的 6 行
    }

    fun lastLines(): List<String> = synchronized(logLock) { logLines.toList() }

    private fun pushLine(msg: String) {
        val snap = synchronized(logLock) {
            logLines.addLast(msg)
            while (logLines.size > LOG_LINES) logLines.removeFirst()
            logLines.toList()
        }
        runCatching { uiListener?.invoke(snap) }
    }

    /** 最近一次驱动的应用上下文（跨进程查询状态时用来发广播）。 */
    @Volatile private var currentApp: Context? = null

    fun isRunning(): Boolean = running

    fun processedCount(): Int = processed

    /** 已收到取消、正在等当前这篇收尾（按钮显示「收尾」）。 */
    fun isStopping(): Boolean = stopRequested

    // ── 面板汇总统计（用户在 2026-11-22 指定要看的：处理/已存/待处理/失败/用时）──
    @Volatile private var okCount = 0
    @Volatile private var skippedCount = 0
    @Volatile private var failedCount = 0
    @Volatile private var pendingNow = 0

    /** 本轮已经「看见过」的卡片数（跨屏累计，按 noteId/描述去重）——服务端不给总篇数，这是能给的上界。 */
    @Volatile private var discoveredCount = 0
    @Volatile private var startedAt = 0L

    /** 收工时刻：收工后「用时」要冻住，不能继续涨（看起来像还在跑）。 */
    @Volatile private var endedAt = 0L

    /** 本轮 `XhsBatchSave` 的下载代次：旧线程看到代次过期立刻退出（取消后立刻重启不复活）。 */
    @Volatile private var dlGen = 0

    /** 本轮是否已把列表滚到顶：从中途位置开始采会永久漏掉上方笔记（2026-11-22 审计 #9）。 */
    @Volatile private var atTop = false

    /**
     * 本轮锁定的作者。`listClassName` 只说明「这是作者主页这个页面」，不说明「这是同一个作者」——
     * 所有作者主页共用同一个 Activity 类（2026-11-22 审计 #11）。来源见 [XhsAuthorTrack]。
     */
    @Volatile private var lockedAuthor: String? = null
    /** 本轮已「成功或跳过」的 noteId：同轮不重复处理同一篇（滚屏后卡片会以不同 top 再次出现）。 */
    private val finishedIds = HashSet<String>()
    /** collect 最近一次解析到的 noteId；循环据此登记 finishedIds。 */
    @Volatile private var lastNoteId: String? = null

    /** 累计落盘**文件**数（不是篇数）：用户 2026-11-22 要求面板同时给出「篇」与「资源」两个口径。 */
    @Volatile private var resourceCount = 0

    /** 当前正在处理的那篇有多少个资源（图片 + 视频）；空闲为 0。 */
    @Volatile private var currentNoteRes = 0

    /** collect 用：本篇预计有多少资源（面板「当前文章 N 个资源」）。 */
    private fun noteResources(total: Int) { currentNoteRes = total }

    /** collect 用：累计真实落盘文件数。 */
    private fun addResources(n: Int) { if (n > 0) resourceCount += n }

    private fun syncStats(
        ok: Int,
        skipped: Int,
        failed: Int,
        pending: Int,
        discovered: Int = 0,
        resources: Int = resourceCount,
        noteRes: Int = currentNoteRes,
    ) {
        resourceCount = resources
        currentNoteRes = noteRes
        okCount = ok
        skippedCount = skipped
        failedCount = failed
        pendingNow = pending
        if (discovered > discoveredCount) discoveredCount = discovered
        processed = ok + skipped + failed
    }

    /** 面板顶栏文本：两行（窄屏一排放不下五项）。 */
    /** 面板用的结构化快照：[已处理篇, 累计资源, 失败, 跳过, 当前文章资源, 本屏待处理, 用时秒]。 */
    fun stats(): LongArray = longArrayOf(
        okCount.toLong(), resourceCount.toLong(), failedCount.toLong(), skippedCount.toLong(),
        currentNoteRes.toLong(), pendingNow.toLong(), elapsedSec(),
    )

    fun stateLabel(): String = when {
        !running -> if (endedAt > 0) "已结束" else "空闲"
        stopRequested -> "收尾中"
        else -> "采集中"
    }

    fun isStoppingNow(): Boolean = stopRequested

    private fun elapsedSec(): Long {
        val endAt = if (!running && endedAt > 0) endedAt else System.currentTimeMillis()
        return if (startedAt == 0L) 0L else (endAt - startedAt) / 1000
    }

    fun header(): String {
        val secs = elapsedSec()
        val ss = (secs % 60).toString().padStart(2, '0')
        val state = when {
            !running -> "已结束"
            stopRequested -> "收尾中"
            else -> "采集中"
        }
        // 用户 2026-11-22 指定的口径：处理 N 篇 / 累计下载 N 个资源 / 当前文章 N 个资源
        return "$state · 已处理 $okCount 篇 · 累计下载 $resourceCount 个 · 失败 $failedCount\n" +
            "当前文章 $currentNoteRes 个资源 · 本屏待处理 $pendingNow · 跳过 $skippedCount · " +
            "用时 ${(secs / 60).toString().padStart(2, '0')}:$ss"
    }

    /** 回答模块面板的状态查询（跨进程，面板进页时问一次）。 */
    fun publishState() {
        val app = currentApp ?: return
        notifyModule(app, AutoActions.STATE, running, if (running) "运行中" else "待机")
        if (running) notifyModule(app, AutoActions.PROGRESS, true, "运行中", processed, 0, 0, 0)
    }

    fun start(module: XposedModule, ctx: Context) {
        if (running) return
        currentApp = ctx.applicationContext
        // 起跑页校验（2026-11-22 根因）：本功能只能在**作者主页**起跑。以前直接把「当前这页」
        // 的类名锁成列表页 ⇒ 用户在详情页（或残留的悬浮按钮上）点采集时，列表页 = NoteDetailActivity，
        // 于是详情页底部的「相关推荐」被别人当成目标作者的笔记：反复采同一批、越滚越乱、
        // 还会把从没打开过的篇当已下载（真机日志 `启动参数：… 前台=NoteDetailActivity`）。
        // 判据与按钮注入共用 XhsAutoEntry.isProfilePage（卡片 + 粉丝/获赞资料区）。
        val front = XhsBatchSave.activityOrNull()
        val frontDecor = runCatching { front?.window?.decorView }.getOrNull()
        if (frontDecor == null || !runCatching { XhsAutoEntry.isProfilePage(frontDecor) }.getOrDefault(false)) {
            val where = runCatching { front?.javaClass?.simpleName }.getOrNull() ?: "无前台页面"
            xlog(ctx.applicationContext, "没有开始：当前页不是作者主页（前台=$where）")
            toast(ctx, "请先打开作者主页再点采集（当前停在$where）")
            return
        }
        // 换代次（不是在旧代次上「清取消」）：上一轮还在网络读里的线程会看到自己过期并退出
        dlGen = XhsBatchSave.beginRun()
        atTop = false
        // 点「采集」这一刻的前台作者就是本轮目标；拿不到就在首篇解析结果里学习（collect）
        lockedAuthor = XhsAuthorTrack.authorId
        endedAt = 0L
        // 面板消息清空：不清的话第二轮面板里混着上一轮的「已保存/失败」（审计 #15）
        synchronized(logLock) { logLines.clear() }
        running = true
        stopRequested = false
        processed = 0
        syncStats(0, 0, 0, 0, 0, resources = 0, noteRes = 0)
        discoveredCount = 0
        startedAt = System.currentTimeMillis()
        // 列表页类名在 start 就锁成「当前这页」：等到第一次扫到卡片才学，会在首屏重绘/切 tab 的
        // 空窗里对着作者主页按返回，退出后把首页 feed 学成列表页 → 把别人的笔记当目标作品（审计 P0）。
        listClassName = runCatching { front?.javaClass?.name }.getOrNull()
        val gen = runGen.incrementAndGet()
        val app = ctx.applicationContext
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 采集开始（逐篇点开取分享链直下）")
        xlog(app, "采集开始")
        vlog(app, "启动参数：节奏 ${paceMinMs}~${paceMaxMs}ms 线程=${Thread.currentThread().name} " +
            "前台=${runCatching { XhsBatchSave.activityOrNull()?.javaClass?.simpleName }.getOrNull()}")
        // 驱动线程是裸 Thread：任何逃出去的 Throwable 会走默认 UncaughtExceptionHandler
        // **直接杀宿主进程**（且 running 卡在 true，按钮永远显示「取消」）。双重兜底（2026-11-22 审计 #1）。
        val worker = Thread({
            runCatching { loop(module, app, gen) }.onFailure {
                running = false
                stopRequested = true
                runCatching { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 驱动线程异常: ${it.javaClass.simpleName}: ${it.message}") }
            }
        }, "HonorHook-xhs-collect")
        worker.setUncaughtExceptionHandler { _, e ->
            running = false
            stopRequested = true
            runCatching { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 驱动线程未捕获: ${e.javaClass.simpleName}") }
        }
        runCatching { worker.start() }.onFailure {
            running = false
            stopRequested = true
            runCatching { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 驱动线程启动失败: ${it.message}") }
            stateListener?.invoke()
            return
        }
        stateListener?.invoke()
        toast(ctx, "开始采集：逐篇打开下载，可随时点取消")
        notifyModule(app, AutoActions.STATE, true, "开始采集…")
    }

    fun stop(module: XposedModule?, ctx: Context?) {
        if (!running) return
        if (stopRequested) {
            // 用户会连点：只认第一次，但别静默——正在收尾时点按钮没反应最像「坏了」
            ctx?.let { toast(it, "正在收尾，请稍候…") }
            return
        }
        stopRequested = true
        // 立即回写状态：下载/解析可能还要几秒到几十秒，按钮不刷新用户会以为没点中而连点（审计 #21）
        stateListener?.invoke()
        // 正在下载的那篇立刻中断（不然要等它自己下完，最坏几十秒）
        runCatching { XhsBatchSave.requestCancel() }
        ctx?.let { notifyModule(it, AutoActions.STATE, true, "已取消，正在收尾…") }
        module?.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 收到取消，正在收尾")
        ctx?.let { xlog(it, "收到取消", toast = true) }
    }

    /**
     * 主循环：逐张点开 → 取分享链 → 直下 → 返回 → 下一张；本屏没有未处理的卡再滚一屏。
     *
     * 2026-11-22 用户反馈「翻页的时候，会漏掉几篇」——根因是旧的**屏内序号游标** `index`：
     * 每次从详情页返回，宿主会重排/复用卡片 View，序号指到的已经不是原来那一张，
     * 于是夹在中间的两张谁都没处理就被下一次滚动带走了。现在改成**按卡片身份去重**
     * （noteId 优先、退化用 contentDescription）：只要这一屏还有没见过的卡，就一定要处理它，
     * 不看序号，所以重排、复用、返回后轻微位移都不会漏。
     */
    /**
     * 起跑页归一（2026-11-22 真机根因）：用户点「采集」时常常停在**详情页**上——那是盖在作者主页
     * 之上的浮层，decor 里既有主页的卡片、也有「粉丝」资料区，`isProfilePage` 会判成主页（判据没错），
     * 但把 `listClassName` 锁成详情页之后，后续的「翻页 / 收工」判断就都建立在错的前提上：
     * 一篇**没有相关推荐**的详情页会把卡片数变成 0，于是 10 轮后宣布「采集完成」，
     * 实际主页上还有几十篇没采（2026-11-22 22:22 真机：失败 24 次后提前收工）。
     *
     * 这里在进入主循环前把页面退回真正的作者主页：**类名含 Detail/Feed 或没有资料区标记**就按返回，
     * 最多 [MAX_BACK_PRESSES] 次；成功后把列表页类名重新锁到当前页。
     */
    private fun normalizeStartPage(module: XposedModule, ctx: Context) {
        repeat(MAX_BACK_PRESSES + 1) {
            if (stopRequested) return
            val act = XhsBatchSave.activityOrNull() ?: return
            val cls = act.javaClass.name
            val stats = onMainOrTimeout { act.window?.decorView?.let { XhsAutoEntry.hasProfileMarkers(it) } }
                .second ?: false
            val detailLike = cls.contains("Detail", ignoreCase = true)
            if (!detailLike && stats) {
                if (listClassName != cls) {
                    listClassName = cls
                    xlog(ctx, "列表页锁定：${act.javaClass.simpleName}")
                }
                return
            }
            if (isAppRoot(act)) {
                xlog(ctx, "起跑页是首页（${act.javaClass.simpleName}），请先打开作者主页再点采集")
                return
            }
            xlog(ctx, "起跑页不是作者主页（${act.javaClass.simpleName} 详情=${detailLike} 资料区=${stats}），退一页")
            pressBack(act)
            Thread.sleep(900)
        }
    }

    private fun loop(module: XposedModule, ctx: Context, gen: Int) {
        var ok = 0
        var skipped = 0
        var failed = 0
        var handled = 0
        // 滚动「没动」的独立计数：不能复用 idle——它在每轮有卡片时都被清零，++
        // 永远攒不到 IDLE_LIMIT，手势失效时就会**每 1.2s 空滚一次、永不收工**（2026-11-22 审计 #4）。
        var scrollIdle = 0
        var redrawWaits = 0
        var offList = 0
        var cardsTotal = 0
        // 看门狗（2026-11-22）：任何路径（主线程卡死、宿主改版、视图树判据失效）都可能让
        // 循环既不推进也不退出，用户看到的是「一直采集中」。无进展超时即收工并说清原因。
        var lastProgressAt = System.currentTimeMillis()
        // 本轮已处理过的卡片身份。noteId 拿不到时退化成 contentDescription（「笔记, 标题…」）。
        // 上限兜底：超长主页会把集合撑大，超过就整体清空（清空只会导致重复去重查询，不会漏）。
        val seen = HashSet<String>()
        // 「已发现」= 本轮扫到过的不同卡片（不是已处理数）：否则首屏 30 张时面板显示「已发现 0」
        val discoveredKeys = HashSet<String>()
        try {
            normalizeStartPage(module, ctx)
            while (!stopRequested) {
                // 看门狗必须在循环最外层：act==null（用户切走）与 !scanned（主线程卡）这两条
                // continue 原来永不超时，表现是「一直采集中、零日志」（2026-11-22 审计 P1）。
                if (System.currentTimeMillis() - lastProgressAt > NO_PROGRESS_LIMIT_MS) {
                    xlog(ctx, "连续 ${NO_PROGRESS_LIMIT_MS / 1000} 秒没有进展，收工")
                    break
                }
                // 换作者 = 立刻收工：下面的卡片已经是别人的笔记，继续跑就是「把 B 的作品存成 A 的」
                val latestAuthor = XhsAuthorTrack.authorId
                if (lockedAuthor == null) {
                    lockedAuthor = latestAuthor
                } else if (latestAuthor != null && latestAuthor != lockedAuthor) {
                    xlog(ctx, "作者变了（$lockedAuthor → $latestAuthor），停止采集，避免混入别人的笔记")
                    break
                }
                // 每轮都重新取前台 Activity：详情页/列表页可能是不同实例，抱死一个会对着
                // 已销毁的视图树空转。
                val act = XhsBatchSave.activityOrNull()
                if (act == null) {
                    Thread.sleep(500)
                    continue
                }
                val (scanned, cardList) = onMainOrTimeout {
                    // key = 描述 + 屏内 top：不同笔记可能描述完全相同（同标题系列/无标题），
                    // 只按描述去重会把第二篇永远判成「已处理」（2026-11-22 审计 P2）
                    // 身份不能用「描述 + top」：滚过一屏同一篇 top 会变 ⇒ 已处理的当新篇重做（重复检测同一篇），
                    // 反过来也会把新篇当已处理（漏篇）。描述里已含标题/作者，本身就能区分；
                    // 只有描述太短（占位文案）时才补 top 兜底，避免所有卡撞成同一个 key。
                    findCards(act).map { v ->
                        val d = v.contentDescription?.toString().orEmpty()
                        v to (if (d.length >= 8) d else d + "#" + v.top())
                    }
                }
                if (!scanned) {
                    // 主线程卡住：视图树这一刻不可信，等它缓过来，绝不据此按返回（审计 #8）
                    Thread.sleep(500)
                    continue
                }
                val cards = cardList.orEmpty()
                cardsTotal = maxOf(cardsTotal, cards.size)
                if (cards.isNotEmpty()) {
                    val cls = act.javaClass.name
                    val locked = listClassName
                    if (locked == null) {
                        listClassName = cls
                    } else if (locked != cls) {
                        // 非列表页的卡片（详情页相关推荐等）：不是目标作者的作品，绝不下、只退回列表
                        offList++
                        xlog(ctx, "第 $offList 次返回：${act.javaClass.simpleName} 有卡片但不是列表页")
                        if (offList > MAX_BACK_PRESSES) break
                        pressBack(act)
                        Thread.sleep(900)
                        continue
                    }
                }
                if (cards.isEmpty()) {
                    if (listClassName == null) {
                        // 还没锁定列表页：这页可能是过场/占位，按返回只会把作者主页关掉（审计 P0）
                        Thread.sleep(700)
                        continue
                    }
                    // 扫到过卡片的同一页现在空 = 宿主重绘/占位中，**绝不能按返回**（2026-11-22 真机：
                    // 连按 3 次把小红书一路退到桌面，整轮就这么收工了）。等它画出来。
                    // 「同类的页」不等于「作者主页」：小红书详情页是盖在主页上的浮层，同一个
                    // Activity 里可能一张卡都没有（2026-11-22 真机：这样会被当成「主页没卡片」，
                    // 10 轮后提前宣布采集完成）。所以这里还要看资料区标记（粉丝 / 获赞与收藏）。
                    val stillGrid = act.javaClass.name == listClassName &&
                        onMainOrTimeout { act.window?.decorView?.let { XhsAutoEntry.hasProfileMarkers(it) } }
                            .second != false
                    if (stillGrid) {
                        if (++redrawWaits >= LIST_REDRAW_WAIT) {
                            xlog(ctx, "作者主页一直没卡片，收工")
                            break
                        }
                        Thread.sleep(700)
                        continue
                    }
                    // 确实离开了列表（停在详情/弹层）：整轮只允许按两次返回，按完就等
                    offList++
                    xlog(ctx, "第 $offList 次返回：${act.javaClass.simpleName} 无卡片")
                    if (offList == 1) dumpViews(ctx, "返回后无卡片", act)
                    if (offList > MAX_BACK_PRESSES) break
                    pressBack(act)
                    Thread.sleep(900)
                    continue
                }
                offList = 0
                redrawWaits = 0
                scrollIdle = 0
                // 用户可能已经翻到半截才点「采集」：不先回顶，上方笔记永远采不到，
                // 面板「已发现」也只从当前位置算（2026-11-22 审计 #9）。
                if (!atTop && act.javaClass.name == listClassName) {
                    var guard = 0
                    var lastTop = cards.first().first.top()
                    var lastSize = cards.size
                    while (guard++ < 30 && !stopRequested) {
                        swipeDown(act)
                        Thread.sleep(700)
                        val cur = onMain { findCards(act) }.orEmpty()
                        val top = cur.firstOrNull()?.top() ?: -1
                        if (top == lastTop && cur.size == lastSize) break   // 到顶了（滚了没变化）
                        lastTop = top
                        lastSize = cur.size
                    }
                    atTop = true
                    xlog(ctx, "已滚到列表顶部，从头开始")
                    continue
                }
                if (seen.size > SEEN_LIMIT) seen.clear()

                cards.forEach { discoveredKeys.add(it.second) }
                // 只处理**真正在屏幕里**的卡片：列表下方的卡片 isShown=true 但 top 已经超过屏高
                // （真机日志 `bounds=Rect(17, 3323 - 628, 3392)`，屏高 2800），performClick 打在屏外
                // 等于什么都没点 → 详情页还停在上篇 → 读到上篇 noteId → 「没打开过的篇被判已下载」。
                // 屏外的留着不处理，滚一屏后它们自然进入视口。
                val viewH = runCatching { act.window?.decorView?.height ?: 0 }.getOrDefault(0)
                val limit = if (viewH > 0) viewH - 120 else Int.MAX_VALUE
                val onScreen = cards.filter { it.first.top() < limit }
                val pending = onScreen.filter { it.second !in seen }
                val keysPreview = cards.take(3).joinToString("|") { it.second.take(14) }
                vlog(ctx, "扫描[${act.javaClass.simpleName}] 卡=${cards.size} 未处理=${pending.size} 已见=${seen.size} " +
                    "首top=${cards.first().first.top()} 键=$keysPreview")
                syncStats(ok, skipped, failed, pending.size, discoveredKeys.size)
                if (pending.isEmpty()) {
                    // 这一屏没有新卡了：滚一屏，滚完确认列表真的动了，否则「一直滚不动」会空转
                    val beforeTop = cards.first().first.top()
                    val beforeSize = cards.size
                    xlog(ctx, "本屏 ${cards.size} 张都处理过，向上滚一屏")
                    swipeUp(act)
                    // 分页慢：固定 sleep(1200) 只判一次，网络稍慢服务端没追加就被算「滚不动」，
                    // 连续 3 次约 3.6s 就收工，后半页全漏（2026-11-22 审计 #10）。
                    // 改成递增等待（1.2/2/3/4s）时每次都可能出现新卡，等满仍没动才算一次 idle。
                    var after = emptyList<View>()
                    var moved = false
                    // 每 400ms 探一次，最多 4s：翻页通常 1~2s 就回来，等满 10 秒纯浪费
                    for (round in 0 until 10) {
                        if (stopRequested) break
                        Thread.sleep(400)
                        after = onMain { findCards(act) }.orEmpty()
                        if (after.size != beforeSize || (after.firstOrNull()?.top() ?: -1) != beforeTop) {
                            moved = true
                            break
                        }
                    }
                    vlog(ctx, "滚动后：${beforeSize}张/top=$beforeTop → ${after.size}张/top=${after.firstOrNull()?.top() ?: -1}")
                    if (moved) {
                        scrollIdle = 0
                        lastProgressAt = System.currentTimeMillis()
                    } else if (++scrollIdle >= IDLE_LIMIT) {
                        xlog(ctx, "列表滚不动了，收工")
                        break
                    }
                    continue
                }

                lastProgressAt = System.currentTimeMillis()
                val (card, key) = pending.first()
                // 先标记再处理：处理失败也不该在同一屏反复点它（失败的账已经记在 failed 里）
                seen.add(key)
                handled++
                // 反射只对「马上要处理的那张」做；整屏几十张全反射是纯主线程浪费（审计 P2）
                val cardNoteId = runCatching { onMain { noteIdOfCard(card) } }.getOrNull()
                if (cardNoteId != null) seen.add(cardNoteId)
                val known = cardNoteId
                if (known != null && XhsVideoSave.shareLinkOf(known) == null) {
                    val tok = runCatching { onMain { tokenOfCard(card, known) } }.getOrNull()
                    if (!tok.isNullOrBlank()) {
                        XhsVideoSave.registerToken(known, tok)
                        vlog(ctx, "卡片 token 登记 noteId=$known token=${tok.take(6)}…")
                    }
                }
                // 整条 share link 已经在表里（homefeed/上一屏响应登记过）⇒ 根本不用点开这篇：
                // 零详情页、零等待，直接解析直下。这是最快也最稳的一条路（2026-11-22）。
                if (known != null && XhsVideoSave.shareLinkOf(known) != null) {
                    val direct = collectWithRetry(module, ctx, prev = null, known = known)
                    if (direct != SaveOutcome.FAIL) lastNoteId?.let { finishedIds.add(it) }
                    when (direct) {
                        SaveOutcome.OK -> ok++
                        SaveOutcome.SKIPPED -> skipped++
                        SaveOutcome.FAIL -> failed++
                    }
                    val dt = when (direct) {
                        SaveOutcome.OK -> "已保存 1 篇（免点开）"
                        SaveOutcome.SKIPPED -> "已下载过，跳过"
                        SaveOutcome.FAIL -> "这篇没保存成功，跳过"
                    }
                    xlog(ctx, dt)
                    syncStats(ok, skipped, failed, pending.size - 1, discoveredKeys.size, resources = resourceCount)
                    stateListener?.invoke()
                    notifyModule(ctx, AutoActions.PROGRESS, true, dt, ok, skipped, failed, cardsTotal)
                    sleepPace()
                    continue
                }
                // 台账先查：已知这篇下过就直接跳过（不点开、不等网络），否则每篇白开一次详情页
                // 还要等 8~23 秒，且反射失败时会记成「失败」而不是「跳过」（2026-11-22 审计 P1）
                if (known != null && runCatching { XhsDownloadLedger.downloaded(ctx, known).first }.getOrDefault(0) > 0) {
                    skipped++
                    syncStats(ok, skipped, failed, pending.size - 1, discoveredKeys.size, resources = resourceCount)
                    stateListener?.invoke()
                    notifyModule(ctx, AutoActions.PROGRESS, true, "已下载过，跳过", ok, skipped, failed, cardsTotal)
                    xlog(ctx, "已下载过，跳过 noteId=$known（免点开）")
                    sleepPace()
                    continue
                }
                xlog(ctx, "点开第 $handled 张（本屏剩 ${pending.size} 张，noteId=${known ?: "待取"}）")
                vlog(ctx, "卡片详情：key=${key.take(30)} bounds=${runCatching { card.let { v -> val r = android.graphics.Rect(); v.getGlobalVisibleRect(r); r.toString() } }.getOrNull()} 可点=${card.isClickable} 可见=${card.isShown}")
                val prev = XhsVideoSave.lastShareNoteId()
                if (onMain { clickView(card) } != true) {
                    failed++
                    syncStats(ok, skipped, failed, pending.size - 1, discoveredKeys.size, resources = resourceCount)
                    stateListener?.invoke()
                    notifyModule(ctx, AutoActions.PROGRESS, true, "卡片点不动", ok, skipped, failed, cardsTotal)
                    xlog(ctx, "卡片点不动（本屏剩 ${pending.size - 1} 张）")
                    sleepPace()   // 原来 sleep(paceMs())：节奏配到 120s 时取消要等两分钟才生效
                    continue
                }
                val saved = collectWithRetry(module, ctx, prev, known)
                if (saved != SaveOutcome.FAIL) lastNoteId?.let { finishedIds.add(it) }
                when (saved) {
                    SaveOutcome.OK -> ok++
                    SaveOutcome.SKIPPED -> skipped++
                    SaveOutcome.FAIL -> failed++
                }
                val text = when (saved) {
                    SaveOutcome.OK -> "已保存 1 篇"
                    SaveOutcome.SKIPPED -> "已下载过，跳过"
                    SaveOutcome.FAIL -> "这篇没保存成功，跳过"
                }
                module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX $text")
                xlog(ctx, "$text（本屏剩 ${pending.size - 1} 张）")
                syncStats(ok, skipped, failed, pending.size - 1, discoveredKeys.size)
                stateListener?.invoke()
                notifyModule(ctx, AutoActions.PROGRESS, true, "$text（成功 $ok · 跳过 $skipped · 失败 $failed）", ok, skipped, failed, cardsTotal)
                backToList()
                sleepPace()
            }
            if (cardsTotal == 0) {
                dumpViews(ctx, "一张卡片都没扫到", XhsBatchSave.activityOrNull())
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 采集中断: ${t.javaClass.simpleName}: ${t.message}")
            xlog(ctx, "采集中断 ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            finish(module, ctx, ok, skipped, failed, null, gen)
        }
    }

    private enum class SaveOutcome { OK, SKIPPED, FAIL }

    /** 24 位 hex（小红书 id 形态）。 */
    private val hexId = Regex("^[0-9a-f]{24}$")

    /** 每个 Activity 类上「能问出 noteId」的方法缓存（name=null 表示该类没有）。 */
    private val idCandidates = HashMap<String, List<java.lang.reflect.Method>>()

    /**
     * 直接从**当前详情页 Activity** 问 noteId（2026-11-22 用户实测：缓存命中的笔记详情页不发
     * imagefeed，旁路登记拿不到 id，整轮卡在「详情页没拿到 noteId」）。
     *
     * 静态实证（`.buildtmp/xhs9533-c8/sources/com/xingin/matrix/notedetail/NoteDetailActivity.java:651`）：
     * `public String s1()`（实现 `dx9.a`）返回 `((b1) controller).h2().J.getId()`，方法名带混淆、
     * 每个版本都会变 ⇒ 不写死名字，改为「该类 public 无参且返回 String 的方法，返回值像 24 位 hex 就用它」，
     * 结果按类缓存。兜底再看 Intent：同文件 :661 用 `getIntent().getStringExtra("id")` 兜底。
     *
     * 只在当前活动 Activity 上试；不在详情页（列表页）时这些方法不返回 hex，天然安全。
     */
    /**
     * 等到本篇 noteId：每 200ms 探一次，**连续两次读到同一个值**才认。
     *
     * 为什么要求稳定：点开卡片的瞬间详情页还是上一篇（controller 尚未换），Activity 上读到的是上一页的 id；
     * 认了它就会把「没打开过的篇」当成「已下载过」（2026-11-22 用户真机：一堆从没打开的篇直接跳过）。
     * 卡片反射出来的 id（known）非空时只接受同一篇，避免串到邻篇。
     */
    private fun awaitNoteId(ctx: Context, prev: String?, known: String?): String? {
        val deadline = System.currentTimeMillis() + NOTE_ID_WAIT_MS
        var stable: String? = null
        while (System.currentTimeMillis() < deadline && !stopRequested) {
            val act = XhsBatchSave.activityOrNull()
            val fromAct = if (act != null && act.javaClass.simpleName.contains("Detail")) {
                runCatching { noteIdFromActivity(act) }.getOrNull()
            } else null
            val cand = fromAct?.takeIf { it != prev && (known == null || it == known) }
            if (cand != null) {
                if (stable == cand) return cand
                stable = cand
            } else {
                stable = null
            }
            val reg = XhsVideoSave.lastShareNoteId()?.takeIf { it != prev && (known == null || it == known) }
            if (reg != null) return reg
            Thread.sleep(200)
        }
        return null
    }

    private fun noteIdFromActivity(activity: Activity): String? {
        // **先看 Intent**：详情页的 `getIntent().getStringExtra("id")` 是它被创建时那一篇的 id，权威且不会串；
        // Activity 上的方法可能是 controller/adapter 的残留值（2026-11-22 真机：不同卡片拿到同一个 id，
        // 导致从没打开过的篇也被判「已下载过」）。
        val intent = runCatching { activity.intent }.getOrNull()
        if (intent != null) {
            runCatching { intent.getStringExtra("id") }.getOrNull()
                ?.takeIf { hexId.matches(it) }?.let { return it }
            val extras = runCatching { intent.extras }.getOrNull()
            if (extras != null) {
                for (key in extras.keySet()) {
                    val v = runCatching { extras.get(key) as? String }.getOrNull() ?: continue
                    if (hexId.matches(v)) return v
                }
            }
        }
        val cls = activity.javaClass
        // 早先只在第一次扫到 id 后缓存那个方法、扫不到就缓存 null —— 一旦首轮 controller 还没就绪
        // 就永久认定「这类 Activity 没有 id 方法」，后面 8 秒里一次都不再试（真机日志：「方法扫描缓存=无」）。
        // 现在缓存的是**候选方法列表**，每轮都 invoke 一遍，直到拿到 24 位串。
        val cands = idCandidates.getOrPut(cls.name) {
            runCatching {
                cls.methods.filter {
                    it.parameterCount == 0 && it.returnType == String::class.java &&
                        it.declaringClass != android.app.Activity::class.java
                }.take(80)
            }.getOrDefault(emptyList())
        }
        for (m in cands) {
            val v = runCatching { m.invoke(activity) as? String }.getOrNull() ?: continue
            if (hexId.matches(v)) return v
        }
        return null
    }

    /**
     * 整篇级重试：失败不是终点，最多 [ITEM_MAX_ATTEMPTS] 次（取消时不重试）。
     * 「刷新即失败」多半是解析超时/凭据还没登记，隔一拍再试常常就好了。
     */
    private fun collectWithRetry(module: XposedModule, ctx: Context, prev: String?, known: String?): SaveOutcome {
        var outcome = runCatching { collect(module, ctx, prev, known) }.getOrDefault(SaveOutcome.FAIL)
        var attempt = 1
        while (outcome == SaveOutcome.FAIL && attempt < ITEM_MAX_ATTEMPTS && !stopRequested) {
            attempt++
            xlog(ctx, "本篇第 $attempt/$ITEM_MAX_ATTEMPTS 次重试", toast = false)
            sleepPace()
            outcome = runCatching { collect(module, ctx, prev, known) }.getOrDefault(SaveOutcome.FAIL)
        }
        return outcome
    }

    /**
     * 点开之后：认 noteId → 查去重 → 解析图片 → 静默直下。
     *
     * noteId 的来源是详情页自己发出的 `imagefeed` 响应（X4 旁路登记，实测每篇 +1），
     * 所以「上一篇的值」必须由调用方传进来比对，否则会把上一篇的 noteId 当成这一篇。
     */
    private fun collect(module: XposedModule, ctx: Context, prev: String?, known: String? = null): SaveOutcome {
        // 优先级：详情页自己登记的 > 卡片反射的。反射可能拿到同一 bean 里的别的 24hex（作者 id 等），
        // 一旦错位就会「把 A 的图存成 B 的 noteId」，所以只作兜底（2026-11-22 审计）。
        // 卡片反射出来的 id 已经带凭据（share_info 或 xsec_token）时不必等：等也是等同一篇，
        // 白等 8 秒换每篇多一次卡顿（2026-11-22 审计）。凭据缺失才等详情页登记。
        val knownReady = known != null &&
            (XhsVideoSave.shareLinkOf(known) != null || XhsVideoSave.tokenOf(known) != null)
        // 有卡片反射出来的 id 时，详情页登记的必须是**同一篇**才算命中：详情页的评论/推荐响应也会
        // 改最近登记值，取错了就「把 A 的图存成 B 的 noteId」（2026-11-22 审计 P0）。
        // 详情页自己知道本篇 id（方法/Intent）⇒ 每 200ms 问一次，直到拿到的或超时：
        // 早先只在点完卡片那一下问一次，那会儿详情页还没 resume（controller 还没挂上），
        // 之后就只轮询分享链登记，缓存命中的笔记永远等不到 ⇒ 「详情页没拿到 noteId」（2026-11-22 真机 17:47）。
        // 只问类名像 Detail 的页面：列表页上某些方法也可能返回 24 位串（作者 id），认错就串篇。
        val registered = if (knownReady) null else awaitNoteId(ctx, prev, known)
        val noteId = registered ?: known
        lastNoteId = noteId
        if (noteId != null && noteId in finishedIds) {
            xlog(ctx, "本篇本轮已处理过，跳过 noteId=$noteId")
            return SaveOutcome.SKIPPED
        }
        if (noteId != null && registered != null) {
            vlog(ctx, "noteId 来源=详情页/分享链轮询 noteId=$registered")
        }
        if (!knownReady && registered == null && known != null) {
            vlog(ctx, "详情页没登记，用卡片反射的 noteId=$known 兜底")
        }
        if (noteId == null) {
            val act = XhsBatchSave.activityOrNull()
            xlog(
                ctx,
                "详情页没拿到 noteId（活动方法/Intent/分享链登记都没给出 24 位 id）" +
                    " 前台=${act?.javaClass?.simpleName} 候选方法=${idCandidates[act?.javaClass?.name]?.size ?: 0}"
            )
            dumpViews(ctx, "详情页", act)
            return SaveOutcome.FAIL
        }
        // noteId 有了不代表凭据有了：宿主命中本地缓存时不发 imagefeed，分享链永远不会落表。
        // 等它一会儿（15s），仍然只有 none 才判失败——这样「打开快、但链是缓存的」不会误判。
        if (XhsVideoSave.shareLinkOf(noteId) == null && XhsVideoSave.tokenOf(noteId) == null) {
            await(LINK_WAIT_MS) {
                if (XhsVideoSave.shareLinkOf(noteId) != null || XhsVideoSave.tokenOf(noteId) != null) true else null
            }
        }
        // 只认台账里的「整篇零失败」记录。早先还看相册前缀：只要有一张旧文件就整篇跳过，
        // 于是早前中断留下的半篇永远停在缺图状态（2026-11-22 用户「为啥跳过的这么多」+ 审计 #5）。
        // 相册现状留到解析之后按**序号**核对，缺哪张补哪张。
        // 台账只说明「曾经记过整篇成功」，不说明文件还在（用户删图/系统清理/老版本的宽松记录都会让它失真）。
        // 现在按台账里的**期望张数**核对相册：够数才跳过，不够就往下走去补齐缺的那几张
        // （用户 2026-11-22：「还是大范围的提示重复下载」，就是老行一律当完整）。
        val ledgerCount = runCatching { XhsDownloadLedger.ledgerCount(ctx, noteId) }.getOrDefault(0)
        if (ledgerCount > 0) {
            val resolver = ctx.contentResolver
            val haveIdx = runCatching { XhsDownloadLedger.existingImageIndexes(resolver, noteId) }
                .getOrDefault(emptySet())
            val haveVideo = runCatching { XhsDownloadLedger.hasVideoFile(resolver, noteId) }.getOrDefault(false)
            val haveTotal = haveIdx.size + if (haveVideo) 1 else 0
            if (haveTotal >= ledgerCount) {
                xlog(ctx, "台账记过整篇且相册齐全（$haveTotal/$ledgerCount），跳过 noteId=$noteId")
                return SaveOutcome.SKIPPED
            }
            vlog(ctx, "台账记过但相册只有 $haveTotal/$ledgerCount，继续补齐 noteId=$noteId")
        }
        val activity = XhsBatchSave.activityOrNull()
        if (activity == null) {
            xlog(ctx, "落盘前没有可用的 Activity（宿主退到后台？）noteId=$noteId")
            return SaveOutcome.FAIL
        }
        val linkKind = when {
            XhsVideoSave.shareLinkOf(noteId) != null -> "share_info"
            XhsVideoSave.tokenOf(noteId) != null -> "token合成"
            else -> "无凭据"
        }
        xlog(ctx, "noteId=$noteId 链=$linkKind")
        if (stopRequested) {
            xlog(ctx, "已取消，这一篇不再解析 noteId=$noteId")
            return SaveOutcome.FAIL
        }
        // webViewFallback = 分享页需要 JS 渲染（RequiresWebView）时由 XhsSavePivot 内部用内置
        // WebView 再取一次；手点保存走的是同一个函数、同一个兜底（2026-11-22 用户要求代码共享）。
        val note = try {
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(NOTE_RESOLVE_MS) {
                    XhsSavePivot.resolveByShareLink(noteId, module, fromBatch = true, webViewFallback = true)
                }
            }
        } catch (t: kotlinx.coroutines.TimeoutCancellationException) {
            // 超时 ≠ 这篇没图：网络/上游解析挂住时下一轮还可能成功，必须和「解析不出」分开记
            xlog(ctx, "解析超时 ${NOTE_RESOLVE_MS / 1000}s noteId=$noteId")
            return SaveOutcome.FAIL
        } catch (t: Throwable) {
            xlog(ctx, "解析异常 ${t.javaClass.simpleName}: ${t.message} noteId=$noteId")
            return SaveOutcome.FAIL
        }
        if (note == null) {
            xlog(ctx, "解析不出图片 noteId=$noteId（链=$linkKind 原因=${XhsSavePivot.lastFailure() ?: "未知"}）")
            return SaveOutcome.FAIL
        }
        // 第二道作者校验：就算页面树里混进了别人的卡片，解析结果里的 authorId 也不会撒谎。
        // 首篇解析成功后顺带学习锁定值（作者主页列表接口被缓存、没发请求时的兜底）。
        val noteAuthor = note.authorId
        if (lockedAuthor == null) {
            lockedAuthor = noteAuthor
        } else if (noteAuthor != null && noteAuthor != lockedAuthor) {
            xlog(ctx, "noteId=$noteId 属于作者 $noteAuthor，不是本轮锁定的 $lockedAuthor，跳过")
            return SaveOutcome.SKIPPED
        }
        val urls = runCatching { xhsContentImageUrls(note, noteId) }.getOrNull().orEmpty()
        // 视频轨（用户 2026-11-22「视频也要下载的」）：分享页解析出的视频候选另走一条静默落盘，
        // 与图片共用同一 noteId 前缀，所以台账/去重/相册查重对视频同样成立。
        val videos = note.videos
        if (urls.isEmpty() && videos.isEmpty()) {
            xlog(ctx, "解析结果没有可下载文件 noteId=$noteId")
            return SaveOutcome.FAIL
        }
        // 补齐：相册里已有哪些图片序号（文件名 `xhs_<noteId>_<ts>_<i>`），只补缺的
        val existingIdx = runCatching {
            XhsDownloadLedger.existingImageIndexes(ctx.contentResolver, noteId)
        }.getOrDefault(emptySet())
        val missingIdx = urls.indices.filter { it !in existingIdx }.toSet()
        // 视频是否已在相册（文件名同为 xhs_<noteId>_ 前缀）；注意必须是「有视频且文件在」
        val videoExists = videos.isNotEmpty() && runCatching {
            XhsDownloadLedger.hasVideoFile(ctx.contentResolver, noteId)
        }.getOrDefault(false)
        // 相册已经齐了（一张不缺、视频也在）就别再进下载：downloadAll 的 onlyIndexes 空集语义是「全都下」，
        // 传空集进去会把整篇重下一遍，日志表现成「存0/败0 耗时1ms → 判失败并重试三次」（2026-11-22 自测）。
        if (missingIdx.isEmpty() && (videos.isEmpty() || videoExists)) {
            xlog(ctx, "相册已齐（图 ${urls.size}/${urls.size}${if (videos.isEmpty()) "" else "，视频已在"}），跳过 noteId=$noteId")
            return SaveOutcome.SKIPPED
        }
        if (urls.isEmpty() && videoExists) {
            xlog(ctx, "相册已有该篇视频，跳过 noteId=$noteId")
            return SaveOutcome.SKIPPED
        }
        if (urls.isNotEmpty() && missingIdx.isEmpty() && videoExists) {
            xlog(ctx, "相册已下齐（图 ${existingIdx.size}/${urls.size}），跳过 noteId=$noteId")
            return SaveOutcome.SKIPPED
        }
        if (missingIdx.size < urls.size) {
            xlog(ctx, "补齐缺失 noteId=$noteId 已有 ${urls.size - missingIdx.size}/${urls.size} 张")
        }
        noteResources(urls.size + videos.size)
        val t0 = System.currentTimeMillis()
        // 只记域名级片段：完整 URL 带 xsec_token，进了诊断文本就等于把凭据复制进剪贴板（审计 #18）
        val hostOnly = urls.firstOrNull()?.let { u -> runCatching { java.net.URI(u).host }.getOrNull() } ?: "-"
        vlog(ctx, "开始落盘 noteId=$noteId 图=${urls.size} 视频=${videos.size} 域名=$hostOnly")
        val result = if (missingIdx.isEmpty()) XhsBatchSave.QuietResult(0, 0, 0, false)
        else runCatching { XhsBatchSave.downloadNoteQuiet(activity, urls, module, noteId, dlGen, missingIdx) }
            .onFailure { xlog(ctx, "落盘调用异常 ${it.javaClass.simpleName} noteId=$noteId") }
            .getOrNull()
        vlog(ctx, "落盘结束 noteId=$noteId 结果=${result?.let { "存${it.ok}/滤${it.filtered}/败${it.failed}/取消${it.canceled}" } ?: "无结果"} " +
            "耗时=${System.currentTimeMillis() - t0}ms 台账=${runCatching { XhsDownloadLedger.downloaded(ctx, noteId).first }.getOrNull()}")
        if (result == null) {
            // 没拿到结果 = 线程没跑完（超时/异常）：记失败，下次重试
            xlog(ctx, "没有拿到落盘结果 noteId=$noteId")
            return SaveOutcome.FAIL
        }
        var videoOk = 0
        var videoSmall = 0
        var videoFail = 0
        for (v in videos) {
            if (stopRequested) break
            if (videoExists) break   // 相册里已有该篇视频：不重复下
            when (XhsBatchVideo.save(ctx, noteId, v, { XhsBatchSave.canceled() })) {
                XhsBatchVideo.Outcome.OK -> videoOk++
                XhsBatchVideo.Outcome.TOO_SMALL -> videoSmall++
                XhsBatchVideo.Outcome.FAIL -> videoFail++
            }
        }
        if (videos.isNotEmpty()) {
            xlog(ctx, "视频落盘 noteId=$noteId 存$videoOk/小$videoSmall/败$videoFail")
        }
        addResources(result.ok + videoOk)
        val savedAny = result.savedAny || videoOk > 0
        val filteredAll = result.filtered > 0 && result.failed == 0
        val videoAllSmall = videos.isNotEmpty() && videoOk == 0 && videoFail == 0 && videoSmall > 0
        if (!savedAny && (filteredAll || videoAllSmall) && videoFail == 0) {
            // 不是失败：这篇的图/视频全部没过画质门槛（≤720p / <5MB）
            xlog(ctx, "全部低于画质门槛，跳过 $noteId（图滤${result.filtered} 视频小$videoSmall）")
            return SaveOutcome.SKIPPED
        }
        if (result.canceled && !savedAny && videoFail == 0) {
            xlog(ctx, "已取消，本篇未落盘 noteId=$noteId")
            return SaveOutcome.FAIL
        }
        // 台账 = 「这篇已经完整拿全」的凭据（用户 2026-11-22：「没有全部成功的不记录 noteid」）。
        // 半篇记了台账 ⇒ 以后永远被判「已下载过」，缺的图再也补不回来（审计 #5）。
        val imagesAccounted = urls.isEmpty() ||
            result.ok + result.filtered + (urls.size - missingIdx.size) >= urls.size
        val videosAccounted = videos.isEmpty() || videoExists || videoOk + videoSmall >= videos.size
        val noFailures = result.failed == 0 && videoFail == 0 && !result.canceled && !stopRequested
        if (noFailures && imagesAccounted && videosAccounted && (savedAny || filteredAll || videoAllSmall)) {
            runCatching { XhsDownloadLedger.record(ctx, noteId, count = urls.size) }
            vlog(ctx, "台账登记 noteId=$noteId 图=${urls.size} 视频=${videos.size}（全篇成功）")
        } else if (!noFailures) {
            vlog(ctx, "不记台账 noteId=$noteId（图败${result.failed}/视频败$videoFail/取消${result.canceled}）")
        }
        return if (savedAny) SaveOutcome.OK else SaveOutcome.FAIL
    }

    /** 等探针在**当前前台 Activity** 上返回非空（每 200ms 一次）；超时或被取消返回 null。 */
    private fun <T : Any> await(timeoutMs: Long, probe: (Activity) -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return null
            val a = XhsBatchSave.activityOrNull()
            if (a != null) {
                val hit = onMain { probe(a) }
                if (hit != null) return hit
            }
            Thread.sleep(200)
        }
        return null
    }

    /**
     * 回到笔记列表：一直按返回，直到当前页能扫到卡片（或超时）。
     * 判据不用「=== 原 Activity 实例」——宿主可能换实例，抱旧实例会白等满超时。
     */
    private fun backToList() {
        var deadline = System.currentTimeMillis() + LIST_BACK_MS
        var extended = false
        var presses = 0
        while (System.currentTimeMillis() < deadline) {
            if (stopRequested) return
            val cur = XhsBatchSave.activityOrNull() ?: run { Thread.sleep(400); continue }
            if (cur.javaClass.name == listClassName && onMain { findCards(cur) }.orEmpty().isNotEmpty()) return
            // 已经回到列表页、只是还在重绘：再给一轮时间，别因为慢半拍就结束整轮（审计 #11）
            if (!extended && cur.javaClass.name == listClassName) {
                extended = true
                deadline += LIST_BACK_MS
            }
            // 回到列表期间列表在重绘，空视图是常态；只有迟迟回不来才再补一次返回，且最多两次
            if (presses == 0 || System.currentTimeMillis() > deadline - LIST_BACK_MS / 2) {
                if (presses < MAX_BACK_PRESSES) {
                    presses++
                    pressBack(cur)
                }
            }
            Thread.sleep(700)
        }
    }

    /**
     * 退回上一层。三级兜底：派发 BACK 键 → onBackPressed → finish。
     *
     * 2026-11-22 真机：详情页上 `dispatchKeyEvent(BACK)` 返回 false 且页面纹丝不动 ⇒ 只发键根本退不出来，
     * 整轮卡在 NoteDetailActivity 到收工。这里每级都等 300ms 看有没有真的换页，仍在原地才降级；
     * **列表页本身绝不 finish**（不然就把用户主页关掉了）。
     */
    /**
     * 是不是小红书自己的**首页/根页**（IndexActivityV2 之类）。
     * 2026-11-22 用户反馈「采集中小红书被关了」：`dumpsys activity exit-info` 里那条是
     * `reason=10 (USER REQUESTED) subreason=22 (REMOVE TASK)`——不是崩溃，是我们按返回把
     * 任务栈整个弹空了，系统于是移除任务，观感就是「小红书自己关了」。
     * 根页永远不能按返回：再按一下就是退出 App。
     */
    private fun isAppRoot(activity: Activity): Boolean {
        val n = activity.javaClass.simpleName
        if (n.contains("Index") || n.contains("Splash") || n.contains("Main")) return true
        // 兜底：任务根 Activity 且不是我们锁定的列表页（作者主页是从别处 push 上来的，不会是 task root）
        return activity.isTaskRoot && listClassName != null && activity.javaClass.name != listClassName
    }

    private fun pressBack(activity: Activity) {
        if (isAppRoot(activity)) {
            xlog(activity.applicationContext, "已在首页（${activity.javaClass.simpleName}），不再按返回", toast = false)
            return
        }
        // 必须成对发 DOWN+UP：AOSP 的 back 由 onKeyUp 触发，只发 DOWN 时页面纹丝不动
        // （2026-11-22 真机：正是这个原因导致每级兜底都降级到 onBackPressed）（审计 #10）
        onMain {
            val t0 = android.os.SystemClock.uptimeMillis()
            val down = activity.dispatchKeyEvent(KeyEvent(t0, t0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK, 0))
            val up = activity.dispatchKeyEvent(KeyEvent(t0, t0 + 50, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK, 0))
            if (!down && !up) runCatching { activity.onBackPressed() }
            true
        }
        Thread.sleep(400)
        val cur = XhsBatchSave.activityOrNull()
        if (cur !== activity) return
        // 已在 finishing 就别再补一次，否则会连退两层（审计 #10）
        if (!activity.isFinishing) onMain { runCatching { activity.onBackPressed() }; true }
        Thread.sleep(400)
        if (XhsBatchSave.activityOrNull() !== activity) return
        // 列表页本身绝不能 finish（会把用户主页关掉）：类名一致即视为列表页
        if (listClassName == null || activity.javaClass.name == listClassName) return
        // 只对「详情类」页 finish：配置变更/用户切换主页会造出新实例，误判会把用户主页关掉（审计 #9）
        val name = activity.javaClass.simpleName
        if (!name.contains("Detail", ignoreCase = true) && !name.contains("Note", ignoreCase = true)) {
            xlog(activity.applicationContext, "三级返回均无效（不进 finish 兜底）class=$name", toast = false)
            return
        }
        onMain { runCatching { activity.finish() }; true }
        Thread.sleep(300)
        if (XhsBatchSave.activityOrNull() === activity) {
            xlog(activity.applicationContext, "三级返回均无效 class=$name", toast = false)
        }
    }

    private fun finish(module: XposedModule, ctx: Context, ok: Int, skipped: Int, failed: Int, note: String?, gen: Int) {
        // 旧线程的收尾不能覆盖新一轮的状态（用户 stop 后立刻再点「采集」时会出现）
        if (gen != runGen.get()) return
        running = false
        endedAt = System.currentTimeMillis()
        pendingNow = 0
        currentNoteRes = 0
        atTop = false
        // 不置空会一直静态持有宿主 Activity + 整棵 decor 视图树到下次启动（审计 #16）
        listClassName = null
        runCatching { stateListener?.invoke() }
        val summary = note ?: "采集结束：成功 $ok · 跳过 $skipped · 失败 $failed"
        // finish 在 finally 里调用：这里再抛异常就没人接了（审计 #1），逐步 runCatching
        runCatching { module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX $summary") }
        runCatching { xlog(ctx, summary, toast = true) }
        runCatching { toast(ctx, summary) }
        runCatching { notifyModule(ctx, AutoActions.PROGRESS, false, summary, ok, skipped, failed, 0) }
        runCatching { notifyModule(ctx, AutoActions.DONE, false, summary) }
    }

    /** 每篇之间的随机停顿，切成 200ms 片检查取消（原来一次 sleep 最长 120s，取消形同虚设）。 */
    private fun sleepPace() {
        var left = paceMs()
        while (left > 0 && !stopRequested) {
            val slice = minOf(left, 200L)
            Thread.sleep(slice)
            left -= slice
        }
    }

    // ---- 视图定位（全部在宿主自己的视图树上，不依赖无障碍） ----

    /**
     * 主页瀑布流卡片：desc 以「笔记,」开头（宿主自己设的无障碍描述，跨版本稳 —— 无障碍时代实测
     * 的同一锚点）。按「先上后左」排序 = 与视觉顺序一致。
     */
    private fun findCards(activity: Activity): List<View> {
        val out = ArrayList<View>()
        walk(activity.window?.decorView) { v ->
            val desc = v.contentDescription?.toString()
            if (v.isShown && isCardDesc(desc)) out += v
        }
        // 排序键先取一次：比较器里每比一次都 getLocationOnScreen（每次 new IntArray）+ 调用系统，很贵
        return out.map { it to it.getLocationOnScreenInts() }
            .sortedWith(compareBy({ it.second.second }, { it.second.first }))
            .map { it.first }
    }

    /** 视图自身在屏幕上的 top（排序/游标用）。 */
    internal fun View.top(): Int = getLocationOnScreenInts().second

    /**
     * 从卡片自身的点击监听里反射出 noteId（24 位 hex）。
     *
     * 为什么需要：详情页命中本地缓存时**不会发 imagefeed**，`noteShares` 就永远拿不到这
     * 一篇的凭据（2026-11-22 真机：点开后 8 秒零登记 → 每篇都判「没拿到分享链」）。而卡片
     * 的 OnClickListener 闭包里握着这条笔记的 bean，24hex 的 id 就在里面，反射两跳即可。
     * 全程 runCatching，取不到返回 null（调用方退回「点开 + 等网络登记」的旧路）。
     */
    /**
     * 卡片实体里的 `xsec_token`（2026-11-22）。缓存笔记的详情页不发 imagefeed，
     * 光有 noteId 也合成不出可用分享链；而列表卡片背后就是带 token 的 NoteItemBean，
     * 点开前登记一次，`synthShareLinkOf` 这条兜底才真的能出图。
     */
    private fun tokenOfCard(card: View, id: String?): String? = try {
        val li = View::class.java.getDeclaredField("mListenerInfo").let { it.isAccessible = true; it.get(card) }
            ?: return null
        val l = li.javaClass.getDeclaredField("mOnClickListener").let { it.isAccessible = true; it.get(li) }
            ?: return null
        fun tokenIn(owner: Any): String? {
            owner.javaClass.declaredFields.forEach { f ->
                if (!f.name.lowercase().contains("token")) return@forEach
                val v = runCatching { f.isAccessible = true; f.get(owner) as? String }.getOrNull()
                if (!v.isNullOrBlank()) return v
            }
            return null
        }
        tokenIn(l)?.let { return it }
        l.javaClass.declaredFields.forEach { f ->
            val bean = runCatching { f.isAccessible = true; f.get(l) }.getOrNull() ?: return@forEach
            // 只认「这个 bean 里能读到的第一个 24hex 正好是我们这个 noteId」的 token，
            // 否则可能把同页别的实体的凭据挂错篇（账号/作者实体也带 token 字段）
            val beanId = bean.javaClass.declaredFields.firstNotNullOfOrNull { bf ->
                runCatching { bf.isAccessible = true; (bf.get(bean) as? String)?.takeIf { it.matches(HEX24) } }.getOrNull()
            }
            if (id == null || beanId == id) tokenIn(bean)?.let { return it }
        }
        null
    } catch (t: Throwable) {
        null
    }

    private fun noteIdOfCard(card: View): String? = try {
        val li = View::class.java.getDeclaredField("mListenerInfo").let { it.isAccessible = true; it.get(card) }
            ?: return null
        val l = li.javaClass.getDeclaredField("mOnClickListener").let { it.isAccessible = true; it.get(li) }
            ?: return null
        fun hexOf(v: Any?): String? = (v as? String)?.takeIf { it.matches(HEX24) }
        // 字段名优先：宿主 userId 同样是 24hex，取「第一个 24hex」可能拿错（审计 #7）。
        // 先只认字段名像 noteId/id 的，再退回任意字段。
        fun namedHex(owner: Any, names: (String) -> Boolean): String? {
            owner.javaClass.declaredFields.forEach { f ->
                if (!names(f.name)) return@forEach
                runCatching { f.isAccessible = true; hexOf(f.get(owner)) }.getOrNull()?.let { return it }
            }
            return null
        }
        val likeNoteId: (String) -> Boolean = { n ->
            val k = n.lowercase()
            (k.contains("noteid") || k == "id" || k.endsWith("noteid")) && !k.contains("userid")
        }
        namedHex(l, likeNoteId)?.let { return it }
        // 一跳：监听器自身「名字像 noteId」的 String 字段
        namedHex(l, likeNoteId)?.let { return it }
        // 两跳：监听器持有的 bean —— **必须先按字段名命中**。bean 里同时有笔记 id 与作者 userid
        // （都是 24hex），取「任意一个 24hex」会拿到作者 id：同作者所有卡片 key 相同 →
        // 整页只剩第一张能处理，其余静默漏下（2026-11-22 审计 P0）。
        l.javaClass.declaredFields.forEach { f ->
            val bean = runCatching { f.isAccessible = true; f.get(l) }.getOrNull() ?: return@forEach
            namedHex(bean, likeNoteId)?.let { return it }
        }
        // 三层递归：bean 里真正存 noteId 的往往是「bean → item → note」这种链条，
        // 两跳够不到就一直是 noteId=待取（真机 17:47/18:00 全是这种）。同样只认名字像 noteId 的字段。
        fun deepNamed(o: Any?, depth: Int): String? {
            if (o == null || depth < 0) return null
            if (o is String) return hexOf(o)
            if (o is View || o is android.content.Context || o is android.app.Activity) return null
            val c = o.javaClass
            if (c.isArray || c.name.startsWith("java.util.")) return null
            namedHex(o, likeNoteId)?.let { return it }
            var scanned = 0
            for (f in c.declaredFields) {
                if (scanned++ > 40) break
                val v = runCatching { f.isAccessible = true; f.get(o) }.getOrNull() ?: continue
                deepNamed(v, depth - 1)?.let { return it }
            }
            return null
        }
        deepNamed(l, 3)?.let { return it }
        // 兜底：bean 里任意 24hex 只有在「这个 id 已经带着凭据」时才能当 noteId 用
        // （凭据是我们从响应里按同一 noteId 记的，等价于外键校验）
        l.javaClass.declaredFields.forEach { f ->
            val bean = runCatching { f.isAccessible = true; f.get(l) }.getOrNull() ?: return@forEach
            bean.javaClass.declaredFields.forEach { bf ->
                val cand = runCatching { bf.isAccessible = true; hexOf(bf.get(bean)) }.getOrNull() ?: return@forEach
                if (XhsVideoSave.shareLinkOf(cand) != null || XhsVideoSave.tokenOf(cand) != null) return cand
            }
        }
        null
    } catch (t: Throwable) {
        // 反射断了要能看出来：否则调用方只能写「没人登记分享链」，无法区分「宿主改字段」和
        // 「宿主真没发」（2026-11-22 审计 P2）。节流：最多每 10 秒一行。
        val now = System.currentTimeMillis()
        if (now - lastReflectLogAt > 10_000) {
            lastReflectLogAt = now
            XhsBatchSave.activityOrNull()?.applicationContext?.let {
                logEntry("卡片反射失败：${t.javaClass.simpleName}: ${t.message}")
            }
        }
        null
    }

    @Volatile private var lastReflectLogAt = 0L

    private fun View.getLocationOnScreenInts(): Pair<Int, Int> {
        val xy = IntArray(2)
        runCatching { getLocationOnScreen(xy) }
        return xy[0] to xy[1]
    }

    /** 点卡片：先试宿主自己的 click 监听，没有就按坐标补一次真实触摸序列。 */
    private fun clickView(v: View): Boolean {
        if (v.performClick()) return true
        var p: View? = v
        repeat(4) {
            p = p?.parent as? View
            if (p != null && p!!.performClick()) return true
        }
        return tapByTouch(v)
    }

    /** 滚一屏：向 decorView 派发带 MOVE 的真手势（宿主列表只认真手势，scrollBy 无效）。 */
    /** 视口里面积最大的「还能往下滚」的容器（作者主页是真 RecyclerView / NestedScroll，不是 androidx） */
    private fun scrollableUnder(root: View): View? {
        var best: View? = null
        var bestArea = 0
        fun walk(v: View, depth: Int) {
            if (depth > 24) return
            if (v.isShown && v.width > 0 && v.height > 0 && v.canScrollVertically(1)) {
                val area = v.width * v.height
                if (area > bestArea) {
                    bestArea = area
                    best = v
                }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
        }
        walk(root, 0)
        return best
    }

    private fun swipeUp(activity: Activity): Boolean {
        val decor = activity.window?.decorView ?: return false
        return onMain {
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) return@onMain false
            // 2026-11-22 真机：整屏手势派给 decorView 时代码返回 true 但列表纹丝不动
            // （`滚动后：6张/top=1132 → 6张/top=1132`，profile 页的 NestedScrollLayout 吃掉了事件）。
            // 改成派给真正能滚的那个容器，坐标用它自己的坐标系；还不动就退化为 scrollBy。
            val target = scrollableUnder(decor) ?: decor
            val tw = if (target === decor) w else target.width
            val th = if (target === decor) h else target.height
            if (tw <= 0 || th <= 0) return@onMain false
            val x = tw / 2f
            val from = th * 0.75f
            val to = th * 0.30f
            val t0 = android.os.SystemClock.uptimeMillis()
            val steps = 12
            // MotionEvent 全部 recycle（每篇滚一次，原来 14 个事件都靠 GC 兜底）
            fun send(t: Long, action: Int, y: Float): Boolean {
                val e = MotionEvent.obtain(t0, t, action, x, y, 0)
                return try { target.dispatchTouchEvent(e) } finally { e.recycle() }
            }
            var ok = send(t0, MotionEvent.ACTION_DOWN, from)
            for (i in 1..steps) {
                ok = send(t0 + i * 16L, MotionEvent.ACTION_MOVE, from + (to - from) * i / steps) || ok
            }
            ok = send(t0 + steps * 16L, MotionEvent.ACTION_UP, to) || ok
            // 手势没让容器动（事件被更上层的布局吃掉）→ 直接滚它。
            // 这是视图层面的滚，不经触摸分发，宿主自己的加载逻辑照旧（滚动回调触发翻页）。
            if (target.canScrollVertically(1)) {
                runCatching { target.scrollBy(0, (th * 0.6f).toInt()) }
            }
            ok
        } == true
    }

    /** 向下滚一屏（回顶用）；与 [swipeUp] 手序相反。 */
    private fun swipeDown(activity: Activity): Boolean {
        val decor = activity.window?.decorView ?: return false
        return onMain {
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) return@onMain false
            val x = w / 2f
            val from = h * 0.30f
            val to = h * 0.78f
            val t0 = android.os.SystemClock.uptimeMillis()
            val steps = 12
            fun send(t: Long, action: Int, y: Float): Boolean {
                val e = MotionEvent.obtain(t0, t, action, x, y, 0)
                return try { decor.dispatchTouchEvent(e) } finally { e.recycle() }
            }
            var ok = send(t0, MotionEvent.ACTION_DOWN, from)
            for (i in 1..steps) {
                ok = send(t0 + i * 16L, MotionEvent.ACTION_MOVE, from + (to - from) * i / steps) || ok
            }
            ok = send(t0 + steps * 16L, MotionEvent.ACTION_UP, to) || ok
            ok
        } == true
    }

    private fun tapByTouch(v: View): Boolean {
        val x = v.width / 2f
        val y = v.height / 2f
        val down = System.currentTimeMillis()
        val d = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0)
        val u = MotionEvent.obtain(down, down + 60, MotionEvent.ACTION_UP, x, y, 0)
        val a = v.dispatchTouchEvent(d)
        val b = v.dispatchTouchEvent(u)
        d.recycle(); u.recycle()
        return a || b
    }


    private fun walk(root: View?, depth: Int = 0, visit: (View) -> Unit) {
        if (root == null || depth > 40) return
        visit(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) walk(root.getChildAt(i), depth + 1, visit)
    }

    /** 失败点转储可见文案，便于按真机结构收敛锚点（仅写文件日志，不刷 modlog）。 */
    private fun dumpViews(ctx: Context, why: String, activity: Activity?) {
        // 视图树只能在主线程读：驱动线程直接 walk 会与 layout/复用并发，可能抛异常把宿主带走
        // （2026-11-22 审计 P1）。decorView 也在主线程取（window 本身也不是跨线程安全的）。
        // 读失败也要留一行，否则「为什么没转储」永远查不到。
        val text = onMainOrTimeout { buildDump(why, activity?.window?.decorView) }.second
        xlog(ctx, text ?: "$why 视图转储：主线程 3s 未响应", toast = false)
    }

    private fun buildDump(why: String, root: View?): String? {
        if (root == null) return "$why: 无视图树"
        val sb = StringBuilder("$why 视图转储：")
        var n = 0
        walk(root) { v ->
            if (n < 40 && v.isShown) {
                val label = (v as? TextView)?.text?.toString()?.take(20)
                    ?: v.contentDescription?.toString()?.take(20)
                sb.append("\n  ${v.javaClass.simpleName}")
                if (!label.isNullOrBlank()) sb.append(" «$label»")
                n++
            }
        }
        return sb.toString()
    }

    // ---- 线程与通道 ----

    /**
     * 在宿主主线程执行（超时返回 null，绝不阻塞主线程）。
     * 注意：**不能用队列传结果**——`ArrayBlockingQueue` 拒收 null，探针合法返回 null 时
     * `offer(null)` 会在主线程抛 NPE 把宿主带走（2026-11-22 真机 exit-info 实证）。
     */
    private fun <T> onMain(f: () -> T?): T? = onMainOrTimeout(f).second

    /**
     * 同 [onMain]，但回答「主线程到底执行了没有」。
     * 超时不是「没有卡片」：主线程被首屏/GC 卡住时，探针会迟到执行，可能对着已被回收复用的
     * view 做点击（打开 A 却采到 B）。所以超时就把已投递的 Runnable 作废，并让调用方**只等不判**
     * （2026-11-22 审计 #3/#8）。
     */
    private fun <T> onMainOrTimeout(f: () -> T?): Pair<Boolean, T?> {
        val holder = arrayOfNulls<Any>(1)
        // 三态握手 0=待跑 / 1=已认领 / 2=作废。原来「先读 abandoned 再执行」两步不原子：
        // 驱动线程恰在两步之间超时置位，主线程仍会把 performClick/onBackPressed 全跑完，
        // 而调用方已按「没执行」继续下一张 → 迟到的点击打开别的笔记（2026-11-22 审计 P1）。
        val state = java.util.concurrent.atomic.AtomicInteger(0)
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (!state.compareAndSet(0, 1)) {
                done.set(true)
                return@post
            }
            runCatching { holder[0] = f() }
            done.set(true)
        }
        val deadline = System.currentTimeMillis() + 3000
        while (!done.get() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val ran = done.get()
        if (!ran) state.compareAndSet(0, 2)
        @Suppress("UNCHECKED_CAST")
        return ran to (holder[0] as T?)
    }

    private fun toast(ctx: Context, msg: String) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            runCatching { android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show() }
        }
    }

    private fun notifyModule(
        ctx: Context,
        action: String,
        running: Boolean,
        text: String,
        ok: Int = 0,
        skipped: Int = 0,
        failed: Int = 0,
        total: Int = 0,
    ) {
        runCatching {
            ctx.sendBroadcast(
                Intent(action)
                    .setPackage(AutoActions.MODULE_PACKAGE)
                    .putExtra(AutoActions.EXTRA_RUNNING, running)
                    .putExtra(AutoActions.EXTRA_TEXT, text)
                    .putExtra("ok", ok).putExtra("skipped", skipped)
                    .putExtra("failed", failed).putExtra("total", total),
            )
        }
    }

    /** 文件日志：宿主进程的 logcat 在荣耀 HKS 下是密文，排障只能靠这个。 */
    /**
     * 每一步都写文件日志 + 推给宿主内悬浮面板（2026-11-22 用户反馈「toast 不太行，要滚动消息」）：
     * Toast 每条都要抢焦点、还会叠成一串；滚动面板是同一个 View 持续更新，看得见上下文。
     * 转储类长文本（[dumpViews]）只进文件不上面板，否则一次 40 行把面板顶满。
     */
    /**
     * 只进文件的详细日志（2026-11-22 用户要求「所有数据和情况都打印」）：
     * 面板只有 6 行，细节全塞进去就没法看了，所以决策链路一律走这里。
     */
    private fun vlog(ctx: Context, msg: String) = xlog(ctx, msg, toast = false)

    /**
     * 一键复制用的诊断文本（用户 2026-11-22：「针对异常情况，增加一键复制信息功能，包含诊断必要信息」）。
     * 只放**能定位问题**的东西：版本、屏幕、运行参数、汇总计数、最近 300 行过程日志、失败行汇总；
     * 不放用户内容、不放 URL 全量（只留首个 URL 的域名级片段）。
     */
    fun diagnostics(ctx: Context): String {
        val sb = StringBuilder()
        val now = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        sb.appendLine("===== HonorHook 批量采集诊断 =====")
        sb.appendLine("时间: $now")
        sb.appendLine("模块: ${appVersion(ctx, AutoActions.MODULE_PACKAGE)}")
        sb.appendLine("宿主: ${appVersion(ctx, AutoActions.HOST_PACKAGE)}")
        val dm = runCatching { ctx.resources.displayMetrics }.getOrNull()
        sb.appendLine("系统: Android ${android.os.Build.VERSION.RELEASE}(SDK ${android.os.Build.VERSION.SDK_INT}) " +
            "${dm?.widthPixels}x${dm?.heightPixels} @${dm?.densityDpi}dpi ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        sb.appendLine("状态: ${if (running) (if (stopRequested) "收尾中" else "采集中") else "空闲"} " +
            "已处理=$processed 已发现=$discoveredCount 已存=$okCount 跳过=$skippedCount 失败=$failedCount 本屏待处理=$pendingNow")
        sb.appendLine("节奏: ${paceMinMs}~${paceMaxMs}ms")
        sb.appendLine("前台: ${runCatching { XhsBatchSave.activityOrNull()?.javaClass?.name }.getOrNull() ?: "?"}")
        sb.appendLine("面板日志: ${lastLines().size} 条")
        sb.appendLine("--- 最近 300 行日志 ---")
        val tail = runCatching {
            val f = java.io.File(ctx.getExternalFilesDir(null), "xhs_batch.log")
            if (!f.exists()) emptyList() else f.readLines().takeLast(300)
        }.getOrDefault(emptyList())
        tail.forEach { sb.appendLine(it) }
        sb.appendLine("--- 失败/异常行 ---")
        tail.filter { line -> listOf("失败", "异常", "解析不出", "超时", "未挂", "错误").any { line.contains(it) } }
            .forEach { sb.appendLine(it) }
        return sb.toString()
    }

    private fun appVersion(ctx: Context, pkg: String): String = runCatching {
        val pm = ctx.packageManager
        val info = pm.getPackageInfo(pkg, 0)
        "$pkg ${info.versionName}"
    }.getOrDefault("$pkg ?")

    private fun xlog(ctx: Context, msg: String, toast: Boolean = true) {
        runCatching {
            Log.i(TAG, msg)
            val f = java.io.File(ctx.getExternalFilesDir(null), "xhs_batch.log")
            if (f.length() > 512 * 1024) f.delete()
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText("$t $msg\n")
        }
        if (toast) runCatching { pushLine(msg) }
    }
}
