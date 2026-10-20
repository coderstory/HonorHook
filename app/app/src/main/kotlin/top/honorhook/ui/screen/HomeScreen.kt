package top.honorhook.ui.screen

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeveloperBoard
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.rounded.CheckCircleOutline
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.honorhook.BuildConfig
import top.honorhook.ui.AppSettings
import top.honorhook.ui.FrameworkState
import top.honorhook.ui.component.isInDarkTheme
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.PressFeedbackType
import top.yukonga.miuix.kmp.utils.scrollEndHaptic
import top.honorhook.ui.Glitch

/**
 * 首页 —— **逐行照抄 KernelSU `HomeMiuix.kt`**：
 * ```
 * Scaffold(TopAppBar(app_name, scrollBehavior)) {
 *   LazyColumn(padding(horizontal = 12.dp)) {
 *     Column(spacedBy(12.dp), align = CenterHorizontally) {
 *         StatusCard(...)   // Card(secondaryContainer) + 110dp CheckCircleOutline 溢出右下 + 22sp SemiBold 大标题
 *         InfoCard(...)     // 两张 Card(padding 16.dp)，每项 = 24dp 图标 + headline1 标题 + body2 内容
 *         SupportLinks(...) // Card { ArrowPreference × 2 }
 *         Spacer(bottomInnerPadding)
 *     }
 *   }
 * }
 * ```
 */
@Composable
fun HomeScreen(bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    val active = FrameworkState.active
    val frameworkName = FrameworkState.frameworkName
    val frameworkVersion = FrameworkState.frameworkVersion

    val scrollBehavior = MiuixScrollBehavior()
    val isDynamicColor = AppSettings.miuixMonet

    Scaffold(
        topBar = {
            TopAppBar(
                title = Glitch.text("Re:荣耀"),
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
        Box {
            LazyColumn(
                modifier = Modifier
                    .fillMaxHeight()
                    .scrollEndHaptic()
                    .overScrollVertical()
                    .nestedScroll(scrollBehavior.nestedScrollConnection)
                    .padding(horizontal = 12.dp),
                contentPadding = innerPadding,
                overscrollEffect = null,
            ) {
                item {
                    Column(
                        modifier = Modifier.padding(top = 12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        StatusCard(
                            active = active,
                            isDynamicColor = isDynamicColor,
                            workingText = if (Glitch.cardRed) Glitch.text("老色批已爆炸")
                            else if (active) Glitch.text("工作中") else Glitch.text("未激活"),
                            versionText = Glitch.text(if (active) {
                                listOf(frameworkName, frameworkVersion)
                                    .filter { it.isNotBlank() }
                                    .joinToString(" ")
                                    .ifBlank { "LSPosed" }
                            } else {
                                "未连接 LSPosed（已安装但未激活，或模块未启用）"
                            }),
                        )

                        InfoCard()

                        Spacer(
                            Modifier.height(
                                bottomInnerPadding +
                                        WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun appVersionLabel(): String {
    val context = LocalContext.current
    return remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            "${info.versionName}（${info.longVersionCode}）"
        }.getOrDefault("?")
    }
}

/**
 * 状态卡 —— 照抄 KernelSU `HomeMiuix.kt::StatusCard` 已激活分支：
 * ```
 * Card(color = secondaryContainer / 0xFFDFFAE4, onClick, pressFeedbackType = Tilt) {
 *     Box {
 *         Box(fillMaxSize.offset(27.dp, 31.dp), BottomEnd) { Icon(110.dp, CheckCircleOutline) }
 *         Box(fillMaxSize.padding(16.dp, 14.dp), TopStart) {
 *             Column { Text(22.sp SemiBold) ; Text(15.sp 版本) }
 *         }
 *     }
 * }
 * ```
 */
@Composable
private fun StatusCard(
    active: Boolean,
    isDynamicColor: Boolean,
    workingText: String,
    versionText: String,
) {
    val containerColor = when {
        Glitch.cardRed -> Color(0xFFFF1744)
        !active -> Color(0xFFFFDAD6)
        isDynamicColor -> colorScheme.secondaryContainer
        isInDarkTheme() -> Color(0xFF1A3825)
        else -> Color(0xFFDFFAE4)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.defaultColors(color = containerColor),
        onClick = {},
        showIndication = false,
        pressFeedbackType = PressFeedbackType.Tilt,
    ) {
        Box {
            // 110dp 绿圈勾，故意溢出右下角只露一半
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .offset(27.dp, 31.dp),
                contentAlignment = Alignment.BottomEnd,
            ) {
                Icon(
                    modifier = Modifier.size(110.dp),
                    imageVector = if (active) Icons.Rounded.CheckCircleOutline else Icons.Rounded.ErrorOutline,
                    tint = when {
                        !active -> Color(0xFFBA1A1A).copy(alpha = 0.5f)
                        isDynamicColor -> colorScheme.primary.copy(alpha = 0.8f)
                        else -> Color(0xFF36D167)
                    },
                    contentDescription = null,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column {
                    Text(
                        text = workingText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = versionText,
                        fontSize = 15.sp,
                    )
                }
            }
        }
    }
}

/**
 * 信息行 —— 照抄 KernelSU `InfoCard` 的行结构：
 * 24dp 图标（end padding 12dp）+ `headline1` 标题（Medium）+ `body2` 内容（top padding 2dp）。
 *
 * 提为顶层函数（原先是声明在 [InfoCard] 内部的嵌套 composable，无法从他处复用）。
 */
@Composable
private fun InfoRow(icon: ImageVector, title: String, content: String, bottomPadding: Dp = 24.dp) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = bottomPadding),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            modifier = Modifier
                .padding(end = 12.dp)
                .size(24.dp),
            tint = colorScheme.onSurface,
        )
        Column {
            Text(
                text = title,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface,
            )
            Text(
                text = content,
                fontSize = MiuixTheme.textStyles.body2.fontSize,
                color = colorScheme.onSurfaceVariantSummary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** 信息卡 —— 照抄 KernelSU `InfoCard`：单张 `Card`，卡内 `Column(padding 16.dp)` 逐行 [InfoRow]。 */
@Composable
private fun InfoCard() {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            InfoRow(Icons.Filled.Tag, Glitch.text("模块版本"), Glitch.text(appVersionLabel()))
            InfoRow(Icons.Filled.DeveloperBoard, Glitch.text("模块包名"), Glitch.text(BuildConfig.APPLICATION_ID))
            InfoRow(
                Icons.Filled.Smartphone, Glitch.text("系统版本"),
                Glitch.text("Android ${Build.VERSION.RELEASE}"),
            )
            InfoRow(
                Icons.Filled.Fingerprint, Glitch.text("设备"),
                Glitch.text("${Build.MANUFACTURER} ${Build.MODEL}"),
            )
            // 系统架构原来复用了 DeveloperBoard（与Glitch.text("插件包名")同一个图标），换成 Memory
            InfoRow(
                Icons.Filled.Memory, Glitch.text("系统架构"),
                Glitch.text(Build.SUPPORTED_ABIS.firstOrNull() ?: "未知"),
                bottomPadding = 0.dp,
            )
        }
    }
}