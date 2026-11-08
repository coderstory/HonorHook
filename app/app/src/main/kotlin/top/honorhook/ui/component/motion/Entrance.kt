package top.honorhook.ui.component.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * 进场编排（2026-11-08 实验分支 `experiment/ui-overhaul`，概念「花园晨启」）。
 *
 * 为什么是这套形状：
 * - **只暴露 Modifier，不给 Card 加参数**：全工程 44 处 `Card(` 共用 `component/Card.kt` 包装件，
 *   但那 44 处里约 30 处是固定卡（没有循环序号）。给包装件加 `enterIndex` 要改 44 个调用点；
 *   导出 `Modifier.cardEntrance(order)` 则由调用方只在自己关心的位置挂，Dialog 内的卡（工具箱子页
 *   弹窗、FileOrganizer 计划弹窗）**天然不挂**——弹窗不该每次打开都播一遍进场。
 * - **一次性播放用宿主的 Set 登记，不用 Composable 局部 remember**：LazyColumn 滚出屏幕会销毁
 *   item 的 remember，回来时若只靠局部状态就会重播（闪烁）。登记表放在每个 tab 一个的
 *   [EntranceState] 里，滚回来的卡片直接落到终态。
 * - **闸门（[LocalEntranceGate]）**：冷启动时 splash 盖在上面 2.8s，若进场在屏下就播完，
 *   用户永远看不到。闸门关闭时 effect 不启动（不是"播了但看不见"），splash 开始淡出才开闸。
 * - 位移/旋转/透明度全部走 `graphicsLayer` 的 lambda 读 [Animatable].value，**只失效 layer、
 *   不触发重组**；与 `Fireflies` 的 `withFrameNanos` 范式同源（那边是为了在 draw 阶段读）。
 *
 * 已知取舍：不用 `Modifier.animateContentSize` / `animateFloatAsState` —— 前者的尺寸动画会与
 * LazyColumn 测量互相抖动，后者的值在组合阶段读会把每帧都变成重组。
 */
object EntranceTokens {
    /** 起点在屏幕下缘之外多少（视觉上从"屏幕外"托起来；太大会等太久，太小像本地抖动） */
    val Travel = 190.dp

    /** 奇偶卡横向扇开量：让一列卡片像被手一扇托开，而不是整列平移 */
    val Fan = 40.dp

    /** 起始缩放；落位后退回 1f，给"从远处飘近"的纵深 */
    const val ScaleFrom = 0.88f

    /** 起始倾角（度），按序号奇偶取 ±，落位归零 */
    const val Rotate = 2.8f

    /** 逐张错峰；封顶 [StaggerCapMs] 保证长列表不会等到最后一张才落位 */
    const val StaggerMs = 62
    const val StaggerCapMs = 400

    /** 卡片内元素的逐行浮现节奏（比卡片晚一拍开始） */
    const val RevealBaseDelayMs = 160
    const val RevealStaggerMs = 42
}

/**
 * 一次「开场」的播放登记表。一个 tab 一个（`MainActivity` 的 `AnimatedContent` 里 remember），
 * 切 tab 重建 ⇒ 新 tab 的卡片重新入场；同一 tab 内二级页切换复用同一张表（已播过的不重播）。
 */
class EntranceState {
    private val played = mutableSetOf<Any>()

    /** true = 这张卡还没播过（调用方负责播）；false = 已在本次开场里落位过，直接给终态。 */
    fun claim(key: Any): Boolean = played.add(key)
}

/** 缺省 null：没人提供时退化为"总是播放"（预览/单测里单独用某个 Composable 也不会哑掉）。 */
val LocalEntranceHost = staticCompositionLocalOf<EntranceState?> { null }

/** 缺省 true：没有 splash 的场景（二级页、预览）直接开闸。 */
val LocalEntranceGate = staticCompositionLocalOf { true }

/**
 * 卡片进场：从屏幕下缘之外带轻微倾斜/缩放飞入，按 [order] 错峰。
 *
 * [key] 缺省用 [order]；同一屏里若有多个互不相干的列表且序号都从 0 起，传一个稳定字符串区分
 * （例如 `key = "tool.tile.$id"`），否则第二组会被登记表当成"已播过"。
 */
