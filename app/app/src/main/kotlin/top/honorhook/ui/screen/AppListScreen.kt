package top.honorhook.ui.screen

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.honorhook.R
import top.honorhook.data.HookSettings
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 目标应用列表页（Tab2，照 LSPosed Manager Modules 列表 fragment_pager 模式，lsposed-ui-spec.md §3）：
 * 大标题"功能"（折叠栏静态大标题形态）+ 副标题"N 个功能已启用"（N = HookSettings.features 开启计数）
 * + 竖向 4 条目标应用（item_module 形态：48dp 真实 app 图标 + 标题 16sp + 包名行 + 描述行，
 * 右端 LSPosed 版本位展示"已启用/未启用"小字；整行 alpha：功能开=1.0/关=0.5）。
 * 点整行 → onNavigateToTarget(targetId)（AppRoot 注册 "target/{targetId}" 路由，本页只管回调）。
 * 恒 4 条，无空态；条目无开关（开关在详情页 MainSwitchBar，本页状态只读）。
 */
@Composable
fun ModulesScreen(onNavigateToTarget: (String) -> Unit) {
    val features by HookSettings.features.collectAsState()
    val enabledCount = features.count { it.value }

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.tab2_modules_title),
                largeTitle = stringResource(R.string.tab2_modules_title),
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
            // 副标题（照 LSPosed fragment_pager 工具栏 subtitle："N module(s) enabled"）
            item {
                Text(
                    text = stringResource(R.string.tab2_modules_subtitle, enabledCount),
                    modifier = Modifier.padding(start = 4.dp, bottom = 2.dp),
                    fontSize = UiTokens.SubtitleSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    AppTargets.forEachIndexed { index, target ->
                        if (index > 0) {
                            HorizontalDivider(Modifier.padding(horizontal = UiTokens.CardMarginH))
                        }
                        ModuleRow(
                            target = target,
                            enabled = target.enabled(features),
                            onClick = { onNavigateToTarget(target.targetId) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * 目标应用行（照 item_module.xml：48×48 图标 + marginStart=20dp 标题（16sp, ellipsize=marquee）
 * + 包名行/描述行（secondary 弱化色，maxLines 1/2）+ 右端"已启用/未启用"小字（LSPosed 版本位）；
 * 条目 padding 上下 16dp/左 16dp/右 24dp；整行 alpha = 功能开 1.0 / 关 0.5（照规格）。
 */
@Composable
private fun ModuleRow(
    target: AppTarget,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(onClick = onClick)
            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 24.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Image(
            painter = painterResource(target.iconRes),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 20.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    text = stringResource(target.nameRes),
                    modifier = Modifier.weight(1f),
                    fontSize = 16.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // LSPosed 版本位：此处展示功能开关态小字
                Text(
                    text = stringResource(
                        if (enabled) R.string.tab2_target_status_on else R.string.tab2_target_status_off,
                    ),
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                    fontSize = UiTokens.CaptionSize,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            }
            Text(
                text = stringResource(target.packageNameRes),
                modifier = Modifier.padding(top = 4.dp),
                fontSize = UiTokens.SubtitleSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(target.descriptionRes),
                modifier = Modifier.padding(top = 2.dp),
                fontSize = UiTokens.SubtitleSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}