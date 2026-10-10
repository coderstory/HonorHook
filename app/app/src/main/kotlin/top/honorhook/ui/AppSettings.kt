package top.honorhook.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec

/**
 * 颜色模式（照抄 KernelSU `ui/theme/Theme.kt` 的 `ColorMode`）。
 * value 0/1/2 = 非 Monet；3/4/5/6 = Monet（跟随壁纸取色）；6 = AMOLED 纯黑。
 */
enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2),
    MONET_SYSTEM(3),
    MONET_LIGHT(4),
    MONET_DARK(5),
    DARK_AMOLED(6);

    val isSystem: Boolean get() = value == 0 || value == 3
    val isDark: Boolean get() = value == 2 || value == 5 || value == 6
    val isAmoled: Boolean get() = value == 6
    val isMonet: Boolean get() = value >= 3

    fun toNonMonetMode(): Int = when (this) {
        MONET_SYSTEM -> 0
        MONET_LIGHT -> 1
        MONET_DARK, DARK_AMOLED -> 2
        else -> value
    }

    fun toMonetMode(): Int = when (this) {
        SYSTEM -> 3
        LIGHT -> 4
        DARK -> 5
        else -> value
    }

    companion object {
        fun fromValue(value: Int) = entries.find { it.value == value } ?: SYSTEM
    }
}

/**
 * 外观设置持久化：颜色模式 / Monet 开关 / 关键色 / 配色风格 / 色规范 / 玻璃与底栏。
 * 变更即时写入 State，MainActivity 读取 → MiuixTheme 重建。
 */
object AppSettings {

    private const val PREFS = "honorhook_settings"

    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_MIUIX_MONET = "miuix_monet"
    private const val KEY_KEY_COLOR = "key_color"
    private const val KEY_COLOR_STYLE = "color_style"
    private const val KEY_COLOR_SPEC = "color_spec"
    private const val KEY_GLASS = "liquid_glass"
    private const val KEY_BAR_GLASS = "floating_bar_glass"
    private const val KEY_FLOATING_BAR = "floating_bar"

    /** 主题色板选项（照抄 KernelSU `ui/theme/Colors.kt` 的 keyColorOptions，0 = 默认）。 */
    val keyColorOptions = listOf(
        0xFFF44336.toInt(), 0xFFE91E63.toInt(), 0xFF9C27B0.toInt(), 0xFF673AB7.toInt(),
        0xFF3F51B5.toInt(), 0xFF2196F3.toInt(), 0xFF00BCD4.toInt(), 0xFF009688.toInt(),
        0xFF4FAF50.toInt(), 0xFFFFEB3B.toInt(), 0xFFFFC107.toInt(), 0xFFFF9800.toInt(),
        0xFF795548.toInt(), 0xFF607D8F.toInt(), 0xFFFF9CA8.toInt(),
    )

    private val _colorMode = mutableStateOf(ColorMode.SYSTEM)
    val colorMode: ColorMode get() = _colorMode.value

    private val _miuixMonet = mutableStateOf(false)

    /** Monet：跟随系统壁纸动态取色。 */
    val miuixMonet: Boolean get() = _miuixMonet.value

    private val _keyColor = mutableIntStateOf(0)

    /** 关键色（0 = 默认/跟随 Monet）。 */
    val keyColor: Int get() = _keyColor.value

    private val _colorStyle = mutableStateOf("TonalSpot")
    val colorStyle: String get() = _colorStyle.value

    private val _colorSpec = mutableStateOf("SPEC_2021")
    val colorSpec: String get() = _colorSpec.value

    private val _liquidGlass = mutableStateOf(true)

    /** 顶栏背景模糊总闸（KernelSU: `LocalEnableBlur`）。 */
    val liquidGlass: Boolean get() = _liquidGlass.value

    private val _floatingBarGlass = mutableStateOf(true)

    /** 底栏药丸玻璃（KernelSU: `LocalEnableFloatingBottomBarBlur`），仅在「悬浮底栏」开启时可用。 */
    val floatingBarGlass: Boolean get() = _floatingBarGlass.value

    private val _floatingBar = mutableStateOf(true)

    /** 悬浮底栏（关闭则退回贴边 NavigationBar）。 */
    val floatingBar: Boolean get() = _floatingBar.value

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val p = prefs
        _colorMode.value = ColorMode.fromValue(p?.getInt(KEY_COLOR_MODE, 0) ?: 0)
        _miuixMonet.value = p?.getBoolean(KEY_MIUIX_MONET, false) ?: false
        _keyColor.value = p?.getInt(KEY_KEY_COLOR, 0) ?: 0
        _colorStyle.value = p?.getString(KEY_COLOR_STYLE, "TonalSpot") ?: "TonalSpot"
        _colorSpec.value = p?.getString(KEY_COLOR_SPEC, "SPEC_2021") ?: "SPEC_2021"
        _liquidGlass.value = p?.getBoolean(KEY_GLASS, true) ?: true
        _floatingBarGlass.value = p?.getBoolean(KEY_BAR_GLASS, true) ?: true
        _floatingBar.value = p?.getBoolean(KEY_FLOATING_BAR, true) ?: true
    }

    fun setColorMode(mode: ColorMode) {
        _colorMode.value = mode
        prefs?.edit()?.putInt(KEY_COLOR_MODE, mode.value)?.apply()
    }

    fun setMiuixMonet(enabled: Boolean) {
        _miuixMonet.value = enabled
        // Monet 与颜色模式互斥（照 KernelSU ThemeController.getAppSettings 的转换规则）
        val cur = _colorMode.value
        _colorMode.value = ColorMode.fromValue(
            if (enabled) cur.toMonetMode() else cur.toNonMonetMode()
        )
        prefs?.edit()?.putBoolean(KEY_MIUIX_MONET, enabled)?.apply()
        prefs?.edit()?.putInt(KEY_COLOR_MODE, _colorMode.value.value)?.apply()
    }

    fun setKeyColor(argb: Int) {
        _keyColor.value = argb
        prefs?.edit()?.putInt(KEY_KEY_COLOR, argb)?.apply()
    }

    fun setColorStyle(name: String) {
        _colorStyle.value = name
        prefs?.edit()?.putString(KEY_COLOR_STYLE, name)?.apply()
    }

    fun setColorSpec(name: String) {
        _colorSpec.value = name
        prefs?.edit()?.putString(KEY_COLOR_SPEC, name)?.apply()
    }

    fun setLiquidGlass(enabled: Boolean) {
        _liquidGlass.value = enabled
        prefs?.edit()?.putBoolean(KEY_GLASS, enabled)?.apply()
    }

    fun setFloatingBarGlass(enabled: Boolean) {
        _floatingBarGlass.value = enabled
        prefs?.edit()?.putBoolean(KEY_BAR_GLASS, enabled)?.apply()
    }

    fun setFloatingBar(enabled: Boolean) {
        _floatingBar.value = enabled
        prefs?.edit()?.putBoolean(KEY_FLOATING_BAR, enabled)?.apply()
    }

    /** 关键色是否非默认（主题页决定是否显示「配色风格 / 色规范」二级项）。 */
    val keyColorCustom: Boolean get() = _keyColor.value != 0

    val paletteStyle: PaletteStyle
        get() = runCatching { PaletteStyle.valueOf(_colorStyle.value) }.getOrDefault(PaletteStyle.TonalSpot)

    val specVersion: ColorSpec.SpecVersion
        get() = runCatching { ColorSpec.SpecVersion.valueOf(_colorSpec.value) }
            .getOrDefault(ColorSpec.SpecVersion.SPEC_2021)
}