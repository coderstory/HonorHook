package top.honorhook.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.honorhook.ui.AppSettings
import top.honorhook.ui.component.LocalEnableBlur
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 设置 Tab —— **照抄 KernelSU `SettingsMiuix.kt`**：
 * ```
 * Scaffold(topBar = TopAppBar("设置", scrollBehavior)) {
 *   LazyColumn(padding(horizontal = 12.dp), contentPadding = innerPadding) {
 *     Card(padding(top = 12.dp)) { SwitchPreference(...) ; ArrowPreference(主题设置) }
 *     Card(padding(top = 12.dp)) { ArrowPreference(运行日志) ; ArrowPreference(关于) }
 *     Spacer(bottomInnerPadding)
 *   }
 * }
 * ```
 * 注意：**没有「界面风格」项**——UI 固定 Miuix 单模式。
 */
@Composable
fun SettingsScreen(bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    var page by remember { mutableStateOf<Page?>(null) }
    BackHandler(enabled = page != null) { page = null }
    when (page) {
        Page.THEME -> ThemeSettingsPage(onBack = { page = null }, bottomInnerPadding = bottomInnerPadding)
        Page.ABOUT -> AboutPage(onBack = { page = null }, bottomInnerPadding = bottomInnerPadding)
        else -> SettingsMain(
            bottomInnerPadding = bottomInnerPadding,
            onOpenTheme = { page = Page.THEME },
            onOpenAbout = { page = Page.ABOUT },
        )
    }
}

private enum class Page { THEME, ABOUT }

@Composable
private fun SettingsMain(
    bottomInnerPadding: androidx.compose.ui.unit.Dp,
    onOpenTheme: () -> Unit,
    onOpenAbout: () -> Unit,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val glass = AppSettings.liquidGlass

    Scaffold(
        topBar = {
            TopAppBar(
                title = "设置",
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    // 第一组：外观
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = "主题设置",
                            summary = "主题色、深浅色与液态玻璃",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Palette,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "主题设置",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = onOpenTheme,
                        )
                    }

                    // 第二组：关于
                    Card(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .fillMaxWidth(),
                    ) {
                        ArrowPreference(
                            title = "运行日志",
                            startAction = {
                                Icon(
                                    Icons.Rounded.BugReport,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "运行日志",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = {},
                        )
                        ArrowPreference(
                            title = "关于",
                            startAction = {
                                Icon(
                                    Icons.Rounded.Info,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "关于",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            onClick = onOpenAbout,
                        )
                    }

                    Spacer(
                        Modifier.height(
                            bottomInnerPadding +
                                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp
                        )
                    )
                }
            }
        }
    }
}

/** 关于页（照 KernelSU AboutMiuix：Card + ArrowPreference 信息行）。 */
@Composable
private fun AboutPage(
    onBack: () -> Unit,
    bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = "关于",
                navigationIcon = { MiuixBackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxHeight()
                .scrollEndHaptic()
                .overScrollVertical()
                .padding(horizontal = 12.dp),
            contentPadding = innerPadding,
            overscrollEffect = null,
        ) {
            item {
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    ArrowPreference(
                        title = "Re:荣耀",
                        summary = "版本 ${appVersionLabel()}",
                        onClick = {},
                    )
                    ArrowPreference(
                        title = "出品",
                        summary = "©2027 CoderStory 出品",
                        onClick = {},
                    )
                    ArrowPreference(
                        title = "第三方库",
                        summary = "LSPosed API · Compose · Miuix · DexKit",
                        onClick = {},
                    )
                }
                Spacer(
                    Modifier.height(
                        bottomInnerPadding +
                                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() + 12.dp
                    )
                )
            }
        }
    }
}