package top.honorhook.xposed.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挂载结果聚合（[MountSummary]）的 JVM 回归。
 *
 * 钉住真机唯一验收口径 `installed N/M` 的计算规则（[docs/hook-inventory-audit.md] §五）：
 * 1) M 只在**首轮**记一次（重试轮传入的是未解析子列表，逐轮覆盖会让判据行变成"累计成功/本轮待挂"）；
 * 2) N = OK + MERGED（合并到另一挂载点的同一方法也算已挂载，见 [MountEngine.Result.installed]）；
 * 3) 通路行按 group 首次出现序输出，全灭 group 升级 ERROR 附失败明细。
 */
class MountSummaryTest {

    private fun outcome(
        id: String,
        group: String = "",
        result: MountEngine.Result = MountEngine.Result.OK,
        description: String = id,
    ) = MountOutcome(id = id, description = description, group = group, result = result)

    // ---------------- M 口径 ----------------

    @Test
    fun `total is set once by the first round`() {
        val summary = MountSummary()
        summary.begin(3, mapOf("A1" to 2))
        assertEquals(3, summary.total())
        summary.begin(1, emptyMap())   // 重试轮再来一次 begin
        assertEquals(3, summary.total())
    }

    @Test
    fun `group totals are not overwritten by later rounds`() {
        val summary = MountSummary()
        summary.begin(3, mapOf("A1" to 2, "A2" to 1))
        summary.begin(1, mapOf("A2" to 1))
        assertEquals(listOf("A1: 0/2", "A2: 0/1"), summary.groupLines())
    }

    // ---------------- N 口径 ----------------

    @Test
    fun `ok accumulates across rounds`() {
        val summary = MountSummary()
        summary.begin(2, mapOf("A1" to 2))
        summary.record(outcome("x", "A1"))
        assertEquals(1, summary.ok())
        summary.record(outcome("y", "A1", MountEngine.Result.NOT_RESOLVED))
        assertEquals(1, summary.ok())
        summary.record(outcome("z", "A1"))
        assertEquals(2, summary.ok())
    }

    @Test
    fun `merged outcome counts as installed`() {
        val summary = MountSummary()
        summary.begin(1, mapOf("A1" to 1))
        summary.record(outcome("x", "A1", MountEngine.Result.MERGED))
        assertEquals(1, summary.ok())
        assertTrue(summary.failedDescriptions().isEmpty())
    }

    // ---------------- 合并冲突（功能不同 ⇒ 本点意图未生效，不计 installed） ----------------

    @Test
    fun `merged conflict is not counted as installed`() {
        val summary = MountSummary()
        summary.begin(2, mapOf("A1" to 2))
        summary.record(outcome("x", "A1", MountEngine.Result.MERGED_CONFLICT, description = "desc-x"))
        summary.record(outcome("y", "A1", MountEngine.Result.MERGED, description = "desc-y"))
        assertEquals(1, summary.ok())
        assertEquals(listOf("desc-x"), summary.failedDescriptions())
    }

    @Test
    fun `merged conflict result is not installed`() {
        assertTrue(!MountEngine.Result.MERGED_CONFLICT.installed)
        assertTrue(MountEngine.Result.MERGED.installed)
    }

    @Test
    fun `same feature merge does not conflict`() {
        assertTrue(!mergedFeatureConflict("f1", "f1"))
        assertTrue(!mergedFeatureConflict("", ""))   // 双方都是常挂点
    }

    @Test
    fun `different feature merge conflicts`() {
        assertTrue(mergedFeatureConflict("f1", "f2"))
        assertTrue(mergedFeatureConflict("f1", ""))   // 占用方是常挂点 ⇒ 本功能意图未生效
    }

    // ---------------- 失败清单 ----------------

    @Test
    fun `failed descriptions are collected in order`() {
        val summary = MountSummary()
        summary.begin(2, mapOf("A1" to 2))
        summary.record(outcome("x", "A1", MountEngine.Result.FAILED, description = "desc-x"))
        summary.record(outcome("y", "A1", MountEngine.Result.FAILED, description = "desc-y"))
        assertEquals(listOf("desc-x", "desc-y"), summary.failedDescriptions())
    }

