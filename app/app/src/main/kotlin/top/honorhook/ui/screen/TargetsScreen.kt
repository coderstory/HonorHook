package top.honorhook.ui.screen

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import top.honorhook.R
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 目标应用列表页。
 * 骨架阶段只有空态提示；逆向分析确定目标后按 Rain “Adapted apps” 结构填充列表
 * （每项：图标 + 包名 + 适配状态）。
 */
@Composable
fun TargetsScreen() {
    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.targets_title),
                largeTitle = stringResource(R.string.targets_title),
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
                    Column(
                        Modifier.padding(
                            horizontal = UiTokens.CardMarginH,
                            vertical = UiTokens.CardMarginV,
                        ),
                    ) {
                        Text(
                            "目标待逆向分析后添加",
                            fontSize = UiTokens.TitleSize,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            "荣耀系统目标应用待 system-files 逆向分析后确定。" +
                                "本模块 staticScope=false，可在 LSPosed Manager 中自由勾选作用域；" +
                                "目标确定后会在此列出适配清单。",
                            fontSize = UiTokens.SubtitleSize,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
            }
        }
    }
}