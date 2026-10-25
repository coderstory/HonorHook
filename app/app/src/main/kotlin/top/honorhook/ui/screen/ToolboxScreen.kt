package top.honorhook.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.clickable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.honorhook.ui.FrameworkState
import top.honorhook.ui.Glitch
import top.honorhook.ui.scope.RootShell
import top.honorhook.ui.scope.SafeCommands
import top.honorhook.ui.toolbox.AppInfo
import top.honorhook.ui.toolbox.AppRepository
import top.honorhook.ui.toolbox.GovernanceList
import top.honorhook.ui.toolbox.OtaSentinel
import top.honorhook.ui.toolbox.SettingsReader
import top.honorhook.ui.toolbox.SnapshotStore
import top.honorhook.ui.toolbox.ToolAction
import top.honorhook.ui.toolbox.ToolQueue
import top.honorhook.ui.toolbox.ToolSwitchTable
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 工具箱 v1.3.0（28 号规格书 §3）：活性 / 开关 / 冻结 三区分段 + root 状态。
 * - 开关区只收 settings 持久层（resetprop 会话层已全数排除——43 号真机实证全 no_effect）。
 * - 冻结区安全默认：只有 B/C 档显式收录的包可选（GovernanceList 硬拦截），A 档折叠灰显。
 * - 执行一律进 ToolQueue 事务化队列：冻结批自动快照 + 失败不中断 + 回读判据。
 * - root 失效：全控件只读 + 引导卡（已冻结列表与快照免 root 可看）。
 */

