package top.honorhook

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import top.honorhook.data.ThemeModeStore
import top.honorhook.ui.AppRoot
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        // 沉浸式：透明系统栏（照 LSPFRIDA MainActivity.kt:84-99）
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
            ),
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT,
                detectDarkMode = { true }, // 透明导航栏，不做暗色 scrim
            ),
        )
        // 三键导航白条修复：关闭系统对比度 scrim（API 29+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
        super.onCreate(savedInstanceState)
        setContent {
            // 主题由 ThemeModeStore 状态流驱动（照 LSPFRIDA MainActivity.kt:101-143 抄改）
            val mode by ThemeModeStore.mode.collectAsState()
            val keyColorHex by ThemeModeStore.keyColor.collectAsState()
            val wallpaperMonet by ThemeModeStore.wallpaperMonet.collectAsState()
            val pureBlack by ThemeModeStore.pureBlack.collectAsState()
            val keyColor = remember(keyColorHex) {
                keyColorHex?.let {
                    runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(it)) }.getOrNull()
                }
            }
            // Monet（M3 dynamicColorScheme）壁纸主色为种子，优先于手选色板；API<31 无动态色→null。
            // dynamicColorScheme 是 @Composable，不能进 remember/runCatching（LSPFRIDA 已踩）。
            // isDark 单一来源：纯黑判定与 Monet 种子共用，避免两处漂移
            val isDark = when (mode) {
                ColorSchemeMode.Dark -> true
                ColorSchemeMode.Light -> false
                else -> isSystemInDarkTheme()
            }
            val context = LocalContext.current
            val dynamicSeed: androidx.compose.ui.graphics.Color? =
                if (wallpaperMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // 暗色种子取暗色动态调色板（避免强调色派生自浅色壁纸色系）；切明暗一次性重组为已知取舍
                    if (isDark) {
                        dynamicDarkColorScheme(context).primary
                    } else {
                        dynamicLightColorScheme(context).primary
                    }
                } else null
            val effectiveSeed = dynamicSeed ?: keyColor
            // ThemeController 源码实读：keyColor 仅 Monet* 模式生效（LSPFRIDA R2a）——有种子色时合成
            // 对应 Monet 模式（跟随系统深浅/强制浅/强制深），否则「选了色但模式不变=色不变」
            val effectiveMode = if (effectiveSeed != null) {
                when (mode) {
                    ColorSchemeMode.Light -> ColorSchemeMode.MonetLight
                    ColorSchemeMode.Dark -> ColorSchemeMode.MonetDark
                    else -> ColorSchemeMode.MonetSystem
                }
            } else {
                mode
            }
            val controller = remember(effectiveMode, effectiveSeed) {
                ThemeController(
                    colorSchemeMode = effectiveMode,
                    keyColor = effectiveSeed,
                    colorSpec = ThemeColorSpec.Spec2021,
                )
            }
            // 纯黑（暗色下生效）：Miuix Colors 槽位 setter/updateColorsFrom 均 internal，公开 API 无法改计算板
            // （controller 层不改色）→ MiuixTheme(colors=...) 覆盖层：仅背景族槽位黑化（对齐 demo
            // --bg:#000/--card:#0C0C0E），文本槽位走 Miuix 暗色默认，黑底上依旧可读；
            // 已知取舍：主色/强调色回退 Miuix 内置暗色默认（不复用 keyColor/Monet 种子色）。
            MiuixTheme(controller = controller) {
                if (pureBlack && isDark) {
                    val black = darkColorScheme(
                        background = androidx.compose.ui.graphics.Color.Black,
                        surface = androidx.compose.ui.graphics.Color(0xFF0C0C0E),
                        surfaceContainer = androidx.compose.ui.graphics.Color(0xFF0C0C0E),
                        surfaceContainerHigh = androidx.compose.ui.graphics.Color(0xFF141417),
                        surfaceContainerHighest = androidx.compose.ui.graphics.Color(0xFF1B1B1E),
                        surfaceVariant = androidx.compose.ui.graphics.Color(0xFF0C0C0E),
                    )
                    MiuixTheme(colors = black) { AppRoot() }
                } else {
                    AppRoot()
                }
            }
        }
    }
}