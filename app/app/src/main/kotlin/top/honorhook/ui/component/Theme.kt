package top.honorhook.ui.component

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import top.honorhook.ui.AppSettings
import top.honorhook.ui.ColorMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.ThemePaletteStyle

/** 当前颜色模式的原始 value（0-6），供 `isInDarkTheme()` 判定。 */
val LocalColorMode = staticCompositionLocalOf { 0 }

/**
 * 是否启用玻璃（KernelSU: `LocalEnableBlur`，影响顶栏/底栏是否走 backdrop）。
 *
 * 必须用 `compositionLocalOf` 而**不是** `staticCompositionLocalOf`：
 * static 的值变更不会触发读取方重组（只在组合结束才生效），
 * 表现为「在主题页改了开关，返回主界面玻璃状态没变」。
 */
val LocalEnableBlur = compositionLocalOf { true }

/** 是否启用悬浮底栏。 */
val LocalEnableFloatingBottomBar = compositionLocalOf { true }

/** 是否启用底栏玻璃（lens 折射）。 */
val LocalEnableFloatingBottomBarBlur = compositionLocalOf { true }

/** SPEC_2025 只在部分配色风格下生效（照抄 KernelSU 的 `supportsSpec2025`）。 */
val PaletteStyle.supportsSpec2025: Boolean
    get() = this == PaletteStyle.TonalSpot ||
            this == PaletteStyle.Neutral ||
            this == PaletteStyle.Vibrant ||
            this == PaletteStyle.Expressive

fun ColorSpec.SpecVersion.effectiveFor(style: PaletteStyle): ColorSpec.SpecVersion =
    if (this == ColorSpec.SpecVersion.SPEC_2025 && !style.supportsSpec2025) {
        ColorSpec.SpecVersion.SPEC_2021
    } else {
        this
    }

/**
 * 应用主题（**Miuix**，照抄 KernelSU `MiuixKernelSUTheme`）。
 *
 * Miuix 自带 `ThemeController`，把「颜色模式 + 关键色 + 配色风格 + 色规范」一次交给它，
 * 由 Miuix 内部生成色板（支持 Monet 跟随壁纸取色）。
 */
@Composable
fun HonorHookTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val systemDarkTheme = isSystemInDarkTheme()

    val colorMode = AppSettings.colorMode
    val darkTheme = colorMode.isDark || (colorMode.isSystem && systemDarkTheme)

    val paletteStyle = AppSettings.paletteStyle
    val colorSpec = AppSettings.specVersion

    val miuixPaletteStyle = runCatching { ThemePaletteStyle.valueOf(paletteStyle.name) }
        .getOrDefault(ThemePaletteStyle.TonalSpot)

    val miuixColorSpec = if (colorSpec.effectiveFor(paletteStyle) == ColorSpec.SpecVersion.SPEC_2025) {
        ThemeColorSpec.Spec2025
    } else {
        ThemeColorSpec.Spec2021
    }

    val resolvedKeyColor: Color? = when {
        AppSettings.keyColor != 0 -> Color(AppSettings.keyColor)
        colorMode.isMonet ->
            if (darkTheme) dynamicDarkColorScheme(context).primary
            else dynamicLightColorScheme(context).primary

        else -> null
    }

    val controller = ThemeController(
        when (colorMode) {
            ColorMode.SYSTEM -> ColorSchemeMode.System
            ColorMode.LIGHT -> ColorSchemeMode.Light
            ColorMode.DARK -> ColorSchemeMode.Dark
            ColorMode.MONET_SYSTEM -> ColorSchemeMode.MonetSystem
            ColorMode.MONET_LIGHT -> ColorSchemeMode.MonetLight
            ColorMode.MONET_DARK, ColorMode.DARK_AMOLED -> ColorSchemeMode.MonetDark
        },
        keyColor = resolvedKeyColor,
        isDark = darkTheme,
        paletteStyle = miuixPaletteStyle,
        colorSpec = miuixColorSpec,
    )

    MiuixTheme(
        controller = controller,
    ) {
        CompositionLocalProvider(
            LocalColorMode provides colorMode.value,
            LocalEnableBlur provides AppSettings.liquidGlass,
            LocalEnableFloatingBottomBar provides AppSettings.floatingBar,
            LocalEnableFloatingBottomBarBlur provides AppSettings.floatingBarGlass,
            LocalContentColor provides MiuixTheme.colorScheme.onBackground,
        ) {
            LaunchedEffect(darkTheme) {
                val window = (context as? Activity)?.window ?: return@LaunchedEffect
                WindowInsetsControllerCompat(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
            content()
        }
    }
}

/** 是否深色（照抄 KernelSU `isInDarkTheme`）。 */
@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean = when (LocalColorMode.current) {
    1, 4 -> false  //强制浅色
    2, 5, 6 -> true   // 强制深色
    else -> isSystemInDarkTheme()  // 跟随系统
}

/** 深色模式下页面底色（AMOLED 纯黑）。 */
@Composable
fun pageBackground(): Color =
    if (AppSettings.colorMode.isAmoled) Color.Black else MiuixTheme.colorScheme.background

/** 卡片底色（Miuix `surfaceContainer`）。 */
@Composable
fun cardColor(): Color = MiuixTheme.colorScheme.surfaceContainer

/** 次级文字。 */
@Composable
fun secondaryText(): Color = MiuixTheme.colorScheme.onBackgroundVariant

/** 强调主色（底栏图标/文字高亮）。 */
@Composable
fun accentPrimary(): Color = MiuixTheme.colorScheme.primary

/** 主色上的前景色。 */
@Composable
fun onPrimary(): Color = MiuixTheme.colorScheme.onPrimary