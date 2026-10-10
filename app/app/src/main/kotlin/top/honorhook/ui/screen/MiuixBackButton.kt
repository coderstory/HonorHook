package top.honorhook.ui.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.graphics.graphicsLayer
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme

/**
 * 返回按钮 —— 照抄 KernelSU 二级页的 `navigationIcon`：
 * ```
 * IconButton(onClick) {
 *     Icon(Modifier.graphicsLayer { if (RTL) scaleX = -1f }, MiuixIcons.Back, tint = onBackground)
 * }
 * ```
 * Miuix 自带 `Back` 图标（`MiuixIcons.extended.Back`），RTL 时自动镜像。
 */
@Composable
internal fun MiuixBackButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        val layoutDirection = LocalLayoutDirection.current
        Icon(
            modifier = Modifier.graphicsLayer {
                if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f
            },
            imageVector = MiuixIcons.Back,
            contentDescription = null,
            tint = colorScheme.onBackground,
        )
    }
}