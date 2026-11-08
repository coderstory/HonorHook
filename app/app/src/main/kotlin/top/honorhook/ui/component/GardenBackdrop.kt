package top.honorhook.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * 「花园篱笆」背景皮肤（2026-11-08 用户要求：面板通体纯白太素，想要屏幕边缘一圈篱笆和鲜花）。
 *
 * 三件套，全部只落在页面外围：
 * 1. 底部一条木篱笆（尖头木条 + 两道横梁），贴上沿的暖色天光
 * 2. 篱笆脚下左右两簇花 + 从四角探进来的枝叶
 * 3. 一整圈极淡的边缘晕（vignette），把视线往中间收
 *
 * 只画装饰、不铺底色：页面底色仍由各页 Scaffold 让出的透明 + 根容器 `rootColor` 决定，
 * 卡片照旧不透明 ⇒ 文字对比度不受影响。
 *
 * 深浅色各一套色板；AMOLED 模式整体再压暗一档（纯黑上出现亮块很扎眼）。
 * 形状用固定种子生成，重组/旋屏不会换位置。
 */

/** 一朵花的归一化位置与外观（相对整屏宽高），画的时候再乘实际尺寸。 */
private data class Bloom(
    val fx: Float,
    val fy: Float,
    val radiusDp: Float,
    val colorIndex: Int,
    val spin: Float,
)

/** 一段藤蔓：二次贝塞尔，叶片沿参数 t 分布。 */
private data class Vine(
    val x0: Float,
    val y0: Float,
    val cx: Float,
    val cy: Float,
    val x1: Float,
    val y1: Float,
    val leafCount: Int,
    val leafSizeDp: Float,
)

private val LightPetals = listOf(
    Color(0xFFFF9FB2), // 粉
    Color(0xFFFFC48A), // 杏
    Color(0xFFC9A7FF), // 丁香紫
    Color(0xFF9BD9C7), // 薄荷
    Color(0xFFFFE3A3), // 奶黄
)

private val DarkPetals = listOf(
    Color(0xFFE39BB0),
    Color(0xFFE0BE8C),
    Color(0xFFAFA0E8),
    Color(0xFF7FC8B4),
    Color(0xFFE8D3A0),
)

@Composable
fun GardenBackdrop(
    modifier: Modifier = Modifier,
    dark: Boolean,
    amoled: Boolean = false,
) {
    // 固定种子 ⇒ 每次重组都长在同一处，不会「花乱跑」
    val blooms = remember { gardenBlooms() }
    val vines = remember { gardenVines() }

    val petalColors = if (dark) DarkPetals else LightPetals
    val dim = if (amoled) 0.72f else 1f

    Canvas(modifier = modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val oneDp = 1.dp.toPx()

        // ① 边缘晕：中心透明 → 四周极淡暖色，避免大面积纯白「空」
        val vignette = if (dark) Color(0xFF0B0D12) else Color(0xFFFFE7CE)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, vignette.copy(alpha = (if (dark) 0.55f else 0.30f) * dim)),
                center = Offset(w * 0.5f, h * 0.42f),
                radius = maxOf(w, h) * 0.78f,
            ),
            size = Size(w, h),
        )

        // ② 底部天光：给篱笆一个「有光从上方下来」的过渡
        val skyGlow = if (dark) Color(0xFF1C2A24) else Color(0xFFFFF0DC)
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(Color.Transparent, skyGlow.copy(alpha = (if (dark) 0.55f else 0.75f) * dim)),
                startY = h * 0.55f,
                endY = h,
            ),
            topLeft = Offset(0f, h * 0.55f),
            size = Size(w, h * 0.45f),
        )

        // ③ 篱笆
        drawFence(w, h, oneDp, dark, dim)

        // ④ 四角枝叶
        val leafColor = if (dark) Color(0xFF6FA189) else Color(0xFF8FC7A5)
        val stemColor = if (dark) Color(0xFF7FA593) else Color(0xFF9CCBAE)
        vines.forEach { v -> drawVine(v, w, h, oneDp, stemColor, leafColor, dim) }

        // ⑤ 花朵（压在篱笆上，花瓣先画外圈再点亮花心）
        blooms.forEach { b ->
            drawBloom(
                center = Offset(b.fx * w, b.fy * h),
                radius = b.radiusDp * oneDp,
                petal = petalColors[b.colorIndex % petalColors.size].copy(alpha = (if (dark) 0.62f else 0.72f) * dim),
                core = if (dark) Color(0xFFFFE3A3).copy(alpha = 0.72f) else Color(0xFFFFD166).copy(alpha = 0.8f),
                spin = b.spin,
            )
        }
    }
}

