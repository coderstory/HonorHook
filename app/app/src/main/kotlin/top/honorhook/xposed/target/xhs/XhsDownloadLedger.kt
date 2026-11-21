package top.honorhook.xposed.target.xhs

import android.content.ContentResolver
import android.provider.MediaStore

/**
 * 下载去重台账（2026-11-21 用户需求「存一下文章的 id」）：**下载文件本身就是台账**——
 * 文件名带 noteId 前缀（24hex，无 SQL 通配符，LIKE 注入面为零），查重 = 一次 MediaStore
 * 前缀查询。不落任何自有持久化状态（SharedPreferences/DB 均无必要，写宿主私有存储反而
 * 多一份要维护生命周期的状态）。
 * 边界（标注不装懂）：改造前的旧文件无 noteId 前缀 = 查不到 = 不判重（安全默认：假阴性
 * 只落在「没下过」，绝不误拦）；用户在相册手动删文件 = 判重自然失效（台账即文件，语义自洽）。
 * 视频轨历史即带该前缀（XhsVideoSave `xhs_<noteId>_hd.mp4`），图片轨本次对齐——
 * 图片+视频一张查询全覆盖。
 */
internal object XhsDownloadLedger {

    /** 图片落盘文件名（downloadAll 唯一构造点；旧名 `xhs_<ts>_<i>` 无 noteId，仅作 null 兜底）。 */
    internal fun imageFileName(noteId: String, ts: Long, index: Int, ext: String): String =
        "xhs_${noteId}_${ts}_$index.$ext"

    /** LIKE 前缀：同笔记的图片与视频文件名都以它开头。 */
    internal fun likePrefix(noteId: String): String = "xhs_$noteId%"

    /**
     * 查该笔记已落盘项数与最近一次落盘时间（DATE_ADDED 秒）。图片表 + 视频表各一条
     * COUNT+MAX 聚合查询，量级毫秒；单表查询失败只丢那张表（runCatching 吞掉，
     * 查重是「宁漏勿误」语义）。返回 (count, lastAddedSec)；count=0 时 lastAdded=0。
     */
    internal fun downloaded(resolver: ContentResolver, noteId: String): Pair<Int, Long> {
        val prefix = likePrefix(noteId)
        var count = 0
        var last = 0L
        for (uri in listOf(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        )) {
            runCatching {
                resolver.query(
                    uri,
                    arrayOf("COUNT(*)", "MAX(date_added)"),
                    "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf(prefix),
                    null,
                )?.use { c ->
                    if (c.moveToFirst()) {
                        count += c.getInt(0)
                        last = maxOf(last, c.getLong(1))
                    }
                }
            }
        }
        return count to last
    }

    /** 「MM-dd HH:mm」展示（DATE_ADDED 秒）；解析失败兜底空串（调用方据此省略时间括号）。 */
    internal fun fmtTime(addedSec: Long): String = runCatching {
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = addedSec * 1000 }
        "%02d-%02d %02d:%02d".format(
            cal.get(java.util.Calendar.MONTH) + 1,
            cal.get(java.util.Calendar.DAY_OF_MONTH),
            cal.get(java.util.Calendar.HOUR_OF_DAY),
            cal.get(java.util.Calendar.MINUTE),
        )
    }.getOrDefault("")
}
