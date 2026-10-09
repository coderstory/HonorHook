package top.honorhook.ui.component

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** UI 设计统一 token（照 LSPFRIDA UiTokens.kt:23-54，去掉未启用的玻璃拟态项）。 */
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

    // ===== 排版层级（大标题 24sp 由 TopAppBar largeTitle 承担，此处为内容层） =====

    /** 卡片/区块标题 */
    val TitleSize = 17.sp

    /** 副标/次级说明 */
    val SubtitleSize = 13.sp

    /** 元信息/说明（caption） */
    val CaptionSize = 12.sp
}