package top.honorhook.xposed

import android.os.ParcelFileDescriptor
import io.github.libxposed.api.XposedModule
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 运行日志环形缓冲：判据级日志通道，UI 日志页经模块 app 侧同名 API 读同一文件展示。
 * 通道选型（真机实证）：prefs 通道废弃——hook 侧 [RemotePrefsHolder] 的 getRemotePreferences 返回
 * 只读快照，edit() 抛 UnsupportedOperationException（LSPosed verbose 日志堆栈实证），KEY_LOG_RING
 * 从未写成功（app 日志页空白根因）；且该异常曾把 install() 收尾带崩（push 抛出 → markInstalled
 * 未执行，LSPosed 报 "target install failed"）。改用 module.openRemoteFile —— XposedModule 公开
 * 方法（javap 实证），daemon 以 MODE_CREATE | MODE_READ_WRITE 打开模块专属沙箱文件
 * （LSPModuleService.openRemoteFile 实证），hook 进程与模块 app 打开的是同一个文件。
 * 文件名约定 "honorhook.log"（[FILE_NAME]）——UI 侧按此名读取，改动必须两侧同步。
 * 热路径零 IO：push 只做内存 append + 500ms 去抖 post 一次落盘；flush 全程 runCatching 吞异常，
 * 任何失败静默 return（下次 push 重试）——install 收尾等关键路径绝不再被日志带崩（真机事故教训）。
 * 窗口裁剪（30 分钟）/×N 去重/cap 200 行语义同前，UI 侧解析依赖（行格式 `MM-dd HH:mm:ss [tag] msg`）。
 * 跨进程（各被 hook 进程一份队列）无锁协议，最后一写胜出——判据日志低频，可接受。
 */
object LogRing {

    /** 窗口裁切：行年龄超此毫秒数（30 分钟）即丢弃。 */
    private const val WINDOW_MS = 30 * 60 * 1000L

    /** 缓冲容量（行数）：窗口裁切后的第二道 cap（内存队列与文件内容同 cap）。 */
    private const val CAP_LINES = 200

    /** 远程文件通道文件名——UI 侧读取同名文件，严格一致。 */
    private const val FILE_NAME = "honorhook.log"

    /** 去抖窗口：窗口内多次 push 合并为一次落盘写。 */
    private const val FLUSH_DEBOUNCE_MS = 500L

    /** 行内时间戳格式（14 字符定长，位于行首）。 */
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** 解析用（行内 "MM-dd" 缺年份，解析时用当前年填充——跨年边界近似）。 */
    private val parseFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 行尾重复计数后缀（" ×N"）。 */
    private val countSuffix = Regex("\\s×\\d+$")

    /** 内存队列（cap [CAP_LINES]）：落盘失败不清空，下次 flush 重试。 */
    private val queue = mutableListOf<String>()

    /** 去抖标志：已有待执行 flush 时为 true，防窗口内重复 post。 */
    private val pendingFlush = AtomicBoolean(false)

    /**
     * 追加一行判据日志（仅内存操作，零文件 IO——intercept 热路径）。
     *
     * @param tag 行内短标识（"theme"/"glass"/"market"/"installer"，UI 日志页归类用）
     * @param msg 判据行内容（时间、tag、×N 计数由本方法统一拼，调用方不重复拼）
     */
    @Synchronized
    fun push(module: XposedModule, tag: String, msg: String) {
        queue += "${timeFmt.format(Date(System.currentTimeMillis()))} [$tag] $msg"
        if (queue.size > CAP_LINES) queue.subList(0, queue.size - CAP_LINES).clear()
        if (pendingFlush.compareAndSet(false, true)) {
            MountScheduler.postDelayed(FLUSH_DEBOUNCE_MS) { flush(module) }
        }
    }

    /**
     * 去抖落盘（MountScheduler 后台线程串行执行，不占用 hook 线程）。
     * 全程 runCatching 吞异常：读合并写任一步失败静默 return，队列保留待下次 push 重试。
     */
    private fun flush(module: XposedModule) {
        // 先复位标志：flush 期间新 push 可再次触发 post，尾部行不会滞留到进程退出
        pendingFlush.set(false)
        val queued: List<String>
        synchronized(this) { queued = queue.toList() }
        if (queued.isEmpty()) return
        runCatching {
            val now = System.currentTimeMillis()
            val pfd = module.openRemoteFile(FILE_NAME)
            try {
                val merged = merge(readAll(pfd), queued, now)
                writeAll(pfd, merged)
                // 只清已落盘快照段；flush 期间新 append 的尾部行保留待下次落盘
                synchronized(this) { queue.subList(0, queued.size).clear() }
            } finally {
                pfd.close()
            }
        }
    }

    /** 现有文件行 + 新队列行合并：窗口裁剪 → 同键合并计数 → cap 200。 */
    private fun merge(existingText: String, queued: List<String>, now: Long): String {
        val kept = mutableListOf<String>()
        val idxByKey = HashMap<String, Int>()
        for (l in existingText.lines().filter { it.isNotBlank() } + queued) {
            if (isExpired(l, now)) continue
            val k = keyOf(l)
            val idx = idxByKey[k]
            if (idx != null) {
                kept[idx] = recount(kept[idx], now)
            } else {
                idxByKey[k] = kept.size
                kept += l
            }
        }
        return kept.takeLast(CAP_LINES).joinToString("\n")
    }

    /** 读文件全文（MODE_CREATE 已保证文件存在；空文件返回空串）。 */
    private fun readAll(pfd: ParcelFileDescriptor): String =
        FileInputStream(pfd.fileDescriptor).readBytes().toString(Charsets.UTF_8)

    /**
     * 截断重写全文件。读写共用同一个 fd：读后位置在 EOF，写前必须截断 + 归位到 0，
     * 否则文件头残留旧字节 / 写进空洞。包装流不显式 close——关流会连带关掉共享 fd，
     * 唯一收尾是调用方 pfd.close()。
     */
    private fun writeAll(pfd: ParcelFileDescriptor, content: String) {
        val out = FileOutputStream(pfd.fileDescriptor)
        out.channel.truncate(0)
        out.channel.position(0)
        out.write(content.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    /** 行年龄 = now − 行首时间戳（年按当前年填充）；解析失败视为保留（防误删异常行）。 */
    private fun isExpired(line: String, now: Long): Boolean {
        if (line.length < 14) return false
        val yyyy = parseFmt.format(Date(now)).substring(0, 4)
        val t = runCatching { parseFmt.parse(yyyy + line.substring(0, 14)) }.getOrNull() ?: return false
        return now - t.time > WINDOW_MS
    }

    /** 去重键：去掉行首时间戳与 ×N 计数后缀后的 "[tag] msg" 部分。 */
    private fun keyOf(line: String): String =
        if (line.length > 14) line.substring(14).replace(countSuffix, "") else line

    /** 计数行改写：×N 自增 1、时间戳刷新为 now（无 ×N 后缀视为出现 1 次）。 */
    private fun recount(line: String, now: Long): String {
        val n = (countSuffix.find(line)?.value?.trimStart()?.substring(1)?.toIntOrNull() ?: 1) + 1
        return "${timeFmt.format(Date(now))}${keyOf(line)} ×$n"
    }
}