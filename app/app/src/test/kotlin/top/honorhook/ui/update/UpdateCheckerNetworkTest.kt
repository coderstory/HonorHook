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

        assertTrue("tag 应符合 v<versionName> 契约: ${r.tag}", r.tag.matches(Regex("^v\\d+\\.\\d+\\.\\d+$")))
        assertEquals("version 应为去掉 v 的语义版本: ${r.version}", r.tag.removePrefix("v"), r.version)
        assertTrue("release 应附带 APK: ${r.apkUrl}", r.apkUrl.endsWith(".apk"))

        // 与本地版本号对比不抛异常（任一方向都合法）
        val newer = UpdateChecker.isNewer(r, "1.0.0")
        println("latest=${r.tag} apk=${r.apkUrl} isNewerThan(1.0.0)=$newer")
    }

    @Test
    fun parseTag_currentRealTag() {
        // 锁定契约形状：v + 三段语义版本（2026-10-12 从 UTC 时间戳契约切换）
        val r = UpdateChecker.parseTag("v1.0.1", "https://example.com/x.apk")
        assertNotNull(r)
        assertEquals("1.0.1", r!!.version)
        assertEquals("v1.0.1", r.tag)
    }
}
