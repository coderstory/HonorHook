package top.honorhook.ui.component

import android.app.Activity
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowInsetsControllerCompat
import top.honorhook.ui.AppSettings
import top.honorhook.ui.ColorMode
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

/** 当前颜色模式的原始 value（0-6），供 `isInDarkTheme()` 判定。 */
val LocalColorMode = staticCompositionLocalOf { 0 }

/**
 * 科技蓝主题的共享动画相位（0..1 循环；null = 该主题未启用）。
 * 提供的是 `State` 而不是 `Float`：卡片在 `onDrawWithContent` 里读 `.value`，
 * 每帧只触发重绘（draw 阶段读快照），不会重组 40 多张卡 —— 全工程只跑这一个动画时钟。
 */
val LocalTechPulse = staticCompositionLocalOf<State<Float>?> { null }

/** 科技蓝主题色（与背景皮肤 `TechBackdrop` / 卡片包边同一组色相）。 */
private val TechPrimary = Color(0xFF2E9BFF)

/**
 * 科技蓝主题的脉动相位：4.2s 一轮、线性、循环重启（卡片把它折成三角波用）。
 * 只在主题开启时才建动画；关着的用户不会为此持续重绘。
 */
@Composable
private fun techPulse(enabled: Boolean): State<Float>? = if (enabled) {
    rememberInfiniteTransition(label = "techPulse").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "techPulseSweep",
    )
} else null

// 原 KernelSU 风格的 `LocalEnableBlur` / `LocalEnableFloatingBottomBar` / `LocalEnableFloatingBottomBarBlur`
// 三个 CompositionLocal 已删除：全工程只 provide、无人 `.current` 读取（MainActivity 改为直接订阅
// AppSettings 的 State，见其 `AppRoot` 注释），留着只会让"改了开关没反应"更难查。

/**
 * 应用主题（**Miuix**，照抄 KernelSU `MiuixKernelSUTheme`）。
 *
 * Miuix 自带 `ThemeController`，把「颜色模式（系统/浅/深）+ 关键色」一次交给它，
 * 由 Miuix 内部生成色板。Monet 跟随壁纸取色与色彩风格/规范两档已于 2026-11-08 删除，
 * 唯一关键色是 `AppSettings.artworkKeyColor`（插画主色）。
 */
@Composable
fun HonorHookTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorMode = AppSettings.colorMode
    val techPulseState = techPulse(AppSettings.techSkin)
    val darkTheme = colorMode.isDark || (colorMode.isSystem && isSystemInDarkTheme())

    // 颜色不再可配（2026-11-08 用户删掉「跟随壁纸取色 / 关键色 / 配色风格 / 色规范」）：
    // 整套色板只从插画主色这一个种子生成，主题页只剩「明暗模式」+ 背景皮肤。
    // 2026-11-11 用户实测「主题色只对 splash 生效」两连：miuix `currentColors` 的
    // System 分支走壁纸动态色、Light/Dark 分支用构造器默认的内置色板（`lightColors`/
    // `darkColors` val），`keyColor` 只是存着不参与取色——真机截图实锤（绯红选中、开关仍蓝）。
    // colorsFromSeed 是 internal 调不到；materialkolor 也是 miuix 的 implementation 传递依赖。
    // 最小可靠修法：显式传 lightColors/darkColors，只覆盖 primary（及深色下调亮），其余色板
    // 用 miuix 默认——按钮/开关/卡片包边全部读 colorScheme.primary，主题色即全量生效。
    // 科技蓝主题：主色与关键色整体换成电蓝（按钮/开关/滑块/顶栏底栏都读 colorScheme.primary）
    val tech = AppSettings.techSkin
    val seed = if (tech) TechPrimary else Color(AppSettings.artworkKeyColor)
    val controller = ThemeController(
        colorSchemeMode = if (darkTheme) ColorSchemeMode.Dark else ColorSchemeMode.Light,
        lightColors = lightColorScheme(primary = seed),
        darkColors = darkColorScheme(primary = seed.copy(alpha = 1f)),
        keyColor = seed,
        isDark = darkTheme,
    )

    MiuixTheme(
        controller = controller,
    ) {
        // 卡片磨砂半透明（2026-11-08 用户要求「所有的卡片」）：miuix `Card` 默认色 =
        // `colorScheme.surfaceContainer`，在主题层统一压一个 alpha，比逐个 Card 传 colors 干净
        // （散点改必漏）。alpha 不能太低：卡片底下现在是整幅插画，透太多字会跟画搅在一起。
        // 2026-11-08（artwork-theme）：插画背景比原来的花园更花，用户反馈卡片要「再白一点」，
        // 浅色 0.72 → 0.80、深色 0.62 → 0.72（不是不透明，还要留一点「画在玻璃后面」的层次）。
        val baseScheme = MiuixTheme.colorScheme
        // 科技蓝把卡片换成「深蓝玻璃」：深色近墨蓝、浅色冷白，避免白磨砂压在深蓝网格上发灰；
        // 主色同时钉成电蓝（种子已改，这里再兜一层，防止 miuix 某些分支回落内置 primary）。
        val frostedScheme = baseScheme.copy(
            surfaceContainer = when {
                tech && darkTheme -> Color(0xFF0C1C33).copy(alpha = 0.86f)
                tech -> Color(0xFFF4F9FF).copy(alpha = 0.88f)
                darkTheme -> baseScheme.surfaceContainer.copy(alpha = 0.72f)
                else -> baseScheme.surfaceContainer.copy(alpha = 0.80f)
            },
            primary = if (tech) TechPrimary else baseScheme.primary,
        )
        MiuixTheme(colors = frostedScheme) {
            CompositionLocalProvider(
                LocalColorMode provides colorMode.value,
                LocalTechPulse provides techPulseState,
                LocalContentColor provides MiuixTheme.colorScheme.onBackground,
            ) {
                LaunchedEffect(darkTheme) {
                    val window = (context as? Activity)?.window ?: return@LaunchedEffect
                    WindowInsetsControllerCompat(window, window.decorView).apply {
                        isAppearanceLightStatusBars = !darkTheme
                        isAppearanceLightNavigationBars = !darkTheme
                    }
                }
                content()
            }
        }
    }
}

