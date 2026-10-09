package top.honorhook

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
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
        // 沉浸式：透明系栏（照 LSPFRIDA MainActivity.kt:84-99）
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
            // 主题模式+种子色+Monet 取色+纯黑由 ThemeModeStore 状态流驱动；任一变化 →
            // collectAsState 触发重组 → remember(mode, keyColor) 重建 ThemeController
            // → MiuixTheme 全局即时生效（照 LSPFRIDA MainActivity.kt:101-143 抄改）。
            val mode by ThemeModeStore.mode.collectAsState()
            val keyColorHex by ThemeModeStore.keyColor.collectAsState()
            val wallpaperMonet by ThemeModeStore.wallpaperMonet.collectAsState()
            val pureBlack by ThemeModeStore.pureBlack.collectAsState()
            val keyColor = remember(keyColorHex) {
                keyColorHex?.let {
                    runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(it)) }.getOrNull()
                }
            }
            // Monet 跟随系统取色（用户明确引入 M3 的目的）：M3 dynamicColorScheme 读取系统壁纸
            // 动态色的主色作为种子；开启时优先于手选色板。⚠ dynamicLightColorScheme 是
            // @Composable——不能在 runCatching/remember 等普通 lambda 中调用（LSPFRIDA 已踩）；
            // API<31 无动态壁纸色→null。
            val context = LocalContext.current
            val dynamicSeed: androidx.compose.ui.graphics.Color? =
                if (wallpaperMonet && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    dynamicLightColorScheme(context).primary
                } else null
            val effectiveSeed = dynamicSeed ?: keyColor
            // Miuix ThemeController 源码实读（LSPFRIDA R2a 注释）：lightColors/darkColors 只在
            // System/Light/Dark 模式下使用，keyColor **仅 Monet* 模式生效**——无种子色用原三态；
            // 有种子色时合成对应 Monet 模式（跟随系统深浅/强制浅/强制深），
            // 否则"选了色但模式不变=色不变"。
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
            // 纯黑模式（暗色下生效）：controller 层不改色（Miuix ThemeController 的
            // lightColors/darkColors 仅非 Monet 模式生效；Colors 槽位 setter/updateColorsFrom
            // 均为 internal，公开 API 无法对计算板改槽位），故用 MiuixTheme(colors=...) 覆盖层：
            // 由公开工厂 darkColorScheme() 直接构造纯黑暗色板——仅背景族槽位黑化
            // （background/surface/surfaceContainer*/surfaceVariant，对齐 demo --bg:#000/
            // --card:#0C0C0E），onSurface 等文本槽位走 Miuix 暗色默认，黑底上依旧可读；
            // 已知取舍：主色/强调色回退 Miuix 内置暗色默认（不复用 keyColor/Monet 种子色）。
            val isDark = when (mode) {
                ColorSchemeMode.Dark -> true
                ColorSchemeMode.Light -> false
                else -> isSystemInDarkTheme()
            }
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