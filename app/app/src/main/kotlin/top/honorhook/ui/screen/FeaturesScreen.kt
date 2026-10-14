package top.honorhook.ui.screen

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.honorhook.FeatureCatalog
import top.honorhook.ui.FeatureStore
import top.honorhook.ui.FrameworkState
import top.honorhook.ui.component.isInDarkTheme
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.ChevronForward
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

/**
 * 第二个 Tab —— **照抄 KernelSU `SuperUserMiuix.kt` 的视觉结构**：
 *
 * 一级（`GroupItem`）：`Card` + 48dp 应用图标 + 主标题 + 副标题（包名/功能数）+
 * 右侧状态标签列 + 右箭头。状态标签沿用 `StatusTagMiuix`（6dp 圆角 + 9sp/750 字重）。
 *
 * 二级（`SimpleAppItem`）：左侧 6dp 强调色条 + `Card { BasicComponent(标题, 说明, startAction=图标) }`，
 * 按用户要求**每个选项都带功能说明**（即 `BasicComponent` 的 `summary`，取 `Feature.description`）。
 */
@Composable
fun FeaturesScreen(bottomInnerPadding: Dp = 0.dp) {
    // Target 不是 Serializable 存不进 Bundle：rememberSaveable 只存选中索引（-1 = 未选中），
    // 按FeatureCatalog.targets 的固定顺序还原，旋屏后停留在二级页
    var selectedTargetIndex by rememberSaveable { mutableStateOf(-1) }
    val target = selectedTargetIndex.takeIf { it >= 0 }?.let { FeatureCatalog.targets.getOrNull(it) }
    BackHandler(enabled = target != null) { selectedTargetIndex = -1 }
    if (target == null) {
        TargetListScreen(bottomInnerPadding) { selectedTargetIndex = FeatureCatalog.targets.indexOf(it) }
    } else {
        // 二级页占满整个内容区，同样要避开悬浮底栏
        FeatureListScreen(
            target = target,
            onBack = { selectedTargetIndex = -1 },
            bottomInnerPadding = bottomInnerPadding,
        )
    }
}

/** 一级列表 —— 照抄 KernelSU `SuperUserMiuix` 的 `GroupItem` 卡。 */
@Composable
private fun TargetListScreen(bottomInnerPadding: Dp, onOpenTarget: (FeatureCatalog.Target) -> Unit) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current

    // 只展示**本机确实装了**的目标应用：没装的功能列出来也没意义（点了必然无效果）。
    // 例外（#8 净化入口方案 b）：iconKey 非空的虚拟目标（如「荣耀系统净化」，一个开关管
    // calendar/weather/browser/quickgame/youku/cloudmusic 的埋点与广告 SDK）非真实 app，
    // 跳过安装过滤恒显示。getApplicationInfo 依赖 QUERY_ALL_PACKAGES（已在 manifest 声明）。
    // 走 IO 线程：逐包跨进程查询不该压在组合期主线程上；null = 尚未返回（加载态）。
    val installedTargets by produceState<List<FeatureCatalog.Target>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            FeatureCatalog.targets.filter { target ->
                target.iconKey != null ||
                    runCatching { pm.getApplicationInfo(target.packageName, 0) }.isSuccess
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = "功能",
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
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 6.dp,
                bottom = bottomInnerPadding +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp,
            ),
            overscrollEffect = null,
        ) {
            item {
                SwitchSemanticsCard(
                    // 只讲"开关到底下没下发到进程"（框架未激活＝假反馈）；生效时机提示卡已按用户要求全局删除
                    noticeWhenInactive = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
            }
            when {
                installedTargets == null -> item { EmptyHint("正在读取设备上已安装的目标应用…") }
                installedTargets!!.isEmpty() -> item {
                    EmptyHint("设备上没有检测到支持的应用。\n本模块只支持荣耀系统应用和指定第三方应用，装上后会自动显示在这里。")
                }
                else -> items(count = installedTargets!!.size) { index ->
                    val target = installedTargets!![index]
                    TargetGroupCard(
                        target = target,
                        onClick = { onOpenTarget(target) },
                    )
                }
            }
        }
    }
}

