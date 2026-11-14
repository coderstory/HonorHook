package top.honorhook.xposed.target.xhs

import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * xhsContentImageUrls 口径契约（纯 JVM：函数在文件顶层，不触碰 XhsBatchSave object）。
 * 2026-11-14 用户二次报障「带图+视频只会下载视频」回归钉：slides_simulate 快闪视频
 * （type=video、N 图全 cover）不得被 cover 过滤清空。
 */
class XhsContentImageUrlsTest {

    private fun img(url: String, cover: Boolean = false, commentId: String? = null) =
        ResolvedMedia.Image(sourceUrl = url, cover = cover, commentId = commentId)

    private fun note(images: List<ResolvedMedia.Image>) = ResolvedNote(
        canonicalUrl = "https://www.xiaohongshu.com/explore/test",
        title = null, description = null, authorName = null, authorId = null, publishTime = null,
        images = images, type = "video",
    )

    @Test
    fun slides_allCover_multiImage_kept() {
        // 回归本体：21 张合成 slide 全被上游标 cover，必须全保留
        val slides = (1..21).map { img("https://sns-img.xhscdn.com/s$it", cover = true) }
        assertEquals(slides.map { it.sourceUrl }, xhsContentImageUrls(note(slides), "n1"))
    }

    @Test
    fun pureVideoNote_singleCover_filtered() {
        // 纯视频笔记封面帧：唯一 cover 图照旧滤掉（视频归 X4 HD 轨）
        assertEquals(emptyList<String>(), xhsContentImageUrls(note(listOf(img("c", cover = true))), "n2"))
    }

    @Test
    fun mixed_coverAndComment_keptFiltered() {
        // 常规混合：非 cover 保留；混有非 cover 时 cover 仍滤；评论图滤
        val images = listOf(
            img("a"), img("b"), img("cover", cover = true), img("cm", commentId = "c1"),
        )
        assertEquals(listOf("a", "b"), xhsContentImageUrls(note(images), "n3"))
    }

    @Test
    fun livePhoto_staticFrameExpanded_videoNotListed() {
        // 实况图：只展开静帧原图（ videos 不在此消费）
        val lp = ResolvedMedia.LivePhoto(
            image = img("frame"),
            video = ResolvedMedia.Video(sourceUrl = "clip.mp4"),
        )
        val n = note(emptyList()).copy(livePhotos = listOf(lp))
        assertEquals(listOf("frame"), xhsContentImageUrls(n, "n4"))
    }

    @Test
    fun dedup_keepsFirstOccurrenceOrder() {
        val images = listOf(img("dup"), img("x"), img("dup"))
        assertEquals(listOf("dup", "x"), xhsContentImageUrls(note(images), "n5"))
    }

    @Test
    fun nullNote_commentHarvestOnly() {
        // note 解析失败时评论图仍可用（被动采集不依赖 note 对象）
        assertEquals(emptyList<String>(), xhsContentImageUrls(null, "n6"))
    }
}
