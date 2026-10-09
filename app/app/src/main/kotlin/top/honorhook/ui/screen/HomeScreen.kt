package top.honorhook.ui.screen

import android.annotation.SuppressLint
import android.os.Build
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
import top.honorhook.BuildConfig
import top.honorhook.FrameworkState
import top.honorhook.R
import top.honorhook.data.HookSettings
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.component.UiTokens
import top.honorhook.xposed.target.TargetRegistry
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Layers
import top.yukonga.miuix.kmp.icon.extended.SelectAll
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 首页 Module 页（对齐 Rain Module 页，docs/rain-ui-layout.md §2）：
 * 模块状态卡 + 运行日志入口卡 + 适配目标计数卡 + 启用功能计数卡 + 设备信息详情卡。
 * 状态卡只做激活/未激活两态（"Read failed" 态需读框架信息，本项目无该路径）；
 * 适配计数 = TargetRegistry 公开 API，4 组与应用 tab 4 行口径一致（不依赖反射，混淆安全）；
 * 详情卡五行对齐 demo Tab1 detaillist（LSP 版本未绑定时显示"未知"）。
 */
@Composable
fun HomeScreen(onNavigateToLogs: () -> Unit) {
    val active by FrameworkState.active.collectAsState()
    val apiVersion by FrameworkState.apiVersion.collectAsState()
    val frameworkName by FrameworkState.frameworkName.collectAsState()
    val frameworkVersion by FrameworkState.frameworkVersion.collectAsState()
    val features by HookSettings.features.collectAsState()
    val adaptedCount = remember { TargetRegistry.groupedTargetCount() }
    val enabledFeatureCount = features.count { it.value }

    // 详情行数据（设备/系统信息静态取一次；LSP 版本随框架绑定状态实时变化）
    val unknown = stringResource(R.string.home_detail_unknown)
    val systemVersion = remember { systemVersionValue() }.ifBlank { unknown }
    val androidVersion = stringResource(
        R.string.home_detail_android_format, Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
    )
    val lspVersion = if (frameworkName.isNotBlank()) {
        stringResource(R.string.home_detail_lsp_format, frameworkName, frameworkVersion, apiVersion)
    } else {
        unknown
    }
    val buildTime = BuildConfig.BUILD_TIME.ifBlank { unknown }
    val deviceModel = remember { "${Build.BRAND} ${Build.MODEL}" }

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
            // 运行日志入口卡（状态卡下方；点入日志二级页，见 AppRoot "logs" route）
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    ArrowPreference(
                        title = stringResource(R.string.home_log_entry),
                        summary = stringResource(R.string.home_log_entry_summary),
                        onClick = onNavigateToLogs,
                    )
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
                        count = stringResource(R.string.home_enabled_count_format, enabledFeatureCount, features.size),
                    )
                }
            }
            item {
                Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
                    DetailCard(
                        systemVersion = systemVersion,
                        androidVersion = androidVersion,
                        lspVersion = lspVersion,
                        buildTime = buildTime,
                        deviceModel = deviceModel,
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
                // 动作列（Rain 三态卡；本模块只做两态）
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

/** 计数卡（照 Rain 计数卡样式，ck0.i/t0 模式）。 */
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

/** 设备信息详情卡（对齐 demo 的五行"上下两行"结构；数值由调用方传入）。 */
@Composable
private fun DetailCard(
    systemVersion: String,
    androidVersion: String,
    lspVersion: String,
    buildTime: String,
    deviceModel: String,
) {
    val unknown = stringResource(R.string.home_detail_unknown)
    val rows = listOf(
        stringResource(R.string.home_detail_system_version) to systemVersion,
        stringResource(R.string.home_detail_android_version) to androidVersion,
        stringResource(R.string.home_detail_lsp_version) to lspVersion,
        stringResource(R.string.home_detail_build_time) to buildTime,
        stringResource(R.string.home_detail_device_model) to deviceModel,
    )
    Column {
        rows.forEachIndexed { index, (title, value) ->
            if (index > 0) {
                HorizontalDivider(Modifier.padding(horizontal = UiTokens.CardMarginH))
            }
            DetailRow(title, value.ifBlank { unknown })
        }
    }
}

/** 单行详情（照 demo .drow）。 */
@Composable
private fun DetailRow(title: String, value: String) {
    Column(
        Modifier.padding(
            horizontal = UiTokens.CardMarginH,
            vertical = UiTokens.CardMarginV,
        ),
    ) {
        Text(
            title,
            fontSize = 14.sp, // demo .t 15px/600，取 UiTokens 中档值
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            fontSize = UiTokens.CaptionSize,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

/**
 * 系统版本（demo 对齐 MagicOS 增量号；SystemProperties 为 @hide，故反射 get(String)，读不到返回空串）。
 * TODO(真机验证)：属性名未实证——首选 ro.build.version.incremental，候选 ro.honor.build.version.incremental /
 * ro.build.display.id，真机比对后定稿。
 */
@SuppressLint("PrivateApi")
private fun systemVersionValue(): String = runCatching {
    val clazz = Class.forName("android.os.SystemProperties")
    val get = clazz.getMethod("get", String::class.java)
    get.invoke(null, "ro.build.version.incremental") as String
}.getOrNull().orEmpty().trim()