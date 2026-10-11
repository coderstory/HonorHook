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

        assertTrue("tag 应符合 v<version>.r<build> 契约: ${r.tag}", r.tag.startsWith("v"))
        assertTrue("version 非空: ${r.version}", r.version.isNotEmpty())
        assertTrue("buildNum >= 0: ${r.buildNum}", r.buildNum >= 0)
        assertTrue("release 应附带 APK: ${r.apkUrl}", r.apkUrl.endsWith(".apk"))

        // 与本地版本对比不抛异常（任一方向都合法）
        val newer = UpdateChecker.isNewer(r, "0.1.0", 0)
        println("latest=${r.tag} apk=${r.apkUrl} isNewerThanLocal(0.1.0,r0)=$newer")
    }

    @Test
    fun parseTag_currentRealTag() {
        // 2026-10-11 实测的线上 tag，锁定契约不随版漂移
        val r = UpdateChecker.parseTag("v0.1.0.r21", "https://example.com/x.apk")
        assertNotNull(r)
        assertEquals("0.1.0", r!!.version)
        assertEquals(21, r.buildNum)
    }
}
