package top.honorhook.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 表驱动用例：远端 tag vs 本地构建号 → 预期是否有更新。 */
private data class Case(val remote: String, val localBuild: Long, val expected: Boolean)

/** tag 契约与构建号对比的表驱动回归（与 CI tag_name = v<UTC YYYYMMDDHHMMSS> 对齐）。 */
class UpdateCheckerTest {

    // ---- parseTag ----

    @Test
    fun parseTag_fullForm() {
        val r = UpdateChecker.parseTag("v20261012193045", "https://x/app.apk")
        assertEquals("20261012193045", r?.version)
        assertEquals(20261012193045L, r?.buildNum)
        assertEquals("v20261012193045", r?.tag)
        assertEquals("https://x/app.apk", r?.apkUrl)
    }

    @Test
    fun parseTag_rejectsBadTags() {
        assertNull(UpdateChecker.parseTag("v1.0.0.r42", ""))       // 旧契约（semver.r<run>）
        assertNull(UpdateChecker.parseTag("v1.2.3", ""))           // 旧契约无 .r
        assertNull(UpdateChecker.parseTag("v2026101219304", ""))   // 13 位
        assertNull(UpdateChecker.parseTag("v202610121930456", "")) // 15 位
        assertNull(UpdateChecker.parseTag("20261012193045", ""))   // 缺 v 前缀
        assertNull(UpdateChecker.parseTag("0.1.0.r1", ""))
        assertNull(UpdateChecker.parseTag("release-v1", ""))
        assertNull(UpdateChecker.parseTag("", ""))
    }

    // ---- isNewer ----

    private fun newer(remote: String, localBuild: Long) =
        UpdateChecker.isNewer(UpdateChecker.parseTag(remote, "")!!, localBuild)

    @Test
    fun timestampComparison_table() {
        // (远端 tag, 本地构建号) → 是否有更新
        val cases = listOf(
            Case("v20261012193045", 20261001000000L, true),  // 远端时间戳更新
            Case("v20261012193045", 20261012193045L, false), // 完全相同
            Case("v20261012193040", 20261012193045L, false), // 远端更旧
        )
        cases.forEach { c ->
            assertEquals("${c.remote} vs 本地 ${c.localBuild}",
                c.expected, newer(c.remote, c.localBuild))
        }
    }

    @Test
    fun isNewer_directLongCompare() {
        // 直接构造 Release，防 parseTag 本身的问题掩盖比较逻辑
        val r = Release("20261012193045", 20261012193045L, "", "v20261012193045")
        assertTrue(UpdateChecker.isNewer(r, 20261001000000L))
        assertFalse(UpdateChecker.isNewer(r, 20261012193045L))
        assertFalse(UpdateChecker.isNewer(r, 20262012193045L))
    }
}
