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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import top.honorhook.ui.AppSettings

/**
 * 首页背景的萤火虫（2026-11-08 用户需求：「主界面能加动态萤火虫效果？」，
 * 随后几轮改成「圆形 / 三角形 / 正方形的光圈」）。
 *
 * 设计取舍：
 * - **不引入粒子库**：只是「漂浮 + 明灭 + 描一圈发光的几何形」，Canvas 足够，省一个依赖。
 * - **发光靠「同一个形状描三遍」**：Compose 的 Canvas 没有真正的模糊（BlurMaskFilter 要 API 31+
 *   且受硬件层限制），所以用同路径三层、宽度递减 + alpha 递增叠出渐隐边缘；单描一圈会很硬。
 * - **状态读在 draw 阶段**：时间变量是 [mutableFloatStateOf]，只在 Canvas 的 draw lambda 里读，
 *   快照系统只会重绘这一层，不触发重组（否则每帧重组整棵首页树）。
 * - **帧循环用 withFrameNanos**：跟屏幕刷新对齐，页面不可见时自然停摆。
 * - **粒子参数一次算好**：种子固定，每次进首页布局一致。
 * - 浅色主题单独一套：白底加光不可见，只能减光；尺寸要够大才有存在感，
 *   而大块实心光斑在白底上只会糊成脏绿 —— 用**描边光圈**而不是实心团，正是为了绕开这一点。
 */
private const val COUNT = 24
private const val COUNT_LIGHT = 18

/** 首帧日志只打一次，方便真机确认「到底有没有在画」（不随帧刷屏） */
private var firstDrawLogged = false

/** 光圈形状（用户要求：圆形 / 三角形 / 正方形） */
enum class FireflyShape { CIRCLE, TRIANGLE, SQUARE }

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
    // 2026-11-08（artwork-theme）：颜色选择器已从主题页删除，萤火虫跟随插画主色固定为靛紫。
    VIOLET("violet", "靛紫", Color(0xFFB6A4FF), Color(0xFF5B4FBF)),
    ICE("ice", "冰白", Color(0xFFEAF6FF), Color(0xFF7E93A3));

    companion object {
        fun of(id: String): FireflyTint = entries.firstOrNull { it.id == id } ?: RAINBOW

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
    val shape: FireflyShape,
    /** 缓慢自转（度/秒）：三角/方形不转像贴上去的图形，转起来才像在游动 */
    val spin: Float,
)

/**
 * 按形状构造一个以 [center] 为中心、外接圆半径 [r] 的路径；多边形按 [rotDeg] 绕中心旋转。
 *
 * 旋转直接算进顶点角度，而不是用 Matrix 变换路径：Compose 的 `Matrix.rotateZ` 只有
 * 「绕原点」版本（带 pivot 的重载不存在，写 `rotateZ(deg, center)` 会报参数过多），
 * 十多只粒子每帧先 translate→rotate→translate 反而更绕。
 */
private fun shapePath(shape: FireflyShape, center: Offset, r: Float, rotDeg: Float): Path {
    val p = Path()
    if (shape == FireflyShape.CIRCLE) {
        p.addOval(Rect(center = center, radius = r))
        return p
    }
    // 多边形顶点：三角 -90/30/150，正方形 45/135/225/315（内接于半径 r 的圆，半边长 = r/√2）
    val baseAngles = if (shape == FireflyShape.TRIANGLE) floatArrayOf(-90f, 30f, 150f)
                     else floatArrayOf(45f, 135f, 225f, 315f)
    baseAngles.forEachIndexed { i, deg ->
        val a = Math.toRadians((deg + rotDeg).toDouble()).toFloat()
        val pt = Offset(center.x + r * cos(a), center.y + r * sin(a))
        if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
    }
    p.close()
    return p
}

