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
 * 批量采集页（2026-11-22）：**只放设置与用法，不放状态统计**。
 *
 * 用户 2026-11-22 明确要求「统计弹窗是小红书里显示，不是放本应用」——所有进度/汇总/过程消息
 * 都在小红书内的悬浮面板（[top.honorhook.xposed.target.xhs.XhsAutoEntry] 注入宿主 decorView）。
 * 本页只做三件事：说清怎么用、配每篇之间的随机节奏（广播下发宿主）、提供一个兜底的「停止」。
 * 采集本身完全在小红书进程内完成，不需要无障碍服务。
 */
@Composable
fun XhsAutoScreen(bottomInnerPadding: Dp = 0.dp, onBack: () -> Unit, topInset: Dp = 0.dp) {
    val context = LocalContext.current
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
    LaunchedEffect(Unit) { pushPace() }

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
                                "**进度、已存/失败篇数、待处理与用时，都显示在小红书内的悬浮面板上**（左下角），" +
                                "运行中按钮变「取消」，随时可停。" +
                                "过滤：图片短边≤720、视频<5MB 不落盘；下载过的笔记自动跳过。",
                            fontSize = 13.sp, color = colorScheme.onSecondaryContainer,
                        )
                        Text(
                            "⚠️ 该功能默认关闭：请到「功能」页 → 小红书 → 打开「批量采集」，" +
                                "并重开小红书（开关在下次宿主进程注入时生效）。",
                            fontSize = 13.sp, color = colorScheme.error,
                        )
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
