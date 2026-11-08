package top.honorhook.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import top.honorhook.R

/**
 * 插画背景（2026-11-08 用户给的 1254×2760 竖图，`res/drawable-nodpi/artwork_backdrop.jpg`）。
 *
 * 为什么是「原图直铺 + 三段遮罩」：
 * - 首版用的是 1254×1254 方图 + 26dp 模糊，两件事都错：方图裁到竖屏只剩中间一条，
 *   26dp 糊完认不出是谁（用户两次反馈「磨砂太强」「依旧太模糊」）。换成竖图后它本身就是
 *   手机壁纸比例，`ContentScale.Crop` 几乎不裁；模糊撤掉，画就是画。
 * - 不模糊就要靠遮罩管可读性：顶部压深托住 TopAppBar 的字，中段几乎全透（让画当主角），
 *   底部压结实托住最后一张卡与玻璃底栏。卡片自己那层磨砂（`Theme.kt` 的 surfaceContainer alpha）
 *   已经把文字和画隔开，不需要再对画动刀。
 * - 遮罩色跟明暗主题走：浅色用白雾（深色字在淡紫上仍可读），深色用黑雾（保留饱和度 + 白字）。
 */
@Composable
fun ArtworkBackdrop(modifier: Modifier = Modifier) {
    val dark = isInDarkTheme()
    val top = if (dark) Color(0xFF0B0818).copy(alpha = 0.46f) else Color.White.copy(alpha = 0.30f)
    val mid = if (dark) Color.Black.copy(alpha = 0.02f) else Color.White.copy(alpha = 0.02f)
    val bottom = if (dark) Color(0xFF120E22).copy(alpha = 0.88f) else Color(0xFFF7F5FF).copy(alpha = 0.86f)

    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.artwork_backdrop),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to top,
                        0.32f to mid,
                        0.70f to mid,
                        1f to bottom,
                    ),
                ),
        )
    }
}
