package top.honorhook.ui.photos

import java.io.File

/**
 * AI 图像工具纯逻辑核心（零 Android 依赖，JVM 可测）。
 * 票 #24：从 AiImageScreen 抽出；UI/引擎只消费，不再各自内联。
 */

/** 扩图目标比例表（label, w/h）。 */
val AI_RATIOS = listOf(
    "1:1" to 1.0f,
    "16:9" to 16f / 9f,
    "9:16" to 9f / 16f,
    // 手机壁纸竖屏（2026-11-11 用户要求）：主流全面屏 9:19.5；outpaintScales 单边 cap 1.0 ⇒ 最高扩 3x，足够
    "壁纸" to 9f / 19.5f,
)

/**
 * 计算扩图四边 scale（相对原图对应边的比例，order: left/right/top/bottom）。
 * 目标宽高比 [target]（w/h）；宽不够扩左右、高不够扩上下，居中对称。
 * 单边上限 1.0（比例差过大时截断，引擎侧也会拒绝极端值）。
 */
internal fun outpaintScales(width: Int, height: Int, target: Float): FloatArray {
    val orig = width.toFloat() / height.toFloat()
    val lr: Float
    val tb: Float
    if (target >= orig) {
        // 需要更宽：扩左右（相对原图宽）
        lr = ((target * height / width) - 1f) / 2f
        tb = 0f
    } else {
        // 需要更高：扩上下（相对原图高）
        lr = 0f
        tb = ((width / (target * height)) - 1f) / 2f
    }
    val cap = { v: Float -> v.coerceIn(0f, 1.0f) }
    return floatArrayOf(cap(lr), cap(lr), cap(tb), cap(tb))
}

/**
 * 输出路径：`<名>-ai.<ext>`，重名顺延 `-ai2/-ai3`（不覆盖既有结果）。
 * [outDir] 由调用方决定：目录来源 = 原图同目录；picker 来源 = Pictures/HonorHookAI。
 */
fun aiOutPath(src: File, outDir: File): File {
    var i = 1
    val stem = src.nameWithoutExtension
    val ext = src.extension.ifEmpty { "jpg" }
    while (true) {
        val suffix = if (i == 1) "-ai" else "-ai$i"
        val cand = File(outDir, "$stem$suffix.$ext")
        if (!cand.exists()) return cand
        i++
    }
}
