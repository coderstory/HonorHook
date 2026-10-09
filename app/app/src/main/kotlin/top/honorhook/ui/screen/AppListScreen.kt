package top.honorhook.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import top.honorhook.HookApplication
import top.honorhook.R
import top.honorhook.data.HookSettings
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 应用列表页（Tab2 一级，照 LSPosed Manager Modules 列表模式，demo p-hook 页）：
 * 总开关卡 + "Hook 目标"分组卡 4 行，点击进入二级功能页（[AppFunctionsScreen]）。
 */
@Composable
fun AppListScreen(onTargetClick: (String) -> Unit) {
    val hookEnabled by HookSettings.enabled.collectAsState()
    val features by HookSettings.features.collectAsState()
    val service by HookApplication.service.collectAsState()

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.app_list_title),
                largeTitle = stringResource(R.string.app_list_title),
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
            // 总开关：关闭即全部功能放行原逻辑（经 LSPosed 远程配置推送，hook 进程即时生效）
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    SwitchPreference(
                        title = "Hook 总开关",
                        summary = "关闭后全部功能恢复原逻辑",
                        checked = hookEnabled,
                        onCheckedChange = { value -> HookSettings.setEnabled(value, service) },
                        startAction = { IconBadge(MiuixIcons.Settings) },
                    )
                }
            }
            item {
                Group(stringResource(R.string.app_list_target_group)) {
                    AppTargets.forEach { target ->
                        ArrowPreference(
                            title = target.name,
                            summary = stringResource(
                                R.string.app_list_enabled_format,
                                target.enabledCount(features),
                                target.fns.size,
                            ) + " · " + target.tagline,
                            startAction = { IconBadge(target.icon) },
                            onClick = { onTargetClick(target.id) },
                        )
                    }
                }
            }
        }
    }
}

/** 图标位（照 LSPFRIDA IconBadge；internal：一级列表行与二级功能行共用）。 */
@Composable
internal fun IconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MiuixTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
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