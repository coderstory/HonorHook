package top.honorhook.xposed.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * HostVersion 的 JVM 回归测试：选表三级（精确命中 / 默认套 / 显式 fallback）。
 *
 * fallback 第三级是显式 opt-in——默认 false 的现状语义（installer matched=false → 基线源码路径）
 * 由 `fallback off` 用例钉死，误开默认会在这里先红。
 *
 * 版本号比较的用例已随实现迁到共享层 `top.honorhook.VersionNameTest`（语义逐字未变）。
 */
class HostVersionTest {

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
    fun `unknown actual version still selects the default suite`() {
        // 版本读不到（resolveHostVersion 返回 null）时的契约：走默认套，不算“没匹配”。
        // theme / launcher 的版本表目前只有默认套，这条断言钉住「读不到版本 ⇒ 仍挂基线点」。
        val tables = listOf(table("1.0.0", build = listOf("old")), table(build = listOf("dflt")))
        val pick = HostVersion.pick(tables, null)
        assertTrue(pick.matched)
        assertEquals(listOf("dflt"), pick.tasks)
        assertNull(pick.matchedVersion)
    }

    @Test
    fun `unknown actual version does not select a precise suite`() {
        // 只有精确套时，读不到版本 ⇒ 不挂（matched=false），而不是盲挂某个精确套。
        val tables = listOf(table("1.0.0", build = listOf("old")))
        val pick = HostVersion.pick(tables, null)
        assertFalse(pick.matched)
        assertTrue(pick.tasks.isEmpty())
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
