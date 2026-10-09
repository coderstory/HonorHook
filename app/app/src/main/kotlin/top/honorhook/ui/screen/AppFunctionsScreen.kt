package top.honorhook.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Blocklist
import top.yukonga.miuix.kmp.icon.extended.ChevronBackward
import top.yukonga.miuix.kmp.icon.extended.FileDownloads
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Promotions
import top.yukonga.miuix.kmp.icon.extended.Store
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Timer
import top.yukonga.miuix.kmp.icon.extended.Unlock
import top.yukonga.miuix.kmp.preference.SwitchPreference

/**
 * 应用功能二级页（照 LSPosed Manager 模块详情模式，demo p-appfn 页）：
 * TopAppBar（返回 + 应用名）+ 该应用的功能开关列表（数据驱动 [AppTargets]）。
 *
 * 每个功能位一行：badge 图标 + 名称 + summary + 右侧开关（照 Rain Apps 行结构）。
 * - [AppFn.key] 非空 = 真实功能位，开关读写 [HookSettings] features（key 双写本地+远程，同旧 HookSettingsScreen）；
 * - [AppFn.key] 为 null = 未实现占位（市场净化「整合中」/ 锁屏玻璃「二期」）——开关禁用不亮、不持久化。
 */
@Composable
fun AppFunctionsScreen(targetId: String, onBack: () -> Unit) {
    val target = AppTargets.firstOrNull { it.id == targetId } ?: return
    val features by HookSettings.features.collectAsState()
    val service by HookApplication.service.collectAsState()

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        topBar = {
            SmallTopAppBar(
                title = target.name,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(MiuixIcons.ChevronBackward, contentDescription = stringResource(R.string.appfn_back))
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
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    target.fns.forEach { fn ->
                        FeatureSwitch(fn, features, service)
                    }
                }
            }
        }
    }
}

/**
 * 功能位开关行（badge + 标题 + summary + 右侧开关）：
 * checked 读 [HookSettings.features]（可观察，与总开关同模式），变更走
 * [HookSettings.setFeatureEnabled] 双写（本地 SP + 远程 prefs；总开关仍是上位语义）。
 * key 为 null 的占位行：enabled=false 禁用开关（灰显不亮），不持久化。
 */
@Composable
private fun FeatureSwitch(
    fn: AppFn,
    features: Map<String, Boolean>,
    service: XposedService?,
) {
    SwitchPreference(
        title = fn.name,
        summary = fn.summary,
        checked = fn.key != null && (features[fn.key] ?: true),
        onCheckedChange = { value -> fn.key?.let { HookSettings.setFeatureEnabled(it, value, service) } },
        enabled = fn.key != null,
        startAction = { IconBadge(fn.icon) },
    )
}

/**
 * 应用功能项：{名称, summary, 功能位 key, 图标}。
 * [key] = HookSettings.features 的 key（F1-F4/F6/F7）；null = 未实现占位（整合中/二期），开关禁用。
 */
data class AppFn(
    val name: String,
    val summary: String,
    val key: String?,
    val icon: ImageVector,
)

/**
 * Hook 目标应用（照 LSPosed Manager 模块详情模式一级列表项）：{id, 名称, 包名, 图标, 功能列表}。
 * 包名对齐 xposed/target 的 TargetHook.packageName（GlassRestoreHook 主键 com.hihonor.desktop.systemui，
 * 另两个别名 com.android.systemui / com.hihonor.android.launcher 见其类注释）。
 */
data class AppTarget(
    val id: String,
    val name: String,
    val packageName: String,
    /** 行内 tagline（demo summary 的"· 主题/壁纸/字体"段） */
    val tagline: String,
    val icon: ImageVector,
    val fns: List<AppFn>,
)

/** 目标当前已启用功能数（只计有 key 的功能位；未实现占位不计入启用）。 */
internal fun AppTarget.enabledCount(features: Map<String, Boolean>): Int =
    fns.count { it.key != null && (features[it.key] ?: true) }