/**
 * 目标真实应用图标。
 *
 * 图标源走 `getApplicationInfo(GET_META_DATA).loadIcon(pm)`：直接 `getApplicationIcon()`
 * 对荣耀/华为这类分体（split APK）系统应用会抛异常或退回系统默认灰块。
 *
 * 两个关键坑：
 * 1. **不能转 Bitmap**。`Drawable.toBitmap()` 对 `LayerDrawable` / `AnimatedVectorDrawable`
 *    会返回 null 或抛异常（成考题库 `com.exam8.chengrenGK` 就是这种），导致图标整块消失。
 * 2. **Drawable 必须先 `ConstantState.newDrawable(res, theme)` 重建并 measure**。
 *    跨进程/跨用户拿到的 Drawable 常带 `intrinsicWidth = -1` 且未 measure，
 *    直接 draw 是空白。
 */
@Composable
private fun rememberAppIcon(packageName: String): Painter? {
    val context = LocalContext.current
    // 图标加载挪出组合期：getApplicationInfo/loadIcon 是跨进程 Binder 调用，不得压主线程（P4）。
    // 加载完成前返回 null，调用方显示首字母色块占位。
    return produceState<Painter?>(initialValue = null, packageName) {
        value = withContext(Dispatchers.IO) { loadAppIcon(context, packageName) }
    }.value
}

/** 图标加载本体（IO 线程跑；见 [rememberAppIcon] 的坑位注释）。 */
private fun loadAppIcon(context: android.content.Context, packageName: String): Painter? {
    val pm = context.packageManager
    val appInfo = runCatching {
        pm.getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    }.getOrNull() ?: return null

    val raw = runCatching { appInfo.loadIcon(pm) }
        .recoverCatching { pm.getApplicationIcon(packageName) }
        .getOrNull() ?: return null

    // 重建 + 固定尺寸 measure，抹掉 intrinsicWidth=-1 与未 measure 的状态
    val drawable = runCatching {
        (raw.constantState?.newDrawable(context.resources, context.theme) ?: raw)
            .mutate()
    }.getOrDefault(raw)

    return DrawablePainter(drawable)
}

/**
 * 极简 Drawable → Painter 适配器。
 *
 * 不依赖 `androidx.compose.ui.res.rememberDrawablePainter`（该 API 在本项目的
 * Compose Multiplatform 依赖组合下不可解析），也不走 `Drawable.toBitmap()`
 * （对 `LayerDrawable`/`AnimatedVectorDrawable` 会失败导致图标整块消失）。
 */
private class DrawablePainter(private val drawable: Drawable) : Painter() {

    override val intrinsicSize: Size = Size(
        width = drawable.intrinsicWidth.takeIf { it > 0 }?.toFloat() ?: 144f,
        height = drawable.intrinsicHeight.takeIf { it > 0 }?.toFloat() ?: 144f,
    )

    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            val w = size.width.toInt().coerceAtLeast(1)
            val h = size.height.toInt().coerceAtLeast(1)
            drawable.setBounds(0, 0, w, h)
            drawable.draw(canvas.nativeCanvas)
        }
    }
}

/** iconKey → 内置 vector（虚拟目标专用）；未匹配返回 null 走 PackageManager 图标。 */
private fun vectorForIconKey(key: String?): ImageVector? = when (key) {
    "shield" -> Icons.Rounded.Shield
    else -> null
}

/**
 * 图标槽位：虚拟目标（iconKey 命中）画内置 vector；真实 app 画 PackageManager 图标；
 * 无图标画首字母色块（不留空白）。
 */
