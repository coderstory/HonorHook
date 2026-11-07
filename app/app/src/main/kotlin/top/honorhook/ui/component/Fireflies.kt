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
private const val COUNT = 22

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
                driftX = 0.03f + rnd.nextFloat() * 0.06f,
                driftY = 0.02f + rnd.nextFloat() * 0.05f,
                speedX = 0.05f + rnd.nextFloat() * 0.10f,
                speedY = 0.04f + rnd.nextFloat() * 0.09f,
                phase = rnd.nextFloat() * 6.283f,
                blinkSpeed = 0.25f + rnd.nextFloat() * 0.55f,
                sizePx = 1.1f + rnd.nextFloat() * 1.4f,
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
            // 双正弦叠加 = 自然的绕圈漫游，避免直线漂移那种「子弹」感
            val x = (f.baseX + f.driftX * sin(t * f.speedX + f.phase)) * w
            val y = (f.baseY + f.driftY * sin(t * f.speedY + f.phase * 1.7f)) * h

            // 明灭：sin 取三次方让「亮起来」短促、「暗下去」长，才像萤火虫；
            // 再乘一个很慢的整体呼吸，避免所有虫子同一节奏。
            // 0.18 的底噪是必须的——纯三次方大多数时间接近 0，画面上等于没有（首版就栽在这）。
            val wave = (sin(t * f.blinkSpeed * 6.283f + f.phase) + 1f) * 0.5f
            val pulse = 0.18f + 0.82f * (wave * wave * wave)
            val breathe = 0.82f + 0.18f * sin(t * 0.35f + f.phase * 0.3f)
            val a = pulse * breathe * strength

            val r = (1.9f + 2.1f * pulse) * f.sizePx.dp.toPx() * 1.35f

            // 三层同心圆凑出「光点 + 光晕」：比径向渐变省一次 Brush 分配，视觉上分不出来
            drawCircle(color = aura.copy(alpha = a * 0.10f), radius = r * 5.0f, center = Offset(x, y))
            drawCircle(color = core.copy(alpha = a * 0.20f), radius = r * 2.4f, center = Offset(x, y))
            drawCircle(color = core.copy(alpha = a * 0.92f), radius = r, center = Offset(x, y))
        }
    }
}
