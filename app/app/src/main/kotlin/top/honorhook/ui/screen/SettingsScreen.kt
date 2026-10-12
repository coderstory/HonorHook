package top.honorhook.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.honorhook.FeatureCatalog
import top.honorhook.ui.FeatureStore
import top.honorhook.ui.scope.ScopeRestarter
import top.honorhook.BuildConfig
import top.honorhook.ui.update.UpdateChecker
import top.honorhook.ui.update.UpdateState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置 Tab —— **照抄 KernelSU `SettingsMiuix.kt`**：
 * ```
 * Scaffold(topBar = TopAppBar("设置", scrollBehavior)) {
 *   LazyColumn(padding(horizontal = 12.dp), contentPadding = innerPadding) {
 *     Card(padding(top = 12.dp)) { SwitchPreference(...) ; ArrowPreference(主题设置) }
 *     Card(padding(top = 12.dp)) { ArrowPreference(运行日志) ; ArrowPreference(关于) }
 *     Spacer(bottomInnerPadding)
 *   }
 * }
 * ```
 * 注意：**没有「界面风格」项**——UI 固定 Miuix 单模式。
 */
@Composable
fun SettingsScreen(bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    var page by remember { mutableStateOf<Page?>(null) }
    BackHandler(enabled = page != null) { page = null }
    when (page) {
        Page.THEME -> ThemeSettingsPage(onBack = { page = null }, bottomInnerPadding = bottomInnerPadding)
        Page.ABOUT -> AboutPage(onBack = { page = null }, bottomInnerPadding = bottomInnerPadding)
        else -> SettingsMain(
            bottomInnerPadding = bottomInnerPadding,
            onOpenTheme = { page = Page.THEME },
            onOpenAbout = { page = Page.ABOUT },
        )
    }
}

private enum class Page { THEME, ABOUT }

@Composable
private fun SettingsMain(
    bottomInnerPadding: androidx.compose.ui.unit.Dp,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    var showRestart by remember { mutableStateOf(false) }
    val updateSummary = run {
        val latest = UpdateState.latest
        when {
            UpdateState.checking -> "检查中…"
            latest != null && UpdateChecker.isNewer(latest, BuildConfig.VERSION_NAME, BuildConfig.BUILD_NUM) ->
                "发现新版本 ${latest.tag}"
            latest != null -> "已是最新（构建 ${BuildConfig.BUILD_NUM}）"
            else -> "当前 v${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.BUILD_NUM}）"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "设置",
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    // 第一组：作用域进程
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = "强制重启作用域应用",
                            summary = "强停目标进程，让开关改动生效",
                            startAction = {
                                Icon(
                                    Icons.Rounded.RestartAlt,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "强制重启作用域应用",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = { showRestart = true },
                        )
                    }

                    // 第二组：外观
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = "主题设置",
                            summary = "主题色、深浅色与液态玻璃",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Palette,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "主题设置",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = onOpenTheme,
                        )
                    }

                    // 第三组：关于
                    // 原「运行日志」项 onClick 为空实现却带右箭头（点进去什么都没有），已移除。
                    // 判据日志的真入口是 `tools/dev.sh modlog` / `wait-hook`（LSPosed 框架日志）。
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = "检查更新",
                            summary = updateSummary,
                            startAction = {
                                Icon(
                                    Icons.Rounded.SystemUpdateAlt,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "检查更新",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            // 手动检查：有新版 → 全局弹窗；无新版/失败 → Toast（UpdateState.notice）
                            onClick = { UpdateState.checkAsync(manual = true) },
                        )
                        ArrowPreference(
                            title = "关于",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Info,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "关于",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = onOpenAbout,
                        )
                    }

                    Spacer(
                        Modifier.height(
                            bottomInnerPadding +
                                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
                        )
                    )
                }
            }
        }
    }

    if (showRestart) {
        RestartScopeDialog(onDismiss = { showRestart = false })
    }
}

