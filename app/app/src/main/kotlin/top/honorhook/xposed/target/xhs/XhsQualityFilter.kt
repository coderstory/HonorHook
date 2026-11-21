package top.honorhook.xposed.target.xhs

import android.graphics.BitmapFactory
import android.net.Uri

/**
 * 画质/体积过滤（2026-11-21 用户需求）：自动过滤低画质图片与过小视频。
 * - 图片：短边 ≤ [MIN_IMAGE_SHORT_SIDE]（720p 及以下）不落盘——fetchOne 完整性校验通过后
 *   inJustDecodeBounds 解码（不载入像素，开销仅文件头解析），命中即删 MediaStore 行。
 * - 视频：字节数 < [MIN_VIDEO_BYTES]（5MB）不落盘——Content-Length 已知时 insert 前早退，
 *   未知时写完终检（rollbackHd 删半成品）。
 * 过滤是硬规则（用户明说「自动过滤或者删除」），不设开关；计数进各完成卡文案。
 */
object XhsQualityFilter {

    /** 图片短边下限：720p 及以下过滤（用户 2026-11-21 口径「720p 及以下」） */
    const val MIN_IMAGE_SHORT_SIDE = 720

    /** 视频字节数下限：5MB（用户 2026-11-21 口径「小于 5mb 的也不要」） */
    const val MIN_VIDEO_BYTES = 5L * 1024 * 1024

    /**
     * 图片分辨率过滤：true = 短边 ≤720（或解码失败视为不可判定，不拦——宁存不误删）。
     * [uri] 必须是已写完整的 MediaStore 行。
     */
    fun imageTooSmall(resolver: android.content.ContentResolver, uri: Uri): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeStream(input, null, opts)
            val w = opts.outWidth
            val h = opts.outHeight
            w > 0 && h > 0 && minOf(w, h) <= MIN_IMAGE_SHORT_SIDE
        } ?: false
    }.getOrDefault(false)

    /** 视频体积过滤：true = 字节数 <5MB。 */
    fun videoTooSmall(bytes: Long): Boolean = bytes in 1 until MIN_VIDEO_BYTES
}
