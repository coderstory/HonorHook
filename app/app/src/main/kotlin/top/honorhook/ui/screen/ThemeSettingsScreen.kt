package top.honorhook.ui.screen

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.Flare
import androidx.compose.material.icons.rounded.LocalFlorist
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.honorhook.ui.AppSettings
import top.honorhook.ui.ColorMode
import top.honorhook.ui.component.IconBadge
import top.honorhook.ui.component.Card
import top.honorhook.ui.component.motion.EntranceEdge
import top.honorhook.ui.component.motion.cardEntrance
import top.honorhook.ui.component.motion.revealFade
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 主题设置页 —— **照抄 KernelSU `ColorPaletteScreenMiuix.kt`**：
 * ```
 * Scaffold(TopAppBar("主题设置"), back)) {
 *   LazyColumn {
 *     Card { TabRow(跟随系统/浅色/深色) }
 *     Card { SwitchPreference(启用模糊); SwitchPreference(悬浮底栏); AnimatedVisibility { SwitchPreference(玻璃) } }
 *     Card { SwitchPreference(首页萤火虫) }
 *     Card { SwitchPreference(插画背景); SwitchPreference(花园背景) }
 *   }
 * }
 * ```
 */
@Composable
internal fun ThemeSettingsPage(
    onBack: () -> Unit,
    bottomInnerPadding: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val ui = AppSettings.colorMode

    Scaffold(
        // 透明底：让出 AppRoot 里的背景皮肤（GardenBackdrop）
        containerColor = Color.Transparent,
        topBar = {
            TopAppBar(
                color = Color.Transparent, // 顶部磨砂带由 ArtworkBackdrop 提供，这里必须让出底色
                title = "主题设置",
                navigationIcon = { MiuixBackButton(onBack) },
                scrollBehavior = scrollBehavior,
            )
        },
        popupHost = { },
        contentWindowInsets = WindowInsets.systemBars
            .add(WindowInsets.displayCutout)
            .only(WindowInsetsSides.Horizontal),
    ) { innerPadding ->
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
                Spacer(modifier = Modifier.height(12.dp))

                // 明暗模式（跟随系统 / 浅色 / 深色）。
                // 2026-11-08 用户「更换深色浅色那个卡片样式不太对劲」：原来它是一条裸 TabRow
                // 直接铺在插画上，两侧没有卡片托底、跟下面一串 Card 完全不是一个语汇。
                // 改成与设置项同构——Card 里放 TabRow，外边距与其它卡齐平。
                // 不挂 cardEntrance：它是页面第一个可交互件，跟着页面切换动画进场即可，
                // 再叠一层飞入会让「刚进主题页」这段本来就在动的画面更晃。
                Card(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        TabRow(
                            tabs = listOf("跟随系统", "浅色", "深色"),
                            selectedTabIndex = ui.value.coerceIn(0, 2),
                            onTabSelected = { index ->
                                AppSettings.setColorMode(ColorMode.fromValue(index))
                            },
                        )
                    }
                }

                // 玻璃与底栏
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .cardEntrance(1, key = "theme.1", edge = EntranceEdge.Bottom),
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        SwitchPreference(
                            title = "启用模糊",
                            summary = "顶栏与底栏的背景模糊",
                            startAction = {
                                IconBadge(
                                    icon = Icons.Rounded.BlurOn,
                                    accent = Color(0xFF4FA8FF),
                                    contentDescription = "启用模糊",
                                    modifier = Modifier.padding(end = 10.dp),
                                )
                            },
                            checked = AppSettings.liquidGlass,
                            onCheckedChange = { AppSettings.setLiquidGlass(it) },
                        )
                    }
                    SwitchPreference(
                        title = "悬浮底栏",
                        summary = "关闭则退回贴边导航栏",
                        startAction = {
                            IconBadge(
                                icon = Icons.Rounded.CallToAction,
                                accent = Color(0xFF9B84FF),
                                contentDescription = "悬浮底栏",
                                modifier = Modifier.padding(end = 10.dp),
                            )
                        },
                        checked = AppSettings.floatingBar,
                        onCheckedChange = { AppSettings.setFloatingBar(it) },
                    )
                    AnimatedVisibility(
                        visible = AppSettings.floatingBar &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                    ) {
                        SwitchPreference(
                            title = "启用玻璃",
                            summary = "底栏按钮的立体折射和高光",
                            startAction = {
                                IconBadge(
                                    icon = Icons.Rounded.WaterDrop,
                                    accent = Color(0xFF2BB3C0),
                                    contentDescription = "启用玻璃",
                                    modifier = Modifier.padding(end = 10.dp),
                                )
                            },
                            checked = AppSettings.floatingBarGlass,
                            onCheckedChange = { AppSettings.setFloatingBarGlass(it) },
                        )
                    }
                }

                // 首页效果：萤火虫开关 + 配色（配色档位定义在 ui/component/Fireflies.kt 的 FireflyTint）
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .cardEntrance(2, key = "theme.2", edge = EntranceEdge.Bottom),
                ) {
                    SwitchPreference(
                        title = "首页萤火虫",
                        summary = "首页背景的浮动光点，关掉可省一点电",
                        checked = AppSettings.fireflyEnabled,
                        onCheckedChange = { AppSettings.setFireflyEnabled(it) },
                        startAction = {
                            IconBadge(
                                icon = Icons.Rounded.Flare,
                                accent = Color(0xFFF0A93B),
                                contentDescription = "首页萤火虫",
                                modifier = Modifier.padding(end = 10.dp),
                            )
                        },
                    )
                }

                // 背景（2026-11-08 用户要求「加一个主题功能」）：插画 / 花园 / 纯色三档。
                // 不做三选一的单选控件，而是两张开关：插画优先，关掉插画才轮到花园，
                // 两个都关 = 纯色底。这样老用户的花园开关不会因为新增插画被悄悄改掉。
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth()
                        .cardEntrance(3, key = "theme.3", edge = EntranceEdge.Bottom),
                ) {
                    SwitchPreference(
                        title = "插画背景",
                        summary = "整屏蓝紫插画，顶栏是一块更厚的磨砂玻璃",
                        checked = AppSettings.artworkBackdrop,
                        onCheckedChange = { AppSettings.setArtworkBackdrop(it) },
                        startAction = {
                            IconBadge(
                                icon = Icons.Rounded.Wallpaper,
                                accent = Color(0xFF9B84FF),
                                contentDescription = "插画背景",
                                modifier = Modifier.padding(end = 10.dp),
                            )
                        },
                    )
                    SwitchPreference(
                        title = "花园背景",
                        summary = if (AppSettings.artworkBackdrop) {
                            "插画背景开启时暂不显示，关掉插画即可看到"
                        } else {
                            "页面四周的篱笆与花草，关掉回到纯色底"
                        },
                        checked = AppSettings.gardenSkin,
                        enabled = !AppSettings.artworkBackdrop,
                        onCheckedChange = { AppSettings.setGardenSkin(it) },
                        startAction = {
                            IconBadge(
                                icon = Icons.Rounded.LocalFlorist,
                                accent = Color(0xFF35C77E),
                                contentDescription = "花园背景",
                                modifier = Modifier.padding(end = 10.dp),
                            )
                        },
                    )
                }

                Spacer(
                    Modifier.height(
                        bottomInnerPadding +
                                WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                                WindowInsets.captionBar.asPaddingValues().calculateBottomPadding() + 12.dp
                    )
                )
            }
        }
    }
}
