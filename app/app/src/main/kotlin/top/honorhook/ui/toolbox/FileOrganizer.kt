package top.honorhook.ui.toolbox

import android.content.Context
import android.media.MediaScannerConnection
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件整理引擎 —— 规格：project/file-organizer-rules.md v2（唯一真源）。
 * 审计闭环要点（见 file-organizer-audit.md）：
 * - 删除 = 直接删（2026-11-04 用户拍板：无回收站；journal 仍记录删除项作审计线索）
 * - journal 两段式 WAL：manifest 先整体落盘(pending)，逐条执行后标 done/failed；崩溃恢复靠对账
 * - mtime 阈值分级：删除类 24h，移动类 60s
 * - 月份三级回退：L1 锚定文件名正则 → L2 前缀 → L3 mtime；失败不静默，进滞留清单
 * - 豁免/黑名单字段化（obb、标记文件、排除区、点目录 allow/deny）
 * - 媒体刷新只用 MediaScannerConnection（广播方案已废除）
 * - dry-run 零副作用（冲突检测用内存 Set 模拟已占用目标路径）
 */
object FileOrganizer {

    // ---------- 数据模型 ----------

    enum class ActionType { ARCHIVE, MERGE, DELETE }
    enum class ConflictPolicy { SUFFIX, SKIP, OVERWRITE_BACKUP }
    enum class OpType { MOVE, DELETE }
    enum class OpStatus { PENDING, DONE, FAILED, ROLLED_BACK }

    /** 月份来源（L1 文件名/L2 前缀/L3 mtime），试匹配时显式呈现 */
    enum class MonthSource { L1_NAME, L2_PREFIX, L3_MTIME }

    data class Rule(
        val id: String,
        val name: String,
        val enabled: Boolean = true,
        val builtin: Boolean = true,
        val scopeDirs: List<String>,        // 相对 /sdcard；空 = 全盘（R0）
        val recursive: Boolean = false,     // 仅 R8 true
        val topOnly: Boolean = true,        // 只扫顶层散文件
        val nameRegex: String? = null,      // 锚定正则（L1）
        val prefixMonth: Boolean = false,   // L2：^\d{6}- 取月（R6）
        val extWhitelist: Set<String>? = null,
        val extBlacklist: Set<String>? = null,
        val excludeGlobs: List<String> = emptyList(), // 相对 /sdcard 前缀排除
        val action: ActionType,
        val dirPattern: String = "",        // {YYYY} {YYYYMM} {MM} {EXT}
        val mergeDir: String = "",          // MERGE 目标
        val conflict: ConflictPolicy = ConflictPolicy.SUFFIX,
        val dryRun: Boolean = false,
        val verifiedVersion: Int = 0,
        val version: Int = 1,
    )

    data class Op(
        val opId: String,
        val ruleId: String,
        val type: OpType,
        val src: String,
        val dst: String,          // DELETE 时为 .Trash 内路径
        var status: OpStatus,
        val size: Long,
        val mtime: Long,
        var note: String = "",
    )

    data class Plan(val ops: List<Op>, val stale: List<String>)

    data class RunResult(
        val runId: String,
        val manifest: File,
        val ops: MutableList<Op>,
        val moved: Int, val deleted: Int, val freedBytes: Long, val failed: Int,
        val skippedConflict: Int, val stale: List<String>,
    )

    private val ROOT = File("/storage/emulated/0")
    private const val DELETE_AGE_MS = 24L * 3600_000      // 删除类阈值 24h
    private const val MOVE_AGE_MS = 60_000L               // 移动类阈值 60s
    private val ZERO_BYTE_KEEP = setOf(".nomedia", ".emptyshow", ".hidden", ".outside")
    private val EXT_NEVER_DELETE = setOf("obb", "pat")
    // allow 比较：rel 与清单统一带尾斜杠（审计 M1：无尾斜杠的目录自身永不相等）
    private val G3_DOTDIR_ALLOW_PREFIX = listOf(
        "Download/.csj/", "Download/.com.hihonor.collectcenter/", "Download/.td3eade_",
        "Download/ugreenPro", "DCIM/.tmfs",
    )
    private val G3_DIR_DENY = setOf("Pictures/hiddenAlbum", "Sounds/.backup", ".fileMOShare")
    val EXCLUDES = listOf(
        "Android/", "backup/", "backups/", "Honor/", "Sounds/CallRecord/", "project/",
        ".Trash/", "Documents/dshdata/", ".fileMOShare/", "Download/123云盘/",
    )

