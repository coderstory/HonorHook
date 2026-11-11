package top.honorhook.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.mutableStateOf

/**
 * 颜色模式：跟随系统 / 浅色 / 深色。
 *
 * 2026-11-08 用户「跟随壁纸取色，纯黑背景。两个开关和代码都删了」：Monet（跟随系统壁纸动态取色）
 * 与 AMOLED 纯黑两档已整体删除。历史 prefs 里可能还残留 3~6，
 * `fromValue` 负责把它们折回 0/1/2 —— 直接 `entries.find` 会让老配置掉回 SYSTEM（跟着系统乱切）。
 */
enum class ColorMode(val value: Int) {
    SYSTEM(0),
    LIGHT(1),
    DARK(2);

    val isSystem: Boolean get() = value == 0
    val isDark: Boolean get() = value == 2

    companion object {
        fun fromValue(value: Int) = when (value) {
            1, 4 -> LIGHT
            2, 5, 6 -> DARK
            else -> SYSTEM
        }
    }
}

/**
 * 外观设置持久化：颜色模式 / 玻璃与底栏 / 背景皮肤 / 萤火虫。
 * 变更即时写入 State，MainActivity 读取 → MiuixTheme 重建。
 */
object AppSettings {

    private const val PREFS = "honorhook_settings"

    private const val KEY_COLOR_MODE = "color_mode"
    private const val KEY_GLASS = "liquid_glass"
    private const val KEY_BAR_GLASS = "floating_bar_glass"
    private const val KEY_FLOATING_BAR = "floating_bar"
    private const val KEY_FIREFLY_ENABLED = "firefly_enabled"
    private const val KEY_GARDEN_SKIN = "garden_skin"
    private const val KEY_ARTWORK_BACKDROP = "artwork_backdrop"
    private const val KEY_ARTWORK_KEY_COLOR = "artwork_key_color"
    /** 默认种子 = 插画 indigo-violet（原硬编码值） */
    const val DEFAULT_ARTWORK_KEY_COLOR = 0xFF6C73E5.toInt()

    private val _colorMode = mutableStateOf(ColorMode.SYSTEM)
    val colorMode: ColorMode get() = _colorMode.value

    private val _liquidGlass = mutableStateOf(true)

    /** 顶栏背景模糊总闸（KernelSU: `LocalEnableBlur`）。 */
    val liquidGlass: Boolean get() = _liquidGlass.value

    private val _floatingBarGlass = mutableStateOf(true)

    /** 底栏药丸玻璃（KernelSU: `LocalEnableFloatingBottomBarBlur`），仅在「悬浮底栏」开启时可用。 */
    val floatingBarGlass: Boolean get() = _floatingBarGlass.value

    private val _floatingBar = mutableStateOf(true)

    /** 悬浮底栏（关闭则退回贴边 NavigationBar）。 */
    val floatingBar: Boolean get() = _floatingBar.value

    private val _fireflyEnabled = mutableStateOf(true)

    /** 首页萤火虫总开关（默认开）。 */
    val fireflyEnabled: Boolean get() = _fireflyEnabled.value

    private val _gardenSkin = mutableStateOf(false)

    /**
     * 花园篱笆背景皮肤（2026-11-08 用户要求）。
     * 与插画背景**互斥**（用户 2026-11-08 指出：两个开关不能都开，把另一个灰掉也不对）：
     * 打开这个会自动关插画；两个都关 = 纯色底。
     */
    val gardenSkin: Boolean get() = _gardenSkin.value

    private val _artworkBackdrop = mutableStateOf(true)

    /**
     * 插画背景（2026-11-08 用户给的蓝紫发角色图，见 `component/ArtworkBackdrop.kt`）。
     * 默认开；与花园背景互斥，打开这个会自动关花园（绿篱笆与紫插画同框会互相打架）。
     */
    val artworkBackdrop: Boolean get() = _artworkBackdrop.value

    /**
     * 主题主色种子。整套配色都从这一个种子生成：卡片包边、按钮、投影、启动页（2026-11-11 起
     * splash 从它派生）全部跟随。
     *
     * 2026-11-11 用户拍板「改全局主题色」：从固定常量改为可配置（主题设置页选色），默认仍是
     * 插画 indigo-violet。
     */
    private val _artworkKeyColor = mutableStateOf(DEFAULT_ARTWORK_KEY_COLOR)
    val artworkKeyColor: Int get() = _artworkKeyColor.value

    fun setArtworkKeyColor(argb: Int) {
        _artworkKeyColor.value = argb
        prefs?.edit()?.putInt(KEY_ARTWORK_KEY_COLOR, argb)?.apply()
    }

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val p = prefs
        _colorMode.value = ColorMode.fromValue(p?.getInt(KEY_COLOR_MODE, 0) ?: 0)
        _liquidGlass.value = p?.getBoolean(KEY_GLASS, true) ?: true
        _floatingBarGlass.value = p?.getBoolean(KEY_BAR_GLASS, true) ?: true
        _floatingBar.value = p?.getBoolean(KEY_FLOATING_BAR, true) ?: true
        _fireflyEnabled.value = p?.getBoolean(KEY_FIREFLY_ENABLED, true) ?: true
        _gardenSkin.value = p?.getBoolean(KEY_GARDEN_SKIN, false) ?: false
        _artworkBackdrop.value = p?.getBoolean(KEY_ARTWORK_BACKDROP, true) ?: true
        _artworkKeyColor.value = p?.getInt(KEY_ARTWORK_KEY_COLOR, DEFAULT_ARTWORK_KEY_COLOR)
            ?: DEFAULT_ARTWORK_KEY_COLOR
        // 互斥兜底：旧版本允许两个都存 true（渲染时插画赢），这里把状态收敛成互斥并回写
        if (_artworkBackdrop.value && _gardenSkin.value) {
            _gardenSkin.value = false
            prefs?.edit()?.putBoolean(KEY_GARDEN_SKIN, false)?.apply()
        }
    }

    fun setColorMode(mode: ColorMode) {
        _colorMode.value = mode
        prefs?.edit()?.putInt(KEY_COLOR_MODE, mode.value)?.apply()
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

    fun setFireflyEnabled(enabled: Boolean) {
        _fireflyEnabled.value = enabled
        prefs?.edit()?.putBoolean(KEY_FIREFLY_ENABLED, enabled)?.apply()
    }

    fun setGardenSkin(enabled: Boolean) {
        _gardenSkin.value = enabled
        // 互斥：开花园即关插画；关花园不动插画（关掉后两个都关 = 纯色底）
        if (enabled) _artworkBackdrop.value = false
        prefs?.edit()
            ?.putBoolean(KEY_GARDEN_SKIN, enabled)
            ?.putBoolean(KEY_ARTWORK_BACKDROP, _artworkBackdrop.value)
            ?.apply()
    }

    fun setArtworkBackdrop(enabled: Boolean) {
        _artworkBackdrop.value = enabled
        // 互斥：开插画即关花园；关插画不动花园（关掉后两个都关 = 纯色底）
        if (enabled) _gardenSkin.value = false
        prefs?.edit()
            ?.putBoolean(KEY_ARTWORK_BACKDROP, enabled)
            ?.putBoolean(KEY_GARDEN_SKIN, _gardenSkin.value)
            ?.apply()
    }
}
