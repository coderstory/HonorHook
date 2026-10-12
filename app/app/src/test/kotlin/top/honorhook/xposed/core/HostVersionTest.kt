package top.honorhook.xposed.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HostVersion 的 JVM 回归测试：版本比较器与选表三级（精确命中 / 默认套 / 显式 fallback）。
 *
 * fallback 第三级是显式 opt-in——默认 false 的现状语义（installer matched=false → 基线源码路径）
 * 由 `fallback off` 用例钉死，误开默认会在这里先红。
 */
class HostVersionTest {

    // ---------------- compareVersionNames ----------------

    @Test
    fun `equal versions compare as zero`() {
        assertEquals(0, HostVersion.compareVersionNames("14.0.37.301", "14.0.37.301"))
    }

    @Test
    fun `segment-wise numeric ordering`() {
        assertTrue(HostVersion.compareVersionNames("14.0.37.301", "14.0.22.326") > 0)
        assertTrue(HostVersion.compareVersionNames("14.0.22.326", "14.0.37.301") < 0)
        assertTrue(HostVersion.compareVersionNames("16.1.0", "16.0.9") > 0)
    }

    @Test
    fun `shorter version pads missing segments with zero`() {
        // 缺段按 0：16.1 == 16.1.0（与 FeatureCatalog 原内联语义一致）
        assertEquals(0, HostVersion.compareVersionNames("16.1", "16.1.0"))
        assertTrue(HostVersion.compareVersionNames("16.2", "16.1.9") > 0)
    }

    @Test
    fun `non-numeric suffix is truncated to zero`() {
        // "0-release" 截断为 0 → 与 5.9.0.0 相等（exam8 基线 "5.9.0.0-release" 场景）
        assertEquals(0, HostVersion.compareVersionNames("5.9.0.0-release", "5.9.0.0"))
        assertTrue(HostVersion.compareVersionNames("5.9.0.1-release", "5.9.0.0") > 0)
    }

    // ---------------- pick 三级 ----------------

    private fun table(vararg versions: String, build: List<String>) =
        HostVersion.Table(versions = versions.toSet()) { build }

    @Test
    fun `precise hit wins`() {
        val tables = listOf(
            table("1.0.0", build = listOf("old")),
            table("2.0.0", build = listOf("new")),
        )
        val pick = HostVersion.pick(tables, "2.0.0")
        assertTrue(pick.matched)
        assertEquals(listOf("new"), pick.tasks)
        assertNull(pick.fallbackTo)
    }

    @Test
    fun `default suite catches any version`() {
        val tables = listOf(table("1.0.0", build = listOf("old")), table(build = listOf("dflt")))
        val pick = HostVersion.pick(tables, "9.9.9.9")
        assertTrue(pick.matched)
        assertEquals(listOf("dflt"), pick.tasks)
        assertNull(pick.fallbackTo)
    }

    @Test
    fun `no hit at all yields matched false`() {
        val tables = listOf(table("1.0.0", build = listOf("old")))
        val pick = HostVersion.pick(tables, "2.0.0")
        assertFalse(pick.matched)
        assertTrue(pick.tasks.isEmpty())
        assertNull(pick.fallbackTo)
    }

    @Test
    fun `fallback on picks the highest-version precise table`() {
        val tables = listOf(
            table("14.0.22.326", build = listOf("old")),
            table("14.0.37.301", build = listOf("new")),
        )
        val pick = HostVersion.pick(tables, "15.0.0.100", fallback = true)
        assertTrue(pick.matched)
        assertEquals(listOf("new"), pick.tasks)
        assertEquals("14.0.37.301", pick.fallbackTo)
    }

    @Test
    fun `fallback off keeps legacy matched-false semantics`() {
        // installer 现有契约：默认不开 fallback，未命中 → matched=false → 调用方走基线源码路径
        val tables = listOf(table("14.0.22.326", build = listOf("old")))
        val pick = HostVersion.pick(tables, "14.0.37.301")
        assertFalse(pick.matched)
        assertTrue(pick.tasks.isEmpty())
        assertNull(pick.fallbackTo)
    }

    @Test
    fun `precise hit and default suite both preempt fallback`() {
        val precise = listOf(table("1.0.0", build = listOf("old")))
        val hit = HostVersion.pick(precise, "1.0.0", fallback = true)
        assertTrue(hit.matched)
        assertNull(hit.fallbackTo)

        val withDefault = listOf(table("1.0.0", build = listOf("old")), table(build = listOf("dflt")))
        val default = HostVersion.pick(withDefault, "2.0.0", fallback = true)
        assertTrue(default.matched)
        assertEquals(listOf("dflt"), default.tasks)
        assertNull(default.fallbackTo)
    }
}
