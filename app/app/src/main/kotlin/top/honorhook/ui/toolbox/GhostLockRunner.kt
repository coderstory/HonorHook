package top.honorhook.ui.toolbox

import android.content.Context
import top.honorhook.tools.ghostlock.shizuku.ShizukuExploitRunner
import top.honorhook.tools.ghostlock.data.ManagerScanner

/**
 * 临时 Root 执行器（2026-11-03）：Shizuku 通道的 CVE-2026-98260 提权；
 * payload 成品随本 app assets 打包（assets/ghostlock/），无 root 场景经
 * Shizuku shell uid 投放执行，不依赖任何 root。
 * 序列 = ShizukuExploitRunner#runCve98260（begin → 256KB 分块 append → run）；
 * managerPackage=null 时载荷自发现优先 KernelSU；debugDir=null 默认零日志；
 * 成功判据 = onComplete(0)，2 = 内核未命中设备完好（源码常量）。
 */
object GhostLockRunner {

    private const val PAYLOAD_ASSET = "ghostlock/cve98260/cve-2026-98260-uid0"
    private const val SU_ASSET = "ghostlock/cve64560/cve-2026-64560-su"

    @Volatile
    private var runner: ShizukuExploitRunner? = null

    fun runnerOf(context: Context): ShizukuExploitRunner =
        runner ?: synchronized(this) {
            runner ?: ShizukuExploitRunner(context.applicationContext).also { runner = it }
        }

    fun loadPayloads(context: Context): Pair<ByteArray, ByteArray> =
        context.assets.let {
            Pair(
                it.open(PAYLOAD_ASSET).use { s -> s.readBytes() },
                it.open(SU_ASSET).use { s -> s.readBytes() },
            )
        }

    /** 已装根管理器候选（KSU 及常见分支，源码 ManagerScanner 同款过滤）；默认不选 = 载荷自发现。 */
    fun managers(context: Context): List<ManagerScanner.Candidate> = runCatching {
        ManagerScanner.scan(context.packageManager, context.packageName)
    }.getOrDefault(emptyList())

    /** 完整执行（默认参数）。返回 (成功, 摘要)；IO 线程调用。 */
    suspend fun run(
        context: Context,
        managerPackage: String?,
        onLog: (String) -> Unit,
        onStatus: ((step: String, status: String) -> Unit)?,
    ): Pair<Boolean, String> {
        val (payload, su) = runCatching { loadPayloads(context) }.getOrElse {
            return Pair(false, "载荷读取失败：${it.message ?: "unknown"}")
        }
        val code = runnerOf(context).runCve98260(
            payload, su, managerPackage,
            onLog = onLog,
            onStatus = onStatus,
        )
        return when (code) {
            0 -> Pair(true, "ROOT_SUCCESS · KernelSU 已上线（临时 root，重启失效）")
            2 -> Pair(false, "内核未命中（设备完好，无需重启）")
            else -> Pair(false, "执行失败（exit=$code）")
        }
    }
}
