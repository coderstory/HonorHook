package top.honorhook.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box

/**
 * 「科技蓝」背景皮肤（2026-11-27 用户要求：第三套主题，科技蓝风格、要时尚）。
 *
 * 第四套皮肤，与插画 / 花园并列（三选一，互斥逻辑在 `AppSettings`）。和花园一样**只画装饰、
 * 不铺页面底色**：卡片仍不透明 ⇒ 文字对比度不受影响；各页 Scaffold 让出透明底才看得见。
 *
 * 构成（自上而下）：
 * 1. **深空渐变底**：顶部近黑蓝 → 中段靛蓝 → 底部回暗，模拟「机舱窗外」纵深
 * 2. **两团光晕**：右上青（`#35E0FF`）、左下电蓝（`#2F6BFF`），大半径径向渐变，中心区不压画面
 * 3. **网格**：28dp 一格，细线 1px；每 4 格一条主线（更亮）⇒ 读起来是仪表盘网格而不是方格纸
 * 4. **地平线光带**：0.58 屏高一条横向渐变线（中间亮、两端透明），给整屏一个视觉锚
 * 5. **顶部/底部压深**：托住 TopAppBar 标题与最下一张卡（浅色用白雾，深色用黑雾）
 *
 * 全静态（2026-11-28 用户：「删除粒子效果」——粒子潮汐版只活了十几分钟就撤了，
 * 背景回到画一次不动；动效只留 ⑤ 扫描线那一条 4.2s 的时钟）。
 */

private val TechCyan = Color(0xFF35E0FF)
private val TechBlue = Color(0xFF2F6BFF)

/** 网格步长（dp）：28 是 4 的倍数，主线每 4 格一条 = 112dp，手机上大约 4 条主线 / 屏。 */
private const val GRID_DP = 28f

/** 每 4 格一条主线。 */
private const val MAJOR_EVERY = 4

@Composable
fun TechBackdrop(
    modifier: Modifier = Modifier,
    dark: Boolean,
) {
    // 深浅色两套色板：浅色靠白雾压亮度、深色靠黑雾压亮度，网格线共用蓝/青但透明度分开调
    val base = if (dark) {
        listOf(Color(0xFF04070F), Color(0xFF081226), Color(0xFF03060C))
    } else {
        listOf(Color(0xFFF7FBFF), Color(0xFFE6F0FF), Color(0xFFF4F9FF))
    }
    val gridMinor = if (dark) TechCyan.copy(alpha = 0.07f) else TechBlue.copy(alpha = 0.07f)
    val gridMajor = if (dark) TechCyan.copy(alpha = 0.16f) else TechBlue.copy(alpha = 0.14f)
    val glowCyan = if (dark) 0.26f else 0.14f
    val glowBlue = if (dark) 0.22f else 0.10f
    val horizon = if (dark) TechCyan.copy(alpha = 0.45f) else TechBlue.copy(alpha = 0.32f)
    val veilTop = if (dark) Color(0xFF04070F).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.42f)
    val veilBottom = if (dark) Color(0xFF03060C).copy(alpha = 0.72f) else Color.White.copy(alpha = 0.55f)
    // 扫描线的相位来自主题层那**一个**共享时钟（`LocalTechPulse`）；这里拿到的是 State 本身，
    // 在 draw 阶段读 `.value` ⇒ 每帧只重绘背景这一层，不会重组整屏页面。
    val pulseState = LocalTechPulse.current

    Box(modifier = modifier.fillMaxSize()) {
      Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // ① 深空渐变底
        drawRect(
            brush = Brush.verticalGradient(
                0f to base[0],
                0.45f to base[1],
                1f to base[2],
            ),
        )

        // ② 光晕：右上青 / 左下电蓝。半径取屏宽量级 ⇒ 只在中段悬着一层辉，不是两个圆斑
        val cyanR = w * 0.95f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(TechCyan.copy(alpha = glowCyan), Color.Transparent),
                center = Offset(w * 0.86f, h * 0.06f),
                radius = cyanR,
            ),
            radius = cyanR,
            center = Offset(w * 0.86f, h * 0.06f),
        )
        val blueR = w * 0.85f
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(TechBlue.copy(alpha = glowBlue), Color.Transparent),
                center = Offset(w * 0.10f, h * 0.94f),
                radius = blueR,
            ),
            radius = blueR,
            center = Offset(w * 0.10f, h * 0.94f),
        )

        // ③ 网格：1px 细线 + 每 4 格主线。只用 drawLine，比 Path 批绘更省（格子数 ≈ 300）
        val step = GRID_DP.dp.toPx()
        if (step > 1f) {
            var i = 0
            var x = 0f
            while (x <= w) {
                drawLine(
                    color = if (i % MAJOR_EVERY == 0) gridMajor else gridMinor,
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f,
                )
                i++
                x += step
            }
            i = 0
            var y = 0f
            while (y <= h) {
                drawLine(
                    color = if (i % MAJOR_EVERY == 0) gridMajor else gridMinor,
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f,
                )
                i++
                y += step
            }
        }

        // ④ 地平线光带：一条中间亮、两端透明的横线 + 线上方 60dp 的淡辉
        val horizonY = h * 0.58f
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                1f to (if (dark) TechCyan.copy(alpha = 0.10f) else TechBlue.copy(alpha = 0.07f)),
                startY = horizonY - 60.dp.toPx(),
                endY = horizonY,
            ),
            topLeft = Offset(0f, horizonY - 60.dp.toPx()),
            size = androidx.compose.ui.geometry.Size(w, 60.dp.toPx()),
        )
        drawLine(
            brush = Brush.horizontalGradient(
                0f to Color.Transparent,
                0.5f to horizon,
                1f to Color.Transparent,
            ),
            start = Offset(0f, horizonY),
            end = Offset(w, horizonY),
            strokeWidth = 1.5f.dp.toPx(),
        )

        // ⑤ 扫描线（2026-11-27 用户要「动效」）：4.2s 从顶扫到底的一道青光。
        // 只在科技蓝主题下存在（pulseState 为 null 时整段跳过，其它主题零开销）。
        pulseState?.let { state ->
            val sweep = state.value
            val beamY = h * (sweep * 1.16f - 0.08f)
            val beamColor = if (dark) TechCyan else TechBlue
            val trail = 120.dp.toPx()
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to beamColor.copy(alpha = if (dark) 0.10f else 0.06f),
                    startY = beamY - trail,
                    endY = beamY,
                ),
                topLeft = Offset(0f, (beamY - trail).coerceAtLeast(0f)),
                size = Size(w, trail),
            )
            drawLine(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent,
                    0.5f to beamColor.copy(alpha = if (dark) 0.38f else 0.24f),
                    1f to Color.Transparent,
                ),
                start = Offset(0f, beamY),
                end = Offset(w, beamY),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // ⑥ 上下压深：托住标题栏与最后一张卡（也让状态栏小字在浅色主题下可读）
        drawRect(
            brush = Brush.verticalGradient(
                0f to veilTop,
                0.22f to Color.Transparent,
                0.80f to Color.Transparent,
                1f to veilBottom,
            ),
        )
      }
    }
}
