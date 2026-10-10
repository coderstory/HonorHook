package top.honorhook.xposed.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 表驱动决策的 JVM 回归测试。
 *
 * `AdSlotFilter` / `BottomTabFilter` / `CloudSwitchTable` / `InstallerConfigTable` 是各自模块
 * "数据与 hook 逻辑分离的唯一改动点"——改表就是改线上行为，但这层改动此前没有任何检查兜着
 * （改错 key、删错条目、安全默认反转都只能靠真机发现）。这里把表的契约钉死：
 * 表内条目必须命中、表外输入必须走安全默认（放行 / 保留 / 返 null）。
 */
class DecisionTablesTest {

    // ---------------- AdSlotFilter ----------------

    @Test
    fun `pruned slot types all match`() {
        AdSlotFilter.PRUNED_SLOT_TYPES.forEach { rule ->
            assertTrue("槽位类型 ${rule.value} 应命中", AdSlotFilter.isPrunedSlotType(rule.value))
        }
    }

    @Test
    fun `unknown slot type is kept`() {
        assertFalse(AdSlotFilter.isPrunedSlotType(null))
        assertFalse(AdSlotFilter.isPrunedSlotType(""))
        assertFalse(AdSlotFilter.isPrunedSlotType("999999"))
    }

    @Test
    fun `pruned target ids all match and unknown ids are kept`() {
        AdSlotFilter.PRUNED_TARGET_IDS.forEach { rule ->
            assertTrue("targetId ${rule.value} 应命中", AdSlotFilter.isPrunedTargetId(rule.value))
        }
        assertFalse(AdSlotFilter.isPrunedTargetId("0"))
        assertFalse(AdSlotFilter.isPrunedTargetId(null))
    }

    // ---------------- BottomTabFilter ----------------

    @Test
    fun `only vip and welfare tabs are pruned`() {
        assertEquals("VIP", BottomTabFilter.ruleFor(3)?.name)
        assertEquals("WELFARE", BottomTabFilter.ruleFor(12)?.name)
        // 1=RECOMMEND 2=CATEGORY 4=RANKING 5=MINE 11=RANKING_CATEGORY 都应保留
        listOf(1, 2, 4, 5, 11).forEach { id ->
            assertNull("tab $id 不应被裁剪", BottomTabFilter.ruleFor(id))
        }
    }

    @Test
    fun `pruning floor protects against host fallback`() {
        // 宿主 createTabs/doSupportOnline 在 size < 3 时回退静态硬编码分支（VIP tab 会复活），
        // 故裁后项数必须不小于该下限，否则调用方整体放弃裁剪。下限被调小会静默破坏这个保护。
        assertTrue(
            "MIN_LIST_SIZE 必须 >= 3，否则裁到触发宿主回退分支",
            BottomTabFilter.MIN_LIST_SIZE >= 3,
        )
    }

    // ---------------- CloudSwitchTable ----------------

    @Test
    fun `forced values round-trip through the map`() {
        CloudSwitchTable.ENTRIES.forEach { entry ->
            assertEquals("key ${entry.key} 的强制值", entry.value, CloudSwitchTable.forced(entry.key))
        }
    }

    @Test
    fun `unknown cloud key passes through`() {
        assertNull(CloudSwitchTable.forced(null))
        assertNull(CloudSwitchTable.forced("__not_in_table__"))
    }

    @Test
    fun `cloud table has no duplicate keys`() {
        val keys = CloudSwitchTable.ENTRIES.map { it.key }
        assertEquals("云控表存在重复 key：MAP 构造会静默丢弃前一条", keys.size, keys.toSet().size)
    }

    // ---------------- InstallerConfigTable ----------------

    @Test
    fun `safe mode json turns both switches off`() {
        assertTrue(InstallerConfigTable.SAFE_MODE_ALL_OFF_JSON.contains("\"easyModeDefaultOpen\":0"))
        assertTrue(InstallerConfigTable.SAFE_MODE_ALL_OFF_JSON.contains("\"superProtectModeDefaultOpen\":0"))
    }

    @Test
    fun `installer gates stay negative`() {
        // 这几个常量是"门恒不成立"的语义载体，被改成 true 就等于把广告/安全模式/拦截全放回来。
        assertFalse(InstallerConfigTable.THIRD_PARTY_JUMP_NOT_INTERCEPTED)
        assertFalse(InstallerConfigTable.SAFE_MODE_CARD_NOT_SHOWN)
        assertFalse(InstallerConfigTable.AD_REQUEST_NOT_SUPPORTED)
        assertFalse(InstallerConfigTable.SAFE_MODE_GUIDE_DIALOG_NOT_SHOWN)
    }
}