@Composable
private fun AppIconSlot(
    packageName: String,
    fallbackText: String,
    modifier: Modifier = Modifier,
    iconKey: String? = null,
) {
    val vector = vectorForIconKey(iconKey)
    val icon = if (vector == null) rememberAppIcon(packageName) else null
    when {
        vector != null -> {
            // 虚拟目标：圆角块底 + tint vector，与首字母兜底块同构
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    imageVector = vector,
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(colorScheme.onPrimaryContainer),
                    modifier = Modifier.fillMaxSize(0.62f),
                )
            }
        }
        icon != null -> Image(painter = icon, contentDescription = null, modifier = modifier)
        else -> {
            // 兜底：Miuix 次强调色圆角块 + 首字母，避免"图标空白"看起来像 bug
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = fallbackText.take(1).uppercase(),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onPrimaryContainer,
                )
            }
        }
    }
}

/** 目标分组卡 —— 照抄 KernelSU `GroupItem`。 */
@Composable
private fun TargetGroupCard(target: FeatureCatalog.Target, onClick: () -> Unit) {
    val isInDark = isInDarkTheme()

    // 状态标签配色沿用 GroupItem 的语义分层
    val customBg = colorScheme.secondaryContainer.copy(alpha = 0.8f)
    val customFg = colorScheme.onSecondaryContainer
    val activeBg = colorScheme.tertiaryContainer.copy(alpha = 0.6f)
    val activeFg = colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
    val countBg = if (isInDark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.3f)
    val countFg = if (isInDark) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.8f)

    val enabledCount = target.features.count { FeatureStore.isEnabled(it.key) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .padding(bottom = 12.dp),
        onClick = onClick,
        showIndication = true,
        insideMargin = PaddingValues(start = 10.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIconSlot(
                packageName = target.packageName,
                fallbackText = stringResource(target.nameRes),
                iconKey = target.iconKey,
                modifier = Modifier
                    .padding(end = 10.dp)
                    .size(48.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(target.nameRes),
                    modifier = Modifier.basicMarquee(),
                    fontWeight = FontWeight(550),
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
                Text(
                    text = "${target.packageName} · ${target.features.size} 项",
                    modifier = Modifier.basicMarquee(),
                    fontSize = 12.sp,
                    fontWeight = FontWeight(550),
                    color = colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                )
            }
            Column(
                modifier = Modifier.padding(start = 16.dp),
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatusTag(
                    label = "$enabledCount/${target.features.size}",
                    backgroundColor = if (enabledCount > 0) activeBg else countBg,
                    contentColor = if (enabledCount > 0) activeFg else countFg,
                )
                if (enabledCount > 0) {
                    StatusTag(label = "已启用", backgroundColor = customBg, contentColor = customFg)
                }
            }
            ArrowRightIcon()
        }
    }
}

/**
 * 顶部应用信息卡 —— 照抄 KernelSU 超级用户/模块详情页的头部：
 * 64dp 应用图标 + 应用名 + 包名 + 版本 + 右侧启用状态标签。
 */
