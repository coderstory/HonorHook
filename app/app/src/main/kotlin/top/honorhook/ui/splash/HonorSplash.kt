package top.honorhook.ui.splash

import android.app.Activity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import top.honorhook.BuildConfig
import top.yukonga.miuix.kmp.basic.Text

/**
 * 启动 Splash（2026-11-08 用户需求：「好看的 splash + 类似 live2d 的简单动画」）。
 *
 * 不引 Live2D SDK（体积 + 授权 + 合规都不划算），用 Canvas 手绘一只「呼吸小机器人」：
 * - **待机循环**：身体上下浮动（bob）、左右轻摆（sway）、眼睛定时眨（blink）、
 *   天线球与光晕随之呼吸、四个光点绕体缓慢公转；
 * - **入场**：整体 0.6→1.0 弹簧放大 + 淡入，文字延迟 180ms 再上浮淡入；
 * - **退场**：停留 [HOLD_MS]（2.4s，2026-11-08 用户要求拉长）后 420ms 淡出，再回调 [onFinish]，由调用方移除覆盖层。
 *
 * 配色沿用「远程开发」悬浮球的语言：近黑底 + 荧光绿描边，与工具箱视觉统一。
 * 动画全部走 Compose 的 infiniteTransition / Animatable，不建 Timer、不碰主线程阻塞。
 */
private const val HOLD_MS = 2400L
private const val FADE_MS = 420

private val Neon = Color(0xFF39FF88)
private val BgTop = Color(0xFF0D100E)
private val BgBottom = Color(0xFF070808)

@Composable
fun HonorSplash(onFinish: () -> Unit) {
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(HOLD_MS)
        leaving = true
        delay(FADE_MS.toLong())
        onFinish()
    }

    val overlayAlpha by animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(FADE_MS),
        label = "splashFade",
    )

    // splash 永远是深底，浅色主题下系统栏原本是深色图标 → 看不清。
    // 期间强制浅色图标，退出时还原成进入前的值（Theme.kt 只在 darkTheme 变化时才重设，不会自己纠正）。
    val context = LocalContext.current
    DisposableEffect(Unit) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        val prevStatus = controller?.isAppearanceLightStatusBars
        val prevNav = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = prevStatus ?: false
            controller?.isAppearanceLightNavigationBars = prevNav ?: false
        }
    }

    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { enter.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 320f)) }

    val textIn by animateFloatAsState(
        targetValue = if (enter.value > 0.85f) 1f else 0f,
        animationSpec = tween(420, easing = FastOutSlowInEasing),
        label = "splashText",
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha }
            // 吞掉点击：splash 期间不让用户点到下面的首页/底栏
            .pointerInput(Unit) { detectTapGestures { } }
            .background(Brush.verticalGradient(listOf(BgTop, BgBottom))),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Mascot(
                modifier = Modifier
                    .size(190.dp)
                    .graphicsLayer {
                        val s = 0.6f + 0.4f * enter.value
                        scaleX = s
                        scaleY = s
                        alpha = enter.value.coerceIn(0f, 1f)
                    },
            )
            Spacer(Modifier.height(18.dp))
            Text(
                text = "Re:荣耀",
                fontSize = 27.sp,
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFEAF6EF),
                modifier = Modifier.graphicsLayer {
                    alpha = textIn
                    translationY = (1f - textIn) * 14f
                },
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "荣耀功能增强模块 · v${BuildConfig.VERSION_NAME}",
                fontSize = 12.sp,
                color = Color(0xFF9FB4A7).copy(alpha = 0.85f * textIn),
                modifier = Modifier.graphicsLayer { translationY = (1f - textIn) * 10f },
            )
            Spacer(Modifier.height(28.dp))
            LoadingDots(alpha = textIn)
        }
    }
}

