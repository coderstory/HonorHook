package top.honorhook.tools.ghostlock.shizuku

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import top.honorhook.tools.ghostlock.data.ManagerScanner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 移植自 GhostLock shizuku/GhostlockUserService.kt（898 行），按 2026-11-03 用户拍板
 * 只保留 CVE-2026-98260 默认路径（runCve98260Blocking 及其依赖链）：
 * runExploit/runExploitAsManager（kernel profile 路径）、runCve64560、CPU 组、
 * 安全模式、debugDir（默认 null 不用）全部不移植。
 * 2026-11-27 用户指令加回 runCve64560（fanout 路径），并删掉 assets/ghostlock/kernel_profiles
 * ——那 11 个 profile 只服务未移植的 runExploit，全仓零引用（实测截面 6.6.118 家族）。
 * 该类跑在 Shizuku UserService 进程（uid 2000 shell），由 ShizukuExploitRunner 经
 * Shizuku.bindUserService 按类名拉起（UserServiceArgs ComponentName）——类名不可混淆，
 * proguard-rules.pro 有具名 keep。
 */
@Keep
class GhostlockUserService(private val context: Context) : IGhostlockUserService.Stub() {
    private val running = AtomicBoolean(false)

    /* The 98260 payload is larger than a binder transaction, so the app
     * streams it here in chunks before runCve98260 consumes it. */
    private val payloadLock = Any()
    private val pendingPayload = ByteArrayOutputStream()

    private companion object {
        /** One strategy's wall clock; the upstream runner uses 150 s. */
        const val STRATEGY_TIMEOUT_MS = 150_000L

        /** Fanout rounds per click; a clean miss leaves the kernel untouched. */
        const val FANOUT_ROUNDS = 3

        /** Non-zero, but distinct from a hard failure: no match, device intact. */
        const val EXIT_NO_MATCH = 2
        val ANSI_REGEX = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        /** 载荷自报的内核状态已保存/损坏：冻结载荷、不再跑下一轮（不是 clean miss）。 */
        val PROTECTED_MARKERS = listOf(
            "MISC_BRIDGE_MISS_PRESERVE",
            "KERNEL_STATE_PRESERVE",
            "CRED_STATE_PRESERVE",
            "KERNEL_STATE_RESTORE_NOT_PROVEN",
            "KERNEL_STATE_CORRUPTED",
        )
    }

    override fun beginCve98260() {
        synchronized(payloadLock) { pendingPayload.reset() }
    }

    override fun appendCve98260(chunk: ByteArray) {
        synchronized(payloadLock) { pendingPayload.write(chunk) }
    }

    override fun runCve98260(
        su: ByteArray,
        managerPackage: String?,
        callback: IGhostlockCallback,
        statusCallback: IGhostlockStatusCallback,
    ) {
        val payload = synchronized(payloadLock) {
            val bytes = pendingPayload.toByteArray()
            pendingPayload.reset()
            bytes
        }
        if (payload.isEmpty()) {
            callback.onLog("<s> error: empty CVE-2026-98260 payload")
            callback.onComplete(1)
            return
        }
        if (!running.compareAndSet(false, true)) {
            callback.onLog("<s> error: another GhostLock process is already running")
            callback.onComplete(1)
            return
        }
        Thread({
            val exitCode = runCatching {
                runCve98260Blocking(payload, su, managerPackage, callback, statusCallback)
            }.getOrElse { error ->
                runCatching { callback.onLog("<s> error: ${error.message}") }
                1
            }
            running.set(false)
            runCatching { callback.onComplete(exitCode) }
        }, "ghostlock-cve98260-runner").start()
    }

