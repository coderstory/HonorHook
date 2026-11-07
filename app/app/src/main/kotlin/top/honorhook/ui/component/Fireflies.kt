package top.honorhook.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameNanos
import kotlin.math.min
import kotlin.math.sin
import top.honorhook.ui.AppSettings
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
private const val COUNT = 32

/**
 * 萤火虫配色档位（2026-11-08 用户要求「多一些颜色选择」）。
 *
 * 每档给两组色：深色主题要求高亮度才「发光」，浅色主题必须换成同色相的深色版本，
 * 否则白底上的高亮色会发灰、看着像脏点。
 */
enum class FireflyTint(
    val id: String,
    val label: String,
    val darkCore: Color,
    val darkAura: Color,
    val lightCore: Color,
    val lightAura: Color,
) {
    RAINBOW("rainbow", "彩虹", Color(0xFF7CF7C4), Color(0xFF7CF7C4), Color(0xFF3E8F7A), Color(0xFF3E8F7A)),
    LIME("lime", "萤绿", Color(0xFFC6FF6E), Color(0xFF6BFFA8), Color(0xFF8FBF4A), Color(0xFFB7D98A)),
    GOLD("gold", "暖金", Color(0xFFFFD166), Color(0xFFFFB347), Color(0xFFC08A2E), Color(0xFFE0C07A)),
    AMBER("amber", "蜜橙", Color(0xFFFFA95E), Color(0xFFFF7A45), Color(0xFFB87A3E), Color(0xFFE2B284)),
    CYAN("cyan", "青蓝", Color(0xFF7FE3FF), Color(0xFF4FC3F7), Color(0xFF3E8FA6), Color(0xFF9FD4E4)),
    PINK("pink", "粉紫", Color(0xFFFF9BE0), Color(0xFFB388FF), Color(0xFFB4629C), Color(0xFFD8A8CC)),
    ICE("ice", "冰白", Color(0xFFEAF6FF), Color(0xFF9FD8FF), Color(0xFF8FA3B0), Color(0xFFC3D4DE));

    companion object {
        fun of(id: String): FireflyTint = entries.firstOrNull { it.id == id } ?: RAINBOW

        /** 深色主题的「彩虹」多色盘：青、蓝、暖黄、粉、紫、冰蓝，全部高亮度（暗底才有荧光感） */
        private val RainbowDark = listOf(
            Color(0xFF6EF7D0), Color(0xFF8AB4FF), Color(0xFFFFD166),
            Color(0xFFFF9BE0), Color(0xFFB388FF), Color(0xFF9FE8FF),
        )

        /** 浅色主题：同色相但要更深更饱和——白底上粉彩色只会糊成灰雾 */
        private val RainbowLight = listOf(
            Color(0xFF1F9E7A), Color(0xFF3E6FCB), Color(0xFFD99412),
            Color(0xFFC0489B), Color(0xFF7A56C8), Color(0xFF2C8CA8),
        )

        /** 当前档位在本次主题下的可用颜色 */
        fun colors(dark: Boolean, self: FireflyTint): List<Color> = when (self) {
            RAINBOW -> if (dark) RainbowDark else RainbowLight
            else -> listOf(if (dark) self.darkCore else self.lightCore)
        }

        fun auraOf(dark: Boolean, self: FireflyTint): Color =
            if (self == RAINBOW) Color.Transparent else if (dark) self.darkAura else self.lightAura
    }
}

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
    /** 配色序号：进页面时定下，保证同一只不会每秒变色 */
    val colorIndex: Int,
    /** 大颗散景（虚焦光斑）：更淡更糊，负责景深；小颗是清晰亮点 */
    val soft: Boolean,
)

