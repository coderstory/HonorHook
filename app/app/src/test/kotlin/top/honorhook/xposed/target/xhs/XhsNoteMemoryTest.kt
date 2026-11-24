package top.honorhook.xposed.target.xhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [XhsNoteMemory] 的字段抽取契约（纯 JVM，用假 bean 钉住口径；真机结构见该类注释）。
 *
 * 钉什么：
 * - 只认「同一个 24 位 id」，别篇/无 id 一律 null（宁可失败，也不把别人的图存成这篇）；
 * - 直链优先级 `urlDefault` > `url` > `infoList`（`imageScene` 里 WB_DFT 优先）；
 * - 视频笔记整篇拒绝（内存里往往只剩封面）；
 * - 包装一层（holder → wrapper → note）也能找到。
 */
class XhsNoteMemoryTest {

    private class FakeVariant(val url: String, val imageScene: String)

    private class FakeImage(
        val urlDefault: String? = null,
        val url: String? = null,
        val infoList: List<FakeVariant> = emptyList(),
    )

    private class FakeUser(val nickname: String? = null, val userId: String? = null)

    private class FakeNote(
        val id: String,
        val title: String? = null,
        // 放在 imageList 前面：评论配图/头像混进图集的老 bug 就是从「字段顺序靠前」漏进去的
        val comments: List<FakeImage> = emptyList(),
        val imageList: List<FakeImage> = emptyList(),
        val user: FakeUser? = null,
        val type: String? = null,
        val video: Any? = null,
        val isVideo: Boolean? = null,
    )

    private class FakeHolder(val item: FakeNote?)

    private class FakeWrapper(val note: FakeNote)

    private val noteId = "6aeca16800000001b01d9a6f"

    @Test
    fun urlDefaultPreferredOverUrl() {
        val note = FakeNote(
            id = noteId,
            imageList = listOf(
                FakeImage(urlDefault = "https://sns-img.xhscdn.com/a!nd_dft", url = "https://sns-img.xhscdn.com/a!webp"),
                FakeImage(urlDefault = "https://sns-img.xhscdn.com/b!nd_dft"),
            ),
        )
        val got = XhsNoteMemory.extractFrom(note, noteId)
        assertEquals(
            listOf("https://sns-img.xhscdn.com/a!nd_dft", "https://sns-img.xhscdn.com/b!nd_dft"),
            got?.images?.map { it.originalUrl },
        )
    }

    @Test
    fun infoListPrefersWbDftScene() {
        val note = FakeNote(
            id = noteId,
            imageList = listOf(
                FakeImage(
                    infoList = listOf(
                        FakeVariant("https://sns-img.xhscdn.com/c!crd_wm_webp", "CRD_WM_WEBP"),
                        FakeVariant("https://sns-img.xhscdn.com/c!wb_dft_webp", "WB_DFT_WEBP"),
                    ),
                ),
            ),
        )
        assertEquals(
            listOf("https://sns-img.xhscdn.com/c!wb_dft_webp"),
            XhsNoteMemory.extractFrom(note, noteId)?.images?.map { it.originalUrl },
        )
    }

    @Test
    fun authorFromUserObject() {
        val note = FakeNote(
            id = noteId,
            imageList = listOf(FakeImage(urlDefault = "https://sns-img.xhscdn.com/a")),
            user = FakeUser(nickname = "墨色琴弦", userId = "5f1b2c3d4e5f6a7b8c9d0e1f"),
        )
        val got = XhsNoteMemory.extractFrom(note, noteId)
        assertEquals("墨色琴弦", got?.authorName)
        assertEquals("5f1b2c3d4e5f6a7b8c9d0e1f", got?.authorId)
    }

    @Test
    fun findsNoteThroughWrappers() {
        val holder = FakeHolder(FakeNote(id = noteId, imageList = listOf(FakeImage(urlDefault = "https://sns-img.xhscdn.com/z"))))
        assertEquals(
            listOf("https://sns-img.xhscdn.com/z"),
            XhsNoteMemory.extractFrom(holder, noteId)?.images?.map { it.originalUrl },
        )
        val wrapped = FakeWrapper(FakeNote(id = noteId, imageList = listOf(FakeImage(urlDefault = "https://sns-img.xhscdn.com/w"))))
        assertEquals(
            listOf("https://sns-img.xhscdn.com/w"),
            XhsNoteMemory.extractFrom(wrapped, noteId)?.images?.map { it.originalUrl },
        )
    }