    private fun runCve98260Blocking(
        payload: ByteArray,
        su: ByteArray,
        managerPackage: String?,
        callback: IGhostlockCallback,
        statusCallback: IGhostlockStatusCallback,
    ): Int {
        require(Process.myUid() == Process.SHELL_UID) {
            "Shizuku UserService uid=${Process.myUid()}, expected ${Process.SHELL_UID}"
        }
        val procStatus = File("/proc/self/status").readText()
        require(Regex("(?m)^Seccomp:\\s*0$").containsMatchIn(procStatus)) {
            "Shizuku UserService is still seccomp-filtered"
        }
        callback.onLog("<s> CVE-2026-98260 Android port: uid=${Process.myUid()} Seccomp=0")
        val workDir = File("/data/local/tmp/ghostlock-app/cve98260").apply {
            require(isDirectory || mkdirs()) { "cannot create $absolutePath" }
        }
        val suDir = File("/data/local/tmp/ghostlock-app/cve64560").apply {
            require(isDirectory || mkdirs()) { "cannot create $absolutePath" }
        }
        val suFile = writePayload(suDir, "cve-64560-su", su)
        val payloadFile = writePayload(workDir, "cve-2026-98260-uid0", payload)
        callback.onLog("<s> payload: ${payloadFile.absolutePath} (${payload.size}B)")

        /* The 98260 payload hands root to whichever manager we name, so resolve
         * the user's choice here; without it the payload self-discovers and
         * always prefers KernelSU. */
        val pm = context.packageManager
        val staging = File("/data/local/tmp/ghostlock-app/ksud")
        callback.onLog("<s> manager choice: ${managerPackage ?: "auto"}")
        val resolved = ManagerScanner.resolveKsud(pm, managerPackage, staging)
        if (resolved != null) {
            callback.onLog("<s> ksud resolved: ${resolved.file.absolutePath} (manager=${resolved.packageName})")
        } else {
            callback.onLog("<s> no manager with libksud.so; payload will self-discover")
        }
        if (managerPackage != null && resolved?.packageName != managerPackage) {
            callback.onLog("<s> $managerPackage has no usable libksud.so; falling back to ${resolved?.packageName ?: "self-discover"}")
        }

        if (isKernelSuLive()) {
            callback.onLog("<s> KernelSU is already live from this boot")
            completeAllSteps(statusCallback)
            logKernelSuIdentity(callback)
            return 0
        }

        val logFile = File(workDir, "cve-2026-98260.log")
        runCatching { logFile.delete() }
        statusCallback.onStatus("c1", "in_progress")
        callback.onLog("<b> starting CVE-2026-98260 UAF process (fake cred -> ksud late-load)")
        // "fake" is YuKongA's chain: swing task->cred at a full-cap fake cred,
        // clear selinux_state.enforcing, then exec ksud late-load for this KMI.
        val process = ProcessBuilder(payloadFile.absolutePath, "fake")
            .directory(workDir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(logFile))
            .apply {
                if (resolved != null) {
                    environment()["GHOSTLOCK_KSUD"] = resolved.file.absolutePath
                    environment()["GHOSTLOCK_KSU_PKG"] = resolved.packageName
                }
            }
            .start()
        val reader = Thread({
            tailSimpleLog(logFile, process, callback)
        }, "ghostlock-cve98260-log").apply {
            isDaemon = true
            start()
        }
        val deadline = System.currentTimeMillis() + 900_000L
        var rooted = false
        while (System.currentTimeMillis() < deadline) {
            if (isKernelSuLive()) {
                rooted = true
                break
            }
            Thread.sleep(1_000)
        }
        if (!rooted && isKernelSuLive()) rooted = true
        if (rooted) {
            completeAllSteps(statusCallback)
            callback.onLog("<b> ROOT_SUCCESS backend=cve_2026_98260+kernelsu")
            logKernelSuIdentity(callback)
            runCatching { reader.interrupt() }
            return 0
        }
        runCatching { reader.interrupt() }
        runCatching { reader.join(1_000) }
        markStopped(statusCallback)
        callback.onLog("<s> CVE-2026-98260: KernelSU did not come up; device was not rebooted")
        return EXIT_NO_MATCH
    }

    /**
     * CVE-2026-64560 backend（2026-11-27 恢复）。shell-uid 服务把三份载荷写到
     * /data/local/tmp，只跑 fanout（rotate 在 OS4.0.0.22 上 Stage 0 panic，上游同样
     * 不自动跑），等到当前启动期的临时 su 守护进程。免内核 profile / 版本门槛。
     */
    override fun runCve64560(
        fanout: ByteArray,
        rotate: ByteArray,
        su: ByteArray,
        debugDir: String?,
        callback: IGhostlockCallback,
        statusCallback: IGhostlockStatusCallback,
    ) {
        if (!running.compareAndSet(false, true)) {
            callback.onLog("<s> error: another GhostLock process is already running")
            callback.onComplete(1)
            return
        }
        Thread({
            val exitCode = runCatching {
                runCve64560Blocking(fanout, rotate, su, debugDir, callback, statusCallback)
            }.getOrElse { error ->
                runCatching { callback.onLog("<s> error: ${error.message}") }
                1
            }
            running.set(false)
            runCatching { callback.onComplete(exitCode) }
        }, "ghostlock-cve64560-runner").start()
    }

