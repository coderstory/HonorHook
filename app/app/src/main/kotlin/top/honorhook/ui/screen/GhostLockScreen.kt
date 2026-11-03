package top.honorhook.ui.screen

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
import top.honorhook.tools.ghostlock.data.ManagerScanner
import top.honorhook.tools.ghostlock.shizuku.ShizukuExploitRunner
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 临时 Root 二级页（2026-11-03 定稿）：Shizuku 授权 / 根管理器选择 / 执行+运行弹窗。
 *  无 root 场景（Shizuku shell uid 投放执行）；全默认参数；默认零日志（onLog 丢弃）。 */
@Composable
fun GhostLockScreen(bottomInnerPadding: Dp = 0.dp, onBack: () -> Unit = {}, topInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val runner = remember { ShizukuExploitRunner(context) }

    var shizukuOk by remember { mutableStateOf(false) }
    var managers by remember { mutableStateOf(top.honorhook.ui.toolbox.GhostLockRunner.managers(context)) }
    var selectedPkg by remember { mutableStateOf<String?>(null) }
    var running by remember { mutableStateOf(false) }
    var runStatus by remember { mutableStateOf("") }
    val logLines = remember { androidx.compose.runtime.mutableStateListOf<String>() }
    var runOpen by remember { mutableStateOf(false) }
    var managerPickerOpen by remember { mutableStateOf(false) }

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
            .padding(top = topInset + 4.dp)
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
                        Text("CVE-2026-98260", fontSize = 11.sp, color = secondary, fontWeight = FontWeight.Medium)
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

        Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.height(6.dp).width(6.dp).background(Color(0xFF4CD97B), RoundedCornerShape(3.dp)))
                    Text(
                        "Shizuku",
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    if (shizukuOk) "已授权" else "未就绪（先启动 Shizuku，再点授权）",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                if (!shizukuOk) {
                    TextButton(
                        text = "授权",
                        onClick = {
                            runner.requestPermission()
                            scope.launch {
                                kotlinx.coroutines.delay(1500)
                                refreshShizuku()
                            }
                        },
                        modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
                    )
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.height(6.dp).width(6.dp).background(Color(0xFF4CD97B), RoundedCornerShape(3.dp)))
                    Text(
                        "根管理器",
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp,
                        color = colorScheme.onSurface,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Text(
                    "当前：" + (managers.firstOrNull { it.packageName == selectedPkg }?.label
                        ?: "自发现（默认，优先 KernelSU）"),
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(
                    text = "选择根管理器",
                    onClick = { managerPickerOpen = true },
                    modifier = Modifier.padding(top = 12.dp).fillMaxWidth().height(48.dp),
                )
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
                        top.honorhook.ui.toolbox.GhostLockRunner.run(context, selectedPkg, onLog = { line -> logLines.add(line) }) { step, status ->
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
            modifier = Modifier
                .padding(top = 12.dp)
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
                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() + 12.dp
            )
        )
    }

    // 根管理器选择弹窗：候选 = BUILTIN + 已知分支（ManagerScanner 已滤普通应用），加自发现默认项。
    // 行样式：圆角 12dp，选中态绿色浅底 + 行尾 ✓；本地 @Composable 复用同一行式
    if (managerPickerOpen) {
        WindowDialog(
            show = true,
            title = "选择根管理器",
            summary = "仅列出已知的 KernelSU 系管理器；不选 = 载荷自发现（默认优先 KernelSU）",
            onDismissRequest = { managerPickerOpen = false },
        ) {
            @Composable
            fun pickerRow(label: String, selected: Boolean, onClick: () -> Unit) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (selected) Color(0xFF4CD97B).copy(alpha = 0.14f) else Color(0x14000000),
                            RoundedCornerShape(12.dp),
                        )
                        .clickable(onClick = onClick)
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = colorScheme.onSurface,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Text("✓", color = Color(0xFF4CD97B), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                pickerRow("自发现（默认，优先 KernelSU）", selectedPkg == null) {
                    selectedPkg = null; managerPickerOpen = false
                }
                managers.forEach { c ->
                    pickerRow(c.label, selectedPkg == c.packageName) {
                        selectedPkg = c.packageName; managerPickerOpen = false
                    }
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
