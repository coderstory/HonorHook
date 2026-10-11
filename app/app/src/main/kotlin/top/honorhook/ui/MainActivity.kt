package top.honorhook.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cottage
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.honorhook.ui.component.FloatingBottomBar
import top.honorhook.ui.component.FloatingBottomBarItem
import top.honorhook.ui.component.HonorHookTheme
import top.honorhook.ui.screen.FeaturesScreen
import top.honorhook.ui.screen.HomeScreen
import top.honorhook.ui.screen.SettingsScreen
import top.honorhook.ui.update.UpdateState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.NavigationBar
import top.yukonga.miuix.kmp.basic.NavigationBarItem
import top.yukonga.miuix.kmp.basic.NavigationItem
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.LayerBackdrop
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 宿主设置界面入口：**Miuix 单模式**（不再提供「界面风格」切换，默认就是 Miuix）。
 *
 * 照抄 KernelSU `MainActivity.kt` 的 Miuix 分支：
 * ```
 * Scaffold(bottomBar = bottomBar) { innerPadding -> pagerContent(innerPadding.calculateBottomPadding()) }
 * bottomBar = Box(fillMaxWidth) { BottomBar(..., modifier = Modifier.align(BottomCenter)) }
 * ```
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 打开即静默查一次更新（失败无感，结果进设置页「检查更新」行）
        UpdateState.checkAsync()
        setContent {
            HonorHookTheme { AppRoot() }
        }
    }
}

@Composable
private fun AppRoot() {
    // rememberSaveable：旋屏/进程重建后仍停在当前 Tab（原 remember 会掉回首页）
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    // 照抄 KernelSU MainActivity.kt:352-355 —— 记录层必须先铺 surface 底色再录内容，
    // 否则 drawBackdrop 采样到空/半透明内容，实色 onDrawSurface 会盖住整个药丸，玻璃感全无。
    val surfaceColor = MiuixTheme.colorScheme.surface
    // AMOLED 纯黑模式：页面底色直接给黑（其余模式不变，避免顺手改掉既有观感）
    val rootColor = if (AppSettings.colorMode.isAmoled) Color.Black else surfaceColor

    // **直接订阅 AppSettings 的 State**，不绕 CompositionLocal。
    // 依赖 CompositionLocal 的写法在「从二级页返回」时不会触发重组（CompositionLocal
    // 只在自身 provider 重算时更新，而 provider 在 HonorHookTheme 里，被 ThemeController
    // 重建节奏影响），表现为「主题设置里改了开关，返回主界面玻璃就没了」。
    // 直接读 AppSettings.xxx（内部是 mutableStateOf）能保证重组必然发生。
    val floatingBar = AppSettings.floatingBar
    val floatingBarGlass = AppSettings.floatingBarGlass
    // 「启用模糊」总闸（AppSettings.liquidGlass）原先只喂给一个无人读取的 CompositionLocal，
    // 开关点了没有任何效果 —— 这里与底栏玻璃一起作为真实生效条件。
    val liquidGlass = AppSettings.liquidGlass
    val enableFloatingBlur =
        floatingBar && floatingBarGlass && liquidGlass && Build.VERSION.SDK_INT >= 33

    // key(rootColor)：backdrop 的 draw 回调只在创建时捕获一次颜色，换深浅色/AMOLED 后
    // 若不重建，玻璃底色会停在旧色上。
    val backdrop = key(rootColor) {
        rememberLayerBackdrop {
            drawRect(rootColor)
            drawContent()
        }
    }

    val items = remember {
        listOf(
            NavigationItem(label = "首页", icon = Icons.Rounded.Cottage),
            NavigationItem(label = "功能", icon = Icons.Rounded.Extension),
            NavigationItem(label = "设置", icon = Icons.Rounded.Settings),
        )
    }

    // 悬浮底栏是**浮在内容之上**的（不是 Scaffold 那种占据布局高度的 bottomBar），
    // 所以内容区必须自己预留出底栏高度，否则最后一行内容会被药丸盖住。
    // 高度 = 药丸 64dp + 底部 inset padding + 左右各 28dp 之外还要留出视觉间隙。
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomBarPadding = if (floatingBar) {
        64.dp + (if (navigationInset != 0.dp) 8.dp + navigationInset else 28.dp) + 16.dp
    } else {
        navigationInset
    }

    // 底栏与内容层必须是**兄弟**：内容 Box 挂 layerBackdrop（只录页面内容），
    // 底栏作为同级兄弟绘制并消费 backdrop。若把 layerBackdrop 挂在同时包含底栏的父节点上，
    // 底栏会采样到「包含自己」的 backdrop → RenderThread 递归栈溢出（SIGSEGV）。
    // 也不用 Miuix Scaffold 的 bottomBar slot（它在 content 之前绘制，采样不到本帧内容）。
    Box(modifier = Modifier.fillMaxSize().background(rootColor)) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(if (enableFloatingBlur) Modifier.layerBackdrop(backdrop) else Modifier),
        ) {
            when (selectedTab) {
                0 -> HomeScreen(bottomInnerPadding = bottomBarPadding)
                1 -> FeaturesScreen(bottomInnerPadding = bottomBarPadding)
                else -> SettingsScreen(bottomInnerPadding = bottomBarPadding)
            }
        }

        HonorHookBottomBar(
            backdrop = backdrop,
            selectedIndex = selectedTab,
            onSelected = { selectedTab = it },
            items = items,
            enableFloating = floatingBar,
            enableBlurSdk = enableFloatingBlur,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }
}

