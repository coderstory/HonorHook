package top.honorhook.ui.screen

import android.annotation.SuppressLint
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.CallToAction
import androidx.compose.material.icons.rounded.Colorize
import androidx.compose.material.icons.rounded.DesignServices
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.PaletteStyle
import com.materialkolor.dynamiccolor.ColorSpec
import com.materialkolor.rememberDynamicColorScheme
import top.honorhook.ui.AppSettings
import top.honorhook.ui.ColorMode
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TabRow
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/** 关键色的中文名（顺序对齐 `AppSettings.keyColorOptions`，index 0 = 默认）。 */
private val keyColorNames = listOf(
    "默认", "红色", "粉色", "紫色", "深紫", "靛蓝", "蓝色", "青色",
    "蓝绿", "绿色", "黄色", "琥珀", "橙色", "棕色", "蓝灰", "樱粉",
)

/**
 * 主题设置页 —— **照抄 KernelSU `ColorPaletteScreenMiuix.kt`**：
 * ```
 * Scaffold(TopAppBar("主题设置", back)) {
 *   LazyColumn {
 *     Spacer(32.dp); ThemePreviewCardMiuix(); Spacer(72.dp)
 *     TabRow(跟随系统/浅色/深色)
 *     Card { SwitchPreference(Monet); AnimatedVisibility { OverlayDropdownPreference(关键色 / 配色风格 / 色规范) } }
 *     Card { SwitchPreference(启用模糊); SwitchPreference(悬浮底栏); AnimatedVisibility { SwitchPreference(玻璃) } }
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
    val isDark = ui.isDark || (ui.isSystem && isSystemInDarkTheme())

    Scaffold(
        topBar = {
            TopAppBar(
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
                Spacer(modifier = Modifier.height(32.dp))
                ThemePreviewCard(
                    keyColor = AppSettings.keyColor,
                    isDark = isDark,
                    miuixMonet = AppSettings.miuixMonet,
                    enableFloatingBottomBar = AppSettings.floatingBar,
                    enableFloatingBottomBarBlur = AppSettings.floatingBarGlass,
                    paletteStyle = AppSettings.paletteStyle,
                    colorSpec = AppSettings.specVersion,
                )
                Spacer(modifier = Modifier.height(72.dp))

                // 跟随系统 / 浅色 / 深色
                TabRow(
                    tabs = listOf("跟随系统", "浅色", "深色"),
                    selectedTabIndex = ui.toNonMonetMode().coerceIn(0, 2),
                    onTabSelected = { index ->
                        AppSettings.setColorMode(
                            ColorMode.fromValue(index).let {
                                if (AppSettings.miuixMonet) it.toMonetMode().let { v -> ColorMode.fromValue(v) }
                                else it
                            }
                        )
                    },
                )

                // Monet + 取色
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    SwitchPreference(
                        title = "跟随壁纸取色",
                        startAction = {
                            Icon(
                                Icons.Rounded.Wallpaper,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "跟随壁纸取色",
                                tint = colorScheme.onBackground,
                            )
                        },
                        checked = AppSettings.miuixMonet,
                        onCheckedChange = { AppSettings.setMiuixMonet(it) },
                    )

                    AnimatedVisibility(visible = AppSettings.miuixMonet) {
                        Column {
                            val colorValues = listOf(0) + AppSettings.keyColorOptions
                            OverlayDropdownPreference(
                                title = "关键色",
                                startAction = {
                                    Icon(
                                        Icons.Rounded.Colorize,
                                        modifier = Modifier.padding(end = 6.dp),
                                        contentDescription = "关键色",
                                        tint = colorScheme.onBackground,
                                    )
                                },
                                items = keyColorNames,
                                selectedIndex = colorValues.indexOf(AppSettings.keyColor).takeIf { it >= 0 } ?: 0,
                                onSelectedIndexChange = { index ->
                                    AppSettings.setKeyColor(colorValues[index])
                                },
                            )

                            AnimatedVisibility(visible = AppSettings.keyColorCustom) {
                                Column {
                                    val styles = PaletteStyle.entries
                                    OverlayDropdownPreference(
                                        title = "配色风格",
                                        startAction = {
                                            Icon(
                                                Icons.Rounded.Style,
                                                modifier = Modifier.padding(end = 6.dp),
                                                contentDescription = "配色风格",
                                                tint = colorScheme.onBackground,
                                            )
                                        },
                                        items = styles.map { it.name },
                                        selectedIndex = styles.indexOfFirst {
                                            it.name == AppSettings.colorStyle
                                        }.coerceAtLeast(0),
                                        onSelectedIndexChange = { index ->
                                            AppSettings.setColorStyle(styles[index].name)
                                        },
                                    )

                                    val specs = ColorSpec.SpecVersion.entries
                                    OverlayDropdownPreference(
                                        title = "色规范",
                                        startAction = {
                                            Icon(
                                                Icons.Rounded.DesignServices,
                                                modifier = Modifier.padding(end = 6.dp),
                                                contentDescription = "色规范",
                                                tint = colorScheme.onBackground,
                                            )
                                        },
                                        items = specs.map { it.name },
                                        selectedIndex = specs.indexOfFirst {
                                            it.name == AppSettings.colorSpec
                                        }.coerceAtLeast(0),
                                        onSelectedIndexChange = { index ->
                                            AppSettings.setColorSpec(specs[index].name)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }

                // 玻璃与底栏
                Card(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        SwitchPreference(
                            title = "启用模糊",
                            summary = "顶栏与底栏的背景模糊",
                            startAction = {
                                Icon(
                                    Icons.Rounded.BlurOn,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "启用模糊",
                                    tint = colorScheme.onBackground,
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
                            Icon(
                                Icons.Rounded.CallToAction,
                                modifier = Modifier.padding(end = 6.dp),
                                contentDescription = "悬浮底栏",
                                tint = colorScheme.onBackground,
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
                            summary = "底栏药丸的透镜折射与高光",
                            startAction = {
                                Icon(
                                    Icons.Rounded.WaterDrop,
                                    modifier = Modifier.padding(end = 6.dp),
                                    contentDescription = "启用玻璃",
                                    tint = colorScheme.onBackground,
                                )
                            },
                            checked = AppSettings.floatingBarGlass,
                            onCheckedChange = { AppSettings.setFloatingBarGlass(it) },
                        )
                    }
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

/**
 * 主题预览卡（照抄 KernelSU `ThemePreviewCardMiuix`）：
 * 按真实屏幕比例画一个手机缩略图，实时反映当前取色/底栏设置。
 */