    /** True once the late-loaded KernelSU module is listed by the kernel. */
    private fun isKernelSuLive(): Boolean = runCatching {
        File("/proc/modules").readLines().any { it.startsWith("kernelsu ") }
    }.getOrDefault(false)

    /** Report the real KernelSU context so success is not a proxy signal. */
    private fun logKernelSuIdentity(callback: IGhostlockCallback) {
        /* KernelSU does not always expose su on PATH, so probe the known
         * install locations as well as the plain command. */
        val probes = listOf(
            listOf("su", "-c", "id"),
            listOf("/data/adb/ksu/bin/su", "-c", "id"),
            listOf("/system/bin/su", "-c", "id"),
        )
        val out = probes.firstNotNullOfOrNull(::runProbe)
        callback.onLog("<s> ksu identity: ${out ?: "(su unavailable)"}")
    }

    private fun runProbe(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(15, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotEmpty() }
    }.getOrNull()

    private inner class SimpleLogPump(
        private val logFile: File,
        private val callback: IGhostlockCallback,
    ) {
        private var readOffset = 0L
        private val pending = StringBuilder()

        fun pump() {
            if (!logFile.isFile) return
            RandomAccessFile(logFile, "r").use { handle ->
                if (readOffset > handle.length()) {
                    readOffset = 0
                    pending.clear()
                }
                handle.seek(readOffset)
                var line = handle.readLine()
                while (line != null) {
                    if (line.isNotEmpty()) pending.appendLine(line)
                    line = handle.readLine()
                }
                readOffset = handle.length()
            }
            emit()
        }

        fun finish() {
            if (!logFile.isFile) return
            runCatching {
                RandomAccessFile(logFile, "r").use { handle ->
                    if (readOffset > handle.length()) {
                        readOffset = 0
                        pending.clear()
                    }
                    handle.seek(readOffset)
                    var line = handle.readLine()
                    while (line != null) {
                        if (line.isNotEmpty()) pending.appendLine(line)
                        line = handle.readLine()
                    }
                }
            }
            emit()
        }

        private fun emit(value: String) {
            for (part in value.lines()) {
                val text = stripAnsi(part)
                if (text.isNotBlank()) runCatching { callback.onLog(text) }
            }
        }

        private fun emit() {
            emit(pending.toString())
            pending.clear()
        }
    }

    private fun tailSimpleLog(
        logFile: File,
        process: java.lang.Process,
        callback: IGhostlockCallback,
    ) {
        val pump = SimpleLogPump(logFile, callback)
        while (!Thread.currentThread().isInterrupted) {
            runCatching { pump.pump() }
            if (!process.isAlive) break
            Thread.sleep(200)
        }
        pump.finish()
    }

