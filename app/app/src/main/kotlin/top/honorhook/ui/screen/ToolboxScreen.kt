package top.honorhook.ui.screen

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import top.yukonga.miuix.kmp.basic.Button
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
 * 工具箱 v1.3.2（2026-10-25 用户拍板：工作台模式）：状态摘要 + 2×2 功能磁贴 + 两级页面。
 * - 首页：运行状态摘要卡（root/LSPosed/OTA 一行结论 + 挂载判据折叠）+ 四磁贴（营销一键关/
 *   应用冻结/系统开关/执行记录，副标题实时计数）+ 最近执行预览。
 * - 二级页：磁贴点进、返回栏回台；冻结/开关沿用 v1.3.1 组件，营销页 = 一键卡 + 私有 DNS。
 * - 开关行只显「标题 + 功能描述」——key=value 属面板数据不上屏（2026-10-25 拍板：用户看不懂）。
 * - 执行一律进 ToolQueue 事务化队列：冻结批自动快照 + 失败不中断 + 回读判据。
 * - root 失效：全控件只读（状态摘要卡显式说明；已冻结列表与快照免 root 可看）。
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
    var view by rememberSaveable { mutableStateOf(HubView.HOME) }
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
            when (view) {
                // —— 工作台首页：状态摘要 + 磁贴 + 最近执行 ——
                HubView.HOME -> {
                    item { SummaryCard(probe, rootOk, ota) }
                    item { HubTiles(switchValues, apps, context, queue, onOpen = { view = it }) }
                    item { RecentCard(queue, onOpen = { view = HubView.LOG }) }
                }

                // —— 净化页：一键动作 ——
                HubView.PURIFY -> {
                    item { BackBar { view = HubView.HOME } }
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
                }

                // —— 开关页：标题 + 功能描述行 ——
                HubView.SWITCH -> {
                    item { BackBar { view = HubView.HOME } }
                    item { DetailTitle("系统开关") }
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
                }

                // —— 冻结页：v1.3.1 组件沿用 ——
                HubView.FREEZE -> {
                    item { BackBar { view = HubView.HOME } }
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
                }

                // —— 记录页：快照 + 执行历史（默认展开） ——
                HubView.LOG -> {
                    item { BackBar { view = HubView.HOME } }
                    item { DetailTitle("执行记录") }
                    item { HistoryCard(context, queue, initialOpen = true) }
                }
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

/** 工具箱二级视图（v1.3.2 工作台）：HOME 磁贴台 + 四个功能页，磁贴点进、返回栏回台。 */
private enum class HubView { HOME, PURIFY, SWITCH, FREEZE, LOG }

/** 二级页返回栏（对齐设计稿：返回箭头 + 「工具箱」，胶囊可点区）。 */
@Composable
private fun BackBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onBack)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.AutoMirrored.Rounded.ArrowBack,
            contentDescription = null,
            tint = colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = Glitch.text(" 工具箱"),
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = colorScheme.primary,
        )
    }
}

/** 二级页大标题。 */
@Composable
private fun DetailTitle(title: String) {
    Text(
        text = Glitch.text(title),
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        color = colorScheme.onSurface,
    )
}

/**
 * 工作台 2×2 功能磁贴（v1.3.2）：副标题实时计数——营销键开数 / 冻结数 / 开关项数 / 记录数。
 * 快照与队列读取沿用 HistoryCard 既有口径（filesDir/toolbox/snapshots/ 与 ToolQueue）。
 */
@Composable
private fun HubTiles(
    values: Map<String, String>,
    apps: List<AppInfo>?,
    context: Context,
    queue: ToolQueue,
    onOpen: (HubView) -> Unit,
) {
    val adsOn = ToolSwitchTable.ADS_GROUP.count { values[it.key] == "1" }
    val frozen = (apps ?: emptyList()).count { it.frozen }
    val switchCount = ToolSwitchTable.ENTRIES.count { it.group == null }
    val snaps = SnapshotStore.all(context).size
    val logCount = queue.items().size

    @Composable
    fun Tile(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        title: String,
        sub: String,
        onClick: () -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
            Column(Modifier.padding(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(13.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    text = Glitch.text(title),
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = colorScheme.onSurface,
                )
                Text(sub, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
            }
        }
    }

    Column(Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                Tile(Icons.Rounded.Campaign, "营销一键关", "11 键 · 当前开 $adsOn") { onOpen(HubView.PURIFY) }
            }
            Box(Modifier.weight(1f)) {
                Tile(Icons.Rounded.AcUnit, "应用冻结", "已冻结 $frozen · B/C 档") { onOpen(HubView.FREEZE) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                Tile(Icons.Rounded.ToggleOn, "系统开关", "$switchCount 项可调") { onOpen(HubView.SWITCH) }
            }
            Box(Modifier.weight(1f)) {
                Tile(Icons.Rounded.History, "执行记录", "$snaps 份快照 · $logCount 条") { onOpen(HubView.LOG) }
            }
        }
    }
}