@SuppressLint("ConfigurationScreenWidthHeight")
@Composable
private fun ThemePreviewCard(
    keyColor: Int,
    isDark: Boolean,
    miuixMonet: Boolean,
    enableFloatingBottomBar: Boolean = false,
    enableFloatingBottomBarBlur: Boolean = false,
    paletteStyle: PaletteStyle = PaletteStyle.TonalSpot,
    colorSpec: ColorSpec.SpecVersion = ColorSpec.SpecVersion.SPEC_2021,
) {
    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.toFloat()
    val screenHeight = configuration.screenHeightDp.toFloat()
    val screenRatio = screenWidth / screenHeight

    val seedColor = if (keyColor == 0) colorScheme.primary else Color(keyColor)
    val effectiveStyle = if (keyColor == 0) PaletteStyle.TonalSpot else paletteStyle
    val effectiveSpec = if (keyColor == 0) ColorSpec.SpecVersion.Default else colorSpec
    val dynamicCs = rememberDynamicColorScheme(
        seedColor = seedColor,
        isDark = isDark,
        style = effectiveStyle,
        specVersion = effectiveSpec,
    )

    val bgColor = if (miuixMonet) dynamicCs.background else colorScheme.surface
    val textColor = if (miuixMonet) dynamicCs.onSurface else colorScheme.onBackground
    val accentCardColor = when {
        miuixMonet -> dynamicCs.secondaryContainer
        isDark -> Color(0xFF1A3825)
        else -> Color(0xFFDFFAE4)
    }
    val cardColorPreview = if (miuixMonet) dynamicCs.surfaceContainerHighest else colorScheme.surfaceVariant
    val navBarColor = if (miuixMonet) dynamicCs.surfaceContainer else colorScheme.surface
    val iconColor = if (miuixMonet) dynamicCs.primary else colorScheme.primary
    val navSelectedColor = colorScheme.onSurfaceContainer
    val navUnselectedColor = colorScheme.onSurfaceContainer.copy(alpha = 0.5f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.4f)
                .aspectRatio(screenRatio)
                .clip(RoundedCornerShape(20.dp))
                .background(bgColor)
                .border(1.dp, colorScheme.outline, RoundedCornerShape(20.dp))
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .height(48.dp)
                        .fillMaxWidth()
                        .padding(start = 12.dp, top = 24.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    androidx.compose.material3.Text(
                        text = "Re:荣耀",
                        fontSize = 12.sp,
                        color = textColor,
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(45.dp)
                        .padding(horizontal = 8.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(accentCardColor)
                )

                BoxWithConstraints(modifier = Modifier.weight(1f)) {
                    val smallCardHeight = 12.dp
                    val smallCardCount = when {
                        maxHeight >= 96.dp -> 2
                        maxHeight >= 72.dp -> 1
                        else -> 0
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(6.dp))
                                .background(cardColorPreview)
                        )
                        repeat(smallCardCount) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(smallCardHeight)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(cardColorPreview)
                            )
                        }
                    }
                }
            }

            if (!enableFloatingBottomBar) {
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(0.5.dp)
                            .background(textColor.copy(alpha = 0.1f))
                    )
                    Row(
                        modifier = Modifier
                            .height(36.dp)
                            .fillMaxWidth()
                            .background(navBarColor)
                            .padding(top = 2.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .size(15.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(if (it == 0) navSelectedColor else navUnselectedColor)
                            )
                        }
                    }
                }
            } else {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (enableFloatingBottomBarBlur) navBarColor.copy(alpha = 0.5f)
                                else navBarColor
                            )
                            .border(0.5.dp, textColor.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        repeat(3) {
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(if (it == 0) iconColor else textColor)
                            )
                        }
                    }
                }
            }
        }
    }
}