package top.honorhook.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import top.honorhook.R
import top.honorhook.data.HookSettings
import top.honorhook.data.KEY_FLOATING_BOTTOM_BAR_ENABLED
import top.honorhook.ui.component.FloatingMiuixNavigationBar
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.screen.AboutScreen
import top.honorhook.ui.screen.AppFunctionsScreen
import top.honorhook.ui.screen.AppListScreen
import top.honorhook.ui.screen.HomeScreen
import top.honorhook.ui.screen.SettingsScreen
import top.honorhook.ui.screen.ThemeSettingsScreen
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold

/** 根组件：NavHost（底部三页主界面 + appfn/theme_settings/about 二级页，路由结构照 LSPFRIDA
 * MainActivity AppRoot，二级页返回 = popBackStack）。 */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScaffold(
                onNavigateToAppFn = { id -> navController.navigate("appfn/$id") },
                onNavigateToThemeSettings = { navController.navigate("theme_settings") },
                onNavigateToAbout = { navController.navigate("about") },
            )
        }
        // 应用功能二级页（targetId = AppTargets.id，Tab2 一级列表点击进入）
        composable("appfn/{targetId}") { backStackEntry ->
            AppFunctionsScreen(
                targetId = backStackEntry.arguments?.getString("targetId").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }
        composable("theme_settings") {
            ThemeSettingsScreen(onBack = { navController.popBackStack() })
        }
        composable("about") {
            AboutScreen(onBack = { navController.popBackStack() })
        }
    }
}

/**
 * 主壳：底部导航双形态，由"悬浮底栏"开关（HookSettings.uiToggles）驱动——
 * 开 = 悬浮玻璃胶囊（FloatingMiuixNavigationBar，照 LSPFRIDA MainActivity.kt:366-432），
 * 关 = miuix 原生贴底通栏。两形态共用同一 pagerState，切换时保留当前页。
 */
@Composable
private fun MainScaffold(
    onNavigateToAppFn: (String) -> Unit,
    onNavigateToThemeSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // 开关在 ThemeSettingsScreen 切换，此处经 uiToggles 即时响应
    val uiToggles by HookSettings.uiToggles.collectAsState()
    val floatingEnabled = uiToggles[KEY_FLOATING_BOTTOM_BAR_ENABLED] ?: false

    // 底栏图标 = Rain 提取的 drawable（honor_ic_home/contacts/settings，原样复制）
    val navigationItems = listOf(
        NavigationItem("首页", ImageVector.vectorResource(R.drawable.honor_ic_home)),
        NavigationItem("应用", ImageVector.vectorResource(R.drawable.honor_ic_contacts)),
        NavigationItem("设置", ImageVector.vectorResource(R.drawable.honor_ic_settings)),
    )

    // selectedTab 只由 pager 回写；点击侧走 animateScrollToPage
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            selectedTab = page
        }
    }

    val pagerContent: @Composable (Int) -> Unit = { page ->
        when (page) {
            0 -> HomeScreen()
            1 -> AppListScreen(onTargetClick = onNavigateToAppFn)
            else -> SettingsScreen(
                onNavigateToThemeSettings = onNavigateToThemeSettings,
                onNavigateToAbout = onNavigateToAbout,
            )
        }
    }

    if (floatingEnabled) {
        // ===== 悬浮形态（照 LSPFRIDA MainActivity.kt:366-432）=====
        // 捕获层含全部页面内容但不含底栏自身，避免自模糊回环
        val backdrop = rememberLayerBackdrop()
        // 外层底色 = 页面底色（防 Pager 滑动/切换帧间透出黑底）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MiuixPageBackground()),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            ) {
                HorizontalPager(
                    state = pagerState,
                    // 页面自身只留 12dp，容器补 128dp 等效 padding 防末项被悬浮胶囊遮挡（LSPFRIDA 在各页面预留同值）
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 128.dp),
                    beyondViewportPageCount = 3, // 三页全保活（防切页状态丢失/重组合卡顿，照 LSPFRIDA）
                ) { page ->
                    pagerContent(page)
                }
            }
            // 覆盖式悬浮（非 bottomBar 槽位）；自身含 navigationBarsPadding + 12dp 偏移
            FloatingMiuixNavigationBar(
                items = navigationItems,
                selected = selectedTab,
                onClick = { index -> scope.launch { pagerState.animateScrollToPage(index) } },
                backdrop = backdrop,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    } else {
        Scaffold(
            containerColor = MiuixPageBackground(),
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                NavigationBar {
                    navigationItems.forEachIndexed { index, item ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                            icon = item.icon,
                            label = item.label,
                        )
                    }
                }
            },
        ) { padding ->
            HorizontalPager(
                state = pagerState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
            ) { page ->
                pagerContent(page)
            }
        }
    }
}