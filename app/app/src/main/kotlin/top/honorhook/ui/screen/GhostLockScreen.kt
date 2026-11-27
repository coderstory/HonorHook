package top.honorhook.ui.screen

import top.honorhook.ui.component.AppIcons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.honorhook.tools.ghostlock.shizuku.ShizukuExploitRunner
import top.honorhook.ui.toolbox.GhostLockRunner
import top.honorhook.ui.toolbox.GhostLockRunner.Backend
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.honorhook.ui.component.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 临时 Root 二级页（2026-11-03 定稿）：Shizuku 授权 / 漏洞版本选择 / 执行+运行弹窗。
 *  无 root 场景（Shizuku shell uid 投放执行）；默认零日志（onLog 丢弃）。
 *  2026-11-27 用户指令加回漏洞版本选择（98260 / 64560 已移植，其余占位待移植）。 */
@Composable
fun GhostLockScreen(bottomInnerPadding: Dp = 0.dp, onBack: () -> Unit = {}, topInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { ShizukuExploitRunner(context) }

    var shizukuOk by remember { mutableStateOf(false) }
    var running by remember { mutableStateOf(false) }
    var runStatus by remember { mutableStateOf("") }
    val logLines = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var runOpen by remember { mutableStateOf(false) }
    // 漏洞版本：默认 98260（本机既有路径），选择只在本次会话内保持
    var backend by remember { mutableStateOf(Backend.Cve202698260) }
    var backendPickerOpen by remember { mutableStateOf(false) }

    // 状态卡数据：内核版本走 System.getProperty("os.version")，无需任何特权。
    // 不做 /proc/modules 探测——GKI 上 KernelSU 是 kprobe 型，不注册模块条目，探测恒 false 且误导（2026-11-04 定稿）
    var kernelVersion by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        kernelVersion = System.getProperty("os.version") ?: "未知"
    }

    fun refreshShizuku() {
        shizukuOk = runner.status() == ShizukuExploitRunner.Status.READY
    }
    LaunchedEffect(Unit) { refreshShizuku() }
    LaunchedEffect(Unit) { runner.setStatusListener { refreshShizuku() } }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 12.dp)
            .padding(top = topInset + 4.dp),
    ) {
        // 滚动区：三张卡片；执行按钮固定在页面底部（滚动区之外）
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
        // 顶部状态卡：深绿底样式对齐 GhostlockUI.kt 的 kernel_supported 卡（暗色 0xFF1A3825 / 亮色 0xFFDFFAE4）。
        // 布局：圆形徽标 + 标题/内核副标题 + CVE 胶囊标签行
        val dark = isSystemInDarkTheme()
        val statusBg = if (dark) Color(0xFF1A3825) else Color(0xFFDFFAE4)
        val accent = Color(0xFF4CD97B)
        val secondary = colorScheme.onSurfaceVariantSummary
        val chipBg = if (dark) Color(0x2EFFFFFF) else Color(0x8CFFFFFF)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(statusBg, RoundedCornerShape(20.dp))
                .padding(horizontal = 18.dp, vertical = 16.dp),
        ) {
        // 徽标与右侧全部内容（标题/内核/标签）垂直居中：圆圈=合并单元格式占满整卡高度的中轴
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .height(40.dp)
                    .width(40.dp)
                    .background(accent.copy(alpha = 0.18f), RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = accent, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            }
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "临时提权 · 内核漏洞可用",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    color = colorScheme.onSurface,
                )
                Text(
                    kernelVersion,
                    fontSize = 12.sp,
                    color = secondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 胶囊标签：半透明白底，弱化次级信息
                    Box(
                        modifier = Modifier
                            .background(chipBg, RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(backend.label, fontSize = 11.sp, color = secondary, fontWeight = FontWeight.Medium)
                    }
                    Box(
                        modifier = Modifier
                            .background(chipBg, RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("Shizuku 投放 · 零日志", fontSize = 11.sp, color = secondary, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        }

        // 漏洞版本卡：左右布局对齐 Shizuku 卡（左=徽标+标题/副标题，右=当前版本 + ›）；
        // 整行可点开行式选择弹窗，每版的一句话说明在上游是卡内正文，这里移到弹窗行内（卡宽放不下）
        Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { backendPickerOpen = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .height(36.dp)
                        .width(36.dp)
                        .background(Color(0xFFF0A93B).copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        AppIcons.Terminal,
                        contentDescription = null,
                        tint = Color(0xFFF0A93B),
                        modifier = Modifier.height(20.dp).width(20.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        "漏洞版本",
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = colorScheme.onSurface,
                    )
                    Text(
                        if (backend.implemented) "已移植 · 点击切换" else "未移植 · 仅供预览",
                        fontSize = 12.sp,
                        color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                Text(
                    backend.label,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (backend.implemented) Color(0xFF2E7D4F) else colorScheme.onSurfaceVariantSummary,
                )
                Icon(
                    AppIcons.ChevronRight,
                    contentDescription = null,
                    tint = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(start = 4.dp).height(18.dp).width(18.dp),
                )
            }
        }

        // Shizuku 卡：安全图标徽标 + 标题/副标题 + 右侧状态胶囊（绿=已授权 / 灰=未就绪）；未授权时下方出授权按钮
        Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .height(36.dp)
                            .width(36.dp)
                            .background(colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(10.dp)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            AppIcons.Security,
                            contentDescription = null,
                            tint = colorScheme.primary,
                            modifier = Modifier.height(20.dp).width(20.dp),
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            "Shizuku",
                            fontWeight = FontWeight.Medium,
                            fontSize = 15.sp,
                            color = colorScheme.onSurface,
                        )
                        Text(
                            if (shizukuOk) "已连接 · shell uid 投放通道" else "未就绪 · 先启动 Shizuku 应用",
                            fontSize = 12.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    // 状态胶囊：绿=已授权 / 灰=未就绪
                    Box(
                        modifier = Modifier
                            .background(
                                if (shizukuOk) Color(0xFF4CD97B).copy(alpha = 0.14f) else Color(0x14000000),
                                RoundedCornerShape(999.dp),
                            )
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text(
                            if (shizukuOk) "已授权" else "未就绪",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (shizukuOk) Color(0xFF2E7D4F) else colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                if (!shizukuOk) {
                    TextButton(
                        text = "授权 Shizuku",
                        onClick = {
                            runner.requestPermission()
                            scope.launch {
                                kotlinx.coroutines.delay(1500)
                                refreshShizuku()
                            }
                        },
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(44.dp),
                    )
                }
            }
        }

        }

        Button(
            // 淡绿底 + 深绿字，与顶部状态卡同一绿色系；禁用态灰绿弱化
            colors = ButtonDefaults.buttonColors(
                color = Color(0xFFDFFAE4),
                disabledColor = Color(0xFFE8ECE9),
                contentColor = Color(0xFF1A4D2E),
                disabledContentColor = Color(0xFF9AA39E),
            ),
            onClick = {
                running = true
                runOpen = true
                runStatus = "准备执行"
                scope.launch {
                    val (ok, msg) = withContext(Dispatchers.IO) {
                        GhostLockRunner.run(context, backend, null /* 自发现：默认优先 KernelSU */, onLog = { line -> logLines.add(line) }) { step, status ->
                            runStatus = "$step · $status"
                        }
                    }
                    running = false
                    runStatus = if (ok) "完成" else "失败"
                }
            },
            enabled = shizukuOk && !running,
            // 与「选择根管理器」按钮同宽：卡片内按钮比屏幕列多缩进 16dp，这里对齐同一内边距；
            // 深绿描边收束淡绿底（画在 border 层，不改变按钮自身形状与点击区域）
            // 固定在页面底部：滚动区之外的尾部
            modifier = Modifier
                .padding(top = 16.dp)
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .height(48.dp)
                .border(1.5.dp, Color(0xFF2E7D4F), RoundedCornerShape(12.dp)),
        ) {
            Text(if (running) "执行中…" else "执行提权")
        }

        Spacer(
            Modifier.height(
                bottomInnerPadding +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() + 12.dp + 8.dp
            )
        )
    }

    // 漏洞版本选择弹窗：已移植项可选，未移植项置灰只展示（后续逐版补执行链路）
    if (backendPickerOpen) {
        WindowDialog(
            show = true,
            title = "选择漏洞版本",
            summary = "只影响本次执行的载荷与策略；失败不会重启设备",
            onDismissRequest = { backendPickerOpen = false },
        ) {
            @Composable
            fun pickerRow(item: Backend, selected: Boolean) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) Color(0xFF4CD97B).copy(alpha = 0.14f) else Color(0x14000000),
                            RoundedCornerShape(12.dp),
                        )
                        .clickable(enabled = item.implemented) {
                            backend = item
                            backendPickerOpen = false
                        }
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.label,
                            fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (item.implemented) colorScheme.onSurface else colorScheme.onSurfaceVariantSummary.copy(alpha = 0.6f),
                        )
                        Text(
                            item.summary,
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                    if (selected) {
                        Text("✓", color = Color(0xFF4CD97B), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    } else if (!item.implemented) {
                        Text("未移植", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary.copy(alpha = 0.7f))
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Backend.entries.forEach { item ->
                    pickerRow(item, backend == item)
                }
            }
        }
    }

    // 运行弹窗（保留）：执行中常驻，完成后出「关闭」
    if (runOpen) {
        WindowDialog(
            show = true,
            title = "提权运行中",
            summary = "载荷经 Shizuku 投放执行，请勿离开本页",
            onDismissRequest = { if (!running) runOpen = false },
        ) {
            Column {
                Text(runStatus, fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                androidx.compose.foundation.lazy.LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(240.dp)
                        .background(androidx.compose.ui.graphics.Color(0xFF101014)),
                ) {
                    items(logLines.size) { i ->
                        Text(
                            logLines[i],
                            fontSize = 11.sp,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = androidx.compose.ui.graphics.Color(0xFF9BE29B),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 1.dp),
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                if (!running) {
                    TextButton(text = "关闭", onClick = { runOpen = false }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
