package top.honorhook.ui.screen

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.libxposed.service.XposedService
import top.honorhook.HookApplication
import top.honorhook.R
import top.honorhook.data.HookSettings
import top.honorhook.data.KEY_F1_VIP_ENABLED
import top.honorhook.data.KEY_F2_FREE_ENABLED
import top.honorhook.data.KEY_F3_TRIAL_ENABLED
import top.honorhook.data.KEY_F4_NO_RESET_ENABLED
import top.honorhook.data.KEY_F5_MARKET_ENABLED
import top.honorhook.data.KEY_F6_INSTALLER_PURIFY_ENABLED
import top.honorhook.data.KEY_F7_GLASS_ENABLED
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 目标应用详情页（照 LSPosed Manager 模块详情 fragment_app_list 模式，lsposed-ui-spec.md §3）：
 * 返回键 + 应用名/包名 + MainSwitchBar 主开关（theme 组 = F1-F4 组控全开/全关；
 * 其余单键目标 = 各自功能 key）+ 功能位条目列表（名称+描述+右端 miuix Checkbox，
 * 点整行 toggle；功能关时整行 alpha 0.5）。
 * 开关双写链路复用 HookSettings.setFeatureEnabled（本地 SP + 远程 prefs，service 为 null 记脏键），
 * service 取法照旧：HookApplication.service.collectAsState()。
 */
@Composable
fun TargetDetailScreen(targetId: String, onBack: () -> Unit) {
    val target = AppTargets.firstOrNull { it.targetId == targetId } ?: return
    val features by HookSettings.features.collectAsState()
    val service by HookApplication.service.collectAsState()

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            SmallTopAppBar(
                title = stringResource(target.nameRes),
                subtitle = stringResource(target.packageNameRes),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixIcons.ChevronBackward,
                            contentDescription = stringResource(R.string.tab2_back),
                        )
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
            // 主开关（item_master_switch）：theme = F1-F4 组控——全开才算开，
            // 切 true 四键全开、切 false 四键全关（四 key 各自走双写）；单键目标即对应功能 key
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    val mainChecked = target.featureKeys.all { features[it] == true }
                    SwitchPreference(
                        title = stringResource(R.string.tab2_switch_title),
                        checked = mainChecked,
                        onCheckedChange = { value ->
                            target.featureKeys.forEach { key ->
                                HookSettings.setFeatureEnabled(key, value, service)
                            }
                        },
                    )
                }
            }
            // 功能位条目（复用 item_module 形态：名称 + 描述 + 右端 checkbox，点整行 toggle）
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    target.fns.forEachIndexed { index, fn ->
                        if (index > 0) {
                            HorizontalDivider(Modifier.padding(horizontal = UiTokens.CardMarginH))
                        }
                        FeatureRow(fn = fn, checked = features[fn.key] ?: false, service = service)
                    }
                }
            }
        }
    }
}

/**
 * 功能位条目行（照 item_module 的 name/description/checkbox 三要素）：
 * 点整行 toggle；checkbox 仅作状态展示载体，自身点击也走同一 toggle（子级消费点击，
 * 不会与整行 clickable 双重触发）。功能关时整行 alpha 0.5（照规格"未启用整行 alpha=0.5"）。
 */
@Composable
private fun FeatureRow(
    fn: AppFn,
    checked: Boolean,
    service: XposedService?,
) {
    val toggle: () -> Unit = { HookSettings.setFeatureEnabled(fn.key, !checked, service) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (checked) 1f else 0.5f)
            .clickable(onClick = toggle)
            .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(fn.nameRes),
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(fn.summaryRes),
                modifier = Modifier.padding(top = 2.dp),
                fontSize = UiTokens.SubtitleSize,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(12.dp))
        Checkbox(
            state = if (checked) ToggleableState.On else ToggleableState.Off,
            onClick = { toggle() },
        )
    }
}

/** 功能位（详情页条目）：key = HookSettings 功能开关 key（恒非空，双写见 HookSettings.setFeatureEnabled）。 */
data class AppFn(
    @StringRes val nameRes: Int,
    @StringRes val summaryRes: Int,
    val key: String,
)

