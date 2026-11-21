package top.honorhook.ui.screen

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.honorhook.ui.auto.XhsAutoService
import top.honorhook.AutoActions
import top.honorhook.ui.component.Card
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 批量采集页（2026-11-21）：状态/停止/节奏设置 + 无障碍开启引导。
 * 触发入口 = 小红书作者主页悬浮「采集」按钮（XhsAutoEntry 注入，宿主进程内起驱动 + 广播开滚）；
 * 本页不直接启动（驱动在宿主进程里，UI 进程无法直接调），只做观测、无障碍引导与半途停止。
 */
@Composable
fun XhsAutoScreen(bottomInnerPadding: Dp = 0.dp, onBack: () -> Unit, topInset: Dp = 0.dp) {
    val context = LocalContext.current
    var enabled by remember { mutableIntStateOf(if (autoServiceEnabled(context)) 1 else 0) }
    val st = top.honorhook.ui.auto.XhsAutoState
    val prefs = remember { XhsAutoService.prefs(context) }
    var minS by remember { mutableIntStateOf(prefs.getInt("xhs_auto_min_s", 2)) }
    var maxS by remember { mutableIntStateOf(prefs.getInt("xhs_auto_max_s", 4)) }

    Scaffold(containerColor = Color.Transparent, topBar = {
        TopAppBar(
            color = Color.Transparent,
            title = "批量采集",
            navigationIcon = { MiuixBackButton(onBack) },
        )
    }, contentWindowInsets = WindowInsets.systemBars
        .add(WindowInsets.displayCutout)
        .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top)
    ) { inner ->
        LazyColumn(Modifier.padding(bottom = bottomInnerPadding)) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                        Text("用法：打开小红书进入作者主页 → 点右侧红色「采集」按钮。服务会自动往下滚，每滚一屏就把该批笔记的原图直接下载（不打开详情页、不点保存），滚到底或再点一次「取消」即结束。过滤：图片短边≤720、视频 <5MB 不落盘；已下载过的自动跳过。", fontSize = 13.sp, color = colorScheme.onSecondaryContainer)
                        Text(
                            if (enabled == 1) "无障碍服务：已开启 ✅" else "无障碍服务：未开启 ❌（点下方按钮去系统设置开启「Re:荣耀 / 批量采集」）",
                            fontSize = 13.sp, color = if (enabled == 1) colorScheme.primary else colorScheme.onSecondaryContainer,
                        )
                        if (enabled == 0) {
                            Button(onClick = {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("打开系统无障碍设置")
                            }
                            TextButton(text = "刷新状态", onClick = { enabled = if (autoServiceEnabled(context)) 1 else 0 })
                        }
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                        Text("状态：${if (st.running.value) "运行中" else "待机"} · ${st.phase.value}", fontSize = 15.sp)
                        Text("成功 ${st.ok.value} 篇 · 跳过 ${st.skipped.value} 篇 · 失败 ${st.failed.value} 篇", fontSize = 13.sp, color = colorScheme.onSecondaryContainer)
                        if (st.running.value) {
                            // 停止 = 广播到宿主进程的驱动接收器（驱动在 com.xingin.xhs 里，UI 进程调不到）
                            Button(onClick = {
                                context.sendBroadcast(Intent(AutoActions.STOP).setPackage(AutoActions.HOST_PACKAGE))
                                st.running.value = false
                                st.phase.value = "已停止"
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text("停止采集")
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            // 联动钳制：下限不得越过上限（引擎侧也有 coerce，这里只为 UI 不自相矛盾）
                            Stepper("节奏下限", minS) {
                                minS = it
                                if (maxS < it) { maxS = it; prefs.edit().putInt("xhs_auto_max_s", it).apply() }
                                prefs.edit().putInt("xhs_auto_min_s", it).apply()
                            }
                            Stepper("节奏上限", maxS) {
                                maxS = it
                                if (minS > it) { minS = it; prefs.edit().putInt("xhs_auto_min_s", it).apply() }
                                prefs.edit().putInt("xhs_auto_max_s", it).apply()
                            }
                        }
                        Text("节奏 = 每篇笔记之间的随机等待秒数（防风控，建议 2~4）", fontSize = 12.sp, color = colorScheme.onSecondaryContainer)
                    }
                }
            }
            if (st.logs.isNotEmpty()) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp)) {
                            Text("日志", fontSize = 15.sp)
                            st.logs.forEach { Text(it, fontSize = 12.sp, color = colorScheme.onSecondaryContainer) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Stepper(label: String, value: Int, onSet: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$label ${value}s", fontSize = 13.sp)
        TextButton(text = "−", onClick = { onSet((value - 1).coerceAtLeast(1)) })
        TextButton(text = "＋", onClick = { onSet((value + 1).coerceAtMost(30)) })
    }
}

private fun autoServiceEnabled(context: Context): Boolean {
    val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
    return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_GENERIC).any {
        it.resolveInfo?.serviceInfo?.packageName == context.packageName
    }
}