@Composable
fun Fireflies(modifier: Modifier = Modifier) {
    val dark = isInDarkTheme()
    // 之前按主题关掉（浅色默认不画），结果用户升级后直接「没效果」——默认能看见比讲究更重要，
    // 真正的开关放设置里（firefly_enabled）。
    if (!AppSettings.fireflyEnabled) return
    // 亮底/暗底两套尺寸：白底上 1.4dp 的小点是彻底看不见的（加光不可见，只能减光），
    // 必须放大到 3~7dp、提高不透明度，才勉强能读出「有东西在飘」。
    val light = !dark
    // 一次算好：不同屏幕比例下按归一化坐标分布，再乘实际尺寸
    val flies = remember(COUNT, light) {
        val rnd = Random(20261108)
        List(COUNT) {
            val soft = rnd.nextFloat() < 0.30f
            Firefly(
                baseX = rnd.nextFloat(),
                baseY = rnd.nextFloat(),
                driftX = 0.02f + rnd.nextFloat() * 0.04f,
                driftY = 0.01f + rnd.nextFloat() * 0.02f,
                // 首版 0.05~0.15 rad/s 慢到 1~2px/s（肉眼「不动」），第二版 0.35~0.9 又偏快。
                // 现在取中段：0.18~0.42（周期 15~35s），每秒约 6~14px，看得出在飘但不抢视线。
                // 再放慢一档：0.07~0.17 rad/s（周期 37~90s），配合光团的柔和形态，像缓慢漂浮
                speedX = 0.07f + rnd.nextFloat() * 0.10f,
                speedY = 0.06f + rnd.nextFloat() * 0.09f,
                phase = rnd.nextFloat() * 6.283f,
                blinkSpeed = 0.05f + rnd.nextFloat() * 0.10f,
                // 两级尺寸（照参考图）：三成是 6~14dp 的虚焦散景，七成是 1.4~3.6dp 的清晰亮点。
                // 全都一样大反而假——真实镜头里远近光斑尺寸差一个数量级。
                // 用户两次反馈「太小、太不起眼」：参考图那种 1~3dp 的小点在手机上基本读不出来，
                // 实际要 4~9dp 才看得见。散景再大一档，负责层次。
                sizePx = if (soft) 12.0f + rnd.nextFloat() * 12.0f
                else 5.0f + rnd.nextFloat() * 5.0f,
                // 放慢：每秒 0.15%~0.4% 屏高（约 4~10px/s），飘得更闲散
                // 0.7%~1.6% 屏高/秒（2800px 屏 ≈ 20~45px/s）——要能一眼看出在往上飘
                rise = 0.007f + rnd.nextFloat() * 0.009f,
                colorIndex = rnd.nextInt(6),
                soft = soft,
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

    var logged = remember { false }
    val tint = FireflyTint.of(AppSettings.fireflyTint)
    val colors = FireflyTint.colors(dark, tint)
    // 亮底上更要收：白底没有「发光」的物理依据，浓度一高就像屏幕脏了
    val strength = if (dark) 1f else 0.85f

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!logged) {
            logged = true
            android.util.Log.i(
                "HonorFireflies",
                "first draw: dark=$dark enabled=${AppSettings.fireflyEnabled} tint=${AppSettings.fireflyTint} " +
                    "count=${flies.size} size=${size.width}x${size.height}",
            )
        }
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
            val breathe = 0.82f + 0.18f * sin(t * 0.10f + f.phase * 0.3f)
            // 回绕时在顶 / 底边缘淡出淡入，否则虫子飞出屏幕又「啪」地出现在另一头
            val edge = minOf(1f, (yNorm / 0.08f).coerceIn(0f, 1f), ((1f - yNorm) / 0.06f).coerceIn(0f, 1f))
            val a = pulse * breathe * strength * edge

            val core = colors[f.colorIndex % colors.size]
            val r = (0.85f + 0.30f * pulse) * f.sizePx.dp.toPx() * 1.45f
            // 散景整体压暗：大而亮会变成一块「膏药」，参考图里大光斑都很淡
            val a2 = if (f.soft) a * 0.55f else a
            val c = Offset(x, y)

            // 荧光光团：**单个径向渐变**，不是多层同心圆。
            // 5 层实心圆在浅色底上会出现明显的「靶心环」（每层边缘都是硬边 + 一个不透明度台阶），
            // 截图里那些一圈圈的圆环就是这么来的。渐变的英文 stop 直接给到边缘 Transparent，才是无缝化开。
            drawCircle(
                brush = Brush.radialGradient(
                    0.00f to core.copy(alpha = (a2 * 0.88f).coerceAtMost(1f)),
                    0.28f to core.copy(alpha = (a2 * 0.52f).coerceAtMost(1f)),
                    0.60f to core.copy(alpha = (a2 * 0.20f).coerceAtMost(1f)),
                    1.00f to core.copy(alpha = 0f),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )
            // 拖尾：往上飘时身后留 3 段渐隐余影，方向和速度都靠它读出来（纯靠位移太慢，看不出）
            val tailLen = f.sizePx.dp.toPx() * (if (f.soft) 0.7f else 0.55f)
            for (i in 1..3) {
                val dy = tailLen * i * 0.9f
                drawCircle(
                    color = core.copy(alpha = (a2 * (0.22f / i)).coerceAtMost(0.5f)),
                    radius = r * (1f - 0.18f * i),
                    center = Offset(x, y + dy),
                )
            }

            // 灯芯：极小的高亮实心点，保留「点状光源」的实感，不然整团只是一片雾
            drawCircle(
                color = core.copy(alpha = (a2 * (if (f.soft) 0.6f else 1.6f)).coerceAtMost(0.95f)),
                radius = r * (if (f.soft) 0.06f else 0.10f),
                center = c,
            )
        }
    }
}
