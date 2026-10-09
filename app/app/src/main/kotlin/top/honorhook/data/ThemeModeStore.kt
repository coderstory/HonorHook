package top.honorhook.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.yukonga.miuix.kmp.theme.ColorSchemeMode

/**
 * 主题设置（模式/种子色/Monet 取色/纯黑）持久化。
 * 照 LSPFRIDA ThemeModeStore.kt:16-93 抄改：mode + keyColor + wallpaperMonet；新增 pureBlack，砍未用的 paletteStyle。
 */
object ThemeModeStore {

    private const val PREFS_CFG = "honorhook_cfg"
    private const val KEY_MODE = "theme_mode"
    private const val KEY_KEY_COLOR = "theme_key_color"
    private const val KEY_WALLPAPER = "theme_wallpaper_monet"
    private const val KEY_PURE_BLACK = "theme_pure_black"
    private const val MODE_SYSTEM = "system"
    private const val MODE_LIGHT = "light"
    private const val MODE_DARK = "dark"

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _mode = MutableStateFlow(ColorSchemeMode.System)
    /** 当前主题模式。 */
    val mode: StateFlow<ColorSchemeMode> = _mode.asStateFlow()

    private val _keyColor = MutableStateFlow<String?>(null)
    /** 种子色（#RRGGBB 大写 Hex；null=Miuix 内置默认）。 */
    val keyColor: StateFlow<String?> = _keyColor.asStateFlow()

    private val _wallpaperMonet = MutableStateFlow(false)
    /** Monet 跟随系统取色（M3 dynamicColorScheme 壁纸主色为种子，优先于手选色板）。 */
    val wallpaperMonet: StateFlow<Boolean> = _wallpaperMonet.asStateFlow()

    private val _pureBlack = MutableStateFlow(false)
    /** 纯黑模式（暗色下纯黑背景，AMOLED 省电）。 */
    val pureBlack: StateFlow<Boolean> = _pureBlack.asStateFlow()

    /** 首次访问 StateFlow/写方法前调用（HookApplication.onCreate）。 */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(PREFS_CFG, Context.MODE_PRIVATE)
        prefs = p
        _mode.value = decode(p.getString(KEY_MODE, null))
        _keyColor.value = p.getString(KEY_KEY_COLOR, null)
        _wallpaperMonet.value = p.getBoolean(KEY_WALLPAPER, false)
        _pureBlack.value = p.getBoolean(KEY_PURE_BLACK, false)
    }

    fun current(): ColorSchemeMode = _mode.value
    fun currentKeyColor(): String? = _keyColor.value

    /** 设置主题模式（持久化并即时生效）。 */
    fun set(mode: ColorSchemeMode) {
        prefs?.edit()?.putString(KEY_MODE, encode(mode))?.apply()
        _mode.value = mode
    }

    /** 设置/清除种子色；null=恢复 Miuix 默认。 */
    fun setKeyColor(hex: String?) {
        prefs?.edit()?.apply { if (hex == null) remove(KEY_KEY_COLOR) else putString(KEY_KEY_COLOR, hex) }?.apply()
        _keyColor.value = hex
    }

    fun setWallpaperMonet(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_WALLPAPER, enabled)?.apply()
        _wallpaperMonet.value = enabled
    }

    /** 纯黑模式开关（仅暗色下生效，渲染侧见 MainActivity PureBlack 覆盖层）。 */
    fun setPureBlack(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_PURE_BLACK, enabled)?.apply()
        _pureBlack.value = enabled
    }

    // 只持久化三态；Monet* 等类型统一按 System 编码，解码未知值回落 System。
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