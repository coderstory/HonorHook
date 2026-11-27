package top.honorhook.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon

/**
 * 行首图标。
 *
 * 两个形态由主题决定（`ThemeShape.flatBadge`）：
 * - **插画 / 花园 / 纯色底**：彩色圆角块（2026-11-08 用户口径「图标要彩色」）—— 半透明 accent
 *   渐变底（0.34 → 0.12，同工具箱 `Tile`）+ accent 字形 + 顶部白色高光 + accent 描边。
 * - **科技蓝**：只画字形、不垫底块（2026-11-28 用户：「我只让你去掉科技蓝的图标背景」）。
 *
 * 边界记清楚：11-28 那句「图标不需要背景色」只针对科技蓝，**别**顺手把另外两套主题的底块
 * 也删了 —— 那会把插画/花园的观感一起改掉，属于夹带。
 *
 * `size`（占位方框）与 `corner` 保留给 16 处调用点排版。
 */
@Composable
fun IconBadge(
    icon: ImageVector,
    accent: Color,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    iconSize: Dp = 19.dp,
    corner: Dp = 11.dp,
) {
    val tint = techAccent(accent)
    if (ThemeShape.flatBadge) {
        // 科技蓝：无底块、无描边、无高光，字形比原来大一号
        Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
            TechGlyph(icon = icon, contentDescription = contentDescription, size = iconSize + 7.dp)
        }
        return
    }
    val shape = RoundedCornerShape(corner)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(
                Brush.linearGradient(
                    listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.12f)),
                ),
            )
            .border(1.dp, accent.copy(alpha = 0.16f), shape),
        contentAlignment = Alignment.Center,
    ) {
        // 顶部高光：白 16% → 透明，模拟光源从上方打下来的玻璃面
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White.copy(alpha = 0.16f), Color.Transparent),
                    ),
                ),
        )
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = accent,
            modifier = Modifier.size(iconSize),
        )
    }
}

/**
 * 科技蓝的字形：**就是图标集本身的样子**——单色、实心、不描边、不加发光、不加投影。
 *
 * 2026-11-28 用户：「你别自己挂了，直接去抄吧，太丑陋了」。前两版特效（三层渐变+外发光、
 * 单色+投影）都是我在图标集之上自己加的，反而把它画丑了。现在不做任何二次加工：
 * Phosphor fill 的块面 + 一个颜色。
 */
@Composable
fun TechGlyph(
    icon: ImageVector,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
) {
    Icon(
        imageVector = icon,
        contentDescription = contentDescription,
        tint = Color(0xFF8BE4FF),
        modifier = modifier.size(size),
    )
}