    private fun runCve64560Blocking(
        fanout: ByteArray,
        rotate: ByteArray,
        su: ByteArray,
        debugDir: String?,
        callback: IGhostlockCallback,
        statusCallback: IGhostlockStatusCallback,
    ): Int {
        require(Process.myUid() == Process.SHELL_UID) {
            "Shizuku UserService uid=${Process.myUid()}, expected ${Process.SHELL_UID}"
        }
        val procStatus = File("/proc/self/status").readText()
        require(Regex("(?m)^Seccomp:\\s*0$").containsMatchIn(procStatus)) {
            "Shizuku UserService is still seccomp-filtered"
        }
        callback.onLog("<s> CVE-2026-64560 backend: uid=${Process.myUid()} Seccomp=0 (gate-free)")
        val workDir = File("/data/local/tmp/ghostlock-app/cve64560").apply {
            require(isDirectory || mkdirs()) { "cannot create $absolutePath" }
        }
        /* A preserved payload is deliberately left in the kernel (SIGSTOP)
         * instead of being torn down, so a second attempt must not run on top
         * of it. The marker is cleared automatically after a reboot. */
        val preservedMarker = File(workDir, "preserved.pid")
        if (preservedMarker.isFile) {
            val heldPid = preservedMarker.readText().trim().toIntOrNull()
            if (heldPid != null && File("/proc/$heldPid").exists()) {
                callback.onLog("<s> a preserved payload is still held (pid=$heldPid); reboot manually before another attempt")
                markStopped(statusCallback)
                return EXIT_NO_MATCH
            }
            runCatching { preservedMarker.delete() }
        }
        waitForThermal(callback)
        val fanoutFile = writePayload(workDir, "cve-64560-fanout", fanout)
        val rotateFile = writePayload(workDir, "cve-64560-rotate", rotate)
        val suFile = writePayload(workDir, "cve-64560-su", su)
        callback.onLog("<k> rotate payload installed but not auto-run: ${rotateFile.name}")
        callback.onLog("<s> payload dir: ${workDir.absolutePath}")
        if (!debugDir.isNullOrEmpty()) callback.onLog("<s> debug dir: $debugDir")

        /* A daemon from an earlier run in this boot is already the objective. */
        if (checkTemporarySu(suFile)) {
            callback.onLog("<s> temporary-su daemon is already running from this boot")
            completeAllSteps(statusCallback)
            logRootIdentity(suFile, callback)
            return 0
        }

        /* Rotate is intentionally not run automatically on this target: the
         * rotate payload panics in Stage 0 on the OS4.0.0.22 build and reboots
         * the device. Fanout is stable (clean miss / preserved state), so the
         * app only runs fanout. The rotate asset stays packaged for manual or
         * future per-build use.
         *
         * A clean miss leaves the kernel untouched, so retry fanout a few times
         * in the same boot. A preserve freezes the payload instead. */
        callback.onLog("<s> rotate strategy disabled on this build (it panics); fanout only")
        val rounds = FANOUT_ROUNDS
        for (round in 1..rounds) {
            callback.onLog("<b> CVE-2026-64560 fanout round $round/$rounds")
            statusCallback.onStatus("c1", "in_progress")
            runPreflight(fanoutFile, callback)
            val outcome = runStrategy(fanoutFile, suFile, callback, statusCallback)
            if (outcome.rooted) {
                runCatching { File(workDir, "preserved.pid").delete() }
                completeAllSteps(statusCallback)
                callback.onLog("<b> ROOT_SUCCESS strategy=fanout round=$round")
                callback.onLog("<s> root shell: $suFile --client (or: adb shell ${suFile.absolutePath} --client)")
                logRootIdentity(suFile, callback)
                return 0
            }
            if (outcome.protectedState) {
                /* The payload declared a preserved/corrupted kernel state. It
                 * has been SIGSTOPped and preserved.pid written; do not run
                 * another round in the same boot. The device stays up. */
                callback.onLog("<s> fanout: no match; kernel state preserved, stopping without reboot")
                markStopped(statusCallback)
                return EXIT_NO_MATCH
            }
            callback.onLog("<s> fanout: clean miss (round $round/$rounds); device not rebooted")
        }
        callback.onLog("<s> CVE-2026-64560: no root after $rounds rounds; device was not rebooted")
        markStopped(statusCallback)
        return EXIT_NO_MATCH
    }

    private data class StrategyOutcome(
        val rooted: Boolean,
        val protectedState: Boolean,
        val timedOut: Boolean,
    )

