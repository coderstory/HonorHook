package top.honorhook.ui.component

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.sin
import kotlin.random.Random
import top.honorhook.ui.AppSettings

/**
 * 首页背景的萤火虫（2026-11-08 用户需求：「主界面能加动态萤火虫效果？」）。
 *
 * 设计取舍：
 * - **不引入粒子库**：只是「漂浮 + 明灭」，Canvas 画两个径向渐变就够，省一个依赖和几十 KB。
 * - **状态读在 draw 阶段**：时间变量是 [mutableFloatStateOf]，只在 Canvas 的 draw lambda 里读，
 *   快照系统只会让这一层重绘，不会触发重组（否则每帧重组整棵首页树）。
 * - **帧循环用 withFrameNanos**：跟屏幕刷新对齐，系统进入 doze / 页面不可见时自然停摆，
 *   不像 `while(true) { delay(16) }` 那样在后台空转。
 * - **粒子参数一次算好**：种子固定，每次进首页布局一致（不会因为重新组合就换一套位置）。
 * - **两点注意（都是实测踩出来的）**：
 *   1) 光团必须是**单个径向渐变**。多层实心圆每层都是硬边 + 一个不透明度台阶，白底上会变成一圈圈「靶心」。
 *   2) 浅色主题要单独一套颜色与尺寸：白底上加光不可见（白已经是 255），只能靠减光；1~3dp 的小点
 *      在白底上等于没画，必须放大到 5dp 以上才有存在感。
 */
private const val COUNT = 30
private const val COUNT_LIGHT = 22

/** 首帧日志只打一次，方便真机确认「到底有没有在画」（不随帧刷屏） */
private var firstDrawLogged = false

/**
 * 萤火虫配色档位（2026-11-08 用户要求「多一些颜色选择」）。
 *
 * 每档给两组色：深色主题要高亮度才「发光」，浅色主题必须换成同色相的深色版本，
 * 否则白底上的粉彩色会糊成灰雾、看着像屏幕脏了。
 */