    // ---------- 内置规则（rules v2 §1/§2，共 11 条） ----------

    fun builtinRules(): List<Rule> = listOf(
        Rule("g1", "清理 tmp/temp 文件", scopeDirs = emptyList(), recursive = true, topOnly = false,
            nameRegex = """(?i).*\.(tmp|temp)$""", action = ActionType.DELETE),
        Rule("g2", "清理 0KB 文件", scopeDirs = emptyList(), recursive = true, topOnly = false,
            action = ActionType.DELETE),
        Rule("g3", "清理空目录", scopeDirs = emptyList(), recursive = true, topOnly = false,
            action = ActionType.DELETE),
        Rule("r2", "相机照片归月", scopeDirs = listOf("DCIM/Camera"), nameRegex = """^(IMG|VID)_""",
            action = ActionType.ARCHIVE, dirPattern = "DCIM/Camera/{YYYYMM}"),
        Rule("r3", "微信图归月", scopeDirs = listOf("Pictures/WeiXin"),
            action = ActionType.ARCHIVE, dirPattern = "DCIM/Camera/{YYYYMM}"),
        Rule("r4", "小红书图合并", scopeDirs = listOf("Pictures/xhsdn"),
            action = ActionType.MERGE, mergeDir = "Pictures/小红书"),
        Rule("r5", "小红书视频合并", scopeDirs = listOf("Movies/xhsdn"),
            action = ActionType.MERGE, mergeDir = "Movies/小红书"),
        Rule("r6", "酷市场图合并", scopeDirs = listOf("Pictures/CoolMarket"),
            action = ActionType.MERGE, mergeDir = "Pictures/小红书"),
        Rule("r8", "B图 隔离", scopeDirs = listOf("B图"), recursive = true, topOnly = false,
            action = ActionType.MERGE, mergeDir = "Pictures/B图"),
        Rule("r9", "缩略图缓存清理", scopeDirs = listOf("Pictures/.thumbnails"), recursive = true,
            topOnly = false, action = ActionType.DELETE),
    )

    // ---------- 月份判定（rules v2 §3） ----------

    private val L1 = Regex("""^(?:IMG|VID|Screenshot)_(\d{6})""")
    // L2：CoolMarket 前缀是 YYYYMMDD-（8 位），取前 6 位为月份（审计 M-2：旧 `^\d{6}-` 对 8 位不匹配）
    private val L2 = Regex("""^(\d{8})-""")
    // SimpleDateFormat 非线程安全（审计自审#4），object 单例里改为每次调用新建
    private fun monthOf(f: File): Pair<String, MonthSource>? {
        val b = f.name
        L1.find(b)?.let { m ->
            val ym = m.groupValues[1]
            if (validYm(ym)) return ym to MonthSource.L1_NAME
        }
        L2.find(b)?.let { m ->
            val ym = m.groupValues[1].take(6)
            if (validYm(ym)) return ym to MonthSource.L2_PREFIX
        }
        return SimpleDateFormat("yyyyMM", Locale.US).format(Date(f.lastModified())) to MonthSource.L3_MTIME
    }

    private fun validYm(ym: String): Boolean {
        val mm = ym.substring(4).toIntOrNull() ?: return false
        return ym.substring(0, 4).toIntOrNull() in 2000..2100 && mm in 1..12
    }

    // {YYYYMM} {YYYY} {MM} {EXT}；ym 来自 monthOf（L1/L2 用文件名日期，非 mtime）
    private fun renderPattern(pattern: String, ym: String, f: File): String {
        var s = pattern
        if (s.contains("{YYYYMM}")) s = s.replace("{YYYYMM}", ym)
        if (s.contains("{YYYY}")) s = s.replace("{YYYY}", ym.take(4))
        if (s.contains("{MM}")) s = s.replace("{MM}", ym.takeLast(2))
        return s.replace("{EXT}", f.extension)
    }

