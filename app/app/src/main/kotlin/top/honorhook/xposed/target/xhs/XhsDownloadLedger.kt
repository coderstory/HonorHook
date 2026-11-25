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

    /**
     * 记账（downloadAll 零失败落盘后调用，后台线程）：append 一行 `noteId\tts\t张数`，失败静默。
     * 张数是「这一篇到底该有几张」的现场值：老行只有两列（= 张数未知），补齐时不拿它当完整依据。
     */
    internal fun record(
        context: android.content.Context,
        noteId: String,
        atSec: Long = System.currentTimeMillis() / 1000,
        count: Int = 0,
    ) {
        runCatching {
            java.io.File(context.filesDir, LEDGER_FILE).appendText("$noteId\t$atSec\t$count\n")
        }
    }

    /** 宿主原生保存（长按面板）单独记一份：一行 `noteId \t ts`。 */
    private const val HOST_SAVED_FILE = "xhs_host_saved.tsv"

    /**
     * 记一笔「这篇用宿主长按面板存过一张」。
     *
     * 为什么不写进 [LEDGER_FILE]：那张表第二列是落盘时间、第三列是期望张数，[downloaded] 只要 ts>0
     * 就返回 1 ⇒ 会在免点开那条路上让这一篇永久跳过、再也补不齐缺的张数（宿主面板只存得到一张压缩图）。
     * 这份只用来抑制兜底重跑：不然每批都给同一篇再长按一次，相册里留两张一模一样的图。
     */
    internal fun recordHostSaved(
        context: android.content.Context,
        noteId: String,
        atSec: Long = System.currentTimeMillis() / 1000,
    ) {
        runCatching {
            java.io.File(context.filesDir, HOST_SAVED_FILE).appendText("$noteId\t$atSec\n")
        }
    }

    /** 这篇是否用宿主长按面板存过（0 = 没记过）。 */
    internal fun hostSavedAt(context: android.content.Context, noteId: String): Long = runCatching {
        val f = java.io.File(context.filesDir, HOST_SAVED_FILE)
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
     * 相册里这篇**已经存在哪些图片序号**（文件名 `xhs_<noteId>_<ts>_<i>.<ext>`）。
     * 用它做「补齐」而不是「整篇重下」：缺 2 张就只下那 2 张，已存在的不再产生重复文件。
     * 老命名（无 noteId）不参与，解析不出序号的行直接跳过。
     */
    internal fun existingImageIndexes(resolver: ContentResolver, noteId: String): Set<Int> {
        val out = HashSet<Int>()
        runCatching {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf(likePrefix(noteId)),
                null,
            )?.use { c ->
                val col = c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(col) ?: continue
                    // xhs_<noteId>_<ts>_<i>.<ext>
                    val idx = name.substringBeforeLast('.').split('_').getOrNull(3)?.toIntOrNull()
                    if (idx != null) out.add(idx)
                }
            }
        }
        return out
    }

    /** 相册里这篇是否已有视频文件（`xhs_<noteId>_hd.mp4` / `_origin.mov`）。 */
    internal fun hasVideoFile(resolver: ContentResolver, noteId: String): Boolean = runCatching {
        resolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
            arrayOf(likePrefix(noteId)),
            null,
        )?.use { it.count > 0 } ?: false
    }.getOrDefault(false)

    /** 主台账查询：返回该 noteId 最近一次落盘时间（秒），无记录 = 0。 */
    /**
     * 台账里这一篇记录过的**期望资源数**（第三列；老行只有两列 → 0 = 未知）。
     * 用它和相册现状比对：够数才跳过，不够就去补齐（用户 2026-11-22「还是大范围的提示重复下载」）。
     */
    internal fun ledgerCount(context: android.content.Context, noteId: String): Int = runCatching {
        val f = java.io.File(context.filesDir, LEDGER_FILE)
        if (!f.exists()) return 0
        var best = 0
        f.useLines { lines ->
            for (line in lines) {
                val parts = line.split('\t')
                if (parts.size >= 3 && parts[0] == noteId) {
                    parts[2].toIntOrNull()?.let { if (it > best) best = it }
                }
            }
        }
        best
    }.getOrDefault(0)

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

    /** 台账总行数——驱动用它判断「这一次保存有没有真的落盘」（不解析内容，只要单调性）。 */
    internal fun stamp(context: android.content.Context): Long = runCatching {
        val f = java.io.File(context.filesDir, LEDGER_FILE)
        if (!f.isFile) 0L else f.readLines().count { it.isNotBlank() }.toLong()
    }.getOrDefault(0L)

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