    @Test
    fun otherNoteIdNeverMatches() {
        val note = FakeNote(id = "111111111111111111111111", imageList = listOf(FakeImage(urlDefault = "https://sns-img.xhscdn.com/x")))
        assertNull(XhsNoteMemory.extractFrom(note, noteId))
        assertNull(XhsNoteMemory.extractFrom(null, noteId))
        assertNull(XhsNoteMemory.extractFrom(note, "not-a-note-id"))
    }

    @Test
    fun videoNoteRejected() {
        val typed = FakeNote(id = noteId, type = "video", imageList = listOf(FakeImage(urlDefault = "https://sns-img.xhscdn.com/cover")))
        assertNull("type=video 的封面不是图集", XhsNoteMemory.extractFrom(typed, noteId))

        val withVideoField = FakeNote(
            id = noteId,
            video = FakeVariant("https://sns-video.xhscdn.com/v", "media"),
            imageList = listOf(FakeImage(urlDefault = "https://sns-img.xhscdn.com/cover2")),
        )
        assertNull("带 video 字段的笔记整篇拒绝", XhsNoteMemory.extractFrom(withVideoField, noteId))
    }

    @Test
    fun commentImagesNeverMixIntoNote() {
        val note = FakeNote(
            id = noteId,
            comments = listOf(FakeImage(urlDefault = "https://sns-img.xhscdn.com/comment-pic")),
            imageList = listOf(FakeImage(urlDefault = "https://sns-img.xhscdn.com/note-pic")),
        )
        assertEquals(
            listOf("https://sns-img.xhscdn.com/note-pic"),
            XhsNoteMemory.extractFrom(note, noteId)?.images?.map { it.originalUrl },
        )
    }

    @Test
    fun emptyVideoContainerIsNotVideo() {
        val note = FakeNote(
            id = noteId,
            video = emptyMap<String, Any>(),
            imageList = listOf(FakeImage(urlDefault = "https://sns-img.xhscdn.com/plain")),
        )
        assertEquals(
            listOf("https://sns-img.xhscdn.com/plain"),
            XhsNoteMemory.extractFrom(note, noteId)?.images?.map { it.originalUrl },
        )
        val flagged = FakeNote(
            id = noteId,
            isVideo = true,
            imageList = listOf(FakeImage(urlDefault = "https://sns-img.xhscdn.com/plain")),
        )
        assertNull("isVideo=true 的笔记仍是视频", XhsNoteMemory.extractFrom(flagged, noteId))
    }

    @Test
    fun nonCdnUrlIgnored() {
        val note = FakeNote(
            id = noteId,
            imageList = listOf(
                FakeImage(urlDefault = "https://evil.example.com/a.jpg"),
                FakeImage(urlDefault = "https://sns-img.xhscdn.com/ok"),
            ),
        )
        // 外域链接被逐条丢掉，能拿到的只有 CDN 那一张
        assertEquals(
            listOf("https://sns-img.xhscdn.com/ok"),
            XhsNoteMemory.extractFrom(note, noteId)?.images?.map { it.originalUrl },
        )
        val onlyJunk = FakeNote(id = noteId, imageList = listOf(FakeImage(urlDefault = "https://evil.example.com/a.jpg")))
        assertNull(XhsNoteMemory.extractFrom(onlyJunk, noteId))
    }

    @Test
    fun missLeavesDiagnostic() {
        val note = FakeNote(id = noteId, user = FakeUser(nickname = "x", userId = "5f1b2c3d4e5f6a7b8c9d0e1f"))
        assertNull(XhsNoteMemory.extractFrom(note, noteId))
        assertNotNull("失败要留下可取证的原因", XhsNoteMemory.lastDiag)
        assertTrue(
            "原因应指向「命中了但抽不到图」：${XhsNoteMemory.lastDiag}",
            XhsNoteMemory.lastDiag.orEmpty().contains("没抽出"),
        )
    }

    @Test
    fun noImageListReturnsNull() {
        val note = FakeNote(id = noteId, user = FakeUser(nickname = "x", userId = "5f1b2c3d4e5f6a7b8c9d0e1f"))
        assertNull(XhsNoteMemory.extractFrom(note, noteId))
    }
}
