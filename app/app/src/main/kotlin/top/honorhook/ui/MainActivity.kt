package top.honorhook.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.material.icons.rounded.Handyman
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.widget.Toast
import top.honorhook.ui.component.genshin.GenshinIcons
import top.honorhook.ui.component.genshin.genshinOr
import top.honorhook.BuildConfig
import top.honorhook.ui.component.FloatingBottomBar
import top.honorhook.ui.component.FloatingBottomBarItem
import top.honorhook.ui.component.ArtworkBackdrop
import top.honorhook.ui.component.GardenBackdrop
import top.honorhook.ui.component.HonorHookTheme
import top.honorhook.ui.component.IconBadge
import top.honorhook.ui.component.isInDarkTheme
import top.honorhook.ui.component.motion.EntranceState
import top.honorhook.ui.component.motion.LocalEntranceGate
import top.honorhook.ui.component.motion.LocalEntranceHost
import top.honorhook.ui.component.motion.dockRise
import top.honorhook.ui.screen.FeaturesScreen
import top.honorhook.ui.screen.HomeScreen
import top.honorhook.ui.screen.SettingsScreen
import top.honorhook.ui.screen.ToolboxScreen
import top.honorhook.ui.update.UpdateDialog
import top.honorhook.ui.update.UpdateState
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
        // 远程开发页曾打开且未被用户关闭 → 桌面冷启/回到主任务后自动拉回该页（恢复会话）
        if (getSharedPreferences("webscan", MODE_PRIVATE).getBoolean("open", false)) {
            startActivity(android.content.Intent(this, top.honorhook.ui.webscan.WebScanActivity::class.java)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        // 框架状态是异步问出来的：兜底计时，1.5s 还没绑上服务才判「未激活」，
        // 免得冷启动先闪一个错误的「未激活」再跳成「工作中」
        FrameworkState.armResolveTimeout()
        // 打开即静默查一次更新（失败无感，结果进设置页「检查更新」行）。
        // debug 构建跳过：debug 包版本号跟入库 version.properties（常落后远端发布版），静默查必弹「发现新版本」干扰自装调试；手动检查不受影响
        if (!BuildConfig.DEBUG) UpdateState.checkAsync()
        setContent {
            HonorHookTheme {
                var splashVisible by rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
                // 进场闸门（2026-11-08 experiment/ui-overhaul）：splash 开始淡出才开闸，
                // 让卡片飞入与 splash 淡出**同帧叠化**，而不是等 splash 完全消失后画面空一拍再飞。
                // 默认 false：屏下不启动进场（否则 2.8s 的 splash 期间动画早播完，用户什么都看不到）。
                var entranceOpen by remember { androidx.compose.runtime.mutableStateOf(false) }
                // 冷启动白屏（2026-11-08 用户反馈「打开会白屏一秒」）：
                // Android 12+ 的系统启动画面只认 `windowSplashScreenBackground` 这一个纯色，
                // 它会一直挂着，直到 App 的第一帧真正上屏。原来这一帧同时组合了 AppRoot
                // （backdrop 录制层 + 花园背景 + 萤火虫 + LazyColumn + 底栏），首帧成本全压在它身上，
                // 白屏因此被拉长到 1s 量级（中低端机上更久）。
                // 现在首帧只画 splash 自身：等它上屏（withFrameNanos）后再让一帧，然后才挂 AppRoot——
                // 它的组合/测量/首绘全部落进 splash 停留的 2.4s 里，用户看不到这笔开销。
                // 闸门（entranceOpen）不变：内容即便提前组合好也还是隐身，进场仍等 splash 淡出才播。
                var contentReady by remember { androidx.compose.runtime.mutableStateOf(false) }
                // 兜底：万一 splash 的 onLeaving 没触发（异常/被裁掉），4s 后强制开闸，内容不会永远隐身
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(4000)
                    contentReady = true
                    entranceOpen = true
                }
                LaunchedEffect(Unit) {
                    androidx.compose.runtime.withFrameNanos { }
                    // 再让一帧：保证 splash 的第一帧已经落屏，AppRoot 不在首帧插队
                    kotlinx.coroutines.delay(64)
                    contentReady = true
                }
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    if (contentReady) {
                        CompositionLocalProvider(LocalEntranceGate provides entranceOpen) {
                            AppRoot()
                        }
                    }
                    if (splashVisible) {
                        top.honorhook.ui.splash.HonorSplash(
                            onFinish = { splashVisible = false },
                            onLeaving = { entranceOpen = true },
                        )
                    }
                }
            }
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
    // 页面底色 = 主题 surface（AMOLED 纯黑模式已于 2026-11-08 随「纯黑背景」开关一起删除）
    val rootColor = surfaceColor

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

    // 更新弹窗全局挂载（静默/手动检查发现新版都弹）；Toast 消费一次性通知。
    val context = LocalContext.current
    LaunchedEffect(UpdateState.notice) {
        UpdateState.notice?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            UpdateState.clearNotice()
        }
    }
    if (UpdateState.showDialog) {
        UpdateDialog(onDismiss = { UpdateState.dismissDialog() })
    }

    // 不 remember：图标要随「插画背景」开关切换（m01154），列表重建成本可忽略。
    val items =
        listOf(
            NavigationItem(label = "首页", icon = genshinOr(Icons.Rounded.Cottage, GenshinIcons.Vision)),
            NavigationItem(label = "功能", icon = genshinOr(Icons.Rounded.Extension, GenshinIcons.Dendro)),
            // 第 4 tab：工具箱（28 号规格书 §3）——root 开关/冻结/活性自检
            NavigationItem(label = "工具箱", icon = genshinOr(Icons.Rounded.Handyman, GenshinIcons.Primogem)),
            NavigationItem(label = "设置", icon = genshinOr(Icons.Rounded.Settings, GenshinIcons.Mora)),
        )

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
            // 背景皮肤（2026-11-08 用户要求）画在内容层内部、页面之下：
            // 页面 Scaffold 让出透明底才能看见它；放在 layerBackdrop 里面，玻璃药丸才会把它当成背景折射。
            // 插画背景优先于花园篱笆（2026-11-08 artwork-theme）：绿篱笆 + 紫插画同框互相打架，
            // 开插画时花园让位；关掉插画又没开花园 → 退回纯色底（rootColor）。
            if (AppSettings.artworkBackdrop) {
                ArtworkBackdrop(modifier = Modifier.fillMaxSize())
            } else if (AppSettings.gardenSkin) {
                GardenBackdrop(
                    modifier = Modifier.fillMaxSize(),
                    dark = isInDarkTheme(),
                )
            }
            // 切 tab 的进场（2026-11-08 ui-overhaul）：整页「淡入 + 上浮 12dp」只是**背景层**的过渡，
            // 主视觉是各屏卡片各自的逐张飞入（cardEntrance）。退场比进场快（140ms），
            // 否则旧页的卡片还压在新页上，两层飞入会打架。
            AnimatedContent(
                targetState = selectedTab,
                modifier = Modifier.fillMaxSize(),
                transitionSpec = {
                    (fadeIn(tween(280)) + slideInVertically(tween(300)) { height -> height / 40 })
                        .togetherWith(fadeOut(tween(140)))
                },
                label = "tabSwitch",
            ) { tab ->
                // 每个 tab 一张播放登记表：本次进入的卡片才播；同 tab 内的二级页复用这张表，
                // 所以「功能页 → 点进目标详情 → 返回」不会让已落位的卡重播。
                val entranceHost = remember { EntranceState() }
                CompositionLocalProvider(LocalEntranceHost provides entranceHost) {
                    when (tab) {
                        0 -> HomeScreen(bottomInnerPadding = bottomBarPadding)
                        1 -> FeaturesScreen(bottomInnerPadding = bottomBarPadding)
                        2 -> ToolboxScreen(bottomInnerPadding = bottomBarPadding)
                        else -> SettingsScreen(bottomInnerPadding = bottomBarPadding)
                    }
                }
            }
        }

        HonorHookBottomBar(
            backdrop = backdrop,
            selectedIndex = selectedTab,
            onSelected = { selectedTab = it },
            items = items,
            enableFloating = floatingBar,
            enableBlurSdk = enableFloatingBlur,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .dockRise(),
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

    // 底栏四 tab 的语义色（2026-11-08 用户要求图标彩色且有质感）：
    // 与工具箱磁贴同一套色板 —— 首页蓝、功能橙、工具箱绿、设置紫。
    val tabAccents = listOf(
        Color(0xFF4FA8FF),
        Color(0xFFF0A93B),
        Color(0xFF35C77E),
        Color(0xFF9B84FF),
    )

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
                // 图标块自带颜色，不再跟随 LocalContentColor；重影层因此也画出同色徽章，
                // 玻璃药丸里看到的是该 tab 自身的颜色晕开 —— 正是选中态要的观感
                // 图标块尺寸（2026-11-08 用户要求「tab 图标适度放大一点点」）：
                // 26→29dp、字形 15→17dp、圆角 8→9dp。只动这一处——徽章本身变大后字形留白也跟着变大，
                // 若只放大字形会出现「字顶到圆角」的拥挤感。药丸宽度由 defaultMinSize(76dp) 兜住，不会被挤窄。
                IconBadge(
                    icon = item.icon,
                    accent = tabAccents.getOrElse(index) { tabAccents.first() },
                    contentDescription = item.label,
                    size = 29.dp,
                    iconSize = 17.dp,
                    corner = 9.dp,
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