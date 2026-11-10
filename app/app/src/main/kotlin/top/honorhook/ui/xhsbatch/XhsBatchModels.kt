package top.honorhook.ui.xhsbatch

/**
 * 小红书用户作品批量下载——模型与节奏常量。
 * PRD：docs/prd-xhs-user-batch-download.md；技术方案：docs/tech-design-xhs-user-batch-download.md。
 * 纯 UI 侧功能，零 hook 依赖；抓取/下载走 HttpURLConnection（okhttp 是 compileOnly，
 * R8 剥离后 APK 内没有，见 app/build.gradle.kts XHS 依赖块注释）。
 */

/** 任务状态机（与 XhsBatchTaskRecord.state 字符串一一对应）。 */
enum class XhsBatchState {
    Idle, Fetching, Running, Paused, Done, Cancelled, Withdrawn,
    /** App 被杀等造成的运行中断（技术方案 §3.0：继续 = 同 userId 重跑 + 按已存在文件去重）。 */
    Interrupted,
    /** 退避重试耗尽（PRD F11）。 */
    Failed,
}

/** 进度快照（PRD §4.4；MB 按落盘字节计）。 */
data class XhsBatchProgress(
    val userId: String = "",
    val notesFound: Int = 0,
    val notesProcessed: Int = 0,
    val notesFailed: Int = 0,
    val imagesDone: Int = 0,
    val videosDone: Int = 0,
    val totalBytes: Long = 0,
    val state: XhsBatchState = XhsBatchState.Idle,
    /** 当前动作行文案（「正在处理第 N 篇」/退避提示等）。 */
    val currentAction: String = "",
)

/** 任务持久化记录（技术方案 §3.0；tasks.json 单文件存储的行模型）。 */
data class XhsBatchTaskRecord(
    val taskId: String,
    val userId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: XhsBatchState,
    val notesFound: Int,
    val notesProcessed: Int,
    val notesFailed: Int,
    val imagesDone: Int,
    val videosDone: Int,
    val totalBytes: Long,
    val dirPath: String,
)

/** 任务持久化口：Controller 只依赖接口，JVM 单测用内存 fake。 */
interface XhsBatchTaskStore {
    fun list(): List<XhsBatchTaskRecord>
    fun upsert(record: XhsBatchTaskRecord)
    fun delete(taskId: String)
}

/** 风控节奏常量表（技术方案 §5：收紧风控时一刀调这里）。 */
object XhsBatchPacing {
    /** 逐篇解析页间隔（含抖动）区间 ms。 */
    const val NOTE_DELAY_MIN_MS = 2000L
    const val NOTE_DELAY_MAX_MS = 4000L

    /** 逐媒体文件间隔（含抖动）区间 ms。 */
    const val MEDIA_DELAY_MIN_MS = 500L
    const val MEDIA_DELAY_MAX_MS = 1500L

    /** WebView 翻页注入滚动间隔区间 ms。 */
    const val PAGE_DELAY_MIN_MS = 2000L
    const val PAGE_DELAY_MAX_MS = 4000L

    /** 列表篇数上限（防失控）。 */
    const val MAX_NOTES = 200

    /** 首屏拦截超时：超时回落 __INITIAL_STATE__ 兜底解析。 */
    const val FIRST_PAGE_TIMEOUT_MS = 15_000L

    /** 退避序列（PRD F11：连续失败/429·461 → 逐级等待重试，耗尽停批）。 */
    val BACKOFF_MS = longArrayOf(30_000L, 60_000L, 120_000L)

    /** 任务记录保留上限（超出淘汰最旧；文件不动）。 */
    const val MAX_TASK_RECORDS = 50

    fun jitter(minMs: Long, maxMs: Long, random: kotlin.random.Random = kotlin.random.Random): Long =
        minMs + random.nextLong(maxMs - minMs + 1)
}
