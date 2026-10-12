package top.honorhook.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 表驱动用例：远端 tag vs 本地 versionName → 预期是否有更新。 */
private data class Case(val remote: String, val local: String, val expected: Boolean)

/** tag 契约（v<versionName> 语义版）与版本比较的表驱动回归。 */
class UpdateCheckerTest {

    // ---- parseTag ----

    @Test
    fun parseTag_fullForm() {
        val r = UpdateChecker.parseTag("v1.2.3", "https://x/app.apk")
        assertEquals("1.2.3", r?.version)
        assertEquals("v1.2.3", r?.tag)
        assertEquals("https://x/app.apk", r?.apkUrl)
    }

    @Test
    fun parseTag_rejectsBadTags() {
        assertNull(UpdateChecker.parseTag("v20261012193045", ""))  // 旧契约（UTC 时间戳）
        assertNull(UpdateChecker.parseTag("v1.0.0.r42", ""))       // 更早契约（semver.r<run>）
        assertNull(UpdateChecker.parseTag("v1.2", ""))             // 缺尾段
        assertNull(UpdateChecker.parseTag("v1.2.3.4", ""))         // 多一段
        assertNull(UpdateChecker.parseTag("1.2.3", ""))            // 缺 v 前缀
        assertNull(UpdateChecker.parseTag("release-v1", ""))
        assertNull(UpdateChecker.parseTag("", ""))
    }

    // ---- isNewer ----

    private fun newer(remote: String, local: String) =
        UpdateChecker.isNewer(UpdateChecker.parseTag(remote, "")!!, local)

    @Test
    fun semverComparison_table() {
        // (远端 tag, 本地 versionName) → 是否有更新
        val cases = listOf(
            Case("v1.0.2", "1.0.1", true),   // 远端 patch 领先
            Case("v1.1.0", "1.0.9", true),   // 中段进位
            Case("v2.0.0", "1.9.9", true),   // 主段进位
            Case("v1.0.1", "1.0.1", false),  // 完全相同
            Case("v1.0.0", "1.0.1", false),  // 远端更旧
        )
        cases.forEach { c ->
            assertEquals("${c.remote} vs 本地 ${c.local}",
                c.expected, newer(c.remote, c.local))
        }
    }

    @Test
    fun isNewer_directRelease() {
        // 直接构造 Release，防 parseTag 本身的问题掩盖比较逻辑
        val r = Release("1.2.3", "", "v1.2.3")
        assertTrue(UpdateChecker.isNewer(r, "1.2.2"))
        assertFalse(UpdateChecker.isNewer(r, "1.2.3"))
        assertFalse(UpdateChecker.isNewer(r, "1.2.4"))
    }
}
