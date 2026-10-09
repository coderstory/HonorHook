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
 * 主题设置二级页（对齐 Rain Theme settings / HonorHook-ui-demo.html p-theme 结构：
 * 卡 1=其他（Rain 三项：悬浮底栏/跟随系统取色/隐藏桌面图标），卡 2=外观（manager 主题组：
 * 暗色模式三态单选 + 主题色色板 + 纯黑模式开关））。
 * 1. 悬浮底栏开关（HookSettings key 持久化 + uiToggles StateFlow 驱动 AppRoot 双形态渲染：
 *    开 = FloatingMiuixNavigationBar 玻璃胶囊悬浮，关 = miuix 原生 NavigationBar 贴底通栏）；
 * 2. 跟随系统取色开关（ThemeModeStore.wallpaperMonet：M3 dynamicColorScheme 壁纸主色作种子，
 *    优先于手选色板，MainActivity 构建链即时生效）；
 * 3. 隐藏桌面图标开关（照 Rain setComponentEnabledSetting 模式，o1.java:707-717）；
 * 4. 外观组：暗色模式三态（RadioButtonPreference 照现有模式）+ 主题色色板（照 LSPFRIDA
 *    SettingsScreenV093 ThemeColorDot 圆点行，12 色对齐 demo 精选 + 默认项）
 *    + 纯黑模式开关（ThemeModeStore.pureBlack，暗色下生效，渲染见 MainActivity 覆盖层）。
 */
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
    val themeMode by ThemeModeStore.mode.collectAsState()
    val wallpaperMonet by ThemeModeStore.wallpaperMonet.collectAsState()
    val pureBlack by ThemeModeStore.pureBlack.collectAsState()
    val keyColorHex by ThemeModeStore.keyColor.collectAsState()
    val service by HookApplication.service.collectAsState()
    val uiToggles by HookSettings.uiToggles.collectAsState()

    // 隐藏桌面图标：实际状态以 PackageManager 为准（Rain vv0.l 同源：PM 状态派生的 StateFlow），HookSettings key 为持久化记录
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
                        // 渲染切换：AppRoot 收集 HookSettings.uiToggles 读取本 key，开关变化即时切换
                        // 悬浮胶囊（FloatingMiuixNavigationBar）/ 贴底通栏双形态（组件照 LSPFRIDA
                        // MiuixComponents.kt:105-220 玻璃胶囊 + pill 滑动指示器）。
                    )
                    SwitchPreference(
                        title = stringResource(R.string.theme_settings_wallpaper_monet),
                        summary = stringResource(R.string.theme_settings_wallpaper_monet_summary),
                        checked = wallpaperMonet,
                        onCheckedChange = { ThemeModeStore.setWallpaperMonet(it) },
                        // 跟随系统取色：接 ThemeModeStore.wallpaperMonet（demo p-theme 卡 1 第 2 行）；
                        // 开启后 MainActivity dynamicLightColorScheme 取壁纸主色作种子，优先于手选色板。
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
                // 外观组（manager 主题组四项：暗色模式/主题色/纯黑模式）
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
                    // 主题色色板（照 LSPFRIDA SettingsScreenV093 L141-165：标题 + 圆点行；
                    // 12 色精选对齐 demo COLORS，色名对齐 manager ThemeUtil 19 色）
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
                    // 12 色分两行（6/6）；默认项放在第一行最前（照 LSPFRIDA 默认圆点 + 色点并排）
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
                        // 纯黑模式：接 ThemeModeStore.pureBlack（demo p-theme 外观组第 3 行），
                        // 暗色下生效——MainActivity MiuixTheme(colors) 覆盖层黑化背景色槽（见其注释）。
                    )
                }
            }
        }
    }
}

/**
 * 隐藏/显示桌面图标（照 Rain o1.java:707-717 的 setComponentEnabledSetting 模式，
 * 参数实证：状态 disabled=2 / enabled=1，flags=DONT_KILL_APP=1）。
 * 隐藏的是 LAUNCHER alias（AndroidManifest 的 .LauncherActivity），
 * LSPosed 模块设置入口（.MainActivity MODULE_SETTINGS）不受影响，
 * 因此隐藏后仍可从 LSPosed Manager 打开本页面。
 */
private fun applyIconHidden(pm: PackageManager, componentName: ComponentName, hidden: Boolean) {
    pm.setComponentEnabledSetting(
        componentName,
        if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP,
    )
}

/** 图标是否处于隐藏态（PM 判定；未设置过（DEFAULT）视为未隐藏）。 */
private fun isIconHidden(pm: PackageManager, componentName: ComponentName): Boolean =
    pm.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

/** 主题模式单选行（从 SettingsScreen 搬入，照 LSPFRIDA SettingsScreenV093 ThemeModeOption）。 */
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

/**
 * 预设主题色（12 色精选，对齐 HonorHook-ui-demo.html COLORS 色值；
 * 色名对齐 manager ThemeUtil.java:48-66 的 19 色：红/粉/紫/深紫/靛蓝/青/绿/橙/琥珀/灰蓝，
 * 默认蓝/茶为 demo 自定义）。Hex 带 # 前缀（ThemeModeStore.keyColor 文档格式）。
 */
private val PRESET_KEY_COLORS: List<Pair<String, String>> = listOf(
    "默认蓝" to "#3E7BFA", "红" to "#E5484D", "粉" to "#E5599A", "紫" to "#8E4EC6",
    "深紫" to "#6E56CF", "靛蓝" to "#3E63DD", "青" to "#12A594", "绿" to "#29A383",
    "橙" to "#F76B15", "琥珀" to "#FFB224", "茶" to "#9D6B53", "灰蓝" to "#4C5AAB",
)

/** 主题色圆点（hex=null 为默认项）；选中描边 primary；默认项中心小点标记（照 LSPFRIDA S121-129）。 */
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

/** 设置分组：分组标题 + 卡片容器（照 HC MainActivity Group 模式简化，圆角用 UiTokens）。 */
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