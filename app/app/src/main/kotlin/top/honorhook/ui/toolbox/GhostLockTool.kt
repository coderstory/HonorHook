package top.honorhook.ui.toolbox

import android.content.Context
import java.io.File
import java.util.zip.ZipFile
import top.honorhook.ui.scope.RootShell

/**
 * GhostLock 集成工具（2026-11-03）：运行时从已安装的 com.ghostlock.app APK 里
 * 解出 payload assets，经 RootShell（su）投放并执行。
 *
 * 载荷与执行序列移植自 GhostLock 源码
 * （web/cev/dada-cve-AN00/app，shizuku/GhostlockUserService.kt
 * #runCve98260Blocking 与 #runCve64560Blocking）：
 * - 载荷 = assets/cve98260/cve-2026-98260-uid0（提权本体）+
 *   assets/cve64560/cve-2026-64560-su（临时 su，98260 链路里投放但非必需）
 * - 投放点 = /data/local/tmp/ghostlock-app/cve98260 与 /cve64560（源码同款目录）
 * - 执行 = 载荷 `fake` 参数（fake cred → 关 SELinux → ksud late-load，README §原理·5）
 * - 成功判据 = /proc/modules 出现 `kernelsu` 行（源码 isKernelSuLive 同款，
 *   README 头部：`su -c id` context=u:r:ksu + `grep kernelsu /proc/modules`）
 * - fake 成功后进程故意驻留（README：那块页承载活着的 task->cred，
 *   退出会 panic；清理只能重启），轮询到 kernelsu 后即报成功、不动该进程。
 * - manager 解析不移植：源码默认 managerPackage=null 时载荷自发现
 *   （"payload will self-discover and always prefers KernelSU"），su 版同样不设
 *   GHOSTLOCK_KSUD/GHOSTLOCK_KSU_PKG 环境变量。
 */
object GhostLockTool {

    const val CARRIER_PACKAGE = "com.ghostlock.app"
    const val CARRIER_REQUIREMENT = "需要已安装 GhostLock v1.3 作为载荷载体"

    /** 目标机内核（aak.md：6.6.118-android15-8-gf17133276a57-abogki518694926-4k）。 */
    private const val EXPECTED_KERNEL_PREFIX = "6.6.118"

    // 源码工作目录常量（GhostlockUserService 同款，勿改：fake 链路对路径无依赖但保持一致便于人工核对）
    private const val WORK_ROOT = "/data/local/tmp/ghostlock-app"
    private const val WORK_DIR_98260 = "$WORK_ROOT/cve98260"
    private const val WORK_DIR_64560 = "$WORK_ROOT/cve64560"

    private const val PAYLOAD_98260_ASSET = "assets/cve98260/cve-2026-98260-uid0"
    private const val PAYLOAD_SU_ASSET = "assets/cve64560/cve-2026-64560-su"
    private const val PAYLOAD_98260_NAME = "cve-2026-98260-uid0"
    private const val PAYLOAD_SU_NAME = "cve-64560-su"

    /** 与源码 runCve98260Blocking 同款：900s 内 kernelsu 未上线即报未命中。 */
    private const val RUN_TIMEOUT_MS = 900_000L
    private const val POLL_INTERVAL_MS = 2_000L

    data class CarrierInfo(
        val installed: Boolean,
        val versionOk: Boolean,
        val versionName: String?,
        val apkPath: String?,
    )

    /** 载荷载体检查：PackageManager 公开 API（免 root）。v1.3 起可用。 */
    fun carrier(context: Context): CarrierInfo = try {
        val pm = context.packageManager
        val info = pm.getPackageInfo(CARRIER_PACKAGE, 0)
        val version = info.versionName
        CarrierInfo(
            installed = true,
            // 只认 1.3.x：payload asset 布局以 v1.3 源码为准，其他版本不兜底
            versionOk = version?.startsWith("1.3") == true,
            versionName = version,
            apkPath = info.applicationInfo?.sourceDir,
        )
    } catch (_: Exception) {
        CarrierInfo(false, false, null, null)
    }

    data class ExtractResult(val ok: Boolean, val payloadFile: File?, val suFile: File?, val error: String?)

    /**
     * 从 base.apk 内解出两个 payload 到 filesDir/ghostlock/（只写本应用私有目录），
     * 再用 su chmod 补执行位（filesDir 上 Java 的 setExecutable 对 app uid 无意义，
     * 后续由 root cp 到 /data/local/tmp 后统一 chmod 700——见 [installPayloads]）。
     */
    fun extractPayloads(context: Context): ExtractResult = runCatching {
        val carrier = carrier(context)
        require(carrier.installed && carrier.versionOk) { CARRIER_REQUIREMENT }
        val apk = File(requireNotNull(carrier.apkPath) { "carrier apk path unavailable" })
        val outDir = File(context.filesDir, "ghostlock").apply { mkdirs() }
        ZipFile(apk).use { zip ->
            val payload = extractEntry(zip, PAYLOAD_98260_ASSET, File(outDir, PAYLOAD_98260_NAME))
            val su = extractEntry(zip, PAYLOAD_SU_ASSET, File(outDir, PAYLOAD_SU_NAME))
            ExtractResult(true, payload, su, null)
        }
    }.getOrElse { ExtractResult(false, null, null, it.message ?: "extract failed") }

