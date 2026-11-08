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
 * 彩色圆角图标块（2026-11-08 用户口径：图标要**彩色**，观感对齐工具箱磁贴）。
 *
 * 结构 = 半透明 accent 渐变底（0.34 → 0.12，同工具箱 `Tile`）+ accent 字形，
 * 再叠一层顶部白色高光 + 一圈 accent 描边补「质感」（纯色块太平，玻璃感来自这两层）。
 *
 * 底色刻意用 accent 自身低透明度而不是主题色：深浅色主题下都能透出底下的卡片色，
 * 字形保持 accent 原色（深色主题下也不会糊成灰）。
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
