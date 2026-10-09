package top.honorhook.xposed

import io.github.libxposed.api.XposedModule
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import top.honorhook.data.KEY_LOG_RING

/**
 * 运行日志环形缓冲：hook 侧判据级日志双写通道——同一行信息既走 module.log（LSPosed 日志），
 * 也经 [RemotePrefsHolder] 既有链路写进远程配置通道 [KEY_LOG_RING]（LSPosed daemon 落库 +
 * binder 推送），UI 日志页读同一 key 实时展示/一键复制，零新基建。
 * 窗口裁剪：按行首时间戳丢弃超 [WINDOW_MS]（30 分钟）的旧行（时间戳按当前年填充解析，
 * 跨年边界近似处理）；[CAP_LINES] 200 行为第二道 cap（超限丢最旧，环形语义）。
 * 重复去重：与已有行 "[tag] msg"（时间戳与 ×N 计数后缀外）相同则不追加新行，改写为
 * `时间戳 [tag] msg ×N`（计数递增、时间戳刷新为最新出现时间）——同一拦截点多次触发、
 * 同 hook 多进程重复汇总一眼可见频次。
 * prefs 获取失败静默返回、下次触发自愈——此处不记日志，否则失败与触发源互相递归刷屏。
 * @Synchronized 串行化同进程多 hook 线程竞争；跨进程各写一份后最后一写胜出
 * （LSPosedRemotePreferences 快照型覆盖写）——判据日志低频，可接受。
 */
object LogRing {

    /** 窗口裁切：行年龄超此毫秒数（30 分钟）即丢弃。 */
    private const val WINDOW_MS = 30 * 60 * 1000L

    /** 缓冲容量（行数）：窗口裁切后的第二道 cap。 */
    private const val CAP_LINES = 200

    /** 行内时间戳格式（14 字符定长，位于行首）。 */
    private val timeFmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)

    /** 解析用（行内 "MM-dd" 缺年份，解析时用当前年填充——跨年边界近似）。 */
    private val parseFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    /** 行尾重复计数后缀（" ×N"）。 */
    private val countSuffix = Regex("\\s×\\d+$")

    /**
     * 追加一行判据日志（与 module.log 同文双写，非替换）。
     *
     * @param tag 行内短标识（"theme"/"glass"/"market"/"installer"，UI 日志页归类用）
     * @param msg 判据行内容（时间、tag、×N 计数由本方法统一拼，调用方不重复拼）
     */
    @Synchronized
    fun push(module: XposedModule, tag: String, msg: String) {
        val prefs = RemotePrefsHolder.obtain(module) ?: return
        val now = System.currentTimeMillis()
        val newLine = "${timeFmt.format(Date(now))} [$tag] $msg"
        val newKey = " [$tag] $msg"

        val kept = mutableListOf<String>()
        var merged = false
        for (l in prefs.getString(KEY_LOG_RING, null).orEmpty().lines().filter { it.isNotBlank() }) {
            if (isExpired(l, now)) continue
            if (!merged && keyOf(l) == newKey) {
                kept += recount(l, now)
                merged = true
            } else {
                kept += l
            }
        }
        if (!merged) kept += newLine
        prefs.edit().putString(KEY_LOG_RING, kept.takeLast(CAP_LINES).joinToString("\n")).apply()
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