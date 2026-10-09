package top.honorhook.ui.screen

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.honorhook.HookApplication
import top.honorhook.R
import top.honorhook.data.HookSettings
import top.honorhook.data.KEY_FLOATING_BOTTOM_BAR_ENABLED
import top.honorhook.data.KEY_HIDE_LAUNCHER_ICON_ENABLED
import top.honorhook.data.ThemeModeStore
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 主题设置二级页（对齐 Rain p-theme / demo p-theme 结构）：卡 1 = 其他（悬浮底栏 /
 * 跟随系统取色 / 隐藏桌面图标），卡 2 = 外观（暗色三态 + 主题色色板 + 纯黑）。
 * 各开关机制见下方对应行注释。
 */
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
    val themeMode by ThemeModeStore.mode.collectAsState()
    val wallpaperMonet by ThemeModeStore.wallpaperMonet.collectAsState()
    val pureBlack by ThemeModeStore.pureBlack.collectAsState()
    val keyColorHex by ThemeModeStore.keyColor.collectAsState()
    val service by HookApplication.service.collectAsState()
    val uiToggles by HookSettings.uiToggles.collectAsState()

    // 图标隐藏态以 PackageManager 为准，HookSettings key 仅作持久化记录
    val context = LocalContext.current
    val launcherAlias = remember { ComponentName(context, "top.honorhook.LauncherActivity") }
    val packageManager = remember { context.packageManager }
    var iconHidden by remember { mutableStateOf(isIconHidden(packageManager, launcherAlias)) }

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            SmallTopAppBar(
                title = stringResource(R.string.theme_settings_title),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.ChevronBackward, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = UiTokens.PagePadding),
            verticalArrangement = Arrangement.spacedBy(UiTokens.CardSpacing),
            contentPadding = PaddingValues(bottom = UiTokens.PagePadding),
        ) {
            item {
                Group(stringResource(R.string.theme_settings_other_group)) {
                    SwitchPreference(
                        title = stringResource(R.string.theme_settings_floating_bar),
                        summary = stringResource(R.string.theme_settings_floating_bar_summary),
                        checked = uiToggles[KEY_FLOATING_BOTTOM_BAR_ENABLED] ?: false,
                        onCheckedChange = { value ->
                            HookSettings.setUiToggle(KEY_FLOATING_BOTTOM_BAR_ENABLED, value, service)
                        },
                        // AppRoot 订阅本 key 即时切换悬浮/贴底双形态（组件照 LSPFRIDA MiuixComponents.kt:105-220）
                    )
                    SwitchPreference(
                        title = stringResource(R.string.theme_settings_wallpaper_monet),
                        summary = stringResource(R.string.theme_settings_wallpaper_monet_summary),
                        checked = wallpaperMonet,
                        onCheckedChange = { ThemeModeStore.setWallpaperMonet(it) },
                        // 开启后 MainActivity 以壁纸主色为 dynamicColorScheme 种子，优先于手选色板
                    )
                    SwitchPreference(
                        title = stringResource(R.string.theme_settings_hide_icon),
                        summary = stringResource(R.string.theme_settings_hide_icon_summary),
                        checked = iconHidden,
                        onCheckedChange = { value ->
                            applyIconHidden(packageManager, launcherAlias, value)
                            HookSettings.setUiToggle(KEY_HIDE_LAUNCHER_ICON_ENABLED, value, service)
                            iconHidden = value
                        },
                    )
                }
            }
            item {
                Group(stringResource(R.string.theme_settings_appearance_group)) {
                    ThemeModeOption(
                        stringResource(R.string.theme_settings_color_system),
                        stringResource(R.string.theme_settings_color_system_summary),
                        ColorSchemeMode.System, themeMode,
                    )
                    ThemeModeOption(
                        stringResource(R.string.theme_settings_color_light),
                        stringResource(R.string.theme_settings_color_light_summary),
                        ColorSchemeMode.Light, themeMode,
                    )
                    ThemeModeOption(
                        stringResource(R.string.theme_settings_color_dark),
                        stringResource(R.string.theme_settings_color_dark_summary),
                        ColorSchemeMode.Dark, themeMode,
                    )
                    // 色板（照 LSPFRIDA SettingsScreenV093 圆点行）
                    Text(
                        stringResource(R.string.theme_settings_key_color),
                        fontSize = UiTokens.SubtitleSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    )
                    Text(
                        stringResource(R.string.theme_settings_key_color_summary),
                        fontSize = UiTokens.CaptionSize,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp),
                    )
                    PRESET_KEY_COLORS.chunked(6).forEachIndexed { rowIndex, rowColors ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        ) {
                            if (rowIndex == 0) {
                                ThemeColorDot(
                                    hex = null,
                                    selected = keyColorHex == null,
                                    onClick = { ThemeModeStore.setKeyColor(null) },
                                )
                            }
                            rowColors.forEach { (_, hex) ->
                                ThemeColorDot(
                                    hex = hex,
                                    selected = keyColorHex == hex,
                                    onClick = { ThemeModeStore.setKeyColor(if (keyColorHex == hex) null else hex) },
                                )
                            }
                        }
                    }
                    SwitchPreference(
                        title = stringResource(R.string.theme_settings_pure_black),
                        summary = stringResource(R.string.theme_settings_pure_black_summary),
                        checked = pureBlack,
                        onCheckedChange = { ThemeModeStore.setPureBlack(it) },
                        // 暗色下生效：MainActivity MiuixTheme 覆盖层黑化背景色槽（见其注释）
                    )
                }
            }
        }
    }
}

