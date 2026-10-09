package top.honorhook.ui.screen

import android.annotation.SuppressLint
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import top.honorhook.BuildConfig
import top.honorhook.FrameworkState
import top.honorhook.HookApplication
import top.honorhook.R
import top.honorhook.ui.component.UiTokens
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.More
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/** 间距规格 token（lsposed-ui-spec.md §5：页面水平 padding/卡间距 16dp、卡内 padding 24dp、信息行距 24dp）。 */
private val PageHPadding = 16.dp
private val CardSpacing = 16.dp
private val CardPadding = 24.dp
private val RowSpacing = 24.dp

/** 运行中目标数轮询间隔（进程启停无事件推送，与 LogScreen 同判据同间隔）。 */
private const val RUNNING_TARGETS_POLL_MS = 5000L

/**
 * 首页（照 LSPosed Manager HomeFragment，lsposed-ui-spec.md §2）：
 * 大标题栏（logo + HonorHook，点标题弹关于）+ 状态卡（主色实底）+ 设备信息卡（含复制）。
 * 旧统计卡（功能数/适配目标）删除——功能数并入模块 tab 角标、目标数并入信息卡末行。
 */
@Composable
fun HomeScreen() {
    val active by FrameworkState.active.collectAsState()
    val apiVersion by FrameworkState.apiVersion.collectAsState()
    val frameworkName by FrameworkState.frameworkName.collectAsState()
    val frameworkVersion by FrameworkState.frameworkVersion.collectAsState()

    // 运行中目标数（XposedService.getRunningTargets；服务断开视为 0）
    val service by HookApplication.service.collectAsState()
    var runningTargets by remember { mutableIntStateOf(0) }
    LaunchedEffect(service) {
        while (isActive) {
            val s = service
            runningTargets = if (s == null) 0 else runCatching { s.getRunningTargets().size }.getOrDefault(0)
            delay(RUNNING_TARGETS_POLL_MS)
        }
    }

    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AboutDialog(onDismiss = { showAbout = false })
    }

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

    Column(Modifier.fillMaxSize()) {
        HomeHeader(onAbout = { showAbout = true })
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = PageHPadding),
            verticalArrangement = Arrangement.spacedBy(CardSpacing),
            contentPadding = PaddingValues(bottom = CardSpacing),
        ) {
            item {
                StatusCard(
                    active = active,
                    frameworkName = frameworkName,
                    frameworkVersion = frameworkVersion,
                    apiVersion = apiVersion,
                )
            }
            item {
                InfoCard(
                    lspVersion = lspVersion,
                    androidVersion = androidVersion,
                    systemVersion = systemVersion,
                    deviceModel = deviceModel,
                    buildTime = buildTime,
                    runningTargets = runningTargets,
                )
            }
        }
    }
}

/** 大标题栏（照 LSPosed MaterialToolbar：logo 图标 + 标题，title 与 logo 间距 16dp；折叠动画不做，静态大标题）。 */
@Composable
private fun HomeHeader(onAbout: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .clickable(onClick = onAbout)
            .padding(start = PageHPadding, end = 4.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            painter = painterResource(R.mipmap.ic_launcher),
            contentDescription = null,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            color = MiuixTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.weight(1f))
        // 溢出菜单（规格：仅"关于"单条目）→ 直接动作等价，省一处下拉空壳
        IconButton(onClick = onAbout) {
            Icon(MiuixIcons.More, contentDescription = stringResource(R.string.home_menu_about))
        }
    }
}

/**
 * 状态卡（规格 token：bg=colorPrimary、文字/图标=onPrimary、内边距 24dp、图标 24dp）：
 * 已激活 = check_circle + 副标题"LSPosed 版本名 (版本码) - API 级"（版本码为字符串版本，无独立码值）；
 * 未连 = error + 引导文案。
 */
