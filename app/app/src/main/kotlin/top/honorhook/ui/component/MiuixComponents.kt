package top.honorhook.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.isRuntimeShaderSupported
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.squircle.SquircleDefaults
import top.yukonga.miuix.kmp.squircle.addSquircleRect
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 页面/Scaffold 底色（Miuix 语义 token，浅深自适应）：
 * - 浅色 = background（官方浅色 #FFFFFF 白，与卡片 surfaceContainer 有层次）；
 * - 深色 = surface（官方深色 surface=Black，background 与卡片同色无层次）。
 * （照 LSPFRIDA MiuixComponents.kt:82-89）
 */
@Composable
fun MiuixPageBackground(): Color {
    val dark = when (MiuixTheme.colorSchemeMode) {
        ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> true
        ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> false
        else -> isSystemInDarkTheme() // System / MonetSystem / null
    }
    return if (dark) MiuixTheme.colorScheme.surface else MiuixTheme.colorScheme.background
}

/**
 * UI 设计统一 token（照 LSPFRIDA UiTokens.kt:23-54，含玻璃拟态两项）。
 */
object UiTokens {

    /** 页面水平留白（8dp 栅格） */
    val PagePadding = 12.dp

    /** 卡片之间的段落间距 */
    val CardSpacing = 12.dp

    /** 卡片统一圆角（Miuix Card 内部即 squircleSurface） */
    val CardRadius = 12.dp

    /** 卡片内边距：水平 14dp、垂直 12dp */
    val CardMarginH = 14.dp
    val CardMarginV = 12.dp

    /** 玻璃悬浮底栏胶囊圆角（照 LSPFRIDA UiTokens.kt:39 保持 22dp 不变） */
    val BarRadius = 22.dp

    /** 玻璃拟态模糊半径（dp；Liquid Glass 适中强度，避免过糊丢失内容识别） */
    val GlassBlurRadius = 20f

    // ===== 排版层级（大标题 24sp 由 TopAppBar largeTitle 承担，此处为内容层） =====

    /** 卡片/区块标题 */
    val TitleSize = 17.sp

    /** 副标/次级说明 */
    val SubtitleSize = 13.sp

    /** 元信息/说明（caption） */
    val CaptionSize = 12.sp
}

/**
 * 超椭圆（Superellipse/squircle）[Shape]：以公开 API [addSquircleRect] 构建轮廓，
 * 供 blur 区域剪切等必须传入 [Shape] 的场景使用（剪裁类用 [top.yukonga.miuix.kmp.squircle.squircleClip]）。
 * （照 LSPFRIDA UiTokens.kt:56-81）
 */
class SquircleShape(
    private val cornerRadius: Dp,
    private val extension: Float = SquircleDefaults.Extension,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radiusPx = with(density) { cornerRadius.toPx() }
        val path = Path()
        path.addSquircleRect(size.width, size.height, radiusPx, extension)
        return Outline.Generic(path)
    }
}

/**
 * 玻璃拟态表面（Miuix Liquid Glass 模式）：
 * `Modifier.layerBackdrop(backdrop)` 捕获内容 → [textureBlur]（RuntimeShader 高斯模糊 +
 * 自适应降采样 + 噪声抖动防色带）→ 半透明表面色 [tint] 叠在模糊之上。
 *
 * 门槛与降级（照 LSPFRIDA GlassSurface.kt:18-70）：
 * - blur 管线全部基于 RuntimeShader，`isRuntimeShaderSupported()` = API<33 自动无操作；
 * - 低于门槛时 [tint] 按不透纯色直接绘制（与 Miuix TopAppBar 默认 surface 实色一致，无视觉回归）。
 *
 * 性能：只用于小区域（底栏胶囊），禁止叠加大区域。
 */
@Composable
fun Modifier.glassSurface(
    backdrop: LayerBackdrop,
    shape: Shape,
    tint: Color,
    blurRadius: Float = UiTokens.GlassBlurRadius,
    blurEnabled: Boolean = true,
): Modifier {
    val dark = when (MiuixTheme.colorSchemeMode) {
        ColorSchemeMode.Dark, ColorSchemeMode.MonetDark -> true
        ColorSchemeMode.Light, ColorSchemeMode.MonetLight -> false
        else -> isSystemInDarkTheme()
    }
    return if (isRuntimeShaderSupported() && blurEnabled) {
        textureBlur(
            backdrop = backdrop,
            shape = shape,
            blurRadius = blurRadius,
            // 显式关闭顶部高光（Highlight）——浅色/动态内容下高光渐变会显现为
            // 左侧灰色"一块模糊"伪影（照 LSPFRIDA GlassSurface.kt:59-61）
            highlight = null,
        ).background(
            color = tint.copy(alpha = if (dark) 0.70f else 0.75f),
            shape = shape,
        )
    } else {
        // API<33 降级纯色（不透）：等同于旧实色表面
        background(color = tint, shape = shape)
    }
}

