package top.honorhook.ui.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 真实网络集成测试（访问 GitHub releases/latest）。
 * 默认跳过：设环境变量 `HH_NETWORK_TEST=1` 才执行（CI 门禁不带，本地手动验证用）。
 */
class UpdateCheckerNetworkTest {

    @Test
    fun fetchLatest_realApi_contractHolds() {
        assumeTrue(System.getenv("HH_NETWORK_TEST") == "1")

        val r = UpdateChecker.fetchLatest()

        assertTrue("tag 应符合 v<UTC YYYYMMDDHHMMSS> 契约: ${r.tag}", r.tag.matches(Regex("^v\\d{14}$")))
        assertEquals("version 应为去掉 v 的 14 位时间戳: ${r.version}", r.tag.removePrefix("v"), r.version)
        assertTrue("buildNum 应为正: ${r.buildNum}", r.buildNum > 0)
        assertTrue("release 应附带 APK: ${r.apkUrl}", r.apkUrl.endsWith(".apk"))

        // 与本地构建号对比不抛异常（任一方向都合法）
        val newer = UpdateChecker.isNewer(r, 0L)
        println("latest=${r.tag} apk=${r.apkUrl} isNewerThanLocal(0)=$newer")
    }

    @Test
    fun parseTag_currentRealTag() {
        // 锁定契约形状：v + 恰好 14 位 UTC 时间戳（2026-10-12 切换）
        val r = UpdateChecker.parseTag("v20261012193045", "https://example.com/x.apk")
        assertNotNull(r)
        assertEquals("20261012193045", r!!.version)
        assertEquals(20261012193045L, r.buildNum)
    }
}