    // ---------- 扫描 → Plan ----------

    fun scan(context: Context, rules: List<Rule>): Plan {
        val ops = mutableListOf<Op>()
        val stale = mutableListOf<String>()
        val occupied = mutableSetOf<String>()
        val seenSrc = mutableSetOf<String>()
        var seq = 0
        val now = System.currentTimeMillis()

        val enabled = rules.filter { it.enabled }
        val protectedTargets = enabled
            .flatMap { if (it.action == ActionType.MERGE) listOf(it.mergeDir) else emptyList() }
            .map { File(ROOT, it).absolutePath }.toSet()

        fun prune(rel: String): Boolean =
            EXCLUDES.any { rel.startsWith(it) || rel == it.trimEnd('/') }

        // ── MediaStore 批量枚举：一条查询拿全盘 path/size/mtime，替代逐文件 File API stat（性能关键）──
        // 注意：.nomedia 目录内文件与未索引文件不在此列 → G3 仍走 File 遍历（空目录判定需要真实文件系统）
        val media = mutableListOf<Triple<String, Long, Long>>() // path, size, mtime
        if (enabled.any { it.id != "g3" }) {
            runCatching {
                context.contentResolver.query(
                    android.provider.MediaStore.Files.getContentUri("external"),
                    arrayOf("_data", "_size", "date_modified"), null, null, null,
                )?.use { c ->
                    while (c.moveToNext()) {
                        val p = c.getString(0) ?: continue
                        if (!p.startsWith("/storage/emulated/0/")) continue
                        media.add(Triple(p, c.getLong(1), c.getLong(2) * 1000))
                    }
                }
            }.onFailure { stale.add("⚠ MediaStore 查询失败：${it.message}（本轮只做 G3 清理）") }
        }

        fun monthFrom(name: String, mtime: Long): Pair<String, MonthSource>? {
            L1.find(name)?.let { m ->
                val ym = m.groupValues[1]
                if (validYm(ym)) return ym to MonthSource.L1_NAME
            }
            L2.find(name)?.let { m ->
                val ym = m.groupValues[1].take(6)
                if (validYm(ym)) return ym to MonthSource.L2_PREFIX
            }
            return SimpleDateFormat("yyyyMM", Locale.US).format(Date(mtime)) to MonthSource.L3_MTIME
        }

        fun emitMove(rule: Rule, path: String, size: Long, mtime: Long) {
            val name = path.substringAfterLast('/')
            val month = monthFrom(name, mtime)
            if (month == null) { stale.add("[${rule.name}] 无法判定月份: $name（未分类滞留）"); return }
            var dstDir = rule.dirPattern
            if (dstDir.contains("{YYYYMM}")) dstDir = dstDir.replace("{YYYYMM}", month.first)
            if (dstDir.contains("{YYYY}")) dstDir = dstDir.replace("{YYYY}", month.first.take(4))
            if (dstDir.contains("{MM}")) dstDir = dstDir.replace("{MM}", month.first.takeLast(2))
            dstDir = dstDir.replace("{EXT}", name.substringAfterLast('.', ""))
            val base = File(ROOT, dstDir)
            val key = File(base, name).absolutePath
            var finalKey = key
            if (File(key).exists() || key in occupied) {
                if (rule.conflict == ConflictPolicy.SKIP) { stale.add("[${rule.name}] 同名跳过: $name"); return }
                val b = name.substringBeforeLast('.')
                val e = name.substringAfterLast('.', "")
                var i = 1
                while (true) {
                    val cand = if (e.isEmpty()) File(base, "${b}_$i").absolutePath else File(base, "${b}_$i.$e").absolutePath
                    if (!File(cand).exists() && cand !in occupied) { finalKey = cand; occupied.add(cand); break }
                    i++
                }
            } else occupied.add(key)
            ops.add(Op("op${seq++}", rule.id, OpType.MOVE, path, finalKey, OpStatus.PENDING, size, mtime))
        }

        for (rule in enabled) {
            if (rule.id == "g3") continue
            if (rule.action == ActionType.MERGE && !File(ROOT, rule.mergeDir).exists()) continue
            val reg = rule.nameRegex?.let { runCatching { Regex(it) }.getOrNull() }
            if (rule.nameRegex != null && reg == null) { stale.add("[${rule.name}] 正则非法: ${rule.nameRegex}"); continue }
            val ageLimit = if (rule.action == ActionType.DELETE) DELETE_AGE_MS else MOVE_AGE_MS
            val wholeRoot = rule.scopeDirs.isEmpty()

            for ((path, size, mtime) in media) {
                val rel = path.removePrefix("/storage/emulated/0/")
                if (wholeRoot) {
                    if (prune(rel)) continue
                } else {
                    val scope = rule.scopeDirs.firstOrNull { rel == it || rel.startsWith("$it/") } ?: continue
                    if (rule.topOnly && !rule.recursive && rel.removePrefix("$scope/").contains('/')) continue
                }
                if (protectedTargets.contains(path) || seenSrc.contains(path)) continue
                // MediaStore Files 表在部分 ROM 会把【目录】也索引成 size=0 的行（用户实测：
                // G2 计划删除 Pictures/DCIM/Download 等顶层目录）→ 一律先判目录并跳过
                if (java.io.File(path).isDirectory) continue
                val ext = path.substringAfterLast('.', "").lowercase()
                if (reg != null && !reg.containsMatchIn(path.substringAfterLast('/'))) continue
                if (rule.extWhitelist != null && ext !in rule.extWhitelist) continue
                if (rule.extBlacklist != null && ext in rule.extBlacklist) continue
                if (now - mtime < ageLimit) continue

                when (rule.action) {
                    ActionType.DELETE -> {
                        if (rule.id == "g2") {
                            if (size != 0L) continue
                            val nm = path.substringAfterLast('/')
                            if (nm in ZERO_BYTE_KEEP) continue
                            if (ext in EXT_NEVER_DELETE) continue
                        }
                        seenSrc.add(path)
                        ops.add(Op("op${seq++}", rule.id, OpType.DELETE, path, "", OpStatus.PENDING, size, mtime))
                    }
                    ActionType.ARCHIVE -> {
                        seenSrc.add(path)
                        emitMove(rule, path, size, mtime)
                    }
                    ActionType.MERGE -> {
                        seenSrc.add(path)
                        val name = path.substringAfterLast('/')
                        val dstDir = File(ROOT, rule.mergeDir)
                        val key = File(dstDir, name).absolutePath
                        var finalKey = key
                        if (File(key).exists() || key in occupied) {
                            if (rule.conflict == ConflictPolicy.SKIP) { stale.add("[${rule.name}] 同名跳过: $name"); continue }
                            val b = name.substringBeforeLast('.')
                            val e = name.substringAfterLast('.', "")
                            var i = 1
                            while (true) {
                                val cand = if (e.isEmpty()) File(dstDir, "${b}_$i").absolutePath else File(dstDir, "${b}_$i.$e").absolutePath
                                if (!File(cand).exists() && cand !in occupied) { finalKey = cand; occupied.add(cand); break }
                                i++
                            }
                        } else occupied.add(key)
                        ops.add(Op("op${seq++}", rule.id, OpType.MOVE, path, finalKey, OpStatus.PENDING, size, mtime))
                    }
                }
            }
        }

        // ── G3 空目录：需要真实文件系统判定（MediaStore 无目录概念），带目录级剪枝的 File 遍历 ──
        val g3 = enabled.find { it.id == "g3" }
        if (g3 != null) {
            fun walk(dir: File, rel: String): Boolean {
                val children = dir.listFiles() ?: return false
                var empty = true
                for (c in children) {
                    val relC = if (rel.isEmpty()) c.name else "$rel/${c.name}"
                    if (prune(relC)) { empty = false; continue }
                    if (c.isDirectory) {
                        if (!walk(c, relC)) empty = false
                    } else empty = false
                }
                if (empty && dir != ROOT) {
                    val last = rel.substringAfterLast('/')
                    if (last.startsWith(".") && G3_DOTDIR_ALLOW_PREFIX.none { (rel + "/").startsWith(it) || rel.startsWith(it) }) return false
                    if (G3_DIR_DENY.any { rel == it || rel.startsWith("$it/") }) return false
                    ops.add(Op("op${seq++}", g3.id, OpType.DELETE, dir.absolutePath, "", OpStatus.PENDING, 0, dir.lastModified(), note = "rmdir"))
                }
                return empty
            }
            walk(ROOT, "")
        }

        return Plan(ops, stale)
    }