/**
 * 科技蓝下把"每格一个彩色"的强调色收敛到同一条青蓝色阶（2026-11-27 用户：
 * 「还是那种彩色背景加涂鸦风格」—— 只换底色、彩色徽标还在，整体仍旧读成原来那套）。
 *
 * 按亮度分三档而不是全压成同一个色：一个青蓝会让 20 多张卡失去区分度，
 * 三档仍有层次，但色相锁在 200~210 度，不再出现彩虹。
 */
fun techAccent(base: Color): Color {
    if (!AppSettings.techSkin) return base
    val l = base.luminance()
    return when {
        l < 0.28f -> Color(0xFF2E9BFF)
        l < 0.62f -> Color(0xFF35E0FF)
        else -> Color(0xFF9AE8FF)
    }
}

/**
 * 各主题的"版式令牌"。2026-11-27 用户：主题之间要像安卓换主题那样**完全不一样**，
 * 不是同一套界面换张背景。所以形状/密度/描边这些也跟着主题走，集中在这里取值，
 * 组件只问令牌、不写死 dp —— 加第四套主题时改这一处，而不是回头找散落各处的圆角。
 */
object ThemeShape {

    /** 卡片圆角：插画/花园是柔和大圆角；科技蓝走硬边（图上"仪表盘"的观感差一半来自这里）。 */
    fun cardRadius(base: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
        if (AppSettings.techSkin) 8.dp else base

    /** 徽标圆角：默认接近圆形；科技蓝切成小方角。 */
    fun badgeCorner(base: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
        if (AppSettings.techSkin) 3.dp else base

    /** 磁贴内图标底框圆角。 */
    fun tileInnerCorner(base: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
        if (AppSettings.techSkin) 5.dp else base

    /** 科技蓝的徽标是实心块 + 硬描边，不用渐变质感和顶部高光。 */
    val flatBadge: Boolean get() = AppSettings.techSkin

    /** 卡片内边距：科技蓝更紧凑（仪表盘密度），其它主题保持原来的宽松。 */
    fun cardPadding(base: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
        if (AppSettings.techSkin) base * 0.75f else base
}

/** 是否深色（照抄 KernelSU `isInDarkTheme`）。 */
@Composable
@ReadOnlyComposable
fun isInDarkTheme(): Boolean = when (LocalColorMode.current) {
    1, 4 -> false  //强制浅色
    2, 5, 6 -> true   // 强制深色
    else -> isSystemInDarkTheme()  // 跟随系统
}

// 原有 pageBackground / cardColor / secondaryText / accentPrimary / onPrimary 五个颜色 helper
// 无任何调用者（各页面直接读 `MiuixTheme.colorScheme`），已删除。