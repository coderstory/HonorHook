package top.honorhook.ui.xhsbatch

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.data.xhs.DefaultXhsContentRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * 批量下载编排（技术方案 §2.2/§3）：单 Job 单调用链真串行；暂停在文件边界；
 * 撤回 = cancel + 递归删目录。UI 只订阅 [progress]。
 *
 * 2026-11-11 重构（用户「下载逻辑乱/慢」）：解析失败分类分流（坏笔记跳过不停批）、
 * 退避改纯函数（不再藏 finish 副作用）、失败篇可单独重试（retryFailed）、
 * 媒体间隔收紧 200-500ms（CDN 直链低风险）。篇间 2-4s 与单任务串行红线不动。
 */
class XhsBatchController(
    private val scope: CoroutineScope,
    private val taskStore: XhsBatchTaskStore,
    private val feedClient: XhsFeedClient = XhsUserFeedClient(),
    private val downloader: XhsDownloader = XhsSerialDownloader(),
    /** WebView 宿主 Context（只透传 feedClient；可空仅为 JVM 单测缝）。 */
    private val appContext: android.content.Context?,
    private val random: Random = Random,
    /** 单篇解析缝（JVM 单测注入 fake）；默认走 DefaultXhsContentRepository + fetchHtmlDirect。 */
    private val noteResolver: NoteResolver = WebViewNoteResolver,
    /** 下载根目录缝（JVM 单测注入临时目录）；默认对齐 XhsBatchSave 的 Pictures/小红书。 */
    private val rootOverride: File? = null,
) {

    /** 单篇解析结果分类（重构核心）：坏笔记（页面拿到但解析不出）与限流（网络）分流。 */
    sealed interface ResolveOutcome {
        data class Ok(val note: ResolvedNote) : ResolveOutcome

        /** 页面拿到了（html ≥ [HTML_BAD_NOTE_MIN_BYTES]）但解析不出：验证页/删帖/解析器不识别 → 篇级失败跳过。 */
        data class BadNote(val html: String?) : ResolveOutcome

        /** 网络失败/风控限流（IO 异常、非 2xx、壳页）→ 退避重试。 */
        data object Throttled : ResolveOutcome
    }

    /** 判定「页面拿到了」的字节阈值：低于此值视为网络/风控壳页，走退避而非跳过。（值在 companion object） */

    /** 单篇解析：[cookie] 来自网页会话（XhsFeedClient.currentCookie）。 */
    interface NoteResolver {
        suspend fun resolve(url: String, cookie: String): ResolveOutcome
    }

    /** 默认实现：上游仓库解析（fetchHtml 注入 = HttpURLConnection 直连）。 */
    private object WebViewNoteResolver : NoteResolver {
        override suspend fun resolve(url: String, cookie: String): ResolveOutcome {
            XhsFetchLog.w("resolve begin ${url.take(120)}")
            var htmlLen = 0
            return try {
                val note = DefaultXhsContentRepository(fetchHtml = { u ->
                    fetchHtmlDirect(u, cookie)?.also {
                        htmlLen = it.length
                        XhsFetchLog.w("resolve html ${it.length}B")
                    } ?: run { XhsFetchLog.w("resolve html null"); null }
                }).resolve(url)
                XhsFetchLog.w("resolve ok media=${note.orderedMedia.size}")
                ResolveOutcome.Ok(note)
            } catch (t: Throwable) {
                XhsFetchLog.w("resolve fail: $t")
                // 分类：拿到了完整页面还解析失败 = 坏笔记（验证页/删帖/解析器不识别）；
                // 没拿到/壳页 = 网络/限流 → 退避
                if (htmlLen >= HTML_BAD_NOTE_MIN_BYTES) ResolveOutcome.BadNote(null)
                else ResolveOutcome.Throttled
            }
        }
    }

    private val _progress = MutableStateFlow(XhsBatchProgress())
    val progress: StateFlow<XhsBatchProgress> = _progress

    private var job: Job? = null
    @Volatile private var paused = false
    private val taskId = AtomicLong(0)

    /** 当前任务上下文（目录、记录、userId）。 */
    private var activeDir: File? = null
    private var activeUserId: String? = null
    private var activeRecordId: String? = null
    private var activeCreatedAt: Long = 0
    // 断点续传（SDD 2026-11-11）：恢复时从记录解出清单与游标，抓取阶段直接跳过
    private var activeNotes: List<XhsUserFeedClient.FeedNote> = emptyList()
    private var resumeIndex: Int = 0
    // 本轮是否来自记录续跑（决定 token 失效时是否允许重抓刷新；新任务不走刷新，退避耗尽即停）
    private var resumedFromStore: Boolean = false
    private var tokenRefreshed = false // 每批只刷一次：清单 token 同批同效，逐篇重抓是风暴
    // 失败重试（2026-11-11）：失败篇集合，成功即移除；持久化进 failedNotes
    private var activeFailedNotes: MutableSet<String> = mutableSetOf()
    // retryFailed 模式：只跑失败子集，不覆盖 notesFound 显示
    private var retrySubsetMode: Boolean = false

    fun isRunning(): Boolean = job?.isActive == true

    /** 可续跑的终态记录（Withdrawn 无文件，等同新建）。 */
    private val RESUMABLE = setOf(
        XhsBatchState.Queued, XhsBatchState.Interrupted, XhsBatchState.Failed, XhsBatchState.Done, XhsBatchState.Cancelled,
    )

    // 待执行队列（2026-11-11 用户裁决）：运行中允许添加任务，新任务排队，当前结束自动开跑
    // （同一时刻仍只有一个任务在跑，不破坏串行防风控）
    private val pendingQueue = ArrayDeque<String>()
    fun hasQueued(): Boolean = pendingQueue.isNotEmpty()
    fun queuedUserIds(): List<String> = pendingQueue.toList()

    /** 取消排队：移出队列并把记录置 Cancelled（不动文件）。 */
    fun cancelQueued(userId: String) {
        if (pendingQueue.remove(userId)) {
            taskStore.list().lastOrNull { it.userId == userId && it.state == XhsBatchState.Queued }?.let { r ->
                taskStore.upsert(r.copy(state = XhsBatchState.Cancelled))
            }
        }
    }

    /** 入口公共门：ID 校验 + 存储权限。通过返回 null，否则返回给用户的提示文案。 */
    private fun entryGate(id: String): String? {
        if (!id.matches(Regex("[0-9a-fA-F]{24}|[A-Za-z0-9_]{2,24}"))) {
            return "请输入合法的小红书号或用户 ID"
        }
        // 下载落 Pictures/小红书/（公共目录）：targetSdk 34 必须「所有文件访问」权限，
        // 未授予时逐文件 IO 全挂（2026-11-10 实测 30 篇全失败）。appContext == null 是 JVM 单测缝
        if (appContext != null && !android.os.Environment.isExternalStorageManager()) {
            return "需要「所有文件访问」权限：设置 → 应用 → HonorHook → 权限 → 文件和媒体"
        }
        return null
    }

    /** 从记录恢复任务上下文（start 与 retryFailed 共用）。 */
    private fun adoptPrior(prior: XhsBatchTaskRecord, id: String) {
        activeCreatedAt = prior.createdAt
        activeRecordId = prior.taskId
        activeFailedNotes = prior.failedNotes.toMutableSet()
        _progress.value = XhsBatchProgress(
            userId = id,
            state = XhsBatchState.Fetching,
            currentAction = "继续抓取笔记列表…",
            notesFound = prior.notesFound,
            notesProcessed = prior.notesProcessed,
            notesFailed = prior.notesFailed,
            imagesDone = prior.imagesDone,
            videosDone = prior.videosDone,
            totalBytes = prior.totalBytes,
        )
    }

    fun start(userId: String) {
        val id = userId.trim()
        entryGate(id)?.let { msg ->
            _progress.value = _progress.value.copy(state = XhsBatchState.Failed, currentAction = msg)
            return
        }
        if (isRunning()) {
            // 排队：已有任务在跑（不重复排同一 userId）
            if (pendingQueue.contains(id) || activeUserId == id) return
            pendingQueue.addLast(id)
            val now = System.currentTimeMillis()
            taskStore.upsert(
                XhsBatchTaskRecord(
                    taskId = "q${now}-${taskId.incrementAndGet()}", userId = id,
                    createdAt = now, updatedAt = now, state = XhsBatchState.Queued,
                    notesFound = 0, notesProcessed = 0, notesFailed = 0,
                    imagesDone = 0, videosDone = 0, totalBytes = 0L,
                    dirPath = downloaderRoot().resolve(id).path,
                ),
            )
            _progress.value = _progress.value.copy(currentAction = "已加入待执行队列（第 ${pendingQueue.size} 位）")
            return
        }
        beginTask(id) { prior ->
            if (prior != null && prior.state in RESUMABLE) {
                // 真断点续传：清单在 → 不再抓取，直接从游标续（Q1/Q2/Q5 裁决）
                activeNotes = prior.notes.mapNotNull { line ->
                    line.split('|', limit = 2).takeIf { it.size == 2 }?.let { (a, b) ->
                        XhsUserFeedClient.FeedNote(a, b)
                    }
                }
                resumeIndex = prior.nextIndex.coerceIn(0, maxOf(activeNotes.size - 1, 0))
                resumedFromStore = true
                adoptPrior(prior, id)
            } else {
                activeCreatedAt = System.currentTimeMillis()
                activeRecordId = "t${activeCreatedAt}-${taskId.incrementAndGet()}"
                activeFailedNotes = mutableSetOf()
                _progress.value = XhsBatchProgress(userId = id, state = XhsBatchState.Fetching, currentAction = "正在抓取笔记列表…")
            }
        }
    }

    /** 失败重试：只重跑记录里 failedNotes 子集（不抓列表；文件去重照旧）。 */
    fun retryFailed(userId: String) {
        val id = userId.trim()
        entryGate(id)?.let { msg ->
            _progress.value = _progress.value.copy(state = XhsBatchState.Failed, currentAction = msg)
            return
        }
        if (isRunning()) {
            _progress.value = _progress.value.copy(currentAction = "有任务在跑，失败重试请稍后或排队")
            return
        }
        val prior = taskStore.list().lastOrNull { it.userId == id }
        if (prior == null || prior.failedNotes.isEmpty()) {
            _progress.value = _progress.value.copy(state = _progress.value.state, currentAction = "没有失败资源可重试")
            return
        }
        beginTask(id) { _ ->
            val failed = prior.failedNotes.toSet()
            activeNotes = prior.notes.mapNotNull { line ->
                line.split('|', limit = 2).takeIf { it.size == 2 }?.let { (a, b) ->
                    XhsUserFeedClient.FeedNote(a, b)
                }
            }.filter { it.noteId in failed }
            resumeIndex = 0
            resumedFromStore = true
            retrySubsetMode = true
            adoptPrior(prior, id)
            _progress.value = _progress.value.copy(
                currentAction = "重试 ${activeNotes.size} 篇失败资源…",
                notesFound = prior.notesFound, // 子集模式不缩总量显示
            )
        }
    }

    /** 任务启动共用骨架：置目录/状态 → 持久化 → 开跑。 */
    private fun beginTask(id: String, init: (XhsBatchTaskRecord?) -> Unit) {
        paused = false
        retrySubsetMode = false
        tokenRefreshed = false
        activeUserId = id
        val dir = downloaderRoot().resolve(id)
        activeDir = dir
        init(taskStore.list().lastOrNull { it.userId == id })
        persist(XhsBatchState.Fetching)
        job = scope.launch { runBatch(dir) }
    }

    fun pause() {
        if (!isRunning()) return
        paused = true
        _progress.value = _progress.value.copy(state = XhsBatchState.Paused, currentAction = "已暂停（当前文件完成后停下）")
        persist(XhsBatchState.Paused)
    }

    fun resumeRun() {
        if (!isRunning()) return
        paused = false
        _progress.value = _progress.value.copy(state = XhsBatchState.Running)
        persist(XhsBatchState.Running)
    }

    /** 取消：停止任务，已下载文件保留（PRD F8）。 */
    fun cancel() {
        val j = job
        j?.cancel()
        job = null
        _progress.value = _progress.value.copy(state = XhsBatchState.Cancelled, currentAction = "已取消（文件已保留）")
        persist(XhsBatchState.Cancelled)
        startNextIfAny() // 队列还有待执行任务则自动开跑（用户已排队，视为愿意继续）
    }

    /** 撤回 = 取消 + 删除本次整个 userId 目录（PRD F9）。返回 (删除文件数, 失败数)。 */
    suspend fun withdraw(): Pair<Int, Int> {
        val j = job
        j?.cancel()
        job = null
        // join 后再删：否则在途协程还在写 .part/rename，删完被重建（审计 P1-2）
        j?.join()
        _progress.value = _progress.value.copy(state = XhsBatchState.Cancelled)
        val dir = activeDir
        return if (dir != null && dir.isDirectory) {
            val result = withContext(Dispatchers.IO) { downloader.deleteRecursivelyCounted(dir) }
            _progress.value = _progress.value.copy(state = XhsBatchState.Withdrawn, currentAction = "已撤回：删除 ${result.first} 个文件")
            persist(XhsBatchState.Withdrawn)
            startNextIfAny()
            result
        } else {
            0 to 0
        }
    }

    // ---- 主管线 ----

    private suspend fun runBatch(dir: File) {
        val userId = activeUserId ?: return
        try {
            val notes: List<XhsUserFeedClient.FeedNote>
            if (activeNotes.isNotEmpty()) {
                notes = activeNotes
                if (!retrySubsetMode) {
                    _progress.value = _progress.value.copy(
                        notesFound = notes.size, state = XhsBatchState.Running,
                        currentAction = "从第 ${resumeIndex + 1} 篇继续",
                    )
                } else {
                    _progress.value = _progress.value.copy(state = XhsBatchState.Running)
                }
            } else {
                val fetched = feedClient.fetchNotes(appContext, userId) { found ->
                    _progress.value = _progress.value.copy(notesFound = found, currentAction = "已抓到 $found 篇")
                }
                notes = fetched
                activeNotes = fetched
                resumeIndex = 0
                resumedFromStore = false
            }
            if (notes.isEmpty()) {
                finish(XhsBatchState.Failed, "列表为空（可能需要网页登录）")
                return
            }
            if (!retrySubsetMode) {
                _progress.value = _progress.value.copy(notesFound = notes.size, state = XhsBatchState.Running)
            }
            persist(XhsBatchState.Running)

            val cookie = feedClient.currentCookie()
            for ((index, note) in notes.withIndex()) {
                if (job?.isActive != true) return
                if (index < resumeIndex) continue
                waitForUnpause()
                _progress.value = _progress.value.copy(
                    currentAction = "正在处理第 ${index + 1}/${notes.size} 篇：${note.noteId}",
                )
                when (val outcome = obtainResolved(note, cookie)) {
                    NoteOutcome.Cancelled -> return
                    NoteOutcome.Skip -> {
                        // 坏笔记：已计失败 + 留痕，直接下一篇（不退避不停批）
                        delay(XhsBatchPacing.jitter(XhsBatchPacing.NOTE_DELAY_MIN_MS, XhsBatchPacing.NOTE_DELAY_MAX_MS, random))
                        continue
                    }
                    NoteOutcome.StopBatch -> return // finish(Failed) 已由 obtainResolved 落账
                    is NoteOutcome.Resolved -> {
                        val (allOk, didWork) = resolveAndDownload(outcome.note, dir)
                        if (didWork) {
                            _progress.value = _progress.value.copy(notesProcessed = _progress.value.notesProcessed + 1)
                            if (!allOk) _progress.value = _progress.value.copy(notesFailed = _progress.value.notesFailed + 1)
                        }
                        persist(_progress.value.state, index + 1)
                        delay(XhsBatchPacing.jitter(XhsBatchPacing.NOTE_DELAY_MIN_MS, XhsBatchPacing.NOTE_DELAY_MAX_MS, random))
                    }
                }
            }
            finish(XhsBatchState.Done, "完成：${_progress.value.imagesDone} 图 ${_progress.value.videosDone} 视频 · ${mbOf()}")
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t // cancel 已置状态，这里只负责穿透
        } catch (t: Throwable) {
            XhsFetchLog.w("runBatch fatal: $t")
            finish(XhsBatchState.Failed, "异常终止：${t.message}")
        }
    }

    /** 单篇总入口：解析分类 + 退避 + token 刷新一次（每批一次）+ 坏笔记落账。终态自管（StopBatch 时已完成 finish）。 */
    private suspend fun obtainResolved(
        note: XhsUserFeedClient.FeedNote,
        cookie: String,
    ): NoteOutcome {
        when (val r = resolveWithBackoff(note, cookie)) {
            ResolveStep.Cancelled -> return NoteOutcome.Cancelled
            is ResolveStep.Done -> return NoteOutcome.Resolved(r.note)
            ResolveStep.BadNote -> {
                markNoteFailed(note.noteId)
                return NoteOutcome.Skip
            }
            ResolveStep.Exhausted -> Unit // 落到下面的 token 刷新
        }
        // Q3 裁决：token 有时效，恢复场景 403 高发 → 重抓列表刷新 token 重试一次（每批一次，逐篇重抓是风暴）
        if (resumedFromStore && !tokenRefreshed) {
            tokenRefreshed = true
            XhsFetchLog.w("resume token refresh: ${note.noteId}")
            activeNotes = feedClient.fetchNotes(appContext, activeUserId ?: "") { found ->
                _progress.value = _progress.value.copy(notesFound = found)
            }
            val refreshed = activeNotes.firstOrNull { it.noteId == note.noteId } ?: note
            _progress.value = _progress.value.copy(state = XhsBatchState.Running, currentAction = "token 已刷新，重试本篇")
            when (val r2 = resolveWithBackoff(refreshed, cookie)) {
                is ResolveStep.Done -> return NoteOutcome.Resolved(r2.note)
                ResolveStep.Cancelled -> return NoteOutcome.Cancelled
                else -> Unit
            }
        }
        finish(XhsBatchState.Failed, "连续失败，退避耗尽，已停止（已下载文件保留）")
        return NoteOutcome.StopBatch
    }

    /** 单篇总出口（runBatch 消费）。 */
    private sealed interface NoteOutcome {
        data class Resolved(val note: ResolvedNote) : NoteOutcome

        /** 坏笔记：已计失败+留痕，跳过继续。 */
        data object Skip : NoteOutcome

        /** 退避耗尽（含刷新后仍失败）：已 finish(Failed)，批停。 */
        data object StopBatch : NoteOutcome
        data object Cancelled : NoteOutcome
    }

    /**
     * 解析 + 限流退避（PRD F11）：Throttled → 30s/60s/120s 重试；BadNote 不重试直接返回。
     * 纯解析层：不停批（停批决策在 obtainResolved）。
     */
    private suspend fun resolveWithBackoff(note: XhsUserFeedClient.FeedNote, cookie: String): ResolveStep {
        for (attempt in XhsBatchPacing.BACKOFF_MS.indices) {
            if (job?.isActive != true) return ResolveStep.Cancelled
            when (val outcome = runCatching { noteResolver.resolve(detailUrlOf(note), cookie) }
                .onFailure { XhsFetchLog.w("resolver threw: $it") }
                .getOrDefault(ResolveOutcome.Throttled)) {
                is ResolveOutcome.Ok -> return ResolveStep.Done(outcome.note)
                is ResolveOutcome.BadNote -> {
                    XhsFetchLog.w("bad note ${note.noteId}: dump & skip")
                    dumpFailHtml(note.noteId, outcome.html)
                    return ResolveStep.BadNote
                }
                ResolveOutcome.Throttled -> {
                    if (attempt < XhsBatchPacing.BACKOFF_MS.size - 1) {
                        val wait = XhsBatchPacing.BACKOFF_MS[attempt]
                        _progress.value = _progress.value.copy(currentAction = "失败，退避 ${wait / 1000}s 后重试（第 ${attempt + 1} 次）")
                        delay(wait)
                        _progress.value = _progress.value.copy(currentAction = "重试中…")
                    }
                }
            }
        }
        return ResolveStep.Exhausted
    }

    /** 解析层结果（无副作用）。 */
    private sealed interface ResolveStep {
        data class Done(val note: ResolvedNote) : ResolveStep
        data object BadNote : ResolveStep
        data object Exhausted : ResolveStep
        data object Cancelled : ResolveStep
    }

    /** 篇级失败登记：计入 failedNotes（重试入口的数据源）与失败计数。 */
    private fun markNoteFailed(noteId: String) {
        activeFailedNotes.add(noteId)
        _progress.value = _progress.value.copy(
            notesProcessed = _progress.value.notesProcessed + 1,
            notesFailed = _progress.value.notesFailed + 1,
        )
        persist(_progress.value.state)
    }

    /** 成功篇从失败清单摘除（重试成功不再留污点）。 */
    private fun markNoteOk(noteId: String) {
        activeFailedNotes.remove(noteId)
    }

    /** 坏笔记留痕：html 落 /sdcard/Download/xhs_fail_<id>.html，最多 3 份（诊断验证页/删帖/解析器问题）。 */
    private fun dumpFailHtml(noteId: String, html: String?) {
        if (html.isNullOrBlank()) return
        runCatching {
            val dir = File("/sdcard/Download")
            if (!dir.isDirectory) return
            // 上限 3 份：按修改时间删最旧
            val olds = (dir.listFiles { f -> f.name.startsWith("xhs_fail_") } ?: emptyArray())
                .sortedBy { it.lastModified() }.toMutableList()
            while (olds.size >= 3) olds.removeAt(0).delete()
            File(dir, "xhs_fail_$noteId.html").writeText(html)
            XhsFetchLog.w("dump fail html note=$noteId bytes=${html.length}")
        }
    }

    /** 单篇详情 URL：xsec_token 必须透传，裸链接会 403/跳登录（技术方案 §2.1）。 */
    private fun detailUrlOf(note: XhsUserFeedClient.FeedNote): String = buildString {
        append("https://www.xiaohongshu.com/explore/").append(note.noteId)
        if (note.xsecToken.isNotBlank()) {
            append("?xsec_token=").append(note.xsecToken).append("&xsec_source=pc_user")
        }
    }

    /**
     * 已解析笔记的逐媒体下载。返回 (整篇是否全成, 是否有实际下载动作)。
     * didWork=false = 全部文件已在盘上（同名跳过）⇒ 续跑时不重复计篇。
     */
    private suspend fun resolveAndDownload(
        resolved: ResolvedNote,
        dir: File,
    ): Pair<Boolean, Boolean> = withContext(Dispatchers.IO) {
        var allOk = true
        var didWork = false
        XhsFetchLog.w("download begin ${resolved.noteId} media=${resolved.orderedMedia.size}")
        resolved.orderedMedia.forEachIndexed { i, media ->
            if (job?.isActive != true) return@withContext allOk to didWork
            waitForUnpause()
            val target = File(dir, fileNameOf(resolved, media, i))
            val url = downloadUrlOf(media) // 只算一次（旧实现下载失败分支重复调用）
            when (val r = downloader.download(url, target)) {
                is XhsSerialDownloader.Result.Ok -> {
                    if (r.bytes > 0) {
                        didWork = true
                        countByType(media, r.bytes)
                        // 相册可见性：写公共 Pictures 目录后必须触发 MediaStore 扫描，否则图库不索引
                        appContext?.let {
                            android.media.MediaScannerConnection.scanFile(
                                it, arrayOf(target.absolutePath), null, null,
                            )
                        }
                    }
                }
                is XhsSerialDownloader.Result.Failure -> {
                    // 单文件失败不弃整篇；篇级失败由本返回值聚合
                    XhsFetchLog.w("download fail: ${r.message} url=${url.take(80)}")
                    allOk = false
                    didWork = true
                }
            }
            delay(XhsBatchPacing.jitter(XhsBatchPacing.MEDIA_DELAY_MIN_MS, XhsBatchPacing.MEDIA_DELAY_MAX_MS, random))
        }
        if (allOk) markNoteOk(resolved.noteId) else activeFailedNotes.add(resolved.noteId)
        allOk to didWork
    }

    private suspend fun waitForUnpause() {
        while (paused && (job?.isActive == true)) delay(300)
    }

    // ---- 辅助 ----

    companion object {
        /** 判定「页面拿到了」的字节阈值：低于此值视为网络/风控壳页，走退避而非跳过。 */
        private const val HTML_BAD_NOTE_MIN_BYTES = 20_000

        private fun fetchHtmlDirect(url: String, cookie: String): String? = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
            )
            conn.setRequestProperty("Referer", "https://www.xiaohongshu.com/")
            if (conn.responseCode !in 200..299) {
                XhsFetchLog.w("detail http=${conn.responseCode}")
                return null
            }
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
    }

    /**
     * 原图地址：页面给的图片 URL 带 ！水印/压缩修饰（下载的是压缩图，2026-11-10 用户实锤）。
     * 复刻上游 XHSDownloader.transformXhsCdnUrl（reference/xhs，AGPL）：取 token 重构 ci.xiaohongshu.com 直链 = 原图。
     * 视频/livePhoto 直链不动。
     */
    private fun downloadUrlOf(media: ResolvedMedia): String {
        val url = media.sourceUrl
        if (media !is ResolvedMedia.Image || !url.contains("xhscdn.com")) return url
        if (url.contains("sns-video")) return url
        val parts = url.split('/')
        if (parts.size <= 5) return url
        val token = parts.drop(5).joinToString("/").substringBefore('!').substringBefore('?')
        return "https://ci.xiaohongshu.com/$token"
    }

    /** 平铺文件名：`<noteId>-<序号>.<ext>`（PRD F4：无子目录，前缀防跨篇重名）。 */
    private fun fileNameOf(note: ResolvedNote, media: ResolvedMedia, index: Int): String {
        val ext = guessExt(media)
        return "${note.noteId.takeIf { it.isNotBlank() } ?: "note"}-$index$ext"
    }

    private fun guessExt(media: ResolvedMedia): String = when (media) {
        is ResolvedMedia.Video -> ".mp4"
        is ResolvedMedia.LivePhoto -> ".mp4"
        else -> {
            // 图片从 URL 路径取扩展名，取不到回落 .jpg
            val path = media.sourceUrl.substringBefore('?').substringAfterLast('/')
            Regex("\\.(jpe?g|png|webp|gif|heic)", RegexOption.IGNORE_CASE)
                .find(path)?.value?.lowercase() ?: ".jpg"
        }
    }

    private fun countByType(media: ResolvedMedia, bytes: Long) {
        val p = _progress.value
        _progress.value = when (media) {
            is ResolvedMedia.Video, is ResolvedMedia.LivePhoto ->
                p.copy(videosDone = p.videosDone + 1, totalBytes = p.totalBytes + bytes)
            else -> p.copy(imagesDone = p.imagesDone + 1, totalBytes = p.totalBytes + bytes)
        }
    }

    private fun finish(state: XhsBatchState, action: String) {
        _progress.value = _progress.value.copy(state = state, currentAction = action)
        persist(state)
        startNextIfAny()
    }

    /** 当前任务终态后，若队列有待执行任务则自动开跑下一个（仍在 scope 上，串行保证）。 */
    private fun startNextIfAny() {
        val next = pendingQueue.removeFirstOrNull() ?: return
        val j = job
        scope.launch {
            j?.join() // 等旧任务协程真正退出，否则 isRunning() 仍为 true，下一个任务会被再次排队
            start(next)
        }
    }

    private fun persist(state: XhsBatchState, nextIndex: Int = 0) {
        val p = _progress.value
        val id = activeRecordId ?: return
        val dirPath = activeDir?.path ?: ""
        // Q4 裁决：撤回 = 清单一并清（Withdrawn 记录不留 notes）
        val notes = if (state == XhsBatchState.Withdrawn) emptyList() else activeNotes.map { "${it.noteId}|${it.xsecToken}" }
        taskStore.upsert(
            XhsBatchTaskRecord(
                taskId = id, userId = p.userId.ifBlank { activeUserId ?: "" },
                createdAt = activeCreatedAt, updatedAt = System.currentTimeMillis(),
                state = state,
                notesFound = p.notesFound, notesProcessed = p.notesProcessed, notesFailed = p.notesFailed,
                imagesDone = p.imagesDone, videosDone = p.videosDone, totalBytes = p.totalBytes,
                dirPath = dirPath,
                notes = notes, nextIndex = nextIndex,
                failedNotes = activeFailedNotes.toList(),
            ),
        )
    }

    private fun mbOf(): String = "%.1f MB".format(_progress.value.totalBytes / 1048576.0)

    /** 下载根目录：默认对齐 XhsBatchSave 现有外挂直下的 Pictures/小红书（技术方案 §2.3）；测试注入 rootOverride。 */
    private fun downloaderRoot(): File =
        rootOverride ?: java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES),
            "小红书",
        )
}
