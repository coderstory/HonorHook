package top.honorhook.ui.photos

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.honorhook.ui.component.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

/**
 * AI 图像工具页（2026-11-11 重构，票 #27）：图优先 = 原型变体 B（网格/队列分屏）。
 * 能力仍只有超清/扩图两档，走 PhotosAiClient（图库注入通道，见 docs/reverse/photos.md）。
 * 交互：3 列缩略图网格，点图 = 预览大图，角标点击 = 勾选；下 40% 实时队列
 * （排队/处理中/完成/失败 + 失败重试）；参数 chips + 执行按钮常驻底部。
 * 设计决策与原型 primary source 见 docs/spec-ai-image-refactor.md。
 */
@Composable
fun AiImageScreen(bottomInnerPadding: Dp = 0.dp, topInset: Dp = 0.dp, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { AiImageEngine(scope) }

    var cap by remember { mutableStateOf(0) }            // 0=超清 1=扩图
    var photoMode by remember { mutableStateOf(false) }  // 超清：false=通用 true=人像
    var ratioIdx by remember { mutableStateOf(0) }
    var useCustomDir by remember { mutableStateOf(false) }
    var customDir by remember { mutableStateOf<File?>(null) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var picked by remember { mutableStateOf<List<File>>(emptyList()) } // picker 导入的 cache 副本
    var sel by remember { mutableStateOf<Set<String>>(emptySet()) }
    var preview by remember { mutableStateOf<File?>(null) }
    var showSource by remember { mutableStateOf(false) }

    val xhsRoot = remember {
        File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_PICTURES), "小红书")
    }
    val activeDir: File? = if (useCustomDir) customDir else xhsRoot

    fun scan() {
        val root = activeDir ?: return
        val exts = setOf("jpg", "jpeg", "png", "webp")
        files = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in exts }
            .sortedByDescending { it.lastModified() }
            .take(200)
            .toList()
    }
    remember {
        customDir = AiSourceStore.customDir(context)
        if (customDir != null) useCustomDir = true
        scan().let { true }
    }

    // 相册 picker（零权限）：选中 → 复制进 cache → 计入选区，输出落 HonorHookAI
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val saved: File? = withContext(Dispatchers.IO) {
                val f = File(pickedImportDir(context), "pick_${System.currentTimeMillis()}.jpg")
                val ok = runCatching {
                    context.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
                    f.length() > 0L
                }.getOrDefault(false)
                if (ok) f else run { f.delete(); null }
            }
            if (saved == null) {
                Toast.makeText(context, "导入失败", Toast.LENGTH_SHORT).show()
            } else {
                picked = picked + saved
                sel = sel + saved.absolutePath
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInset + 4.dp)
            .padding(horizontal = 12.dp),
    ) {
        // 头部：返回 + 标题 + 来源切换 + 刷新（顶栏已对本页豁免，防标题重复）
        Row(verticalAlignment = Alignment.CenterVertically) {
            top.honorhook.ui.screen.MiuixBackButton(onClick = onBack)
            Text("AI 图像工具", fontWeight = FontWeight.Medium, fontSize = 17.sp, modifier = Modifier.weight(1f))
            Text(
                if (useCustomDir) "📁 自定义 ▾" else "📁 小红书 ▾",
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { showSource = true }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            IconButton(onClick = { scan() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
        }

        // 能力 chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            listOf("AI 超清", "AI 扩图").forEachIndexed { i, label ->
                val active = cap == i
                Text(
                    label,
                    fontSize = 13.sp,
                    color = if (active) Color.White else colorScheme.onSurface,
                    modifier = Modifier
                        .background(if (active) colorScheme.primary else colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
                        .clickable { cap = i }
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                )
            }
        }

        // ---- 上：缩略图网格（~58%）----
        val gridItems = remember(files, picked) { files + picked }
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            modifier = Modifier.fillMaxWidth().weight(if (engine.items.isEmpty()) 1f else 0.42f).padding(top = 8.dp),
            contentPadding = PaddingValues(bottom = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (gridItems.isEmpty()) {
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(3) }) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "目录为空\n先下载/挑选图片，或点右上刷新 / 「来源」切换目录",
                            fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        )
                    }
                }
            }
            gridItems(gridItems, key = { it.absolutePath }) { f ->
                val isSel = f.absolutePath in sel
                Box(
                    modifier = Modifier
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { preview = f },
                ) {
                    Thumb(f, Modifier.fillMaxSize())
                    Text(
                        "${f.length() / 1024} KB",
                        fontSize = 9.sp, color = Color.White.copy(alpha = 0.8f),
                        modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
                    )
                    // 角标 = 勾选（点缩略图本体是预览）
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(5.dp)
                            .size(18.dp)
                            .background(
                                if (isSel) colorScheme.primary else Color.Black.copy(alpha = 0.4f),
                                RoundedCornerShape(50),
                            )
                            .clickable {
                                sel = if (isSel) sel - f.absolutePath else sel + f.absolutePath
                            },
                    )
                }
            }
        }

        // ---- 下：队列面板（点「开始处理」产生条目后才出现，之前网格占满整屏——2026-11-11 用户 UX 纠正）----
        if (engine.items.isNotEmpty()) Column(Modifier.fillMaxWidth().weight(0.58f)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                Text(
                    if (engine.running) "处理中（${engine.items.count { it.state == AiImageEngine.State.RUN || it.state == AiImageEngine.State.WAIT }} 项待完）"
                    else "处理队列（${engine.items.size}）",
                    fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary,
                    modifier = Modifier.weight(1f),
                )
                if (engine.running) {
                    Text("取消", fontSize = 13.sp, color = colorScheme.primary,
                        modifier = Modifier.clickable { engine.cancel() }.padding(horizontal = 8.dp))
                } else if (engine.items.any { it.state == AiImageEngine.State.OK }) {
                    Text("清已完成", fontSize = 13.sp, color = colorScheme.primary,
                        modifier = Modifier.clickable { engine.clearFinished() }.padding(horizontal = 8.dp))
                }
            }
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(engine.items) { idx, it ->
                    QueueRow(it) { engine.retry(context, idx) }
                }
            }

            // 参数 chips + 执行
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                if (cap == 0) {
                    listOf("通用" to false, "人像" to true).forEach { (label, pm) ->
                        Chip(label + "增强", photoMode == pm) { photoMode = pm }
                    }
                    Text("倍率引擎定死", fontSize = 10.sp, color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.align(Alignment.CenterVertically))
                } else {
                    AI_RATIOS.forEachIndexed { i, (label, _) ->
                        Chip(label, ratioIdx == i) { ratioIdx = i }
                    }
                    Text("居中扩展", fontSize = 10.sp, color = colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.align(Alignment.CenterVertically))
                }
            }
            TextButton(
                text = if (engine.running) "处理中…" else "开始处理（已选 ${sel.size} 张）",
                onClick = {
                    when {
                        engine.running -> {}
                        sel.isEmpty() -> Toast.makeText(context, "先勾选图片", Toast.LENGTH_SHORT).show()
                        else -> engine.submit(
                            context,
                            (gridItems).filter { it.absolutePath in sel },
                            enhance = cap == 0,
                            photoMode = photoMode,
                            ratioIdx = ratioIdx,
                            outDirOf = { src -> if (src in pickedSet(picked)) pickedOutDir() else src.parentFile ?: src.absoluteFile.parentFile },
                        ).also { sel = emptySet() }
                    }
                },
                enabled = !engine.running && sel.isNotEmpty(),
                modifier = Modifier.fillMaxWidth().padding(bottom = bottomInnerPadding + 12.dp),
            )
        }
    }

    // 来源切换对话框
    if (showSource) {
        SourceDialog(
            currentCustom = customDir?.absolutePath ?: "",
            onPick = { path ->
                showSource = false
                if (path == null) {
                    useCustomDir = false
                } else {
                    val f = File(path)
                    if (f.isDirectory) {
                        useCustomDir = true
                        customDir = f
                        AiSourceStore.setCustomDir(context, f.absolutePath)
                    } else {
                        Toast.makeText(context, "目录不存在：$path", Toast.LENGTH_SHORT).show()
                        return@SourceDialog
                    }
                }
                scan()
            },
            onPickAlbum = { showSource = false; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onDismiss = { showSource = false },
        )
    }

    // 预览大图
    preview?.let { f ->
        WindowDialog(
            show = true,
            title = f.name,
            summary = "${f.length() / 1024} KB · 点「关闭」返回",
            onDismissRequest = { preview = null },
        ) {
            Thumb(f, Modifier.fillMaxWidth(), big = true)
            TextButton(text = "关闭", onClick = { preview = null }, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }

    // 队列跑完提示（含取消）
    var wasRunning by remember { mutableStateOf(false) }
    LaunchedEffect(engine.running) {
        if (wasRunning && !engine.running) Toast.makeText(context, "队列处理完毕", Toast.LENGTH_SHORT).show()
        wasRunning = engine.running
    }
}

/** picker 导入的文件集合（用于输出落点判断）。 */
private fun pickedSet(picked: List<File>): Set<File> = picked.toSet()

@Composable
private fun Thumb(f: File, modifier: Modifier = Modifier, big: Boolean = false) {
    var img by remember(f.absolutePath, f.lastModified()) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(f.absolutePath, f.lastModified(), big) {
        img = ThumbLoader.load(f)
    }
    val v = img
    if (v != null) {
        // 缩略图用 Crop（网格定比例裁切合理）；预览大图必须 Fit——Crop 会裁掉画面、比例失真（用户实测「UI 比例不对」）
        Image(v, contentDescription = f.name, contentScale = if (big) ContentScale.Fit else ContentScale.Crop, modifier = modifier)
    } else {
        Box(modifier.background(colorScheme.surfaceVariant))
    }
}

@Composable
private fun Chip(label: String, active: Boolean, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 13.sp,
        color = if (active) Color.White else colorScheme.onSurface,
        modifier = Modifier
            .background(if (active) colorScheme.primary else colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

@Composable
private fun QueueRow(it: AiImageEngine.Item, onRetry: () -> Unit) {
    val (label, color) = when (it.state) {
        AiImageEngine.State.WAIT -> "排队" to colorScheme.onSurfaceVariantSummary
        AiImageEngine.State.RUN -> "处理中…" to colorScheme.primary
        AiImageEngine.State.OK -> "完成" to Color(0xFF34A853)
        AiImageEngine.State.ERR -> "失败" to Color(0xFFE5484D)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
        Thumb(it.src, Modifier.size(34.dp).clip(RoundedCornerShape(7.dp)))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(it.src.name, fontSize = 12.sp, maxLines = 1)
            Text(
                "${it.capLabel} → " + if (it.state == AiImageEngine.State.OK) it.msg else (it.msg.ifEmpty { it.out.name }),
                fontSize = 10.sp, color = colorScheme.onSurfaceVariantSummary, maxLines = 1,
            )
        }
        Text(label, fontSize = 11.sp, color = color, modifier = Modifier.padding(start = 6.dp))
        if (it.state == AiImageEngine.State.ERR && it.msg != "已取消") {
            Text("重试", fontSize = 11.sp, color = colorScheme.primary,
                modifier = Modifier.clickable(onClick = onRetry).padding(start = 8.dp))
        }
    }
}

/** 来源切换：小红书默认 / 自定义目录（路径输入，记忆）/ 相册挑一张。 */
@Composable
private fun SourceDialog(
    currentCustom: String,
    onPick: (String?) -> Unit,
    onPickAlbum: () -> Unit,
    onDismiss: () -> Unit,
) {
    var input by remember { mutableStateOf(currentCustom) }
    WindowDialog(show = true, title = "图片来源", summary = "默认扫 Pictures/小红书；自定义目录按绝对路径", onDismissRequest = onDismiss) {
        androidx.compose.material3.OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = colorScheme.onSurface),
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(text = "用自定义目录", onClick = { onPick(input.trim().ifEmpty { null }) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        TextButton(text = "回到小红书目录（默认）", onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth())
        TextButton(text = "从相册挑一张", onClick = onPickAlbum, modifier = Modifier.fillMaxWidth())
        TextButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
    }
}
