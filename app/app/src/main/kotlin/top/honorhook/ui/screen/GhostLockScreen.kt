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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.honorhook.ui.scope.RootShell
import top.honorhook.ui.toolbox.GhostLockTool
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * GhostLock 二级页（2026-11-03）：极简三件套——状态卡 + 主按钮 + 检查 root 次按钮。
 * 全部默认值（CVE-2026-98260 fake 链路、manager 自发现、零日志）：
 * 不移植 CPU 核心组 / 安全模式 / AdvancedUI / profile 编辑（用户拍板，勿扩大）。
 * 提权按钮留待用户自测（本机已 root，AI 验收只跑「检查 root 状态」）。
 */
@Composable
fun GhostLockScreen(bottomInnerPadding: Dp = 0.dp, onBack: () -> Unit = {}, topInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var carrier by remember { mutableStateOf<GhostLockTool.CarrierInfo?>(null) }
    var extract by remember { mutableStateOf<GhostLockTool.ExtractResult?>(null) }
    var root by remember { mutableStateOf<GhostLockTool.RootStatus?>(null) }
    var running by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var lastRun by remember { mutableStateOf<String?>(null) }

    // 进页先做只读检查：载体 + 载荷提取 + root 状态（任何一步异常吞掉显示失败文案）
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            carrier = GhostLockTool.carrier(context)
            extract = if (carrier?.installed == true && carrier?.versionOk == true) {
                GhostLockTool.extractPayloads(context)
            } else {
                null
            }
            root = GhostLockTool.checkRoot()
            RootShell.reset()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .padding(horizontal = 12.dp)
            // topInset 必须消费：外层 Scaffold 不自动让位（MfaScreen 真机空白实证）
            .padding(top = topInset + 4.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        // —— 状态卡 ——
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text(
                    text = "运行状态",
                    fontWeight = FontWeight.Medium,
                    fontSize = 15.sp,
                    color = colorScheme.onSurface,
                )
                StatusLine(
                    "载荷载体",
                    when {
                        carrier == null -> "检查中…"
                        carrier?.installed != true -> "未安装 · ${GhostLockTool.CARRIER_REQUIREMENT}"
                        carrier?.versionOk != true ->
                            "版本不符（${carrier?.versionName ?: "?"}）· ${GhostLockTool.CARRIER_REQUIREMENT}"
                        else -> "GhostLock v${carrier?.versionName} 已安装"
                    },
                )
                StatusLine(
                    "载荷提取",
                    when {
                        extract == null && carrier?.versionOk != true -> "未提取（等载体就绪）"
                        extract == null -> "未提取"
                        extract?.ok == true -> "已提取（payload + 临时 su）"
                        else -> "提取失败：${extract?.error ?: "未知"}"
                    },
                )
                StatusLine(
                    "Root",
                    when {
                        root == null -> "检测中…"
                        root?.suAvailable != true -> "su 不可用（需 KSU 授权）"
                        root?.kernelsuLive == true -> "KernelSU 已在线 · 已是 root"
                        else -> "su 可用 · kernelsu 模块未加载"
                    },
                )
                root?.kernelRelease?.let {
                    StatusLine("内核", it)
                }
            }
        }

        // —— 主按钮：完整提权流程（默认参数，见 GhostLockTool）——
        val ready = extract?.ok == true && root?.suAvailable == true && !running
        Button(
            onClick = {
                running = true
                scope.launch {
                    lastRun = withContext(Dispatchers.IO) { GhostLockTool.runEscalation(context).message }
                    running = false
                }
            },
            enabled = ready,
            modifier = Modifier.padding(top = 12.dp).fillMaxWidth(),
        ) {
            Text(if (running) "提权流程运行中…" else "运行提权流程（CVE-2026-98260 · 默认参数）")
        }
        if (extract == null || extract?.ok != true || root?.suAvailable != true) {
            Text(
                text = GhostLockTool.CARRIER_REQUIREMENT + "；且需 KernelSU 授权本应用的 su",
                fontSize = 11.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        lastRun?.let {
            Text(
                text = it,
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        // —— 次按钮：检查 root 状态（AI 验收走这条）——
        TextButton(
            text = if (checking) "检查中…" else "检查 root 状态",
            enabled = !checking && !running,
            onClick = {
                checking = true
                scope.launch {
                    root = withContext(Dispatchers.IO) {
                        val status = GhostLockTool.checkRoot()
                        RootShell.reset()
                        status
                    }
                    checking = false
                }
            },
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth(),
        )

        Spacer(
            Modifier.height(
                bottomInnerPadding +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() + 12.dp
            )
        )
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.padding(top = 6.dp)) {
        Text(
            text = label,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.width(72.dp),
        )
        Text(text = value, fontSize = 12.sp, color = colorScheme.onSurface)
    }
}
