package top.honorhook.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import top.honorhook.R

/**
 * 全幅插画的「轻微虚化」：1dp ≈ 只吃掉像素锯齿，几乎看不出糊（26 / 9 / 2dp 都被嫌强，折中到底）。
 */
private val ArtBlur = 1.dp

/**
 * 顶部磨砂带的模糊半径：仍是全幅的数倍（看得出「这块玻璃更厚」），
 * 但 16dp 会让整条 banner 变成色块、看不出底下还有画，收回到 6dp。
 */
private val BandBlur = 6.dp

/**
 * 插画背景（2026-11-08 用户给的 1254×2760 竖图，`res/drawable-nodpi/artwork_backdrop.jpg`）。
 *
 * 构成三层，自上而下叠：
 * 1. **全幅插画**：竖图本身就是手机壁纸比例，`ContentScale.Crop` 几乎不裁；轻微 2dp 虚化。
 * 2. **三段遮罩**：顶部压深托住标题，中段几乎全透（画是主角），底部压结实托住最后一张卡
 *    与玻璃底栏。浅色用白雾（深色字在淡紫上仍可读），深色用黑雾（保留饱和度 + 白字）。
 * 3. **顶部磨砂带**（2026-11-08 用户要求「banner 加透明磨砂、这块更强」）：把同一张插画
 *    再画一遍、裁到顶部带内、加 16dp 强模糊，压一层半透明白/黑，底缘渐变淡出。
 *    为什么重画而不去采样下层：Compose 的 `Modifier.blur` 只能糊**自己的内容**，
 *    要糊「背后的画面」得走 miuix 的 layerBackdrop 采样链（那是底栏药丸的机制，重且要改渲染层）；
 *    这里背景本来就只是一张静态图，重画一份的代价远小于引入 backdrop 采样。
 *    带子画在页面内容**之前**，所以各屏 TopAppBar 必须让出底色（`color = Color.Transparent`），
 *    否则 miuix 默认的 `surface` 不透明底会把这块磨砂整个盖掉。
 */
@Composable
fun ArtworkBackdrop(modifier: Modifier = Modifier) {
    val dark = isInDarkTheme()
    val top = if (dark) Color(0xFF0B0818).copy(alpha = 0.46f) else Color.White.copy(alpha = 0.30f)
    val mid = if (dark) Color.Black.copy(alpha = 0.02f) else Color.White.copy(alpha = 0.02f)
    val bottom = if (dark) Color(0xFF120E22).copy(alpha = 0.88f) else Color(0xFFF7F5FF).copy(alpha = 0.86f)
    val bandTint = if (dark) Color(0xFF0B0818).copy(alpha = 0.38f) else Color.White.copy(alpha = 0.38f)

    BoxWithConstraints(modifier) {
        val screenW = constraints.maxWidth
        val screenH = constraints.maxHeight
        Image(
            painter = painterResource(R.drawable.artwork_backdrop),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.fillMaxSize().blur(ArtBlur),
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

        // 顶部磨砂带：状态栏 + 展开态标题区的高度，底缘 20% 渐变淡出，避免一条硬边切断画面
        val bandHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 104.dp
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bandHeight)
                .clipToBounds(),
        ) {
            val art = ImageBitmap.imageResource(R.drawable.artwork_backdrop)
            Canvas(modifier = Modifier.fillMaxSize().blur(BandBlur)) {
                drawImage(
                    image = art,
                    dstSize = IntSize(screenW, screenH),
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            0f to bandTint,
                            0.70f to bandTint,
                            1f to Color.Transparent,
                        ),
                    ),
            )
        }
    }
}
