package top.honorhook.ui.component

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
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

// 原 KernelSU 风格的 `LocalEnableBlur` / `LocalEnableFloatingBottomBar` / `LocalEnableFloatingBottomBarBlur`
// 三个 CompositionLocal 已删除：全工程只 provide、无人 `.current` 读取（MainActivity 改为直接订阅
// AppSettings 的 State，见其 `AppRoot` 注释），留着只会让"改了开关没反应"更难查。

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

// 原有 pageBackground / cardColor / secondaryText / accentPrimary / onPrimary 五个颜色 helper
// 无任何调用者（各页面直接读 `MiuixTheme.colorScheme`），已删除。