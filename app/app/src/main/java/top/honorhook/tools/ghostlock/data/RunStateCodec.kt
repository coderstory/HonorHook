package top.honorhook.tools.ghostlock.data

import top.honorhook.tools.ghostlock.data.RunStateCodec.Steps


/**
 * Encoding/decoding for the attack step status file written by the app while a
 * native run reports steps over stdio. HOCON (a JSON superset) so the file is
 * trivially parseable and inspectable.
 */
object RunStateCodec {
    /**
     * Every step either backend reports, in execution order. `w*` belongs to
     * the CVE-2026-43499 native pipeline; `c1..c3` covers the CVE-2026-64560
     * stages (kernel bridge, root child, temporary-su daemon).
     */
    val Steps = listOf("w1a", "w1b", "w2a", "w2b", "w3a", "w3b", "w3c", "c1", "c2", "c3")

    const val NotStarted = "not_start"
    const val InProgress = "in_progress"
    const val Completed = "completed"
    /** The run stopped without reaching the objective but without a panic. */
    const val Stopped = "stopped"

    fun initial(updatedAt: Long): String = encode(Steps.associateWith { NotStarted }, updatedAt)

    fun encode(steps: Map<String, String>, updatedAt: Long): String = buildString {
        append("{\n")
        append("  \"schema_version\": 1,\n")
        append("  \"updated_at\": ").append(updatedAt).append(",\n")
        append("  \"steps\": {\n")
        val entries = steps.entries.toList()
        entries.forEachIndexed { index, (key, value) ->
            append("    \"").append(key).append("\": \"").append(value).append('"')
            if (index + 1 < entries.size) append(',')
            append('\n')
        }
        append("  }\n}\n")
    }

    /**
     * The earliest step left `in_progress` (a panic mid-step), or null. Ordered
     * by [Steps], not by the parsed map order (HOCON/JSON maps are unordered).
     */
    fun parseStuckStep(text: String): String? {
        val root = HoconSupport.parseValue(text) as? Map<*, *> ?: return null
        val steps = root["steps"] as? Map<*, *> ?: return null
        return Steps.firstOrNull { (steps[it] as? String) == InProgress }
    }
}
