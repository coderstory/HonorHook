package top.honorhook.xposed.target.xhs

import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import com.neoruaa.xhsdn.core.model.MediaCandidate
import com.neoruaa.xhsdn.core.model.ResolvedMedia

/**
 * X5 批量采集的**视频轨**（2026-11-22 用户需求「视频也要下载的」）。
 *
 * 为什么不复用 [XhsVideoSave] 的 HD 管线：那条链是「宿主长按面板 → 询问卡 → 选档 → 进度卡」的交互流程，
 * 带全局 hdActive/progress 状态与完成卡；批量静默跑时弹卡会盖住作者主页、状态互相覆盖。这里只借它的
 * 落盘语义（MediaStore.Video + `xhs_<noteId>_hd` 前缀、同名先删、IS_PENDING 收尾），不做任何 UI。
 *
 * 选档照 XhsVideoSave.pickBestAndDownload 的偏好（watermarked 升序 → original 降序 → w×h 降序），
 * 只消费上游 [ResolvedNote.videos] 的候选；批量的数据源是分享页解析结果，不含宿主 API 梯子。
 *
 * 画质门槛与图片一致口径：<5MB 视为低画质不落盘（用户 2026-11-21 定「视频小于 5mb 不要」）。
 * 取消由调用方传入（批量驱动持有取消旗标），写循环每块检查一次。
 */
internal object XhsBatchVideo {

    private const val MIN_BYTES = 5L * 1024 * 1024
    private const val MAX_BYTES = 500L * 1024 * 1024
    private const val CONNECT_TIMEOUT = 15_000
    private const val READ_TIMEOUT = 60_000

    enum class Outcome { OK, TOO_SMALL, FAIL }

    fun save(ctx: Context, noteId: String, video: ResolvedMedia.Video, canceled: () -> Boolean): Outcome {
        val best = pick(video) ?: return Outcome.FAIL
        return runCatching { download(ctx, noteId, best.url, canceled) }
            .getOrElse { Outcome.FAIL }
    }

    private fun pick(video: ResolvedMedia.Video): MediaCandidate? {
        val list = video.candidates.ifEmpty { return MediaCandidate(video.originalUrl) }
        return list.sortedWith(
            compareBy<MediaCandidate> { it.watermarked }
                .thenByDescending { it.original }
                .thenByDescending { it.width.toLong() * it.height }
                .thenByDescending { it.bitrate },
        ).firstOrNull()
    }

    private fun download(ctx: Context, noteId: String, url: String, canceled: () -> Boolean): Outcome {
        val conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT
            readTimeout = READ_TIMEOUT
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
            setRequestProperty("Referer", "https://www.xiaohongshu.com/")
        }
        val total = conn.contentLengthLong
        if (total in 1 until MIN_BYTES) {
            conn.disconnect()
            return Outcome.TOO_SMALL
        }
        val isMov = (conn.contentType ?: "").contains("quicktime", ignoreCase = true)
        val name = "xhs_${noteId}_hd" + if (isMov) ".origin.mov" else ".mp4"
        val resolver = ctx.contentResolver
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        // 同名旧行先删（重跑/补齐时不留两份）；DELETE 权限范围内本应用可删自己的历史行
        runCatching {
            resolver.delete(collection, "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name))
        }

        val values = android.content.ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, if (isMov) "video/quicktime" else "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/小红书")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: run { conn.disconnect(); return Outcome.FAIL }
        var written = 0L
        try {
            conn.inputStream.use { input ->
                (resolver.openOutputStream(uri) ?: error("openOutputStream 为空")).use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (canceled()) return rollback(resolver, uri, Outcome.FAIL)
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                        if (written > MAX_BYTES) return rollback(resolver, uri, Outcome.FAIL)
                    }
                }
            }
            if (written < MIN_BYTES) return rollback(resolver, uri, Outcome.TOO_SMALL)
            resolver.update(uri, android.content.ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
                put(MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            }, null, null)
            return Outcome.OK
        } finally {
            conn.disconnect()
        }
    }

    private fun rollback(resolver: android.content.ContentResolver, uri: android.net.Uri, outcome: Outcome): Outcome {
        runCatching { resolver.delete(uri, null, null) }
        return outcome
    }
}
