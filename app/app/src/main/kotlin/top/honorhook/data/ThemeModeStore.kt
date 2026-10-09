package top.honorhook.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * 主题模式（System/Light/Dark 三态）持久化存储。
 * 由 HookApplication.onCreate 初始化；变更即时写入 StateFlow，
 * MainActivity 在 setContent 中 collectAsState 驱动 ThemeController → MiuixTheme 全局即时生效。
 * （照 LSPFRIDA ThemeModeStore.kt:16-93 简化：砍掉 keyColor / paletteStyle / wallpaperMonet）
 */
object ThemeModeStore {

    private const val PREFS_CFG = "honorhook_cfg"
    private const val KEY_MODE = "theme_mode"
    private const val MODE_SYSTEM = "system"
    private const val MODE_LIGHT = "light"
    private const val MODE_DARK = "dark"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _mode = MutableStateFlow(ColorSchemeMode.System)
    /** 当前主题模式（只读 StateFlow；修改只有 [set]）。 */
    val mode: StateFlow<ColorSchemeMode> = _mode.asStateFlow()

    /** 必须在首次访问 [mode]/[set] 前调用（HookApplication.onCreate）。 */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_CFG, Context.MODE_PRIVATE)
        prefs = p
        _mode.value = decode(p.getString(KEY_MODE, null))
    }

    fun current(): ColorSchemeMode = _mode.value

    /** 持久化并即时更新全局状态（设置页 → MainActivity 生效链路）。 */
    fun set(mode: ColorSchemeMode) {
        prefs?.edit()?.putString(KEY_MODE, encode(mode))?.apply()
        _mode.value = mode
    }

    // 只持久化三态；Monet* 等类型统一按 System 编码，解码未知值回落 System
    private fun encode(mode: ColorSchemeMode): String = when (mode) {
        ColorSchemeMode.Light -> MODE_LIGHT
        ColorSchemeMode.Dark -> MODE_DARK
        else -> MODE_SYSTEM
    }

    private fun decode(value: String?): ColorSchemeMode = when (value) {
        MODE_LIGHT -> ColorSchemeMode.Light
        MODE_DARK -> ColorSchemeMode.Dark
        else -> ColorSchemeMode.System
    }
}