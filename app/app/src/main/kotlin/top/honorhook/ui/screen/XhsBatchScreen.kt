package top.honorhook.ui.screen

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import kotlinx.coroutines.launch
import top.honorhook.ui.component.Card
import top.honorhook.ui.xhsbatch.FileXhsBatchTaskStore
import top.honorhook.ui.xhsbatch.XhsBatchController
import top.honorhook.ui.xhsbatch.XhsBatchState
import top.honorhook.ui.xhsbatch.XhsBatchTaskRecord
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 小红书用户作品批量下载页（PRD docs/prd-xhs-user-batch-download.md）：
 * 任务列表 → 新建任务（输入用户 ID）→ 进度面板（四计数 + 暂停/取消/撤回）。
 * 纯 UI 侧功能，零 hook 依赖；业务在 XhsBatchController。
 */
@Composable
fun XhsBatchScreen(bottomInnerPadding: Dp = 0.dp, topInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val store = remember { FileXhsBatchTaskStore(context) }
    // Controller 用独立 SupervisorJob scope：页面离开/重组不掐任务（取消只能由用户显式触发）；
    // Web 端进度更新走 StateFlow，页面重进后从 store 记录恢复展示
    // Controller 是应用级单例（Holder）+ 前台服务保活（2026-11-11「后台不会继续下载」）：
    // 页面只是视图层，Service 才是生命周期锚；页面重进从 progress StateFlow / store 记录恢复展示
    val controller = remember { top.honorhook.ui.xhsbatch.XhsBatchControllerHolder.get(context) }
    var records by remember { mutableStateOf(store.list()) }
    // 列表实时刷新：运行中任务每篇落一次盘，这里 2s 轮询重读（单文件小 JSON，开销可忽略）
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { store.list() }
                .let { if (it != records) records = it }
        }
    }
    var showNewTask by remember { mutableStateOf(false) }
    var newUserId by remember { mutableStateOf("") }
    var confirmWithdraw by remember { mutableStateOf(false) }
    var showLogin by remember { mutableStateOf(false) }
    // 登录态常驻显示（web_session = 小红书网页登录标志 cookie）；从登录页返回时重查
    // 登录态三态：null=检测中；不能拿 cookie 名判断（匿名访客也有 web_session，实测误报）
    var loggedIn by remember { mutableStateOf<Boolean?>(null) }
    fun refreshLogin() {
        val cookie = android.webkit.CookieManager.getInstance()
            .getCookie("https://www.xiaohongshu.com")
        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val probe = top.honorhook.ui.xhsbatch.XhsFeedClient.isWebLoggedInBlocking(cookie)
            loggedIn = probe ?: (cookie?.contains("web_session") == true)
        }
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { refreshLogin() }
    var openRecord by remember { mutableStateOf<XhsBatchTaskRecord?>(null) }

    val progress by controller.progress.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInset + 4.dp)
            .padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("小红书批量下载", fontWeight = FontWeight.Medium, fontSize = 17.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                if (controller.isRunning()) {
                    Toast.makeText(context, "已有任务进行中", Toast.LENGTH_SHORT).show()
                } else {
                    newUserId = ""
                    showNewTask = true
                }
            }) {
                Icon(Icons.Rounded.Add, contentDescription = "新建任务")
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = bottomInnerPadding + 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // ---- 电池白名单引导（荣耀 PowerKeeper 杀后台实锤 2026-11-11：FGS 也被杀）----
            item {
                val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
                if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
                    Text(
                        "⚠ 后台下载被系统限制：点击加入电池优化白名单，否则退后台可能停止",
                        fontSize = 12.sp,
                        color = androidx.compose.ui.graphics.Color(0xFFF57C00),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                                            .setData(android.net.Uri.parse("package:" + context.packageName)),
                                    )
                                }
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp),
                    )
                }
            }
            // ---- 账户状态卡（登录入口收拢到这里，不再占头部图标位）----
            item {
                if (loggedIn == null) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "正在检测登录态…",
                            fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        )
                    }
                } else if (loggedIn == true) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            androidx.compose.foundation.layout.Box(
                                Modifier.padding(end = 8.dp).size(8.dp).background(
                                    androidx.compose.ui.graphics.Color(0xFF35C77E),
                                    androidx.compose.foundation.shape.CircleShape,
                                ),
                            )
                            Text("小红书网页 · 已登录", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            Text(
                                "重新登录",
                                fontSize = 12.sp,
                                color = colorScheme.primary,
                                modifier = Modifier
                                    .clickable { showLogin = true }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            )
                        }
                    }
                } else {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp)) {
                            Text("需要登录小红书网页版", fontWeight = FontWeight.Medium, fontSize = 14.sp)
                            Text(
                                "抓取笔记列表与解析小红书号都需要网页登录态；cookie 存在本模块内（外部浏览器登录不共享）",
                                fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary,
                                modifier = Modifier.padding(top = 2.dp),
                            )
                            TextButton(
                                text = "登录小红书网页版",
                                onClick = { showLogin = true },
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            )
                        }
                    }
                }
            }
            if (records.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "暂无任务\n点右上 + 新建：输入小红书号（如 moranmoai00）或用户 ID，抓取其公开笔记并串行下载图片与视频\n下载目录：Pictures/小红书/<用户ID>",
                            fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            items(records, key = { it.taskId }) { r ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openRecord = r },
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("用户 ${r.userId}", fontWeight = FontWeight.Medium, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            // 状态胶囊：同色系浅底 + 实色字，比裸文字醒目
                            val sc = stateColor(r.state)
                            Text(
                                stateLabel(r.state),
                                fontSize = 11.sp,
                                color = sc,
                                modifier = Modifier
                                    .background(sc.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 8.dp, vertical = 2.dp),
                            )
                            // 「继续」：终态任务重跑同 userId（PRD §3.0：v1 无断点续传，继续=重跑+已下载文件去重）
                            if (r.state in CONTINUABLE) {
                                // 小号文字入口（TextButton 太大，2026-11-11 用户反馈）
                                Text(
                                    "继续",
                                    fontSize = 13.sp,
                                    color = colorScheme.primary,
                                    modifier = Modifier
                                        .clickable {
                                            controller.start(r.userId)
                                            top.honorhook.ui.xhsbatch.XhsBatchService.start(context)
                                        }
                                        .padding(horizontal = 6.dp),
                                )
                            }
                            IconButton(onClick = { store.delete(r.taskId); records = store.list() }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "删除记录")
                            }
                        }
                        Text(
                            "已处理 ${r.notesProcessed}/${r.notesFound} · 图 ${r.imagesDone} · 视频 ${r.videosDone} · %.1f MB".format(r.totalBytes / 1048576.0) +
                                (if (r.notesFailed > 0) " · 失败 ${r.notesFailed}" else ""),
                            fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }

    // ---- 新建任务对话框 ----
    if (showNewTask) {
        WindowDialog(
            show = true,
            title = "新建批量下载任务",
            summary = null,
            onDismissRequest = { showNewTask = false },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    InputField(
                        query = newUserId,
                        onQueryChange = { newUserId = it.filter { c -> c.isLetterOrDigit() || c == '_' } },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        label = "小红书号 / 用户 ID",
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "串行下载不触发风控；仅下载公开内容；可随时暂停/取消/撤回",
                        fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary,
                    )
                    TextButton(
                        text = "开始",
                        onClick = {
                            val id = newUserId.trim()
                            if (id.isEmpty()) {
                                Toast.makeText(context, "请输入用户 ID", Toast.LENGTH_SHORT).show()
                            } else if (!android.os.Environment.isExternalStorageManager()) {
                                // MANAGE_EXTERNAL_STORAGE 只能跳系统页授权（没有运行时弹窗）
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                            android.net.Uri.parse("package:io.github.coderstory.honorhook"),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                    Toast.makeText(context, "请授予「所有文件访问」后重新开始", Toast.LENGTH_LONG).show()
                                }.onFailure {
                                    Toast.makeText(context, "需要「所有文件访问」权限才能下载", Toast.LENGTH_LONG).show()
                                }
                            } else {
                                showNewTask = false
                                controller.start(id)
                                top.honorhook.ui.xhsbatch.XhsBatchService.start(context)
                                records = store.list()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
        )
    }

    // ---- 详情/进度对话框（运行中实时刷新 progress，结束后显示记录快照）----
    // 2026-11-11 重设计：状态胶囊 + 进度条 + 指标网格 + 按状态分组操作区
    val detail = openRecord
    if (detail != null) {
        val p = if (controller.isRunning() && progress.userId == detail.userId) progress else null
        val st = p?.state ?: detail.state
        val notesFound = p?.notesFound ?: detail.notesFound
        val notesProcessed = p?.notesProcessed ?: detail.notesProcessed
        val notesFailed = p?.notesFailed ?: detail.notesFailed
        val images = p?.imagesDone ?: detail.imagesDone
        val videos = p?.videosDone ?: detail.videosDone
        val mb = (p?.totalBytes ?: detail.totalBytes) / 1048576.0
        val action = p?.currentAction
        WindowDialog(
            show = true,
            title = "用户 ${detail.userId}",
            summary = null,
            onDismissRequest = { openRecord = null },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    // 状态胶囊 + 当前动作
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            stateLabel(st), fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = stateColor(st),
                            modifier = Modifier
                                .background(stateColor(st).copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                        if (!action.isNullOrBlank()) {
                            Text(action, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary, modifier = Modifier.weight(1f))
                        }
                    }
                    // 进度条（已处理 / 总篇数）
                    if (notesFound > 0) {
                        val frac = (notesProcessed.toFloat() / notesFound).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f), RoundedCornerShape(3.dp)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxWidth(frac)
                                    .height(6.dp)
                                    .background(stateColor(st), RoundedCornerShape(3.dp)),
                            )
                        }
                        Text("已处理 $notesProcessed / $notesFound 篇 · 失败 $notesFailed", fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                    }
                    // 指标 2×2 网格：图片 / 视频 / 体积 / 失败
                    listOf(
                        listOf("图片" to "$images 张", "视频" to "$videos 个"),
                        listOf("体积" to "%.1f MB".format(mb), "失败" to "$notesFailed 篇"),
                    ).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowItems.forEach { (label, value) ->
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .background(colorScheme.onSurfaceVariantSummary.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                                        .padding(10.dp),
                                ) {
                                    Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    Text(label, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                                }
                            }
                        }
                    }
                    // 目录
                    Text(detail.dirPath, fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                    // 操作区（按状态分组）
                    if (p != null && (st == XhsBatchState.Running || st == XhsBatchState.Paused)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = if (st == XhsBatchState.Paused) "恢复" else "暂停",
                                onClick = {
                                    if (st == XhsBatchState.Paused) controller.resumeRun() else controller.pause()
                                    records = store.list()
                                },
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                text = "取消", onClick = { controller.cancel(); records = store.list() },
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Text(
                            "撤回（取消并删除全部文件）",
                            fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFE53935),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { confirmWithdraw = true }
                                .padding(vertical = 6.dp),
                        )
                    } else if (st == XhsBatchState.Queued) {
                        TextButton(text = "取消排队", onClick = {
                            controller.cancelQueued(detail.userId)
                            records = store.list()
                            openRecord = null
                        }, modifier = Modifier.fillMaxWidth())
                    } else if (st in CONTINUABLE) {
                        TextButton(text = "继续下载", onClick = {
                            controller.start(detail.userId)
                            top.honorhook.ui.xhsbatch.XhsBatchService.start(context)
                            openRecord = null
                        }, modifier = Modifier.fillMaxWidth())
                        if (detail.failedNotes.isNotEmpty()) {
                            TextButton(text = "重试失败资源（${detail.failedNotes.size}）", onClick = {
                                controller.retryFailed(detail.userId)
                                top.honorhook.ui.xhsbatch.XhsBatchService.start(context)
                                openRecord = null
                            }, modifier = Modifier.fillMaxWidth())
                        }
                        Text(
                            "撤回（删除已下载文件）",
                            fontSize = 12.sp, color = androidx.compose.ui.graphics.Color(0xFFE53935),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { confirmWithdraw = true }
                                .padding(vertical = 6.dp),
                        )
                    }
                }
            },
        )
    }

    // ---- 撤回二次确认（PRD F12：不可逆删除）----
    if (confirmWithdraw) {
        WindowDialog(
            show = true,
            title = "确认撤回？",
            summary = null,
            onDismissRequest = { confirmWithdraw = false },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "将取消任务并删除本次已下载的全部文件（Pictures/小红书/<用户ID> 整个目录），不可恢复。",
                        fontSize = 13.sp,
                    )
                    TextButton(
                        text = "确认删除",
                        onClick = {
                            confirmWithdraw = false
                            openRecord = null
                            scope.launch {
                                val (deleted, failed) = controller.withdraw()
                                Toast.makeText(
                                    context,
                                    if (failed == 0) "已撤回：删除 $deleted 个文件" else "已撤回：删除 $deleted 个，失败 $failed 个",
                                    Toast.LENGTH_LONG,
                                ).show()
                                records = store.list()
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TextButton(text = "取消", onClick = { confirmWithdraw = false }, modifier = Modifier.fillMaxWidth())
                }
            },
        )
    }

    // ---- 网页登录覆盖层（全屏，盖住任务列表）----
    if (showLogin) {
        XhsLoginOverlay(topInset = topInset, onClose = {
            showLogin = false
            refreshLogin()
        })
    }
}