    @Test
    fun `unresolved points dropped after retries join the failure list`() {
        val summary = MountSummary()
        summary.begin(2, mapOf("A1" to 2))
        summary.record(outcome("x", "A1", MountEngine.Result.NOT_RESOLVED, description = "desc-x"))
        summary.markUnmounted("desc-x", "A1")
        assertEquals(listOf("desc-x"), summary.failedDescriptions())
        assertEquals(0, summary.ok())
    }

    // ---------------- 通路行 ----------------

    @Test
    fun `group lines show survival ratio in declaration order`() {
        val summary = MountSummary()
        summary.begin(3, mapOf("A1" to 2, "A2" to 1))
        summary.record(outcome("x", "A1"))
        summary.record(outcome("y", "A1"))
        assertEquals(listOf("A1: 2/2", "A2: 0/1"), summary.groupLines())
    }

    @Test
    fun `dead groups are those with zero ok`() {
        val summary = MountSummary()
        summary.begin(3, mapOf("A1" to 2, "A2" to 1))
        summary.record(outcome("x", "A1"))
        assertEquals(listOf("A2"), summary.deadGroups())
    }

    // ---------------- 日志行 ----------------

    @Test
    fun `summary line keeps the installed N over M wording`() {
        val summary = MountSummary()
        summary.begin(3, mapOf("A1" to 3))
        summary.record(outcome("x", "A1"))
        summary.record(outcome("y", "A1"))
        val lines = summary.logLines("ThemeStoreHook")
        assertEquals("ThemeStoreHook: installed 2/3", lines.first().text)
        assertEquals(MountLogLevel.INFO, lines.first().level)
    }

    @Test
    fun `summary line carries host version and failure list`() {
        val summary = MountSummary()
        summary.begin(2, mapOf("A1" to 2))
        summary.record(outcome("x", "A1", MountEngine.Result.FAILED, description = "desc-x"))
        val line = summary.logLines("InstallerHook", "（宿主 14.0.38.301）").first()
        assertEquals("InstallerHook: installed 0/2（宿主 14.0.38.301） — 失败: desc-x", line.text)
        assertEquals(MountLogLevel.WARN, line.level)
    }

    @Test
    fun `dead group produces an error line`() {
        val summary = MountSummary()
        summary.begin(3, mapOf("A1" to 2, "A2" to 1))
        summary.record(outcome("x", "A1"))
        summary.record(outcome("y", "A2", MountEngine.Result.FAILED, description = "desc-y"))
        val lines = summary.logLines("ThemeStoreHook")
        assertTrue(lines.any { it.level == MountLogLevel.ERROR && it.text.contains("通路 A2 全灭 0/1") })
        assertTrue(lines.any { it.text == "ThemeStoreHook 通路: A1: 1/2; A2: 0/1" })
    }

    // ---------------- 宿主包名（同一 hook 名按包注册多次时，判据行要能分清归属） ----------------

    @Test
    fun `summary line carries the host package name`() {
        val summary = MountSummary()
        summary.begin(1, mapOf("A1" to 1))
        summary.record(outcome("x", "A1"))
        val line = summary.logLines("HonorAdsHook", hostPackage = "com.hihonor.hihonorads").first()
        assertTrue(line.text.contains("com.hihonor.hihonorads"))
        assertTrue(line.text.contains("installed 1/1"))
    }

    @Test
    fun `group line carries the host package name`() {
        val summary = MountSummary()
        summary.begin(1, mapOf("A1" to 1))
        summary.record(outcome("x", "A1"))
        val lines = summary.logLines("HonorAdsHook", hostPackage = "com.hihonor.hihonorads")
        assertTrue(lines.any { it.text.startsWith("HonorAdsHook[com.hihonor.hihonorads] 通路:") })
    }
}
