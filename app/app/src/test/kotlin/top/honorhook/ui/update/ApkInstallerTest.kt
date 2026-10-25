package top.honorhook.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.MessageDigest

/**
 * 下载完整性（fail-closed + SHA-256 强校验）与直连优先源选择的 JVM 回归。
 * 只测纯函数/前置拒绝路径——真实下载与探测握手走设备验证（docs/plans/2026-10-25-audit-remediation.md §T3）。
 */
class ApkInstallerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val hex64 = "ab".repeat(32)

    /** 测试自带尺子：独立算已知内容的 SHA-256，绝不拿被测代码算期望值。 */
    private fun sha256(content: String): String =
        MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
            .joinToString("") { "%02x".format(it) }

    /** junit4 无 kotlin.test.assertFailsWith，本地极简版（无 kotlin-test 依赖，别引入）。 */
    private fun <T : Throwable> assertThrows(type: Class<T>, block: () -> Unit): T {
        try {
            block()
        } catch (e: Throwable) {
            if (type.isInstance(e)) {
                @Suppress("UNCHECKED_CAST")
                return e as T
            }
            throw AssertionError("期望 ${type.simpleName}，实际 ${e.javaClass.simpleName}: $e")
        }
        throw AssertionError("期望抛 ${type.simpleName}，但正常返回了")
    }

    // ---- fail-closed 前置：无可信哈希不下载（2026-10-26 拍板） ----

    @Test
    fun download_refusesEntryWithoutHash() {
        val dest = File(tmp.root, "update/app-release.apk")
        listOf(null, "", "zz", "ab".repeat(31), "${hex64}ab", "sha256:$hex64").forEach { bad ->
            val e = assertThrows(IntegrityException::class.java) {
                ApkInstaller.download("https://example.com/x.apk", dest, bad) { }
            }
            assertEquals("缺少 SHA-256 校验值，已取消下载", e.message)
        }
        assertFalse("缺哈希必须在发起任何网络请求前拒绝（文件不落盘）", dest.exists())
    }

    @Test
    fun requireValidSha256_acceptsAndNormalizes64Hex() {
        assertEquals(hex64, ApkInstaller.requireValidSha256(hex64))
        assertEquals(hex64, ApkInstaller.requireValidSha256(" ${hex64.uppercase()} "))
    }

    // ---- 下载完成强校验：不匹配删文件并抛错，绝不进 install() ----

    @Test
    fun verifySha256_mismatch_deletesAndThrows() {
        val f = tmp.newFile().apply { writeText("good bytes") }
        val e = assertThrows(IntegrityException::class.java) {
            ApkInstaller.verifySha256(f, sha256("tampered bytes"))
        }
        assertEquals("校验失败，已删除下载文件", e.message)
        assertFalse("校验失败的文件必须已删除", f.exists())
    }

    @Test
    fun verifySha256_match_keepsFile() {
        val f = tmp.newFile().apply { writeText("good bytes") }
        ApkInstaller.verifySha256(f, sha256("good bytes"))
        assertTrue(f.exists())
    }

    // ---- 直连优先源选择（probeSources 反转：不赛马，按序降级） ----

    @Test
    fun selectSource_directOk_neverProbesMirrors() {
        val probed = mutableListOf<String>()
        val winner = ApkInstaller.selectSource(listOf("", "https://m1/", "https://m2/")) { c ->
            probed.add(c)
            if (c.isEmpty()) "direct-url" else null
        }
        assertEquals("" to "direct-url", winner)
        assertEquals("直连握手成功 ⇒ 镜像零探测", listOf(""), probed)
    }

    @Test
    fun selectSource_directFail_fallsToFirstMirrorOnly() {
        val probed = mutableListOf<String>()
        val winner = ApkInstaller.selectSource(listOf("", "https://m1/", "https://m2/")) { c ->
            probed.add(c)
            if (c == "https://m1/") "m1-url" else null
        }
        assertEquals("https://m1/" to "m1-url", winner)
        assertEquals("直连失败后逐个降级，首个成功即停", listOf("", "https://m1/"), probed)
    }

    @Test
    fun selectSource_allFail_returnsNull() {
        val probed = mutableListOf<String>()
        assertNull(
            ApkInstaller.selectSource(listOf("", "https://m1/")) { c ->
                probed.add(c)
                null
            },
        )
        assertEquals(listOf("", "https://m1/"), probed)
    }

    @Test
    fun sourcePrefixes_directAlwaysFirst() {
        // 生产候选序 = 优先级：直连（空前缀）必须恒在首位（镜像只作直连失败的降级）
        assertEquals("", ApkInstaller.SOURCE_PREFIXES.first())
    }
}