private fun stateLabel(s: XhsBatchState): String = when (s) {
    XhsBatchState.Idle -> "待启动"
    XhsBatchState.Fetching -> "抓取中"
    XhsBatchState.Running -> "下载中"
    XhsBatchState.Paused -> "已暂停"
    XhsBatchState.Queued -> "待执行"
    XhsBatchState.Done -> "已完成"
    XhsBatchState.Cancelled -> "已取消"
    XhsBatchState.Withdrawn -> "已撤回"
    XhsBatchState.Interrupted -> "已中断"
    XhsBatchState.Failed -> "已失败"
}

// 可「继续」重跑的终态（Running/Fetching/Paused 由进度面板接管；Withdrawn 无文件可续）
private val CONTINUABLE = setOf(
    XhsBatchState.Interrupted, XhsBatchState.Failed, XhsBatchState.Done, XhsBatchState.Cancelled,
) // Queued 不在此列：排队中由「取消排队」接管

private fun stateColor(s: XhsBatchState) = when (s) {
    XhsBatchState.Done -> androidx.compose.ui.graphics.Color(0xFF35C77E)
    XhsBatchState.Failed, XhsBatchState.Interrupted -> androidx.compose.ui.graphics.Color(0xFFF2685A)
    XhsBatchState.Running, XhsBatchState.Fetching -> androidx.compose.ui.graphics.Color(0xFF4FA8FF)
    XhsBatchState.Queued -> androidx.compose.ui.graphics.Color(0xFF9C6BFF)
    else -> androidx.compose.ui.graphics.Color(0xFF888888)
}