    private fun runStrategy(
        binary: File,
        suFile: File,
        callback: IGhostlockCallback,
        statusCallback: IGhostlockStatusCallback,
    ): StrategyOutcome {
        /* The payload writes to a file, not a pipe: a slow binder callback must
         * never apply backpressure to the race (same rule as the native path). */
        val logFile = File(binary.parentFile, "${binary.name}.log")
        runCatching { logFile.delete() }
        val process = ProcessBuilder(binary.absolutePath, "--run")
            .directory(binary.parentFile)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.to(logFile))
            .start()
        val protectedState = AtomicBoolean(false)
        val tailer = StrategyLogTailer(logFile, protectedState, callback, statusCallback)
        val reader = Thread({
            while (!Thread.currentThread().isInterrupted) {
                runCatching { tailer.pump() }
                if (!process.isAlive) break
                Thread.sleep(100)
            }
            tailer.finish()
        }, "ghostlock-cve64560-log").apply {
            isDaemon = true
            start()
        }
        // The root child inherits this stdin and executes both commands once
        // the credential stage lands.
        runCatching {
            process.outputStream.write("${suFile.absolutePath} --daemon\nexit\n".toByteArray())
            process.outputStream.flush()
        }
        val deadline = System.currentTimeMillis() + STRATEGY_TIMEOUT_MS
        var rooted = false
        var preserved = false
        while (System.currentTimeMillis() < deadline) {
            if (checkTemporarySu(suFile)) {
                rooted = true
                break
            }
            if (!process.isAlive) break
            if (protectedState.get()) {
                /* Do NOT destroy the payload here: it is spinning in
                 * hold_cred_unknown() with a possibly corrupted kernel state.
                 * Tearing it down can panic the kernel. Freeze it instead and
                 * report a plain miss; the device stays up. */
                preserved = true
                break
            }
            Thread.sleep(1_000)
        }
        if (!rooted && checkTemporarySu(suFile)) rooted = true
        /* A timeout is handled like a preserve: never tear down a process that
         * may still hold corrupted kernel state. */
        val timedOut = !rooted && System.currentTimeMillis() >= deadline
        if (!rooted && (preserved || timedOut)) {
            preserved = true
            /* 上游直接 process.pid()；Android 的 java.lang.Process 没公开 pid()，
             * 只有 libcore 的 ProcessImpl 有 ⇒ 反射取，取不到就保持运行（不销毁，
             * 销毁可能内核 panic）。 */
            val pid: Int? = runCatching {
                process.javaClass.getMethod("pid").invoke(process) as? Int
            }.getOrNull()
            val frozen = pid != null &&
                runCatching { Os.kill(pid, OsConstants.SIGSTOP) }.isSuccess
            callback.onLog(
                "<s> ${binary.name}: kernel state preserved; payload pid=${pid ?: "?"} " +
                    (if (frozen) "frozen with SIGSTOP" else "left running") +
                    "; device not rebooted",
            )
            if (pid != null) {
                runCatching { File(binary.parentFile, "preserved.pid").writeText("$pid ${binary.name}") }
            }
            reader.interrupt()
            runCatching { reader.join(1_000) }
            return StrategyOutcome(false, true, timedOut)
        }
        if (rooted) {
            process.waitFor(15, TimeUnit.SECONDS)
        }
        reader.join(3_000)
        if (process.isAlive) process.destroyForcibly()
        return StrategyOutcome(rooted, false, false)
    }

    private inner class StrategyLogTailer(
        private val logFile: File,
        private val protectedState: AtomicBoolean,
        private val callback: IGhostlockCallback,
        private val statusCallback: IGhostlockStatusCallback,
    ) {
        private var readOffset = 0L
        private val pending = StringBuilder()

        fun pump() {
            if (!logFile.isFile) return
            RandomAccessFile(logFile, "r").use { handle ->
                if (readOffset > handle.length()) {
                    readOffset = 0
                    pending.clear()
                }
                handle.seek(readOffset)
                while (true) {
                    val byte = handle.read()
                    if (byte == -1) break
                    if (byte == '\n'.code) {
                        emit(pending.toString())
                        pending.clear()
                    } else {
                        pending.append(byte.toChar())
                    }
                }
                /* Partial lines stay buffered; the next pump reads only the
                 * bytes appended after this point. */
                readOffset = handle.filePointer
            }
        }

        fun finish() {
            runCatching { pump() }
            if (pending.isNotEmpty()) emit(pending.toString())
        }

        private fun emit(line: String) {
            val text = stripAnsi(line)
            if (text.isBlank()) return
            if (PROTECTED_MARKERS.any(text::contains)) protectedState.set(true)
            advanceStep(text, statusCallback)
            runCatching { callback.onLog(text) }
        }
    }

    private fun runPreflight(binary: File, callback: IGhostlockCallback) {
        runCatching {
            val process = ProcessBuilder(binary.absolutePath, "--preflight")
                .directory(binary.parentFile)
                .redirectErrorStream(true)
                .start()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(120, TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
            output.lineSequence().filter { it.isNotBlank() }.forEach {
                callback.onLog(stripAnsi(it))
            }
        }.onFailure { callback.onLog("<s> warning: preflight failed: ${it.message}") }
    }

    /** Match the upstream runner: do not start the race on a hot CPU. */
    private fun waitForThermal(callback: IGhostlockCallback) {
        val limitMilliC = 55_000
        val deadline = System.currentTimeMillis() + 10 * 60 * 1_000L
        var lastLog = 0L
        while (System.currentTimeMillis() < deadline) {
            val max = cpuThermalMilliC()
            if (max == null) {
                callback.onLog("<s> thermal zones unavailable; starting without a thermal gate")
                return
            }
            if (max <= limitMilliC) {
                callback.onLog("<s> thermal ok: max_cpu=${max / 1000.0}C")
                return
            }
            val now = System.currentTimeMillis()
            if (now - lastLog > 10_000L) {
                callback.onLog("<s> thermal ${max / 1000.0}C > ${limitMilliC / 1000.0}C; waiting for CPU to cool")
                lastLog = now
            }
            Thread.sleep(5_000)
        }
        callback.onLog("<s> thermal wait expired; starting anyway")
    }

    private fun cpuThermalMilliC(): Int? {
        val zones = File("/sys/class/thermal").listFiles { file ->
            file.name.startsWith("thermal_zone")
        } ?: return null
        var maximum = -1
        for (zone in zones) {
            val type = File(zone, "type").takeIf { it.isFile }
                ?.readText()?.trim().orEmpty()
            if (type != "cpu_therm" && !type.startsWith("cpu-") && !type.startsWith("cpuss-")) continue
            val value = File(zone, "temp").takeIf { it.isFile }
                ?.readText()?.trim()?.toIntOrNull() ?: continue
            if (value > maximum) maximum = value
        }
        return maximum.takeIf { it >= 0 }
    }

    private fun checkTemporarySu(suFile: File): Boolean = runCatching {
        val process = ProcessBuilder(suFile.absolutePath, "--check")
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor(5, TimeUnit.SECONDS)
        if (process.isAlive) process.destroyForcibly()
        process.exitValue() == 0 && output.contains("TEMP_SU_DAEMON_READY")
    }.getOrDefault(false)

    private fun logRootIdentity(suFile: File, callback: IGhostlockCallback) {
        runCatching {
            val process = ProcessBuilder(suFile.absolutePath, "--client")
                .redirectErrorStream(true)
                .start()
            process.outputStream.write("id\nexit\n".toByteArray())
            process.outputStream.close()
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor(5, TimeUnit.SECONDS)
            if (process.isAlive) process.destroyForcibly()
            val identity = output.lineSequence().lastOrNull { it.contains("uid=") }
                ?: output.trim()
            callback.onLog("<s> root identity: $identity")
        }.onFailure { callback.onLog("<s> warning: root identity check failed: ${it.message}") }
    }

    private fun advanceStep(line: String, statusCallback: IGhostlockStatusCallback) {
        when {
            line.contains("STAGE0_GATE_PASS") || line.contains("BOOTID_WRITE_PASS") -> {
                statusCallback.onStatus("c1", "completed")
                statusCallback.onStatus("c2", "in_progress")
            }

            line.contains("MISC_BRIDGE_READ_PASS") -> {
                statusCallback.onStatus("c1", "completed")
                statusCallback.onStatus("c2", "in_progress")
            }

            line.contains("ROOT_CHILD_HOLD_PASS") -> {
                statusCallback.onStatus("c2", "completed")
                statusCallback.onStatus("c3", "in_progress")
            }

            line.contains("TEMP_SU_DAEMON_STARTED") -> {
                statusCallback.onStatus("c3", "completed")
            }
        }
    }

    private fun writePayload(workDir: File, name: String, bytes: ByteArray): File {
        val temporary = File(workDir, "$name.tmp")
        val target = File(workDir, name)
        temporary.writeBytes(bytes)
        if (target.exists() && !target.delete()) {
            throw IllegalStateException("cannot replace ${target.absolutePath}")
        }
        require(temporary.renameTo(target)) { "cannot install ${target.absolutePath}" }
        require(
            target.setReadable(true, false) &&
                target.setExecutable(true, false) &&
                target.setWritable(false, false),
        ) { "cannot chmod ${target.absolutePath}" }
        return target
    }

    private fun markStopped(statusCallback: IGhostlockStatusCallback) {
        for (step in listOf("c1", "c2", "c3")) {
            runCatching { statusCallback.onStatus(step, "stopped") }
        }
    }

    private fun completeAllSteps(statusCallback: IGhostlockStatusCallback) {
        for (step in listOf("c1", "c2", "c3")) {
            runCatching { statusCallback.onStatus(step, "completed") }
        }
    }

    private fun stripAnsi(value: String): String = value.replace(ANSI_REGEX, "")

    override fun destroy() {
        if (!running.get()) kotlin.system.exitProcess(0)
    }
}
