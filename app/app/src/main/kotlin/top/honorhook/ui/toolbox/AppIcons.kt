package top.honorhook.ui.toolbox

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用图标内存缓存（2026-11-08 冻结列表需求）。
 *
 * 性能取舍（列表可能一次铺 200+ 行，图标最容易被写成「滚一下卡一下」）：
 * - **只进内存、不落盘**：图标由 PackageManager 随时可重建，落盘反而要处理包更新/卸载的失效，
 *   还得多一份清理逻辑；内存 LruCache 足够覆盖一次会话。
 * - **先缩到 96px 再驻留**：原图常是 192~512px，一张 512² 就是 1MB；列表行按 32~36dp 显示，
 *   96px 在 3x 密度下仍清晰。缓存上限 160 条 ≈ 5.9MB（96×96×4B×160），超出按 LRU 淘汰。
 * - **解码在 IO 线程，且只对可见行触发**：LazyColumn 只为可见项组合，`produceState` 在
 *   首次组合时才发起解码；缓存命中直接同步返回，不切线程、不闪占位。
 * - 并发重复解码不做全局串行化：同一包短时间内出现两次的概率极低，加锁反而给滚动加开销。
 */
object AppIconCache {

    private const val MAX_PX = 96
    private const val MAX_ENTRIES = 160

    private val cache = object : LruCache<String, ImageBitmap>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = 1
    }

    /** 同步peek：只在已缓存时返回，供首帧直接出图，避免占位闪烁 */
    fun peek(pkg: String): ImageBitmap? = cache.get(pkg)

    fun load(context: Context, pkg: String): ImageBitmap? {
        cache.get(pkg)?.let { return it }
        val drawable: Drawable = try {
            context.packageManager.getApplicationIcon(pkg)
        } catch (e: Exception) {
            return null
        }
        val bitmap = try {
            drawable.toBitmapScaled(MAX_PX)
        } catch (e: Exception) {
            null
        } ?: return null
        val image = bitmap.asImageBitmap()
        cache.put(pkg, image)
        return image
    }

    private fun Drawable.toBitmapScaled(maxPx: Int): Bitmap {
        val w = intrinsicWidth.takeIf { it > 0 } ?: maxPx
        val h = intrinsicHeight.takeIf { it > 0 } ?: maxPx
        val scale = minOf(maxPx.toFloat() / w, maxPx.toFloat() / h, 1f)
        val bw = maxOf(1, (w * scale).toInt())
        val bh = maxOf(1, (h * scale).toInt())
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val old = bounds
        setBounds(0, 0, bw, bh)
        draw(canvas)
        bounds = old
        return bmp
    }
}

/**
 * 应用图标：缓存命中同步出图；未命中时才进 IO 解码，期间显示同尺寸占位块
 * （固定占位尺寸 = 不触发列表项重排，避免滚动时抖动）。
 */
@Composable
fun AppIcon(pkg: String, size: Dp = 36.dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val initial = remember(pkg) { AppIconCache.peek(pkg) }
    val bitmap by produceState(initialValue = initial, key1 = pkg) {
        if (value == null) {
            value = withContext(Dispatchers.IO) { AppIconCache.load(context.applicationContext, pkg) }
        }
    }
    val shape = RoundedCornerShape(size * 0.28f)
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(shape)
                .background(MiuixTheme.colorScheme.surfaceVariant),
        )
    }
}