/**
 * 网页登录覆盖层：在本模块进程内开可见 WebView 登录小红书网页版。
 * WebView cookie 存本 App 的 CookieManager（按 App 沙箱隔离），登录一次后
 * 离屏抓取（XhsUserFeedClient / XhsBatchController.fetchHtmlDirect）即带登录态。
 */
// 诊断用文件日志：本 ROM logcat 丢弃 App 自有 tag，只能写文件取证
private object XhsLoginLog {
    private val f = java.io.File(
        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
        "xhs_login_debug.log",
    )
    fun w(msg: String) {
        try {
            f.appendText("${System.currentTimeMillis()} $msg\n")
        } catch (_: Throwable) {
        }
    }
}

@Composable
private fun XhsLoginOverlay(topInset: Dp = 0.dp, onClose: () -> Unit) {
    // 不透明底：覆盖层必须完全盖住下面的任务列表，否则两层内容叠印（实测踩坑）
    Column(
        Modifier
            .fillMaxSize()
            .background(colorScheme.background)
            .padding(top = topInset + 4.dp)
            .padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("登录小红书网页版", fontWeight = FontWeight.Medium, fontSize = 16.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, contentDescription = "关闭")
            }
        }
        // 登录态检测：探活 /user/self 必须走 IO（主线程 HTTP 抛 NetworkOnMainThread 被吞，审计 P1-5）
        // 三态：null=检测中
        var loggedIn by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<Boolean?>(null)
        }
        androidx.compose.runtime.LaunchedEffect(Unit) {
            val cookie = android.webkit.CookieManager.getInstance().getCookie("https://www.xiaohongshu.com")
            loggedIn = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                top.honorhook.ui.xhsbatch.XhsFeedClient.isWebLoggedInBlocking(cookie)
                    ?: (cookie?.contains("web_session") == true)
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            val sc = if (loggedIn == true) androidx.compose.ui.graphics.Color(0xFF35C77E) else colorScheme.onSurfaceVariantSummary
            Text(
                if (loggedIn == true) "已登录 · 复用 cookie" else "未登录",
                fontSize = 11.sp,
                color = sc,
                modifier = Modifier
                    .background(sc.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
            Text(
                if (loggedIn == true) "扫码可刷新登录态；完成后点右上关闭" else "扫码或手机号登录；完成后点右上关闭",
                fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        // AndroidView 常驻组合：登录期间 WebView 必须真实挂在视图树上才能渲染/执行 JS
        androidx.compose.runtime.SideEffect {
            XhsLoginLog.w("overlay composed")
        }
        androidx.compose.ui.viewinterop.AndroidView(
            modifier = Modifier.fillMaxSize().padding(top = 8.dp),
            factory = { ctx ->
                XhsLoginLog.w("factory: WebView created")
                android.webkit.WebView(ctx).apply {
                    // 软件渲染层：硬件合成下 H5 视频层 + 荣耀 VRR 变帧率会让 WebView 表面
                    // 在「完整渲染↔纯白」间闪烁（2026-11-10 连拍实帧）；登录页一次性场景可接受
                    setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // 桌面 UA（用户要求：PC 版登录页才有完整登录方式）。之前怀疑桌面 UA 导致
                    // 刷新循环，实锤是 http 明文跳转被禁（见下方 https 升级），不是 UA。
                    settings.userAgentString =
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                    settings.useWideViewPort = true
                    settings.loadWithOverviewMode = true
                    // 钉死字号：useWideViewPort 下 WebView 的 font boosting 会把文字放大数倍
                    //（真机实测整页巨型字），textZoom=100 关掉
                    settings.textZoom = 100
                    // 禁 Force Dark：荣耀 ROM 深色模式下 HwForceDarkManager 会对 WebView 反复
                    // 反色重绘（实测 isViewAllowedForceDark true↔false 翻转、整页闪烁）
                    if (android.os.Build.VERSION.SDK_INT >= 33) {
                        settings.setAlgorithmicDarkeningAllowed(false)
                    }
                    @Suppress("DEPRECATION")
                    if (android.os.Build.VERSION.SDK_INT >= 29) {
                        settings.forceDark = android.webkit.WebSettings.FORCE_DARK_OFF
                    }
                    setBackgroundColor(android.graphics.Color.WHITE)
                    // CookieManager 自动持久化到本 App 数据目录，无需手动同步
                    // 拦外部跳转：小红书页面会发 intent:// 想唤起自家 App，
                    // 不拦就会落到默认浏览器（实测弹 Edge）；http(s) 一律留在内置 WebView
                    webViewClient = object : android.webkit.WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                        ): Boolean {
                            val url = request?.url?.toString() ?: return false
                            XhsLoginLog.w("override: $url")
                            val scheme = request.url.scheme ?: return false
                            // 明文 http 一律升级 https 加载（targetSdk 28+ 禁 cleartext，
                            // 不升级会 ERR_CLEARTEXT_NOT_PERMITTED → 页面反复重试 = 闪烁）
                            if (scheme == "http") {
                                view?.loadUrl(url.replaceFirst("http://", "https://"))
                                return true
                            }
                            return scheme != "https"
                        }

                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            XhsLoginLog.w("finished: $url")
                        }

                        override fun doUpdateVisitedHistory(view: android.webkit.WebView?, url: String?, isReload: Boolean) {
                            XhsLoginLog.w("history: $url reload=$isReload")
                        }

                        override fun onReceivedError(view: android.webkit.WebView?, req: android.webkit.WebResourceRequest?, err: android.webkit.WebResourceError?) {
                            XhsLoginLog.w("error: ${req?.url} code=${err?.errorCode} desc=${err?.description}")
                        }
                    }
                    loadUrl("https://www.xiaohongshu.com")
                }
            },
        )
    }
}