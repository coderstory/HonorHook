package top.honorhook.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.CardColors
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.isSquircleEnabled
import top.honorhook.ui.AppSettings
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.basic.Card as MiuixCard

/**
 * 带主题色包边的卡片（2026-11-08 用户要求所有卡片加包边；原先固定淡绿，插画主题上线后
 * 改为跟随主色——见下方 `cardRim` 里 `AppSettings.artworkBackdrop` 分支）。
 *
 * 为什么做成同名同形的包装，而不是给每个 `Card(...)` 传 colors/modifier：
 * - miuix `Card` 没有 border 参数；全工程 44 处 `Card(` 调用，逐个改必然漏，
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
 * 卡片触感（2026-11-08 用户要求「卡片加一点触感反馈」）。
 *
 * 为什么收口在包装件、且**只在抬手确认时**震：
 * - 全工程 40 多处卡片，逐处加手感必然漏；包装件是唯一能全覆盖的位置。
 * - 不在按下（ACTION_DOWN）时震：卡片大多躺在 LazyColumn 里，手指一落就开始滚动，
 *   按下即震会让"滚动列表"变成一路嗡鸣。`clickable`/卡片自带 onClick 只在手势被判定为点击后
 *   才回调，天然排除了滚动、拖拽与误触。
 * - 用 `SegmentTick`（最轻的一档"嗒"）而不是 `LongPress`/`Confirm`：后者是短震动，
 *   手指在玻璃背板上会听出"嗡"；这一档在多数 ROM 上接近键盘触感，才是"一点"。
 *   系统设置里关掉触感反馈时 `performHapticFeedback` 自动静默，不用自己判断。
 */
private val CardTapHaptic = HapticFeedbackType.SegmentTick

/** 长按已经是"重操作"语义，给按住确认那一档（系统自带的 LONG_PRESS）。 */
private val CardLongPressHaptic = HapticFeedbackType.LongPress

/**
 * 可点击卡片（`FeaturesScreen` 的功能网格、详情页头部卡等在用）。
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
    val haptics = LocalHapticFeedback.current
    MiuixCard(
        modifier = modifier.cardRim(cornerRadius),
        cornerRadius = cornerRadius,
        insideMargin = insideMargin,
        colors = colors,
        pressFeedbackType = pressFeedbackType,
        showIndication = showIndication,
        holdDownState = holdDownState,
        onClick = onClick?.let { callback ->
            {
                haptics.performHapticFeedback(CardTapHaptic)
                callback()
            }
        },
        onLongPress = onLongPress?.let { callback ->
            {
                haptics.performHapticFeedback(CardLongPressHaptic)
                callback()
            }
        },
        content = content,
    )
}

/**
 * 给「自挂 `Modifier.clickable` 的卡片」用的同款点击：`Card(modifier = ... .cardClick { })`。
 *
 * 单独导出是因为工具箱磁贴（`ToolboxScreen.kt` 的 `Tile`）把 `clickable` 挂在 modifier 上而不是
 * `Card(onClick=)` 参数上——那条路绕过了上面的包装件，不加这一层就会"同一个页面里有的卡响有的不响"。
 * 后续新卡片优先用 `Card(onClick = …)`，只有需要自定义 clickable 语义时才用本扩展。
 */
@Composable
fun Modifier.cardClick(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val haptics = LocalHapticFeedback.current
    return this.clickable(enabled = enabled) {
        haptics.performHapticFeedback(CardTapHaptic)
        onClick()
    }
}

/**
 * 包边色：**跟主题主色走**（2026-11-08 artwork-theme）。
 *
 * 原先是写死的淡绿（`0xFF8ED2A8` / `0xFF7FC79B`）——那是花园篱笆背景时代的配色。
 * 换成蓝紫插画背景后，绿边像贴错色卡：同一屏里紫卡 + 绿边读不出「同一套系统」。
 * 取 `colorScheme.primary` 而不是再写一组紫常量，是因为主题关键色可被用户改（主题页 15 档色板），
 * 写死就只在默认配色下成立。
 */
private val RimWidth = 1.dp

/** 包边不透明度：浅色卡上要「看得见一条边」但不抢字，深色卡提亮一档才不糊进背景。 */
private const val RimAlphaLight = 0.40f
private const val RimAlphaDark = 0.50f