@Composable
fun Fireflies(modifier: Modifier = Modifier) {
    val dark = isInDarkTheme()
    // 之前按主题关掉（浅色默认不画），结果用户升级后直接「没效果」——默认能看见比讲究更重要，
    // 真正的开关放设置里（firefly_enabled）。
    if (!AppSettings.fireflyEnabled) return

    val light = !dark
    val count = if (light) COUNT_LIGHT else COUNT

    val flies = remember(count, light) {
        val rnd = Random(20261108)
        List(count) {
            Firefly(
                // 分布留边：用户要求「出现在屏幕边缘的概率低一点」。基准点收在 10%~90%，
                // 加上 ±4% 的横向漂移，实际活动范围是 6%~94%，不会再贴着边框打转。
                baseX = 0.10f + rnd.nextFloat() * 0.80f,
                baseY = 0.06f + rnd.nextFloat() * 0.88f,
                driftX = 0.02f + rnd.nextFloat() * 0.04f,
                driftY = 0.01f + rnd.nextFloat() * 0.02f,
                // 0.07~0.17 rad/s（周期 37~90s）：慢到像漂浮，但横向摆动仍看得出来
                speedX = 0.07f + rnd.nextFloat() * 0.10f,
                speedY = 0.06f + rnd.nextFloat() * 0.09f,
                phase = rnd.nextFloat() * 6.283f,
                blinkSpeed = 0.05f + rnd.nextFloat() * 0.10f,
                // 浅色整体更大：白底上小图形很容易被当成灰尘
                // 用户要求「尺寸放大一些」：6.5~14dp（浅色再多 1dp，白底上更吃尺寸）
                sizePx = if (dark) 6.5f + rnd.nextFloat() * 7.5f else 7.5f + rnd.nextFloat() * 7.0f,
                // 0.7%~1.6% 屏高/秒（2800px 屏 ≈ 20~45px/s）——要能一眼看出在往上飘
                rise = 0.007f + rnd.nextFloat() * 0.009f,
                colorIndex = rnd.nextInt(6),
                // 圆形约一半、三角/方形各四分之一：全是多边形会显得太硬
                shape = when {
                    rnd.nextFloat() < 0.5f -> FireflyShape.CIRCLE
                    rnd.nextFloat() < 0.5f -> FireflyShape.TRIANGLE
                    else -> FireflyShape.SQUARE
                },
                spin = if (rnd.nextBoolean()) 6f + rnd.nextFloat() * 10f else -(6f + rnd.nextFloat() * 10f),
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

    // 配色不再可配（用户 2026-11-08「萤火虫颜色这个开关是不是要改一改？或者删了？」）：
    // 插画主题一套色，7 档配色只会让人挑出跟背景打架的颜色，改成跟主色同族的靛紫。
    val tint = FireflyTint.VIOLET
    val colors = FireflyTint.colors(dark, tint)
    // 白底没有「发光」的物理依据，浓度一高就像屏幕脏了；描边光圈正好吃得住更高的浓度。
    val strength = if (dark) 1f else 0.85f

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!firstDrawLogged) {
            firstDrawLogged = true
            android.util.Log.i(
                "HonorFireflies",
                "first draw: dark=$dark tint=${tint.id} count=${flies.size} " +
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
            // 0.18 的底噪是必须的——纯三次方大多数时间接近 0，画面上等于没有。
            val wave = (sin(t * f.blinkSpeed * 6.283f + f.phase) + 1f) * 0.5f
            val pulse = 0.18f + 0.82f * (wave * wave * wave)
            val breathe = 0.82f + 0.18f * sin(t * 0.10f + f.phase * 0.3f)
            // 回绕时在顶 / 底边缘淡出淡入，否则虫子飞出屏幕又「啪」地出现在另一头
            // 边缘淡出带加宽到 12%/10%：用户要求「出现在屏幕边缘的概率低一点」，
            // 光靠收窄初始分布不够——它们会往上飘，淡出带窄了就会在顶边留半只。
            val edge = minOf(1f, (yNorm / 0.12f).coerceIn(0f, 1f), ((1f - yNorm) / 0.10f).coerceIn(0f, 1f))
            val a = pulse * breathe * strength * edge
            if (a <= 0.01f) return@forEach

            val base = colors[f.colorIndex % colors.size]
            // 描边偏白一点：真实光源的勾边是过曝的，纯色线看着像贴纸
            val stroke = lerp(base, Color.White, if (dark) 0.25f else 0.10f)
            val r = (0.85f + 0.30f * pulse) * f.sizePx.dp.toPx()
            val c = Offset(x, y)
            val path = shapePath(f.shape, c, r, t * f.spin + f.phase * 57f)
            // 线宽跟着 dp 走，小图形也要 >=1.6px，不然抗锯齿会把细线糊成一片灰
            // 线宽同时跟 dp 与图形大小走：大图形用细线会显得「虚」，小图形用粗线会糊成一团
            val crisp = ((1.2f + 0.6f * pulse) * density).coerceAtLeast(r * 0.06f)

            // 发光：同一个形状由粗到细描三遍（alpha 递增、宽度递减），边缘自然渐隐
            val haloWidths = floatArrayOf(r * 0.72f, r * 0.36f, r * 0.16f)
            val haloAlphas = floatArrayOf(0.07f, 0.14f, 0.24f)
            for (i in 0..2) {
                drawPath(
                    path = path,
                    color = base.copy(alpha = (a * haloAlphas[i] * if (dark) 1f else 1.25f).coerceIn(0f, 1f)),
                    style = Stroke(width = haloWidths[i].coerceAtLeast(crisp)),
                )
            }
            // 内部一层极淡的填充：没有它，白底上就只是一条几何线（像剪贴画），
            // 有它才读得出「这块区域在发光」。浓度压到 0.10，太实会变成色块。
            drawPath(
                path = path,
                color = base.copy(alpha = (a * 0.10f * if (dark) 1f else 1.15f).coerceIn(0f, 1f)),
            )
            // 主描边：这一圈才是「光圈」本体
            drawPath(
                path = path,
                color = stroke.copy(alpha = (a * if (dark) 0.85f else 0.75f).coerceIn(0f, 1f)),
                style = Stroke(width = crisp),
            )
            // 用户要求去掉中心的实心圆点：光圈保持空心，形状语义更干净（三角/方形不会被那一点抢走）。
            // 需要「有芯」的观感时，靠上面那层 0.10 的内部填充就够。
        }
    }
}