/**
 * 隐藏/显示桌面图标（照 Rain o1.java:707-717 setComponentEnabledSetting 模式，
 * 参数实证：disabled=2 / enabled=1 / DONT_KILL_APP=1）。
 * 只隐藏 LAUNCHER alias，模块设置入口（.MainActivity）不受影响——隐藏后仍可从 LSPosed Manager 打开。
 */
private fun applyIconHidden(pm: PackageManager, componentName: ComponentName, hidden: Boolean) {
    pm.setComponentEnabledSetting(
        componentName,
        if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
}

private fun isIconHidden(pm: PackageManager, componentName: ComponentName): Boolean =
    pm.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

/** 主题模式单选行（照 LSPFRIDA SettingsScreenV093 ThemeModeOption）。 */
@Composable
private fun ThemeModeOption(
    title: String,
    summary: String,
    option: ColorSchemeMode,
    current: ColorSchemeMode,
) {
    RadioButtonPreference(
        title = title,
        summary = summary,
        selected = current == option,
        onClick = { ThemeModeStore.set(option) },
    )
}

/** 预设主题色（12 色精选：色值对齐 demo COLORS，色名对齐 manager ThemeUtil 19 色；
 * 默认蓝/茶为 demo 自定义；Hex 带 # 前缀与 ThemeModeStore 格式一致）。 */
private val PRESET_KEY_COLORS: List<Pair<String, String>> = listOf(
    "默认蓝" to "#3E7BFA", "红" to "#E5484D", "粉" to "#E5599A", "紫" to "#8E4EC6",
    "深紫" to "#6E56CF", "靛蓝" to "#3E63DD", "青" to "#12A594", "绿" to "#29A383",
    "橙" to "#F76B15", "琥珀" to "#FFB224", "茶" to "#9D6B53", "灰蓝" to "#4C5AAB",
)

/** 主题色圆点（hex=null 为默认项；照 LSPFRIDA S121-129）。 */
@Composable
private fun ThemeColorDot(hex: String?, selected: Boolean, onClick: () -> Unit) {
    val dotColor = if (hex != null) {
        Color(runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(0xFF6750A4.toInt()))
    } else {
        Color(0xFF6750A4)
    }
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (hex == null) MiuixTheme.colorScheme.surfaceVariant else dotColor)
            .then(
                if (selected) Modifier.border(2.dp, MiuixTheme.colorScheme.primary, CircleShape)
                else Modifier.border(1.dp, MiuixTheme.colorScheme.outline, CircleShape)
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (hex == null) {
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(MiuixTheme.colorScheme.onSurfaceVariantSummary)
            )
        }
    }
}

/** 设置分组（照 HC MainActivity Group 模式）。 */
@Composable
private fun Group(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp, bottom = 4.dp),
            fontSize = UiTokens.SubtitleSize,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
        Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
            content()
        }
    }
}