    /** 冲突处理（rules v2 §4.3）。occupied: 内存模拟，dry-run 也能检出碰撞 */
    private fun uniqueDst(
        dstDir: File, name: String, policy: ConflictPolicy,
        occupied: MutableSet<String>, ops: MutableList<Op>, stale: MutableList<String>, ruleName: String,
    ): String? {
        val key = "$dstDir/$name"
        val existsOnDisk = File(key).exists() || key in occupied
        if (!existsOnDisk) { occupied.add(key); return key }
        when (policy) {
            ConflictPolicy.SKIP -> { stale.add("[$ruleName] 同名跳过: $name"); return null }
            ConflictPolicy.OVERWRITE_BACKUP -> { return key } // 直接覆盖（无回收站后无备份层）
            ConflictPolicy.SUFFIX -> {
                val base = name.substringBeforeLast('.')
                val ext = name.substringAfterLast('.', "")
                var i = 1
                while (true) {
                    val cand = if (ext.isEmpty()) "$dstDir/${base}_$i" else "$dstDir/${base}_$i.$ext"
                    if (File(cand).exists() || cand in occupied) { i++; continue }
                    occupied.add(cand); return cand
                }
            }
        }
    }

    @Volatile private var currentRunId: String? = null
    @Volatile private var cancelRequested = false
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 请求取消当前运行：当前项完成后停止，剩余项标 FAILED/已取消（M1 闭环） */
    fun cancelRun() { cancelRequested = true }

