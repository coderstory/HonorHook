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

    /**
     * url → note 映射；resolveHook 在每次 resolve 前回调（做暂停/取消编排）。
     * badNoteIds 命中的篇返回 BadNote（验证页/删帖场景）；未注册的 url 返回 Throttled（限流场景）。
     */
    private inner class FakeResolver(
        val byUrl: MutableMap<String, ResolvedNote> = mutableMapOf(),
        private val alwaysThrow: Boolean = false,
        private val resolveHook: suspend (Int) -> Unit = {},
    ) : XhsBatchController.NoteResolver {
        var resolveCalls = 0
        val badNoteIds = mutableSetOf<String>()
        override suspend fun resolve(url: String, cookie: String): XhsBatchController.ResolveOutcome {
            resolveCalls++
            resolveHook(resolveCalls)
            if (alwaysThrow) throw IllegalStateException("rate limited")
            val id = url.substringAfter("/explore/").substringBefore("?")
            if (id in badNoteIds) return XhsBatchController.ResolveOutcome.BadNote("<html>verify page</html>")
            return byUrl[url]?.let { XhsBatchController.ResolveOutcome.Ok(it) }
                ?: XhsBatchController.ResolveOutcome.Throttled
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

    /** 返回 (controller, rootOverride)，避免反射读私有缝。[root] 传入时复用目录（模拟进程重启后同一磁盘状态）。 */
    private fun TestScope.makeController(
        store: FakeStore,
        feed: XhsFeedClient,
        downloader: XhsDownloader,
        resolver: XhsBatchController.NoteResolver,
        notesByUrl: Map<String, ResolvedNote> = emptyMap(),
        root: File? = null,
    ): Pair<XhsBatchController, File> {
        // 压真实 delay：delay 在 IO 上下文走真实时间，秒级节奏会拖死虚拟时间等待
        XhsBatchPacing.NOTE_DELAY_MIN_MS = 5; XhsBatchPacing.NOTE_DELAY_MAX_MS = 10
        XhsBatchPacing.MEDIA_DELAY_MIN_MS = 5; XhsBatchPacing.MEDIA_DELAY_MAX_MS = 10
        if (resolver is FakeResolver) notesByUrl.forEach { (url, n) -> resolver.byUrl[url] = n }
        val rootDir = root ?: tmp.newFolder()
        val c = XhsBatchController(
            scope = backgroundScope,
            taskStore = store,
            feedClient = feed,
            downloader = downloader,
            appContext = null, // 只透传 feedClient，fake 不用
            random = Random(0),
            noteResolver = resolver,
            rootOverride = rootDir,
        )
        return c to rootDir
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

    /** 回归：撤回 A 用户时若 B 在跑，只能删 A 的目录、不许碰 B（withdraw 曾删 activeDir=错目录，2026-11-11 实锤）。 */
    @Test
    fun `withdraw by userId only deletes that user's directory`() = runTest {
        val store = FakeStore()
        val dl = FakeDownloader()
        val resolver = FakeResolver()
        val (c, root) = makeController(store, FakeFeed(listOf(feed("n1"), feed("n2"))), dl, resolver, notesByUrl = mapOf(
            detailUrl("n1") to note("n1"), detailUrl("n2") to note("n2")))
        c.start("1504334302")
        while (c.progress.value.state != XhsBatchState.Done) delay(100)
        // 造一个"另一个用户"的目录，模拟 B 任务存在
        val otherDir = java.io.File(root, "999").apply { mkdirs(); java.io.File(this, "keep.jpg").writeBytes(ByteArray(10)) }
        val (deleted, failed) = c.withdraw("1504334302")
        assertEquals(2, deleted) // 两篇各 1 文件都落在本用户目录
        assertEquals(0, failed)
        assertFalse(userDir(root).exists())
        assertTrue(otherDir.exists()) // B 的文件原封不动
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
    fun `queued task runs automatically after current finishes`() = runTest {
        val store = FakeStore()
        val feed = FakeFeed(listOf(feed("n1")))
        val dl = FakeDownloader()
        val (c, _) = makeController(store, feed, dl, FakeResolver(), notesByUrl = mapOf(detailUrl("n1") to note("n1")))
        c.start("1504334302")
        // 运行中添加第二个任务 → 待执行
        c.start("2000000001")
        assertEquals(1, c.queuedUserIds().size)
        assertEquals(XhsBatchState.Queued, store.list().last { it.userId == "2000000001" }.state)
        var guard = 0
        while (store.list().last { it.userId == "2000000001" }.state != XhsBatchState.Done) {
            if (guard % 10 == 0) java.io.File("/data/data/com.dsharnessmobile.shell/files/home/.dbg.log").appendText("Q guard=$guard B=${store.list().last { it.userId == "2000000001" }.state} prog=${c.progress.value.state}/${c.progress.value.userId}/${c.progress.value.currentAction} q=${c.queuedUserIds()} dl=${dl.downloads.toList()}\n")
            delay(100); check(++guard < 200000) { "second task never finished" }
        }
        val rec2 = store.list().last { it.userId == "2000000001" }
        assertEquals(XhsBatchState.Done, rec2.state)
        assertEquals(1, rec2.notesProcessed)
        assertEquals(0, c.queuedUserIds().size)
    }

    @Test
    fun `resume with persisted notes skips fetching and starts at nextIndex`() = runTest {
        val store = FakeStore()
        val feed = FakeFeed(listOf(feed("n1"), feed("n2"), feed("n3")))
        val dl = FakeDownloader()
        val resolver = FakeResolver()
        val notesByUrl = mapOf(detailUrl("n1") to note("n1"), detailUrl("n2") to note("n2"), detailUrl("n3") to note("n3"))
        val (c, _) = makeController(store, feed, dl, resolver, notesByUrl = notesByUrl)
        // 预置一条已中断记录：清单 3 篇、游标 1（n1 已处理过）
        store.upsert(
            XhsBatchTaskRecord(
                taskId = "t1", userId = "1504334302", createdAt = 1L, updatedAt = 1L,
                state = XhsBatchState.Interrupted,
                notesFound = 3, notesProcessed = 1, notesFailed = 0,
                imagesDone = 2, videosDone = 0, totalBytes = 2048L,
                dirPath = "",
                notes = listOf("n1|token-n1", "n2|token-n2", "n3|token-n3"), nextIndex = 1,
            ),
        )
        java.io.File("/data/data/com.dsharnessmobile.shell/files/home/.dbg.log").appendText("BEFORE-START\n")
        c.start("1504334302")
        java.io.File("/data/data/com.dsharnessmobile.shell/files/home/.dbg.log").appendText("AFTER-START state=${c.progress.value.state}\n")
        java.io.File("/data/data/com.dsharnessmobile.shell/files/home/.dbg.log").appendText("LOOP-ENTER state=${c.progress.value.state}\n")
        var last = ""
        while (c.progress.value.state != XhsBatchState.Done) {
            if (c.progress.value.state.name + c.progress.value.currentAction != last) {
                last = c.progress.value.state.name + c.progress.value.currentAction
                java.io.File("/data/data/com.dsharnessmobile.shell/files/home/.dbg.log").appendText("DBG state=${c.progress.value.state} action=${c.progress.value.currentAction} fetch=${feed.fetchCalls} dl=${dl.downloads.toList()}\n")
            }
            delay(100)
        }
        // 断点续传核心判据：恢复不重新抓列表（fetchCalls==0），从游标 1 起只处理 n2/n3
        assertEquals(0, feed.fetchCalls)
        assertEquals(2, dl.downloads.size)
        assertTrue(dl.downloads.none { it.startsWith("n1-") })
        assertEquals(3, c.progress.value.notesFound)
    }

    @Test
    fun `start while running queues the second task`() = runTest {
        val store = FakeStore()
        val gate = CompletableDeferred<Unit>()
        val resolver = FakeResolver(resolveHook = { gate.await() })
        val (c, _) = makeController(store, FakeFeed(listOf(feed("n1"))), FakeDownloader(), resolver, notesByUrl = mapOf(detailUrl("n1") to note("n1")))
        c.start("1504334302")
        while (!c.isRunning()) delay(10)
        c.start("9999999999") // 运行中 → 排队（待执行）
        assertEquals(XhsBatchState.Queued, store.records.values.first { it.userId == "9999999999" }.state)
        gate.complete(Unit)
        // 第一个跑完后队列自动开跑，第二个任务也到终态
        var guard = 0
        while (store.records.values.first { it.userId == "9999999999" }.state != XhsBatchState.Done) {
            delay(100); check(++guard < 200000) { "queued task never finished" }
        }
    }

    // ---- 2026-11-11 重构新增：失败分类 + 失败重试 ----

    @Test
    fun `bad note is skipped without stopping the batch`() = runTest {
        val store = FakeStore()
        val dl = FakeDownloader()
        val resolver = FakeResolver()
        resolver.badNoteIds.add("n1")
        val (c, _) = makeController(
            store, FakeFeed(listOf(feed("n1"), feed("n2"))), dl, resolver,
            notesByUrl = mapOf(detailUrl("n2") to note("n2")),
        )
        c.start("1504334302")
        while (c.progress.value.state != XhsBatchState.Done) delay(100)
        // 坏笔记：计 1 篇失败但不退避不停批（好笔记照常下载到 Done）
        assertEquals(XhsBatchState.Done, c.progress.value.state)
        assertEquals(1, c.progress.value.notesFailed)
        assertEquals(2, c.progress.value.notesProcessed)
        // 坏笔记只试 1 次（无退避），好笔记 1 次
        assertEquals(2, resolver.resolveCalls)
        assertEquals(listOf("n2-0.jpg"), dl.downloads)
        // 失败篇落账 failedNotes（「重试失败资源」的数据源）
        assertEquals(listOf("n1"), store.records.values.single().failedNotes)
    }

    @Test
    fun `retryFailed reruns only the failed notes`() = runTest {
        val store = FakeStore()
        val dl = FakeDownloader()
        val resolver = FakeResolver()
        val feed = FakeFeed(emptyList()) // retryFailed 不得抓列表：fetchCalls 必须 == 0
        val (c, _) = makeController(
            store, feed, dl, resolver,
            notesByUrl = mapOf(detailUrl("n2") to note("n2")),
        )
        // 预置 Done 记录：清单 3 篇，其中 n2 失败
        store.upsert(
            XhsBatchTaskRecord(
                taskId = "t1", userId = "1504334302", createdAt = 1L, updatedAt = 1L,
                state = XhsBatchState.Done,
                notesFound = 3, notesProcessed = 2, notesFailed = 1,
                imagesDone = 2, videosDone = 0, totalBytes = 2048L,
                dirPath = "",
                notes = listOf("n1|token-n1", "n2|token-n2", "n3|token-n3"),
                failedNotes = listOf("n2"),
            ),
        )
        c.retryFailed("1504334302")
        while (c.progress.value.state != XhsBatchState.Done) delay(100)
        // 只重跑 n2：不抓列表、不碰 n1/n3
        assertEquals(0, feed.fetchCalls)
        assertEquals(listOf("n2-0.jpg"), dl.downloads)
        assertEquals(1, resolver.resolveCalls)
    }

    /** 回归：cancel/pause 落盘曾用 nextIndex=0 覆盖续传游标，「继续」被迫从头重扫（2026-11-11 单测实锤）。 */
    @Test
    fun `pause and cancel preserve the resume cursor`() = runTest {
        val store = FakeStore()
        val feed = FakeFeed(listOf(feed("n1"), feed("n2"), feed("n3"), feed("n4"), feed("n5")))
        val dl = FakeDownloader()
        val resolver = FakeResolver()
        val notesByUrl = (1..5).associate { detailUrl("n$it") to note("n$it") }
        val (c, _) = makeController(store, feed, dl, resolver, notesByUrl = notesByUrl)
        store.upsert(
            XhsBatchTaskRecord(
                taskId = "t1", userId = "1504334302", createdAt = 1L, updatedAt = 1L,
                state = XhsBatchState.Interrupted,
                notesFound = 5, notesProcessed = 3, notesFailed = 0,
                imagesDone = 3, videosDone = 0, totalBytes = 3072L,
                dirPath = "",
                notes = (1..5).map { "n$it|token-n$it" }, nextIndex = 3,
            ),
        )
        backgroundScope.launch {
            while (true) {
                if (c.progress.value.state == XhsBatchState.Running) { c.pause(); break }
                delay(10)
            }
        }
        c.start("1504334302")
        while (c.progress.value.state != XhsBatchState.Paused) delay(10)
        assertEquals(3, store.records.values.last { it.userId == "1504334302" }.nextIndex) // 暂停不丢游标
        c.cancel()
        assertEquals(3, store.records.values.last { it.userId == "1504334302" }.nextIndex) // 取消不丢游标
    }

    // ---- 2026-11-11 验收场景补齐：大量数据 + 进程被杀后恢复 ----

    /** 大量数据：40 篇全量跑完，串行顺序与计数一致（有大量数据的场景）。 */
    @Test
    fun `large batch of 40 notes completes fully and in order`() = runTest {
        val store = FakeStore()
        val ids = (1..40).map { "n$it" }
        val dl = FakeDownloader()
        val resolver = FakeResolver()
        val notesByUrl = ids.associate { detailUrl(it) to note(it) }
        val (c, _) = makeController(store, FakeFeed(ids.map { feed(it) }), dl, resolver, notesByUrl = notesByUrl)
        c.start("1504334302")
        var guard = 0
        while (c.progress.value.state != XhsBatchState.Done) { delay(100); check(++guard < 200000) { "large batch never finished: ${c.progress.value}" } }
        assertEquals(40, c.progress.value.notesFound)
        assertEquals(40, c.progress.value.notesProcessed)
        assertEquals(40, c.progress.value.imagesDone)
        assertEquals(0, c.progress.value.notesFailed)
        assertEquals(40, dl.downloads.size)
        // 串行判据：首篇 n1、末篇 n40，且严格按清单序
        assertEquals("n1-0.jpg", dl.downloads.first())
        assertEquals("n40-0.jpg", dl.downloads.last())
        assertEquals((1..40).map { "n$it-0.jpg" }, dl.downloads)
    }

    /** 进程被杀后恢复：controller1 下载 2 篇后模拟进程死亡（记录残留 Running→Holder 归一化 Interrupted），
     *  controller2 从同一 store + 同一磁盘目录恢复——不重抓、快进到游标、剩余 3 篇下载到 Done。 */
    @Test
    fun `process kill then new controller resumes from persisted cursor`() = runTest {
        val store = FakeStore()
        val ids = listOf("n1", "n2", "n3", "n4", "n5")
        val notesByUrl = ids.associate { detailUrl(it) to note(it) }
        val dl1 = FakeDownloader()
        val gate = CompletableDeferred<Unit>()
        // 卡住第 3 篇解析（n1/n2 已落盘），模拟"下载过程中进程被杀"
        val resolver1 = FakeResolver(resolveHook = { call -> if (call == 3) gate.await() })
        val (_, root) = makeController(store, FakeFeed(emptyList()), dl1, resolver1, notesByUrl = notesByUrl)
        val feed1 = FakeFeed(ids.map { feed(it) })
        val c1 = XhsBatchController(
            scope = backgroundScope, taskStore = store, feedClient = feed1, downloader = dl1,
            appContext = null, random = Random(0), noteResolver = resolver1, rootOverride = root,
        )
        c1.start("1504334302")
        // 等游标 2 落盘（下载完成 ≠ persist 完成，等记录本身才无竞态）
        while ((store.records.values.lastOrNull { it.userId == "1504334302" }?.nextIndex ?: 0) < 2) delay(10)
        c1.cancel() // kill 前的取消落 Cancelled（RESUMABLE）；gate 释放让协程退场
        gate.complete(Unit)
        while (c1.isRunning()) delay(10)
        // 模拟 Holder 归一化：下次启动把残留态落 Interrupted（清单与游标必须已持久化）
        val killed = store.records.values.last { it.userId == "1504334302" }
        store.upsert(killed.copy(state = XhsBatchState.Interrupted))
        assertEquals(5, killed.notesFound)
        assertEquals(listOf("n1|token-n1", "n2|token-n2", "n3|token-n3", "n4|token-n4", "n5|token-n5"), killed.notes)
        assertEquals(2, killed.nextIndex)
        assertTrue(killed.notesProcessed >= 2)

        // "重启进程"：全新 controller + 全新 feed/downloader，同一 store、同一磁盘目录
        val feed2 = FakeFeed(ids.map { feed(it) })
        val dl2 = FakeDownloader()
        val resolver2 = FakeResolver()
        notesByUrl.forEach { (u, n) -> resolver2.byUrl[u] = n }
        val c2 = XhsBatchController(
            scope = backgroundScope, taskStore = store, feedClient = feed2, downloader = dl2,
            appContext = null, random = Random(0), noteResolver = resolver2, rootOverride = root,
        )
        c2.start("1504334302")
        var guard = 0
        while (c2.progress.value.state != XhsBatchState.Done) { delay(100); check(++guard < 200000) { "resume never finished: ${c2.progress.value}" } }
        // 真恢复判据：不重抓列表；只下载剩余 3 篇；旧文件去重（n1/n2 不重下）
        assertEquals(0, feed2.fetchCalls)
        assertEquals(listOf("n3-0.jpg", "n4-0.jpg", "n5-0.jpg"), dl2.downloads)
        assertEquals(5, c2.progress.value.notesFound)
        assertEquals(5, c2.progress.value.notesProcessed)
        // 磁盘最终状态：5 个文件齐全
        assertEquals(5, userDir(root).listFiles()!!.size)
        assertEquals(XhsBatchState.Done, store.records.values.last { it.userId == "1504334302" }.state)
    }
}