/** 尖头木条 + 两道横梁；木色按下沉距离略微加深，做出「一排」的纵深。 */
private fun DrawScope.drawFence(w: Float, h: Float, oneDp: Float, dark: Boolean, dim: Float) {
    val picketWidth = 15.dp.toPx()
    val gap = 20.dp.toPx()
    val height = 96.dp.toPx()
    val top = h - 30.dp.toPx() - height

    val wood = if (dark) Color(0xFF5A4433) else Color(0xFFC89A66)
    val woodShadow = if (dark) Color(0xFF3E2F24) else Color(0xFFAE7F4D)
    val woodAlpha = (if (dark) 0.42f else 0.34f) * dim

    // 篱笆脚下的薄雾，把木条「压」进地面，不留生硬的下边缘
    drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(Color.Transparent, woodShadow.copy(alpha = 0.10f * dim)),
            startY = h - 40.dp.toPx(),
            endY = h,
        ),
        topLeft = Offset(0f, h - 40.dp.toPx()),
        size = Size(w, 40.dp.toPx()),
    )

    var x = -gap
    while (x < w + gap) {
        val picket = Path().apply {
            moveTo(x, h - 22.dp.toPx())
            lineTo(x, top + 12.dp.toPx())
            lineTo(x + picketWidth / 2f, top)          // 尖头
            lineTo(x + picketWidth, top + 12.dp.toPx())
            lineTo(x + picketWidth, h - 22.dp.toPx())
            close()
        }
        drawPath(picket, color = wood.copy(alpha = woodAlpha))
        // 右缘一道深色，木条之间才有缝隙感（不靠描边就不像个院子）
        drawLine(
            color = woodShadow.copy(alpha = woodAlpha * 0.85f),
            start = Offset(x + picketWidth, top + 14.dp.toPx()),
            end = Offset(x + picketWidth, h - 22.dp.toPx()),
            strokeWidth = 1.dp.toPx(),
        )
        x += picketWidth + gap
    }

    // 两道横梁压在木条前面
    val railColor = wood.copy(alpha = woodAlpha * 1.15f)
    val railTop = top + 22.dp.toPx()
    val railBottom = top + 58.dp.toPx()
    listOf(railTop, railBottom).forEach { ry ->
        drawRoundRect(
            color = railColor,
            topLeft = Offset(0f, ry),
            size = Size(w, 9.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
        )
    }
}

/** 一段藤：茎用二次贝塞尔，叶片沿 t 均匀落两片一组，越靠末端越小。 */
private fun DrawScope.drawVine(
    v: Vine,
    w: Float,
    h: Float,
    oneDp: Float,
    stem: Color,
    leaf: Color,
    dim: Float,
) {
    val p0 = Offset(v.x0 * w, v.y0 * h)
    val pc = Offset(v.cx * w, v.cy * h)
    val p1 = Offset(v.x1 * w, v.y1 * h)

    val path = Path().apply {
        moveTo(p0.x, p0.y)
        quadraticTo(pc.x, pc.y, p1.x, p1.y)
    }
    drawPath(path, color = stem.copy(alpha = 0.42f * dim), style = Stroke(width = 1.6.dp.toPx()))

    // 二次贝塞尔上取点：B(t) = (1-t)²P0 + 2(1-t)t·Pc + t²P1
    for (i in 0 until v.leafCount) {
        val t = (i + 1f) / (v.leafCount + 1f)
        val mt = 1f - t
        val x = mt * mt * p0.x + 2f * mt * t * pc.x + t * t * p1.x
        val y = mt * mt * p0.y + 2f * mt * t * pc.y + t * t * p1.y
        val leafSize = v.leafSizeDp * oneDp * (1f - 0.35f * t)
        // 一节两片叶（左右各一片）：单边排下来像一串绿点，成对才像叶子
        listOf(-1f, 1f).forEach { side ->
            rotate(degrees = side * 40f, pivot = Offset(x, y)) {
                drawOval(
                    color = leaf.copy(alpha = (if (side < 0) 0.52f else 0.40f) * dim),
                    topLeft = Offset(x + side * leafSize * 0.22f, y - leafSize * 0.5f),
                    size = Size(leafSize * 1.7f, leafSize),
                )
            }
        }
    }
}