/**
 * 悬浮底栏（自绘）——玻璃拟态版（照 LSPFRIDA MiuixComponents.kt:105-220）：
 * 容器 = 超椭圆（SquircleShape 22dp）玻璃胶囊（blur + 半透明 surfaceContainer），
 * 仅每项 icon+text + **单一 pill 滑动指示器**（secondaryContainer + 300ms 位置动画，
 * 无颜色动画=无"双灰"交叉 fade）。
 *
 * glassSurface 已内置 API<33 降级（不透纯色胶囊）。
 * 指示器容器同步用 squircleClip（超椭圆；API<33 自动降级 RoundedCornerShape）。
 *
 * 调用方（AppRoot）负责：
 *   val backdrop = rememberLayerBackdrop()
 *   内容容器 .layerBackdrop(backdrop)（不含本底栏，避免自模糊回环）。
 */
@Composable
fun FloatingMiuixNavigationBar(
    items: List<NavigationItem>,
    selected: Int,
    onClick: (Int) -> Unit,
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp), // 底部偏移 12dp + 水平 16dp 悬浮边距
    ) {
        // 单一 pill 的 x 偏移（相对胶囊容器左上角）；与 Row(SpaceEvenly, 16dp 水平 padding) 槽位公式对齐
        val pillOffsetX by animateDpAsState(
            targetValue = singlePillOffsetX(items.size, selected, maxWidth),
            animationSpec = tween(300),
            label = "navSinglePillOffsetX",
        )
        // 玻璃胶囊容器（超椭圆 Shape；模糊区域剪裁与内容底色同形）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .glassSurface(
                    backdrop = backdrop,
                    shape = SquircleShape(UiTokens.BarRadius),
                    tint = MiuixTheme.colorScheme.surfaceContainer,
                ),
        ) {
            // 指示器层（置于 Row 之下，实体整体滑动，无交叉 fade）
            Box(
                modifier = Modifier
                    .offset(x = pillOffsetX, y = 8.dp) // 8dp = Row vertical padding
                    .size(64.dp, 56.dp)
                    .squircleClip(UiTokens.BarRadius)
                    .background(MiuixTheme.colorScheme.secondaryContainer),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp, horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                items.forEachIndexed { index, item ->
                    FloatingNavItem(
                        item = item,
                        isSelected = selected == index,
                        onClick = { onClick(index) },
                    )
                }
            }
        }
    }
}

/** 单一 pill 在 [index] 槽位上的 x 偏移（Dp），与 Row 的 SpaceEvenly + 76dp 槽位布局精确对齐。 */
private fun singlePillOffsetX(itemCount: Int, index: Int, containerWidth: Dp): Dp {
    if (itemCount <= 0) return 0.dp
    // 越界时 clamp（与旧实现"无选中项"行为兼容，不抛异常）
    val safeIndex = index.coerceIn(0, itemCount - 1)
    val itemWidth = 76.dp            // item 槽位宽（照 LSPFRIDA singlePillOffsetX:166）
    val rowContentWidth = containerWidth - 32.dp // Row horizontal padding 16dp × 2
    // SpaceEvenly：剩余空间均分到 (itemCount + 1) 个间隙（含两端）
    val gap = (rowContentWidth - itemWidth * itemCount) / (itemCount + 1)
    val itemStartX = 16.dp + gap + (itemWidth + gap) * safeIndex
    // 胶囊 64dp 在 76dp 槽位内水平居中
    return itemStartX + (itemWidth - 64.dp) / 2
}

@Composable
private fun FloatingNavItem(
    item: NavigationItem,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    // 标签背景静止透明（指示器上移到单一 pill 层），仅 icon/text 做色彩动画，
    // 选中 primary 高亮 vs 未选中 onSurface（照 LSPFRIDA FloatingNavItem:175-220）。
    val tint by animateColorAsState(
        targetValue = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
        animationSpec = tween(300),
        label = "navTint",
    )
    // item 容器 76dp 宽，胶囊 64×56，胶囊内边距 4dp；圆角 22dp 与 300ms 过渡（照 LSPFRIDA 原值）
    Column(
        modifier = Modifier.width(76.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier
                .size(64.dp, 56.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color.Transparent)
                .clickable(onClick = onClick)
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = item.icon,
                contentDescription = item.label,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = item.label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal,
                color = tint,
                maxLines = 1,
            )
        }
    }
}