/**
 * UI 侧目标注册表（与 xposed/target/TargetRegistry 一一对应，展示级数据驱动源）。
 * 功能标题/summary 照旧 HookSettingsScreen 既有文案（docs/honorhook-theme-requirements.md 功能表）；
 * 市场 F5 已落地（MarketHook 15 点核心组，真机 16.1.9.302 基线实证，scope.list 已声明）。
 */
val AppTargets: List<AppTarget> = listOf(
    AppTarget(
        id = "theme",
        name = "主题商店",
        packageName = "com.hihonor.android.thememanager",
        tagline = "主题/壁纸/字体",
        icon = MiuixIcons.Theme,
        fns = listOf(
            AppFn(
                "开启 VIP",
                "会员状态判定全部改为 VIP（到期/状态判定 → 永久有效）",
                KEY_F1_VIP_ENABLED,
                MiuixIcons.Unlock,
            ),
            AppFn(
                "全部资源免费",
                "付费判定全部放行、价格归零、购买/VIP 按钮隐藏",
                KEY_F2_FREE_ENABLED,
                MiuixIcons.Promotions,
            ),
            AppFn(
                "试用无倒计时",
                "试用不因时间/次数到期而中断",
                KEY_F3_TRIAL_ENABLED,
                MiuixIcons.Timer,
            ),
            AppFn(
                "永不恢复默认",
                "授权校验失败/试用结束等一切回退路径全部阻断",
                KEY_F4_NO_RESET_ENABLED,
                MiuixIcons.Lock,
            ),
        ),
    ),
    AppTarget(
        id = "installer",
        name = "软件包安装程序",
        packageName = "com.android.packageinstaller",
        tagline = "安装器净化",
        icon = MiuixIcons.FileDownloads,
        fns = listOf(
            AppFn(
                "安装器净化",
                "跳过联网安全检测与安装前指纹、禁止安装器联网、隐藏安全卡片/广告横幅。安全敏感：默认关闭——跳过检测会削弱系统对恶意应用的拦截，请仅对来源可信的 APK 开启",
                KEY_F6_INSTALLER_PURIFY_ENABLED,
                MiuixIcons.Blocklist,
            ),
        ),
    ),
    AppTarget(
        id = "systemui",
        name = "系统界面",
        packageName = "com.hihonor.desktop.systemui",
        tagline = "液态玻璃",
        icon = MiuixIcons.Layers,
        fns = listOf(
            AppFn(
                "液态玻璃恢复",
                "还原控制中心/通知中心液态玻璃渲染（面板遮罩/模糊/边光等，光学参数经 setprop persist.sys.lgr.* 实时可调）。Boot-loop 事故史：默认关闭——开启后请先在 LSPosed 日志过滤 [glass] 确认挂载汇总（installed 18/18），再下拉控制中心观察效果；锁屏玻璃链为二期未包含",
                KEY_F7_GLASS_ENABLED,
                MiuixIcons.Layers,
            ),
            AppFn(
                "锁屏玻璃",
                "锁屏位图合成链 · 二期未实现",
                null,
                MiuixIcons.Lock,
            ),
        ),
    ),
    AppTarget(
        id = "market",
        name = "应用市场",
        packageName = "com.hihonor.appmarket",
        tagline = "市场净化",
        icon = MiuixIcons.Store,
        fns = listOf(
            AppFn(
                "市场净化",
                "去开屏广告/运营弹窗/营销推送 · 拦截插电/WiFi/开机自启与外部 content:// 唤醒 · 搜索热词与工具页运营入口屏蔽 · 「我的」/搜索发现页推荐流请求源头拦截（应用更新与搜索应用不受影响）。真机 16.1.9.302 基线 15 点实证。默认关闭——改动面大，开启后请过一遍主要页面确认无广告残留",
                KEY_F5_MARKET_ENABLED,
                MiuixIcons.Filter,
            ),
        ),
    ),
)