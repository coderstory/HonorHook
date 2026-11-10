package top.honorhook.ui.photos

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Environment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import android.util.LruCache

/**
 * AI 图像工具引擎（票 #24/#25/#26 的非 UI 面）：
 * 串行队列（引擎单次一张，客户端排队逐张提交）、失败保因可重试、可取消；
 * 来源三轨：小红书目录（默认）/ 任选目录（记忆，见 [AiSourceStore]）/ 相册 picker
 * （复制进 cache，输出落 Pictures/HonorHookAI）。
 * 纯逻辑（比例表/扩图换算/输出命名）在 AiImageCore.kt，JVM 测试钉那里。
 */

/** picker 选中的图先落到 cache（图库引擎只收真实文件路径），输出另落 HonorHookAI。 */
fun pickedImportDir(ctx: Context): File = File(ctx.cacheDir, "picked").apply { mkdirs() }

/** picker 来源的输出目录（picker 的 content URI 无写权限，回不了原位置）。 */
fun pickedOutDir(): File =
    File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "HonorHookAI")
        .apply { mkdirs() }

/** 任选目录的记忆（票 #26）。存模块同一 prefs 文件（honorhook_settings），独立 key，不进 AppSettings 状态面。 */
object AiSourceStore {
    private const val PREFS = "honorhook_settings"
    private const val KEY_CUSTOM_DIR = "aiimg_custom_dir"

    fun customDir(ctx: Context): File? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CUSTOM_DIR, null)?.let { p -> File(p).takeIf { it.isDirectory } }

    fun setCustomDir(ctx: Context, path: String?) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_CUSTOM_DIR, path).apply()
    }
}

/** 缩略图解码器（票 #25）：边界解码取 inSampleSize → IO 线程小图 → LruCache。 */
object ThumbLoader {
    // ponytail: 固定 48 张上限、256px 采样，覆盖 3 列网格与队列行；出现 OOM/卡顿再升档
    private const val MAX_DIM = 256

    private val cache = object : LruCache<String, ImageBitmap>(48) {}

    suspend fun load(f: File): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "${f.absolutePath}:${f.lastModified()}"
        cache.get(key)?.let { return@withContext it }
        runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= MAX_DIM || bounds.outHeight / (sample * 2) >= MAX_DIM) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeFile(f.absolutePath, opts)?.asImageBitmap()
        }.getOrNull()?.also { cache.put(key, it) }
    }
}

/**
 * 串行队列引擎。一个页面一个实例（remember），队列项状态用 Compose state 驱动 UI。
 * 取消：WAIT 全标「已取消」，RUN 项在 [runItem] 捕获 CancellationException 后同样标取消再上抛。
 */
class AiImageEngine(private val scope: CoroutineScope) {
    enum class State { WAIT, RUN, OK, ERR }

    inner class Item(
        val src: File,
        val out: File,
        val capLabel: String,
        val enhance: Boolean,
        val photoMode: Boolean,
        val ratioIdx: Int,
    ) {
        var state by mutableStateOf(State.WAIT)
        var msg by mutableStateOf("")
    }

    val items = mutableStateListOf<Item>()
    var running by mutableStateOf(false)
        private set
    private var job: Job? = null

    /** 整批入队并开跑。参数整批共用；[outDirOf] 决定输出落点（同目录 vs HonorHookAI）。 */
    fun submit(
        ctx: Context,
        files: List<File>,
        enhance: Boolean,
        photoMode: Boolean,
        ratioIdx: Int,
        outDirOf: (File) -> File,
    ) {
        if (files.isEmpty()) return
        val capLabel = if (enhance) {
            "超清·" + if (photoMode) "人像" else "通用"
        } else {
            "扩图·" + AI_RATIOS[ratioIdx].first
        }
        files.forEach { items.add(Item(it, aiOutPath(it, outDirOf(it)), capLabel, enhance, photoMode, ratioIdx)) }
        startIfNeeded(ctx)
    }

    fun cancel() {
        items.forEach { if (it.state == State.WAIT) { it.state = State.ERR; it.msg = "已取消" } }
        job?.cancel()
    }

    fun retry(ctx: Context, index: Int) {
        val it = items.getOrNull(index) ?: return
        if (it.state != State.ERR || it.msg == "已取消") return
        it.state = State.WAIT
        it.msg = ""
        startIfNeeded(ctx)
    }

    fun clearFinished() {
        check(!running) { "队列进行中不可清理" }
        items.removeAll { it.state == State.OK }
    }

    private fun startIfNeeded(ctx: Context) {
        if (running) return
        job = scope.launch {
            running = true
            try {
                while (true) {
                    val it = items.firstOrNull { it.state == State.WAIT } ?: break
                    runItem(ctx, it)
                }
            } finally {
                running = false
            }
        }
    }

    private suspend fun runItem(ctx: Context, it: Item) {
        it.state = State.RUN
        val r = try {
            // 图库冷启动后 hook 注册通道需 ~3s（PhotosAiClient.ensureGalleryAlive 内置余量）
            if (!PhotosAiClient.ensureGalleryAlive(ctx)) {
                PhotosAiClient.AiResult(-998, null, "无法拉起荣耀图库")
            } else if (it.enhance) {
                PhotosAiClient.enhance(ctx, it.src.absolutePath, it.out.absolutePath, photoMode = it.photoMode)
            } else {
                val dim = withDimension(it.src)
                if (dim == null) {
                    PhotosAiClient.AiResult(-997, null, "读取图片尺寸失败")
                } else {
                    val (l, rr, t, b) = outpaintScales(dim.first, dim.second, AI_RATIOS[it.ratioIdx].second)
                    PhotosAiClient.outpaint(
                        ctx, it.src.absolutePath, it.out.absolutePath,
                        top = t, bottom = b, left = l, right = rr,
                    )
                }
            }
        } catch (e: CancellationException) {
            it.state = State.ERR
            it.msg = "已取消"
            throw e
        } catch (e: Exception) {
            PhotosAiClient.AiResult(-996, null, "调用失败: ${e.message}")
        }
        it.state = if (r.ok) State.OK else State.ERR
        it.msg = if (r.ok) it.out.name else "code=${r.code} ${r.msg}"
    }
}

/** 只解码边界拿宽高（不加载像素），失败返回 null。 */
suspend fun withDimension(f: File): Pair<Int, Int>? = withContext(Dispatchers.IO) {
    runCatching {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) null else opts.outWidth to opts.outHeight
    }.getOrNull()
}