enum class FireflyTint(
    val id: String,
    val label: String,
    val darkCore: Color,
    val lightCore: Color,
) {
    RAINBOW("rainbow", "彩虹", Color(0xFF7CF7C4), Color(0xFF3E8F7A)),
    LIME("lime", "萤绿", Color(0xFFC6FF6E), Color(0xFF3F9E62)),
    GOLD("gold", "暖金", Color(0xFFFFD166), Color(0xFFB8862B)),
    AMBER("amber", "蜜橙", Color(0xFFFFA95E), Color(0xFFB4703A)),
    CYAN("cyan", "青蓝", Color(0xFF7FE3FF), Color(0xFF2E8CA6)),
    PINK("pink", "粉紫", Color(0xFFFF9BE0), Color(0xFFA8579A)),
    ICE("ice", "冰白", Color(0xFFEAF6FF), Color(0xFF7E93A3));

    companion object {
        fun of(id: String): FireflyTint = entries.firstOrNull { it.id == id } ?: LIME

        /** 深色主题的「彩虹」多色盘：青、蓝、暖黄、粉、紫、冰蓝，全部高亮度（暗底才有荧光感） */
        private val RainbowDark = listOf(
            Color(0xFF6EF7D0), Color(0xFF8AB4FF), Color(0xFFFFD166),
            Color(0xFFFF9BE0), Color(0xFFB388FF), Color(0xFF9FE8FF),
        )

        /** 浅色主题：同色相但要更深更饱和——白底上粉彩色只会糊成灰雾 */
        private val RainbowLight = listOf(
            Color(0xFF1F9E7A), Color(0xFF3E6FCB), Color(0xFFC08A12),
            Color(0xFFB4488F), Color(0xFF6E4EBC), Color(0xFF2C8CA8),
        )

        /** 当前档位在本次主题下的可用颜色 */
        fun colors(dark: Boolean, self: FireflyTint): List<Color> = when (self) {
            RAINBOW -> if (dark) RainbowDark else RainbowLight
            else -> listOf(if (dark) self.darkCore else self.lightCore)
        }
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

    val light = !dark
    val count = if (light) COUNT_LIGHT else COUNT

    // 一次算好：不同屏幕比例下按归一化坐标分布，再乘实际尺寸
    val flies = remember(count, light) {
        val rnd = Random(20261108)
        List(count) {
            // 白底上的大光斑只会糊成一块脏绿（实测截图就是这样），所以浅色主题不画散景，
            // 只留小而清晰的亮点——看着像「飘着的微粒」，比强行发光干净。
            val soft = dark && rnd.nextFloat() < 0.28f
            Firefly(
                baseX = rnd.nextFloat(),
                baseY = rnd.nextFloat(),
                driftX = 0.02f + rnd.nextFloat() * 0.04f,
                driftY = 0.01f + rnd.nextFloat() * 0.02f,
                // 0.07~0.17 rad/s（周期 37~90s）：慢到像漂浮，但横向摆动仍看得出来
                speedX = 0.07f + rnd.nextFloat() * 0.10f,
                speedY = 0.06f + rnd.nextFloat() * 0.09f,
                phase = rnd.nextFloat() * 6.283f,
                blinkSpeed = 0.05f + rnd.nextFloat() * 0.10f,
                // 两级尺寸：三成虚焦散景，七成清晰亮点。全都一样大反而假。
                sizePx = when {
                    soft -> 12.0f + rnd.nextFloat() * 10.0f
                    dark -> 5.0f + rnd.nextFloat() * 4.5f
                    else -> 3.2f + rnd.nextFloat() * 2.6f
                },
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

    val tint = FireflyTint.of(AppSettings.fireflyTint)
    val colors = FireflyTint.colors(dark, tint)
    // 白底没有「发光」的物理依据，浓度一高就像屏幕脏了；暗底则要亮才像光。
    // 浅色取 0.62：点虽小但饱和度高，太浓会在白色卡片上留下明显绿斑。
    val strength = if (dark) 1f else 0.62f

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!firstDrawLogged) {
            firstDrawLogged = true
            android.util.Log.i(
                "HonorFireflies",
                "first draw: dark=$dark tint=${AppSettings.fireflyTint} count=${flies.size} " +
                    "size=${size.width}x${size.height}",
            )
        }
        val t = time.floatValue
        val w = size.width
        val h = size.height

        flies.forEach { f ->
            // 横向：正弦左右游走；纵向：正弦上下摆 + 持续上升（到顶回绕）。
            // 只有正弦没有净位移的话，虫子会在一个小范围里「原地晃」，看不出在飞。
            val x = (f.baseX + f.driftX * sin(t * f.speedX + f.phase)) * w
            val yNorm = f.baseY - f.rise * t + f.driftY * sin(t * f.speedY + f.phase * 1.7f)
            val y = ((yNorm % 1f) + 1f) % 1f * h

            // 明灭：sin 取三次方让「亮起来」短促、「暗下去」长，才像萤火虫。
            // 0.18 的底噪是必须的——纯三次方大多数时间接近 0，画面上等于没有（首版就栽在这）。
            val wave = (sin(t * f.blinkSpeed * 6.283f + f.phase) + 1f) * 0.5f
            val pulse = 0.18f + 0.82f * (wave * wave * wave)
            val breathe = 0.82f + 0.18f * sin(t * 0.10f + f.phase * 0.3f)
            // 回绕时在顶 / 底边缘淡出淡入，否则虫子飞出屏幕又「啪」地出现在另一头
            val edge = minOf(1f, (yNorm / 0.08f).coerceIn(0f, 1f), ((1f - yNorm) / 0.06f).coerceIn(0f, 1f))
            val a = pulse * breathe * strength * edge

            val base = colors[f.colorIndex % colors.size]
            // 灯芯偏白：真实光源中心是过曝的，纯色芯看着像贴纸
            val core = lerp(base, Color.White, if (dark) 0.22f else 0.10f)
            val r = (0.85f + 0.30f * pulse) * f.sizePx.dp.toPx() * (if (f.soft) 1.35f else 1.5f)
            // 散景整体压暗：大而亮会变成一块「膏药」，参考图里大光斑都很淡
            val a2 = (if (f.soft) a * 0.5f else a).coerceIn(0f, 1f)
            val c = Offset(x, y)

            // 拖尾只给深色主题：浅色底上余影会糊成一道道水渍，反而脏
            if (dark) {
                val tailLen = f.sizePx.dp.toPx() * (if (f.soft) 0.55f else 0.45f)
                for (i in 1..3) {
                    drawCircle(
                        color = base.copy(alpha = (a2 * (0.20f / i)).coerceAtMost(0.45f)),
                        radius = r * (1f - 0.22f * i),
                        center = Offset(x, y + tailLen * i * 0.95f),
                    )
                }
            }

            // 光晕：大半径、低浓度、边缘直接归零（径向渐变没有硬边，所以不会出现「靶心环」）
            drawCircle(
                brush = Brush.radialGradient(
                    0.00f to base.copy(alpha = a2 * 0.42f),
                    0.40f to base.copy(alpha = a2 * 0.17f),
                    0.72f to base.copy(alpha = a2 * 0.05f),
                    1.00f to base.copy(alpha = 0f),
                    center = c,
                    radius = r,
                ),
                radius = r,
                center = c,
            )
            // 灯芯：小而实，负责「有个亮点在这儿」，否则整团只是一片雾
            val cr = r * (if (f.soft) 0.30f else 0.42f)
            drawCircle(
                brush = Brush.radialGradient(
                    0.00f to core.copy(alpha = a2 * 0.95f),
                    0.55f to core.copy(alpha = a2 * 0.45f),
                    1.00f to core.copy(alpha = 0f),
                    center = c,
                    radius = cr,
                ),
                radius = cr,
                center = c,
            )
            if (!f.soft) {
                drawCircle(
                    color = core.copy(alpha = (a2 * 1.1f).coerceAtMost(0.95f)),
                    radius = cr * 0.34f,
                    center = c,
                )
            }
        }
    }
}
