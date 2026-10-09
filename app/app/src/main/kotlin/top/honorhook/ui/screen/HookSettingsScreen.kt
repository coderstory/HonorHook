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
import io.github.libxposed.service.XposedService
import top.honorhook.HookApplication
import top.honorhook.R
import top.honorhook.data.HookSettings
import top.honorhook.data.KEY_F1_VIP_ENABLED
import top.honorhook.data.KEY_F2_FREE_ENABLED
import top.honorhook.data.KEY_F3_TRIAL_ENABLED
import top.honorhook.data.KEY_F4_NO_RESET_ENABLED
import top.honorhook.data.KEY_F6_INSTALLER_PURIFY_ENABLED
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Unlock
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Hook 设置页：功能分组（Hook 总开关 + F1-F4/F6 功能位独立开关，HookSettings 驱动）。
 * 行结构对齐 Rain Apps 页行（rain-ui-layout.md §3 y92.b）：图标位 + 标题 + summary + 右侧开关。
 * 页面骨架照 HomeScreen 模式（Scaffold + TopAppBar + LazyColumn + UiTokens 间距）。
 */
@Composable
fun HookSettingsScreen() {
    val hookEnabled by HookSettings.enabled.collectAsState()
    val features by HookSettings.features.collectAsState()
    val service by HookApplication.service.collectAsState()

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.hook_settings_title),
                largeTitle = stringResource(R.string.hook_settings_title),
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
                Group("功能") {
                    SwitchPreference(
                        title = "Hook 总开关",
                        summary = "总开关（上位语义）：总关 = 全部功能放行原逻辑；经 LSPosed 远程配置推送，hook 进程监听变化即时生效",
                        checked = hookEnabled,
                        onCheckedChange = { value -> HookSettings.setEnabled(value, service) },
                        startAction = { IconBadge(MiuixIcons.Settings) },
                    )
                    FeatureSwitch(
                        "开启 VIP",
                        "会员状态判定全部改为 VIP（到期/状态判定 → 永久有效）",
                        KEY_F1_VIP_ENABLED, features, service,
                        icon = MiuixIcons.Unlock,
                    )
                    FeatureSwitch(
                        "全部资源免费",
                        "付费判定全部放行、价格归零、购买/VIP 按钮隐藏",
                        KEY_F2_FREE_ENABLED, features, service,
                        icon = MiuixIcons.Store,
                    )
                    FeatureSwitch(
                        "试用无倒计时",
                        "试用不因时间/次数到期而中断",
                        KEY_F3_TRIAL_ENABLED, features, service,
                        icon = MiuixIcons.Timer,
                    )
                    FeatureSwitch(
                        "永不恢复默认",
                        "授权校验失败/试用结束等一切回退路径全部阻断",
                        KEY_F4_NO_RESET_ENABLED, features, service,
                        icon = MiuixIcons.Lock,
                    )
                    FeatureSwitch(
                        "安装器净化",
                        "跳过联网安全检测与安装前指纹、禁止安装器联网、隐藏安全卡片/广告横幅。安全敏感：默认关闭——跳过检测会削弱系统对恶意应用的拦截，请仅对来源可信的 APK 开启",
                        KEY_F6_INSTALLER_PURIFY_ENABLED, features, service,
                        icon = MiuixIcons.Blocklist,
                    )
                }
            }
        }
    }
}

/**
 * 功能位独立开关（中文标题照 docs/honorhook-theme-requirements.md 功能表 / docs/honor-plugins-integration.md §5.2）：
 * checked 读 [HookSettings.features]（可观察，与总开关同模式），变更走
 * [HookSettings.setFeatureEnabled] 双写（本地 SP + 远程 prefs；总开关仍是上位语义）。
 */
@Composable
private fun FeatureSwitch(
    title: String,
    summary: String,
    key: String,
    features: Map<String, Boolean>,
    service: XposedService?,
    icon: ImageVector,
) {
    SwitchPreference(
        title = title,
        summary = summary,
        checked = features[key] ?: true,
        onCheckedChange = { value -> HookSettings.setFeatureEnabled(key, value, service) },
        startAction = { IconBadge(icon) },
    )
}

/** 图标位（Rain Apps 行结构：左侧图标；36dp 圆底 surfaceVariant + 20dp primary 图标，照 LSPFRIDA IconBadge）。 */
@Composable
private fun IconBadge(icon: ImageVector) {
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