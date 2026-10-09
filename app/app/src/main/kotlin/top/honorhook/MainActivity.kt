package top.honorhook

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import top.honorhook.data.ThemeModeStore
import top.honorhook.ui.AppRoot
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController

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
            // 三态主题模式由 ThemeModeStore 驱动：collectAsState → remember 重建
            // ThemeController → MiuixTheme 全局即时生效（照 LSPFRIDA setContent 简化）。
            val mode by ThemeModeStore.mode.collectAsState()
            val controller = remember(mode) { ThemeController(colorSchemeMode = mode) }
            MiuixTheme(controller = controller) { AppRoot() }
        }
    }
}