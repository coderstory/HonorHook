package top.honorhook.ui.scope

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 全 app 唯一的 root shell 执行体（自 ScopeRestarter 提取，工具箱共用）。
 *
 * 语义与提取前一致：su 4 路径懒探测（命中缓存）/ 5s 超时 destroyForcibly /
 * stdout 独立线程读（先 waitFor 再读会让写满管道的子进程阻塞成假超时）。
 * 新增：stderr 同样独立线程采集——settings put 写错表 exit 0 静默失败时，
 * stderr 是唯一线索（28 号 §7 表陷阱）。
 *
 * **单发决策**（28 号 §1）：不开持久 su 会话——设备无 su 可用时 iaware 会杀常驻进程，
 * 且 spawn `su -c` 与既有 ScopeRestarter 行为一致。
 * 对外命令一律走 [SafeCommands] 工厂；[exec] 保持 object 可见性仅为包内复用。
 */
object RootShell {

    enum class Failure {
        NO_SU,          // 候选 su 全部 exec 不到
        NOT_GRANTED,    // su 起得来但非 uid=0：KernelSU 未授权本应用
        TIMEOUT,        // 超时窗口内未结束
    }

    sealed interface Result {
        data class Ok(val stdout: String) : Result
        data class Failed(val exit: Int, val stdout: String, val stderr: String) : Result
        data object Unavailable : Result
        data object Timeout : Result
    }

    data class ProbeResult(val suPath: String?, val failure: Failure?) {
        val available: Boolean get() = suPath != null
    }

    private const val DEFAULT_TIMEOUT_SECONDS = 5L
    private const val READER_JOIN_MS = 1000L

    @Volatile
    private var cachedSuPath: String? = null

    @Volatile
    private var lastFailure: Failure = Failure.NO_SU

    fun reset() {
        cachedSuPath = null
        lastFailure = Failure.NO_SU
    }

    /**
     * 懒探测：不单独烧一次 `su -c id` 的话无法区分「没 su」和「没授权」，
     * 首次 exec 前必经此路径；结果缓存到下次 [reset]。
     */
    fun probe(): ProbeResult {
        cachedSuPath?.let { return ProbeResult(it, null) }
        var sawNonIoFailure = false
        var sawTimeout = false
        for (candidate in ScopeCommand.SU_CANDIDATES) {
            when (val result = execRaw(candidate, ScopeCommand.ID_COMMAND)) {
                is Result.Ok -> if (ScopeCommand.isRootShell(result.stdout)) {
                    cachedSuPath = candidate
                    return ProbeResult(candidate, null)
                } else {
                    sawNonIoFailure = true
                }

                Result.Unavailable -> Unit
                Result.Timeout -> sawTimeout = true
                is Result.Failed -> sawNonIoFailure = true
            }
        }
        lastFailure = when {
            sawTimeout -> Failure.TIMEOUT
            sawNonIoFailure -> Failure.NOT_GRANTED
            else -> Failure.NO_SU
        }
        return ProbeResult(null, lastFailure)
    }

    /** 调用方须在 IO 线程调用；批量链（dumpsys 快照等）可放宽 [timeoutSeconds]。 */
    fun exec(command: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): Result {
        val su = cachedSuPath ?: probe().suPath ?: return Result.Unavailable
        return execRaw(su, command, timeoutSeconds)
    }

    private fun execRaw(su: String, command: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): Result {
        val process = try {
            Runtime.getRuntime().exec(arrayOf(su, "-c", command))
        } catch (e: IOException) {
            return Result.Unavailable
        }
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val stdoutReader = Thread { runCatching { process.inputStream.copyTo(out) } }.apply { start() }
        val stderrReader = Thread { runCatching { process.errorStream.copyTo(err) } }.apply { start() }
        return try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                Result.Timeout
            } else {
                val exit = process.exitValue()
                val stdout = out.toString(Charsets.UTF_8.name())
                val stderr = err.toString(Charsets.UTF_8.name())
                if (exit == 0) Result.Ok(stdout) else Result.Failed(exit, stdout, stderr)
            }
        } catch (e: IOException) {
            Result.Unavailable
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            Result.Timeout
        } finally {
            stdoutReader.join(READER_JOIN_MS)
            stderrReader.join(READER_JOIN_MS)
            runCatching { process.destroy() }
        }
    }
}

/**
 * 对外命令工厂：拼进 root shell 的每一块都过白名单（28 号 §1）。
 * 返回 null = 入参非法，调用方不得回退到手工拼串。
 */
object SafeCommands {

    enum class Table(val token: String) {
        SYSTEM("system"), SECURE("secure"), GLOBAL("global");
    }

    private val KEY_PATTERN = Regex("^[a-zA-Z0-9._]+$")
    // 只放行 数字/布尔/null/受限字符串；引号、分号、换行、空白、$ 一律拒（注入面）
    private val VALUE_PATTERN = Regex("^[A-Za-z0-9._:+-]*$")

    fun pmDisable(pkg: String): String? = pmValidated(pkg)?.let { "pm disable-user --user 0 $it" }
    fun pmEnable(pkg: String): String? = pmValidated(pkg)?.let { "pm enable $it" }

    fun settingsPut(table: Table, key: String, value: String): String? {
        if (!KEY_PATTERN.matches(key)) return null
        if (!VALUE_PATTERN.matches(value)) return null
        // 空值必须带引号，否则 sh 吃掉尾参变成缺参命令（private_dns_specifier 回滚走空值）
        val v = if (value.isEmpty()) "''" else value
        return "settings put ${table.token} $key $v"
    }

    fun settingsGet(table: Table, key: String): String? {
        if (!KEY_PATTERN.matches(key)) return null
        return "settings get ${table.token} $key"
    }

    /** 冻结批前强制快照（12 号恢复机制）：dumpsys package 全文，含每 userId enabled 状态。 */
    fun snapshotScript(): String = "dumpsys package"

    private fun pmValidated(pkg: String): String? =
        if (ScopeCommand.isValidPackage(pkg)) pkg else null
}
