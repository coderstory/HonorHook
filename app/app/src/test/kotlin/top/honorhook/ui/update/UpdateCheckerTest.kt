package top.honorhook.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 表驱动用例：远端 tag vs 本地版本/构建号 → 预期是否有更新。 */
private data class Case(val remote: String, val localVersion: String, val localBuild: Int, val expected: Boolean)

/** tag 契约与版本对比的表驱动回归（与 CI tag_name = v<versionName>.r<run_number> 对齐）。 */
class UpdateCheckerTest {

    // ---- parseTag ----

    @Test
    fun parseTag_fullForm() {
        val r = UpdateChecker.parseTag("v1.2.3.r42", "https://x/app.apk")
        assertEquals("1.2.3", r?.version)
        assertEquals(42, r?.buildNum)
        assertEquals("v1.2.3.r42", r?.tag)
        assertEquals("https://x/app.apk", r?.apkUrl)
    }

    @Test
    fun parseTag_withoutBuildNum() {
        val r = UpdateChecker.parseTag("v1.2.3", "")
        assertEquals("1.2.3", r?.version)
        assertEquals(0, r?.buildNum)
    }

    @Test
    fun parseTag_rejectsBadTags() {
        assertNull(UpdateChecker.parseTag("0.1.0.r1", ""))
        assertNull(UpdateChecker.parseTag("release-v1", ""))
        assertNull(UpdateChecker.parseTag("v1.2.r3", ""))
        assertNull(UpdateChecker.parseTag("v1.2.3.4", ""))
        assertNull(UpdateChecker.parseTag("v1.2.3.rx", ""))
        assertNull(UpdateChecker.parseTag("", ""))
    }

    // ---- isNewer ----

    private fun newer(remote: String, local: String, localBuild: Int) =
        UpdateChecker.isNewer(UpdateChecker.parseTag(remote, "")!!, local, localBuild)

    @Test
    fun semverComparison_table() {
        // (远端 tag, 本地版本, 本地构建号) → 是否有更新
        val cases = listOf(
            Case("v0.2.0.r1", "0.1.0", 0, true),    // minor 更新
            Case("v0.1.1.r0", "0.1.0", 0, true),    // patch 更新
            Case("v1.0.0.r0", "0.1.0", 0, true),    // major 更新
            Case("v0.1.0.r5", "0.1.0", 0, true),    // 同版本，远端构建号新
            Case("v0.1.0.r0", "0.1.0", 0, false),   // 完全相同
            Case("v0.1.0.r3", "0.1.0", 3, false),   // 同构建号
            Case("v0.1.0.r3", "0.1.0", 5, false),   // 远端构建号更旧
            Case("v0.0.9.r99", "0.1.0", 0, false),  // semver 更旧，构建号不救
            Case("v0.1.0.r10", "0.2.0", 0, false),  // 本地 semver 更新
        )
        cases.forEach { c ->
            assertEquals("${c.remote} vs ${c.localVersion}(r${c.localBuild})",
                c.expected, newer(c.remote, c.localVersion, c.localBuild))
        }
    }

    @Test
    fun isNewer_missingSegmentsTreatedAsZero() {
        // 防御：version 段数不足按 0 补齐
        val remote = UpdateChecker.parseTag("v1.r1", "") // 不会出现，但 parse 宽容
        assertNull(remote)
        val r = Release("1.0", 5, "", "x") // 直接构造两段版本
        assertFalse(UpdateChecker.isNewer(r, "1.0.1", 0))
        assertTrue(UpdateChecker.isNewer(r, "0.9.9", 0))
    }
}