    private fun extractEntry(zip: ZipFile, entryName: String, target: File): File {
        val entry = zip.getEntry(entryName) ?: error("APK 内缺少 $entryName")
        zip.getInputStream(entry).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    /** root 状态检测：su 探测 + uid + kernelsu 模块行 + 内核版本（README 成功判据同款）。 */
    data class RootStatus(
        val suAvailable: Boolean,
        val identity: String?,
        val kernelsuLive: Boolean,
        val kernelRelease: String?,
    )

    fun checkRoot(): RootStatus {
        val probe = RootShell.probe()
        if (!probe.available) {
            return RootStatus(false, null, false, null)
        }
        val id = when (val r = RootShell.exec("id")) {
            is RootShell.Result.Ok -> r.stdout.trim()
            else -> null
        }
        val modules = when (val r = RootShell.exec("cat /proc/modules")) {
            is RootShell.Result.Ok -> r.stdout
            else -> ""
        }
        val kernel = when (val r = RootShell.exec("uname -r")) {
            is RootShell.Result.Ok -> r.stdout.trim()
            else -> null
        }
        return RootStatus(
            suAvailable = true,
            identity = id,
            kernelsuLive = modules.lineSequence().any { it.startsWith("kernelsu ") },
            kernelRelease = kernel,
        )
    }

    data class RunResult(val ok: Boolean, val message: String)

    /**
     * 完整提权流程（su 版 runCve98260Blocking）：
     * ① 复制 payload 到源码同款工作目录并 chmod 700；
     * ② kernelsu 已在线则直接成功（源码 isKernelSuLive 前置检查同款）；
     * ③ 后台拉起 `payload fake`（fake 成功后进程故意驻留，不 kill）；
     * ④ 轮询 /proc/modules 等 kernelsu 上线，900s 超时报未命中。
     * 调用方须在 IO 线程调用。任何异常吞掉转 [RunResult]，不炸 app。
     */
    fun runEscalation(context: Context): RunResult = runCatching {
        val extract = extractPayloads(context)
        if (!extract.ok || extract.payloadFile == null) {
            return RunResult(false, "载荷提取失败：${extract.error ?: CARRIER_REQUIREMENT}")
        }
        val payload = extract.payloadFile
        val su = extract.suFile ?: return RunResult(false, "载荷提取失败：缺临时 su 载荷")

        // ① 投放：root 把 filesDir 里的载荷 cp 到源码同款目录并补执行位
        // （VALUE_PATTERN 白名单不覆盖路径类命令，这里目录均为本文件常量、文件名固定，无用户输入）
        val stage = RootShell.exec(
            "mkdir -p $WORK_DIR_98260 $WORK_DIR_64560" +
                " && cp ${payload.absolutePath} $WORK_DIR_98260/$PAYLOAD_98260_NAME" +
                " && cp ${su.absolutePath} $WORK_DIR_64560/$PAYLOAD_SU_NAME" +
                " && chmod 700 $WORK_DIR_98260/$PAYLOAD_98260_NAME $WORK_DIR_64560/$PAYLOAD_SU_NAME",
            timeoutSeconds = 30,
        )
        if (stage !is RootShell.Result.Ok) {
            return RunResult(false, "载荷投放失败（su）")
        }

        // ② 前置检查：kernelsu 已在线（本机常态）则无需再跑
        if (kernelsuLive()) {
            return RunResult(true, "KernelSU 已在线（本机已是 root），无需提权")
        }

        // ③ 后台拉起 fake 链路：nohup + & 使 su 立即返回；输出落文件不落管道
        // （管道回压在 race 窗口内会拖死链路——源码注释同款约束）。进程驻留是故意的。
        val launch = RootShell.exec(
            "cd $WORK_DIR_98260 && nohup ./$PAYLOAD_98260_NAME fake > $WORK_DIR_98260/run.log 2>&1 &",
            timeoutSeconds = 10,
        )
        if (launch !is RootShell.Result.Ok) {
            return RunResult(false, "提权进程启动失败（su）")
        }

        // ④ 轮询成功判据（源码 isKernelSuLive：/proc/modules 有 kernelsu 行）
        val deadline = System.currentTimeMillis() + RUN_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (kernelsuLive()) {
                return RunResult(true, "ROOT_SUCCESS backend=cve_2026_98260+kernelsu")
            }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        RunResult(false, "CVE-2026-98260: KernelSU 未上线（设备未重启；进程按源码语义保留）")
    }.getOrElse { RunResult(false, "提权流程异常：${it.message ?: "unknown"}") }

    private fun kernelsuLive(): Boolean = when (val r = RootShell.exec("grep -q '^kernelsu ' /proc/modules && echo live")) {
        is RootShell.Result.Ok -> r.stdout.contains("live")
        else -> false
    }
}
