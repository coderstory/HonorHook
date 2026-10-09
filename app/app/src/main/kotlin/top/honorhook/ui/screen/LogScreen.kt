package top.honorhook.ui.screen

import android.content.Context
import android.content.Intent
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.honorhook.HookApplication
import top.honorhook.R
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Ok
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 远端日志文件名（hook 侧 LogRing 内存队列 + 后台落盘同一文件；UI 侧只读/删除）。 */
private const val LOG_FILE_NAME = "honorhook.log"

/** 远端日志刷新轮询间隔（文件无变更推送，照 getRunningTargets 轮询模式）。 */
private const val POLL_INTERVAL_MS: Long = 5000

/** 滚动阈值语义（规格 §4 SCROLL_THRESHOLD）：距端 >500 行跳转，否则平滑滚动。 */
private const val SCROLL_THRESHOLD = 500

/** 自动换行开关本地持久化（纯 UI 偏好，不经过 daemon；LSPosed 同款记忆行为）。 */
private const val PREFS_UI = "honorhook_ui"
private const val KEY_WORD_WRAP = "log_word_wrap"

/**
 * 日志页（Tab3，照 LSPosed Logs 页规格 §4 重排）：
 * 大标题"日志" + 顶栏菜单（保存/分享可见图标 + 溢出菜单：滚顶/滚底/清空/自动换行）。
 * 读数链路（2026-10-09 修正）：hook 侧经 daemon 落盘远端文件，本页经
 * XposedService.openRemoteFile("honorhook.log") 读全文——prefs 双写通道废弃
 * （hook 侧 getRemotePreferences 是只读快照），文件无变更推送，改 5s 轮询。
 * 行格式不变：`MM-dd HH:mm:ss [tag] msg`，可选 ` ×N` 折叠计数。
 */