@Composable
private fun RestartScopeDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(FeatureCatalog.targets.map { it.packageName }.toSet()) }
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<ScopeRestarter.Report?>(null) }
    var push by remember { mutableStateOf<FeatureStore.PushResult?>(null) }

    WindowDialog(
        show = true,
        title = "强制重启作用域应用",
        summary = null,
        onDismissRequest = { if (!running) onDismiss() },
    ) {
        val result = report
        when {
            result != null -> RestartResult(result, push, onDismiss)
            running -> Text("正在执行…")
            else -> RestartPicker(
                selected = selected,
                onToggle = { pkg, on -> selected = if (on) selected + pkg else selected - pkg },
                onConfirm = {
                    val snapshot = selected.toList()
                    running = true
                    scope.launch {
                        // 必须先下发再强停，否则下次注入读到的还是旧远端值
                        val pushed = withContext(Dispatchers.IO) { FeatureStore.pushAllToRemote() }
                        val outcome = withContext(Dispatchers.IO) { ScopeRestarter.restart(snapshot) }
                        push = pushed
                        report = outcome
                        running = false
                    }
                },
                onCancel = onDismiss,
            )
        }
    }
}

private val RESTART_SIDE_EFFECT = mapOf(
    "com.hihonor.android.launcher" to "桌面会重建：图标与小部件重载、回到主屏",
    "com.android.systemui" to "状态栏与导航栏会重建：短暂闪一下",
)

@Composable
private fun RestartPicker(
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column {
        // 目标数随 scope 增长（现 13），纯 Column 会撑爆 WindowDialog：限高内滚动
        Column(
            modifier = Modifier
                .heightIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            FeatureCatalog.targets.forEach { target ->
                CheckboxPreference(
                    title = target.name,
                    summary = RESTART_SIDE_EFFECT[target.packageName] ?: target.packageName,
                    checked = target.packageName in selected,
                    onCheckedChange = { onToggle(target.packageName, it) },
                )
            }
        }
        Row(
            modifier = Modifier.padding(top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                text = "取消",
                onClick = onCancel,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(
                text = "执行",
                onClick = onConfirm,
                enabled = selected.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun RestartResult(
    result: ScopeRestarter.Report,
    push: FeatureStore.PushResult?,
    onDismiss: () -> Unit,
) {
    Column {
        Text(text = "已停止 ${result.stopped.size} / 在跑 ${result.runningCount}")
        push?.let {
            Text(
                text = if (!it.connected) {
                    "⚠️ LSPosed 未连接：开关未下发，hook 侧仍按上次远端值运行"
                } else {
                    "开关已下发 ${it.sent}/${it.total}"
                },
            )
        }
        result.failure?.let { Text(text = failureText(it)) }
        if (result.notRunning.isNotEmpty()) {
            Text(text = "未运行（无需处理）：${result.notRunning.joinToString()}")
        }
        if (result.failed.isNotEmpty()) {
            Text(text = "执行失败：${result.failed.joinToString()}")
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            TextButton(text = "关闭", onClick = onDismiss, modifier = Modifier.weight(1f))
        }
    }
}

private fun failureText(failure: ScopeRestarter.Failure): String = when (failure) {
    ScopeRestarter.Failure.NO_SU -> "未能获取 Root 授权"
    ScopeRestarter.Failure.NOT_GRANTED -> "root 未授权本应用：请在 KernelSU 管理器中允许 top.honorhook 后重试"
    ScopeRestarter.Failure.TIMEOUT -> "命令执行超时（5 秒），请重试"
}

/**
 * 关于页的信息行：**纯展示**，不带右箭头也不可点。
 * 原先用 `ArrowPreference` + 空 `onClick`，右箭头会给出"点进去还有东西"的错觉。
 */
@Composable
private fun AboutRow(title: String, summary: String) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = colorScheme.onSurface,
        )
        Text(
            text = summary,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
        )
    }
}

/** 关于页（照 KernelSU AboutMiuix 的卡片结构）。 */
@Composable
private fun AboutPage(
    onBack: () -> Unit,
    bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "关于",
                navigationIcon = { MiuixBackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                        AboutRow("Re:荣耀", "版本 ${appVersionLabel()}")
                        AboutRow("作者", "©${java.time.Year.now().value} CoderStory")
                        AboutRow("第三方库", "LSPosed API · Compose · Miuix · DexKit")
                    }
                }
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