    // ---------- 执行（WAL 两段式，rules v2 §4.2） ----------

    fun execute(context: Context, plan: Plan, excluded: Set<String>, dryRun: Boolean,
                onProgress: ((Int, Int) -> Unit)? = null): RunResult? {
        if (dryRun) return null // dry-run 由 UI 层直接展示 plan，不落盘
        // 引擎级并发防护：UI 按钮状态之外再挡一层（审计自审#5）
        if (!busy.compareAndSet(false, true)) return null
        try {
            // runId 带毫秒（审计 m3：秒级可撞）
            val runId = "run-${SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())}"
            currentRunId = runId
            cancelRequested = false
            val dir = File(context.filesDir, "toolbox/file-organizer").apply { mkdirs() }
            val manifest = File(dir, "$runId.json")

            // 用户拍板（2026-11-04）：删除 = 直接删，不做回收站（回收站必须配还原 UI，否则是半成品抽象）。
            // journal 仍记录删除项（审计线索），但 DELETE 不可撤销，undo 只回滚 MOVE。
            // R8 隐私隔离：目标目录 .nomedia 必须先写（审计 M-3），否则合并瞬间进相册时间线
            val ops = plan.ops.filter { it.opId !in excluded }.toMutableList()
            if (ops.any { it.ruleId == "r8" }) {
                File(ROOT, "Pictures/B图").apply { mkdirs() }
                File(ROOT, "Pictures/B图/.nomedia").writeText("")
            }
            writeManifest(manifest, runId, ops)   // ① 先整体落盘（WAL）

            var moved = 0; var deleted = 0; var freed = 0L; var failed = 0
            val touched = mutableListOf<String>()
            // manifest 节流回写：正确性由「每条先落 pending + 崩溃后 reconcile 按 dst 存在对账」保证，
            // 全量重写是 O(n²) IO（审计自审#3），故每 20 条刷一次盘 + 收尾必刷
            var cancelled = false
            for ((idx, op) in ops.withIndex()) {
                if (cancelRequested) { cancelled = true; op.status = OpStatus.FAILED; op.note = "已取消"; failed++; continue }
                val src = File(op.src)
                when (op.type) {
                    OpType.DELETE -> {
                        if (op.note == "rmdir") {
                            if (src.listFiles()?.isEmpty() == true && src.delete()) { op.status = OpStatus.DONE; deleted++ }
                            else { op.status = OpStatus.FAILED; op.note = "目录非空或删除失败"; failed++ }
                        } else {
                            if (src.delete()) { op.status = OpStatus.DONE; deleted++; freed += op.size }
                            else { op.status = OpStatus.FAILED; op.note = "删除失败"; failed++ }
                        }
                    }
                    OpType.MOVE -> {
                        File(op.dst).parentFile?.mkdirs()
                        if (move(src, File(op.dst))) { op.status = OpStatus.DONE; moved++; touched += op.dst }
                        else { op.status = OpStatus.FAILED; op.note = "移动失败"; failed++ }
                    }
                }
                onProgress?.invoke(idx + 1, ops.size)   // 逐文件进度（UI hero 实时显示）
                if (idx % 20 == 19) writeManifest(manifest, runId, ops)
            }
            writeManifest(manifest, runId, ops) // ② 收尾必刷
            refreshMedia(context, touched)
            currentRunId = null
            return RunResult(runId, manifest, ops, moved, deleted, freed, failed, 0,
                plan.stale + if (cancelled) listOf("⚠ 用户取消：剩余 ${ops.count { it.note == "已取消" }} 项未执行") else emptyList())
        } finally {
            busy.set(false)
        }
    }

