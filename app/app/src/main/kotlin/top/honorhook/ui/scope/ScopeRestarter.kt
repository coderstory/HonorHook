package top.honorhook.ui.scope

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/** 只走 root 一条通道（LSPosed 设备必有 root）。调用方须在 IO 线程调用。 */
object ScopeRestarter {

    enum class Failure {
        NO_SU,          // 候选 su 全部 exec 不到
        NOT_GRANTED,    // su 起得来但非 uid=0：KernelSU 未授权本应用
        TIMEOUT,        // 5 秒内未结束
    }

    /** stopped + failed = 执行前真正在跑的目标数；notRunning 不计入成败。 */
    data class Report(
        val stopped: List<String>,
        val notRunning: List<String>,
        val failed: List<String>,
        val failure: Failure?,
        val suPath: String?,
    ) {
        val runningCount: Int get() = stopped.size + failed.size
    }

    private const val TIMEOUT_SECONDS = 5L
    private const val READER_JOIN_MS = 1000L

    @Volatile
    private var cachedSuPath: String? = null

    @Volatile
    private var lastFailure: Failure = Failure.NO_SU

    val currentSuPath: String? get() = cachedSuPath

    fun reset() {
        cachedSuPath = null
    }

    fun restart(packages: List<String>): Report {
        val targets = packages.filter { ScopeCommand.isValidPackage(it) }
        if (targets.isEmpty()) {
            return Report(emptyList(), emptyList(), emptyList(), null, cachedSuPath)
        }

        val su = resolveSu()
            ?: return Report(emptyList(), emptyList(), emptyList(), lastFailure, null)

        val ps = runShell(su, ScopeCommand.PS_COMMAND)
        val running = when (ps) {
            is ShellResult.Ok -> ScopeCommand.parseProcesses(ps.stdout)
            else -> return Report(emptyList(), emptyList(), emptyList(), mapFailure(ps), su)
        }

        val toStop = targets.filter { running[it]?.isNotEmpty() == true }
        val notRunning = targets - toStop.toSet()
        if (toStop.isEmpty()) {
            return Report(emptyList(), notRunning, emptyList(), null, su)
        }

        return when (val result = runShell(su, ScopeCommand.killScript(toStop, running))) {
            is ShellResult.Ok -> Report(toStop, notRunning, emptyList(), null, su)
            else -> Report(emptyList(), notRunning, toStop, mapFailure(result), su)
        }
    }

    /** 懒探测：不单独跑 `su -c id`，首次执行的 ps 本身就是探测。命中者存入 [cachedSuPath]。 */
    private fun resolveSu(): String? {
        cachedSuPath?.let { return it }
        var sawNonIoFailure = false
        var sawTimeout = false
        for (candidate in ScopeCommand.SU_CANDIDATES) {
            when (val result = runShell(candidate, ScopeCommand.ID_COMMAND)) {
                is ShellResult.Ok -> if (ScopeCommand.isRootShell(result.stdout)) {
                    cachedSuPath = candidate
                    return candidate
                } else {
                    sawNonIoFailure = true
                }

                ShellResult.Unavailable -> Unit
                ShellResult.Timeout -> sawTimeout = true
                is ShellResult.Failed -> sawNonIoFailure = true
            }
        }
        lastFailure = when {
            sawTimeout -> Failure.TIMEOUT
            sawNonIoFailure -> Failure.NOT_GRANTED
            else -> Failure.NO_SU
        }
        return null
    }

    private fun mapFailure(result: ShellResult): Failure = when (result) {
        is ShellResult.Ok -> Failure.NOT_GRANTED
        ShellResult.Unavailable -> Failure.NO_SU
        ShellResult.Timeout -> Failure.TIMEOUT
        is ShellResult.Failed -> Failure.NOT_GRANTED
    }

    private sealed interface ShellResult {
        data class Ok(val stdout: String) : ShellResult
        data class Failed(val exit: Int, val stdout: String) : ShellResult
        data object Unavailable : ShellResult
        data object Timeout : ShellResult
    }

    /** 输出在独立线程读：先 waitFor 再读会让写满管道的进程阻塞成假超时。 */
    private fun runShell(su: String, command: String): ShellResult {
        val process = try {
            Runtime.getRuntime().exec(arrayOf(su, "-c", command))
        } catch (e: IOException) {
            return ShellResult.Unavailable
        }
        val buffer = ByteArrayOutputStream()
        val reader = Thread { runCatching { process.inputStream.copyTo(buffer) } }.apply { start() }
        return try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly()
                ShellResult.Timeout
            } else {
                val exit = process.exitValue()
                val stdout = buffer.toString(Charsets.UTF_8.name())
                if (exit == 0) ShellResult.Ok(stdout) else ShellResult.Failed(exit, stdout)
            }
        } catch (e: IOException) {
            ShellResult.Unavailable
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            ShellResult.Timeout
        } finally {
            reader.join(READER_JOIN_MS)
            runCatching { process.destroy() }
        }
    }
}
