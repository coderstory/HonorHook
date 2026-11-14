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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Password
import androidx.compose.material.icons.rounded.TravelExplore
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.ToggleOn
import androidx.compose.material3.Icon
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.state.ToggleableState
import top.yukonga.miuix.kmp.basic.Checkbox
import top.honorhook.ui.component.genshin.GenshinIcons
import top.honorhook.ui.component.genshin.genshinOr
import top.honorhook.ui.toolbox.AppIcon
import top.honorhook.ui.FrameworkState
import top.honorhook.ui.component.cardClick
import top.honorhook.ui.component.StatusChip
import top.honorhook.ui.scope.RootShell
import top.honorhook.ui.scope.SafeCommands
import top.honorhook.ui.photos.AiImageScreen
import top.honorhook.ui.screen.MfaScreen
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
import top.honorhook.ui.component.Card
import top.honorhook.ui.component.motion.EntranceEdge
import top.honorhook.ui.component.motion.EntranceMotion
import top.honorhook.ui.component.motion.cardEntrance
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
 * 工具箱 v1.3.5（2026-10-25 用户拍板：工作台模式）：状态摘要 + 2 列功能磁贴 + 两级页面。
 * - 首页：运行状态摘要卡（root/LSPosed/OTA chips）+ 五磁贴（营销推荐/
 *   应用冻结/MFA/远程开发/文件整理，副标题实时计数）。
 * - 2026-11-09 用户指令删除「临时Root」（GhostLock CVE 提权整链，含 Shizuku 通道与 payload assets）。
 * - 二级页：磁贴点进、返回栏回台；冻结沿用 v1.3.1 组件，营销页 = 一键卡。
 * - 2026-11-08 用户指令删除「系统开关」整页（连同 18 条非营销开关），不留入口。
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

    // 营销键现值：免 root 读三表（表名定位在执行器做，这里只展示；系统开关模块已删，只读 ADS_GROUP）
    var switchValues by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    fun refreshValues() {
        switchValues = ToolSwitchTable.ADS_GROUP.associate { e ->
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
        // 透明底：让出 AppRoot 里的背景皮肤（GardenBackdrop）
        containerColor = Color.Transparent,
        topBar = {
            // FILES / AI_IMAGE 页无顶栏（自带标题行 + 页内返回键，2026-11-11 修顶栏与页内标题重复）；
            // 状态栏避让走 contentWindowInsets.Top
            if (view != HubView.FILES && view != HubView.AI_IMAGE) TopAppBar(
                color = Color.Transparent, // 顶部磨砂带由 ArtworkBackdrop 提供，这里必须让出底色
                title = if (view == HubView.HOME) "工具箱" else view.title,
                navigationIcon = { if (view != HubView.HOME) MiuixBackButton { view = HubView.HOME } },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
    ) { innerPadding ->
        if (view == HubView.MFA) {
            // MFA 验证器（2026-11-03）：自带 LazyColumn/心跳/对话框的整页，接住返回键回工作台
            MfaScreen(bottomInnerPadding = bottomInnerPadding, onBack = { view = HubView.HOME }, topInset = innerPadding.calculateTopPadding())
        } else if (view == HubView.FILES) {
            // 文件整理（2026-11-04）：规则引擎 + 页面见 ui/toolbox/FileOrganizer.kt（规格 project/file-organizer-rules.md v2）
            // FILES 页无顶栏：Miuix Scaffold 不渲染 TopAppBar 时 innerPadding 仍按顶栏高度预留 → 改用纯状态栏高度
            FileOrganizerScreen(bottomInnerPadding = bottomInnerPadding,
                topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding())
        } else if (view == HubView.AI_IMAGE) {
            // AI 图像工具（2026-11-10）：AI 超清/扩图，能力来自 PhotosAiClient（图库注入通道），见 ui/photos/AiImageScreen.kt
            AiImageScreen(bottomInnerPadding = bottomInnerPadding,
                topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding(),
                onBack = { view = HubView.HOME })
        } else if (view == HubView.FREEZE) {
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
                    // FILES 整页在外层 if 分支接住（FileOrganizerScreen 自带滚动），此处仅保 when 穷尽
                    HubView.FILES -> {}
                    // —— 工作台首页：状态摘要 + 磁贴（最近执行预览已删——与执行记录磁贴同数据源，同屏双份冗余） ——
                    HubView.HOME -> {
                        // 运行状态卡已删（2026-11-03 用户指令）：probe/rootOk 仍供各开关/冻结页只读态
                        item {
                        HubTiles(
                            switchValues,
                            apps,
                            onOpen = { view = it },
                            onOpenHubActivity = {
                                context.startActivity(android.content.Intent(
                                    context, top.honorhook.ui.webscan.WebScanActivity::class.java)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            },
                        )
                    }
                    }

                // —— 净化页：一键动作 ——
                HubView.PURIFY -> {
                    item {
                        AdsGroupCard(rootOk, switchValues, running) { actions ->
                            request(actions, "营销推荐：全部关闭")
                        }
                    }
                }

                // 冻结页走 FreezePane 独立布局（固定搜索 + 滚动列表，见上）
                HubView.FREEZE -> {}
                // MFA 页走独立整页分支（见 when 外层）
                HubView.MFA -> {}
                HubView.AI_IMAGE -> {}
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
            // 每项一张卡片：label 主行 + 说明副行 + 右侧本次会勾中的数量（实时算，不用再点才知道）
            BulkOption.entries.forEachIndexed { i, opt ->
                val count = when (opt) {
                    BulkOption.NONE -> 0
                    else -> {
                        val selectable = (apps ?: emptyList()).filter { it.selectable }
                        when (opt) {
                            BulkOption.PRESET ->
                                selectable.count { it.pkg in GovernanceList.PURE_PRESET }
                            BulkOption.TIER_B -> selectable.count { it.entry?.tier == GovernanceList.Tier.B }
                            BulkOption.TIER_C -> selectable.count { it.entry?.tier == GovernanceList.Tier.C }
                            BulkOption.ALL -> selectable.size
                            BulkOption.NONE -> 0
                        }
                    }
                }
                Card(
                    onClick = {
                        applyBulk(opt)
                        freezeMenuOpen = false
                    },
                    showIndication = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = if (i == 0) 0.dp else 8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(opt.label, fontSize = 15.sp, color = colorScheme.onSurface)
                            Text(opt.desc, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                        }
                        Text(
                            text = if (opt == BulkOption.NONE) "" else "$count 项",
                            fontSize = 13.sp,
                            color = colorScheme.primary,
                        )
                    }
                }
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
    HOME("工具箱"), PURIFY("营销推荐"), FREEZE("应用冻结"), MFA("MFA 验证器"),
    FILES("文件整理"), AI_IMAGE("AI 图像工具"),
}

/** 冻结页批量勾选项（2026-10-25 拍板：下拉「影响勾选」不过滤列表展示）。 */
private enum class BulkOption(val label: String, val desc: String) {
    PRESET("纯净模式精选", "名单内的系统营销 / 推送类应用"),
    TIER_B("可选冻结（有功能代价）", "有副作用，冻结后会二次确认"),
    TIER_C("建议冻结", "低风险，可放心冻结"),
    ALL("全部勾选", "所有可冻结应用"),
    NONE("清空勾选", "取消已勾选的全部应用"),
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
 * 工作台 2×2 功能磁贴（v1.3.2；2026-11-03 执行记录磁贴删、MFA 验证器磁贴加）：
 * 副标题实时计数——营销键开数 / 冻结数 / 开关项数。
 */
@Composable
private fun HubTiles(
    values: Map<String, String>,
    apps: List<AppInfo>?,
    onOpen: (HubView) -> Unit,
    onOpenHubActivity: () -> Unit = {},
) {
    val adsOn = ToolSwitchTable.ADS_GROUP.count { values[it.key] == "1" }
    val frozen = (apps ?: emptyList()).count { it.frozen }

    // 磁贴固定宽（2026-11-11 用户要求「不要自适应，计算出固定值」）：weight(1f) 会随内容/
    // 父容器变化微调，改为一进页面按屏宽算死：屏宽 - 页面左右 12dp*2 - 两格间 12dp，再对半。
    // 记住本次打开的计算值，重组不重算（顺转/数据刷新不会让卡片抖宽度）。
    val screenDp = LocalConfiguration.current.screenWidthDp.dp
    val tileWidth = remember(screenDp) {
        (screenDp - 24.dp - 12.dp) / 2
    }

    // 每格一个语义色（2026-11-08 用户要求「图标整体好看一点」）：
    // 冷色=清理类、暖色=拦截类、青/紫=工具类，深浅色主题下都能保持对比度。
    val accentAds = Color(0xFFF2685A)
    val accentFreeze = Color(0xFF4FA8FF)
    val accentMfa = Color(0xFF35C77E)
    val accentWeb = Color(0xFF2BB3C0)
    val accentFiles = Color(0xFF00BFA5)

    @Composable
    fun Tile(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        title: String,
        sub: String,
        accent: Color,
        // 磁贴是 3 个硬编码 Row 里的固定格子（没有循环序号），所以序号手写 0..5；
        // key 同时用序号：一个开场里每格只播一次，进子页再回 HOME 不重播。
        order: Int,
        onClick: () -> Unit,
    ) {
        Card(
            modifier = Modifier
                .width(tileWidth)
                .fillMaxHeight()
                // 每行两格（左右并列）：左列从左、右列从右合拢
                // 磁贴的动效性格（2026-11-08 用户反馈「功能和工具箱的卡片动效一样」）：
                // 功能页的网格是「左右合拢飞入」，这里改成「原地弹起 + 回弹吸附」（EntranceMotion.Snap），
                // 只上浮 ~28dp、从 0.69 弹到 1，错峰也收紧到 34ms ⇒ 六块磁贴像被一块块按在板上，
                // 与功能页的长途飞入在**读感**上分家，但仍是同一套弹簧 + 错峰语言。
                .cardEntrance(
                    order,
                    key = "tool.tile.$order",
                    edge = EntranceEdge.Bottom,
                    motion = EntranceMotion.Snap,
                )
                // cardClick = clickable + 触感（同 Card(onClick=) 的一档"嗒"）；磁贴把点击挂在 modifier 上
                .cardClick(onClick = onClick),
        ) {
            Column(Modifier.padding(16.dp)) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(
                            Brush.linearGradient(
                                listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.10f)),
                            ),
                            RoundedCornerShape(15.dp),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
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
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                Tile(genshinOr(Icons.Rounded.Campaign, GenshinIcons.Pyro), "营销推荐", "11 个营销开关 · 当前开 $adsOn", accentAds, order = 0) { onOpen(HubView.PURIFY) }
            }
            Box(Modifier.weight(1f)) {
                Tile(genshinOr(Icons.Rounded.AcUnit, GenshinIcons.Cryo), "应用冻结", "已冻结 $frozen · B/C 档", accentFreeze, order = 1) { onOpen(HubView.FREEZE) }
            }
        }
        // 2026-11-09 删「临时Root」后重排：2 行 4 格 + 文件整理单格（营销推荐|应用冻结、MFA|远程开发）
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                Tile(genshinOr(Icons.Rounded.Password, GenshinIcons.Vision), "MFA 验证器", "TOTP 动态码 · 扫码/导入", accentMfa, order = 2) { onOpen(HubView.MFA) }
            }
            Box(Modifier.weight(1f)) {
                // 远程开发（2026-11-03）：独立 Activity（扫码开网页，极简白）
                Tile(genshinOr(Icons.Rounded.TravelExplore, GenshinIcons.Anemo), "远程开发", "扫码打开网页 · 全屏浏览器", accentWeb, order = 3) {
                    onOpenHubActivity()
                }
            }
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.weight(1f)) {
                // 文件整理（2026-11-04）：规格 project/file-organizer-rules.md v2
                Tile(genshinOr(Icons.Rounded.PhotoLibrary, GenshinIcons.Primogem), "文件整理", "照片按月归档 · 垃圾清理", accentFiles, order = 4) { onOpen(HubView.FILES) }
            }
            Box(Modifier.weight(1f)) {
                val accentAi = Color(0xFF9C6BFF)
                Tile(genshinOr(Icons.Rounded.AutoAwesome, GenshinIcons.Primogem), "AI 图像工具", "AI 超清 · AI 扩图", accentAi, order = 6) { onOpen(HubView.AI_IMAGE) }
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
    Card(
        modifier = Modifier
            .padding(top = 12.dp)
            .fillMaxWidth()
            .cardEntrance(6, key = "tool.ads", edge = EntranceEdge.Bottom),
    ) {
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
                AppIcon(app.pkg, 44.dp, Modifier.padding(end = 14.dp))
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
        // 三行文本（名称 / 包名 / 代价说明）＋图标：CheckboxPreference 只有 title+summary 两个槽，
        // 塞不下第三行，故这里用 Card+Row 自绘；点整行等效勾选，勾选框自身也可点。
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = enabled) { onCheck(!checked) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.pkg, 44.dp, Modifier.padding(end = 14.dp))
            Column(Modifier.weight(1f)) {
                Text(app.label, fontSize = 15.sp, color = colorScheme.onSurface)
                Text(app.pkg, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                val cost = app.entry?.cost
                if (!cost.isNullOrBlank()) {
                    Text(cost, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                }
            }
            Checkbox(
                state = if (checked) ToggleableState.On else ToggleableState.Off,
                onClick = { if (enabled) onCheck(!checked) },
                enabled = enabled,
            )
        }
    }
}

@Composable
private fun RedLineRow(app: AppInfo) {
    Card(modifier = Modifier.padding(top = 8.dp).fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIcon(app.pkg, 44.dp, Modifier.padding(end = 14.dp))
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
        // 确认正文按实际动作类型生成：只有冻结才跑快照，「随时可恢复」不能被开关批次借用——那会让用户
        // 以为写系统设置也有模块内恢复按钮（实际没有，得去系统设置逐项改）。
        val hasFreeze = actions.any { it is ToolAction.Freeze }
        val hasSwitch = actions.any { it is ToolAction.Switch }
        val notes = buildList {
            if (hasFreeze) add("冻结前会自动备份，随时可恢复")
            if (hasSwitch) add("开关项写入系统设置，模块内不提供一键恢复，需到系统设置逐项改回")
            add("失败不中断，每项都会检查结果")
        }
        Column {
            Text(
                text = "将执行 ${actions.size} 项：\n" +
                    actions.joinToString("\n") { describeAction(it) } +
                    "\n\n" + notes.joinToString("；") + "。",
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

