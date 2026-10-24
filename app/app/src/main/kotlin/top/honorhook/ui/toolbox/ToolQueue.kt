package top.honorhook.ui.toolbox

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import top.honorhook.ui.scope.SafeCommands

/**
 * 事务化执行队列（28 号 §2）：逐项落盘，**中断续跑不重放 done**——
 * 状态机与 Android 解耦在 [ToolQueue.Core]（JVM 单测钉这个语义）。
 *
 * 持久化用行式格式而非 JSON：模块刻意无 kotlinx-serialization 插件（build.gradle 注），
 * 而所有写入字段都已过 SafeCommands/包名白名单（无 `|` 无换行），行式最省依赖。
 * 格式：`state|ts|type|args...`（Freeze/Unfreeze: pkg；Switch: table,key,value）。
 */
class ToolQueue(context: Context) {

    private val dir = File(context.filesDir, "toolbox").apply { mkdirs() }
    private val core = Core(
        queueFile = File(dir, "queue.txt"),
        historyDir = File(dir, "history").apply { mkdirs() },
    ) { ToolExecutor.run(context, it) }

    fun enqueue(actions: List<ToolAction>) = core.enqueue(actions)
    fun items(): List<Core.Item> = core.items()
    fun pending(): List<Core.Item> = core.pending()
    suspend fun runPending(onProgress: (Core.Item) -> Unit = {}): Core.Summary =
        withContext(Dispatchers.IO) { core.runPending(onProgress) }

    class Core(private val queueFile: File, private val historyDir: File, private val runner: suspend (ToolAction) -> ActionOutcome) {

        enum class State { PENDING, DONE, FAILED }

        data class Item(val id: String, val action: ToolAction, val state: State, val ts: Long, val error: String = "")

        data class Summary(val done: Int, val failed: Int, val failures: List<Item>)

        private val lock = Any()
        private var items: MutableList<Item> = load()

        fun enqueue(actions: List<ToolAction>) = synchronized(lock) {
            val now = System.currentTimeMillis()
            items += actions.map { Item(newId(now), it, State.PENDING, now) }
            persist()
        }

        fun items(): List<Item> = synchronized(lock) { items.toList() }
        fun pending(): List<Item> = synchronized(lock) { items.filter { it.state == State.PENDING } }

        /** 逐项串行执行；单项失败不中断（28 号 §7 ①：失败不中断+失败清单）。 */
        suspend fun runPending(onProgress: (Item) -> Unit = {}): Summary {
            var done = 0
            val failures = mutableListOf<Item>()
            for (item in pending()) {
                val outcome = runner(item.action)
                val finished = synchronized(lock) {
                    val updated = if (outcome.ok) {
                        item.copy(state = State.DONE)
                    } else {
                        item.copy(state = State.FAILED, error = sanitize(outcome.error))
                    }
                    items.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.let { idx ->
                        items[idx] = updated
                    }
                    persist()
                    updated
                }
                appendHistory(outcome)
                onProgress(finished)
                if (outcome.ok) done++ else failures += finished
            }
            return Summary(done, failures.size, failures)
        }

        private fun appendHistory(outcome: ActionOutcome) {
            runCatching {
                val a = outcome.action
                val line = buildString {
                    append(System.currentTimeMillis()).append('|')
                    append(if (outcome.ok) "OK" else "FAIL").append('|')
                    append(sanitize(outcome.readBack)).append('|')
                    append(sanitize(outcome.error)).append('|')
                    append(encode(a))
                }
                File(historyDir, "log.jsonl").appendText(line + "\n")
            }
        }

        private fun persist() {
            val body = items.joinToString("\n") { item ->
                "${item.state}|${item.ts}|${item.id}|${encode(item.action)}|${sanitize(item.error)}"
            }
            val tmp = File(queueFile.parentFile, queueFile.name + ".tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(queueFile)) {
                queueFile.writeText(body)
                tmp.delete()
            }
        }

        private fun encode(a: ToolAction): String = when (a) {
            is ToolAction.Freeze -> "Freeze|${a.pkg}"
            is ToolAction.Unfreeze -> "Unfreeze|${a.pkg}"
            is ToolAction.Switch -> "Switch|${a.table?.token ?: ""}|${a.key}|${a.value}"
        }

        private fun decode(fields: List<String>): ToolAction? = when (fields.getOrNull(0)) {
            "Freeze" -> fields.getOrNull(1)?.let { ToolAction.Freeze(it) }
            "Unfreeze" -> fields.getOrNull(1)?.let { ToolAction.Unfreeze(it) }
            "Switch" -> ToolAction.Switch(
                table = fields.getOrNull(1)?.takeIf { it.isNotEmpty() }
                    ?.let { t -> SafeCommands.Table.entries.firstOrNull { it.token == t } },
                key = fields.getOrNull(2) ?: "",
                value = fields.getOrNull(3) ?: "",
            )
            else -> null
        }

        private fun load(): MutableList<Item> {
            if (!queueFile.exists()) return mutableListOf()
            return runCatching {
                queueFile.readLines().filter { it.isNotBlank() }.mapNotNull { line ->
                    val f = line.split("|")
                    val action = decode(f.drop(3)) ?: return@mapNotNull null
                    Item(
                        id = f.getOrNull(2) ?: return@mapNotNull null,
                        action = action,
                        state = State.entries.firstOrNull { it.name == f.getOrNull(0) } ?: State.PENDING,
                        ts = f.getOrNull(1)?.toLongOrNull() ?: 0L,
                        error = f.getOrNull(4) ?: "",
                    )
                }
            }.getOrDefault(emptyList()).toMutableList()
        }

        private fun newId(now: Long): String = "$now-${(0..9999).random()}"

        private fun sanitize(s: String): String =
            s.replace("|", "/").replace("\n", " ").take(200)
    }
}
