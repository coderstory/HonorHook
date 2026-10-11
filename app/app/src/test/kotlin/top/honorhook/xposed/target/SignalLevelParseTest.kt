package top.honorhook.xposed.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SystemUI 信号格档位解析的 JVM 回归测试。
 *
 * 背景：`MergedSignalHook` 把宿主信号格资源 id 解析成 0~5 的档位，再自绘成合并图标。
 * 解析一旦写错，真机表现是「格子数不对」这类难以定位的问题——测试比真机回归便宜得多。
 *
 * 覆盖的是 SystemUI `res/drawable` 里的真实资源名（2026-10-11 真机 APK 实证）。
 */
class SignalLevelParseTest {

    // ---------------- five 系列（当前状态栏在用）----------------

    @Test
    fun `five series maps 0 to 5`() {
        assertEquals(0, MergedSignalHook.parseLevel("stat_sys_signal_five_0"))
        assertEquals(1, MergedSignalHook.parseLevel("stat_sys_signal_five_1"))
        assertEquals(2, MergedSignalHook.parseLevel("stat_sys_signal_five_2"))
        assertEquals(3, MergedSignalHook.parseLevel("stat_sys_signal_five_3"))
        assertEquals(4, MergedSignalHook.parseLevel("stat_sys_signal_five_4"))
        assertEquals(5, MergedSignalHook.parseLevel("stat_sys_signal_five_5"))
    }

    /** `_small`（状态栏小尺寸变体）是真机实测命中的那一支，必须与基础名同档位。 */
    @Test
    fun `small variant keeps same level`() {
        assertEquals(0, MergedSignalHook.parseLevel("stat_sys_signal_five_0_small"))
        assertEquals(5, MergedSignalHook.parseLevel("stat_sys_signal_five_5_small"))
    }

    @Test
    fun `fully variant keeps same level`() {
        assertEquals(3, MergedSignalHook.parseLevel("stat_sys_signal_five_3_fully"))
        assertEquals(5, MergedSignalHook.parseLevel("stat_sys_signal_five_5_fully"))
    }

    /** 旧式 stat_sys_signal_N（无 five 段）也仍在资源表里，不能漏。 */
    @Test
    fun `legacy series maps 0 to 4`() {
        assertEquals(0, MergedSignalHook.parseLevel("stat_sys_signal_0"))
        assertEquals(4, MergedSignalHook.parseLevel("stat_sys_signal_4"))
        assertEquals(2, MergedSignalHook.parseLevel("stat_sys_signal_2_fully"))
    }

    /** 档位是 6 档（0~5）而非 5 档——这是需求里「一个信号要 5 个」之外的真实值。 */
    @Test
    fun `level range is 0 to 5 inclusive`() {
        val all = (0..5).map { MergedSignalHook.parseLevel("stat_sys_signal_five_$it") }
        assertEquals("档位集合应恰好是 0..5", listOf(0, 1, 2, 3, 4, 5), all)
    }

    // ---------------- 非信号格：必须返回 -1（不归本功能管）----------------

    @Test
    fun `non-signal icons are rejected`() {
        val others = listOf(
            "stat_sys_signal_null",              // 无服务
            "stat_sys_signal_flightmode",        // 飞行模式
            "stat_sys_signal_inout",             // 数据活动双向箭头
            "stat_sys_signal_in",                // 数据活动入
            "stat_sys_signal_out",               // 数据活动出
            "stat_sys_signal_roam_type_2g",      // 漫游类型标
            "stat_sys_signal_type_2g",           // 2G 类型标
            "stat_sys_signal_type_waiting",      // 等待中
            "stat_sys_signal_carrier_network_change",
            "stat_sys_signal_null_res_0x7f08306c",
        )
        others.forEach { name ->
            assertEquals("$name 不是蜂窝信号格，应被排除", -1, MergedSignalHook.parseLevel(name))
        }
    }

    /**
     * `stat_sys_signal_null` 与 `stat_sys_signal_null_res_0x…` 都是 null 类图标。
     * 前缀正则若写成 `stat_sys_signal_(\d)` 之外的宽松形式，会把 null 误认成档位——
     * 这里锁死它们必须返回 -1。
     */
    @Test
    fun `null icons never parse as a level`() {
        assertEquals(-1, MergedSignalHook.parseLevel("stat_sys_signal_null"))
        assertEquals(-1, MergedSignalHook.parseLevel("stat_sys_signal_null_res_0x7f08306c"))
    }

    @Test
    fun `null and empty input is rejected`() {
        assertEquals(-1, MergedSignalHook.parseLevel(null))
        assertEquals(-1, MergedSignalHook.parseLevel(""))
    }

    /** 别的 drawable 前缀不能误命中（wifi 信号格也叫 stat_sys_wifi_signal_*）。 */
    @Test
    fun `unrelated drawables are rejected`() {
        val others = listOf(
            "stat_sys_wifi_signal_4_fully",
            "stat_sys_wifi_inout",
            "stat_sys_signal_fully",            // 没有档位数字
            "ic_statusbar_signal",
        )
        others.forEach { name ->
            assertEquals("$name 不应被当作蜂窝信号格", -1, MergedSignalHook.parseLevel(name))
        }
    }

    /**
     * 契约锁死：解析出的档位必须落在 0..5，否则 drawRow 的 `i < level.coerceAtMost(BARS)` 
     * 与 alpha 计算会走到未定义分支。任一真实资源名解析出越界值都算回归。
     */
    @Test
    fun `every real drawable name maps into valid range or -1`() {
        val realNames = listOf(
            "stat_sys_signal_0", "stat_sys_signal_0_fully",
            "stat_sys_signal_1", "stat_sys_signal_1_fully",
            "stat_sys_signal_2", "stat_sys_signal_2_fully",
            "stat_sys_signal_3", "stat_sys_signal_3_fully",
            "stat_sys_signal_4", "stat_sys_signal_4_fully",
            "stat_sys_signal_five_0", "stat_sys_signal_five_0_small", "stat_sys_signal_five_0_fully",
            "stat_sys_signal_five_1", "stat_sys_signal_five_1_small", "stat_sys_signal_five_1_fully",
            "stat_sys_signal_five_2", "stat_sys_signal_five_2_small", "stat_sys_signal_five_2_fully",
            "stat_sys_signal_five_3", "stat_sys_signal_five_3_small", "stat_sys_signal_five_3_fully",
            "stat_sys_signal_five_4", "stat_sys_signal_five_4_small", "stat_sys_signal_five_4_fully",
            "stat_sys_signal_five_5", "stat_sys_signal_five_5_small", "stat_sys_signal_five_5_fully",
        )
        realNames.forEach { name ->
            val level = MergedSignalHook.parseLevel(name)
            assertTrue("$name 解析出越界档位 $level", level == -1 || level in 0..5)
            assertTrue("真实信号格 $name 不应被排除", level >= 0)
        }
    }
}