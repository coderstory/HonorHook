package top.honorhook.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
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
        modifier = modifier.cardRim(techCornerRadius(cornerRadius)),
        cornerRadius = techCornerRadius(cornerRadius),
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
        modifier = modifier.cardRim(techCornerRadius(cornerRadius)),
        cornerRadius = techCornerRadius(cornerRadius),
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

/** 卡片几何叠加（2026-11-27 用户「卡片能加背景？几何的图形叠加都行啊」）。 */
private val TechGridStep = 14.dp

/** 科技蓝包边用的青 / 电蓝；与背景皮肤 `TechBackdrop` 同一组色相。 */
private val TechRimCyan = Color(0xFF35E0FF)
private val TechRimBlue = Color(0xFF2E9BFF)

/**
 * 科技蓝主题下的卡片圆角：比常规大 4dp（用户要「更大圆角」的玻璃感）。
 * 描边路径与 miuix 填充必须用同一个值，否则四角会露出一圈不贴合的缝。
 */
private fun techCornerRadius(base: Dp): Dp = ThemeShape.cardRadius(base)

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
    // 科技蓝主题（2026-11-27）：包边改青→蓝→青渐变，投影换成同色系外发光，
    // 亮度由主题层共享的脉动相位驱动（LocalTechPulse，读在 draw 阶段 ⇒ 每帧只重绘不重组）。
    val tech = AppSettings.techSkin
    val pulseState = LocalTechPulse.current
    val rim = primary.copy(alpha = if (dark) RimAlphaDark else RimAlphaLight)
    val techRimColors = listOf(TechRimCyan, TechRimBlue, TechRimCyan)
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
        // 科技蓝的包边渐变：沿卡片对角线铺，四角各自吃到不同相位，转屏/改尺寸时重算（缓存内）
        val techRim = if (tech) {
            Brush.linearGradient(
                colors = techRimColors,
                start = Offset.Zero,
                end = Offset(size.width, size.height),
            )
        } else null
        val techSheen = if (tech) Color(0xFFB7ECFF) else Color.White
        val sheen = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to techSheen.copy(alpha = sheenAlpha),
                0.6f to Color.White.copy(alpha = 0f),
            ),
            startY = 0f,
            endY = size.height * 0.45f,
        )
        onDrawWithContent {
            // 科技蓝：三角波脉动（0→1→0），发光在外圈最亮 —— 读 State 发生在 draw 阶段，
            // 只触发重绘不触发重组。提到最外层是因为发光和卡片底纹都要用同一个相位。
            val pulse = if (tech) {
                val t = pulseState?.value ?: 0f
                1f - kotlin.math.abs(t * 2f - 1f)
            } else 0f
            if (size.width > 0f && size.height > 0f) {
                shadows.forEachIndexed { index, (expandPx, alpha, shadowPath) ->
                    translate(-expandPx, -expandPx + shadowOffsetY) {
                        if (tech) {
                            // 外发光：同一圈几何，换成青色、最外圈更亮，随脉动 0.6x~1.6x
                            val glowAlpha = alpha * (0.6f + 0.9f * pulse) * if (index == 0) 2.2f else 1.4f
                            drawPath(
                                path = shadowPath,
                                color = Color(0xFF2E9BFF).copy(alpha = glowAlpha.coerceAtMost(0.5f)),
                            )
                        } else {
                            drawPath(path = shadowPath, color = shadow.copy(alpha = alpha))
                        }
                    }
                }
            }
            // 科技蓝：卡片自身的几何底纹（斜向细线 + 右上双圆环 + 网格交点上的电路节点）。
            // 画在 `drawContent()` 之前、裁剪到同一张 squircle 路径 ⇒ 卡片内边距内的文字仍在最上层。
            if (tech && drawable) {
                translate(half, half) {
                    clipPath(path) {
                        drawTechGeometry(dark = dark, pulse = pulse)
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

/**
 * 卡片上的几何叠加层（科技蓝主题专用，画在内容之下、裁剪在卡片轮廓之内）。
 *
 * 为什么是「少量几何」而不是贴图/位图：
 * - 44 张卡的尺寸各不相同，位图要出多套密度还得平铺裁剪；纯几何按 `size` 现算，任意尺寸都不拉伸。
 * - 全部只画线/圆/点，没有离屏层，卡片滚动时不额外分配位图。
 * - 透明度压在 0.05~0.14：底纹是"材质"，不能抢卡片里的字（深色卡上更亮一档才看得见）。
 *
 * `pulse` 来自主题层共享时钟（0..1 三角波），只用来让节点轻微呼吸 —— 不引入新的动画。
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTechGeometry(dark: Boolean, pulse: Float) {
    val w = size.width
    val h = size.height
    if (w <= 0f || h <= 0f) return

    // ① 斜向细线：45° 一组，从左上外沿切进来，间距 14dp；线宽 1px 是物理像素级的发丝线
    val step = TechGridStep.toPx()
    val lineAlpha = if (dark) 0.075f else 0.055f
    val lineColor = TechRimCyan.copy(alpha = lineAlpha)
    var offset = -h
    while (offset < w) {
        drawLine(
            color = lineColor,
            start = Offset(offset, 0f),
            end = Offset(offset + h, h),
            strokeWidth = 1f,
        )
        offset += step
    }

    // ② 右上双圆环：一大一小同心（卡角被裁掉一半，只露内侧弧），给"传感器/雷达"的暗示
    val ringCenter = Offset(w * 1.02f, -h * 0.06f)
    val ringAlpha = if (dark) 0.16f else 0.11f
    drawCircle(
        color = TechRimBlue.copy(alpha = ringAlpha),
        radius = w * 0.52f,
        center = ringCenter,
        style = Stroke(width = 1f),
    )
    drawCircle(
        color = TechRimCyan.copy(alpha = ringAlpha * 0.8f),
        radius = w * 0.34f,
        center = ringCenter,
        style = Stroke(width = 1f),
    )

    // ③ 左下电路节点：4 个点 + 一截折线，呼吸幅度 ±40%，几乎察觉不到但扫一眼是"活的"
    val nodeColor = TechRimCyan.copy(alpha = (if (dark) 0.20f else 0.14f) * (0.8f + 0.4f * pulse))
    val nodeY = h * 0.82f
    drawCircle(color = nodeColor, radius = 1.6f, center = Offset(w * 0.10f, nodeY))
    drawCircle(color = nodeColor, radius = 1.6f, center = Offset(w * 0.22f, nodeY))
    drawCircle(color = nodeColor, radius = 1.2f, center = Offset(w * 0.30f, nodeY - 8f))
    drawLine(
        color = nodeColor.copy(alpha = nodeColor.alpha * 0.65f),
        start = Offset(w * 0.10f, nodeY),
        end = Offset(w * 0.30f, nodeY),
        strokeWidth = 1f,
    )
}
