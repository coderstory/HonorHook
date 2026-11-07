package top.honorhook.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 首页背景的萤火虫（2026-11-08 用户需求：「主界面能加动态萤火虫效果？」）。
 *
 * 设计取舍：
 * - **不引入粒子库**：只是「漂浮 + 明灭」，Canvas 画三层同心圆就够，省一个依赖和几十 KB。
 * - **状态读在 draw 阶段**：时间变量是 [mutableFloatStateOf]，只在 Canvas 的 draw lambda 里读，
 *   快照系统只会让这一层重绘，不会触发重组（否则每帧重组整棵首页树）。
 * - **帧循环用 withFrameNanos**：跟屏幕刷新对齐，系统进入 doze / 页面不可见时自然停摆，
 *   不像 `while(true) { delay(16) }` 那样在后台空转。
 * - **粒子参数一次算好**：种子固定，每次进首页布局一致（不会因为重新组合就换一套位置）。
 * - **配色跟主题**：深色用黄绿 `#C6FF6E`（萤火虫本色），浅色用偏橄榄的 `#8FBF4A` 并整体降透明度——
 *   亮底上高饱和黄绿会发灰，看着像脏点而不是光。
 *
 * 只放在首页（HomeScreen）的内容层后面：其它页是列表 / 设置页，密集文字上飘光点会干扰阅读。
 */
private const val COUNT = 34

/** 单只萤火虫的固定参数（位置、路径、节奏），只在首次进首页时随机一次 */
private class Firefly(
    val baseX: Float,
    val baseY: Float,
    val driftX: Float,
    val driftY: Float,
    val speedX: Float,
    val speedY: Float,
    val phase: Float,
    val blinkSpeed: Float,
    val sizePx: Float,
    /** 每秒往上飘的归一化距离（屏高比例）；到顶回到底部，像真的往上飞 */
    val rise: Float,
)

@Composable
fun Fireflies(modifier: Modifier = Modifier) {
    val dark = isInDarkTheme()
    // 一次算好：不同屏幕比例下按归一化坐标分布，再乘实际尺寸
    val flies = remember(COUNT) {
        val rnd = Random(20261108)
        List(COUNT) {
            Firefly(
                baseX = rnd.nextFloat(),
                baseY = rnd.nextFloat(),
                driftX = 0.03f + rnd.nextFloat() * 0.05f,
                driftY = 0.02f + rnd.nextFloat() * 0.04f,
                // 首版 0.05~0.15 rad/s 慢到 1~2px/s（肉眼「不动」），第二版 0.35~0.9 又偏快。
                // 现在取中段：0.18~0.42（周期 15~35s），每秒约 6~14px，看得出在飘但不抢视线。
                speedX = 0.18f + rnd.nextFloat() * 0.24f,
                speedY = 0.16f + rnd.nextFloat() * 0.22f,
                phase = rnd.nextFloat() * 6.283f,
                blinkSpeed = 0.12f + rnd.nextFloat() * 0.26f,
                sizePx = 1.0f + rnd.nextFloat() * 1.1f,
                rise = 0.004f + rnd.nextFloat() * 0.007f,
            )
        }
    }

    // 秒级时间；在 draw 阶段读，避免每帧重组
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var origin = 0L
        while (true) {
            withFrameNanos { now ->
                if (origin == 0L) origin = now
                time.floatValue = (now - origin) / 1_000_000_000f
            }
        }
    }

    val core = if (dark) Color(0xFFC6FF6E) else Color(0xFF8FBF4A)
    val aura = if (dark) Color(0xFF6BFFA8) else Color(0xFFB7D98A)
    // 亮底上要收着点，否则一片糊成浅黄
    val strength = if (dark) 1f else 0.72f

    Canvas(modifier = modifier.fillMaxSize()) {
        val t = time.floatValue
        val w = size.width
        val h = size.height

        flies.forEach { f ->
            // 横向：正弦左右游走；纵向：正弦上下摆 + 持续上升（到顶回绕）
            // 只有正弦没有净位移的话，虫子会在一个小范围里「原地晃」，看不出在飞。
            val x = (f.baseX + f.driftX * sin(t * f.speedX + f.phase)) * w
            val yNorm = (f.baseY - f.rise * t + f.driftY * sin(t * f.speedY + f.phase * 1.7f))
            val y = ((yNorm % 1f) + 1f) % 1f * h

            // 明灭：sin 取三次方让「亮起来」短促、「暗下去」长，才像萤火虫；
            // 再乘一个很慢的整体呼吸，避免所有虫子同一节奏。
            // 0.18 的底噪是必须的——纯三次方大多数时间接近 0，画面上等于没有（首版就栽在这）。
            val wave = (sin(t * f.blinkSpeed * 6.283f + f.phase) + 1f) * 0.5f
            val pulse = 0.18f + 0.82f * (wave * wave * wave)
            val breathe = 0.82f + 0.18f * sin(t * 0.18f + f.phase * 0.3f)
            // 回绕时在顶 / 底边缘淡出淡入，否则虫子飞出屏幕又「啪」地出现在另一头
            val edge = minOf(1f, (yNorm / 0.08f).coerceIn(0f, 1f), ((1f - yNorm) / 0.06f).coerceIn(0f, 1f))
            val a = pulse * breathe * strength * edge

            val r = (1.5f + 1.7f * pulse) * f.sizePx.dp.toPx() * 1.2f

            // 三层同心圆凑出「光点 + 光晕」：比径向渐变省一次 Brush 分配，视觉上分不出来。
            // 光晕收窄（5.0/2.4 → 3.6/1.8）：太大在低透明度下就是一团「闪烁的圆」，不像虫子。
            drawCircle(color = aura.copy(alpha = a * 0.10f), radius = r * 3.6f, center = Offset(x, y))
            drawCircle(color = core.copy(alpha = a * 0.22f), radius = r * 1.8f, center = Offset(x, y))
            drawCircle(color = core.copy(alpha = a.coerceAtMost(1f)), radius = r, center = Offset(x, y))
        }
    }
}
