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
 * 撤回 = cancel + 递归删目录；限流退避耗尽停批。UI 只订阅 [progress]。
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

    /** 单篇解析：[cookie] 来自网页会话（XhsFeedClient.currentCookie）。失败抛异常（上层退避）。 */
    fun interface NoteResolver {
        suspend fun resolve(url: String, cookie: String): ResolvedNote?
    }

    /** 默认实现：上游仓库解析（fetchHtml 注入 = HttpURLConnection 直连）；失败抛 XhsResolveException → 上层退避。 */
    private object WebViewNoteResolver : NoteResolver {
        override suspend fun resolve(url: String, cookie: String): ResolvedNote? =
            DefaultXhsContentRepository(fetchHtml = { fetchHtmlDirect(it, cookie) }).resolve(url)
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

    fun isRunning(): Boolean = job?.isActive == true

    fun start(userId: String) {
        val id = userId.trim()
        if (!id.matches(Regex("\\d+"))) {
            _progress.value = _progress.value.copy(state = XhsBatchState.Failed, currentAction = "用户 ID 必须是纯数字")
            return
        }
        if (isRunning()) return
        paused = false
        activeUserId = id
        activeCreatedAt = System.currentTimeMillis()
        activeRecordId = "t${activeCreatedAt}-${taskId.incrementAndGet()}"
        val dir = File(downloaderRoot(), "xhs").resolve(id)
        activeDir = dir
        _progress.value = XhsBatchProgress(userId = id, state = XhsBatchState.Fetching, currentAction = "正在抓取笔记列表…")
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
        job?.cancel()
        job = null
        _progress.value = _progress.value.copy(state = XhsBatchState.Cancelled, currentAction = "已取消（文件已保留）")
        persist(XhsBatchState.Cancelled)
    }

    /** 撤回 = 取消 + 删除本次整个 userId 目录（PRD F9）。返回 (删除文件数, 失败数)。 */
    suspend fun withdraw(): Pair<Int, Int> {
        cancel()
        val dir = activeDir
        return if (dir != null && dir.isDirectory) {
            val result = withContext(Dispatchers.IO) { downloader.deleteRecursivelyCounted(dir) }
            _progress.value = _progress.value.copy(state = XhsBatchState.Withdrawn, currentAction = "已撤回：删除 ${result.first} 个文件")
            persist(XhsBatchState.Withdrawn)
            result
        } else {
            0 to 0
        }
    }

    // ---- 主管线 ----

    private suspend fun runBatch(dir: File) {
        val userId = activeUserId ?: return
        try {
            val notes = feedClient.fetchNotes(appContext, userId) { found ->
                _progress.value = _progress.value.copy(notesFound = found, currentAction = "已抓到 $found 篇")
            }
            if (notes.isEmpty()) {
                finish(XhsBatchState.Failed, "列表为空（可能需要网页登录）")
                return
            }
            _progress.value = _progress.value.copy(notesFound = notes.size, state = XhsBatchState.Running)
            persist(XhsBatchState.Running)

            val cookie = feedClient.currentCookie()
            for ((index, note) in notes.withIndex()) {
                if (!job!!.isActive) return
                waitForUnpause()
                _progress.value = _progress.value.copy(
                    currentAction = "正在处理第 ${index + 1}/${notes.size} 篇：${note.noteId}",
                )
                // 退避只包「解析」（网络页请求，限流高发）；下载是 CDN 直链，失败只计篇级失败不退避
                // 退避只包「解析」（网络页请求，限流高发）；下载是 CDN 直链，失败只计篇级失败不退避
                val resolved = backoffRetry { noteResolver.resolve(detailUrlOf(note), cookie) }
                if (_progress.value.state == XhsBatchState.Failed) return // 退避耗尽已停批
                if (resolved == null) {
                    _progress.value = _progress.value.copy(
                        notesProcessed = _progress.value.notesProcessed + 1,
                        notesFailed = _progress.value.notesFailed + 1,
                    )
                    persist(_progress.value.state)
                    delay(XhsBatchPacing.jitter(XhsBatchPacing.NOTE_DELAY_MIN_MS, XhsBatchPacing.NOTE_DELAY_MAX_MS, random))
                    continue
                }
                val allOk = resolveAndDownload(resolved, dir)
                _progress.value = _progress.value.copy(notesProcessed = _progress.value.notesProcessed + 1)
                if (!allOk) _progress.value = _progress.value.copy(notesFailed = _progress.value.notesFailed + 1)
                persist(_progress.value.state)
                delay(XhsBatchPacing.jitter(XhsBatchPacing.NOTE_DELAY_MIN_MS, XhsBatchPacing.NOTE_DELAY_MAX_MS, random))
            }
            finish(XhsBatchState.Done, "完成：${_progress.value.imagesDone} 图 ${_progress.value.videosDone} 视频 · ${mbOf()}")
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t // cancel 已置状态，这里只负责穿透
        } catch (t: Throwable) {
            finish(XhsBatchState.Failed, "异常终止：${t.message}")
        }
    }

    /** 单篇详情 URL：xsec_token 必须透传，裸链接会 403/跳登录（技术方案 §2.1）。 */
    private fun detailUrlOf(note: XhsUserFeedClient.FeedNote): String = buildString {
        append("https://www.xiaohongshu.com/explore/").append(note.noteId)
        if (note.xsecToken.isNotBlank()) {
            append("?xsec_token=").append(note.xsecToken).append("&xsec_source=pc_user")
        }
    }

    /** 已解析笔记的逐媒体下载。返回整篇是否全成。 */
    private suspend fun resolveAndDownload(
        resolved: ResolvedNote,
        dir: File,
    ): Boolean = withContext(Dispatchers.IO) {
        var allOk = true
        resolved.orderedMedia.forEachIndexed { i, media ->
            if (!job!!.isActive) return@withContext allOk
            waitForUnpause()
            val target = File(dir, fileNameOf(resolved, media, i))
            when (val r = downloader.download(media.sourceUrl, target)) {
                is XhsSerialDownloader.Result.Ok -> {
                    if (r.bytes > 0) countByType(media, r.bytes)
                }
                is XhsSerialDownloader.Result.Failure -> {
                    // 单文件失败不弃整篇；篇级失败由本返回值聚合
                    allOk = false
                }
            }
            delay(XhsBatchPacing.jitter(XhsBatchPacing.MEDIA_DELAY_MIN_MS, XhsBatchPacing.MEDIA_DELAY_MAX_MS, random))
        }
        allOk
    }

    /** 解析限流退避（PRD F11）：30s→60s→120s，耗尽置 Failed 停批并返回 null。 */
    private suspend fun <T> backoffRetry(block: suspend () -> T): T? {
        var lastError: Throwable? = null
        for (attempt in XhsBatchPacing.BACKOFF_MS.indices) {
            if (!job!!.isActive) return null
            val result: T? = try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                lastError = e
                null
            }
            if (result != null) return result
            if (attempt < XhsBatchPacing.BACKOFF_MS.size - 1) {
                val wait = XhsBatchPacing.BACKOFF_MS[attempt]
                _progress.value = _progress.value.copy(currentAction = "失败，退避 ${wait / 1000}s 后重试（第 ${attempt + 1} 次）")
                delay(wait)
                _progress.value = _progress.value.copy(currentAction = "重试中…")
            }
        }
        finish(XhsBatchState.Failed, "连续失败（${lastError?.message ?: "未知"}），退避耗尽，已停止（已下载文件保留）")
        return null
    }

    private suspend fun waitForUnpause() {
        while (paused && (job?.isActive == true)) delay(300)
    }

    // ---- 辅助 ----

    companion object {
        private fun fetchHtmlDirect(url: String, cookie: String): String? = runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.instanceFollowRedirects = true
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36",
            )
            if (conn.responseCode !in 200..299) return null
            conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrNull()
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
    }

    private fun persist(state: XhsBatchState) {
        val p = _progress.value
        val id = activeRecordId ?: return
        val dirPath = activeDir?.path ?: ""
        taskStore.upsert(
            XhsBatchTaskRecord(
                taskId = id, userId = p.userId.ifBlank { activeUserId ?: "" },
                createdAt = activeCreatedAt, updatedAt = System.currentTimeMillis(),
                state = state,
                notesFound = p.notesFound, notesProcessed = p.notesProcessed, notesFailed = p.notesFailed,
                imagesDone = p.imagesDone, videosDone = p.videosDone, totalBytes = p.totalBytes,
                dirPath = dirPath,
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