/**
 * 底栏（照抄 KernelSU `ui/component/bottombar/BottomBarMiuix.kt`）：
 * - `enableFloating = false` → 贴边 `NavigationBar`
 * - `= true` → `FloatingBottomBar`（玻璃药丸），底部 padding = 导航栏 inset + 8dp（无 inset 时 28dp）
 *
 * 两个开关由 [AppRoot] 直接传进来（不走 CompositionLocal），确保改设置后立刻重组。
 */
@Composable
private fun HonorHookBottomBar(
    backdrop: LayerBackdrop,
    selectedIndex: Int,
    onSelected: (Int) -> Unit,
    items: List<NavigationItem>,
    enableFloating: Boolean,
    enableBlurSdk: Boolean,
    modifier: Modifier = Modifier,
) {

    if (!enableFloating) {
        NavigationBar(
            modifier = modifier,
            color = if (enableBlurSdk) Color.Transparent else MiuixTheme.colorScheme.surface,
        ) {
            items.forEachIndexed { index, item ->
                NavigationBarItem(
                    modifier = Modifier.weight(1f),
                    icon = item.icon,
                    label = item.label,
                    selected = selectedIndex == index,
                    onClick = { onSelected(index) },
                )
            }
        }
        return
    }

    val bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        .let { inset -> if (inset != 0.dp) 8.dp + inset else 28.dp }

    FloatingBottomBar(
        modifier = modifier
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 28.dp, end = 28.dp, bottom = bottomPadding),
        selectedIndex = selectedIndex,
        onSelected = onSelected,
        backdrop = backdrop,
        tabsCount = items.size,
        isBlurEnabled = enableBlurSdk,
    ) { activateTab ->
        items.forEachIndexed { index, item ->
            FloatingBottomBarItem(
                selected = selectedIndex == index,
                onClick = { activateTab(index) },
                // 关键：外层 Box 是 IntrinsicSize.Min，而 item 是 weight(1f)（固有宽度 0），
                // 必须靠 defaultMinSize 撑出每项的最小宽度，否则药丸会塌成一个圆。
                modifier = Modifier.defaultMinSize(minWidth = 76.dp),
            ) {
                // 图标与文字取 LocalContentColor：药丸内的重影层靠它把内容重新着色成强调色
                Icon(
                    imageVector = item.icon,
                    contentDescription = item.label,
                )
                Text(
                    text = item.label,
                    fontSize = 11.sp,
                    lineHeight = 14.sp,
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}