package top.honorhook.ui.component

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import top.honorhook.R

/**
 * 插画背景（2026-11-08 用户给的 1254×1254 蓝紫发角色图，`res/drawable-nodpi/artwork_backdrop.jpg`）。
 *
 * 为什么是「大模糊 + 三段遮罩」而不是直接把原图铺底：
 * - 原图是**近景头像**，细节密度极高。直接铺在卡片下面，卡片的磨砂半透明会把五官切成碎片，
 *   滚动时还会出现「脸在动」的干扰感。压到 26dp 模糊后它退化成一片同色系的色场，卡片浮在上面，
 *   谁是主体一目了然。
 * - 三段遮罩解决可读性：顶部要托住 TopAppBar 的字（深色主题下白字压在亮紫上会糊），
 *   底部要托住最后一张卡片与悬浮底栏（底栏是玻璃，底下必须是可预期的色块）。中段留透，
 *   让插画的气质还在。
 * - 遮罩色跟着明暗主题走：浅色用白雾（把图洗成淡紫水彩，深色字仍可读），深色用黑雾
 *   （保留饱和度，白字可读）。不在这里判断 Monet——背景色是插画自己的事，与 Monet 无关。
 * - `Modifier.blur` 在 API 31 以下不生效（RenderEffect 缺失）：那几台机器上退化成「原图 + 更浓遮罩」，
 *   仍然能看，不做二套资源。
 */
@Composable
fun ArtworkBackdrop(modifier: Modifier = Modifier) {
    val dark = isInDarkTheme()
    // 数值是「让人认出这是那张图」与「字不糊」之间的折中：浅色再厚一档就洗成纯色底，
    // 深色再薄一档卡片边缘会跟插画的高饱和紫糊在一起。
    val top = if (dark) Color(0xFF0B0818).copy(alpha = 0.52f) else Color.White.copy(alpha = 0.44f)
    val mid = if (dark) Color.Black.copy(alpha = 0.10f) else Color.White.copy(alpha = 0.10f)
    val bottom = if (dark) Color(0xFF120E22).copy(alpha = 0.90f) else Color(0xFFF7F5FF).copy(alpha = 0.88f)

    Box(modifier) {
        Image(
            painter = painterResource(R.drawable.artwork_backdrop),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize().blur(26.dp),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to top,
                        0.34f to mid,
                        0.72f to mid,
                        1f to bottom,
                    ),
                ),
        )
    }
}
