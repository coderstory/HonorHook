package top.honorhook.ui.xhsbatch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 任务持久化：filesDir/xhs_batch/tasks.json 单文件（技术方案 §3.0）。
 * 单列表低频写，不上 Room。写盘全量重写 + tmp rename 原子落盘；
 * Controller 侧另有 500ms debounce 合并（节流在调用方，本类只保证单次写原子）。
 */
class FileXhsBatchTaskStore(context: Context) : XhsBatchTaskStore {

    private val file = File(File(context.filesDir, "xhs_batch"), "tasks.json")

    @Synchronized
    override fun list(): List<XhsBatchTaskRecord> {
        if (!file.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(file.readText())
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                XhsBatchTaskRecord(
                    taskId = o.getString("taskId"),
                    userId = o.getString("userId"),
                    createdAt = o.getLong("createdAt"),
                    updatedAt = o.getLong("updatedAt"),
                    state = runCatching { XhsBatchState.valueOf(o.getString("state")) }
                        .getOrDefault(XhsBatchState.Interrupted),
                    notesFound = o.optInt("notesFound"),
                    notesProcessed = o.optInt("notesProcessed"),
                    notesFailed = o.optInt("notesFailed"),
                    imagesDone = o.optInt("imagesDone"),
                    videosDone = o.optInt("videosDone"),
                    totalBytes = o.optLong("totalBytes"),
                    dirPath = o.optString("dirPath"),
                    notes = o.optJSONArray("notes")?.let { ja -> (0 until ja.length()).map { ja.optString(it) } } ?: emptyList(),
                    nextIndex = o.optInt("nextIndex"),
                    failedNotes = o.optJSONArray("failedNotes")?.let { ja -> (0 until ja.length()).map { ja.optString(it) } } ?: emptyList(),
                )
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    override fun upsert(record: XhsBatchTaskRecord) {
        val rest = list().filterNot { it.taskId == record.taskId }
        val next = (listOf(record) + rest).take(XhsBatchPacing.MAX_TASK_RECORDS)
        writeAll(next)
    }

    @Synchronized
    override fun delete(taskId: String) {
        writeAll(list().filterNot { it.taskId == taskId })
    }

    private fun writeAll(records: List<XhsBatchTaskRecord>) {
        val array = JSONArray()
        records.forEach { r ->
            array.put(
                JSONObject()
                    .put("taskId", r.taskId)
                    .put("userId", r.userId)
                    .put("createdAt", r.createdAt)
                    .put("updatedAt", r.updatedAt)
                    .put("state", r.state.name)
                    .put("notesFound", r.notesFound)
                    .put("notesProcessed", r.notesProcessed)
                    .put("notesFailed", r.notesFailed)
                    .put("imagesDone", r.imagesDone)
                    .put("videosDone", r.videosDone)
                    .put("totalBytes", r.totalBytes)
                    .put("dirPath", r.dirPath)
                    .put("notes", org.json.JSONArray(r.notes))
                    .put("nextIndex", r.nextIndex)
                    .put("failedNotes", org.json.JSONArray(r.failedNotes)),
            )
        }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(file)) {
            // rename 跨场景失败（极少数 ROM）：兜底直写，宁丢原子性不丢记录
            file.writeText(array.toString())
            tmp.delete()
        }
    }
}