@Composable
private fun AppHeaderCard(
    target: FeatureCatalog.Target,
    versionText: String?,
    enabledCount: Int,
    versionMismatch: Boolean,
    supportedText: String?,
    modifier: Modifier = Modifier,
) {
    val isInDark = isInDarkTheme()
    val activeBg = colorScheme.tertiaryContainer.copy(alpha = 0.6f)
    val activeFg = colorScheme.onTertiaryContainer.copy(alpha = 0.8f)
    val idleBg = if (isInDark) Color.White.copy(alpha = 0.4f) else Color.Black.copy(alpha = 0.3f)
    val idleFg = if (isInDark) Color.Black.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.8f)

    Card(modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppIconSlot(
                packageName = target.packageName,
                fallbackText = stringResource(target.nameRes),
                iconKey = target.iconKey,
                modifier = Modifier.size(44.dp),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp, end = 8.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = stringResource(target.nameRes),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
                // 虚拟目标（iconKey 非空）不透出宿主 app 元信息：包名/版本对跨 app 开关无意义
                if (target.iconKey == null) {
                    // 每行带色：版本适配行单独染色（颜色即语义），其余行维持弱化灰。
                    val meta: List<Pair<String, Color>> = listOfNotNull(
                        target.packageName to colorScheme.onSurfaceVariantSummary,
                        versionText?.let { "版本 $it" to colorScheme.onSurfaceVariantSummary },
                        // 版本适配行：仅按宿主版本卡的目标透出（supportedText null = 不显示）。
                        // 绿色判据 = 装机 versionName ∈ target.supportedVersions（含历史精确套）；红色 = 不在集合内。
                        // 红字保留「仍会尝试」的依据：MountEngine.checkHostVersionOnce 版本不一致只打 WARN，
                        // attemptMount 不跳过任何任务，故未适配仍会按基线表挂载——不写这半句用户会误读成「功能废了」。
                        // 措辞用「最新支持 X」而非「需 X」（2026-10-14 审计 P4）：宿主 OTA 到比 X 更新时，
                        // 「需」会被读成「需要降级到 X」；X = supportedVersions 升序末位，即本模块已适配的最新版本。
                        // 绿行带版本号（同上 P4）：装机版本落在 extraVersions（历史精确套）时，本行是唯一说明
                        // 「适配的是哪一套」的地方——与上一行「版本 Y」（本机版本）不等也说得清。
                        // 绿不加状态标签：绝大多数宿主即基线，恒真标签无信息量，且右侧已有「已启用/待启用」标签。
                        // 红也不再另给右上「版本未适配」tag（同上 P3）：同屏两处红表达同一事实，红字信息量更大。
                        supportedText?.let {
                            if (versionMismatch) {
                                "未适配（最新支持 $it，仍会尝试）" to VersionTagRed
                            } else {
                                "已适配 $it" to VersionTagGreen
                            }
                        },
                    )
                    meta.forEach { (line, color) ->
                        Text(
                            text = line,
                            fontSize = 11.sp,
                            color = color,
                            maxLines = 1,
                            softWrap = false,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = "已启用 $enabledCount/${target.features.size} 项功能",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (enabledCount > 0) activeFg else colorScheme.onSurfaceVariantSummary,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // 右侧标签列：只剩启用状态一行（原「版本未适配」红 tag 已删——meta 行红字已表达同一事实）
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                StatusTag(
                    label = if (enabledCount > 0) "已启用" else "待启用",
                    backgroundColor = if (enabledCount > 0) activeBg else idleBg,
                    contentColor = if (enabledCount > 0) activeFg else idleFg,
                    // 头部卡空间有限，用 11sp（原 13sp 偏大）；一级列表仍用 9sp
                    fontSize = 11.sp,
                )
            }
        }
    }
}

/** 二级列表 —— 照抄 KernelSU 超级用户详情页：顶部应用信息卡 + 左侧色条的功能开关列表。 */
@Composable
private fun FeatureListScreen(
    target: FeatureCatalog.Target,
    onBack: () -> Unit,
    bottomInnerPadding: Dp = 0.dp,
) {
    val scrollBehavior = MiuixScrollBehavior()
    val context = LocalContext.current

    // 应用信息（版本名），拿不到就留 null 不显示该行。
    // 注意：`ApplicationInfo.versionName/versionCode` 是 @hide 字段，SDK 里访问不到，
    // 必须走 `PackageInfo`。
    val pkgInfo: PackageInfo? = remember(target.packageName) {
        runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(target.packageName, 0)
        }.getOrNull()
    }
    val versionText: String? = remember(pkgInfo) {
        pkgInfo?.let { info ->
            val code = runCatching { info.longVersionCode }.getOrNull()
                ?: @Suppress("DEPRECATION") { info.versionCode.toLong() }
            "${info.versionName} ($code)"
        }
    }
    val enabledCount = target.features.count { FeatureStore.isEnabled(it.key) }

    // 宿主版本适配校验：baselineVersion 非 null = 按宿主版本卡；装机 versionName 不在受支持集合内时
    // 头部卡加红 tag 并在「已适配」行内透出当前版本（versionName 拿不到时不误报）。
    val hostVersioned = target.baselineVersion != null
    val installedVersionName = pkgInfo?.versionName
    val versionMismatch = hostVersioned && installedVersionName != null &&
        installedVersionName !in target.supportedVersions
    // 多版本适配集只在 meta 行透出一个（supportedVersions 已升序，last 即最新；旧版精确套属历史适配，
    // 逐个列反而引导用户降级）
    val supportedText = target.supportedVersions.lastOrNull()

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(target.nameRes),
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
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(
                top = innerPadding.calculateTopPadding() + 6.dp,
                bottom = bottomInnerPadding +
                    WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 12.dp,
            ),
            overscrollEffect = null,
        ) {
            item {
                SwitchSemanticsCard(
                    noticeWhenInactive = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
            }

            // ---- 顶部应用信息卡（KernelSU 详情页同款：图标 + 名称 + 包名/版本 + 状态标签）----
            item {
                AppHeaderCard(
                    target = target,
                    versionText = versionText,
                    enabledCount = enabledCount,
                    versionMismatch = versionMismatch,
                    // 非 hostVersioned（无 baselineVersion 的目标）不透出版本适配行
                    supportedText = supportedText.takeIf { hostVersioned },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 12.dp),
                )
            }

            items(count = target.features.size) { index ->
                val feature = target.features[index]
                // 直接读 FeatureStore 的可观察镜像：开关一变，本项、顶部「已启用 N/M」
                // 与一级列表的计数标签同时重组（原先用 item 局部 remember，只有本项会刷新）。
                val enabled = FeatureStore.isEnabled(feature.key)
                // 整个 Row 统一带上/下边距，保证左侧色条与卡片同步留白
                Row(
                    modifier = Modifier.padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左侧 6dp 彩色条：色相由 feature.key 哈希决定，稳定不跳变。
                    // 开启时满色 + 100% 不透明；关闭时淡化到 45% + 半透明，
                    // 避免用「变灰」表达关闭（灰色在深色模式下几乎看不见）。
                    val accent = rememberAccentColor(feature.key)
                    Box(
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .width(6.dp)
                            .height(36.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (enabled) accent else accent.copy(alpha = 0.45f)
                            )
                    )
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 6.dp, end = 12.dp),
                    ) {
                        // 不加图标：每个开关都重复同一个目标应用图标反而是噪声，
                        // 左侧色条（开=primary / 关=primaryContainer）已经能表达状态。
                        SwitchPreference(
                            title = stringResource(feature.titleRes),
                            // 用户要求：二级菜单每个选项都带功能说明
                            summary = stringResource(feature.descRes),
                            checked = enabled,
                            onCheckedChange = { FeatureStore.setEnabled(feature.key, it) },
                            // 卡片内边距：原来只设了 horizontal，内容贴着上下边缘很挤
                            insideMargin = PaddingValues(
                                start = 9.dp,
                                end = 9.dp,
                                top = 14.dp,
                                bottom = 14.dp,
                            ),
                        )
                    }
                }
            }
        }
    }
}

