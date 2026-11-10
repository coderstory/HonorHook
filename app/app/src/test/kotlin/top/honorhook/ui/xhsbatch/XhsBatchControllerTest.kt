package top.honorhook.ui.xhsbatch

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * XhsBatchController JVM 单测：fake 注入（store/feed/downloader/resolver + rootOverride），
 * 虚拟时间跑串行节奏（真实 jitter 是秒级）。org.json 在 JVM 不可用 ⇒ TaskStore 不在此测（技术方案 §3.0）。
 */
class XhsBatchControllerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- fakes ----

    private class FakeStore : XhsBatchTaskStore {
        val records = LinkedHashMap<String, XhsBatchTaskRecord>()
        override fun list() = records.values.toList()
        override fun upsert(record: XhsBatchTaskRecord) { records[record.taskId] = record }
        override fun delete(taskId: String) { records.remove(taskId) }
    }

    private class FakeFeed(private val notes: List<XhsUserFeedClient.FeedNote>) : XhsFeedClient {
        var fetchCalls = 0
        override suspend fun fetchNotes(context: android.content.Context?, userId: String, onProgress: (Int) -> Unit): List<XhsUserFeedClient.FeedNote> {
            fetchCalls++
            onProgress(notes.size)
            return notes
        }
        override fun currentCookie() = "test-cookie"
    }

    /** 落真盘：断言续跑去重与撤回删净都需要真实文件存在。 */
    private inner class FakeDownloader : XhsDownloader {
        val downloads = mutableListOf<String>()
        override fun download(url: String, target: File): XhsSerialDownloader.Result {
            downloads.add(target.name)
            if (target.isFile && target.length() > 0) return XhsSerialDownloader.Result.Ok(target, 0)
            target.parentFile?.mkdirs()
            target.writeBytes(ByteArray(1024))
            return XhsSerialDownloader.Result.Ok(target, 1024)
        }
        override fun deleteRecursivelyCounted(dir: File): Pair<Int, Int> =
            XhsSerialDownloader().deleteRecursivelyCounted(dir)
    }

    /** url → note 映射；resolveHook 在每次 resolve 前回调（做暂停/取消编排）。 */
    private inner class FakeResolver(
        val byUrl: MutableMap<String, ResolvedNote> = mutableMapOf(),
        private val alwaysThrow: Boolean = false,
        private val resolveHook: suspend (Int) -> Unit = {},
    ) : XhsBatchController.NoteResolver {
        var resolveCalls = 0
        override suspend fun resolve(url: String, cookie: String): ResolvedNote? {
            resolveCalls++
            resolveHook(resolveCalls)
            if (alwaysThrow) throw IllegalStateException("rate limited")
            return byUrl[url]
        }
    }

    private fun note(id: String, imageCount: Int = 1, videoCount: Int = 0) = ResolvedNote(
        canonicalUrl = detailUrl(id), title = id, description = null, authorName = null, authorId = null, publishTime = null,
        images = (0 until imageCount).map { ResolvedMedia.Image(sourceUrl = "https://cdn/$id-img$it.jpg") },
        videos = (0 until videoCount).map { ResolvedMedia.Video(sourceUrl = "https://cdn/$id-vid$it.mp4") },
        noteId = id,
    )

    private fun feed(id: String) = XhsUserFeedClient.FeedNote(id, "token-$id")

    private fun detailUrl(id: String) =
        "https://www.xiaohongshu.com/explore/$id?xsec_token=token-$id&xsec_source=pc_user"

    /** 返回 (controller, rootOverride)，避免反射读私有缝。 */
    private fun TestScope.makeController(
        store: FakeStore,
        feed: XhsFeedClient,
        downloader: XhsDownloader,
        resolver: XhsBatchController.NoteResolver,
        notesByUrl: Map<String, ResolvedNote> = emptyMap(),
    ): Pair<XhsBatchController, File> {
        if (resolver is FakeResolver) notesByUrl.forEach { (url, n) -> resolver.byUrl[url] = n }
        val root = tmp.newFolder()
        val c = XhsBatchController(
            scope = backgroundScope,
            taskStore = store,
            feedClient = feed,
            downloader = downloader,
            appContext = null, // 只透传 feedClient，fake 不用
            random = Random(0),
            noteResolver = resolver,
            rootOverride = root,
        )
        return c to root
    }

    private fun userDir(root: File) = File(root, "1504334302") // 2026-11-11 目录去 xhs 层

    // ---- cases ----

    @Test
    fun `serial download counts images videos and bytes`() = runTest {
        val store = FakeStore()
        val feed = FakeFeed(listOf(feed("n1"), feed("n2")))
        val dl = FakeDownloader()
        val resolver = FakeResolver()
        val (c, _) = makeController(
            store, feed, dl, resolver,
            notesByUrl = mapOf(
                detailUrl("n1") to note("n1", imageCount = 2, videoCount = 1),
                detailUrl("n2") to note("n2"),
            ),
        )
        c.start("1504334302")
        while (c.progress.value.state != XhsBatchState.Done) delay(100)
        assertEquals(XhsBatchState.Done, c.progress.value.state)
        assertEquals(3, c.progress.value.imagesDone)
        assertEquals(1, c.progress.value.videosDone)
        assertEquals(4 * 1024L, c.progress.value.totalBytes)
        assertEquals(2, c.progress.value.notesFound)
        assertEquals(2, c.progress.value.notesProcessed)
        assertEquals(0, c.progress.value.notesFailed)
        // 串行：下载顺序 = 笔记顺序、媒体顺序
        assertEquals(listOf("n1-0.jpg", "n1-1.jpg", "n1-2.mp4", "n2-0.jpg"), dl.downloads)
        // 持久化：终态落 store
        assertEquals(XhsBatchState.Done, store.records.values.single().state)
    }

    @Test
    fun `existing file is skipped and not double counted`() = runTest {
        val store = FakeStore()
        val dl = FakeDownloader()
        val resolver = FakeResolver()
        val (c, root) = makeController(store, FakeFeed(listOf(feed("n1"))), dl, resolver, notesByUrl = mapOf(detailUrl("n1") to note("n1")))
        val dir = userDir(root)
        dir.mkdirs()
        File(dir, "n1-0.jpg").writeBytes(ByteArray(2048))
        c.start("1504334302")
        while (c.progress.value.state != XhsBatchState.Done) delay(100)
        // 跳过：无新下载、不计数
        assertTrue(dl.downloads.contains("n1-0.jpg"))
        assertEquals(0L, c.progress.value.totalBytes)
        assertEquals(0, c.progress.value.imagesDone)
    }

    @Test
    fun `cancel keeps downloaded files`() = runTest {
        val store = FakeStore()
        val dl = FakeDownloader()
        val gate = CompletableDeferred<Unit>()
        val resolver = FakeResolver(resolveHook = { call ->
            if (call == 2) gate.await() // 卡住第 2 篇解析，让主测试线程在下载发生后取消
        })
        val (c, root) = makeController(store, FakeFeed(listOf(feed("n1"), feed("n2"))), dl, resolver, notesByUrl = mapOf(detailUrl("n1") to note("n1")))
        c.start("1504334302")
        while (c.progress.value.imagesDone < 1) delay(10)
        c.cancel()
        gate.complete(Unit)
        while (c.isRunning()) delay(20)
        assertEquals(XhsBatchState.Cancelled, c.progress.value.state)
        assertEquals(1, c.progress.value.imagesDone)
        // 文件保留（PRD F8）
        assertTrue(userDir(root).isDirectory)
    }

    @Test
    fun `withdraw deletes the whole user directory`() = runTest {
        val store = FakeStore()
        val dl = FakeDownloader()
        val resolver = FakeResolver()
        val (c, root) = makeController(store, FakeFeed(listOf(feed("n1"))), dl, resolver, notesByUrl = mapOf(detailUrl("n1") to note("n1")))
        c.start("1504334302")
        while (c.progress.value.state != XhsBatchState.Done) delay(100)
        assertTrue(userDir(root).isDirectory)
        val (deleted, failed) = c.withdraw()
        assertEquals(1, deleted)
        assertEquals(0, failed)
        assertFalse(userDir(root).exists())
        assertEquals(XhsBatchState.Withdrawn, c.progress.value.state)
    }

    @Test
    fun `backoff exhaustion stops the batch as failed`() = runTest {
        val store = FakeStore()
        val dl = FakeDownloader()
        val resolver = FakeResolver(alwaysThrow = true)
        val (c, _) = makeController(store, FakeFeed(listOf(feed("n1"), feed("n2"))), dl, resolver)
        c.start("1504334302")
        while (c.progress.value.state != XhsBatchState.Failed) delay(100)
        assertEquals(XhsBatchState.Failed, c.progress.value.state)
        // 退避序列 30s/60s/120s：3 次尝试全打在同篇（等待发生在尝试之间），第 2 篇不再碰（停批）
        assertEquals(3, resolver.resolveCalls)
        assertTrue(dl.downloads.isEmpty())
        assertTrue(c.progress.value.currentAction.contains("退避耗尽"))
    }

    @Test
    fun `illegal user id is rejected without fetching`() = runTest {
        val store = FakeStore()
        val feed = FakeFeed(emptyList())
        val c = makeController(store, feed, FakeDownloader(), FakeResolver()).first
        // 输入放宽为 小红书号/hex id（[A-Za-z0-9_]{2,24}），只剩带非法字符的输入才拒绝
        c.start(" abc!123 ")
        assertEquals(XhsBatchState.Failed, c.progress.value.state)
        assertEquals(0, feed.fetchCalls)
    }

    @Test
    fun `empty note list fails the task`() = runTest {
        val store = FakeStore()
        val c = makeController(store, FakeFeed(emptyList()), FakeDownloader(), FakeResolver()).first
        c.start("1504334302")
        while (c.progress.value.state != XhsBatchState.Failed) delay(100)
        assertTrue(c.progress.value.currentAction.contains("列表为空"))
    }

    @Test
    fun `pause holds at file boundary until resume`() = runTest {
        val store = FakeStore()
        val dl = FakeDownloader()
        lateinit var c: XhsBatchController
        val resolver = FakeResolver(resolveHook = { call ->
            if (call == 2) c.pause() // 第 2 篇解析前暂停（文件边界）
        })
        val (controller, _) = makeController(store, FakeFeed(listOf(feed("n1"), feed("n2"))), dl, resolver, notesByUrl = mapOf(detailUrl("n1") to note("n1")))
        c = controller
        resolver.byUrl[detailUrl("n2")] = note("n2")
        // 看门狗：见到 Paused 即恢复（模拟用户点「恢复」）
        backgroundScope.launch {
            while (true) {
                if (c.progress.value.state == XhsBatchState.Paused) { c.resumeRun(); break }
                delay(20)
            }
        }
        c.start("1504334302")
        while (c.progress.value.state != XhsBatchState.Done) delay(100)
        assertEquals(2, c.progress.value.notesProcessed)
        assertEquals(2, c.progress.value.imagesDone)
    }

    @Test
    fun `start while running is ignored`() = runTest {
        val store = FakeStore()
        val gate = CompletableDeferred<Unit>()
        val resolver = FakeResolver(resolveHook = { gate.await() })
        val (c, _) = makeController(store, FakeFeed(listOf(feed("n1"))), FakeDownloader(), resolver, notesByUrl = mapOf(detailUrl("n1") to note("n1")))
        c.start("1504334302")
        while (!c.isRunning()) delay(10)
        c.start("9999999999") // 应被忽略：不产生第二个任务
        assertEquals(1, store.records.size)
        gate.complete(Unit)
        while (c.progress.value.state != XhsBatchState.Done) delay(100)
        assertEquals(null, store.records.values.firstOrNull { it.userId == "9999999999" })
    }
}
