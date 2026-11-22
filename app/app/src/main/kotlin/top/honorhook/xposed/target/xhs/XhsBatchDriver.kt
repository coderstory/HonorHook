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

    private const val JUDGE_PREFIX = "XhsHook·Batch"
    private const val TAG = "HonorHookXhsBatch"

    private const val NOTE_ID_WAIT_MS = 8000L
    private const val NOTE_RESOLVE_MS = 90_000L
    private const val LIST_BACK_MS = 5000L
    /** 每篇之间的随机节奏默认 2~4s（用户 2026-11-21 定案；UI 页可改，经 CONFIG 广播下发）。 */
    private const val PACE_MIN_MS = 2000L
    private const val PACE_MAX_MS = 4000L
    private const val IDLE_LIMIT = 3

    /** 同一列表页重绘等待轮次（700ms/轮 ≈ 7s），超时才收工。 */
    private const val LIST_REDRAW_WAIT = 10

    /** 整轮最多按几次返回（防一路把宿主退到桌面）。 */
    private const val MAX_BACK_PRESSES = 2

    /** 详情页开出后等分享链/裸 token 落表的上限（宿主命中缓存时不发请求，要给足时间）。 */
    private const val LINK_WAIT_MS = 15_000L

    /** 宿主 noteId 形态（24 位小写 hex），与 XhsSavePivot.HEX24 同口径。 */
    private val HEX24 = Regex("[0-9a-f]{24}")

    /** 当前这轮「扫到过卡片的那一页」，返回时用来判「同一列表页重绘」与「能不能 finish」。 */
    @Volatile private var listActivity: Activity? = null
    /** 卡片锚点：desc 前缀（同 XhsAutoEntry 的主页判据）。 */
    private const val CARD_DESC_PREFIX = "笔记,"

    @Volatile private var running = false
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

    /** 最近一次驱动的应用上下文（跨进程查询状态时用来发广播）。 */
    @Volatile private var currentApp: Context? = null

    fun isRunning(): Boolean = running

    fun processedCount(): Int = processed

    /** 已收到取消、正在等当前这篇收尾（按钮显示「收尾」）。 */
    fun isStopping(): Boolean = stopRequested

    /** 回答模块面板的状态查询（跨进程，面板进页时问一次）。 */
    fun publishState() {
        val app = currentApp ?: return
        notifyModule(app, AutoActions.STATE, running, if (running) "运行中" else "待机")
        if (running) notifyModule(app, AutoActions.PROGRESS, true, "运行中", processed, 0, 0, 0)
    }

    fun start(module: XposedModule, ctx: Context) {
        if (running) return
        currentApp = ctx.applicationContext
        XhsBatchSave.clearCancel()
        running = true
        stopRequested = false
        processed = 0
        listActivity = null
        val app = ctx.applicationContext
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 采集开始（逐篇点开取分享链直下）")
        xlog(app, "采集开始")
        // 驱动线程是裸 Thread：任何逃出去的 Throwable 会走默认 UncaughtExceptionHandler
        // **直接杀宿主进程**（且 running 卡在 true，按钮永远显示「取消」）。双重兜底（2026-11-22 审计 #1）。
        val worker = Thread({
            runCatching { loop(module, app) }.onFailure {
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
        worker.start()
        stateListener?.invoke()
        toast(ctx, "开始采集：逐篇打开下载，可随时点取消")
        notifyModule(app, AutoActions.STATE, true, "开始采集…")
    }

    fun stop(module: XposedModule?, ctx: Context?) {
        if (!running || stopRequested) return   // 用户会连点「取消」：只认第一次，别刷屏
        stopRequested = true
        // 立即回写状态：下载/解析可能还要几秒到几十秒，按钮不刷新用户会以为没点中而连点（审计 #21）
        stateListener?.invoke()
        // 正在下载的那篇立刻中断（不然要等它自己下完，最坏几十秒）
        runCatching { XhsBatchSave.requestCancel() }
        ctx?.let { notifyModule(it, AutoActions.STATE, true, "已取消，正在收尾…") }
        module?.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 收到取消，正在收尾")
        ctx?.let { xlog(it, "收到取消", toast = true) }
    }

    private fun loop(module: XposedModule, ctx: Context) {
        var ok = 0
        var skipped = 0
        var failed = 0
        var idle = 0
        // 滚动「没动」的独立计数：不能复用 idle——它在每轮有卡片时都被清零，++
        // 永远攒不到 IDLE_LIMIT，手势失效时就会**每 1.2s 空滚一次、永不收工**（2026-11-22 审计 #4）。
        var scrollIdle = 0
        var redrawWaits = 0
        var offList = 0
        var cardsTotal = 0
        // 本屏已处理到第几张（按当前屏的排序索引）。**不能按 view 实例记**（宿主回收复用），
        // 也不能只记上一次的 top（同排两张卡 top 相同，会被自己反复选中）——2026-11-22 真机：
        // 记 top 的版本处理完第一篇后就再也选不出下一张，12 秒 3 次「滚不动」直接收工。
        var index = 0
        try {
            while (!stopRequested) {
                // 每轮都重新取前台 Activity：详情页/列表页可能是不同实例，抱死一个会对着
                // 已销毁的视图树空转（上一版就是这样在第一条之后收工的）。
                val act = XhsBatchSave.activityOrNull()
                if (act == null) {
                    Thread.sleep(500)
                    continue
                }
                if (act !== listActivity && listActivity != null) {
                    index = 0
                }
                val (scanned, cardList) = onMainOrTimeout { findCards(act) }
                if (!scanned) {
                    // 主线程卡住：视图树这一刻不可信，等它缓过来，绝不据此按返回（审计 #8）
                    Thread.sleep(500)
                    continue
                }
                val cards = cardList.orEmpty()
                cardsTotal = maxOf(cardsTotal, cards.size)
                if (cards.isEmpty()) {
                    // 扫到过卡片的同一页现在空 = 宿主重绘/占位中，**绝不能按返回**（2026-11-22 真机：
                    // 连按 3 次把小红书一路退到桌面，整轮就这么收工了）。等它画出来。
                    if (act === listActivity) {
                        if (++redrawWaits >= LIST_REDRAW_WAIT) {
                            xlog(ctx, "列表页一直没卡片，收工")
                            break
                        }
                        Thread.sleep(700)
                        continue
                    }
                    // 确实离开了列表（停在详情/弹层）：整轮只允许按两次返回，按完就等
                    offList++
                    xlog(ctx, "第 $offList 次返回：${act.javaClass.simpleName} 无卡片（index=$index）")
                    if (offList == 1) dumpViews(ctx, "返回后无卡片", act.window?.decorView)
                    if (offList > MAX_BACK_PRESSES) break
                    pressBack(act)
                    Thread.sleep(900)
                    continue
                }
                offList = 0
                idle = 0
                redrawWaits = 0
                scrollIdle = 0
                listActivity = act
                if (index >= cards.size) {
                    // 本屏处理完：滚一屏，滚完确认列表真的动了，否则「一直滚不动」会空转
                    val beforeTop = cards.first().top()
                    val beforeSize = cards.size
                    xlog(ctx, "本屏 ${cards.size} 张处理完，向上滚一屏", toast = true)
                    swipeUp(act)
                    Thread.sleep(1200)
                    val after = onMain { findCards(act) }.orEmpty()
                    index = 0
                    val moved = after.size != beforeSize || (after.firstOrNull()?.top() ?: -1) != beforeTop
                    if (moved) {
                        scrollIdle = 0
                    } else if (++scrollIdle >= IDLE_LIMIT) {
                        xlog(ctx, "列表滚不动了，收工")
                        break
                    }
                    continue
                }
                idle = 0
                val card = cards[index]
                index++
                val known = onMain { noteIdOfCard(card) }
                // 整条 share link 已经在表里（homefeed/上一屏响应登记过）⇒ 根本不用点开这篇：
                // 零详情页、零等待，直接解析直下。这是最快也最稳的一条路（2026-11-22）。
                if (known != null && XhsVideoSave.shareLinkOf(known) != null) {
                    val direct = runCatching { collect(module, ctx, prev = null, known = known) }
                        .getOrDefault(SaveOutcome.FAIL)
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
                    xlog(ctx, dt, toast = true)
                    processed = ok + skipped
                    stateListener?.invoke()
                    notifyModule(ctx, AutoActions.PROGRESS, true, dt, ok, skipped, failed, cardsTotal)
                    sleepPace()
                    continue
                }
                xlog(ctx, "点开第 $index/${cards.size} 张（noteId=${known ?: "待取"}）", toast = true)
                val prev = XhsVideoSave.lastShareNoteId()
                if (onMain { clickView(card) } != true) {
                    failed++
                    processed = ok + skipped
                    stateListener?.invoke()
                    notifyModule(ctx, AutoActions.PROGRESS, true, "卡片点不动", ok, skipped, failed, cardsTotal)
                    xlog(ctx, "卡片点不动（第 $index 张）", toast = true)
                    Thread.sleep(paceMs())
                    continue
                }
                val saved = runCatching { collect(module, ctx, prev, known) }.getOrDefault(SaveOutcome.FAIL)
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
                xlog(ctx, "$text（本屏第 $index/${cards.size} 张）", toast = true)
                processed = ok + skipped
                stateListener?.invoke()
                notifyModule(ctx, AutoActions.PROGRESS, true, "$text（成功 $ok · 跳过 $skipped · 失败 $failed）", ok, skipped, failed, cardsTotal)
                backToList()
                sleepPace()
            }
            if (cardsTotal == 0) {
                dumpViews(ctx, "一张卡片都没扫到", XhsBatchSave.activityOrNull()?.window?.decorView)
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 采集中断: ${t.javaClass.simpleName}: ${t.message}")
            xlog(ctx, "采集中断 ${t.javaClass.simpleName}: ${t.message}", toast = true)
        } finally {
            finish(module, ctx, ok, skipped, failed, null)
        }
    }

    private enum class SaveOutcome { OK, SKIPPED, FAIL }

    /**
     * 点开之后：认 noteId → 查去重 → 解析图片 → 静默直下。
     *
     * noteId 的来源是详情页自己发出的 `imagefeed` 响应（X4 旁路登记，实测每篇 +1），
     * 所以「上一篇的值」必须由调用方传进来比对，否则会把上一篇的 noteId 当成这一篇。
     */
    private fun collect(module: XposedModule, ctx: Context, prev: String?, known: String? = null): SaveOutcome {
        val noteId = known ?: await(NOTE_ID_WAIT_MS) { XhsVideoSave.lastShareNoteId()?.takeIf { it != prev } }
        if (noteId == null) {
            xlog(ctx, "详情页没拿到 noteId（没人登记分享链）")
            dumpViews(ctx, "详情页", XhsBatchSave.activityOrNull()?.window?.decorView)
            return SaveOutcome.FAIL
        }
        // noteId 有了不代表凭据有了：宿主命中本地缓存时不发 imagefeed，分享链永远不会落表。
        // 等它一会儿（15s），仍然只有 none 才判失败——这样「打开快、但链是缓存的」不会误判。
        if (XhsVideoSave.shareLinkOf(noteId) == null && XhsVideoSave.tokenOf(noteId) == null) {
            await(LINK_WAIT_MS) {
                if (XhsVideoSave.shareLinkOf(noteId) != null || XhsVideoSave.tokenOf(noteId) != null) true else null
            }
        }
        val already = runCatching { XhsDownloadLedger.downloaded(ctx, noteId).first }.getOrDefault(0)
        if (already > 0) {
            xlog(ctx, "已下载过，跳过 noteId=$noteId")
            return SaveOutcome.SKIPPED
        }
        val activity = XhsBatchSave.activityOrNull() ?: return SaveOutcome.FAIL
        xlog(ctx, "noteId=$noteId 链=${if (XhsVideoSave.shareLinkOf(noteId) != null) "share_info" else "token合成"} " +
            "token=${XhsVideoSave.tokenOf(noteId) != null}")
        val urls = runCatching {
            val note = kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(NOTE_RESOLVE_MS) { XhsSavePivot.resolveByShareLink(noteId, module) }
            }
            xhsContentImageUrls(note, noteId)
        }.getOrNull().orEmpty()
        if (urls.isEmpty()) {
            xlog(ctx, "解析不出图片 noteId=$noteId")
            return SaveOutcome.FAIL
        }
        val ok = runCatching { XhsBatchSave.downloadNoteQuiet(activity, urls, module, noteId) }.getOrDefault(false)
        return if (ok) SaveOutcome.OK else SaveOutcome.FAIL
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
            if (onMain { findCards(cur) }.orEmpty().isNotEmpty()) return
            // 已经回到列表页、只是还在重绘：再给一轮时间，别因为慢半拍就结束整轮（审计 #11）
            if (!extended && cur === listActivity) {
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
    private fun pressBack(activity: Activity) {
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
        if (listActivity == null || activity === listActivity) return
        // 只对「详情类」页 finish：配置变更/用户切换主页会造出新实例，误判会把用户主页关掉（审计 #9）
        val name = activity.javaClass.simpleName
        if (!name.contains("Detail", ignoreCase = true) && !name.contains("Note", ignoreCase = true)) return
        onMain { runCatching { activity.finish() }; true }
    }

    private fun finish(module: XposedModule, ctx: Context, ok: Int, skipped: Int, failed: Int, note: String?) {
        running = false
        // 不置空会一直静态持有宿主 Activity + 整棵 decor 视图树到下次启动（审计 #16）
        listActivity = null
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
            if (v.isShown && v.contentDescription?.startsWith(CARD_DESC_PREFIX) == true) out += v
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
        // 一跳：监听器自身的 String 字段
        l.javaClass.declaredFields.forEach { f ->
            runCatching { f.isAccessible = true; hexOf(f.get(l)) }.getOrNull()?.let { return it }
        }
        // 两跳：监听器持有的 bean 里的 String / id 字段
        l.javaClass.declaredFields.forEach { f ->
            val bean = runCatching { f.isAccessible = true; f.get(l) }.getOrNull() ?: return@forEach
            bean.javaClass.declaredFields.forEach { bf ->
                runCatching { bf.isAccessible = true; hexOf(bf.get(bean)) }.getOrNull()?.let { return it }
            }
        }
        null
    } catch (t: Throwable) {
        null
    }

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
    private fun swipeUp(activity: Activity): Boolean {
        val decor = activity.window?.decorView ?: return false
        return onMain {
            val w = decor.width
            val h = decor.height
            if (w <= 0 || h <= 0) return@onMain false
            val x = w / 2f
            val from = h * 0.75f
            val to = h * 0.30f
            val t0 = android.os.SystemClock.uptimeMillis()
            var ok = decor.dispatchTouchEvent(MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, from, 0))
            val steps = 12
            for (i in 1..steps) {
                val t = t0 + i * 16L
                val y = from + (to - from) * i / steps
                ok = decor.dispatchTouchEvent(MotionEvent.obtain(t0, t, MotionEvent.ACTION_MOVE, x, y, 0)) || ok
            }
            ok = decor.dispatchTouchEvent(MotionEvent.obtain(t0, t0 + steps * 16L, MotionEvent.ACTION_UP, x, to, 0)) || ok
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
    private fun dumpViews(ctx: Context, why: String, root: View?) {
        if (root == null) { xlog(ctx, "$why: 无视图树"); return }
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
        xlog(ctx, sb.toString(), toast = false)
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
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        val abandoned = java.util.concurrent.atomic.AtomicBoolean(false)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            if (abandoned.get()) {
                done.set(true)
                return@post
            }
            runCatching { holder[0] = f() }
            done.set(true)
        }
        val deadline = System.currentTimeMillis() + 3000
        while (!done.get() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val ran = done.get()
        if (!ran) abandoned.set(true)
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
     * 每一步都写文件日志 + Toast（2026-11-22 用户要求「用 toast 打印所有操作」）：
     * 手机上直接看得到我在干什么，不用去翻文件。转储类长文本（[dumpViews]）只进文件不弹，
     * 否则一次弹 40 行会把屏幕盖住。
     */
    private fun xlog(ctx: Context, msg: String, toast: Boolean = true) {
        runCatching {
            Log.i(TAG, msg)
            val f = java.io.File(ctx.getExternalFilesDir(null), "xhs_batch.log")
            if (f.length() > 512 * 1024) f.delete()
            val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            f.appendText("$t $msg\n")
        }
        if (toast) runCatching { toast(ctx, msg.take(60)) }
    }
}
