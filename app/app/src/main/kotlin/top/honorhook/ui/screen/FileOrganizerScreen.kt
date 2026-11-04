package top.honorhook.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CallMerge
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.honorhook.ui.component.isInDarkTheme
import top.honorhook.ui.toolbox.FileDedup
import top.honorhook.ui.toolbox.FileOrganizer
import top.honorhook.ui.toolbox.FileOrganizer.ActionType
import top.honorhook.ui.toolbox.FileOrganizer.Plan
import top.honorhook.ui.toolbox.FileOrganizer.Rule
import top.honorhook.ui.toolbox.FileOrganizer.RunResult
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 文件整理页（规格 project/file-organizer-rules.md v2 / file-organizer-ui-design.md）。
 * 删除一律直接删（无回收站）；移动可撤销。v7 = v6 视觉 + 全屏编辑器独占渲染修复。
 */
@Composable
fun FileOrganizerScreen(bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp, topInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val dark = isInDarkTheme()

    var rules by remember { mutableStateOf<List<Rule>>(emptyList()) }
    var plan by remember { mutableStateOf<Plan?>(null) }
    var result by remember { mutableStateOf<RunResult?>(null) }
    var logLines by remember { mutableStateOf<List<String>>(emptyList()) }
    var phase by remember { mutableStateOf("idle") } // idle | scanning | preview | analyze | running | done
    var pendingExcluded by remember { mutableStateOf<Set<String>>(emptySet()) }
    var analyzeSummary by remember { mutableStateOf<List<Triple<String, Int, Long>>>(emptyList()) }
    var confirmRun by remember { mutableStateOf(false) }
    var runProgress by remember { mutableStateOf(0 to 0) }  // 已处理 to 总数（running 态实时刷新）

    var mode by remember { mutableStateOf("organize") } // organize | dedup
    var dedupGroups by remember { mutableStateOf<List<FileDedup.Group>>(emptyList()) }
    var dedupScanning by remember { mutableStateOf(false) }
    var dedupProgress by remember { mutableStateOf("" to 0) }
    var dedupKeep by remember { mutableStateOf<Map<FileDedup.Group, Int>>(emptyMap()) }
    var dedupDone by remember { mutableStateOf<String?>(null) }
    var confirmDedupDelete by remember { mutableStateOf(false) }
    var showFailedDialog by remember { mutableStateOf(false) }

    var ruleEditor by remember { mutableStateOf<Rule?>(null) }

    LaunchedEffect(Unit) {
        rules = withContext(Dispatchers.IO) { FileOrganizer.loadRules(context) }
        val dirty = withContext(Dispatchers.IO) { FileOrganizer.reconcile(context) }
        if (dirty.isNotEmpty()) logLines = listOf("⚠ 上次运行中断，已对账 ${dirty.sumOf { it.second }} 项")
    }

    // 权限态每次回到前台重查（授权在系统设置页完成，remembered 初值会过期）
    var storageGranted by remember {
        mutableStateOf(android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager())
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                storageGranted = android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    fun log(s: String) { logLines = (logLines + s).takeLast(100) }  // 封顶防无限增长

    fun doScan(analyzeOnly: Boolean = false) {
        if (!storageGranted) { log("⚠ 缺少「所有文件访问」权限，无法扫描"); return }
        mode = "organize"
        phase = "scanning"
        val t0 = System.currentTimeMillis()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { FileOrganizer.scan(context, rules) } }
                .onSuccess { p ->
                    plan = p; pendingExcluded = emptySet()
                    if (analyzeOnly) {
                        val byRule = p.ops.groupBy { it.ruleId }
                        analyzeSummary = byRule.map { (rid, ops) ->
                            Triple(rid, ops.size, ops.sumOf { it.size })   // key=ruleId（重名规则不撞）
                        }.sortedByDescending { it.third }
                        phase = "analyze"
                        log("分析完成：${p.ops.size} 项可处理，涉及 ${byRule.size} 条规则（耗时 ${(System.currentTimeMillis() - t0) / 100}f / 10 秒）".replace("f / 10 秒", "秒").replace(".0 秒", "秒"))
                    } else {
                        phase = "preview"
                        log("扫描完成（耗时 ${(System.currentTimeMillis() - t0) / 1000.0} 秒）：${p.ops.size} 项计划" + (if (p.stale.isNotEmpty()) "，${p.stale.size} 条告警" else ""))
                    }
                }
                .onFailure { phase = "idle"; log("✗ 扫描失败：${it.message}") }
        }
    }

    fun doApply() {
        val p = plan ?: return
        phase = "running"
        runProgress = 0 to (p.ops.size - pendingExcluded.size)
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { FileOrganizer.execute(context, p, pendingExcluded, dryRun = false,
                onProgress = { done, total -> runProgress = done to total }) } }
                .onSuccess { r ->
                    result = r; phase = "done"
                    if (r != null) {
                        log("执行完成：归档/移动 ${r.moved} · 删除 ${r.deleted} · 腾出 ${r.freedBytes / 1024}KB · 失败 ${r.failed}")
                        val executed = r.ops.map { it.ruleId }.toSet()
                        rules = rules.map { if (it.id in executed) it.copy(verifiedVersion = it.version) else it }
                        withContext(Dispatchers.IO) { FileOrganizer.saveRules(context, rules) }
                    }
                }
                .onFailure { phase = "idle"; log("✗ 执行失败：${it.message}") }
        }
    }

    fun dedupScan() {
        dedupScanning = true; dedupDone = null; dedupKeep = emptyMap()
        val t0 = System.currentTimeMillis()
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { FileDedup.scan { v, h -> dedupProgress = (if (h == 0) "已扫描 $v 个文件" else "已校验 $h 个") to h } } }
                .onSuccess { groups ->
                    dedupGroups = groups
                    dedupKeep = groups.associateWith { FileDedup.suggest(it, 2) }
                    dedupScanning = false
                    log("查重完成：${groups.size} 组重复（哈希耗时 ${(System.currentTimeMillis() - t0) / 1000.0} 秒）")
                }
                .onFailure { dedupScanning = false; log("✗ 查重失败：${it.message}") }
        }
    }

    fun dedupApply() {
        dedupScanning = true
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) { FileDedup.delete(context, dedupKeep.map { (g, k) -> FileDedup.Selection(g, k) }) }
            }.onSuccess { r ->
                dedupScanning = false
                dedupDone = r?.let { "已删除 ${it.deleted} 个重复文件 · 腾出 ${it.freedBytes / 1024}KB" } ?: "没有需要删除的项"
                if (r != null) log("查重删除：${r.deleted} 项 · 腾出 ${r.freedBytes / 1024}KB · 失败 ${r.failed}")
                dedupGroups = emptyList()
            }.onFailure { dedupScanning = false; log("✗ 删除失败：${it.message}") }
        }
    }

    // ── 全屏编辑器独占渲染：提前 return（外层是 Box 作用域，兄弟节点会叠画在主内容上）──
    if (ruleEditor != null) {
        val draft = ruleEditor!!
        RuleEditorPane(
            draft = draft,
            topInset = topInset,
            bottomInnerPadding = bottomInnerPadding,
            onCancel = { ruleEditor = null },
            onSave = { saved ->
                // 新建草稿 id 为空：保存时必须生成真实 id，否则该规则永远无法编辑/删除
                val finalSaved = if (saved.id.isEmpty()) saved.copy(id = "c${System.currentTimeMillis()}") else saved
                rules = if (draft.id.isEmpty()) rules + finalSaved
                        else rules.map { if (it.id == draft.id) finalSaved else it }
                scope.launch { withContext(Dispatchers.IO) { FileOrganizer.saveRules(context, rules) } }
                ruleEditor = null
                log(if (draft.id.isEmpty()) "已新建规则「${saved.name}」" else "已更新规则「${saved.name}」")
            },
            onDelete = if (draft.builtin) null else {
                {
                    rules = rules.filter { it.id != draft.id }
                    scope.launch { withContext(Dispatchers.IO) { FileOrganizer.saveRules(context, rules) } }
                    ruleEditor = null
                }
            },
        )
        return
    }

    // 目标目录不存在的合并规则隐藏（用户拍板）；File.exists 是磁盘 IO，
    // 必须按 rules 变化才重算，不能放组合函数里每次重组都跑
    val visibleRules = remember(rules) {
        rules.filter {
            it.action != ActionType.MERGE || java.io.File("/storage/emulated/0/${it.mergeDir}").exists()
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        val listState = rememberLazyListState()
        // 阶段切换滚回顶部：hero 按钮永远在第一屏
        LaunchedEffect(phase, mode, dedupDone) {
            if (phase == "preview" || phase == "analyze" || phase == "done" || phase == "idle") listState.scrollToItem(0)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = topInset + 12.dp, bottom = bottomInnerPadding + 16.dp),
        ) {
            // ═══ Hero：按状态显式拆分 ═══
            item {
                val deleting = mode == "organize" && phase == "preview" &&
                    (plan?.ops ?: emptyList()).any { it.type == FileOrganizer.OpType.DELETE }
                val ui: HeroUi = when {
                    !storageGranted -> HeroUi(
                        light = Color(0xFFFFDAD6), dark = Color(0xFF3A1614),
                        icon = Icons.Rounded.DeleteOutline,
                        headline = "需要文件访问权限",
                        sub = "授权后才能扫描与整理文件",
                        ctas = listOf(HeroCta("前往系统设置授权", primary = true) {
                            val i = android.content.Intent(
                                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                android.net.Uri.parse("package:${context.packageName}"),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            runCatching { context.startActivity(i) }
                                .onFailure { context.startActivity(android.content.Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }),
                    )
                    mode == "dedup" -> when {
                        dedupScanning -> HeroUi(
                            light = Color(0xFFDDE9FF), dark = Color(0xFF1A2A38),
                            icon = Icons.Rounded.ContentCopy,
                            headline = "正在查重…",
                            sub = dedupProgress.first.ifEmpty { "扫描中" },
                        )
                        dedupDone != null -> HeroUi(
                            light = Color(0xFFDFF3E1), dark = Color(0xFF14301F),
                            icon = Icons.Rounded.CheckCircleOutline,
                            headline = "查重完成",
                            sub = dedupDone!!,
                            ctas = listOf(HeroCta("重新扫描") { dedupScan() }),
                        )
                        dedupGroups.isNotEmpty() -> HeroUi(
                            light = Color(0xFFDDE9FF), dark = Color(0xFF1A2A38),
                            icon = Icons.Rounded.ContentCopy,
                            headline = "${dedupGroups.size} 组重复",
                            sub = "每组选一个保留，其余直接删除（不可恢复）",
                            ctas = listOf(
                                HeroCta("删除其余文件", primary = true, danger = true) { confirmDedupDelete = true },
                                HeroCta("全部保留最新") { dedupKeep = dedupGroups.associateWith { FileDedup.suggest(it, 2) } },
                                HeroCta("全部保留最旧") { dedupKeep = dedupGroups.associateWith { FileDedup.suggest(it, 3) } },
                                HeroCta("全部保留最短路径") { dedupKeep = dedupGroups.associateWith { FileDedup.suggest(it, 1) } },
                            ),
                        )
                        else -> HeroUi(
                            light = Color(0xFFDDE9FF), dark = Color(0xFF1A2A38),
                            icon = Icons.Rounded.ContentCopy,
                            headline = "文件查重",
                            sub = "扫描 >1MB 的重复文件，按首尾内容比对",
                            ctas = listOf(HeroCta("开始扫描", primary = true) { dedupScan() }),
                        )
                    }
                    phase == "scanning" -> HeroUi(
                        light = Color(0xFFDDE9FF), dark = Color(0xFF1A2A38),
                        icon = Icons.Rounded.FolderCopy,
                        headline = "正在扫描…",
                        sub = "列出文件并生成整理计划",
                    )
                    phase == "running" -> HeroUi(
                        light = Color(0xFFDDE9FF), dark = Color(0xFF1A2A38),
                        icon = Icons.Rounded.FolderCopy,
                        headline = "正在执行整理计划 ${runProgress.first}/${runProgress.second}",
                        sub = "移动可撤销 · 删除不可恢复",
                        ctas = listOf(HeroCta("取消执行", danger = true, primary = true) { FileOrganizer.cancelRun() }),
                    )
                    phase == "preview" -> {
                        val ops = plan?.ops ?: emptyList()
                        val delN = ops.count { it.type == FileOrganizer.OpType.DELETE }
                        val mvN = ops.size - delN
                        HeroUi(
                            light = if (delN > 0) Color(0xFFFFF2D9) else Color(0xFFDDE9FF),
                            dark = if (delN > 0) Color(0xFF332A15) else Color(0xFF1A2A38),
                            icon = Icons.Rounded.FolderCopy,
                            headline = "${ops.size} 项待执行",
                            sub = if (pendingExcluded.isEmpty()) "确认后开始移动/删除" else "已排除 ${pendingExcluded.size} 项，将执行 ${ops.size - pendingExcluded.size} 项",
                            stats = listOf(
                                mvN.toLong() to "移动",
                                delN.toLong() to "删除（不可恢复）",
                            ),
                            ctas = listOf(
                                HeroCta("确认执行 ${ops.size - pendingExcluded.size} 项", primary = true,
                                    enabled = ops.size - pendingExcluded.size > 0) { confirmRun = true },
                                HeroCta("重新扫描") { doScan() },
                            ),
                        )
                    }
                    phase == "analyze" -> {
                        val total = analyzeSummary.sumOf { it.second }
                        val bytes = analyzeSummary.sumOf { it.third }
                        HeroUi(
                            light = Color(0xFFDFF3E1), dark = Color(0xFF14301F),
                            icon = Icons.Rounded.CheckCircleOutline,
                            headline = if (total == 0) "全部已是最新状态" else "$total 项可处理",
                            sub = if (total == 0) "没有需要整理的文件" else "共可腾出 ${fmtMb(bytes)} · 仅分析，未动文件",
                            stats = if (total == 0) emptyList() else listOf(bytes to "可腾出"),
                            ctas = if (total == 0) listOf(HeroCta("重新分析") { doScan(analyzeOnly = true) })
                                   else listOf(
                                       HeroCta("查看明细并执行", primary = true) { phase = "preview" },
                                       HeroCta("重新分析") { doScan(analyzeOnly = true) },
                                   ),
                        )
                    }
                    phase == "done" -> {
                        val r = result
                        val failedN = r?.ops?.count { it.status == FileOrganizer.OpStatus.FAILED } ?: 0
                        HeroUi(
                            light = if ((r?.failed ?: 0) > 0) Color(0xFFFFF2D9) else Color(0xFFDFF3E1),
                            dark = if ((r?.failed ?: 0) > 0) Color(0xFF332A15) else Color(0xFF14301F),
                            icon = Icons.Rounded.CheckCircleOutline,
                            headline = "整理完成",
                            sub = "移动可撤销 · 删除不可恢复",
                            stats = listOf(
                                (r?.moved ?: 0).toLong() to "移动",
                                (r?.deleted ?: 0).toLong() to "删除",
                                (r?.freedBytes ?: 0).toLong() to "腾出字节",
                                (r?.failed ?: 0).toLong() to "失败",
                            ),
                            ctas = buildList {
                                if (failedN > 0) add(HeroCta("失败 ${failedN} 项明细") { showFailedDialog = true })
                                add(HeroCta("撤销本次", primary = true) {
                                    scope.launch {
                                        runCatching { withContext(Dispatchers.IO) { FileOrganizer.undo(context, r!!.runId) } }
                                            .onSuccess { (ok, fail) ->
                                                if (ok < 0) log("⚠ 有整理任务运行中，稍后再试撤销")
                                                else {
                                                    log("撤销：回滚 $ok 项" + (if (fail > 0) "，$fail 项已失效无法回滚" else ""))
                                                    result = null; plan = null; phase = "idle"
                                                }
                                            }
                                            .onFailure { log("✗ 撤销失败：${it.message}") }
                                    }
                                })
                                add(HeroCta("再次整理") { result = null; doScan() })
                            }
                        )
                    }
                    else -> HeroUi(
                        light = Color(0xFFDDE9FF), dark = Color(0xFF1A2A38),
                        icon = Icons.Rounded.FolderCopy,
                        headline = "规则整理就绪",
                        sub = "照片按月归档 · 垃圾清理 · 同类归拢",
                        stats = listOf(
                            visibleRules.count { it.enabled }.toLong() to "已启用规则",
                            visibleRules.size.toLong() to "规则总数",
                        ),
                        ctas = listOf(
                            HeroCta("立即整理", primary = true) { doScan() },
                            HeroCta("先分析（不动文件）") { doScan(analyzeOnly = true) },
                        ),
                    )
                }
                val container = if (dark) ui.dark else ui.light
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.defaultColors(color = container),
                    showIndication = false,
                ) {
                    Box {
                        Box(Modifier.fillMaxWidth().offset(27.dp, 31.dp), contentAlignment = Alignment.BottomEnd) {
                            Icon(ui.icon, null, Modifier.size(110.dp), tint = colorScheme.primary.copy(alpha = 0.30f))
                        }
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp)) {
                            Text(ui.headline, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface)
                            Spacer(Modifier.height(2.dp))
                            Text(ui.sub, fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                            if (ui.stats.isNotEmpty()) {
                                Spacer(Modifier.height(10.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                    ui.stats.forEach { (v, label) ->
                                        Column {
                                            Text(fmtCount(v), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colorScheme.onSurface)
                                            Text(label, fontSize = 10.sp, color = colorScheme.onSurfaceVariantSummary)
                                        }
                                    }
                                }
                            }
                            if (ui.ctas.isNotEmpty()) {
                                Spacer(Modifier.height(14.dp))
                                val primaries = ui.ctas.filter { it.primary }
                                val secondaries = ui.ctas.filter { !it.primary }
                                primaries.forEach { cta ->
                                    val cc = if (cta.danger) Color(0xFFBA1A1A) else colorScheme.primary
                                    Button(onClick = cta.onClick, enabled = cta.enabled, modifier = Modifier.fillMaxWidth(),
                                        colors = ButtonDefaults.buttonColors(color = cc, contentColor = Color.White)) {
                                        Text(cta.label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                    }
                                    Spacer(Modifier.height(8.dp))
                                }
                                if (secondaries.isNotEmpty()) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        secondaries.forEach { cta ->
                                            Card(
                                                modifier = Modifier.weight(1f).clickable(enabled = cta.enabled, onClick = cta.onClick),
                                                colors = CardDefaults.defaultColors(color = colorScheme.primary.copy(alpha = 0.12f)),
                                            ) {
                                                Text(cta.label, fontSize = 12.sp, color = colorScheme.primary,
                                                    maxLines = 1,
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 13.dp),
                                                    textAlign = TextAlign.Center)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ═══ 模式切换 ═══
            item {
                Spacer(Modifier.height(12.dp))
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ModeTab(Icons.Rounded.FolderCopy, "规则整理", mode == "organize", Modifier.weight(1f)) { mode = "organize" }
                        ModeTab(Icons.Rounded.ContentCopy, "文件查重", mode == "dedup", Modifier.weight(1f)) { mode = "dedup" }
                    }
                }
            }

            // ═══ 分析汇总 ═══
            if (mode == "organize" && phase == "analyze") {
                item { SectionHeader("按规则汇总 · 未执行任何操作") }
                items(analyzeSummary, key = { it.first }) { (name, count, bytes) ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconBadge(rules.find { it.id == name }?.let { actionIcon(it) } ?: Icons.Rounded.Archive)
                            Spacer(Modifier.size(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(rules.find { it.id == name }?.name ?: name, fontSize = 14.sp, color = colorScheme.onSurface)
                                Text("命中 $count 项", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                            }
                            Text(fmtMb(bytes), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.primary)
                        }
                    }
                }
            }

            // ═══ 预览明细 ═══
            if (mode == "organize" && phase == "preview") {
                item {
                    SectionHeader("计划明细 · 点击排除/恢复")
                    plan?.stale?.take(5)?.forEach {
                        Text("⚠ $it", fontSize = 12.sp, color = Color(0xFFB8860B),
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                    }
                }
                items(plan?.ops?.take(80) ?: emptyList(), key = { it.opId }) { op ->
                    val excluded = op.opId in pendingExcluded
                    Card(Modifier.fillMaxWidth().padding(bottom = 6.dp).clickable {
                        pendingExcluded = if (excluded) pendingExcluded - op.opId else pendingExcluded + op.opId
                    }) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text((if (excluded) "☐" else "☑"), fontSize = 15.sp,
                                color = if (excluded) colorScheme.onSurfaceVariantSummary else colorScheme.primary)
                            Spacer(Modifier.size(10.dp))
                            Text(
                                (if (op.type == FileOrganizer.OpType.DELETE) "删除 " else "移动 ") +
                                    op.src.removePrefix("/storage/emulated/0/") +
                                    (if (op.type == FileOrganizer.OpType.MOVE) "  →  " + op.dst.removePrefix("/storage/emulated/0/") else ""),
                                fontSize = 12.sp, maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                color = if (excluded) colorScheme.onSurfaceVariantSummary else colorScheme.onSurface,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if ((plan?.ops?.size ?: 0) > 80) {
                    item { Text("…其余 ${(plan?.ops?.size ?: 0) - 80} 项将按同样规则执行", fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.padding(8.dp)) }
                }
            }

            // ═══ 查重分组 ═══
            if (mode == "dedup" && dedupGroups.isNotEmpty()) {
                item { SectionHeader("重复文件组 · 点文件行可改保留项") }
                items(dedupGroups, key = { it.entries.first().path }) { group ->
                    val keep = dedupKeep[group] ?: 0
                    val ext = group.entries.first().file.extension.uppercase()
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconBadge(Icons.Rounded.Image)
                                Spacer(Modifier.size(10.dp))
                                Text(if (ext == "文件") "${group.entries.size} 个相同大小文件 · 每个 ${fmtMb(group.entries.first().size)}" else "${group.entries.size} 个相同的 $ext · 每个 ${fmtMb(group.entries.first().size)}",
                                    fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                            }
                            Spacer(Modifier.height(6.dp))
                            group.entries.forEachIndexed { i, e ->
                                val isKeep = i == keep
                                val reason = when {
                                    !isKeep -> ""
                                    group.entries[i].mtime >= group.entries.maxOf { it.mtime } -> "保留·最新  "
                                    i == FileDedup.suggest(group, 3) -> "保留·最旧  "
                                    i == FileDedup.suggest(group, 1) -> "保留·最短路径  "
                                    else -> "保留·手动  "
                                }
                                Row(
                                    Modifier.fillMaxWidth().clickable { dedupKeep = dedupKeep + (group to i) }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        (if (isKeep) "✅ $reason" else "🗑  ") + e.path.removePrefix("/storage/emulated/0/"),
                                        fontSize = 12.sp, maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                        color = if (isKeep) colorScheme.onSurface else colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.weight(1f),
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                QuickChip("留最短路径") { dedupKeep = dedupKeep + (group to FileDedup.suggest(group, 1)) }
                                QuickChip("留最新") { dedupKeep = dedupKeep + (group to FileDedup.suggest(group, 2)) }
                                QuickChip("留最旧") { dedupKeep = dedupKeep + (group to FileDedup.suggest(group, 3)) }
                            }
                        }
                    }
                }
            }


            // ═══ 日志 ═══
            if (logLines.isNotEmpty()) {
                item { SectionHeader("日志") }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                            logLines.takeLast(15).forEach {
                                Text(it, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(vertical = 2.dp))
                            }
                        }
                    }
                }
            }

            // ═══ 规则库 ═══
            if (mode == "organize") {
                item {
                    Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("规则库 · ${visibleRules.count { it.enabled }}/${visibleRules.size} 条启用",
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                        Text("+ 新建规则", fontSize = 12.sp, color = colorScheme.primary,
                            modifier = Modifier.clickable {
                                ruleEditor = Rule(id = "", name = "", enabled = true, builtin = false,
                                    scopeDirs = listOf(""), action = ActionType.ARCHIVE)
                            }.padding(horizontal = 8.dp, vertical = 8.dp))
                    }
                }
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column {
                            visibleRules.forEachIndexed { idx, rule ->
                                if (idx > 0) Box(
                                    Modifier.fillMaxWidth().height(0.5.dp)
                                        .background(colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f))
                                )
                                Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconBadge(actionIcon(rule), rule.action == ActionType.DELETE)
                                    Spacer(Modifier.size(10.dp))
                                    Column(Modifier.weight(1f).clickable(enabled = !rule.builtin) { ruleEditor = rule }) {
                                        Text(rule.name + if (!rule.builtin) " ·自定义" else "",
                                            fontSize = 14.sp, color = colorScheme.onSurface)
                                        Text(ruleSummary(rule), fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                                    }
                                    Switch(checked = rule.enabled, onCheckedChange = { on ->
                                        rules = rules.map { if (it.id == rule.id) it.copy(enabled = on) else it }
                                        scope.launch { withContext(Dispatchers.IO) { FileOrganizer.saveRules(context, rules) } }
                                    })
                                    if (!rule.builtin) {
                                        Spacer(Modifier.size(6.dp))
                                        Text("›", fontSize = 16.sp, color = colorScheme.onSurfaceVariantSummary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }


    // ═══ 失败明细弹窗（设计稿 docs/file-organizer-ui-spec.md · 失败弹窗节）═══
    if (showFailedDialog && result != null) {
        val failedOps = result!!.ops.filter { it.status == FileOrganizer.OpStatus.FAILED }
        val okN = result!!.moved
        WindowDialog(
            show = true,
            title = "整理结果 · ${failedOps.size} 项失败",
            summary = "成功 ${okN} · 失败 ${failedOps.size} · 腾出 ${result!!.freedBytes / 1024}KB",
            onDismissRequest = { showFailedDialog = false },
        ) {
            Column(
                Modifier.fillMaxWidth().height(320.dp).verticalScroll(rememberScrollState())
            ) {
                // 失败行：与规则库/汇总卡同构——36dp 徽章 + 路径 14sp + 原因 11sp（设计稿定稿版）
                failedOps.forEachIndexed { i, op ->
                    val isDel = op.type == FileOrganizer.OpType.DELETE
                    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(36.dp)
                                .background(
                                    (if (isDel) Color(0xFFBA1A1A) else colorScheme.primary).copy(alpha = 0.12f),
                                    RoundedCornerShape(11.dp),
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (isDel) Icons.Rounded.DeleteOutline else Icons.Rounded.CallMerge,
                                null, Modifier.size(20.dp),
                                tint = if (isDel) Color(0xFFBA1A1A) else colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.size(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                op.src.removePrefix("/storage/emulated/0/") +
                                    (if (!isDel) "  →  " + op.dst.removePrefix("/storage/emulated/0/") else ""),
                                fontSize = 14.sp, color = colorScheme.onSurface,
                                modifier = Modifier.padding(bottom = 2.dp),
                            )
                            val bad = !op.note.contains("取消") && !op.note.contains("缓存")
                            Text(
                                op.note.ifEmpty { "未知原因" },
                                fontSize = 11.sp,
                                color = when {
                                    bad -> if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A)
                                    else -> Color(0xFFB8860B)
                                },
                            )
                        }
                    }
                    if (i < failedOps.size - 1) Box(
                        Modifier.fillMaxWidth().height(0.5.dp)
                            .background(colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f))
                    ) { Box(Modifier) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(text = "复制明细", modifier = Modifier.weight(1f), onClick = {
                    val text = failedOps.joinToString("\n") { op ->
                        "[${op.note}] ${op.type.name} ${op.src}" +
                            (if (op.type == FileOrganizer.OpType.MOVE) " -> ${op.dst}" else "")
                    }
                    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                        as android.content.ClipboardManager
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("failed", text))
                    log("失败明细已复制 ${failedOps.size} 条")
                })
                TextButton(text = "关闭", modifier = Modifier.weight(1f), onClick = { showFailedDialog = false })
            }
        }
    }

    // ═══ 对话框 ═══
    if (confirmRun) {
        val ops = plan?.ops ?: emptyList()
        val exec = ops - ops.filter { it.opId in pendingExcluded }.toSet()
        val mvN = exec.count { it.type == FileOrganizer.OpType.MOVE }
        val delN = exec.count { it.type == FileOrganizer.OpType.DELETE }
        WindowDialog(
            show = true,
            title = "确认执行",
            summary = null,
            onDismissRequest = { confirmRun = false },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠", fontSize = 18.sp, color = Color(0xFFBA1A1A))
                Spacer(Modifier.size(8.dp))
                Text("确认执行", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface)
            }
            Spacer(Modifier.height(10.dp))
            Text("将真实执行 ${exec.size} 项：", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("$mvN 移动（可撤销）", fontSize = 13.sp, color = colorScheme.primary)
                Text("$delN 删除（不可恢复）", fontSize = 13.sp, color = Color(0xFFBA1A1A),
                    fontWeight = if (delN > 0) FontWeight.SemiBold else FontWeight.Normal)
            }
            if ((plan?.ops?.size ?: 0) > 80) {
                Spacer(Modifier.height(6.dp))
                Text("预览仅列前 80 项，其余 ${(plan?.ops?.size ?: 0) - 80} 项按同规则直接执行",
                    fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { confirmRun = false }, modifier = Modifier.weight(1f)) {
                    Text("取消", fontSize = 13.sp)
                }
                Button(onClick = { confirmRun = false; doApply() }, enabled = exec.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        color = if (delN > 0) Color(0xFFBA1A1A) else colorScheme.primary,
                        contentColor = Color.White)) {
                    Text(if (delN > 0) "执行（含删除）" else "执行", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
    if (confirmDedupDelete) {
        val delCount = dedupKeep.entries.sumOf { it.key.entries.size - 1 }
        WindowDialog(
            show = true,
            title = "删除重复文件",
            summary = null,
            onDismissRequest = { confirmDedupDelete = false },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠", fontSize = 18.sp, color = Color(0xFFBA1A1A))
                Spacer(Modifier.size(8.dp))
                Text("删除重复文件", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface)
            }
            Spacer(Modifier.height(10.dp))
            Text("将直接删除 $delCount 个重复文件（每组保留 1 个）。", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { confirmDedupDelete = false }, modifier = Modifier.weight(1f)) {
                    Text("取消", fontSize = 13.sp)
                }
                Button(onClick = { confirmDedupDelete = false; dedupApply() }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(color = Color(0xFFBA1A1A), contentColor = Color.White)) {
                    Text("删除", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ═══ Hero 状态数据模型：每个状态显式声明 ═══

/** hero 按钮描述：primary=实底主色（蓝），danger=红色语义，default=浅底次级 */
private data class HeroCta(val label: String, val primary: Boolean = false, val danger: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

private data class HeroUi(
    val light: Color, val dark: Color,   // 容器色（浅/深主题）
    val icon: ImageVector,
    val headline: String,
    val sub: String,
    val stats: List<Pair<Long, String>> = emptyList(), // 数值 to 标签（数值用 fmtMb? 不——数值 Long 原样显示）
    val ctas: List<HeroCta> = emptyList(),
)

// ═══ 视觉辅助 ═══

private fun fmtCount(v: Long): String =
    if (v >= 1024L * 1024) "${v / 1024 / 1024}MB" else if (v >= 1024) "${v / 1024}KB" else v.toString()

/** 规则/操作的动作图标 */
private fun actionIcon(r: Rule): ImageVector = when (r.action) {
    ActionType.ARCHIVE -> Icons.Rounded.Archive
    ActionType.MERGE -> Icons.Rounded.CallMerge
    ActionType.DELETE -> Icons.Rounded.CleaningServices
}

/** 彩色圆角图标徽章（HubTiles 同款 36dp） */
@Composable
private fun IconBadge(icon: ImageVector, danger: Boolean = false) {
    val tint = if (danger) Color(0xFFBA1A1A) else colorScheme.primary
    Box(
        Modifier.size(36.dp)
            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(11.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, Modifier.size(20.dp), tint = tint)
    }
}

private fun fmtMb(bytes: Long): String =
    if (bytes >= 1024L * 1024) "${bytes / 1024 / 1024}MB" else "${bytes / 1024}KB"

/** 分段模式 tab（带图标） */
@Composable
private fun ModeTab(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .background(
                if (selected) colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                RoundedCornerShape(10.dp),
            )
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(16.dp),
            tint = if (selected) colorScheme.primary else colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.size(6.dp))
        Text(label, fontSize = 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colorScheme.primary else colorScheme.onSurfaceVariantSummary)
    }
}

/** 小节头 */
@Composable
private fun SectionHeader(label: String) {
    Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
        color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 6.dp))
}

/** 查重快捷选择小片 */
@Composable
private fun QuickChip(label: String, onClick: () -> Unit) {
    Text(label, fontSize = 12.sp, color = colorScheme.primary,
        modifier = Modifier
            .background(colorScheme.primary.copy(alpha = 0.10f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp))
}

/** 动作选择大卡片 */
@Composable
private fun ActionCard(title: String, desc: String, selected: Boolean, danger: Boolean = false, onClick: () -> Unit) {
    val accent = if (danger) Color(0xFFBA1A1A) else colorScheme.primary
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        colors = CardDefaults.defaultColors(color = if (selected) accent.copy(alpha = 0.10f) else colorScheme.surface),
        onClick = onClick, showIndication = false,
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected) "●" else "○", fontSize = 14.sp, color = accent)
            Spacer(Modifier.size(10.dp))
            Column {
                Text(title, fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected && danger) Color(0xFFBA1A1A) else colorScheme.onSurface)
                Text(desc, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

/** 外置字段标签（miuix InputField 的 label 是占位符，输入后消失） */
@Composable
private fun FieldLabel(text: String) {
    Text(text, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
}

private fun ruleSummary(r: Rule): String {
    val scope = if (r.scopeDirs.isEmpty()) "全盘" else r.scopeDirs.joinToString("/")
    return when (r.action) {
        ActionType.ARCHIVE -> "$scope → ${r.dirPattern}"
        ActionType.MERGE -> "$scope → ${r.mergeDir}"
        ActionType.DELETE -> "$scope · 删除(不可恢复)"
    }
}

/**
 * 全屏规则编辑器：新建/编辑/删除自定义规则 + 演练（只读试匹配）。
 */
@Composable
private fun RuleEditorPane(
    draft: Rule,
    topInset: androidx.compose.ui.unit.Dp,
    bottomInnerPadding: androidx.compose.ui.unit.Dp,
    onCancel: () -> Unit,
    onSave: (Rule) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(draft.name) }
    var scope by remember { mutableStateOf(draft.scopeDirs.joinToString("/")) }
    var regex by remember { mutableStateOf(draft.nameRegex ?: "") }
    var ext by remember { mutableStateOf(draft.extWhitelist?.joinToString(",") ?: "") }
    var action by remember { mutableStateOf(draft.action.name) }
    var target by remember { mutableStateOf(if (draft.action == ActionType.ARCHIVE) draft.dirPattern else draft.mergeDir) }
    var conflict by remember { mutableStateOf(draft.conflict.name) }
    var enabled by remember { mutableStateOf(draft.enabled) }
    var confirmDelete by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Int, List<String>>?>(null) }
    var testing by remember { mutableStateOf(false) }
    val dirPickerLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            // SAF tree uri 解码后是相对存储根的路径（如 "Pictures/CoolMarket"），
            // 旧实现错误地要求绝对路径前缀 → 永不回填（选目录选了个寂寞的根因）
            val seg = uri.lastPathSegment.orEmpty()
            val dec = java.net.URLDecoder.decode(seg.substringAfter(':'), "UTF-8")
            if (dec.isNotBlank()) {
                testResult = null
                scope = dec.trim().trimStart('/').trimEnd('/')
            }
        }
    }
    val paneScope = rememberCoroutineScope()
    val dark = isInDarkTheme()

    val regexOk = regex.isEmpty() || runCatching { Regex(regex) }.isSuccess
    val formOk = name.isNotBlank() && scope.isNotBlank() && regexOk && (action == "DELETE" || target.isNotBlank())
    val isDelete = action == "DELETE"

    // 系统返回手势回列表，而非退出丢表单（审计 C3）
    androidx.activity.compose.BackHandler { onCancel() }

    fun buildRule(): Rule = draft.copy(
        enabled = enabled,
        name = name.trim(),
        scopeDirs = listOf(scope.trim().trimEnd('/')),
        nameRegex = regex.trim().ifEmpty { null },
        extWhitelist = ext.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet().ifEmpty { null },
        action = ActionType.valueOf(action),
        dirPattern = if (action == "ARCHIVE") target.trim() else "",
        mergeDir = if (action == "MERGE") target.trim() else "",
        conflict = FileOrganizer.ConflictPolicy.valueOf(conflict),
        version = draft.version + 1,
    )

    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        // 标题/返回由外层顶栏承担；保存固定在底部操作栏，滚动全程可见
        LazyColumn(
            Modifier.weight(1f)
                .padding(top = topInset + 8.dp)   // 外层顶栏让位（删顶栏时误删的）
                .imePadding(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                FieldLabel("规则名")
                                InputField(query = name, onQueryChange = { name = it },
                                    onSearch = { }, expanded = false, onExpandedChange = { }, label = "如：壁纸批量归档",
                                    modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.size(12.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("启用", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                                Switch(checked = enabled, onCheckedChange = { enabled = it })
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FieldLabel("作用域目录（顶层散文件）")
                            Spacer(Modifier.weight(1f))
                            Text("选择目录", fontSize = 12.sp, color = colorScheme.primary,
                                modifier = Modifier.clickable { dirPickerLauncher.launch(null) }
                                    .padding(horizontal = 8.dp, vertical = 6.dp))
                        }
                        InputField(query = scope, onQueryChange = { scope = it; testResult = null },
                            onSearch = { }, expanded = false, onExpandedChange = { }, label = "点「选择目录」或手输相对路径",
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("匹配条件（都可选）", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FieldLabel("文件名正则")
                            if (regex.isNotEmpty()) {
                                Spacer(Modifier.weight(1f))
                                Text(if (regexOk) "✓ 正则合法" else "✗ 正则非法（保存已禁用）",
                                    fontSize = 11.sp,
                                    color = if (regexOk) colorScheme.primary else if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
                                    modifier = Modifier.padding(end = 4.dp))
                            }
                        }
                        InputField(query = regex, onQueryChange = { regex = it; testResult = null },
                            onSearch = { }, expanded = false, onExpandedChange = { }, label = "^IMG_ · 留空=全部匹配",
                            modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                        FieldLabel("扩展名白名单")
                        InputField(query = ext, onQueryChange = { ext = it; testResult = null },
                            onSearch = { }, expanded = false, onExpandedChange = { }, label = "jpg,png · 留空=不限",
                            modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("执行动作", fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = colorScheme.onSurfaceVariantSummary)
                        Spacer(Modifier.height(8.dp))
                        ActionCard("归档", "移动到按月子目录，如 Camera/202611",
                            selected = action == "ARCHIVE",
                            onClick = { action = "ARCHIVE"; testResult = null
                                if (target.isEmpty()) target = scope.trim() + "/{YYYYMM}" })
                        ActionCard("合并", "移动到一个固定目录，与现有文件共存",
                            selected = action == "MERGE",
                            onClick = { action = "MERGE"; testResult = null })
                        ActionCard("删除", "直接删除，不可恢复", danger = true,
                            selected = action == "DELETE",
                            onClick = { action = "DELETE"; testResult = null; target = "" })
                        if (action == "ARCHIVE" || action == "MERGE") {
                            Spacer(Modifier.height(10.dp))
                            FieldLabel(if (action == "ARCHIVE") "目标目录模板（{YYYYMM} 会替换成月份）" else "合并目标目录")
                            InputField(query = target, onQueryChange = { target = it; testResult = null },
                                onSearch = { }, expanded = false, onExpandedChange = { }, label = "如：DCIM/Camera/{YYYYMM}",
                                modifier = Modifier.fillMaxWidth())
                        }
                        if (action == "MERGE") {
                            Spacer(Modifier.height(10.dp))
                            FieldLabel("目标已有同名文件时")
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("改名保留" to "SUFFIX", "跳过新文件" to "SKIP", "覆盖旧文件" to "OVERWRITE_BACKUP")
                                    .forEach { (label, v) ->
                                        Text(label, fontSize = 12.sp,
                                            color = if (conflict == v) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                                            fontWeight = if (conflict == v) FontWeight.SemiBold else FontWeight.Normal,
                                            modifier = Modifier
                                                .background(colorScheme.primary.copy(alpha = if (conflict == v) 0.14f else 0f), RoundedCornerShape(8.dp))
                                                .clickable { conflict = v }
                                                .padding(horizontal = 12.dp, vertical = 9.dp))
                                    }
                            }
                        }

                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("演练", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface)
                                Text("只读预演：看看这条规则会命中哪些文件，不动任何文件",
                                    fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                            }
                            Button(onClick = {
                                paneScope.launch {
                                    testing = true
                                    testResult = runCatching {
                                        withContext(Dispatchers.IO) { FileOrganizer.testMatch(buildRule()) }
                                    }.getOrNull() ?: (-1 to emptyList())
                                    testing = false
                                }
                            },
                                enabled = scope.isNotBlank() && regexOk && !testing,
                                colors = ButtonDefaults.buttonColors(color = colorScheme.primary, contentColor = Color.White)) {
                                Text(if (testing) "演练中…" else "▶ 演练", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                        testResult?.let { (n, samples) ->
                            Spacer(Modifier.height(10.dp))
                            if (n < 0) {
                                Text("✗ 正则非法，无法演练", fontSize = 12.sp, color = Color(0xFFBA1A1A))
                            } else if (n == 0) {
                                Text("作用域内无命中文件", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                            } else {
                                Text("命中 $n 个文件：", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = colorScheme.onSurface)
                                samples.forEach {
                                    Text(it, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(start = 8.dp, top = 3.dp))
                                }
                                if (n > samples.size) {
                                    Text("…等共 $n 个", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary,
                                        modifier = Modifier.padding(start = 8.dp, top = 3.dp))
                                }
                            }
                        }
                    }
                }
            }

        }
        // 底部操作栏：固定可见（返回由外层顶栏/手势承担）
        Row(
            Modifier.fillMaxWidth()
                .padding(vertical = 5.dp)
                .navigationBarsPadding()
                .padding(bottom = bottomInnerPadding),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onDelete != null && draft.id.isNotEmpty()) {   // 新建流程无删除入口
                Text("删除此规则", fontSize = 13.sp,
                    color = if (dark) Color(0xFFFFB4AB) else Color(0xFFBA1A1A),
                    modifier = Modifier.clickable { confirmDelete = true }
                        .padding(horizontal = 4.dp, vertical = 9.dp))
            }
            Spacer(Modifier.weight(1f))
            Button(onClick = { onCancel() }) { Text("取消", fontSize = 13.sp) }
            Button(onClick = { onSave(buildRule()) }, enabled = formOk,
                colors = ButtonDefaults.buttonColors(color = colorScheme.primary, contentColor = Color.White)) {
                Text("保存", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }

    if (onDelete != null && confirmDelete) {
        WindowDialog(
            show = true,
            title = "删除此规则",
            summary = null,
            onDismissRequest = { confirmDelete = false },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("⚠", fontSize = 18.sp, color = Color(0xFFBA1A1A))
                Spacer(Modifier.size(8.dp))
                Text("删除此规则", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = colorScheme.onSurface)
            }
            Spacer(Modifier.height(10.dp))
            Text("移除「$name」？只删除规则配置，不删除任何文件。", fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary)
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = { confirmDelete = false }, modifier = Modifier.weight(1f)) {
                    Text("取消", fontSize = 13.sp)
                }
                Button(onClick = { confirmDelete = false; onDelete?.invoke() }, modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(color = Color(0xFFBA1A1A), contentColor = Color.White)) {
                    Text("删除", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
