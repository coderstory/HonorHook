package top.honorhook.ui.photos

import android.graphics.BitmapFactory
import android.os.Environment
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import top.honorhook.ui.component.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.io.File

/**
 * AI 图像工具页：AI 超清 / AI 扩图（简单档）。
 *
 * 能力来自 PhotosAiClient（经 PhotosAiHook 注入图库进程调荣耀 visionkit，
 * 见 docs/reverse/photos.md）——图库存活是硬前提，调用前先 ensureGalleryAlive。
 * 图片来源默认浏览小红书下载目录（Pictures/小红书，与 XhsBatchSave/XhsBatchController
 * 同一根）；输出落原图同目录，文件名加 -ai 后缀（重名追加序号，不覆盖既有结果）。
 * 超清倍率由厂商 SDK 定死不可选，只有通用/人像（photoMode）两档；
 * 扩图按目标比例换算成居中扩展的四边 scale（outpaintScales）。
 */
@Composable
fun AiImageScreen(bottomInnerPadding: Dp = 0.dp, topInset: Dp = 0.dp) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(0) } // 0=超清 1=扩图
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var selected by remember { mutableStateOf<File?>(null) }
    var photoMode by remember { mutableStateOf(false) } // 超清：false=通用 true=人像
    var ratioIdx by remember { mutableStateOf(0) } // 扩图：0=1:1 1=16:9 2=9:16 3=壁纸(9:19.5)
    var busy by remember { mutableStateOf(false) }
    var resultMsg by remember { mutableStateOf<String?>(null) }

    val root = remember {
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "小红书")
    }

    fun scan() {
        val exts = setOf("jpg", "jpeg", "png", "webp")
        files = root.walkTopDown()
            .filter { it.isFile && it.extension.lowercase() in exts }
            .sortedByDescending { it.lastModified() }
            .take(200)
            .toList()
    }

    // 进入页面扫一次；本地扫目录毫秒级，与 XhsBatchScreen 里 store.list() 同款取舍
    remember { scan().let { true } }

    fun relName(f: File): String = try { f.relativeTo(root).path } catch (_: Exception) { f.name }

    // 输出路径：原图同目录，<名>-ai.<ext>，重名顺延 -ai2/-ai3（不覆盖既有结果）
    fun outPath(src: File): File {
        var i = 1
        val stem = src.nameWithoutExtension
        val ext = src.extension.ifEmpty { "jpg" }
        while (true) {
            val suffix = if (i == 1) "-ai" else "-ai$i"
            val cand = File(src.parentFile, "$stem$suffix.$ext")
            if (!cand.exists()) return cand
            i++
        }
    }

    // remember 持有：普通 var 重组即重置，取消按钮拿到 null（审计 P1-3）
    val job = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun run() {
        val src = selected ?: return
        busy = true
        job.value = scope.launch {
            val r = try {
                // 图库冷启动后 hook 注册通道需 ~3s（PhotosAiClient.ensureGalleryAlive 内置余量）
                if (!PhotosAiClient.ensureGalleryAlive(context)) {
                    PhotosAiClient.AiResult(-998, null, "无法拉起荣耀图库")
                } else if (tab == 0) {
                    PhotosAiClient.enhance(context, src.absolutePath, outPath(src).absolutePath, photoMode = photoMode)
                } else {
                    val dim = withDimension(src)
                    if (dim == null) {
                        PhotosAiClient.AiResult(-997, null, "读取图片尺寸失败")
                    } else {
                        val (l, rr, t, b) = outpaintScales(dim.first, dim.second, RATIOS[ratioIdx].second)
                        PhotosAiClient.outpaint(
                            context, src.absolutePath, outPath(src).absolutePath,
                            top = t, bottom = b, left = l, right = rr,
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 用户取消：提示后原样上抛（PhotosAiClient 已透传取消）
                busy = false
                resultMsg = "已取消"
                Toast.makeText(context, resultMsg, Toast.LENGTH_SHORT).show()
                throw e
            } catch (e: Exception) {
                PhotosAiClient.AiResult(-996, null, "调用失败: ${e.message}")
            }
            busy = false
            resultMsg = if (r.ok) "完成：${r.out?.substringAfterLast('/')}" else "失败（code=${r.code}）：${r.msg}"
            Toast.makeText(context, resultMsg, Toast.LENGTH_LONG).show()
            scan()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = topInset + 4.dp)
            .padding(horizontal = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("AI 图像工具", fontWeight = FontWeight.Medium, fontSize = 17.sp, modifier = Modifier.weight(1f))
            IconButton(onClick = { scan() }) {
                Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
            }
        }

        // 两个能力 tab
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
            listOf("AI 超清", "AI 扩图").forEachIndexed { i, label ->
                val active = tab == i
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { tab = i },
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = if (active) FontWeight.Medium else FontWeight.Normal,
                        color = if (active) colorScheme.primary else colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(vertical = 8.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }

        // 参数区（简单档）
        Card(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                if (tab == 0) {
                    Text("增强模式", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        listOf("通用增强" to false, "人像增强" to true).forEach { (label, pm) ->
                            val active = photoMode == pm
                            Text(
                                label,
                                fontSize = 13.sp,
                                color = if (active) Color.White else colorScheme.onSurface,
                                modifier = Modifier
                                    .background(
                                        if (active) colorScheme.primary else colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { photoMode = pm }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("倍率由图库引擎决定，不可选", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                } else {
                    Text("目标比例", fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                        RATIOS.forEachIndexed { i, (label, _) ->
                            val active = ratioIdx == i
                            Text(
                                label,
                                fontSize = 13.sp,
                                color = if (active) Color.White else colorScheme.onSurface,
                                modifier = Modifier
                                    .background(
                                        if (active) colorScheme.primary else colorScheme.surfaceVariant,
                                        RoundedCornerShape(8.dp),
                                    )
                                    .clickable { ratioIdx = i }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("从原图居中扩展到目标比例", fontSize = 11.sp, color = colorScheme.onSurfaceVariantSummary)
                }
            }
        }

        // 选图列表
        Text(
            "选择图片（Pictures/小红书）",
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize().weight(1f),
            contentPadding = PaddingValues(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (files.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "目录为空\n先用「小红书批量下载」拉一些图，或点右上刷新",
                            fontSize = 13.sp, color = colorScheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                }
            }
            items(files, key = { it.absolutePath }) { f ->
                val isSel = selected == f
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selected = f },
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            relName(f),
                            fontSize = 13.sp,
                            fontWeight = if (isSel) FontWeight.Medium else FontWeight.Normal,
                            color = if (isSel) colorScheme.primary else colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Text(
                            "${f.length() / 1024} KB",
                            fontSize = 11.sp,
                            color = colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }

        TextButton(
            text = if (busy) "处理中…" else "开始处理",
            onClick = {
                when {
                    busy -> {}
                    selected == null -> Toast.makeText(context, "先选一张图片", Toast.LENGTH_SHORT).show()
                    else -> run()
                }
            },
            modifier = Modifier.fillMaxWidth().padding(bottom = bottomInnerPadding + 12.dp),
        )
    }

    // 处理中：等待可取消（客户端停止等待；广播已进图库侧无法中断，输出若完成后仅是多余文件）
    if (busy) {
        WindowDialog(
            show = true,
            title = "处理中",
            summary = "已提交图库引擎（云端管线，约 10~60s）\n${selected?.let { relName(it) } ?: ""}",
            onDismissRequest = {},
        ) {
            TextButton(
                text = "取消",
                onClick = { job.value?.cancel() },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    // 结果
    resultMsg?.let { msg ->
        WindowDialog(
            show = true,
            title = if (msg.startsWith("完成")) "完成" else "失败",
            summary = msg,
            onDismissRequest = { resultMsg = null },
        ) {
            TextButton(
                text = "知道了",
                onClick = { resultMsg = null },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 扩图目标比例表（label, w/h）。 */
private val RATIOS = listOf(
    "1:1" to 1.0f,
    "16:9" to 16f / 9f,
    "9:16" to 9f / 16f,
    // 手机壁纸竖屏（2026-11-11 用户要求）：主流全面屏 9:19.5；outpaintScales 单边 cap 1.0 ⇒ 最高扩 3x，足够
    "壁纸" to 9f / 19.5f,
)

/** 只解码边界拿宽高（不加载像素），失败返回 null。 */
private suspend fun withDimension(f: File): Pair<Int, Int>? =
    kotlinx.coroutines.withContext(Dispatchers.IO) {
        runCatching {
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) null else opts.outWidth to opts.outHeight
        }.getOrNull()
    }

/**
 * 计算扩图四边 scale（相对原图对应边的比例，order: left/right/top/bottom）。
 * 目标宽高比 [target]（w/h）；宽不够扩左右、高不够扩上下，居中对称。
 * 单边上限 1.0（比例差过大时截断，引擎侧也会拒绝极端值）。
 */
internal fun outpaintScales(width: Int, height: Int, target: Float): FloatArray {
    val orig = width.toFloat() / height.toFloat()
    val lr: Float
    val tb: Float
    if (target >= orig) {
        // 需要更宽：扩左右（相对原图宽）
        lr = ((target * height / width) - 1f) / 2f
        tb = 0f
    } else {
        // 需要更高：扩上下（相对原图高）
        lr = 0f
        tb = ((width / (target * height)) - 1f) / 2f
    }
    val cap = { v: Float -> v.coerceIn(0f, 1.0f) }
    return floatArrayOf(cap(lr), cap(lr), cap(tb), cap(tb))
}
