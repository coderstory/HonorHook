package top.honorhook.ui.scope

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

    /** 执行体在 [RootShell]（工具箱共用）；这里只做 ps→kill 的编排。 */
    fun restart(packages: List<String>): Report {
        val targets = packages.filter { ScopeCommand.isValidPackage(it) }
        if (targets.isEmpty()) {
            return Report(emptyList(), emptyList(), emptyList(), null, null)
        }

        val probe = RootShell.probe()
        val su = probe.suPath
            ?: return Report(emptyList(), emptyList(), emptyList(), probe.failure.toFailure(), null)

        val ps = RootShell.exec(ScopeCommand.PS_COMMAND)
        val running = when (ps) {
            is RootShell.Result.Ok -> ScopeCommand.parseProcesses(ps.stdout)
            else -> return Report(emptyList(), emptyList(), emptyList(), mapFailure(ps), su)
        }

        val toStop = targets.filter { running[it]?.isNotEmpty() == true }
        val notRunning = targets - toStop.toSet()
        if (toStop.isEmpty()) {
            return Report(emptyList(), notRunning, emptyList(), null, su)
        }

        return when (val result = RootShell.exec(ScopeCommand.killScript(toStop, running))) {
            is RootShell.Result.Ok -> Report(toStop, notRunning, emptyList(), null, su)
            else -> Report(emptyList(), notRunning, toStop, mapFailure(result), su)
        }
    }

    private fun RootShell.Failure?.toFailure(): Failure? = when (this) {
        null -> null
        RootShell.Failure.NO_SU -> Failure.NO_SU
        RootShell.Failure.NOT_GRANTED -> Failure.NOT_GRANTED
        RootShell.Failure.TIMEOUT -> Failure.TIMEOUT
    }

    private fun mapFailure(result: RootShell.Result): Failure = when (result) {
        is RootShell.Result.Ok -> Failure.NOT_GRANTED
        RootShell.Result.Unavailable -> Failure.NO_SU
        RootShell.Result.Timeout -> Failure.TIMEOUT
        is RootShell.Result.Failed -> Failure.NOT_GRANTED
    }
}
