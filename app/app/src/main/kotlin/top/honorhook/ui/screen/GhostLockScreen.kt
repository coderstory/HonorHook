package top.honorhook.ui.screen

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
    var runOpen by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }

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
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Shizuku", fontWeight = FontWeight.Medium, fontSize = 15.sp, color = colorScheme.onSurface)
                Text(
                    if (shizukuOk) "已授权" else "未就绪（先启动 Shizuku，再点授权）",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                TextButton(
                    text = if (shizukuOk) "已授权" else "授权",
                    enabled = !shizukuOk,
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

        Card(modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("根管理器", fontWeight = FontWeight.Medium, fontSize = 15.sp, color = colorScheme.onSurface)
                Text(
                    if (managers.isEmpty()) "未检测到候选（默认：载荷自发现，优先 KernelSU）" else "检测到候选，默认自发现优先 KernelSU",
                    fontSize = 12.sp,
                    color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.padding(top = 6.dp),
                )
                managers.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            (if (selectedPkg == c.packageName) "● " else "○ ") + c.label,
                            fontSize = 13.sp,
                            color = colorScheme.onSurface,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (if (selectedPkg == null) "● " else "○ ") + "自发现（默认）",
                        fontSize = 13.sp,
                        color = colorScheme.onSurface,
                    )
                }
            }
        }

        Button(
            onClick = {
                running = true
                runOpen = true
                result = null
                runStatus = "准备执行"
                scope.launch {
                    val (ok, msg) = withContext(Dispatchers.IO) {
                        top.honorhook.ui.toolbox.GhostLockRunner.run(context, selectedPkg) { step, status ->
                            runStatus = "$step · $status"
                        }
                    }
                    result = msg
                    running = false
                    runStatus = if (ok) "完成" else "失败"
                }
            },
            enabled = shizukuOk && !running,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
        ) {
            Text(if (running) "执行中…" else "执行提权")
        }
        if (!shizukuOk) {
            Text(
                "需要先授权 Shizuku（本功能面向无 root 设备）",
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        result?.let {
            Text(it, fontSize = 13.sp, color = colorScheme.onSurface, modifier = Modifier.padding(top = 8.dp))
        }

        Spacer(
            Modifier.height(
                bottomInnerPadding +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() + 12.dp
            )
        )
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
                Spacer(Modifier.height(12.dp))
                if (!running) {
                    TextButton(text = "关闭", onClick = { runOpen = false }, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}