/** 工作台首页最近执行预览（最多 3 条；点开进执行记录页）。 */
@Composable
private fun RecentCard(queue: ToolQueue, onOpen: () -> Unit) {
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = Glitch.text("最近执行"),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = colorScheme.onSurface,
            )
            val recent = queue.items().takeLast(3).reversed()
            if (recent.isEmpty()) {
                Text(
                    text = Glitch.text("暂无记录——开关与冻结的执行都会落在这里"),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                recent.forEach { item ->
                    val state = when (item.state) {
                        ToolQueue.Core.State.PENDING -> Glitch.text("待执行")
                        ToolQueue.Core.State.DONE -> "✓"
                        ToolQueue.Core.State.FAILED -> "×"
                    }
                    Text(
                        text = "$state ${describeAction(item.action)}",
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** 状态圆点色（iOS 系绿/琥珀，小尺寸点在深浅色下均可读）。 */
private val StatusOk = Color(0xFF34C759)
private val StatusWarn = Color(0xFFFF9500)

/** 状态 chip（对齐设计稿：圆点 + 短标签的浅底胶囊）。 */
@Composable
private fun StatusChip(label: String, ok: Boolean) {
    Row(
        modifier = Modifier
            .background(colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(13.dp))
            .padding(horizontal = 10.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).background(if (ok) StatusOk else StatusWarn, RoundedCornerShape(4.dp)))
        Text(label, fontSize = 11.sp, color = colorScheme.onSurface)
    }
}

/** 筛选胶囊 chip（对齐设计稿：选中 = 主题色浅底加粗）。 */
@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(15.dp))
            .background(
                if (selected) colorScheme.primary.copy(alpha = 0.14f)
                else colorScheme.onSurface.copy(alpha = 0.06f),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = if (selected) colorScheme.primary else colorScheme.onSurface,
        )
    }
}

/**
 * 状态摘要卡（v1.3.1：原 运行环境/OTA 告警/root 失效 三卡合一；v1.3.3 对齐设计稿 chips 化）。
 * 三枚状态 chip（root/LSPosed/OTA）+ 异常详情行 + 挂载判据折叠（F3，趁 root 读 lspd 日志最近 4 条）。
 */
@Composable
private fun SummaryCard(probe: RootShell.ProbeResult?, rootOk: Boolean, ota: OtaSentinel.Report?) {
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
    var showCriteria by rememberSaveable { mutableStateOf(false) }

    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = Glitch.text("运行状态"),
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = colorScheme.onSurface,
            )
            val fw = FrameworkState.active
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(
                    if (probe == null) "root 检测中" else if (probe.available) "root 可用" else "root 不可用",
                    ok = probe?.available == true,
                )
                StatusChip(
                    when {
                        fw == null -> "LSPosed 检测中"
                        fw -> "LSPosed 已激活"
                        else -> "LSPosed 未激活"
                    },
                    ok = fw == true,
                )
                StatusChip(
                    when {
                        ota == null -> "OTA 检查中"
                        ota.shouldWarn -> "OTA 告警"
                        else -> "OTA 防线正常"
                    },
                    ok = ota != null && !ota.shouldWarn,
                )
            }
            // 正常态：版本凭据一行（凭据不丢）；异常态：原因与处置各行其是
            if (probe != null && probe.available && fw != null && fw) {
                Text(
                    text = "root ${probe.suPath} · LSPosed ${FrameworkState.frameworkVersion} API${FrameworkState.apiVersion} · 超线固件拒升（内核>6.6.118 / SP>2026-08-01）",
                    fontSize = 11.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (!rootOk && probe != null) {
                Text(
                    text = Glitch.text("⚠ root 不可用（${probe.failure}）：开关与冻结只读。本机 root 为临时态，重启即失；已存快照与执行历史不受影响。"),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (ota != null && ota.shouldWarn) {
                Text(
                    text = Glitch.text("⚠ OTA：" + listOfNotNull(
                        ota.violatedSix.takeIf { it.isNotEmpty() }?.let { "六键失守 ${it.joinToString()}" },
                        ota.sentinelChanged.takeIf { it.isNotEmpty() }?.let { "有推送活动 ${it.joinToString()}" },
                    ).joinToString("；")),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (hookLines.isNotEmpty()) {
                TextButton(
                    text = Glitch.text(if (showCriteria) "收起挂载判据" else "挂载判据（最近 4 条 installed N/M）"),
                    onClick = { showCriteria = !showCriteria },
                    modifier = Modifier.padding(top = 4.dp).fillMaxWidth(),
                )
                if (showCriteria) {
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
            Button(
                onClick = { onRequest(entries.map { ToolAction.Switch(it.table, it.key, "0") }) },
                enabled = enabled && !running && onCount > 0,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            ) {
                Text(Glitch.text("全部关闭（写 0）"))
            }
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
                    text = Glitch.text("dns.adguard-dns.com · 需网络放行 DoT（853 端口），系统提示「无法访问」时请关闭此开关；当前：${if (mode.isBlank()) "未设置" else mode}"),
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
                        // 单向开关是功能约束（用户需知），保留提示；key/当前值属面板数据不上屏
                        if (e.offValue == null) {
                            Text(
                                text = Glitch.text("单向：只开不关，恢复走系统设置"),
                                fontSize = 11.sp,
                                color = colorScheme.onSurfaceVariantSummary,
                            )
                        }
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
            Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FreezeFilter.entries.forEach { f ->
                    FilterChip(label = f.label, selected = f == filter, onClick = { onFilter(f) })
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
private fun HistoryCard(context: Context, queue: ToolQueue, initialOpen: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(initialOpen) }
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
