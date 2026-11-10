package top.honorhook.ui.screen

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
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
    val controller = remember {
        XhsBatchController(
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
            ),
            taskStore = store,
            appContext = context,
        )
    }
    var records by remember { mutableStateOf(store.list()) }
    var showNewTask by remember { mutableStateOf(false) }
    var newUserId by remember { mutableStateOf("") }
    var confirmWithdraw by remember { mutableStateOf(false) }
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
            if (records.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "暂无任务\n点右上 + 新建：输入小红书用户 ID，抓取其公开笔记并串行下载图片与视频\n下载目录：Pictures/小红书/<用户ID>",
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
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("用户 ${r.userId}", fontWeight = FontWeight.Medium, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(stateLabel(r.state), fontSize = 12.sp, color = stateColor(r.state))
                            IconButton(onClick = { store.delete(r.taskId); records = store.list() }) {
                                Icon(Icons.Rounded.Delete, contentDescription = "删除记录")
                            }
                        }
                        Text(
                            "${r.imagesDone} 图 · ${r.videosDone} 视频 · %.1f MB".format(r.totalBytes / 1048576.0) +
                                " · 处理 ${r.notesProcessed}/${r.notesFound}" +
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
                        onQueryChange = { newUserId = it.filter(Char::isDigit) },
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = {},
                        label = "小红书用户 ID（纯数字）",
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
                            } else {
                                showNewTask = false
                                controller.start(id)
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
    val detail = openRecord
    if (detail != null) {
        val p = if (controller.isRunning() && progress.userId == detail.userId) progress else null
        WindowDialog(
            show = true,
            title = "任务详情 · 用户 ${detail.userId}",
            summary = null,
            onDismissRequest = { openRecord = null },
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stateLabel(p?.state ?: detail.state), fontSize = 14.sp, color = stateColor(p?.state ?: detail.state))
                    Text(
                        "文章 ${p?.notesFound ?: detail.notesFound} 篇 · 已处理 ${p?.notesProcessed ?: detail.notesProcessed}" +
                            " · 图片 ${p?.imagesDone ?: detail.imagesDone} 张 · 视频 ${p?.videosDone ?: detail.videosDone} 个" +
                            " · 累计 %.1f MB".format((p?.totalBytes ?: detail.totalBytes) / 1048576.0),
                        fontSize = 13.sp,
                    )
                    val action = p?.currentAction
                    if (!action.isNullOrBlank()) {
                        Text(action, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
                    }
                    if (p != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(
                                text = if (p.state == XhsBatchState.Paused) "恢复" else "暂停",
                                onClick = {
                                    if (p.state == XhsBatchState.Paused) controller.resumeRun() else controller.pause()
                                    records = store.list()
                                },
                            )
                            TextButton(text = "取消", onClick = { controller.cancel(); records = store.list() })
                            TextButton(text = "撤回", onClick = { confirmWithdraw = true })
                        }
                    } else {
                        TextButton(text = "撤回（删除已下载文件）", onClick = { confirmWithdraw = true })
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
}

private fun stateLabel(s: XhsBatchState): String = when (s) {
    XhsBatchState.Idle -> "待启动"
    XhsBatchState.Fetching -> "抓取中"
    XhsBatchState.Running -> "下载中"
    XhsBatchState.Paused -> "已暂停"
    XhsBatchState.Done -> "已完成"
    XhsBatchState.Cancelled -> "已取消"
    XhsBatchState.Withdrawn -> "已撤回"
    XhsBatchState.Interrupted -> "已中断"
    XhsBatchState.Failed -> "已失败"
}

private fun stateColor(s: XhsBatchState) = when (s) {
    XhsBatchState.Done -> androidx.compose.ui.graphics.Color(0xFF35C77E)
    XhsBatchState.Failed, XhsBatchState.Interrupted -> androidx.compose.ui.graphics.Color(0xFFF2685A)
    XhsBatchState.Running, XhsBatchState.Fetching -> androidx.compose.ui.graphics.Color(0xFF4FA8FF)
    else -> androidx.compose.ui.graphics.Color(0xFF888888)
}