@Composable
fun LogScreen() {
    val service by HookApplication.service.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var lines by remember { mutableStateOf<List<MergedLogLine>>(emptyList()) }
    var wordWrap by remember { mutableStateOf(loadWordWrap(context)) }
    var menuExpanded by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()

    // 5s 轮询读取远端日志文件（service 断连/重绑随 remember 重启；offload 到 IO 线程）
    LaunchedEffect(service) {
        val s = service
        if (s == null) {
            lines = emptyList()
            return@LaunchedEffect
        }
        while (isActive) {
            val raw = withContext(Dispatchers.IO) { readRemoteLogFile(s) }
            val newLines = transformLog(raw)
            if (newLines != lines) lines = newLines
            delay(POLL_INTERVAL_MS)
        }
    }

    // 新行到达：停在末尾（或首帧）时跟随滚到底部，不打断用户上翻
    LaunchedEffect(lines.size) {
        if (lines.isEmpty()) return@LaunchedEffect
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (lastVisible == -1 || lastVisible >= lines.lastIndex - 1) {
            listState.scrollToItem(lines.lastIndex)
        }
    }

    val rendered = remember(lines) { renderLines(lines) }

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.log3_title),
                largeTitle = stringResource(R.string.log3_title),
                actions = {
                    // 保存（图标可见）：系统分享文本全部日志（规格 §4 Save 的 HonorHook 映射）
                    IconButton(onClick = { exportLogs(context, rendered) }) {
                        Icon(
                            imageVector = MiuixIcons.Share,
                            contentDescription = stringResource(R.string.log3_save),
                            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                    // 溢出菜单：滚到顶部 / 滚到底部 / 清空日志 / 自动换行
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                imageVector = MiuixIcons.More,
                                contentDescription = stringResource(R.string.log3_more),
                                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                        if (menuExpanded) {
                            Popup(
                                alignment = Alignment.TopEnd,
                                offset = IntOffset(0, with(LocalDensity.current) { 8.dp.roundToPx() }),
                                onDismissRequest = { menuExpanded = false },
                                properties = PopupProperties(focusable = true),
                            ) {
                                LogOverflowMenu(
                                    wordWrap = wordWrap,
                                    onScrollTop = {
                                        menuExpanded = false
                                        scope.launch { listState.jumpOrSmoothToTop() }
                                    },
                                    onScrollBottom = {
                                        menuExpanded = false
                                        scope.launch { listState.jumpOrSmoothToBottom() }
                                    },
                                    onClear = {
                                        menuExpanded = false
                                        clearRemoteLog(service)
                                        lines = emptyList()
                                    },
                                    onToggleWordWrap = {
                                        menuExpanded = false
                                        wordWrap = !wordWrap
                                        saveWordWrap(context, wordWrap)
                                    },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        if (rendered.isEmpty()) {
            // 空态（规格 §5 空态 token：居中 + 弱色次色）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    stringResource(R.string.log3_empty_title),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.log3_empty_hint),
                    fontSize = UiTokens.CaptionSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
            }
        } else {
            // 日志列表（规格 §4 item_log_textview 语义：12sp monospace onSurface、水平 16dp、
            // 无颜色分级；强制 LTR。自动换行开 = 竖排正常 wrap；关 = 行内不换行横向滚动）
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                items(rendered) { line ->
                    if (wordWrap) {
                        Text(
                            line,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                        )
                    } else {
                        Box(Modifier.horizontalScroll(rememberScrollState())) {
                            Text(
                                line,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                color = MiuixTheme.colorScheme.onSurface,
                                softWrap = false,
                                maxLines = 1,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
                }
            }
        }
    }
}

/** 保存（分享）：ACTION_SEND 全部日志文本（规格 §4；空列表不弹）。 */private fun exportLogs(context: Context, rendered: List<String>) {
    if (rendered.isEmpty()) return
    val share = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, rendered.joinToString("\n"))
    }
    runCatching { context.startActivity(Intent.createChooser(share, null)) }
}

/**
 * 远端日志读取：openRemoteFile("honorhook.log") → ParcelFileDescriptor → 全文字节流转 String。
 * 服务未连接/文件不存在/读取失败一律回退空串（配合 transformLog 的空输入短路）。
 */
private fun readRemoteLogFile(service: XposedService): String = runCatching {
    val pfd = service.openRemoteFile(LOG_FILE_NAME)
    if (pfd == null) "" else ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().decodeToString() }
}.getOrDefault("")

/** 清空：删除远端日志文件（hook 侧下次落盘自然重建，参见 hook 侧 LogRing 重写）。 */
private fun clearRemoteLog(service: XposedService?) {
    if (service != null) runCatching { service.deleteRemoteFile(LOG_FILE_NAME) }
}

private fun loadWordWrap(context: Context): Boolean =
    context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE).getBoolean(KEY_WORD_WRAP, true)

private fun saveWordWrap(context: Context, enabled: Boolean) {
    context.getSharedPreferences(PREFS_UI, Context.MODE_PRIVATE)
        .edit().putBoolean(KEY_WORD_WRAP, enabled).apply()
}

/** 溢出菜单（顶栏 More 弹出的次级动作；自动换行项带对勾指示当前态）。 */
@Composable
private fun LogOverflowMenu(
    wordWrap: Boolean,
    onScrollTop: () -> Unit,
    onScrollBottom: () -> Unit,
    onClear: () -> Unit,
    onToggleWordWrap: () -> Unit,
) {
    Card(modifier = Modifier.width(180.dp), cornerRadius = UiTokens.CardRadius) {
        Column {
            OverflowItem(MiuixIcons.ExpandLess, stringResource(R.string.log3_scroll_top), onScrollTop)
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            OverflowItem(MiuixIcons.ExpandMore, stringResource(R.string.log3_scroll_bottom), onScrollBottom)
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            OverflowItem(MiuixIcons.Delete, stringResource(R.string.log3_clear), onClear)
            HorizontalDivider(Modifier.padding(horizontal = 12.dp))
            OverflowItem(
                MiuixIcons.Ok,
                stringResource(R.string.log3_word_wrap),
                onToggleWordWrap,
                isChecked = wordWrap,
            )
        }
    }
}

/** 溢出菜单单行（图标 + 文本；isChecked 非空时为可勾选项，开启态右端显示对勾）。 */
@Composable
private fun OverflowItem(
    icon: ImageVector,
    title: String,
    onClick: () -> Unit,
    isChecked: Boolean? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (isChecked == true) {
            Icon(
                imageVector = MiuixIcons.Ok,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

/** 滚到顶部：距顶 >500 行跳转，否则平滑滚动（规格 §4 SCROLL_THRESHOLD）。 */
private suspend fun LazyListState.jumpOrSmoothToTop() {
    if (firstVisibleItemIndex > SCROLL_THRESHOLD) scrollToItem(0) else animateScrollToItem(0)
}

/** 滚到底部：距底 >500 行跳转，否则平滑滚动。 */
private suspend fun LazyListState.jumpOrSmoothToBottom() {
    val lastVisible = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: firstVisibleItemIndex
    val last = layoutInfo.totalItemsCount - 1
    if (last - lastVisible > SCROLL_THRESHOLD) scrollToItem(last) else animateScrollToItem(last)
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