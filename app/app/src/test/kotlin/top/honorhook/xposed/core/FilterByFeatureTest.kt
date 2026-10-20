package top.honorhook.xposed.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 功能开关过滤（[filterByFeature]）的 JVM 回归。
 *
 * 钉住两条语义：
 * 1) `feature` 为空的挂载点**永远保留**——它不属于任何功能开关（探针/载体类），关开关不该影响它；
 * 2) 过滤发生在**M 统计之前**（引擎侧）⇒ 关掉某功能后该功能的点不计入 M，
 *    判据行 `installed N/M` 的 M 仍 = 实际尝试挂载的点数。
 */
class FilterByFeatureTest {

    private class FakeGate(private val enabled: Set<String>) : FeatureGate {
        override fun isEnabled(key: String): Boolean = key in enabled
    }

    private fun task(id: String, feature: String = "") = MountEngine.SimpleTask(
        id = id,
        className = "com.example.C",
        methodName = "m",
        paramCount = 0,
        returnType = "void",
        description = id,
        feature = feature,
    )

    @Test
    fun `points without feature are always kept`() {
        val pending = listOf(task("probe"), task("carrier"))
        assertEquals(2, filterByFeature(pending, FakeGate(emptySet())).size)
    }

    @Test
    fun `points of enabled feature are kept`() {
        val pending = listOf(task("a", "f1"), task("b", "f2"))
        val kept = filterByFeature(pending, FakeGate(setOf("f1")))
        assertEquals(listOf("a"), kept.map { it.id })
    }

    @Test
    fun `points of disabled feature are dropped`() {
        val pending = listOf(task("a", "f1"), task("b", "f2"))
        val kept = filterByFeature(pending, FakeGate(setOf("f2")))
        assertEquals(listOf("b"), kept.map { it.id })
    }

    @Test
    fun `filter keeps declaration order`() {
        val pending = listOf(task("a"), task("b", "f1"), task("c"), task("d", "f2"))
        val kept = filterByFeature(pending, FakeGate(setOf("f2")))
        assertEquals(listOf("a", "c", "d"), kept.map { it.id })
    }

    @Test
    fun `empty pending stays empty`() {
        assertEquals(0, filterByFeature(emptyList<MountEngine.Task>(), FakeGate(emptySet())).size)
    }

    @Test
    fun `always on gate keeps everything`() {
        val pending = listOf(task("a", "f1"), task("b", "f2"))
        assertEquals(2, filterByFeature(pending, FeatureGate.AlwaysOn).size)
    }
}
