package top.honorhook.ui.scope

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 钉住：ps 表头必须被吃掉、kill 必须在前、多包用 `;` 分隔、包名白名单防注入。 */
class ScopeCommandTest {

    private val psOutput = """
        PID NAME
         1118 com.android.systemui

         27994 com.hihonor.android.thememanager
         27995 com.hihonor.android.thememanager
    """.trimIndent()

    @Test
    fun parseProcesses_groupsPidsByNameAndDropsHeader() {
        val parsed = ScopeCommand.parseProcesses(psOutput)
        assertEquals(setOf(1118), parsed["com.android.systemui"])
        assertEquals(setOf(27994, 27995), parsed["com.hihonor.android.thememanager"])
        assertEquals(2, parsed.size)
    }

    @Test
    fun parseRunning_isNameView() {
        assertEquals(
            setOf("com.android.systemui", "com.hihonor.android.thememanager"),
            ScopeCommand.parseRunning(psOutput),
        )
    }

    @Test
    fun parseEmptyOutput() {
        assertTrue(ScopeCommand.parseProcesses("").isEmpty())
        assertTrue(ScopeCommand.parseProcesses("PID NAME").isEmpty())
        assertTrue(ScopeCommand.parseRunning("PID NAME").isEmpty())
    }

    @Test
    fun isRootShell_onlyUidZero() {
        assertTrue(ScopeCommand.isRootShell("uid=0(root) gid=0(root) groups=0(root) context=u:r:ksu:s0"))
        assertFalse(ScopeCommand.isRootShell("uid=10247(u0_a247) gid=10247(u0_a247) groups=1007(log)"))
        assertFalse(ScopeCommand.isRootShell("uid=2000(shell) gid=2000(shell)"))
    }

    @Test
    fun killScript_killFirstWithPidof() {
        val pids = mapOf("com.android.systemui" to setOf(1118))
        assertEquals(
            "kill -9 \$(pidof com.android.systemui) ; am force-stop com.android.systemui",
            ScopeCommand.killScript(listOf("com.android.systemui"), pids),
        )
    }

    @Test
    fun killScript_multiplePackages() {
        val pids = mapOf(
            "com.android.systemui" to setOf(1118),
            "com.hihonor.android.thememanager" to setOf(27994, 27995),
        )
        assertEquals(
            "kill -9 \$(pidof com.android.systemui) ; am force-stop com.android.systemui ; " +
                "kill -9 \$(pidof com.hihonor.android.thememanager) ; " +
                "am force-stop com.hihonor.android.thememanager",
            ScopeCommand.killScript(listOf("com.android.systemui", "com.hihonor.android.thememanager"), pids),
        )
    }

    @Test
    fun killScript_skipsPackagesWithoutPid() {
        assertEquals("", ScopeCommand.killScript(listOf("com.android.settings"), emptyMap()))
        assertEquals("", ScopeCommand.killScript(emptyList(), emptyMap()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun killScript_rejectsShellMetacharacters() {
        ScopeCommand.killScript(listOf("com.android.settings; rm -rf /"), emptyMap())
    }

    @Test
    fun isValidPackage_acceptsRealPackageNames() {
        assertTrue(ScopeCommand.isValidPackage("com.android.settings"))
        assertTrue(ScopeCommand.isValidPackage("com.exam8.chengrenGK"))
        assertFalse(ScopeCommand.isValidPackage(""))
        assertFalse(ScopeCommand.isValidPackage("settings"))
        assertFalse(ScopeCommand.isValidPackage("com.android.settings;ls"))
    }

    @Test
    fun suCandidates_putKernelSuHookPathFirst() {
        // KernelSU 的 sucompat 只认 /system/bin/su：候选链第一位必须是它
        assertEquals("/system/bin/su", ScopeCommand.SU_CANDIDATES.first())
    }
}