/**
 * 目标应用（数据驱动常量表，与 xposed/target/TargetRegistry 一一对应）。
 * 列表行 4 条固定（无空态）；[featureKeys] 为列表行/主开关的"功能开"判定 key 集合
 * （theme 组 4 键、单键目标 1 键）。
 */
data class AppTarget(
    /** 路由参数（AppRoot 注册 "target/{targetId}"，点击回调原样透传）。 */
    val targetId: String,
    /** 列表行 48dp 图标（提取的真实 app 图标：honor_ic_theme/installer/systemui/market）。 */
    @DrawableRes val iconRes: Int,
    @StringRes val nameRes: Int,
    /** 包名行；玻璃为 3 包合并展示（com.hihonor.desktop.systemui 等 3 包）。 */
    @StringRes val packageNameRes: Int,
    @StringRes val descriptionRes: Int,
    val featureKeys: List<String>,
    val fns: List<AppFn>,
)

/** 列表行/主开关"功能开"判定：theme 组 = F1-F4 任一开即可算开；单键目标 = 对应 key 开。 */
internal fun AppTarget.enabled(features: Map<String, Boolean>): Boolean =
    featureKeys.any { features[it] == true }

/**
 * UI 侧目标注册表（theme 组 4 位 + installer/glass/market 单键位；文案见 strings_tab2.xml）。
 * 功能位 key 对齐 data/HookSettings.kt KEY_F1~F7；原二期占位（锁屏玻璃，key=null）按新列表口径移除。
 */
val AppTargets: List<AppTarget> = listOf(
    AppTarget(
        targetId = "theme",
        iconRes = R.drawable.honor_ic_theme,
        nameRes = R.string.tab2_target_theme_name,
        packageNameRes = R.string.tab2_target_theme_pkg,
        descriptionRes = R.string.tab2_target_theme_desc,
        featureKeys = listOf(KEY_F1_VIP_ENABLED, KEY_F2_FREE_ENABLED, KEY_F3_TRIAL_ENABLED, KEY_F4_NO_RESET_ENABLED),
        fns = listOf(
            AppFn(R.string.tab2_fn_f1_name, R.string.tab2_fn_f1_summary, KEY_F1_VIP_ENABLED),
            AppFn(R.string.tab2_fn_f2_name, R.string.tab2_fn_f2_summary, KEY_F2_FREE_ENABLED),
            AppFn(R.string.tab2_fn_f3_name, R.string.tab2_fn_f3_summary, KEY_F3_TRIAL_ENABLED),
            AppFn(R.string.tab2_fn_f4_name, R.string.tab2_fn_f4_summary, KEY_F4_NO_RESET_ENABLED),
        ),
    ),
    AppTarget(
        targetId = "installer",
        iconRes = R.drawable.honor_ic_installer,
        nameRes = R.string.tab2_target_installer_name,
        packageNameRes = R.string.tab2_target_installer_pkg,
        descriptionRes = R.string.tab2_target_installer_desc,
        featureKeys = listOf(KEY_F6_INSTALLER_PURIFY_ENABLED),
        fns = listOf(
            AppFn(R.string.tab2_fn_f6_name, R.string.tab2_fn_f6_summary, KEY_F6_INSTALLER_PURIFY_ENABLED),
        ),
    ),
    AppTarget(
        targetId = "glass",
        iconRes = R.drawable.honor_ic_systemui,
        nameRes = R.string.tab2_target_glass_name,
        packageNameRes = R.string.tab2_target_glass_pkg,
        descriptionRes = R.string.tab2_target_glass_desc,
        featureKeys = listOf(KEY_F7_GLASS_ENABLED),
        fns = listOf(
            AppFn(R.string.tab2_fn_f7_name, R.string.tab2_fn_f7_summary, KEY_F7_GLASS_ENABLED),
        ),
    ),
    AppTarget(
        targetId = "market",
        iconRes = R.drawable.honor_ic_market,
        nameRes = R.string.tab2_target_market_name,
        packageNameRes = R.string.tab2_target_market_pkg,
        descriptionRes = R.string.tab2_target_market_desc,
        featureKeys = listOf(KEY_F5_MARKET_ENABLED),
        fns = listOf(
            AppFn(R.string.tab2_fn_f5_name, R.string.tab2_fn_f5_summary, KEY_F5_MARKET_ENABLED),
        ),
    ),
)