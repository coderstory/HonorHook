package top.honorhook.xposed.target.xhs

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import top.honorhook.xposed.core.MountEngine

/**
 * 解析失败时的**宿主兜底**：进程内模拟「长按图片 → 点面板里的保存」（2026-11-25 用户拍板）。
 *
 * 为什么需要：X4/X5 与批量驱动都依赖「拿到 noteId + 分享链/内存图集」再自己去下原图；
 * 一旦宿主命中缓存不发 `imagefeed`、或分享页是反爬壳页，整篇就判失败（用户在面板上只看到「失败 N」）。
 * 但这一刻**详情页的图已经渲染在屏上了**，宿主自己也具备保存能力（长按面板「保存」）。
 * 于是兜底不动我们的解析链，只把「用户手动长按 → 点保存」这两步交给宿主自己做：
 * 宿主发它自己的请求、用它自己的图，我们只管点 —— 代价是宿主原生保存（单张压缩图），不是原图。
 *
 * 与 X5（[XhsBatchSave]）的关系（2026-11-22 真机实证）：详情页长按弹出的是**图片预览面板**，
 * 其「保存」不走 X5 挂的 `ji9.b` lambda（modlog 零 X5 行）。因此这条兜底一般不会被模块接管，
 * 而是宿主原生保存；万一被接管，X5 链接解析失败也会 `proceed()` 回落宿主原生保存，结果同样是「存了一张」。
 *
 * **面板可能不在 Activity 的视图树里**（2026-11-22 转储只看到详情页）：宿主用的是
 * `LCBBottomSheetDialog` 一类 Dialog，根视图挂在 WindowManagerGlobal。所以这里先枚举全部根视图，
 * 枚举不到（hidden API 拦截）再退回 Activity decor —— 降级只影响「找不找得到保存按钮」，不影响主流程。
 *
 * 判据不猜：点完保存后轮询 MediaStore 的最大 `_id`，涨了才算成功（相册口径，与台账/相册核对一致）。
 * 全程吞异常（[trySave] 外层 runCatching），失败返回 false，由调用方维持原来的「失败」结论。
 */
internal object XhsHostNativeSave {

    private const val TAG = MountEngine.TAG
    private const val JUDGE_PREFIX = "XhsHook·Fallback"

    /** 长按后等面板弹出的上限（真机 Happy path ≈ 300~800ms，给慢机留余量）。 */
    private const val PANEL_WAIT_MS = 2_500L

    /** 点保存后等相册出现新图的上限（宿主原生保存走它自己的下载/合成，网络慢时会久一点）。 */
    private const val SAVE_WAIT_MS = 10_000L

    /** 保存成功后等宿主自己收面板的上限；超时才自己去找 Dialog dismiss。 */
    private const val PANEL_CLOSE_WAIT_MS = 1_500L

    private const val POLL_STEP_MS = 250L

    /** 长按的按住时长：Android 默认 longPressTimeout 500ms，多给 100ms 余量。 */
    private const val LONG_PRESS_HOLD_MS = 600L

    /** 面板上的保存文案（宿主各版本措辞不同，按精确优先、前缀次之匹配）。 */
    private val SAVE_LABELS = arrayOf("保存", "保存图片", "保存到相册", "保存原图", "存图")

    /** 同屏一定会出现的干扰项：宁可找不到保存，也不点这些。 */
    private val NEGATIVE_LABELS = arrayOf("取消", "举报", "不感兴趣", "删除", "分享")

    private const val XHS_PACKAGE = "com.xingin.xhs"

    /** MediaStore.MediaColumns 的 `owner_package_name` / `relative_path`（API 29+；写字面量省兼容告警）。 */
    private const val OWNER_PACKAGE_NAME = "owner_package_name"
    private const val RELATIVE_PATH = "relative_path"

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    /** 上一次兜底的诊断串（写进 modlog，真机失败时用来判断是哪一步没成）。 */
    @Volatile
    var lastDiag: String? = null
        private set

