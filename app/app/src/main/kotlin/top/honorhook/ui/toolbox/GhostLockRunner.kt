package top.honorhook.ui.toolbox

import android.content.Context
import top.honorhook.tools.ghostlock.shizuku.ShizukuExploitRunner
import top.honorhook.tools.ghostlock.data.ManagerScanner

/**
 * 临时 Root 执行器（2026-11-03 建；2026-11-27 用户指令加回多漏洞版本选择）。
 * payload 成品随本 app assets 打包（assets/ghostlock/），无 root 场景经
 * Shizuku shell uid 投放执行，不依赖任何 root。
 * 两条路径：
 * - CVE_2026_98260 = ShizukuExploitRunner#runCve98260（begin → 256KB 分块 append → run），
 *   managerPackage=null 时载荷自发现优先 KernelSU；
 * - CVE_2026_64560 = ShizukuExploitRunner#runCve64560（fanout/rotate/su 三个载荷一次事务），
 *   只跑 fanout（rotate 在本机 OS4.0.0.22 上 Stage 0 panic），等当前启动期临时 su。
 * 成功判据 = onComplete(0)，2 = 内核未命中设备完好（源码常量）。
 * 上游其余 backend（43499/98499/53361/99491）见 [Backend]，待后续移植。
 */
object GhostLockRunner {

    /**
     * 漏洞版本清单，顺序 = 展示顺序。上游 GhostLock 的 ExploitBackend 全集；
     * [implemented] = 本模块是否已移植执行链路（false 的项 UI 置灰，只作占位）。
     */
    enum class Backend(
        val label: String,
        val summary: String,
        val implemented: Boolean,
    ) {
        Cve202698260(
            "CVE-2026-98260",
            "UAF → fake cred → KernelSU late-load；载荷按本机 6.6.118 预编译，不读 kernel_profiles",
            true,
        ),
        Cve202664560(
            "CVE-2026-64560",
            "fanout → 当前启动期临时 su；载荷自带内核构建串表（读 /proc/version），不读 kernel_profiles",
            true,
        ),
        Cve202643499("CVE-2026-43499", "libghostlock.so 原生管线，需 kernel_profiles 偏移表（未移植）", false),
        Cve202698499("CVE-2026-98499", "wacom OOB-read leak，只读诊断（未移植）", false),
        Cve202653361("CVE-2026-53361", "AF_UNIX GC UAF safegate（未移植）", false),
        Cve202699491("CVE-2026-99491", "wacom sibling UAF probe（未移植）", false),
    }

    private const val PAYLOAD_ASSET = "ghostlock/cve98260/cve-2026-98260-uid0"
    private const val SU_ASSET = "ghostlock/cve64560/cve-2026-64560-su"
    private const val FANOUT_ASSET = "ghostlock/cve64560/cve-2026-64560-fanout"
    private const val ROTATE_ASSET = "ghostlock/cve64560/cve-2026-64560-rotate"

    @Volatile
    private var runner: ShizukuExploitRunner? = null

    fun runnerOf(context: Context): ShizukuExploitRunner =
        runner ?: synchronized(this) {
            runner ?: ShizukuExploitRunner(context.applicationContext).also { runner = it }
        }

    /** 98260 = 大载荷 + 临时 su。 */
    fun loadPayloads(context: Context): Pair<ByteArray, ByteArray> =
        context.assets.let {
            Pair(
                it.open(PAYLOAD_ASSET).use { s -> s.readBytes() },
                it.open(SU_ASSET).use { s -> s.readBytes() },
            )
        }

    /** 64560 = fanout + rotate + 临时 su（rotate 只投放不自动执行）。 */
    fun loadCve64560Payloads(context: Context): ShizukuExploitRunner.Cve64560Payloads =
        context.assets.let {
            ShizukuExploitRunner.Cve64560Payloads(
                fanout = it.open(FANOUT_ASSET).use { s -> s.readBytes() },
                rotate = it.open(ROTATE_ASSET).use { s -> s.readBytes() },
                su = it.open(SU_ASSET).use { s -> s.readBytes() },
            )
        }

    /** 已装根管理器候选（KSU 及常见分支，源码 ManagerScanner 同款过滤）；默认不选 = 载荷自发现。 */
    fun managers(context: Context): List<ManagerScanner.Candidate> = runCatching {
        ManagerScanner.scan(context.packageManager, context.packageName)
    }.getOrDefault(emptyList())

    /** 完整执行。返回 (成功, 摘要)；IO 线程调用。 */
    suspend fun run(
        context: Context,
        backend: Backend,
        managerPackage: String?,
        onLog: (String) -> Unit,
        onStatus: ((step: String, status: String) -> Unit)?,
    ): Pair<Boolean, String> {
        if (!backend.implemented) {
            return Pair(false, "${backend.label} 执行链路未移植")
        }
        val code = when (backend) {
            Backend.Cve202698260 -> {
                val (payload, su) = runCatching { loadPayloads(context) }.getOrElse {
                    return Pair(false, "载荷读取失败：${it.message ?: "unknown"}")
                }
                runnerOf(context).runCve98260(
                    payload, su, managerPackage,
                    onLog = onLog,
                    onStatus = onStatus,
                )
            }
            Backend.Cve202664560 -> {
                val payloads = runCatching { loadCve64560Payloads(context) }.getOrElse {
                    return Pair(false, "载荷读取失败：${it.message ?: "unknown"}")
                }
                runnerOf(context).runCve64560(
                    payloads, null,
                    onLog = onLog,
                    onStatus = onStatus,
                )
            }
            else -> return Pair(false, "${backend.label} 执行链路未移植")
        }
        return when (code) {
            0 -> when (backend) {
                Backend.Cve202664560 -> Pair(true, "ROOT_SUCCESS · 临时 su 已就绪（本次启动内有效）")
                else -> Pair(true, "ROOT_SUCCESS · KernelSU 已上线（临时 root，重启失效）")
            }
            2 -> Pair(false, "内核未命中（设备完好，无需重启）")
            else -> Pair(false, "执行失败（exit=$code）")
        }
    }
}
