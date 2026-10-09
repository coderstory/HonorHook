package top.honorhook.ui.screen

import android.content.Intent
import android.content.SharedPreferences
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.libxposed.service.HookedTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import top.honorhook.HookApplication
import top.honorhook.R
import top.honorhook.data.KEY_LOG_RING
import top.honorhook.data.REMOTE_PREFERENCE_GROUP
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 运行日志页：读取 hook 侧经 daemon 远程 prefs 双写的 KEY_LOG_RING（getString），
 * 经 [transformLog] 展示层变换后以等宽字体列出；头部为活跃目标状态卡
 * （XposedService.getRunningTargets，注入进程的直接判据）。
 */
@Composable
fun LogScreen(onBack: () -> Unit) {
    val service by HookApplication.service.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    // 远程 prefs 快照（service 断开/re-bind 时随 remember(service) 重建，勿每帧重复 get）
    val prefs = remember(service) { service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP) }

    var lines by remember { mutableStateOf<List<MergedLogLine>>(emptyList()) }
    var targets by remember { mutableStateOf<List<HookedTarget>>(emptyList()) }

    // 监听器是 daemon binder 推送（hook 进程写 KEY_LOG_RING 即回调到本进程），
    // 回调落在 binder 线程，状态更新 post 回主线程；注册/注销随页面进入/退出
    DisposableEffect(prefs) {
        if (prefs == null) {
            lines = emptyList()
            onDispose {}
        } else {
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_LOG_RING) {
                    val newLines = transformLog(prefs.getString(KEY_LOG_RING, "").orEmpty())
                    scope.launch(Dispatchers.Main) { lines = newLines }
                }
            }
            lines = transformLog(prefs.getString(KEY_LOG_RING, "").orEmpty())
            prefs.registerOnSharedPreferenceChangeListener(listener)
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }

    // 进程启停没有事件推送，只能轻量轮询（5s，页面在组合中才执行）
    LaunchedEffect(service) {
        while (isActive) {
            val s = service
            targets = if (s == null) {
                emptyList()
            } else {
                runCatching { s.getRunningTargets() }.getOrDefault(emptyList())
            }
            delay(5000)
        }
    }

    val rendered = remember(lines) { renderLines(lines) }
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.log_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.ChevronBackward, contentDescription = stringResource(R.string.appfn_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = UiTokens.PagePadding),
        ) {
            RunningTargetsCard(targets)
            Spacer(Modifier.height(UiTokens.CardSpacing))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(UiTokens.CardSpacing),
            ) {
                Button(
                    onClick = {
                        if (rendered.isEmpty()) return@Button
                        clipboard.setText(AnnotatedString(rendered.joinToString("\n")))
                        Toast.makeText(context, context.getString(R.string.log_copied), Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.log_copy), fontSize = UiTokens.SubtitleSize)
                }
                Button(
                    onClick = {
                        if (rendered.isEmpty()) return@Button
                        val share = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, rendered.joinToString("\n"))
                        }
                        runCatching { context.startActivity(Intent.createChooser(share, null)) }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.log_share), fontSize = UiTokens.SubtitleSize)
                }
                Button(
                    onClick = {
                        lines = emptyList()
                        // 远端置空会经 daemon 推给 hook 进程，其环形缓冲同步清
                        if (prefs != null) {
                            runCatching { prefs.edit().putString(KEY_LOG_RING, "").apply() }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(stringResource(R.string.log_clear), fontSize = UiTokens.SubtitleSize)
                }
            }
            Spacer(Modifier.height(UiTokens.CardSpacing))
            if (rendered.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(R.string.log_empty),
                        fontSize = UiTokens.CaptionSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                SelectionContainer(
                    modifier = Modifier.weight(1f),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = UiTokens.PagePadding),
                    ) {
                        items(rendered) { line ->
                            Text(
                                line,
                                fontSize = UiTokens.CaptionSize,
                                fontFamily = FontFamily.Monospace,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 活跃目标状态卡：getRunningTargets 返回的注入进程清单（断连/无目标时显示空态）。 */
@Composable
private fun RunningTargetsCard(targets: List<HookedTarget>) {
    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
        Column(
            Modifier.padding(
                horizontal = UiTokens.CardMarginH,
                vertical = UiTokens.CardMarginV,
            ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = MiuixIcons.ListView,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.width(UiTokens.CardMarginH))
                Text(
                    stringResource(R.string.log_running_targets_title),
                    fontSize = UiTokens.SubtitleSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Spacer(Modifier.height(8.dp))
            if (targets.isEmpty()) {
                Text(
                    stringResource(R.string.log_running_targets_empty),
                    fontSize = UiTokens.CaptionSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                targets.forEach { target ->
                    Text(
                        target.processName,
                        fontSize = UiTokens.SubtitleSize,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.log_target_state_format, target.pid, target.state),
                        fontSize = UiTokens.CaptionSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

/** KEY_LOG_RING 行：`MM-dd HH:mm:ss [tag] msg`，可选 ` ×N` 折叠计数后缀（hook 侧写入格式）。 */
private data class MergedLogLine(val timeText: String, val content: String, val count: Int)

private val LOG_TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")

private val LOG_WINDOW_MS: Long = 30 * 60 * 1000L

private val LOG_LINE_PATTERN = Regex("""^(\d{2}-\d{2} \d{2}:\d{2}:\d{2}) (.+?)(?: ×(\d+))?\s*$""")

/**
 * 展示层变换（hook 侧写入时已做裁剪/折叠，此处为双保险，防 daemon 残留旧数据与多进程写竞争重复）：
 * ① 按行内时间戳滤掉超过 30 分钟的行；
 * ② 相邻相同内容（时间戳与 `×N` 计数后缀除外）合并，计数相加、保留最末行时间戳；
 * 无时间戳的非标准行直接丢弃。行序保持写入序（最新在末尾）。
 */
private fun transformLog(raw: String, nowMs: Long = System.currentTimeMillis()): List<MergedLogLine> {
    if (raw.isBlank()) return emptyList()
    val out = ArrayList<MergedLogLine>()
    for (line in raw.lineSequence()) {
        val m = LOG_LINE_PATTERN.matchEntire(line.trim()) ?: continue
        val timeMs = parseTimeMs(m.groupValues[1], nowMs) ?: continue
        if (nowMs - timeMs > LOG_WINDOW_MS) continue
        val content = m.groupValues[2].trim()
        val count = m.groupValues[3].toIntOrNull() ?: 1
        val last = out.lastOrNull()
        if (last != null && last.content == content) {
            out[out.size - 1] = last.copy(timeText = m.groupValues[1], count = last.count + count)
        } else {
            out += MergedLogLine(m.groupValues[1], content, count)
        }
    }
    return out
}

/** `MM-dd HH:mm:ss` → epochMs；行时间戳无年份，取当年，跨年窗口（1 月见去年 12 月）回退一年。 */
private fun parseTimeMs(timeText: String, nowMs: Long): Long? = runCatching {
    val t = LocalDateTime.parse(timeText, LOG_TIME_FORMAT)
    val now = LocalDateTime.ofInstant(Instant.ofEpochMilli(nowMs), ZoneId.systemDefault())
    var dt = t.withYear(now.year)
    if (dt.isAfter(now.plusMonths(6))) dt = dt.minusYears(1)
    dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
}.getOrNull()

private fun renderLines(lines: List<MergedLogLine>): List<String> = lines.map { line ->
    val suffix = if (line.count > 1) " ×${line.count}" else ""
    "${line.timeText} ${line.content}$suffix"
}