/**
 * 框架未激活提示卡 —— 唯一必须讲清楚、否则用户会误判的点：
 * **框架未激活**时 [FeatureStore.setEnabled] 只写本地，hook 侧读不到 ⇒ 开关看起来生效了
 * （UI 状态变了）而实际行为没变，属于最坏的一类"假反馈"。
 *
 * 原「开关何时生效」提示卡（重启目标进程才生效）已按用户要求全局删除（2026-10-14）。
 */
@Composable
private fun SwitchSemanticsCard(
    noticeWhenInactive: Boolean,
    modifier: Modifier = Modifier,
) {
    val inactive = noticeWhenInactive && !FrameworkState.active
    if (!inactive) return
    val title = "LSPosed 未激活"
    val summary = "LSPosed 未连接，开关暂时不会生效。请先在 LSPosed 中启用本模块。"
    val bg = colorScheme.tertiaryContainer.copy(alpha = 0.6f)
    val fg = colorScheme.onTertiaryContainer
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                text = title,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = fg,
            )
            Text(
                text = summary,
                fontSize = 11.sp,
                color = fg,
            )
        }
    }
}

/** 列表空态/加载态提示（此前列表为空时整屏空白，看起来像坏了）。 */
@Composable
private fun EmptyHint(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = colorScheme.onSurfaceVariantSummary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

/**
 * 按 feature key 稳定取一个彩色强调色（左侧色条用）。
 *
 * 用 `key.hashCode()` 索引固定调色板，而不是每次重组 `Random.nextInt()`：
 * 后者会在重组时变色，滚动列表会「闪成彩虹」，很廉价。
 * key → index 的映射是确定性的，同一个功能在任何时候都是同一个颜色。
 *
 * 色板取饱和度/亮度一致的一组，颜色之间跳变明显且在浅色/深色背景下都能看清。
 */

/** 「未适配」文字红色（Miuix colorScheme 无 error 槽，审计要求固定值；色值与 accentPalette 首色相同 = 历史遗留）。 */
private val VersionTagRed = Color(0xFFEF5350)

/**
 * 「已适配」文字绿色（Miuix colorScheme 无 success 槽，同上固定值）。
 *
 * 刻意**不取** accentPalette 里的「绿」0xFF66BB6A（2026-10-14 审计 P4）：那支是左侧功能色条的
 * 哈希调色板之一，两者同值会被连读成「某功能的色条」，且调色板改绿时本处"同值"承诺静默漂移。
 * 选深一档的 Material Green 700：浅色背景对比度更高，文字 11sp 也看得清。
 */
private val VersionTagGreen = Color(0xFF43A047)

private val accentPalette = listOf(
    Color(0xFFEF5350), // 红
    Color(0xFFEC407A), // 粉
    Color(0xFFAB47BC), // 紫
    Color(0xFF7E57C2), // 深紫
    Color(0xFF5C6BC0), // 靛蓝
    Color(0xFF42A5F5), // 天蓝
    Color(0xFF26C6DA), // 青
    Color(0xFF26A69A), // 蓝绿
    Color(0xFF66BB6A), // 绿
    Color(0xFF9CCC65), // 黄绿
    Color(0xFFFFCA28), // 琥珀
    Color(0xFFFF7043), // 橙
    Color(0xFF8D6E63), // 棕
    Color(0xFF78909C), // 蓝灰
)

@Composable
private fun rememberAccentColor(key: String): Color = remember(key) {
    accentPalette[Math.floorMod(key.hashCode(), accentPalette.size)]
}
@Composable
internal fun StatusTag(
    label: String,
    modifier: Modifier = Modifier,
    backgroundColor: Color,
    contentColor: Color,
    fontSize: androidx.compose.ui.unit.TextUnit = 9.sp,
) {
    Box(modifier = modifier.background(color = backgroundColor, shape = RoundedCornerShape(6.dp))) {
        Text(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            text = label,
            color = contentColor,
            fontSize = fontSize,
            fontWeight = FontWeight(750),
            maxLines = 1,
            softWrap = false,
        )
    }
}

/** 右箭头（照抄 GroupItem 末尾的箭头，RTL 时翻转）。 */
@Composable
private fun ArrowRightIcon() {
    val layoutDirection = LocalLayoutDirection.current
    Image(
        modifier = Modifier
            .graphicsLayer { if (layoutDirection == LayoutDirection.Rtl) scaleX = -1f }
            .padding(start = 8.dp)
            .size(width = 10.dp, height = 16.dp),
        imageVector = MiuixIcons.ChevronForward,
        contentDescription = null,
        colorFilter = ColorFilter.tint(colorScheme.onSurfaceVariantActions),
    )
}
