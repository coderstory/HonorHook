package top.honorhook.ui.screen

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.FilterList
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
import top.honorhook.ui.component.StatusChip
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
 * 工具箱 v1.3.5（2026-10-25 用户拍板：工作台模式）：状态摘要 + 2×2 功能磁贴 + 两级页面。
 * - 首页：运行状态摘要卡（root/LSPosed/OTA chips）+ 四磁贴（营销推荐/
 *   应用冻结/系统开关/执行记录，副标题实时计数）。
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
    var showA by rememberSaveable { mutableStateOf(false) }
    var showFolded by rememberSaveable { mutableStateOf(false) }
    var view by rememberSaveable { mutableStateOf(HubView.HOME) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var freezeMenuOpen by remember { mutableStateOf(false) }
    var bulkNote by remember { mutableStateOf<String?>(null) }

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

    // 统一二级页导航（照 FeatureListScreen 标准样式）：顶栏=二级标题+返回箭头；
    // 侧滑/返回键回工作台而非退出 app（2026-10-25 用户指正）
    BackHandler(enabled = view != HubView.HOME) { view = HubView.HOME }

    Scaffold(
        topBar = {
            TopAppBar(
                title = if (view == HubView.HOME) "工具箱" else view.title,
                navigationIcon = { if (view != HubView.HOME) MiuixBackButton { view = HubView.HOME } },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        if (view == HubView.FREEZE) {
            FreezePane(
                rootOk = rootOk,
                topInset = innerPadding.calculateTopPadding(),
                apps = apps,
                running = running,
                selected = selected,
                onSelectedChange = { selected = it },
                search = search,
                onSearchChange = { search = it },
                showA = showA,
                onToggleA = { showA = it },
                bottomInnerPadding = bottomInnerPadding,
                onUnfreeze = { app -> request(listOf(ToolAction.Unfreeze(app.pkg)), "解冻 ${app.label}") },
                onFreezeSelected = { request(selected.map { ToolAction.Freeze(it) }, "冻结所选") },
                onOpenBulkMenu = { freezeMenuOpen = true },
                bulkNote = bulkNote,
            )
        } else {
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
        ) {
            when (view) {
                    // —— 工作台首页：状态摘要 + 磁贴（最近执行预览已删——与执行记录磁贴同数据源，同屏双份冗余） ——
                    HubView.HOME -> {
                        item { SummaryCard(probe, rootOk, ota) }
                        item { HubTiles(switchValues, apps, context, queue, onOpen = { view = it }) }
                    }

                // —— 净化页：一键动作 ——
                HubView.PURIFY -> {
                    item {
                        AdsGroupCard(rootOk, switchValues, running) { actions ->
                            request(actions, "营销推荐：全部关闭")
                        }
                    }
                    item {
                        PrivateDnsCard(rootOk, switchValues, running) { actions ->
                            request(actions, "私有 DNS")
                        }
                    }
                }

                // —— 开关页：功能描述行 ——
                HubView.SWITCH -> {
                    item {
                        SwitchListCard(
                            entries = ToolSwitchTable.ENTRIES.filter { it.group == null && (!it.folded || showFolded) },
                            values = switchValues,
                            enabled = rootOk && !running,
                            onToggle = { entry, turnOn ->
                                val value = if (turnOn) entry.onValue else entry.offValue
                                if (value == null) {
                                    runResult = "「${entry.title}」是单向开关：只开不关"
                                } else {
                                    request(listOf(ToolAction.Switch(entry.table, entry.key, value)), entry.title)
                                }
                            },
                        )
                    }
                    if (!showFolded) {
                        item {
                            TextButton(
                                text = "更多开关（含高风险）",
                                onClick = { showFolded = true },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                // 冻结页走 FreezePane 独立布局（固定搜索 + 滚动列表，见上）
                HubView.FREEZE -> {}

                // —— 记录页：执行历史与快照（顶栏即页面标题，卡内不再重复标题） ——
                HubView.LOG -> {
                    item { HistoryCard(context, queue, asPage = true) }
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
    }

    // —— 确认流（摘要 → 快照 → 执行 → 结果）——
    // 批量勾选弹层（WindowDialog 必须在页面顶层与 ConfirmDialog 同级渲染——Scaffold 内容区内不显示，真机实证）
    fun applyBulk(opt: BulkOption) {
        val selectableApps = (apps ?: emptyList()).filter { it.selectable }
        val installedSet = selectableApps.map { it.pkg }.toSet()
        val next = when (opt) {
            BulkOption.PRESET -> GovernanceList.PURE_PRESET.toSet().intersect(installedSet)
            BulkOption.TIER_B -> selectableApps.filter { it.entry?.tier == GovernanceList.Tier.B }.map { it.pkg }.toSet()
            BulkOption.TIER_C -> selectableApps.filter { it.entry?.tier == GovernanceList.Tier.C }.map { it.pkg }.toSet()
            BulkOption.ALL -> installedSet
            BulkOption.NONE -> emptySet()
        }
        selected = next
        bulkNote = if (opt == BulkOption.NONE) "已清空勾选"
        else "已勾选 ${next.size} 项 · ${opt.label}"
    }
    if (freezeMenuOpen) {
        WindowDialog(
            show = true,
            title = "批量勾选",
            summary = "选中后自动勾选名单内的应用，列表显示不变；「可选冻结」的部分应用有功能代价",
            onDismissRequest = { freezeMenuOpen = false },
        ) {
            BulkOption.entries.forEach { opt ->
                TextButton(
                    text = opt.label,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        applyBulk(opt)
                        freezeMenuOpen = false
                    },
                )
            }
        }
    }
    if (confirmOpen) {
        val resultText = runResult
        if (resultText != null) {
            WindowDialog(
                show = true,
                title = "执行结果",
                summary = null,
                onDismissRequest = { if (!running) { runResult = null; confirmOpen = false } },
            ) {
                Text(resultText)
                Spacer(Modifier.height(8.dp))
                TextButton(
                    text = "关闭",
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
    // 确认清单/失败明细面向用户：按开关表反查标题说人话（关闭/开启/清空/设置），
    // 表外键（理论不可达）兜底回键值表示
    is ToolAction.Switch -> {
        val e = ToolSwitchTable.ENTRIES.firstOrNull { it.key == a.key && it.table == a.table }
        when {
            e == null -> "写入 ${a.key}=${a.value}"
            a.value.isBlank() -> "清空 ${e.title}"
            a.value == e.offValue -> "关闭 ${e.title}"
            a.value == e.onValue -> "开启 ${e.title}"
            else -> "设置 ${e.title}"
        }
    }
}

/** 工具箱二级视图（v1.3.4）：顶栏标题随视图切换（FeatureListScreen 同款导航）。 */
private enum class HubView(val title: String) {
    HOME("工具箱"), PURIFY("营销推荐"), SWITCH("系统开关"), FREEZE("应用冻结"), LOG("执行记录"),
}

/** 冻结页批量勾选项（2026-10-25 拍板：下拉「影响勾选」不过滤列表展示）。 */
private enum class BulkOption(val label: String) {
    PRESET("纯净模式精选"), TIER_B("可选冻结（有功能代价）"), TIER_C("建议冻结"), ALL("全部勾选"), NONE("清空勾选"),
}

/**
 * 冻结页独立布局：搜索框与批量勾选/冻结所选固定不滚动，应用列表滚动。
 * 页面外壳（顶栏返回、BackHandler）由 ToolboxScreen 统一提供。
 */
@Composable
private fun FreezePane(
    rootOk: Boolean,
    topInset: androidx.compose.ui.unit.Dp,
    apps: List<AppInfo>?,
    running: Boolean,
    selected: Set<String>,
    onSelectedChange: (Set<String>) -> Unit,
    search: String,
    onSearchChange: (String) -> Unit,
    showA: Boolean,
    onToggleA: (Boolean) -> Unit,
    bottomInnerPadding: androidx.compose.ui.unit.Dp,
    onUnfreeze: (AppInfo) -> Unit,
    onFreezeSelected: () -> Unit,
    onOpenBulkMenu: () -> Unit,
    bulkNote: String?,
) {
    val selectableApps = (apps ?: emptyList()).filter { it.selectable }
    val visibleApps = selectableApps.filter { app ->
        search.isBlank() || app.label.contains(search, true) || app.pkg.contains(search, true)
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 12.dp)
            // 必须消费顶栏 inset：否则固定区（搜索/按钮）被顶栏盖住（真机实证「搜索栏消失」bug）
            .padding(top = topInset + 4.dp),
    ) {
        // 固定区：搜索框（不随列表滚动）
        InputField(
            query = search,
            onQueryChange = onSearchChange,
            onSearch = { },
            expanded = false,
            onExpandedChange = { },
            label = "搜索系统应用",
            modifier = Modifier.fillMaxWidth(),
        )
        // 固定区：批量勾选下拉触发 + 冻结所选
        Row(
            modifier = Modifier.padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Card(
                onClick = onOpenBulkMenu,
                showIndication = true,
                insideMargin = PaddingValues(horizontal = 14.dp, vertical = 11.dp),
                modifier = Modifier.weight(1f),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        Icons.Rounded.FilterList,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "按名单批量勾选",
                        fontSize = 14.sp,
                        color = colorScheme.onSurface,
                    )
                }
            }
            TextButton(
                text = "冻结所选 (${selected.size})",
                enabled = rootOk && !running && selected.isNotEmpty(),
                onClick = onFreezeSelected,
            )
        }
        bulkNote?.let {
            Text(
                text = it,
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        // 滚动区：应用行 + A 档
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(
                top = 8.dp,
                bottom = bottomInnerPadding +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() + 12.dp,
            ),
        ) {
            items(visibleApps, key = { it.pkg }) { app ->
                FreezeRow(
                    app = app,
                    enabled = rootOk && !running,
                    checked = app.pkg in selected,
                    onCheck = { on -> onSelectedChange(if (on) selected + app.pkg else selected - app.pkg) },
                    onUnfreeze = { onUnfreeze(app) },
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
                    text = if (showA) "收起禁碰名单" else "展开禁碰名单",
                    onClick = { onToggleA(!showA) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
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
                    text = title,
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
                Tile(Icons.Rounded.Campaign, "营销推荐", "11 个系统开关 · 当前开 $adsOn") { onOpen(HubView.PURIFY) }
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

/**
 * 状态摘要卡（v1.3.4）：三枚状态 chip + root 临时态一行说明。
 * 挂载判据整线已删（2026-10-25 用户拍板：工程诊断数据不上用户界面）。
 */
@Composable
private fun SummaryCard(
    probe: RootShell.ProbeResult?,
    rootOk: Boolean,
    ota: OtaSentinel.Report?,
) {
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                text = "运行状态",
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
                        ota == null -> "更新防护检查中"
                        ota.shouldWarn -> "更新防护异常"
                        else -> "更新防护正常"
                    },
                    ok = ota != null && !ota.shouldWarn,
                )
            }
            Text(
                text = "root 为临时授权，手机重启后失效",
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (!rootOk && probe != null) {
                Text(
                    text = "⚠ root 不可用（${probe.failure}）：开关和冻结暂时用不了；已保存的记录和备份不受影响。",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (ota != null && ota.shouldWarn) {
                Text(
                    text = "⚠ 更新防护：" + listOfNotNull(
                        ota.violatedSix.takeIf { it.isNotEmpty() }?.let { "有 ${it.size} 项防护开关被系统改回（${it.joinToString()}）" },
                        ota.sentinelChanged.takeIf { it.isNotEmpty() }?.let { "检测到系统更新推送活动（${it.joinToString()}）" },
                    ).joinToString("；"),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
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
                text = "营销推荐",
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = colorScheme.onSurface,
            )
            Text(
                text = "一键关闭荣耀系统里的 11 个营销/推荐开关（个性化推送、智慧推荐、负一屏推荐流、系统广告总开关、位置上报等）。写入系统设置，全局生效；可能被云端回写，隔天复查。当前开 $onCount 个。",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
            )
            Button(
                onClick = { onRequest(entries.map { ToolAction.Switch(it.table, it.key, "0") }) },
                enabled = enabled && !running && onCount > 0,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
            ) {
                Text("全部关闭")
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
                    text = "私有 DNS 去遥测",
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = colorScheme.onSurface,
                )
                Text(
                    text = "dns.adguard-dns.com · 需网络放行 DoT（853 端口），系统提示「无法访问」时请关闭此开关；当前：${if (mode.isBlank()) "未设置" else mode}",
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
                            text = if (e.risk == ToolSwitchTable.Risk.HIGH) "⚠ ${e.title}" else e.title,
                            fontSize = 15.sp,
                            color = colorScheme.onSurface,
                        )
                        if (e.summary.isNotBlank()) {
                            Text(e.summary, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                        }
                        // 单向开关是功能约束（用户需知），保留提示；key/当前值属面板数据不上屏
                        if (e.offValue == null) {
                            Text(
                                text = "单向：只开不关，恢复走系统设置",
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
                    text = "已冻结 · 点解冻",
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
        title = title,
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
                text = "将执行 ${actions.size} 项：\n" +
                    actions.joinToString("\n") { describeAction(it) } +
                    "\n\n冻结前会自动备份，随时可恢复；失败不中断，每项都会检查结果。",
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
                        text = "确认冻结 B 档：有真实功能代价，解冻需 root 在场",
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            Row(modifier = Modifier.padding(top = 12.dp)) {
                TextButton(
                    text = "取消",
                    onClick = onCancel,
                    enabled = !running,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                TextButton(
                    text = if (running) "执行中…" else "执行",
                    onClick = onConfirm,
                    enabled = !running && (!hasBTier || bConfirmed),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** 执行记录卡；LOG 页模式（asPage=true）不渲染折叠头——顶栏已是页面标题（2026-10-25 去重）。 */
@Composable
private fun HistoryCard(context: Context, queue: ToolQueue, asPage: Boolean = false) {
    var open by rememberSaveable { mutableStateOf(asPage) }
    Card(modifier = Modifier.padding(top = 12.dp).fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (!asPage) Row(
                modifier = Modifier.fillMaxWidth().clickable { open = !open },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = "执行记录与快照",
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = colorScheme.onSurface,
                    )
                    val pending = queue.pending().size
                    Text(
                        text = if (pending > 0) "待执行 $pending 项（点开查看）" else "点开查看队列与快照",
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
            if (open) {
                queue.items().takeLast(8).reversed().forEach { item ->
                    val state = when (item.state) {
                        ToolQueue.Core.State.PENDING -> "待执行"
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
                        text = "备份快照：",
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