    /** renameTo 优先，跨目录失败退 copy+delete（FUSE）；copy 半截残留也要清（审计 m2） */
    private fun move(src: File, dst: File): Boolean {
        if (!src.exists()) return false
        if (src.renameTo(dst)) return true
        return try {
            src.inputStream().use { i -> dst.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            if (dst.length() == src.length()) src.delete() else { dst.delete(); false }
        } catch (_: Exception) {
            dst.delete()
            false
        }
    }

    /** 原子写（tmp+rename）：崩溃不留半文件喂坏 reconcile（审计 M2） */
    private fun writeManifest(f: File, runId: String, ops: List<Op>) {
        val o = JSONObject().put("runId", runId).put("ts", System.currentTimeMillis())
        val arr = JSONArray()
        for (op in ops) arr.put(JSONObject()
            .put("opId", op.opId).put("ruleId", op.ruleId)
            .put("type", op.type.name).put("status", op.status.name)
            .put("src", op.src).put("dst", op.dst)
            .put("size", op.size).put("mtime", op.mtime).put("note", op.note))
        o.put("ops", arr)
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(o.toString(2))
        if (!tmp.renameTo(f)) {
            // 同名覆盖失败（FUSE 个别场景）退回直接写
            f.writeText(o.toString(2)); tmp.delete()
        }
    }

    /** 崩溃对账：dst 存在 → 补 done；src 存在 → 保持 pending 可重放。半文件/损坏 manifest 跳过（审计 M2） */
    fun reconcile(context: Context): List<Pair<String, Int>> {
        if (!busy.compareAndSet(false, true)) return emptyList()
        try {
            return reconcileLocked(context)
        } finally { busy.set(false) }
    }

    private fun reconcileLocked(context: Context): List<Pair<String, Int>> {
        val dir = File(context.filesDir, "toolbox/file-organizer")
        val out = mutableListOf<Pair<String, Int>>()
        for (mf in dir.listFiles { _, n -> n.endsWith(".json") } ?: emptyArray()) {
            val j = runCatching { JSONObject(mf.readText()) }.getOrNull() ?: continue
            val arr = j.optJSONArray("ops") ?: continue
            var pending = 0
            for (i in 0 until arr.length()) {
                val op = arr.getJSONObject(i)
                if (op.getString("status") == "PENDING") {
                    pending++
                    val dstExists = File(op.getString("dst")).exists()
                    val srcExists = File(op.getString("src")).exists()
                    if (dstExists) { op.put("status", "DONE") }
                    else if (!srcExists) {
                        if (op.getString("type") == "DELETE") op.put("status", "DONE")
                        else op.put("status", "FAILED").put("note", "crash: src 消失")
                    }
                }
            }
            if (pending > 0) { mf.writeText(j.toString(2)); out.add(mf.name to pending) }
        }
        return out
    }

    /** 撤销：逆序回滚 done 项；预检 dst 现状（size+mtime 一致才动） */
    fun undo(context: Context, runId: String): Pair<Int, Int> {
        if (!busy.compareAndSet(false, true)) return -1 to 0
        try {
            return undoLocked(context, runId)
        } finally { busy.set(false) }
    }

    private fun undoLocked(context: Context, runId: String): Pair<Int, Int> {
        val mf = File(context.filesDir, "toolbox/file-organizer/$runId.json")
        if (!mf.exists()) return 0 to 0
        val j = runCatching { JSONObject(mf.readText()) }.getOrNull() ?: return 0 to 0
        val arr = j.getJSONArray("ops")
        var ok = 0; var fail = 0
        val touched = mutableListOf<String>()
        for (i in arr.length() - 1 downTo 0) {
            val op = arr.getJSONObject(i)
            if (op.getString("status") != "DONE") continue
            if (op.getString("type") != "MOVE") continue // 删除不可逆（用户拍板：无回收站），跳过
            val dst = File(op.getString("dst"))
            val src = File(op.getString("src"))
            // 预检 size+mtime 双比对（rules v2 §4.2；审计 M-4：只比 size 会被同尺寸新文件骗过）
            val sizeMatch = dst.length() == op.optLong("size", -1)
            val mtimeMatch = op.optLong("mtime", 0) <= 0 || dst.lastModified() == op.optLong("mtime", 0)
            // C1：src 已存在同名（用户在原位重建了新文件）时绝不回滚——move 的 copy 回退会截断覆盖
            if (src.exists()) { fail++; continue }
            if (!dst.exists() || !sizeMatch || !mtimeMatch) { fail++; continue }
            if (op.getString("type") == "DELETE") {
                src.parentFile?.mkdirs()
                if (move(dst, src)) { op.put("status", "ROLLED_BACK"); ok++; touched += op.getString("src") } else fail++
            } else {
                if (move(dst, src)) { op.put("status", "ROLLED_BACK"); ok++; touched += op.getString("src") } else fail++
                // 清理因此变空的月目录
                dst.parentFile?.let { p -> if (p.listFiles()?.isEmpty() == true && p != ROOT) p.delete() }
            }
        }
        mf.writeText(j.toString(2))
        refreshMedia(context, touched)
        return ok to fail
    }

    fun listRuns(context: Context): List<String> =
        File(context.filesDir, "toolbox/file-organizer")
            .listFiles { _, n -> n.endsWith(".json") }?.map { it.name.removeSuffix(".json") }?.sortedDescending() ?: emptyList()

    fun readManifest(context: Context, runId: String): List<Op> {
        val mf = File(context.filesDir, "toolbox/file-organizer/$runId.json")
        if (!mf.exists()) return emptyList()
        val arr = JSONObject(mf.readText()).getJSONArray("ops")
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Op(o.getString("opId"), o.getString("ruleId"), OpType.valueOf(o.getString("type")),
                o.getString("src"), o.getString("dst"), OpStatus.valueOf(o.getString("status")),
                o.optLong("size", 0), o.optLong("mtime", 0), o.optString("note"))
        }
    }

