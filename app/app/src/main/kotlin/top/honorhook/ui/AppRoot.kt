package top.honorhook.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import top.honorhook.R
import top.honorhook.data.HookSettings
import top.honorhook.data.KEY_FLOATING_BOTTOM_BAR_ENABLED
import top.honorhook.ui.component.FloatingMiuixNavigationBar
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.screen.HomeScreen
import top.honorhook.ui.screen.LogScreen
import top.honorhook.ui.screen.ModulesScreen
import top.honorhook.ui.screen.TargetDetailScreen
import top.honorhook.ui.screen.ThemeSettingsScreen
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 4 tab 路由表（lsposed-ui-spec.md §1：模块｜主页｜日志｜设置，主页为 startDestination）。
 * 图标取 miuix icons 权重对：Light=描边（未选中）、Demibold=实心感（选中）——规格"选中实心/未选中描边"
 * 的 miuix 0.9.3 等价物（该版本图标库无 Filled/Outlined 命名空间，按权重对映射）。
 */
private data class TabSpec(
    val route: String,
    @StringRes val labelRes: Int,
    val outlined: ImageVector,
    val filled: ImageVector,
)

private val TAB_SPECS = listOf(
    TabSpec("modules", R.string.tab_modules, MiuixIcons.Light.GridView, MiuixIcons.Demibold.GridView),
    TabSpec("home", R.string.tab_home, MiuixIcons.Light.Home, MiuixIcons.Demibold.Home),
    TabSpec("logs", R.string.tab_logs, MiuixIcons.Light.ListView, MiuixIcons.Demibold.ListView),
    TabSpec("settings", R.string.tab_settings, MiuixIcons.Light.Settings, MiuixIcons.Demibold.Settings),
)

private const val START_ROUTE = "home"

/**
 * 根组件（照 LSPosed Manager activity_main.xml：内容区 + BottomNavigationView；edgeToEdge 由
 * MainActivity enableEdgeToEdge 提供）：4 tab 路由 + 目标详情二级页 target/{targetId}（返回 = popBackStack）。
 * 底栏双形态：默认贴底标准形态；悬浮底栏开关（KEY_FLOATING_BOTTOM_BAR_ENABLED，主题设置页）开时切
 * 悬浮玻璃胶囊（FloatingMiuixNavigationBar）。二级页全屏（隐藏底栏）。
 */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route ?: START_ROUTE
    val uiToggles by HookSettings.uiToggles.collectAsState()
    val floatingEnabled = uiToggles[KEY_FLOATING_BOTTOM_BAR_ENABLED] ?: false
    val showBottomBar = TAB_SPECS.any { it.route == currentRoute }
    val selectedTab = TAB_SPECS.indexOfFirst { it.route == currentRoute }.coerceAtLeast(0)

    // 内容区底色 = 页面底色（防路由切换帧间透出窗口背景色）
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixPageBackground()),
    ) {
        when {
            showBottomBar && floatingEnabled -> FloatingTabRoot(navController, selectedTab)
            showBottomBar -> StandardTabRoot(navController, selectedTab)
            else -> TabNavHost(navController, Modifier.fillMaxSize())
        }
    }
}

/** 贴底标准形态（照 LSPosed BottomNavigationView；模块 tab 角标 = 已启用功能数，bg=primary/文字=onPrimary）。 */
@Composable
private fun StandardTabRoot(navController: NavHostController, selectedTab: Int) {
    val features by HookSettings.features.collectAsState()
    val enabledCount = features.count { it.value }
    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                TAB_SPECS.forEachIndexed { index, tab ->
                    NavigationBarItem(
                        selected = selectedTab == index,
                        onClick = { navController.navigateToTab(tab.route) },
                        icon = if (selectedTab == index) tab.filled else tab.outlined,
                        label = stringResource(tab.labelRes),
                        badge = if (index == 0 && enabledCount > 0) {
                            { EnabledFeatureBadge(enabledCount) }
                        } else null,
                    )
                }
            }
        },
    ) { padding ->
        TabNavHost(navController, Modifier.fillMaxSize().padding(padding))
    }
}

/** 悬浮玻璃形态（照 LSPFRIDA MainActivity.kt:366-432）：捕获层含页面但不含底栏自身，避免自模糊回环。 */
@Composable
private fun FloatingTabRoot(navController: NavHostController, selectedTab: Int) {
    val backdrop = rememberLayerBackdrop()
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop),
        ) {
            // 容器补 128dp 等效 padding 防末项被悬浮胶囊遮挡（LSPFRIDA 各页面预留同值）
            TabNavHost(navController, Modifier.fillMaxSize().padding(bottom = 128.dp))
        }
        // 覆盖式悬浮（非 bottomBar 槽位）；自身含 navigationBarsPadding + 12dp 偏移。
        // 悬浮胶囊无角标槽位（FloatingMiuixNavigationBar 不渲染 badge，组件不动），模块角标仅标准形态显示。
        FloatingMiuixNavigationBar(
            items = TAB_SPECS.mapIndexed { index, tab ->
                NavigationItem(
                    label = stringResource(tab.labelRes),
                    icon = if (selectedTab == index) tab.filled else tab.outlined,
                )
            },
            selected = selectedTab,
            onClick = { index -> navController.navigateToTab(TAB_SPECS[index].route) },
            backdrop = backdrop,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/** 路由注册（页面签名契约见任务单；详情二级页由各屏幕自带返回键 popBackStack）。 */
@Composable
private fun TabNavHost(navController: NavHostController, modifier: Modifier) {
    NavHost(navController = navController, startDestination = START_ROUTE, modifier = modifier) {
        composable("modules") { ModulesScreen(onNavigateToTarget = { navController.navigate("target/$it") }) }
        composable("home") { HomeScreen() }
        composable("logs") { LogScreen() }
        composable("settings") { ThemeSettingsScreen() }
        composable("target/{targetId}") { backStackEntry ->
            TargetDetailScreen(
                targetId = backStackEntry.arguments?.getString("targetId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
    }
}

/** tab 切换（saveState/restoreState 保留各 tab 页面状态，Navigation Compose 多 tab 惯例）。 */
private fun NavHostController.navigateToTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 模块 tab 角标（照 LSPosed：bg=colorPrimary、文字=colorOnPrimary）。 */
@Composable
private fun EnabledFeatureBadge(count: Int) {
    Box(
        modifier = Modifier
            .background(color = MiuixTheme.colorScheme.primary, shape = CircleShape)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(
            text = count.toString(),
            color = MiuixTheme.colorScheme.onPrimary,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}