@Composable
fun Modifier.cardEntrance(order: Int = 0, key: Any? = null): Modifier {
    val host = LocalEntranceHost.current
    val gate = LocalEntranceGate.current
    val slot = key ?: order
    // 只在 host/slot 变化时登记一次；LazyColumn 回收后重进会重新登记并拿到 false（终态，不重播）
    val fresh = remember(host, slot) { host?.claim(slot) ?: true }
    val progress = remember { Animatable(if (fresh) 0f else 1f) }
    var settled by remember { mutableStateOf(!fresh) }

    LaunchedEffect(fresh, gate) {
        if (!fresh || !gate || settled) return@LaunchedEffect
        delay((order * EntranceTokens.StaggerMs).coerceAtMost(EntranceTokens.StaggerCapMs).toLong())
        progress.animateTo(
            targetValue = 1f,
            animationSpec = spring(dampingRatio = 0.76f, stiffness = 210f),
        )
        settled = true
    }

    if (settled) return this

    val sway = if (order % 2 == 0) -1f else 1f
    return this.graphicsLayer {
        val p = progress.value
        val remain = 1f - p
        // 前 45% 行程就把透明度拉满：卡片底色本身已是 0.62/0.72 磨砂，再叠长时间半透明会显脏
        alpha = (p * 2.2f).coerceIn(0f, 1f)
        translationY = remain * EntranceTokens.Travel.toPx()
        translationX = sway * remain * EntranceTokens.Fan.toPx()
        rotationZ = sway * remain * EntranceTokens.Rotate
        val scale = EntranceTokens.ScaleFrom + (1f - EntranceTokens.ScaleFrom) * p
        scaleX = scale
        scaleY = scale
        transformOrigin = TransformOrigin(0.5f, 0.5f)
    }
}

/**
 * 底栏的「落座」：整条从屏幕下缘之下升起 26dp 并淡入，比卡片晚一拍（120ms）。
 *
 * 为什么单独一个而不是复用 [revealFade]：8dp 的上浮对整条 88dp 高的药丸底栏几乎看不出位移，
 * 而底栏是**唯一压在卡片之上的常驻件**——它若和卡片同帧出现，会像"贴纸"钉在画面上；
 * 晚一拍升起，视觉上先有内容、再有导航，顺序才像同一个世界里的东西。
 */
@Composable
fun Modifier.dockRise(): Modifier {
    val gate = LocalEntranceGate.current
    val progress = remember { Animatable(0f) }
    var settled by remember { mutableStateOf(false) }

    LaunchedEffect(gate) {
        if (!gate || settled) return@LaunchedEffect
        delay(120)
        progress.animateTo(1f, tween(durationMillis = 520, easing = FastOutSlowInEasing))
        settled = true
    }

    if (settled) return this
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 26.dp.toPx()
    }
}

/**
 * 卡片内元素（分组标题、信息行）的逐行浮现：只让文字与图标淡入 + 8dp 上移，
 * 不再从屏幕外飞 —— 一张卡里所有元素都飞会互相打架。
 *
 * 与 [cardEntrance] 的区别：这里**不需要登记表**。这些元素都活在自己那张卡的组合树里，
 * 卡被回收时它们一起没了；回来时用户看到的是整张卡（已落位），逐行浮现重播一次也无感。
 */
@Composable
fun Modifier.revealFade(order: Int = 0, baseDelayMs: Int = EntranceTokens.RevealBaseDelayMs): Modifier {
    val gate = LocalEntranceGate.current
    val progress = remember { Animatable(0f) }
    var settled by remember { mutableStateOf(false) }

    LaunchedEffect(gate) {
        if (!gate || settled) return@LaunchedEffect
        delay((baseDelayMs + order * EntranceTokens.RevealStaggerMs).toLong())
        progress.animateTo(1f, tween(durationMillis = 360, easing = FastOutSlowInEasing))
        settled = true
    }

    if (settled) return this
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 8.dp.toPx()
    }
}