/** miuix Switch 无 enabled 参：禁用时吞回调（视觉不作降，足够传达只读语义）。 */
@Composable
private fun SafeSwitch(checked: Boolean, enabled: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = { if (enabled) onCheckedChange(it) },
    )
}
@Composable
fun ToolboxScreen(bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scrollBehavior = MiuixScrollBehavior()
    val queue = remember { ToolQueue(context) }

    var probe by remember { mutableStateOf<RootShell.ProbeResult?>(null) }
    LaunchedEffect(Unit) {
        probe = withContext(Dispatchers.IO) { RootShell.probe() }
        RootShell.reset()
    }
    val rootOk = probe?.available == true

    // 开关区现值：免 root 读三表（表名定位在执行器做，这里只展示）
    var switchValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    fun refreshValues() {
        switchValues = ToolSwitchTable.ENTRIES.associate { e ->
            e.key to (SettingsReader.readAll(context, e.key).values.firstOrNull() ?: "")
        }
    }
    LaunchedEffect(rootOk) { withContext(Dispatchers.IO) { refreshValues() } }

    val apps by produceState<List<AppInfo>?>(initialValue = null, rootOk) {
        value = withContext(Dispatchers.IO) { AppRepository.loadAll(context) }
    }
    var ota by remember { mutableStateOf<OtaSentinel.Report?>(null) }
    LaunchedEffect(Unit) { withContext(Dispatchers.IO) { ota = OtaSentinel.check(context) } }

    var search by rememberSaveable { mutableStateOf("") }
    var filter by remember { mutableStateOf(FreezeFilter.ALL) }
    var showA by rememberSaveable { mutableStateOf(false) }
    var showFolded by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    var confirmTitle by remember { mutableStateOf("") }
    var confirmActions by remember { mutableStateOf<List<ToolAction>>(emptyList()) }
    var confirmOpen by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var runResult by remember { mutableStateOf<String?>(null) }

    fun request(actions: List<ToolAction>, title: String) {
        confirmTitle = title
        confirmActions = actions
        confirmOpen = true
    }

    Scaffold(
        topBar = { TopAppBar(title = Glitch.text("工具箱"), scrollBehavior = scrollBehavior) },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
        ) {
            ota?.let { report ->
                if (report.shouldWarn) {
                    item { OtaWarnCard(report) }
                }
            }

            if (!rootOk) {
                item { RootMissingCard() }
            }

            item { ActivityCard(probe, rootOk) }
            item { HistoryCard(context, queue) }

            // —— 开关区 ——
            item {
                AdsGroupCard(rootOk, switchValues, running) { actions ->
                    request(actions, Glitch.text("营销一键关"))
                }
            }
            item {
                PrivateDnsCard(rootOk, switchValues, running) { actions ->
                    request(actions, Glitch.text("私有 DNS"))
                }
            }
            item {
                SwitchListCard(
                    entries = ToolSwitchTable.ENTRIES.filter { it.group == null && (!it.folded || showFolded) },
                    values = switchValues,
                    enabled = rootOk && !running,
                    onToggle = { entry, turnOn ->
                        val value = if (turnOn) entry.onValue else entry.offValue
                        if (value == null) {
                            runResult = Glitch.text("「${entry.title}」是单向开关：只开不关")
                        } else {
                            request(listOf(ToolAction.Switch(entry.table, entry.key, value)), entry.title)
                        }
                    },
                )
            }
            if (!showFolded) {
                item {
                    TextButton(
                        text = Glitch.text("更多开关（含高风险）"),
                        onClick = { showFolded = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // —— 冻结区 ——
            item {
                FreezeHeaderCard(
                    rootOk = rootOk,
                    apps = apps,
                    selectedCount = selected.size,
                    filter = filter,
                    running = running,
                    onSearch = { search = it },
                    onFilter = { filter = it },
                    onPreset = {
                        val installed = (apps ?: emptyList()).map { it.pkg }.toSet()
                        val targets = GovernanceList.PURE_PRESET.filter { it in installed }
                        request(targets.map { ToolAction.Freeze(it) }, Glitch.text("纯净模式精选集"))
                    },
                    onFreezeSelected = {
                        request(selected.map { ToolAction.Freeze(it) }, Glitch.text("冻结所选"))
                    },
                )
            }
            item {
                InputField(
                    query = search,
                    onQueryChange = { search = it },
                    onSearch = { },
                    expanded = false,
                    onExpandedChange = { },
                    label = Glitch.text("搜索系统应用"),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val visibleApps = (apps ?: emptyList()).filter { app ->
                app.selectable && when (filter) {
                    FreezeFilter.ALL -> true
                    FreezeFilter.B -> app.entry?.tier == GovernanceList.Tier.B
                    FreezeFilter.C -> app.entry?.tier == GovernanceList.Tier.C
                    FreezeFilter.FROZEN -> app.frozen
                } && (search.isBlank() ||
                    app.label.contains(search, true) || app.pkg.contains(search, true))
            }
            items(visibleApps, key = { it.pkg }) { app ->
                FreezeRow(
                    app = app,
                    enabled = rootOk && !running,
                    checked = app.pkg in selected,
                    onCheck = { on -> selected = if (on) selected + app.pkg else selected - app.pkg },
                    onUnfreeze = { request(listOf(ToolAction.Unfreeze(app.pkg)), "解冻 ${app.label}") },
                )
            }
            if (showA) {
                items(
                    (apps ?: emptyList()).filter { GovernanceList.isRedLine(it.pkg) },
                    key = { "redline-${it.pkg}" },
                ) { app ->
                    RedLineRow(app)
                }
            }
            item {
                TextButton(
                    text = Glitch.text(if (showA) "收起禁碰名单（A 档）" else "展开禁碰名单（A 档）"),
                    onClick = { showA = !showA },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            item {
                Spacer(
                    Modifier.height(
                        bottomInnerPadding +
                            WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                            WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() + 12.dp
                    )
                )
            }
        }
    }

    // —— 确认流（摘要 → 快照 → 执行 → 结果）——
    if (confirmOpen) {
        val resultText = runResult
        if (resultText != null) {
            WindowDialog(
                show = true,
                title = Glitch.text("执行结果"),
                summary = null,
                onDismissRequest = { if (!running) { runResult = null; confirmOpen = false } },
            ) {
                Text(resultText)
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = Glitch.text("关闭"),
                    onClick = {
                        runResult = null
                        confirmOpen = false
                        scope.launch {
                            probe = withContext(Dispatchers.IO) { RootShell.probe() }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            ConfirmDialog(
                title = confirmTitle,
                actions = confirmActions,
                running = running,
                onConfirm = {
                    running = true
                    scope.launch {
                        runResult = executeWithQueue(context, queue, confirmActions)
                        running = false
                        withContext(Dispatchers.IO) { refreshValues() }
                    }
                },
                onCancel = { if (!running) confirmOpen = false },
            )
        }
    }
}

/** 冻结批先强制快照（28 号 §7 ①）；整批进队列串行执行，失败不中断。 */
private suspend fun executeWithQueue(
    context: Context,
    queue: ToolQueue,
    actions: List<ToolAction>,
): String {
    val sb = StringBuilder()
    if (actions.any { it is ToolAction.Freeze }) {
        val snap = SnapshotStore.snapshot(context)
        sb.append(if (snap.ok) "已存快照 ${snap.file?.name}\n" else "快照失败：${snap.error}（已中止）\n")
        if (!snap.ok) return sb.toString()
    }
    queue.enqueue(actions)
    val summary = queue.runPending()
    sb.append("完成 ${summary.done}，失败 ${summary.failed}\n")
    summary.failures.forEach { f ->
        sb.append("× ${describeAction(f.action)}：${f.error}\n")
    }
    return sb.toString().trimEnd()
}

private fun describeAction(a: ToolAction): String = when (a) {
    is ToolAction.Freeze -> "冻结 ${a.pkg}"
    is ToolAction.Unfreeze -> "解冻 ${a.pkg}"
    is ToolAction.Switch -> "写入 ${a.key}=${a.value}"
}

@Composable
private fun OtaWarnCard(report: OtaSentinel.Report) {
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = Glitch.text("OTA 防线告警"),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = colorScheme.onSurface,
            )
            if (report.violatedSix.isNotEmpty()) {
                Text(
                    text = Glitch.text("六键失守：${report.violatedSix.joinToString()}"),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            if (report.sentinelChanged.isNotEmpty()) {
                Text(
                    text = Glitch.text("哨兵变化（有推送活动）：${report.sentinelChanged.joinToString()}"),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = Glitch.text("内核>6.6.118 或 SP>2026-08-01 的固件一律拒升"),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun RootMissingCard() {
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = Glitch.text("Root 不可用"),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = colorScheme.onSurface,
            )
            Text(
                text = Glitch.text("开关与冻结全部只读。本机 root 为临时态：重启即失，重新提权后再来。已存的快照与执行历史不受影响。"),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ActivityCard(probe: RootShell.ProbeResult?, rootOk: Boolean) {
    // F3 收尾（28 号 §5）：趁 root 读 LSPosed 框架日志里本模块的 installed N/M 判据
    var hookLines by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(rootOk) {
        if (!rootOk) return@LaunchedEffect
        hookLines = withContext(Dispatchers.IO) {
            when (val r = RootShell.exec(
                "grep -h coderstory.honorhook /data/adb/lspd/log/modules_*.log 2>/dev/null | grep -E 'installed [0-9]+/[0-9]+' | tail -4",
            )) {
                is RootShell.Result.Ok -> r.stdout.trim().lineSequence().filter { it.isNotBlank() }.toList()
                else -> emptyList()
            }
        }
    }
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = Glitch.text("运行环境"),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = colorScheme.onSurface,
            )
            val rootLine = when {
                probe == null -> "检测中…"
                probe.available -> "root 可用（${probe.suPath}）"
                else -> "root 不可用（${probe.failure}）"
            }
            Text(rootLine, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
            val fw = FrameworkState.active
            val fwLine = when {
                fw == null -> "LSPosed：检测中…"
                fw -> "LSPosed：已激活（${FrameworkState.frameworkName} ${FrameworkState.frameworkVersion} API${FrameworkState.apiVersion}）"
                else -> "LSPosed：未激活（模块未被框架加载）"
            }
            Text(fwLine, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
            if (hookLines.isNotEmpty()) {
                Text(
                    text = Glitch.text("挂载判据（最近 4 条 installed N/M）："),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
                hookLines.forEach { line ->
                    Text(
                        // 原行：… ] (目标包)[模块指纹] Hook名[目标包]: installed N/M
                        text = "· ${line.substringAfter("] (").substringBefore(")")}  ${line.substringAfterLast("] ")}",
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun AdsGroupCard(
    enabled: Boolean,
    values: Map<String, String>,
    running: Boolean,
    onRequest: (List<ToolAction>) -> Unit,
) {
    val entries = ToolSwitchTable.ADS_GROUP
    val onCount = entries.count { values[it.key] == "1" }
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = Glitch.text("营销一键关"),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = colorScheme.onSurface,
            )
            Text(
                text = Glitch.text("11 个营销/推荐键，当前开 $onCount 个。可能被云端回写，隔天复查。"),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            TextButton(
                text = Glitch.text("全部关闭（写 0）"),
                enabled = enabled && !running && onCount > 0,
                onClick = { onRequest(entries.map { ToolAction.Switch(it.table, it.key, "0") }) },
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PrivateDnsCard(
    enabled: Boolean,
    values: Map<String, String>,
    running: Boolean,
    onRequest: (List<ToolAction>) -> Unit,
) {
    val mode = values["private_dns_mode"].orEmpty()
    val on = mode == "hostname"
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = Glitch.text("私有 DNS 去遥测"),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = Glitch.text("dns.adguard-dns.com；两键成对，当前：${if (mode.isBlank()) "未设置" else mode}"),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
            }
            SafeSwitch(
                checked = on,
                enabled = enabled && !running,
                onCheckedChange = { turnOn ->
                    onRequest(
                        if (turnOn) {
                            listOf(
                                ToolAction.Switch(SafeCommands.Table.GLOBAL, "private_dns_mode", "hostname"),
                                ToolAction.Switch(SafeCommands.Table.GLOBAL, "private_dns_specifier", "dns.adguard-dns.com"),
                            )
                        } else {
                            listOf(
                                ToolAction.Switch(SafeCommands.Table.GLOBAL, "private_dns_mode", "off"),
                                ToolAction.Switch(SafeCommands.Table.GLOBAL, "private_dns_specifier", ""),
                            )
                        },
                    )
                },
            )
        }
    }
}

@Composable
private fun SwitchListCard(
    entries: List<ToolSwitchTable.Entry>,
    values: Map<String, String>,
    enabled: Boolean,
    onToggle: (ToolSwitchTable.Entry, Boolean) -> Unit,
) {
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column {
            entries.forEachIndexed { i, e ->
                val current = values[e.key].orEmpty()
                val on = current == e.onValue
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = Glitch.text(if (e.risk == ToolSwitchTable.Risk.HIGH) "⚠ ${e.title}" else e.title),
                            fontSize = 15.sp,
                            color = colorScheme.onSurface,
                        )
                        if (e.summary.isNotBlank()) {
                            Text(e.summary, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                        }
                        Text(
                            text = "key=${e.key}  当前=${current.ifBlank { "未设置" }}  ${e.note}",
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    SafeSwitch(
                        checked = on,
                        enabled = enabled && (!on || e.offValue != null),
                        onCheckedChange = { onToggle(e, it) },
                    )
                }
            }
        }
    }
}

/** 冻结区档位过滤（28 号 §3 chips：B/C/已冻结；A 档不进列表只折叠展示）。 */
private enum class FreezeFilter(val label: String) { ALL("全部"), B("B 可选冻"), C("C 建议冻"), FROZEN("已冻结") }

@Composable
private fun FreezeHeaderCard(
    rootOk: Boolean,
    apps: List<AppInfo>?,
    selectedCount: Int,
    filter: FreezeFilter,
    running: Boolean,
    onSearch: (String) -> Unit,
    onFilter: (FreezeFilter) -> Unit,
    onPreset: () -> Unit,
    onFreezeSelected: () -> Unit,
) {
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = Glitch.text("系统应用冻结"),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = colorScheme.onSurface,
            )
            Text(
                text = Glitch.text("只收录治理名单 B/C 档；冻结前自动存快照，解冻需 root 在场"),
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FreezeFilter.entries.forEach { f ->
                    TextButton(
                        text = Glitch.text(if (f == filter) "· ${f.label}" else f.label),
                        enabled = rootOk && !running,
                        onClick = { onFilter(f) },
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = Glitch.text("纯净模式精选"),
                    enabled = rootOk && !running,
                    onClick = onPreset,
                )
                TextButton(
                    text = Glitch.text("冻结所选($selectedCount)"),
                    enabled = rootOk && !running && selectedCount > 0,
                    onClick = onFreezeSelected,
                )
            }
        }
    }
}

@Composable
private fun FreezeRow(
    app: AppInfo,
    enabled: Boolean,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    onUnfreeze: () -> Unit,
) {
    if (app.frozen) {
        Card(modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(app.label, fontSize = 15.sp, color = colorScheme.onSurface)
                    Text(app.pkg, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                }
                Text(
                    text = Glitch.text("已冻结 · 点解冻"),
                    modifier = Modifier.clickable(enabled = enabled) { onUnfreeze() },
                    fontSize = 13.sp,
                    color = colorScheme.primary,
                )
            }
        }
        return
    }
    Card(modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
        CheckboxPreference(
            title = app.label,
            summary = "${app.pkg}（${app.entry?.cost ?: ""}）",
            checked = checked,
            onCheckedChange = { if (enabled) onCheck(it) },
        )
    }
}

@Composable
private fun RedLineRow(app: AppInfo) {
    Card(modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "${app.label}（禁碰）",
                    fontSize = 15.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                )
                Text(app.pkg, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun ConfirmDialog(
    title: String,
    actions: List<ToolAction>,
    running: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    WindowDialog(
        show = true,
        title = Glitch.text(title),
        summary = null,
        onDismissRequest = onCancel,
    ) {
        // 28 号 §7：B 档有真实功能代价，批量冻结含 B 档时强制二次勾选
        val hasBTier = actions.any {
            it is ToolAction.Freeze && GovernanceList.lookup(it.pkg)?.tier == GovernanceList.Tier.B
        }
        var bConfirmed by remember { mutableStateOf(false) }
        Column {
            Text(
                text = Glitch.text("将执行 ${actions.size} 项：\n") +
                    actions.joinToString("\n") { describeAction(it) } +
                    Glitch.text("\n\n冻结批会先自动存快照。失败不中断，逐项回读验收。"),
                fontSize = 13.sp,
            )
            if (hasBTier) {
                Row(
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .fillMaxWidth()
                        .clickable { bConfirmed = !bConfirmed },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SafeSwitch(checked = bConfirmed, enabled = true) { bConfirmed = it }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = Glitch.text("确认冻结 B 档：有真实功能代价，解冻需 root 在场"),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 12.dp)) {
                TextButton(
                    text = Glitch.text("取消"),
                    onClick = onCancel,
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = Glitch.text(if (running) "执行中…" else "执行"),
                    onClick = onConfirm,
                    enabled = !running && (!hasBTier || bConfirmed),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HistoryCard(context: Context, queue: ToolQueue) {
    var open by rememberSaveable { mutableStateOf(false) }
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = Glitch.text("执行记录与快照"),
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = colorScheme.onSurface,
                    )
                    val pending = queue.pending().size
                    Text(
                        text = Glitch.text(if (pending > 0) "待执行 $pending 项（点开查看）" else "点开查看队列与快照"),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (open) {
                queue.items().takeLast(8).reversed().forEach { item ->
                    val state = when (item.state) {
                        ToolQueue.Core.State.PENDING -> Glitch.text("待执行")
                        ToolQueue.Core.State.DONE -> "✓"
                        ToolQueue.Core.State.FAILED -> "× ${item.error}"
                    }
                    Text(
                        text = "$state ${describeAction(item.action)}",
                        fontSize = 11.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val snaps = SnapshotStore.all(context).take(3)
                if (snaps.isNotEmpty()) {
                    Text(
                        text = Glitch.text("快照（filesDir/toolbox/snapshots/）："),
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    snaps.forEach { s ->
                        Text("· ${s.name}（${s.length() / 1024}KB）", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                    }
                }
            }
        }
    }
}
