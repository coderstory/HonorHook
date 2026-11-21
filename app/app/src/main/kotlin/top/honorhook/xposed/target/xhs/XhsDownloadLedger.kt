package top.honorhook.xposed.target.xhs

import android.content.ContentResolver
import android.provider.MediaStore

/**
 * 下载去重台账（2026-11-21 用户需求「存一下文章的 id」）。
 * 主台账 = **宿主进程 filesDir 下的 TSV 文件**（`noteId\tts` 一行一条）：相册文件的删除 /
 * 改名 / 系统自动清理都碰不到它，生命周期只挂在「清小红书数据」上——这是有意接受的边界
 * （清数据 = 用户主动重置，判重失效是合理语义）。hook 跑在宿主进程内，写宿主自己的
 * filesDir 无跨应用权限问题；落盘走 append（单行原子性由 OS 保证），读取容错（坏行跳过）。
 * 辅助信号 = MediaStore 文件名前缀查询（`xhs_<noteId>_`）：兜住「台账写入前崩溃」的窄缝 +
 * 视频轨（XhsVideoSave 直落 MediaStore、不走本台账）。文件名前缀仍保留——它同时是用户在
 * 相册里辨识模块下载件的标记。
 * 边界（标注不装懂）：改造前的旧文件无前缀、台账也无记录 = 不判重（安全默认：假阴性
 * 只落在「没下过」，绝不误拦）。
 */
internal object XhsDownloadLedger {

    /** 宿主 filesDir 相对路径；TSV：noteId \t 落盘时间（DATE_ADDED 同口径，秒）。 */
    private const val LEDGER_FILE = "xhs_download_ledger.tsv"

    /** 图片落盘文件名（downloadAll 唯一构造点；旧名 `xhs_<ts>_<i>` 无 noteId，仅作 null 兜底）。 */
    internal fun imageFileName(noteId: String, ts: Long, index: Int, ext: String): String =
        "xhs_${noteId}_${ts}_$index.$ext"

    /** LIKE 前缀：同笔记的图片与视频文件名都以它开头。 */
    internal fun likePrefix(noteId: String): String = "xhs_$noteId%"

    /** 记账（downloadAll 成功落盘后调用，后台线程）：append 一行，失败静默（下次 MediaStore 查询兜底）。 */
    internal fun record(context: android.content.Context, noteId: String, atSec: Long = System.currentTimeMillis() / 1000) {
        runCatching {
            java.io.File(context.filesDir, LEDGER_FILE).appendText("$noteId\t$atSec\n")
        }
    }

    /** 主台账查询：返回该 noteId 最近一次落盘时间（秒），无记录 = 0。 */
    internal fun ledgerDownloaded(context: android.content.Context, noteId: String): Long = runCatching {
        val f = java.io.File(context.filesDir, LEDGER_FILE)
        if (!f.exists()) return 0L
        var last = 0L
        f.useLines { lines ->
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size >= 2 && parts[0] == noteId) {
                    parts[1].toLongOrNull()?.let { if (it > last) last = it }
                }
            }
        }
        last
    }.getOrDefault(0L)

    /**
     * 辅助信号：MediaStore 图片表+视频表 COUNT+MAX(DATE_ADDED) 前缀查询，量级毫秒；
     * 单表查询失败只丢那张表（runCatching 吞掉，查重是「宁漏勿误」语义）。
     */
    internal fun mediaStoreDownloaded(resolver: ContentResolver, noteId: String): Pair<Int, Long> {
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

    /**
     * 综合判定（调用方唯一入口）：主台账文件命中 或 MediaStore 前缀命中 = 已下载过。
     * 返回 (count, lastAddedSec, source)：count = max(两路张数，命中至少 1)；last = 两路取大；
     * source = 命中渠道台账/相册/both（诊断用，调用方进日志/提示文案）。
     */
    internal fun downloaded(context: android.content.Context, noteId: String): Triple<Int, Long, String> {
        val ledTs = ledgerDownloaded(context, noteId)
        val (msCount, msLast) = mediaStoreDownloaded(context.contentResolver, noteId)
        val source = when {
            ledTs > 0 && msCount > 0 -> "both"
            ledTs > 0 -> "ledger"
            else -> "mediastore"
        }
        return Triple(maxOf(msCount, if (ledTs > 0) 1 else 0), maxOf(msLast, ledTs), source)
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
