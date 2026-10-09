package top.honorhook.ui.screen

import android.content.ComponentName
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
 * 主题设置二级页（对齐 Rain Theme settings，docs/rain-ui-layout.md §5 ck0.j）：
 * 1. Color Mode 三态（从 SettingsScreen 搬入，主题即时生效链路不变）；
 * 2. 悬浮底栏开关（HookSettings key 持久化；实际悬浮渲染切换 TODO 后续实现）；
 * 3. 隐藏桌面图标开关（照 Rain setComponentEnabledSetting 模式，o1.java:707-717）。
 */
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
    val themeMode by ThemeModeStore.mode.collectAsState()
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
                Group(stringResource(R.string.theme_settings_color_mode_group)) {
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
                }
            }
            item {
                Group(stringResource(R.string.theme_settings_other_group)) {
                    SwitchPreference(
                        title = stringResource(R.string.theme_settings_floating_bar),
                        summary = stringResource(R.string.theme_settings_floating_bar_summary),
                        checked = uiToggles[KEY_FLOATING_BOTTOM_BAR_ENABLED] ?: false,
                        onCheckedChange = { value ->
                            HookSettings.setUiToggle(KEY_FLOATING_BOTTOM_BAR_ENABLED, value, service)
                        },
                        // TODO 悬浮底栏渲染切换未实现（2026-10 只做开关 + 持久化）：
                        // 后续照 LSPFRIDA MiuixComponents.kt:105-159 FloatingMiuixNavigationBar
                        // （玻璃胶囊 + 12dp 底部悬浮，rain-ui-layout.md §1 h70:100-104 同语义）
                        // 或 miuix 0.9.3 原生 FloatingNavigationBar 接入底栏渲染。
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