/**
 * 投影色（实验分支 ui-overhaul 的装饰件）：浅色用压暗的**主色**，深色用纯黑——
 * 黑底上叠彩色投影会显脏。
 *
 * 这里取不透明色，下面的 `ShadowSteps` 每档各自带 alpha 覆盖它。
 */
private val ShadowDark = Color.Black

/** 顶部玻璃高光的强度：浅色卡上白高光要低到"只看见一层光",深色卡更需要一点点提亮边缘。 */
private const val SheenAlphaLight = 0.09f
private const val SheenAlphaDark = 0.05f

/** 投影三档：(外扩 dp, 不透明度)。越外越淡，叠起来近似高斯衰减。 */
private val ShadowSteps = listOf(1.5f to 0.06f, 3f to 0.035f, 5.5f to 0.018f)

/** 投影下移量：光从花园上方来，投影落在卡片下缘 */
private val ShadowOffsetY = 2.dp

/**
 * 沿超椭圆轮廓描一圈主题色边。路径按尺寸缓存（`drawWithCache`），尺寸不变不重建；
 * 线宽整条压在描边中线，所以内缩半个线宽对齐卡片填充。
 *
 * 2026-11-08（ui-overhaul）在同一层追加两件装饰，画序 = 投影 → 内容 → 顶高光 → 包边：
 * - **投影**：三圈逐级放大(1.5/3/5.5dp)、逐级更淡的**实心**超椭圆垫在 `drawContent()` 之前。
 *   不用 `Modifier.shadow`：那个走 RenderEffect 模糊，SDK 31+ 才有，且 44 张卡各起一层离屏
 *   缓冲区，滚动时掉帧；这里三次路径填充只是几何膨胀，视觉上足够像软阴影。
 * - **顶高光**：裁剪到卡片轮廓的竖直白渐变（0 → 45% 高度归零），给磨砂面一点"玻璃压了层光"。
 *   裁剪必须用**同一张** squircle 路径，否则光斑会在四角露出圆角矩形的接缝。
 */
@Composable
private fun Modifier.cardRim(cornerRadius: Dp): Modifier {
    val dark = isInDarkTheme()
    // 包边/投影的颜色**跟当前背景主题走**（2026-11-08 用户「紫色主题下，卡片边缘从绿色改成紫色」）：
    // 插画背景 → 插画主色；花园/纯色 → 面板 primary。
    // 不能只读 `colorScheme.primary`：主题控制器在部分分支下会回落到 miuix 自带的偏绿 primary，
    // 卡片边缘就会露出一圈绿边（2026-11-08 用户实测反馈）。
    val primary = if (AppSettings.artworkBackdrop) Color(AppSettings.artworkKeyColor) else MiuixTheme.colorScheme.primary
    val rim = primary.copy(alpha = if (dark) RimAlphaDark else RimAlphaLight)
    val shadow = if (dark) ShadowDark else primary
    val sheenAlpha = if (isInDarkTheme()) SheenAlphaDark else SheenAlphaLight
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
        val radiusPx = cornerRadius.toPx()
        val shadows = ShadowSteps.map { (expandDp, alpha) ->
            Triple(expandDp.dp.toPx(), alpha, Path().apply {
                addSquircleRect(
                    width = size.width + 2 * expandDp.dp.toPx(),
                    height = size.height + 2 * expandDp.dp.toPx(),
                    cornerRadius = radiusPx + expandDp.dp.toPx(),
                    extension = SquircleDefaults.Extension,
                    squircleEnabled = squircle,
                )
            })
        }
        val shadowOffsetY = ShadowOffsetY.toPx()
        val cardSize = size
        val sheen = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.White.copy(alpha = sheenAlpha),
                0.6f to Color.White.copy(alpha = 0f),
            ),
            startY = 0f,
            endY = size.height * 0.45f,
        )
        onDrawWithContent {
            if (size.width > 0f && size.height > 0f) {
                shadows.forEach { (expandPx, alpha, shadowPath) ->
                    translate(-expandPx, -expandPx + shadowOffsetY) {
                        drawPath(path = shadowPath, color = shadow.copy(alpha = alpha))
                    }
                }
            }
            drawContent()
            if (drawable) {
                translate(half, half) {
                    clipPath(path) {
                        drawRect(brush = sheen, size = cardSize)
                    }
                    drawPath(path = path, color = rim, style = Stroke(width = widthPx))
                }
            }
        }
    }
}