    /**
     * 兜底一次：长按详情页里面积最大的图片 → 等面板 → 点「保存」→ 等相册多出一张。
     *
     * @return true = 相册确实新增了一张（宿主原生保存成功）
     */
    fun trySave(
        activity: Activity?,
        module: XposedModule,
        noteId: String?,
        /** 取消/暂停判据（驱动侧 stopRequested/paused）：每一步轮询都看一次，别让 14s 的兜底拖住取消。 */
        canContinue: () -> Boolean = { true },
    ): Boolean {
        lastDiag = null
        val act = activity ?: run {
            lastDiag = "无前台 Activity"
            return false
        }
        return try {
            val before = probeAlbum(act)
            val (pressed, why) = onMain { longPressBiggestImage(act) }.let { it ?: (false to "主线程 3s 未响应") }
            if (!pressed) {
                finish(module, noteId, false, "长按没发出去（$why）")
                return false
            }
            val rootsDiag = ArrayList<String>()
            // WindowManagerGlobal 走不通时兜底用的 Dialog 根视图：BFS 只在主线程跑一次，别每次轮询都扫对象图
            val dialogRoots = ArrayList<View>(2)
            var clicked = false
            val panelDeadline = System.currentTimeMillis() + PANEL_WAIT_MS
            while (System.currentTimeMillis() < panelDeadline && !clicked && canContinue()) {
                Thread.sleep(POLL_STEP_MS)
                clicked = onMain { clickSaveLabel(act, rootsDiag, dialogRoots) } == true
            }
            if (!clicked && !canContinue()) {
                finish(module, noteId, false, "已暂停/取消，放弃兜底")
                return false
            }
            if (!clicked) {
                val closeNote = dismissPanelIfOpen(act)
                finish(module, noteId, false,
                    "面板里没找到保存按钮（${rootsDiag.firstOrNull() ?: "根视图枚举为空"}）", closeNote)
                return false
            }
            var saved = false
            val saveDeadline = System.currentTimeMillis() + SAVE_WAIT_MS
            while (System.currentTimeMillis() < saveDeadline && !saved && canContinue()) {
                Thread.sleep(POLL_STEP_MS)
                val now = probeAlbum(act)
                val scopedGrew = now.scoped != null && before.scoped != null && now.scoped > before.scoped
                val allGrew = now.all != null && before.all != null && now.all > before.all
                // 全量涨了但不能确认是宿主写的（最新一张查得出是别的 App）就不算成功
                if (scopedGrew || (allGrew && newestImageIsHost(act) != false)) saved = true
            }
            // 记一笔宿主口径的落盘：下一批同一篇不再重复长按（否则相册里会多一张内容相同的图）
            if (saved && noteId != null) runCatching { XhsDownloadLedger.recordHostSaved(act, noteId) }
            // 保存成功后面板一般自己收掉；还在就关掉，否则驱动按返回时会被面板吃掉一次返回
            // （[XhsBatchDriver.backToList] 只按一次返回，被面板接走就判「没回到列表」收工）。
            val closeNote = dismissPanelIfOpen(act)
            finish(module, noteId, saved,
                if (!before.usable) "相册查询不可用，无法确认是否落盘"
                else if (saved) "相册已新增一张（宿主面板口径，单张压缩图）"
                else "点了保存但 10s 内相册没有新图",
                closeNote)
            saved
        } catch (t: Throwable) {
            lastDiag = "异常 ${t.javaClass.simpleName}: ${t.message}"
            runCatching { module.log(Log.WARN, TAG, "$JUDGE_PREFIX 宿主兜底异常（已吞）: ${t.message}") }
            false
        }
    }

    private fun finish(module: XposedModule, noteId: String?, ok: Boolean, why: String, extra: String? = null) {
        lastDiag = if (extra.isNullOrBlank()) why else "$why · $extra"
        runCatching {
            module.log(
                if (ok) Log.INFO else Log.WARN,
                TAG,
                "$JUDGE_PREFIX ${if (ok) "长按保存成功" else "长按保存未成"} noteId=${noteId ?: "未知"} · ${lastDiag}"
            )
        }
    }

    // ── 相册判据 ──

    /**
     * 一次相册探针：`scoped` = 宿主写的那批图的最大 _id，`all` = 全量最大 _id；
     * 两者都是 -1「表里没有图」/ null「这条查询不被支持」（OEM 砍列）。
     */
    private class AlbumProbe(val scoped: Long?, val all: Long?) {
        val usable: Boolean get() = scoped != null || all != null
    }

    /**
     * 相册探针（两种口径一起取）。
     *
     * 判据是「10s 内相册 _id 变大」，全量口径下这段时间里**别的 App** 存一张图就会被误判成本篇保存成功
     * ——假阳性把失败的篇记成成功，比漏判更糟。所以优先看「宿主写的那批」（`owner_package_name` 或
     * 目录名含「小红书」）；宿主自己扫描入库、这两列可能空着时，再用全量增长 + 最新一张的归属来裁。
     */
    private fun probeAlbum(ctx: Context): AlbumProbe = AlbumProbe(
        scoped = queryMaxImageId(ctx, "$OWNER_PACKAGE_NAME=? OR $RELATIVE_PATH LIKE ?",
            arrayOf(XHS_PACKAGE, "%小红书%"))
            ?: queryMaxImageId(ctx, "$RELATIVE_PATH LIKE ?", arrayOf("%小红书%")),
        all = queryMaxImageId(ctx, null, null),
    )

