package top.honorhook.ui.component

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import top.honorhook.ui.AppSettings
import top.honorhook.ui.ColorMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

/** 当前颜色模式的原始 value（0-6），供 `isInDarkTheme()` 判定。 */
val LocalColorMode = staticCompositionLocalOf { 0 }

// 原 KernelSU 风格的 `LocalEnableBlur` / `LocalEnableFloatingBottomBar` / `LocalEnableFloatingBottomBarBlur`
// 三个 CompositionLocal 已删除：全工程只 provide、无人 `.current` 读取（MainActivity 改为直接订阅
// AppSettings 的 State，见其 `AppRoot` 注释），留着只会让"改了开关没反应"更难查。

/**
 * 应用主题（**Miuix**，照抄 KernelSU `MiuixKernelSUTheme`）。
 *
 * Miuix 自带 `ThemeController`，把「颜色模式 + 关键色 + 配色风格 + 色规范」一次交给它，
 * 由 Miuix 内部生成色板（支持 Monet 跟随壁纸取色）。
 */
@Composable
fun HonorHookTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorMode = AppSettings.colorMode
    val darkTheme = colorMode.isDark || (colorMode.isSystem && isSystemInDarkTheme())

    // 颜色不再可配（2026-11-08 用户删掉「跟随壁纸取色 / 关键色 / 配色风格 / 色规范」）：
    // 整套色板只从插画主色这一个种子生成，主题页只剩「明暗模式」+ 背景皮肤。
    val controller = ThemeController(
        colorSchemeMode = when (colorMode) {
            ColorMode.SYSTEM -> ColorSchemeMode.System
            ColorMode.LIGHT -> ColorSchemeMode.Light
            ColorMode.DARK -> ColorSchemeMode.Dark
        },
        keyColor = Color(AppSettings.artworkKeyColor),
        isDark = darkTheme,
    )

    MiuixTheme(
        controller = controller,
    ) {
        // 卡片磨砂半透明（2026-11-08 用户要求「所有的卡片」）：miuix `Card` 默认色 =
        // `colorScheme.surfaceContainer`，在主题层统一压一个 alpha，比逐个 Card 传 colors 干净
        // （散点改必漏）。alpha 不能太低：卡片底下现在是整幅插画，透太多字会跟画搅在一起。
        // 2026-11-08（artwork-theme）：插画背景比原来的花园更花，用户反馈卡片要「再白一点」，
        // 浅色 0.72 → 0.80、深色 0.62 → 0.72（不是不透明，还要留一点「画在玻璃后面」的层次）。
        val baseScheme = MiuixTheme.colorScheme
        val frostedScheme = baseScheme.copy(
            surfaceContainer = baseScheme.surfaceContainer.copy(
                alpha = if (darkTheme) 0.72f else 0.80f,
            ),
        )
        MiuixTheme(colors = frostedScheme) {
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