package top.honorhook.ui.toolbox

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import top.honorhook.ui.scope.RootShell
import top.honorhook.ui.scope.SafeCommands

/**
 * 快照库（28 号 §2 / 12 号恢复机制）：冻结批前强制 dumpsys package 全文
 * （含每 userId enabled 状态）。root 失效后已存快照仍可读——恢复的依据必须先于恢复存在。
 */
object SnapshotStore {

    data class Result(val ok: Boolean, val file: File?, val error: String = "")

    /** 调用方须在 IO 线程。dumpsys 大输出走 15s 超时（28 号 §1 批量链放宽）。 */
    suspend fun snapshot(context: Context): Result = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "toolbox/snapshots").apply { mkdirs() }
        val out = File(dir, "dumpsys-pkg-${java.time.LocalDate.now()}.txt")
        when (val r = RootShell.exec(SafeCommands.snapshotScript(), timeoutSeconds = 15)) {
            is RootShell.Result.Ok -> {
                out.writeText(
                    buildString {
                        appendLine("#meta ts=${System.currentTimeMillis()} lines=${r.stdout.lineSequence().count()}")
                        append(r.stdout)
                    },
                )
                Result(true, out)
            }
            else -> Result(false, null, ToolExecutor.describe(r))
        }
    }

    fun latest(context: Context): File? =
        File(context.filesDir, "toolbox/snapshots").listFiles()
            ?.filter { it.name.startsWith("dumpsys-pkg-") }
            ?.maxByOrNull { it.name }
}