    /**
     * 最新一张图片是不是宿主存的：true 是 / false 不是别的 App / null 判不出（两列都空 = 宿主直接写文件后扫描入库）。
     * 只在「全量增长了但宿主过滤没增长」时用一次。
     */
    private fun newestImageIsHost(ctx: Context): Boolean? = runCatching {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(OWNER_PACKAGE_NAME, RELATIVE_PATH),
            null, null,
            "${MediaStore.Images.Media._ID} DESC",
        )?.use { c ->
            if (!c.moveToFirst()) return@use null
            val owner = runCatching { c.getString(0) }.getOrNull()
            val rel = runCatching { c.getString(1) }.getOrNull()
            when {
                owner == XHS_PACKAGE -> true
                rel?.contains("小红书") == true -> true
                owner.isNullOrBlank() && rel.isNullOrBlank() -> null
                else -> false
            }
        }
    }.getOrNull()

    /** null = 这条查询不被支持/抛异常；-1 = 通得过但表里没有图。 */
    private fun queryMaxImageId(ctx: Context, selection: String?, args: Array<String>?): Long? = runCatching {
        ctx.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID),
            selection, args,
            "${MediaStore.Images.Media._ID} DESC"
        )?.use { c -> if (c.moveToFirst()) c.getLong(0) else -1L } ?: -1L
    }.getOrNull()

    // ── 主线程动作 ──

    /** 长按详情页里面积最大的图片：真实 DOWN → 600ms → UP 走宿主自己的手势识别（不用 performLongClick，
     * 宿主的长按监听挂在容器/GestureDetector 上，直接调 ImageView 的反而可能没人接）。 */
    private fun longPressBiggestImage(act: Activity): Pair<Boolean, String> {
        val decor = act.window?.decorView ?: return false to "无 decorView"
        val target = biggestImage(decor) ?: return false to "屏内没有够大的图片视图"
        val loc = IntArray(2)
        target.getLocationInWindow(loc)
        // 判定「中心点落在窗口内」而不是「左上角非负」：半出屏（轮播滑到一半）的图左上角可能为负，
        // 但中心仍在屏内、照样能长按。整张在屏外才放弃。
        val x = loc[0] + target.width / 2f
        val y = loc[1] + target.height / 2f
        val decorH = decor.height.coerceAtLeast(1)
        val decorW = decor.width.coerceAtLeast(1)
        if (x < 0 || y < 0 || x >= decorW || y >= decorH) return false to "图片中心不在窗口内"
        val t0 = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(t0, t0, MotionEvent.ACTION_DOWN, x, y, 0)
        runCatching { decor.dispatchTouchEvent(down) }
        down.recycle()
        mainHandler.postDelayed({
            val up = MotionEvent.obtain(t0, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, x, y, 0)
            runCatching { decor.dispatchTouchEvent(up) }
            up.recycle()
        }, LONG_PRESS_HOLD_MS)
        return true to "${target.javaClass.simpleName}@(${x.toInt()},${y.toInt()})"
    }

    /** 屏内可见、面积最大的图片类视图（Fresco 的 DraweeView / 各种 PhotoView 都按类名认）。 */
    private fun biggestImage(root: View): View? {
        var best: View? = null
        var bestArea = 0
        val screenArea = root.width.coerceAtLeast(1) * root.height.coerceAtLeast(1)
        walk(root, 0) { v ->
            if (!v.isShown || v.width <= 0 || v.height <= 0) return@walk
            val name = v.javaClass.simpleName
            // 只认图片类：ImageView 子类（含 Drawee/Photo/Scale/PhotoView 命名）+ 名称里带 Image 的
            if (v !is android.widget.ImageView && !name.contains("Image") && !name.contains("Drawee")) return@walk
            val area = v.width * v.height
            // 太小的（头像/图标）不算"这张笔记的图"
            if (area * 6 < screenArea) return@walk
            if (area > bestArea) {
                bestArea = area
                best = v
            }
        }
        return best
    }

    /** 在所有根视图里找「保存」并点它；找到就把根视图枚举情况写进 diag（只写第一次）。 */
    private fun clickSaveLabel(act: Activity, diag: MutableList<String>, dialogRoots: MutableList<View>): Boolean {
        // 面板在独立 Window（Dialog）里：先只找非 decor 的根，避免把详情页正文里出现的「保存」字样
        // 当成面板按钮（点错的后果不可控）。decor 放最后一轮。
        val decor = act.window?.decorView
        val roots = rootsOf(act, diag)
        // mViews 反射被 hidden API 拦下时只剩 decor 一个根：改从 Activity 对象图里捞 Dialog 的窗口根视图。
        // 只在这一次真的走投无路时跑 BFS（主线程 100~300ms），结果缓存给后续轮询复用。
        if (roots.size <= 1 && dialogRoots.isEmpty()) dialogRoots.addAll(dialogRootsAround(act))
        val ordered = (roots + dialogRoots).distinct().sortedBy { if (it === decor) 1 else 0 }
        for (r in ordered) {
            val hit = findSaveView(r) ?: continue
            // 先点可点的祖先（宿主的条目通常只在外层挂 click 监听）
            var p: View? = hit
            repeat(4) {
                val cur = p
                if (cur != null && cur.isClickable && cur.performClick()) return true
                p = cur?.parent as? View
            }
            if (hit.performClick()) return true
            // 宿主用触摸监听而不是 click 监听时，补一次坐标点击
            if (tapByTouch(hit)) return true
        }
        return false
    }

    private fun findSaveView(root: View): View? {
        var exact: View? = null
        var prefix: View? = null
        walk(root, 0) { v ->
            if (!v.isShown) return@walk
            val text = (v as? TextView)?.text?.toString()?.trim()
                ?: v.contentDescription?.toString()?.trim().orEmpty()
            if (text.isEmpty() || text.length > 12) return@walk
            if (NEGATIVE_LABELS.any { text.contains(it) }) return@walk
            when {
                text == "保存" -> if (exact == null) exact = v
                SAVE_LABELS.any { text == it || text.startsWith(it) } -> if (prefix == null) prefix = v
            }
        }
        return exact ?: prefix
    }

    /**
     * 全部窗口的根视图：Activity decor + WindowManagerGlobal.mViews（Dialog/Popup 的窗口）。
     * 反射被 hidden API 拦了就只用 decor —— 面板在 Dialog 里时这里找不到保存按钮，兜底会记「没找到」。
     */
    private fun rootsOf(act: Activity, diag: MutableList<String>): List<View> {
        val out = ArrayList<View>(4)
        act.window?.decorView?.let { out.add(it) }
        runCatching {
            val c = Class.forName("android.view.WindowManagerGlobal")
            val inst = c.getMethod("getInstance").invoke(null)
            val f = c.getDeclaredField("mViews").apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val views = f.get(inst) as? List<View>
            views.orEmpty().forEach { if (!out.contains(it)) out.add(it) }
        }.onFailure { if (diag.isEmpty()) diag.add("WindowManagerGlobal 反射失败 ${it.javaClass.simpleName}") }
        if (diag.isEmpty()) diag.add("根视图 ${out.size} 个")
        return out
    }

    /**
     * 面板还开着就把它收掉。
     *
     * 为什么必须收：驱动回列表只按**一次**返回（[XhsBatchDriver.backToList]），这次返回若被面板接走，
     * 指纹复核就判「没回到列表」→ 收工。而 [XhsBatchDriver.pressBack] 打的是 **Activity 的窗口**，
     * 对挂在独立 Window 上的面板 Dialog 不起作用（这条链上只有 View 级派发，没有输入注入），
     * 所以只能我们在主线程上自己找 Dialog 调 dismiss。
     *
     * 顺序：① 先等宿主自己关（保存成功后宿主通常自己 dismiss）② 从 Activity 起按字段 BFS 找 Dialog
     * （宿主面板是 `LCBBottomSheetDialog` 一类，实例挂在 controller/ViewModel 字段里，不在视图树里）。
     * 兜底没有「猜路径」的余地：找不到就只记诊断，这一次兜底仍算成功（图已经进相册），
     * 最坏后果是本轮提前收工，不会误存别的东西。
     */
    private fun dismissPanelIfOpen(act: Activity): String? {
        var note: String? = null
        runCatching {
            val closeDeadline = System.currentTimeMillis() + PANEL_CLOSE_WAIT_MS
            while (System.currentTimeMillis() < closeDeadline) {
                val still = onMain { rootsOf(act, ArrayList()).any { findSaveView(it) != null } } == true
                if (!still) return null
                Thread.sleep(POLL_STEP_MS)
            }
            val dismissed = onMain { dismissDialogAround(act) } == true
            if (!dismissed) {
                note = "面板未关"
                runCatching { Log.w(TAG, "$JUDGE_PREFIX 长按面板没能关掉（未在 Activity 对象图里找到 Dialog）") }
            }
        }
        return note
    }

    /**
     * 从 Activity 起按字段 BFS 找正在显示的 Dialog 并 dismiss（上限：深度 5 / 600 个对象 / 每对象 48 字段）。
     * 口径与 [XhsBatchSave.dismissHostPanel] 一致：按**类型**认，不赌混淆字段名。
     */
    private fun dismissDialogAround(root: Activity): Boolean {
        val queue = ArrayDeque<Any?>()
        queue.add(root)
        val seen = HashSet<Int>()
        var scanned = 0
        var depth = 0
        while (queue.isNotEmpty() && depth < 5) {
            val layer = queue.toList()
            queue.clear()
            for (node in layer) {
                if (node == null) continue
                if (!seen.add(System.identityHashCode(node))) continue
                if (++scanned > 600) return false
                if (node is android.app.Dialog) {
                    if (runCatching { node.isShowing }.getOrDefault(false)) {
                        return runCatching { node.dismiss() }.isSuccess
                    }
                    continue
                }
                val fields = runCatching { node.javaClass.declaredFields }.getOrNull() ?: continue
                var n = 0
                for (f in fields) {
                    if (++n > 48) break
                    val t = f.type
                    if (t.isPrimitive || t == String::class.java || t == Class::class.java) continue
                    val v = runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue
                    queue.add(v)
                }
            }
            depth++
        }
        return false
    }

    /**
     * 从 Activity 对象图里找「正在显示的 Dialog」，返回它们的窗口根视图——`WindowManagerGlobal`
     * 反射被 hidden API 拦下时的退路（见 [rootsOf]）。宿主面板是 `LCBBottomSheetDialog` 一类，
     * 实例挂在 controller/ViewModel 字段里、不在视图树上，视图树里找不到它。
     * 口径与 [dismissDialogAround] 一致：按**类型**认，不赌混淆字段名。
     */
    private fun dialogRootsAround(root: Activity): List<View> {
        val out = ArrayList<View>(2)
        val queue = ArrayDeque<Any?>()
        queue.add(root)
        val seen = HashSet<Int>()
        var scanned = 0
        var depth = 0
        while (queue.isNotEmpty() && depth < 5 && out.size < 4) {
            val layer = queue.toList()
            queue.clear()
            for (node in layer) {
                if (node == null) continue
                if (!seen.add(System.identityHashCode(node))) continue
                if (++scanned > 600) return out
                if (node is android.app.Dialog) {
                    if (runCatching { node.isShowing }.getOrDefault(false)) {
                        node.window?.decorView?.let { if (!out.contains(it)) out.add(it) }
                    }
                    continue
                }
                val fields = runCatching { node.javaClass.declaredFields }.getOrNull() ?: continue
                var n = 0
                for (f in fields) {
                    if (++n > 48) break
                    val t = f.type
                    if (t.isPrimitive || t == String::class.java || t == Class::class.java) continue
                    val v = runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue
                    queue.add(v)
                }
            }
            depth++
        }
        return out
    }

    private fun tapByTouch(v: View): Boolean {
        val x = v.width / 2f
        val y = v.height / 2f
        val t = SystemClock.uptimeMillis()
        val d = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
        val u = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
        return try {
            val a = v.dispatchTouchEvent(d)
            val b = v.dispatchTouchEvent(u)
            a || b
        } finally {
            d.recycle(); u.recycle()
        }
    }

    private fun walk(root: View?, depth: Int, visit: (View) -> Unit) {
        if (root == null || depth > 40) return
        visit(root)
        if (root is ViewGroup) for (i in 0 until root.childCount) walk(root.getChildAt(i), depth + 1, visit)
    }

    /**
     * 在宿主主线程执行（3s 超时；超时不阻塞、不抛）。
     * 复用驱动同一套「不能用队列传 null」的教训：这里用简单的数组盒子，不投 null。
     */
    private fun <T> onMain(f: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return runCatching { f() }.getOrNull()
        var box: T? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        val posted = mainHandler.post {
            try {
                box = runCatching { f() }.getOrNull()
            } finally {
                latch.countDown()
            }
        }
        if (!posted) return null
        return if (latch.await(3, java.util.concurrent.TimeUnit.SECONDS)) box else null
    }
}
