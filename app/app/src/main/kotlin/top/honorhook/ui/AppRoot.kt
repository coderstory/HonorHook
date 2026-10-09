package top.honorhook.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import top.honorhook.R
import top.honorhook.ui.component.MiuixPageBackground
import top.honorhook.ui.screen.AboutScreen
import top.honorhook.ui.screen.HomeScreen
import top.honorhook.ui.screen.HookSettingsScreen
import top.honorhook.ui.screen.SettingsScreen
import top.honorhook.ui.screen.ThemeSettingsScreen
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.Scaffold

/**
 * 根组件：NavHost（底部三页主界面 + theme_settings / about 二级页）。
 * 二级页路径照 LSPFRIDA MainActivity AppRoot 结构（返回 = popBackStack）。
 */
@Composable
fun AppRoot() {
    val navController = rememberNavController()
    NavHost(navController = navController, startDestination = "main") {
        composable("main") {
            MainScaffold(
                onNavigateToThemeSettings = { navController.navigate("theme_settings") },
                onNavigateToAbout = { navController.navigate("about") },
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
 * 主壳：底部导航（miuix 原生 NavigationBar）+ HorizontalPager 三页
 * （首页 / Hook 设置 / 设置），pager ↔ tab 同步。
 * （照 LSPFRIDA MainActivity.kt:333-435 简化：去掉悬浮底栏与玻璃拟态）
 */
@Composable
private fun MainScaffold(
    onNavigateToThemeSettings: () -> Unit,
    onNavigateToAbout: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 3 })
    val scope = rememberCoroutineScope()

    // 滑动 → 选中项跟随：selectedTab 只由 pager 状态回写，点击侧 animateScrollToPage
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.collect { page ->
            selectedTab = page
        }
    }

    Scaffold(
        containerColor = MiuixPageBackground(),
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                // 底栏图标 = Rain 提取的 drawable（honor_ic_home/contacts/settings，原样复制）
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { scope.launch { pagerState.animateScrollToPage(0) } },
                    icon = ImageVector.vectorResource(R.drawable.honor_ic_home),
                    label = "首页",
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                    icon = ImageVector.vectorResource(R.drawable.honor_ic_contacts),
                    label = "Hook 设置",
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { scope.launch { pagerState.animateScrollToPage(2) } },
                    icon = ImageVector.vectorResource(R.drawable.honor_ic_settings),
                    label = "设置",
                )
            }
        },
    ) { padding ->
        HorizontalPager(
            state = pagerState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) { page ->
            when (page) {
                0 -> HomeScreen()
                1 -> HookSettingsScreen()
                else -> SettingsScreen(
                    onNavigateToThemeSettings = onNavigateToThemeSettings,
                    onNavigateToAbout = onNavigateToAbout,
                )
            }
        }
    }
}