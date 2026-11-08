package top.honorhook.ui.screen

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.honorhook.ui.mfa.MfaAccount
import top.honorhook.ui.mfa.MfaStore
import top.honorhook.ui.mfa.Totp
import top.honorhook.ui.component.Card
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * MFA 验证器页（2026-11-03 用户需求：抄爱码仕首页功能——账号卡 + 实时验证码 + 剩余环 + 点按复制；
 * 自实现 RFC-6238，不引第三方验证器代码）。
 * 录入三通道：相机扫码（[MfaScanScreen]）/ 手动录入（secret 粘贴）/ 批量导入 otpauth 文本（迁移口）。
 */
@Composable
fun MfaScreen(bottomInnerPadding: Dp = 0.dp, onBack: () -> Unit, topInset: Dp = 0.dp) {
    // topInset 必须消费：外层 Scaffold 不自动让位，漏了内容整块画进顶栏底下（2026-11-03 真机空白实证）
    val context = LocalContext.current
    var accounts by remember { mutableStateOf(MfaStore.list(context)) }
    var view by rememberSaveable { mutableStateOf(MfaView.LIST) }
    var showManual by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<MfaAccount?>(null) }
    var renaming by remember { mutableStateOf<MfaAccount?>(null) }
    fun refresh() { accounts = MfaStore.list(context) }

    // 导入文件选择器（2026-11-03 备份/导入）：系统 SAF 选 JSON
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) runCatching {
            val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
            val r = text?.let { MfaStore.importJson(context, it) } ?: Pair(0, 0)
            val msg = when {
                r.second == 0 -> "文件里没有有效账号"
                r.first == 0 -> "共 ${r.second} 个账号，全部已存在（未重复导入）"
                else -> "导入 ${r.first} 个新账号（共 ${r.second} 个）"
            }
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            if (r.first > 0) refresh()
        }.onFailure { Toast.makeText(context, "读取失败: ${it.message}", Toast.LENGTH_SHORT).show() }
    }

    // 全局 1s 心跳：所有卡共用一次 now() 取样，避免每卡独立 ticker
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            tick = System.currentTimeMillis()
            kotlinx.coroutines.delay(1000)
        }
    }
    BackHandler(enabled = view != MfaView.LIST) { view = MfaView.LIST }

    when (view) {
        MfaView.SCAN -> MfaScanScreen(
            bottomInnerPadding = bottomInnerPadding,
            topInset = topInset,
            onBack = { view = MfaView.LIST },
            onResult = { uris ->
                view = MfaView.LIST
                val n = MfaStore.addAll(context, uris)
                Toast.makeText(context, if (n > 0) "已导入 $n 个账号" else "未识别到有效 otpauth 码", Toast.LENGTH_SHORT).show()
                refresh()
            },
        )
        MfaView.LIST -> LazyColumn(Modifier.fillMaxSize().padding(top = topInset, bottom = bottomInnerPadding)) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    IconButton(onClick = {
                        val name = MfaStore.backup(context)
                        Toast.makeText(context,
                            if (name != null) "已备份到 Download/$name" else "备份失败",
                            Toast.LENGTH_LONG).show()
                    }) {
                        Icon(Icons.Rounded.Backup, null, tint = colorScheme.onSurface)
                    }
                    IconButton(onClick = {
                        importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                    }) {
                        Icon(Icons.Rounded.FolderOpen, null, tint = colorScheme.onSurface)
                    }
                    IconButton(onClick = { showImport = true }) {
                        Icon(Icons.Rounded.ContentPaste, null, tint = colorScheme.onSurface)
                    }
                    IconButton(onClick = { showManual = true }) {
                        Icon(Icons.Rounded.Add, null, tint = colorScheme.onSurface)
                    }
                    IconButton(onClick = { view = MfaView.SCAN }) {
                        Icon(Icons.Rounded.QrCodeScanner, null, tint = colorScheme.onSurface)
                    }
                }
            }
            if (accounts.isEmpty()) {
                item {
                    Card(Modifier.padding(16.dp)) {
                        Text("还没有账号", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "右上角三入口：扫码 / 手动录入 / 批量导入 otpauth 文本（迁移口）",
                            fontSize = 13.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
            items(accounts, key = { it.uri }) { acc ->
                AccountCard(acc, tick, MfaStore.aliasOf(context, acc.uri), onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("otp", Totp.now(acc, tick)))
                    Toast.makeText(context, "已复制 ${Totp.now(acc, tick)}", Toast.LENGTH_SHORT).show()
                }, onDelete = { deleting = acc },
                    onRename = { renaming = acc })
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    // —— 手动录入对话框 ——
    if (showManual) {
        var issuer by remember { mutableStateOf("") }
        var label by remember { mutableStateOf("") }
        var secret by remember { mutableStateOf("") }
        WindowDialog(
            show = true,
            title = "手动录入",
            summary = null,
            onDismissRequest = { showManual = false },
        ) {
                Column {
                    InputField(query = issuer, onQueryChange = { issuer = it }, onSearch = {}, expanded = false, onExpandedChange = {}, label = "服务（Issuer）", modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    InputField(query = label, onQueryChange = { label = it }, onSearch = {}, expanded = false, onExpandedChange = {}, label = "账号（可空）", modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    InputField(query = secret, onQueryChange = { secret = it }, onSearch = {}, expanded = false, onExpandedChange = {}, label = "Base32 密钥", modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    TextButton(text = "保存", onClick = {
                        val path = if (label.isBlank()) issuer else "$issuer:$label"
                        val uri = "otpauth://totp/$path?secret=${secret.filterNot { it.isWhitespace() }}&issuer=$issuer"
                        val n = MfaStore.addAll(context, listOf(uri))
                        Toast.makeText(context, if (n > 0) "已添加" else "密钥无效（Base32 解码失败）", Toast.LENGTH_SHORT).show()
                        if (n > 0) { showManual = false; refresh() }
                    }, modifier = Modifier.fillMaxWidth())
                }
        }
    }

    // —— 批量导入对话框（迁移口：粘贴多行 otpauth:// 文本）——
    if (showImport) {
        var text by remember { mutableStateOf("") }
        WindowDialog(
            show = true,
            title = "批量导入",
            summary = null,
            onDismissRequest = { showImport = false },
        ) {
                Column {
                    InputField(query = text, onQueryChange = { text = it }, onSearch = {}, expanded = false, onExpandedChange = {}, label = "每行一条 otpauth:// URI", modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    TextButton(text = "导入", onClick = {
                        val uris = text.lines().map { it.trim() }.filter { it.startsWith("otpauth://") }
                        val n = MfaStore.addAll(context, uris)
                        Toast.makeText(context, "导入 $n 条（共 ${uris.size} 行有效 URI）", Toast.LENGTH_SHORT).show()
                        if (n > 0) { showImport = false; refresh() }
                    }, modifier = Modifier.fillMaxWidth())
                }
        }
    }

    // —— 删除确认 ——
    if (deleting != null) {
        val acc = deleting!!
        WindowDialog(
            show = true,
            title = "删除账号",
            summary = null,
            onDismissRequest = { deleting = null },
        ) {
                Column {
                    Text("${acc.issuer} / ${acc.label}", fontSize = 14.sp)
                    Spacer(Modifier.height(12.dp))
                    TextButton(text = "删除", onClick = { MfaStore.remove(context, acc.uri); deleting = null; refresh() },
                        modifier = Modifier.fillMaxWidth())
                }
        }
    }

    // —— 改别名对话框（2026-11-03 用户需求）——
    if (renaming != null) {
        val acc = renaming!!
        var name by remember(acc.uri) { mutableStateOf(MfaStore.aliasOf(context, acc.uri) ?: acc.issuer) }
        WindowDialog(
            show = true,
            title = "改别名",
            summary = null,
            onDismissRequest = { renaming = null },
        ) {
            Column {
                InputField(query = name, onQueryChange = { name = it }, onSearch = {}, expanded = false, onExpandedChange = {}, label = "显示名", modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                TextButton(text = "保存", onClick = {
                    MfaStore.setAlias(context, acc.uri, name)
                    renaming = null
                    refresh()
                }, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

private enum class MfaView { LIST, SCAN }

/** 账号卡（爱码仕首页同款信息密度）：issuer/label + 大字验证码 + 剩余秒环；点按复制，尾钮删除。 */
@Composable
private fun AccountCard(acc: MfaAccount, tick: Long, alias: String?, onClick: () -> Unit, onDelete: () -> Unit, onRename: () -> Unit) {
    val code = Totp.now(acc, tick)
    val remain = Totp.remainingSeconds(acc, tick)
    val frac = remain.toFloat() / acc.period
    Card(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).clickable { onClick() }) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(alias ?: acc.issuer, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(acc.label.ifEmpty { acc.issuer }, fontSize = 12.sp, color = colorScheme.onSurfaceVariantSummary)
            }
            // 剩余秒进度环
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp)) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = Stroke(width = 6f, cap = StrokeCap.Round)
                    drawArc(Color.Gray.copy(alpha = 0.25f), -90f, 360f, false, style = stroke)
                    drawArc(
                        if (remain <= 5) Color(0xFFFF2E4D) else Color(0xFF4CAF50),
                        -90f, 360f * frac, false, style = stroke,
                    )
                }
                Text("$remain", fontSize = 11.sp)
            }
            Spacer(Modifier.width(10.dp))
            Text(
                code.chunked(3).joinToString(" "),
                fontSize = 24.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onRename) {
                Icon(Icons.Rounded.Edit, null, tint = colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, null, tint = colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(20.dp))
            }
        }
    }
}
