package top.honorhook.ui.photos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * AI 图像工具纯逻辑契约（票 #24）：扩图换算、输出命名不覆盖既有结果。
 * 来源：docs/spec-ai-image-refactor.md；实现 AiImageCore.kt。
 */
class AiImageCoreTest {

    @Test
    fun `扩图_目标更宽时扩左右`() {
        // 1000x1000 → 16:9（更宽）：左右各 (16/9-1)/2 ≈ 0.3889，上下 0
        val s = outpaintScales(1000, 1000, 16f / 9f)
        assertEquals(0f, s[2], 1e-6f)
        assertEquals(0f, s[3], 1e-6f)
        assertEquals((16f / 9f - 1f) / 2f, s[0], 1e-4f)
        assertEquals(s[0], s[1], 1e-6f)
    }

    @Test
    fun `扩图_目标更高时扩上下`() {
        // 1600x900 → 1:1（更高）：上下各 (1600/900-1)/2 ≈ 0.3889
        val s = outpaintScales(1600, 900, 1f)
        assertEquals(0f, s[0], 1e-6f)
        assertEquals((1600f / 900f - 1f) / 2f, s[2], 1e-4f)
        assertEquals(s[2], s[3], 1e-6f)
    }

    @Test
    fun `扩图_比例相同为零_极端目标单边封顶`() {
        // JUnit 无 float[] 的 assertArrayEquals 重载 → 用 contentEquals
        assertTrue(outpaintScales(1000, 1000, 1f).contentEquals(floatArrayOf(0f, 0f, 0f, 0f)))
        // 9:16 竖图要 16:9（差 3.16x）：单边 1.08 → cap 1.0
        assertTrue(outpaintScales(900, 1600, 16f / 9f).contentEquals(floatArrayOf(1f, 1f, 0f, 0f)))
    }

    @Test
    fun `壁纸比例在表内且约为9比19点5`() {
        val wall = AI_RATIOS.first { it.first == "壁纸" }
        assertEquals(9f / 19.5f, wall.second, 1e-6f)
        assertEquals(4, AI_RATIOS.size)
    }

    @Test
    fun `输出命名_重名顺延不覆盖`() {
        val dir = Files.createTempDirectory("aiimg").toFile()
        val src = File(dir, "a.jpg").apply { writeBytes(byteArrayOf(1)) }
        assertEquals("a-ai.jpg", aiOutPath(src, dir).name)
        File(dir, "a-ai.jpg").writeBytes(byteArrayOf(2))
        assertEquals("a-ai2.jpg", aiOutPath(src, dir).name)
        File(dir, "a-ai2.jpg").writeBytes(byteArrayOf(3))
        assertEquals("a-ai3.jpg", aiOutPath(src, dir).name)
        assertTrue(dir.deleteRecursively())
    }

    @Test
    fun `输出命名_无扩展名回落jpg`() {
        val dir = Files.createTempDirectory("aiimg2").toFile()
        val src = File(dir, "noext").apply { writeBytes(byteArrayOf(1)) }
        assertEquals("noext-ai.jpg", aiOutPath(src, dir).name)
        dir.deleteRecursively()
    }
}