@Composable
private fun StatusCard(
    active: Boolean,
    frameworkName: String,
    frameworkVersion: String,
    apiVersion: Int,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = UiTokens.CardRadius,
        colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primary),
    ) {
        Row(
            modifier = Modifier.padding(CardPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 已激活对勾是 Rain 抄来的 webp 位图——vectorResource 只收矢量 XML（真机 FC 实证：
            // IllegalArgumentException: Only VectorDrawables and rasterized asset types are supported），
            // 位图走 painterResource + ColorFilter.tint 染 onPrimary
            if (active) {
                Image(
                    painter = painterResource(R.drawable.honor_ic_check_circle),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    colorFilter = ColorFilter.tint(MiuixTheme.colorScheme.onPrimary),
                )
            } else {
                // 未连态 error 图标用 Report（circle+!，miuix 图标集中近 error 语义）
                Icon(
                    imageVector = MiuixIcons.Report,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MiuixTheme.colorScheme.onPrimary,
                )
            }
            Spacer(Modifier.width(24.dp))
            Column {
                Text(
                    text = stringResource(
                        if (active) R.string.home_status_title_active else R.string.home_status_title_inactive,
                    ),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = if (active) {
                        stringResource(R.string.home_status_summary_active, frameworkName, frameworkVersion, apiVersion)
                    } else {
                        stringResource(R.string.home_status_summary_inactive)
                    },
                    fontSize = 14.sp,
                    color = MiuixTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

/** 设备信息卡（规格：Elevated、24dp 内边距；标签 16sp 弱化色、值 14sp、行距 24dp；末行右对齐复制按钮）。 */
@Composable
private fun InfoCard(
    lspVersion: String,
    androidVersion: String,
    systemVersion: String,
    deviceModel: String,
    buildTime: String,
    runningTargets: Int,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val unknown = stringResource(R.string.home_detail_unknown)
    // 值空时展示层回填"未知"，复制文本用同一份已回填行
    val rows = listOf(
        stringResource(R.string.home_detail_lsp_version) to lspVersion,
        stringResource(R.string.home_detail_android_version) to androidVersion,
        stringResource(R.string.home_detail_system_version) to systemVersion,
        stringResource(R.string.home_detail_device_model) to deviceModel,
        stringResource(R.string.home_detail_build_time) to buildTime,
        stringResource(R.string.home_detail_running_targets) to runningTargets.toString(),
    ).map { (label, value) -> label to value.ifBlank { unknown } }

    Card(modifier = Modifier.fillMaxWidth(), cornerRadius = UiTokens.CardRadius) {
        Column(Modifier.padding(CardPadding)) {
            rows.forEachIndexed { index, (label, value) ->
                if (index > 0) Spacer(Modifier.height(RowSpacing))
                Column {
                    Text(
                        text = label,
                        fontSize = 16.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = value,
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                }
            }
            Spacer(Modifier.height(RowSpacing))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(
                    text = stringResource(R.string.home_copy_info),
                    onClick = {
                        clipboard.setText(AnnotatedString(rows.joinToString("\n") { (label, value) -> "$label: $value" }))
                        Toast.makeText(context, context.getString(R.string.home_copied), Toast.LENGTH_SHORT).show()
                    },
                )
            }
        }
    }
}

/** 关于对话框（App 名 + 版本 + 简述；标题栏与溢出菜单共用入口）。 */
@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    WindowDialog(
        show = true,
        title = stringResource(R.string.app_name),
        titleColor = MiuixTheme.colorScheme.onSurface,
        summary = stringResource(R.string.about_version_badge, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE) +
            "\n" + stringResource(R.string.module_description),
        summaryColor = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        backgroundColor = MiuixTheme.colorScheme.background,
        enableWindowDim = true,
        onDismissRequest = onDismiss,
        onDismissFinished = {},
        outsideMargin = DpSize(24.dp, 24.dp),
        insideMargin = DpSize(20.dp, 20.dp),
        defaultWindowInsetsPadding = true,
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(text = stringResource(R.string.dialog_ok), onClick = onDismiss)
            }
        },
    )
}

/**
 * 系统版本（照 LSPosed 增量号列；SystemProperties 为 @hide，故反射 get(String)，读不到返回空串）。
 * TODO(真机验证)：属性名未实证——首选 ro.build.version.incremental，候选 ro.honor.build.version.incremental /
 * ro.build.display.id，真机比对后定稿。
 */
@SuppressLint("PrivateApi")
private fun systemVersionValue(): String = runCatching {
    val clazz = Class.forName("android.os.SystemProperties")
    val get = clazz.getMethod("get", String::class.java)
    get.invoke(null, "ro.build.version.incremental") as String
}.getOrNull().orEmpty().trim()