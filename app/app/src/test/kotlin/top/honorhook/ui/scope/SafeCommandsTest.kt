package top.honorhook.ui.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 28 号 §6：SafeCommands 工厂把注入面钉死——非法包名/键/值一律拒绝（返回 null）。 */
class SafeCommandsTest {

    @Test
    fun `pm 命令拒绝非法包名`() {
        assertNull(SafeCommands.pmDisable("com.hihonor.system; rm -rf /"))
        assertNull(SafeCommands.pmDisable("com hihonor.system"))
        assertNull(SafeCommands.pmDisable("single-segment"))
        assertNull(SafeCommands.pmEnable(""))
        // 合法包名照常生成
        assertEquals("pm disable-user --user 0 com.hihonor.magazine", SafeCommands.pmDisable("com.hihonor.magazine"))
        assertEquals("pm enable com.hihonor.magazine", SafeCommands.pmEnable("com.hihonor.magazine"))
    }

    @Test
    fun `settings put 拒绝注入型值`() {
        val t = SafeCommands.Table.GLOBAL
        assertNull(SafeCommands.settingsPut(t, "ok.key_1", "a'b"))
        assertNull(SafeCommands.settingsPut(t, "ok.key_1", "a;b"))
        assertNull(SafeCommands.settingsPut(t, "ok.key_1", "line1\nline2"))
        assertNull(SafeCommands.settingsPut(t, "ok.key_1", "\$(id)"))
        assertNull(SafeCommands.settingsPut(t, "bad key", "1"))
        assertNull(SafeCommands.settingsPut(t, "bad;key", "1"))
        // 合法值：数字/布尔/受限字符串/空
        assertEquals("settings put global k 26", SafeCommands.settingsPut(t, "k", "26"))
        assertEquals("settings put global k true", SafeCommands.settingsPut(t, "k", "true"))
        assertEquals("settings put global k dns.adguard-dns.com", SafeCommands.settingsPut(t, "k", "dns.adguard-dns.com"))
        assertEquals("settings put global k ''", SafeCommands.settingsPut(t, "k", ""))
    }

    @Test
    fun `settings get 只验键不验值`() {
        assertNotNull(SafeCommands.settingsGet(SafeCommands.Table.SECURE, "a.b_c9"))
        assertNull(SafeCommands.settingsGet(SafeCommands.Table.SECURE, "a b"))
        assertEquals("settings get secure a.b_c9", SafeCommands.settingsGet(SafeCommands.Table.SECURE, "a.b_c9"))
    }

    @Test
    fun `表 token 覆盖三表`() {
        assertEquals("system", SafeCommands.Table.SYSTEM.token)
        assertEquals("secure", SafeCommands.Table.SECURE.token)
        assertEquals("global", SafeCommands.Table.GLOBAL.token)
    }
}