/** 五瓣花：先扑一圈很淡的光晕，再点花瓣，最后压花心 —— 光晕是「磨砂」观感的关键。 */
private fun DrawScope.drawBloom(
    center: Offset,
    radius: Float,
    petal: Color,
    core: Color,
    spin: Float,
) {
    drawCircle(color = petal.copy(alpha = petal.alpha * 0.28f), radius = radius * 1.9f, center = center)
    val petals = 5
    for (i in 0 until petals) {
        val a = Math.toRadians(spin + i * (360.0 / petals))
        val c = Offset(
            center.x + (cos(a) * radius * 0.62).toFloat(),
            center.y + (sin(a) * radius * 0.62).toFloat(),
        )
        drawCircle(color = petal, radius = radius * 0.52f, center = c)
    }
    drawCircle(color = core, radius = radius * 0.3f, center = center)
}

/**
 * 花簇落在**篱笆上沿到药丸上沿那道缝**里（约 0.83~0.88 高度）：
 * 更靠下会被悬浮底栏盖住，更靠上就飘在内容区中央、像贴纸。
 */
private fun gardenBlooms(): List<Bloom> {
    val rnd = Random(20261108)
    val out = mutableListOf<Bloom>()
    // 左簇 / 右簇：篱笆顶上探出来，高低错落
    listOf(0.02f..0.24f, 0.76f..0.98f).forEach { span ->
        repeat(9) {
            out += Bloom(
                fx = span.start + rnd.nextFloat() * (span.endInclusive - span.start),
                fy = 0.825f + rnd.nextFloat() * 0.045f,
                radiusDp = 7f + rnd.nextFloat() * 4f,
                colorIndex = rnd.nextInt(5),
                spin = rnd.nextFloat() * 72f,
            )
        }
    }
    // 篱笆沿线零星几朵（中间被药丸压着，缩小、收敛）
    repeat(5) {
        out += Bloom(
            fx = 0.30f + rnd.nextFloat() * 0.40f,
            fy = 0.845f + rnd.nextFloat() * 0.03f,
            radiusDp = 4.5f + rnd.nextFloat() * 2.5f,
            colorIndex = rnd.nextInt(5),
            spin = rnd.nextFloat() * 72f,
        )
    }
    // 最底两角（药丸左右外侧，露在篱笆脚下）
    repeat(3) {
        out += Bloom(
            fx = if (rnd.nextBoolean()) 0.005f + rnd.nextFloat() * 0.05f else 0.945f + rnd.nextFloat() * 0.05f,
            fy = 0.93f + rnd.nextFloat() * 0.045f,
            radiusDp = 6f + rnd.nextFloat() * 3f,
            colorIndex = rnd.nextInt(5),
            spin = rnd.nextFloat() * 72f,
        )
    }
    return out
}

/** 四条藤：底部两角往上爬，顶部两角垂下来 —— 合起来像「一圈」而不是只堆在底下。 */
private fun gardenVines(): List<Vine> = listOf(
    // 左下角：沿左缘爬到 45% 高度
    Vine(x0 = -0.01f, y0 = 0.94f, cx = 0.16f, cy = 0.83f, x1 = 0.02f, y1 = 0.55f, leafCount = 6, leafSizeDp = 13f),
    // 右下角
    Vine(x0 = 1.01f, y0 = 0.94f, cx = 0.84f, cy = 0.83f, x1 = 0.98f, y1 = 0.55f, leafCount = 6, leafSizeDp = 13f),
    // 左上角：垂下来
    Vine(x0 = -0.02f, y0 = 0.02f, cx = 0.10f, cy = 0.10f, x1 = 0.06f, y1 = 0.24f, leafCount = 5, leafSizeDp = 11f),
    // 右上角
    Vine(x0 = 1.02f, y0 = 0.02f, cx = 0.90f, cy = 0.10f, x1 = 0.94f, y1 = 0.24f, leafCount = 5, leafSizeDp = 11f),
)