/** 待机动画参数：一个 infiniteTransition 里跑四条曲线，相位错开才有「活物」感 */
@Composable
private fun Mascot(modifier: Modifier) {
    val loop = rememberInfiniteTransition(label = "mascot")

    // 上下浮动 0..1（正弦往复，2.2s 一个来回）
    val bob by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bob",
    )

    // 左右轻摆 -1..1（周期比浮动长，避免两种动作同步显得机械）
    val sway by loop.animateFloat(
        initialValue = -1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "sway",
    )

    // 光晕呼吸
    val glow by loop.animateFloat(
        initialValue = 0.20f,
        targetValue = 0.50f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow",
    )

    // 眨眼：3.2s 一轮，2.45s 处闭眼 170ms 再睁开；scaleY=1 表示睁眼
    val blink by loop.animateFloat(
        initialValue = 1f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 3200
                1f at 0
                1f at 2450
                0.08f at 2560
                0.08f at 2620
                1f at 2700
                1f at 3200
            },
        ),
        label = "blink",
    )

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val cx = w / 2f
        // 身体中心随浮动上下移：只让身体动，阴影留在原地，才有「浮起来」的感觉
        val cy = h * 0.55f - h * 0.03f * bob
        val bodyW = w * 0.54f
        val bodyH = h * 0.46f
        val top = cy - bodyH / 2f
        val left = cx - bodyW / 2f
        val corner = CornerRadius(bodyW * 0.34f)

        // 1) 地面阴影（不随浮动走，越浮动越淡越小）
        drawOval(
            color = Color.Black.copy(alpha = 0.30f - 0.10f * bob),
            topLeft = Offset(cx - bodyW * 0.42f, h * 0.845f),
            size = Size(bodyW * 0.84f, h * 0.05f),
        )

        // 2) 身后光晕
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(Neon.copy(alpha = glow * 0.55f), Color.Transparent),
                center = Offset(cx, cy),
                radius = w * 0.46f,
            ),
            radius = w * 0.46f,
            center = Offset(cx, cy),
        )

        // 3) 公转光点（4 个，相位均分；随浮动微调角度，看着像被身体带动）
        val orbitR = w * 0.42f
        repeat(4) { i ->
            val deg = i * 90f + bob * 26f
            val rad = Math.toRadians(deg.toDouble())
            val px = cx + (orbitR * Math.cos(rad)).toFloat()
            val py = cy + (orbitR * Math.sin(rad)).toFloat() * 0.55f
            drawCircle(
                color = Neon.copy(alpha = 0.18f + 0.22f * ((i + 1) % 3) / 2f),
                radius = (2.2f + i * 0.7f).dp.toPx(),
                center = Offset(px, py),
            )
        }

        // 4) 天线：随摆动摇摆，球球上下弹
        val antennaBase = Offset(cx, top + bodyH * 0.06f)
        val tipX = cx + sway * 8.dp.toPx()
        val tipY = top - h * 0.10f - 4.dp.toPx() * bob
        drawLine(
            color = Neon.copy(alpha = 0.85f),
            start = antennaBase,
            end = Offset(tipX, tipY),
            strokeWidth = 2.5.dp.toPx(),
            cap = StrokeCap.Round,
        )
        drawCircle(color = Neon, radius = 5.5.dp.toPx(), center = Offset(tipX, tipY))
        drawCircle(
            color = Neon.copy(alpha = 0.25f),
            radius = 12.dp.toPx() * (0.8f + 0.35f * bob),
            center = Offset(tipX, tipY),
        )

        // 5) 身体：近黑圆角块 + 荧光绿描边；整体绕底部微倾
        rotate(degrees = sway * 2.2f, pivot = Offset(cx, cy + bodyH * 0.55f)) {
            drawRoundRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF24282A), Color(0xFF0F1112)),
                    startY = top,
                    endY = top + bodyH,
                ),
                topLeft = Offset(left, top),
                size = Size(bodyW, bodyH),
                cornerRadius = corner,
            )
            drawRoundRect(
                color = Neon.copy(alpha = 0.85f),
                topLeft = Offset(left, top),
                size = Size(bodyW, bodyH),
                cornerRadius = corner,
                style = Stroke(width = 2.dp.toPx()),
            )

            // 6) 眼睛：竖向缩放做眨眼；闭眼时留一条细线（不是消失）
            val eyeW = bodyW * 0.115f
            val eyeH = bodyH * 0.15f * blink.coerceAtLeast(0.06f)
            val eyeY = cy - bodyH * 0.02f
            listOf(-0.20f, 0.20f).forEach { dx ->
                drawRoundRect(
                    color = Neon,
                    topLeft = Offset(cx + bodyW * dx - eyeW / 2f, eyeY - eyeH / 2f),
                    size = Size(eyeW, eyeH),
                    cornerRadius = CornerRadius(eyeW / 2f),
                )
            }

            // 7) 腮红
            listOf(-0.30f, 0.30f).forEach { dx ->
                drawCircle(
                    color = Neon.copy(alpha = 0.13f),
                    radius = bodyW * 0.075f,
                    center = Offset(cx + bodyW * dx, eyeY + bodyH * 0.22f),
                )
            }

            // 8) 嘴：一段微笑弧（比横条更「活」），随浮动轻微上下，像在轻轻呼吸
            val mouthW = bodyW * 0.26f
            val mouthY = eyeY + bodyH * 0.20f + 1.dp.toPx() * bob
            drawArc(
                color = Neon.copy(alpha = 0.8f),
                startAngle = 15f,
                sweepAngle = 150f,
                useCenter = false,
                topLeft = Offset(cx - mouthW / 2f, mouthY),
                size = Size(mouthW, bodyH * 0.10f),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** 底部三点脉冲（相位错开 160ms），替代无限旋转的菊花，和上面柔和的待机动画更搭 */
@Composable
private fun LoadingDots(alpha: Float) {
    val loop = rememberInfiniteTransition(label = "dots")
    val phase by loop.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(1200)),
        label = "dotPhase",
    )
    Row(
        modifier = Modifier.graphicsLayer { this.alpha = alpha },
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        repeat(3) { i ->
            // 三角波：0→1→0，越靠近峰值越亮越大
            val p = (phase + i * 0.16f) % 1f
            val k = 1f - kotlin.math.abs(p * 2f - 1f)
            Box(
                modifier = Modifier
                    .size(5.dp + 1.5.dp * k)
                    .background(Neon.copy(alpha = 0.25f + 0.6f * k), CircleShape),
            )
        }
    }
}
