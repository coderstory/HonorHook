package top.honorhook.ui.screen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import top.honorhook.AutoActions
import top.honorhook.ui.component.Card
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 本页可观察状态（驱动在宿主进程，进度经广播回传到这里）。 */
private object XhsAutoUiState {
    var running by mutableStateOf(false)
    var phase by mutableStateOf("待机")
    var ok by mutableIntStateOf(0)
    var skipped by mutableIntStateOf(0)
    var failed by mutableIntStateOf(0)
    val logs = mutableStateListOf<String>()
}

/**
 * 节奏范围本地存档（模块侧，跨进程下发给宿主驱动）。
 * 单位是**分秒（0.1 秒）**：UI 步进 0.5s，用整秒存不下（2026-11-22 用户要求步进 0.5）。
 */
private object PaceStore {
    private const val FILE = "honorhook_settings"
    private const val KEY_MIN = "xhs_auto_min_ds"
    private const val KEY_MAX = "xhs_auto_max_ds"

    fun min(context: Context): Int = prefs(context).getInt(KEY_MIN, 20).coerceIn(10, 600)
    fun max(context: Context): Int = prefs(context).getInt(KEY_MAX, 40).coerceIn(min(context), 1200)

    fun save(context: Context, min: Int, max: Int) {
        prefs(context).edit().putInt(KEY_MIN, min).putInt(KEY_MAX, max).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}

/**
 * 批量采集页（2026-11-22）：观测面板 + 节奏配置。
 *
 * 采集本身完全在小红书进程内完成（[top.honorhook.xposed.target.xhs.XhsBatchDriver] 直接点宿主的
 * view），不需要无障碍服务。本页做四件事：看进度、半途停止（广播到宿主）、改每篇之间的
 * 随机节奏（广播下发）、看日志尾。触发入口仍是作者主页的悬浮「采集」按钮。
 */
@Composable
fun XhsAutoScreen(bottomInnerPadding: Dp = 0.dp, onBack: () -> Unit, topInset: Dp = 0.dp) {
    val context = LocalContext.current
    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val st = XhsAutoUiState
    var minSec by remember { mutableIntStateOf(PaceStore.min(context)) }
    var maxSec by remember { mutableIntStateOf(PaceStore.max(context)) }

    // 进入本页 + 每次改动都下发给宿主（驱动在那边读，模块进程写不到它的存储）
    fun pushPace() {
        PaceStore.save(context, minSec, maxSec)
        context.sendBroadcast(
            Intent(AutoActions.CONFIG)
                .setPackage(AutoActions.HOST_PACKAGE)
                .putExtra(AutoActions.EXTRA_PACE_MIN_DS, minSec)
                .putExtra(AutoActions.EXTRA_PACE_MAX_DS, maxSec),
        )
    }
    LaunchedEffect(Unit) {
        pushPace()
        // 驱动在宿主进程、状态不在本进程里：进页问一次真实运行态（审计 #13），
        // 否则中途打开本页会一直显示「待机」且没有「停止采集」可用。
        runCatching {
            context.sendBroadcast(Intent(AutoActions.QUERY).setPackage(AutoActions.HOST_PACKAGE))
        }
    }

    // 驱动发来的进度广播 → 本页状态（离开页面即摘，避免泄漏）
    DisposableEffect(Unit) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    AutoActions.STATE, AutoActions.DONE -> {
                        val on = i.getBooleanExtra(AutoActions.EXTRA_RUNNING, false)
                        // 新起一轮：清掉上一轮的计数与日志，别把旧数字当成本轮（审计 #20）
                        if (on && !st.running) {
                            st.ok = 0
                            st.skipped = 0
                            st.failed = 0
                            st.logs.clear()
                        }
                        st.running = on
                    }
                    AutoActions.PROGRESS -> {
                        // 进度带 running=true 就说明在跑（收尾那条带 false，不能反手点亮）（审计 #13）
                        if (i.getBooleanExtra(AutoActions.EXTRA_RUNNING, false)) st.running = true
                        st.ok = i.getIntExtra("ok", st.ok)
                        st.skipped = i.getIntExtra("skipped", st.skipped)
                        st.failed = i.getIntExtra("failed", st.failed)
                    }
                }
                i.getStringExtra(AutoActions.EXTRA_TEXT)?.let {
                    st.phase = it
                    st.logs.add("${timeFmt.format(Date())} $it")
                    while (st.logs.size > 60) st.logs.removeAt(0)
                }
            }
        }
        ContextCompat.registerReceiver(
            context, r,
            IntentFilter().apply {
                addAction(AutoActions.STATE)
                addAction(AutoActions.PROGRESS)
                addAction(AutoActions.DONE)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { runCatching { context.unregisterReceiver(r) } }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent,
                title = "批量采集",
                navigationIcon = { MiuixBackButton(onBack) },
            )
        },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top),
    ) { inner ->
        // inner 必须吃掉：不吃就会顶到 TopAppBar 底下叠字（2026-11-22 截图实证）
        LazyColumn(
            modifier = Modifier.padding(inner),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomInnerPadding + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card {
                    Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                        Text("用法", fontSize = 15.sp)
                        Text(
                            "打开小红书 → 进某个作者的主页 → 点右侧红色「采集」按钮。" +
                                "之后自动逐篇打开、下载原图并返回列表，一直滚到底。" +
                                "运行中按钮变「取消」（带已处理篇数），随时可停。" +
                                "过滤：图片短边≤720、视频<5MB 不落盘；下载过的笔记自动跳过。",
                            fontSize = 13.sp, color = colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp), Arrangement.spacedBy(8.dp)) {
                        Text("状态", fontSize = 15.sp)
                        Text(if (st.running) "运行中" else "待机", fontSize = 14.sp)
                        Text(st.phase, fontSize = 13.sp, color = colorScheme.onSecondaryContainer)
                        Text(
                            "成功 ${st.ok} 篇 · 跳过 ${st.skipped} 篇 · 失败 ${st.failed} 篇",
                            fontSize = 13.sp, color = colorScheme.onSecondaryContainer,
                        )
                        if (st.running) {
                            // 停止 = 广播到宿主进程的驱动（驱动在 com.xingin.xhs 里，UI 进程调不到）
                            Button(
                                onClick = {
                                    context.sendBroadcast(
                                        Intent(AutoActions.STOP).setPackage(AutoActions.HOST_PACKAGE),
                                    )
                                    st.phase = "已发送停止"
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("停止采集") }
                        }
                    }
                }
            }
            item {
                Card {
                    Column(Modifier.padding(16.dp), Arrangement.spacedBy(4.dp)) {
                        Text("节奏（每篇之间的随机等待）", fontSize = 15.sp)
                        PaceRow("最短", minSec, 10, 600) {
                            minSec = it
                            if (maxSec < minSec) maxSec = minSec
                            pushPace()
                        }
                        PaceRow("最长", maxSec, minSec, 1200) {
                            maxSec = it
                            pushPace()
                        }
                        Text(
                            "范围 ${fmtDs(minSec)}~${fmtDs(maxSec)} 秒。改完立即下发，下次点「采集」生效。",
                            fontSize = 12.sp, color = colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
            if (st.logs.isNotEmpty()) {
                item {
                    Card {
                        Column(Modifier.padding(16.dp), Arrangement.spacedBy(4.dp)) {
                            Text("日志", fontSize = 15.sp)
                            st.logs.forEach {
                                Text(it, fontSize = 12.sp, color = colorScheme.onSecondaryContainer)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 分秒 → 显示文本（20 → "2.0"）。 */
private fun fmtDs(ds: Int): String {
    val sec = ds / 10
    val frac = ds % 10
    return if (frac == 0) "${sec}.0" else "$sec.$frac"
}

/** 一行步进器：− 值 ＋（步长 0.5 秒，越界由上下限钳住）。 */
@Composable
private fun PaceRow(label: String, value: Int, lo: Int, hi: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        TextButton(text = "−", enabled = value > lo, onClick = { onChange((value - STEP_DS).coerceAtLeast(lo)) })
        Text("${fmtDs(value)} s", fontSize = 14.sp, modifier = Modifier.padding(horizontal = 8.dp))
        TextButton(text = "＋", enabled = value < hi, onClick = { onChange((value + STEP_DS).coerceAtMost(hi)) })
    }
}

/** 步进 = 5 分秒 = 0.5 秒。 */
private const val STEP_DS = 5
