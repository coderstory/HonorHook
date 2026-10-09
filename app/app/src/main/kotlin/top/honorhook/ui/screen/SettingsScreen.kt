package top.honorhook.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import top.honorhook.BuildConfig
import top.honorhook.R
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference

/**
 * 设置页（对齐 Rain Settings 页，docs/rain-ui-layout.md §4 y92.f）：
 * 只保留入口行（主题设置 / 关于），具体开关全部收进二级页
 * （主题设置 → ThemeSettingsScreen；关于 → AboutScreen）。
 */
@Composable
fun SettingsScreen(
    onNavigateToThemeSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                largeTitle = stringResource(R.string.settings_title),
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
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    ArrowPreference(
                        title = stringResource(R.string.settings_theme_entry),
                        summary = stringResource(R.string.settings_theme_entry_summary),
                        onClick = onNavigateToThemeSettings,
                    )
                    ArrowPreference(
                        title = stringResource(R.string.settings_about_entry),
                        summary = stringResource(R.string.settings_about_entry_summary, BuildConfig.VERSION_NAME),
                        onClick = onNavigateToAbout,
                    )
                }
            }
        }
    }
}