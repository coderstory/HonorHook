package top.honorhook.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.honorhook.FrameworkState
import top.honorhook.R
import top.honorhook.data.HookSettings
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.component.UiTokens
import top.honorhook.xposed.target.TargetRegistry
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页 Module 页（对齐 Rain Module 页，docs/rain-ui-layout.md §2）：
 * 模块状态卡 + 适配目标计数卡 + 启用功能计数卡。
 * - 状态卡两态（Rain 三态中的 "Not connected / Working"；"Read failed" 态需读取框架信息，
 *   本项目无该读取路径，不做）；
 * - 适配卡数值 = TargetRegistry 静态注册目标数；
 * - 启用卡数值 = HookSettings F1-F4 开启数（n/4，照 FrameworkState 的 StateFlow + collectAsState 模式）。
 */
@Composable
fun HomeScreen() {
    val active by FrameworkState.active.collectAsState()
    val apiVersion by FrameworkState.apiVersion.collectAsState()
    val features by HookSettings.features.collectAsState()
    val adaptedCount = remember { registeredTargetCount() }
    val enabledFeatureCount = features.count { it.value }

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = stringResource(R.string.app_name), largeTitle = stringResource(R.string.app_name)) },
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
                    StatusCard(active, apiVersion)
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    CountCard(
                        icon = MiuixIcons.Layers,
                        title = stringResource(R.string.home_adapted_count_title),
                        count = adaptedCount.toString(),
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    CountCard(
                        icon = MiuixIcons.SelectAll,
                        title = stringResource(R.string.home_enabled_count_title),
                        count = stringResource(R.string.home_enabled_count_format, enabledFeatureCount),
                    )
                }
            }
        }
    }
}

/**
 * 模块状态卡（照 Rain 激活态真机截图规格：浅绿背景大圆角卡 + 右侧 44dp 大圆形对勾 + 左侧三行文字，
 * 配色 #E8F5E9 浅绿底 / #4CAF50 大绿对勾 / #1B5E20 深绿文字，Material Green 50/500/900 同系）。
 * - 激活：浅绿卡底 + 绿色大对勾（矢量自带 fillColor #4CAF50）+ 深绿文字；
 * - 未激活：默认卡底 + 灰色对勾 + 中性文字（对勾 tint 覆盖矢量色）；
 * - 三行文字 = 状态标题 / summary / 动作文本（Rain module_status_card 结构，ck0.java:896-909）；
 * - 对勾矢量 honor_ic_check_circle 源自 LSPosed Manager ic_round_check_circle_24；
 * - 状态卡点击彩蛋（Rain unlockHiddenApps）不做。
 */
@Composable
private fun StatusCard(active: Boolean, apiVersion: Int) {
    val title = stringResource(
        if (active) R.string.home_status_title_active else R.string.home_status_title_inactive,
    )
    val summary = if (active) {
        stringResource(R.string.home_status_summary_active, apiVersion)
    } else {
        stringResource(R.string.home_status_summary_inactive)
    }
    val action = stringResource(
        if (active) R.string.home_status_action_active else R.string.home_status_action_inactive,
    )
    val textGreen = colorResource(R.color.honor_status_text)
    val titleColor = if (active) textGreen else MiuixTheme.colorScheme.onSurface
    val summaryColor = if (active) textGreen.copy(alpha = 0.72f) else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val actionColor = if (active) textGreen else MiuixTheme.colorScheme.onSurfaceVariantSummary
    val checkTint = if (active) Color.Unspecified else MiuixTheme.colorScheme.onSurfaceVariantSummary
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = UiTokens.CardRadius,
        colors = CardDefaults.defaultColors(
            color = if (active) colorResource(R.color.honor_status_bg) else MiuixTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(
            Modifier.padding(
                horizontal = UiTokens.CardMarginH,
                vertical = UiTokens.CardMarginV,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    fontSize = UiTokens.TitleSize,
                    fontWeight = FontWeight.Medium,
                    color = titleColor,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    summary,
                    fontSize = UiTokens.SubtitleSize,
                    color = summaryColor,
                )
                Spacer(Modifier.height(8.dp))
                // 动作文本（Rain 三态卡的动作列；本模块只做激活/未激活两态）
                Text(
                    action,
                    fontSize = UiTokens.SubtitleSize,
                    fontWeight = FontWeight.Medium,
                    color = actionColor,
                )
            }
            Spacer(Modifier.width(UiTokens.CardMarginH))
            Icon(
                imageVector = ImageVector.vectorResource(R.drawable.honor_ic_check_circle),
                contentDescription = null,
                modifier = Modifier.size(44.dp),
                tint = checkTint,
            )
        }
    }
}

/**
 * 计数卡（Rain 计数卡样式，ck0.i/t0 模式：图标(16dp) + title + 大数字，无副标题时数字居中）。
 */
@Composable
private fun CountCard(icon: ImageVector, title: String, count: String) {
    Column(
        Modifier.padding(
            horizontal = UiTokens.CardMarginH,
            vertical = UiTokens.CardMarginV,
        ),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.width(UiTokens.CardMarginH))
            Text(
                title,
                fontSize = UiTokens.SubtitleSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            count,
            modifier = Modifier.fillMaxWidth(),
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 已注册 hook 目标数（适配目标计数卡数值）。
 * 数值来自 [TargetRegistry]（静态注册表：init 内 register(ThemeStoreHook) 等目标）。
 * 本次重构红线禁止改动 xposed/，且 TargetRegistry 无公开 size API，
 * 故对照其 private targets 反射读取（本工程 minify 关闭、字段名稳定；加载安全：
 * TargetRegistry/<init> 只含字符串与空集合初始化，libxposed API 类在 install() 内才引用）。
 * 日后 xposed 侧若提供公开访问器，此处可换直读。
 */
private fun registeredTargetCount(): Int = runCatching {
    val clazz = TargetRegistry::class.java
    val instance = clazz.getDeclaredField("INSTANCE").apply { isAccessible = true }[null]
    val targets = clazz.getDeclaredField("targets").apply { isAccessible = true }[instance] as Map<*, *>
    targets.size
}.getOrDefault(0)