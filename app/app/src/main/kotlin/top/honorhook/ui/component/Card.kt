package top.honorhook.ui.component

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

/**
 * 带「淡绿包边」的卡片（2026-11-08 用户要求：「给所有的卡片加一个淡绿色的包边」）。
 *
 * 为什么做成同名同形的包装，而不是给每个 `Card(...)` 传 colors/modifier：
 * - miuix `Card` 没有 border 参数；全工程 40 多处 `Card(` 调用，逐个改必然漏，
 *   把 import 从 `top.yukonga.miuix.kmp.basic.Card` 换成本文件即可全覆盖。
 * - 包边必须画在卡片背景**之上**：`Modifier.drawWithCache{ onDrawWithContent { drawContent(); stroke } }`
 *   挂在传进 Card 的 modifier 上，链里它排在 miuix 内部 `.squircleSurface()` 之前，
 *   所以 `drawContent()` 先把背景+内容画完、闭包再描边 —— 换成 `border`/`onDrawBehind`
 *   都会被随后绘制的不透明/半透明背景盖住。
 * - 必须用 squircle 路径描边（`addSquircleRect`）而不是 `drawRoundRect`：miuix 卡片是
 *   超椭圆（extension 1.1），圆角矩形描边在四角会露出一圈不贴合的缝。
 *
 * 参数兜底到 `CardDefaults`，miuix 换默认值不用跟着改（0.9.4 里这两个属性名是
 * `CardDefaults.CornerRadius` / `CardDefaults.InsideMargin`，首字母大写）。
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    content: @Composable ColumnScope.() -> Unit,
) {
    MiuixCard(
        modifier = modifier.cardRim(cornerRadius),
        cornerRadius = cornerRadius,
        insideMargin = insideMargin,
        colors = colors,
        content = content,
    )
}

/**
 * 可点击卡片（目前只有 `FeaturesScreen` 的功能网格在用）。
 * 默认值照 0.9.4 源码：pressFeedbackType = None、showIndication = false、onClick/onLongPress 可空。
 */
@Composable
fun Card(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = CardDefaults.CornerRadius,
    insideMargin: PaddingValues = CardDefaults.InsideMargin,
    colors: CardColors = CardDefaults.defaultColors(),
    pressFeedbackType: PressFeedbackType = PressFeedbackType.None,
    showIndication: Boolean = false,
    holdDownState: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongPress: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    MiuixCard(
        modifier = modifier.cardRim(cornerRadius),
        cornerRadius = cornerRadius,
        insideMargin = insideMargin,
        colors = colors,
        pressFeedbackType = pressFeedbackType,
        showIndication = showIndication,
        holdDownState = holdDownState,
        onClick = onClick,
        onLongPress = onLongPress,
        content = content,
    )
}

/** 包边色：浅色用偏灰的嫩绿（纯亮绿压在白卡上会显廉价），深色提亮一档。 */
private val RimLight = Color(0xFF8ED2A8).copy(alpha = 0.62f)
private val RimDark = Color(0xFF7FC79B).copy(alpha = 0.45f)

private val RimWidth = 1.dp

/**
 * 沿超椭圆轮廓描一圈淡绿边。路径按尺寸缓存（`drawWithCache`），尺寸不变不重建；
 * 线宽整条压在描边中线，所以内缩半个线宽对齐卡片填充。
 */
@Composable
private fun Modifier.cardRim(cornerRadius: Dp): Modifier {
    val rim = if (isInDarkTheme()) RimDark else RimLight
    val squircle = isSquircleEnabled()
    return this.drawWithCache {
        val widthPx = RimWidth.toPx()
        val half = widthPx / 2f
        val innerW = size.width - widthPx
        val innerH = size.height - widthPx
        val path = Path()
        val drawable = widthPx > 0f && innerW > 0f && innerH > 0f
        if (drawable) {
            path.addSquircleRect(
                width = innerW,
                height = innerH,
                cornerRadius = (cornerRadius.toPx() - half).coerceAtLeast(0f),
                extension = SquircleDefaults.Extension,
                squircleEnabled = squircle,
            )
        }
        onDrawWithContent {
            drawContent()
            if (drawable) {
                translate(half, half) {
                    drawPath(path = path, color = rim, style = Stroke(width = widthPx))
                }
            }
        }
    }
}