    private fun refreshMedia(context: Context, paths: List<String>) {
        if (paths.isEmpty()) return
        MediaScannerConnection.scanFile(context, paths.toTypedArray(), null, null)
    }

    // ---------- 试匹配（编辑器实时预览） ----------

    /**
     * 作用域顶层散文件的匹配演练：返回 (命中数, 样例路径)。正则非法返回 (-1, 空)。
     * 只读，不动文件，供编辑器「试匹配」按钮用（UI 规格 §4.2 试匹配面板）。
     */
    fun testMatch(rule: Rule, limit: Int = 5): Pair<Int, List<String>> {
        val reg = rule.nameRegex?.let { runCatching { Regex(it) }.getOrNull() } ?: if (rule.nameRegex != null) return -1 to emptyList() else null
        val now = System.currentTimeMillis()
        val ageLimit = if (rule.action == ActionType.DELETE) DELETE_AGE_MS else MOVE_AGE_MS
        var count = 0
        val samples = mutableListOf<String>()
        for (scope in rule.scopeDirs) {
            val dir = File(ROOT, scope)
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (f.isDirectory) continue
                val ext = f.extension.lowercase()
                if (reg != null && !reg.containsMatchIn(f.name)) continue
                if (rule.extWhitelist != null && ext !in rule.extWhitelist) continue
                if (now - f.lastModified() < ageLimit) continue
                count++
                if (samples.size < limit) samples.add(f.absolutePath.removePrefix(ROOT.absolutePath + "/"))
            }
        }
        return count to samples
    }

    // ---------- 规则持久化 ----------

    fun loadRules(context: Context): List<Rule> {
        val f = File(context.filesDir, "toolbox/file-organizer/rules.json")
        if (!f.exists()) return builtinRules()
        return try {
            val arr = JSONArray(f.readText())
            val stored = (0 until arr.length()).map { i -> arr.getJSONObject(i) }
            // builtin 规则以当前代码为准（内置定义升级要能触达已装设备），
            // 存储只贡献用户可调字段：enabled / conflict / verifiedVersion / version
            val out = stored.map { o ->
                val id = o.getString("id")
                val builtinDef = builtinRules().find { it.id == id }
                if (builtinDef != null) {
                    builtinDef.copy(
                        enabled = o.optBoolean("enabled", true),
                        conflict = runCatching { ConflictPolicy.valueOf(o.optString("conflict", "SUFFIX")) }.getOrDefault(builtinDef.conflict),
                        verifiedVersion = o.optInt("verified", 0),
                        version = o.optInt("version", 1),
                    )
                } else {
                    Rule(
                        id = id, name = o.getString("name"),
                        enabled = o.optBoolean("enabled", true), builtin = false,
                        scopeDirs = o.getJSONArray("scope").let { a -> (0 until a.length()).map { a.getString(it) } },
                        recursive = o.optBoolean("recursive"), topOnly = o.optBoolean("topOnly", true),
                        nameRegex = o.optString("nameRegex").ifEmpty { null },
                        prefixMonth = o.optBoolean("prefixMonth"),
                        extWhitelist = o.optString("extW").ifEmpty { null }?.split(',')?.toSet(),
                        extBlacklist = o.optString("extB").ifEmpty { null }?.split(',')?.toSet(),
                        action = ActionType.valueOf(o.getString("action")),
                        dirPattern = o.optString("dirPattern"), mergeDir = o.optString("mergeDir"),
                        conflict = ConflictPolicy.valueOf(o.optString("conflict", "SUFFIX")),
                        dryRun = false,
                        verifiedVersion = o.optInt("verified", 0), version = o.optInt("version", 1),
                    )
                }
            }
            // 内置 id 必须在当前代码的定义表内：已删除的内置规则（如 r7）不复活
            val cleaned = out.filter { !it.id.matches(Regex("^[rg]\\d+$")) || builtinRules().any { b -> b.id == it.id } }
            // 新版本新增的内置规则补进来（存储里还没有的）
            val missing = builtinRules().filter { b -> cleaned.none { it.id == b.id } }
            cleaned + missing
        } catch (_: Exception) { builtinRules() }
    }

    fun saveRules(context: Context, rules: List<Rule>) {
        val dir = File(context.filesDir, "toolbox/file-organizer").apply { mkdirs() }
        val arr = JSONArray()
        for (r in rules) arr.put(JSONObject()
            .put("id", r.id).put("name", r.name).put("enabled", r.enabled).put("builtin", r.builtin)
            .put("scope", JSONArray(r.scopeDirs)).put("recursive", r.recursive).put("topOnly", r.topOnly)
            .put("nameRegex", r.nameRegex ?: "").put("prefixMonth", r.prefixMonth)
            .put("extW", r.extWhitelist?.joinToString(",") ?: "").put("extB", r.extBlacklist?.joinToString(",") ?: "")
            .put("action", r.action.name).put("dirPattern", r.dirPattern).put("mergeDir", r.mergeDir)
            .put("conflict", r.conflict.name).put("dryRun", r.dryRun)
            .put("verified", r.verifiedVersion).put("version", r.version))
        File(dir, "rules.json").writeText(arr.toString(2))
    }
}
