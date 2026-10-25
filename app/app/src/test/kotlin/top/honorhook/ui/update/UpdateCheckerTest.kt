package top.honorhook.ui.update

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 表驱动用例：远端 tag vs 本地 versionName → 预期是否有更新。 */
private data class Case(val remote: String, val local: String, val expected: Boolean)

/** 64 位 hex 基底（"ab"×32），digest 用例共用。 */
private val HEX64 = "ab".repeat(32)

/** tag 契约（v<versionName> 语义版）与版本比较的表驱动回归。 */
class UpdateCheckerTest {

    // ---- parseTag ----

    @Test
    fun parseTag_fullForm() {
        val r = UpdateChecker.parseTag("v1.2.3", "https://x/app.apk")
        assertEquals("1.2.3", r?.version)
        assertEquals("v1.2.3", r?.tag)
        assertEquals("https://x/app.apk", r?.apkUrl)
    }

    @Test
    fun parseTag_rejectsBadTags() {
        assertNull(UpdateChecker.parseTag("v20261012193045", ""))  // 旧契约（UTC 时间戳）
        assertNull(UpdateChecker.parseTag("v1.0.0.r42", ""))       // 更早契约（semver.r<run>）
        assertNull(UpdateChecker.parseTag("v1.2", ""))             // 缺尾段
        assertNull(UpdateChecker.parseTag("v1.2.3.4", ""))         // 多一段
        assertNull(UpdateChecker.parseTag("1.2.3", ""))            // 缺 v 前缀
        assertNull(UpdateChecker.parseTag("release-v1", ""))
        assertNull(UpdateChecker.parseTag("", ""))
    }

    // ---- isNewer ----

    private fun newer(remote: String, local: String) =
        UpdateChecker.isNewer(UpdateChecker.parseTag(remote, "")!!, local)

    @Test
    fun semverComparison_table() {
        // (远端 tag, 本地 versionName) → 是否有更新
        val cases = listOf(
            Case("v1.0.2", "1.0.1", true),   // 远端 patch 领先
            Case("v1.1.0", "1.0.9", true),   // 中段进位
            Case("v2.0.0", "1.9.9", true),   // 主段进位
            Case("v1.0.1", "1.0.1", false),  // 完全相同
            Case("v1.0.0", "1.0.1", false),  // 远端更旧
        )
        cases.forEach { c ->
            assertEquals("${c.remote} vs 本地 ${c.local}",
                c.expected, newer(c.remote, c.local))
        }
    }

    @Test
    fun isNewer_directRelease() {
        // 直接构造 Release，防 parseTag 本身的问题掩盖比较逻辑
        val r = Release("1.2.3", "", "v1.2.3")
        assertTrue(UpdateChecker.isNewer(r, "1.2.2"))
        assertFalse(UpdateChecker.isNewer(r, "1.2.3"))
        assertFalse(UpdateChecker.isNewer(r, "1.2.4"))
    }

    // ---- parseDigest：GitHub asset.digest "sha256:<hex>" → hex 字段 ----

    @Test
    fun parseDigest_fullForm() {
        assertEquals(HEX64, UpdateChecker.parseDigest("sha256:$HEX64"))
    }

    @Test
    fun parseDigest_normalizesUpperHex() {
        assertEquals(HEX64, UpdateChecker.parseDigest("sha256:${HEX64.uppercase()}"))
    }

    @Test
    fun parseDigest_trimsWhitespace() {
        assertEquals(HEX64, UpdateChecker.parseDigest(" sha256:$HEX64 "))
    }

    @Test
    fun parseDigest_missingReturnsNull() {
        // fail-closed 语义（2026-10-26 拍板）：digest 缺失 → null，下载层拒绝下载安装
        assertNull(UpdateChecker.parseDigest(null))
        assertNull(UpdateChecker.parseDigest(""))
    }

    @Test
    fun parseDigest_rejectsBadFormats() {
        listOf(
            HEX64,                        // 裸 hex：缺 sha256: 前缀不认
            "sha512:$HEX64",              // 算法不是 sha256
            "sha256:${"ab".repeat(31)}",  // 63 位
            "sha256:${HEX64}ab",          // 66 位
            "sha256:${HEX64}g",           // 含非 hex 字符
            "sha256:",                    // 空 hex
        ).forEach { d -> assertNull("应拒绝: $d", UpdateChecker.parseDigest(d)) }
    }

    // ---- parseRelease：releases/latest JSON → Release ----

    /** 构造 releases/latest 响应：非 APK 资产在前（钉住「digest 只认 APK 资产的」），APK 资产 digest 可控。 */
    private fun releaseJson(digest: String?, tag: String = "v1.2.3"): JSONObject {
        val apkAsset = JSONObject()
            .put("name", "app-release.apk")
            .put("browser_download_url", "https://github.com/x/app-release.apk")
        if (digest != null) apkAsset.put("digest", digest)
        return JSONObject()
            .put("tag_name", tag)
            .put(
                "assets",
                JSONArray()
                    // 干扰项：非 APK 资产带合法 digest——绝不能被当成 APK 的哈希
                    .put(JSONObject().put("name", "sources.jar").put("digest", "sha256:${HEX64}"))
                    .put(apkAsset),
            )
    }

    @Test
    fun parseRelease_withDigest() {
        val r = UpdateChecker.parseRelease(releaseJson("sha256:$HEX64"))
        assertEquals("v1.2.3", r.tag)
        assertTrue(r.apkUrl.endsWith(".apk"))
        assertEquals(HEX64, r.sha256)
    }

    @Test
    fun parseRelease_digestMissing_failClosed() {
        // digest 字段缺失 → sha256=null（下载层拒绝并明确报错，见 ApkInstallerTest）
        assertNull(UpdateChecker.parseRelease(releaseJson(digest = null)).sha256)
    }

    @Test
    fun parseRelease_digestMalformed_failClosed() {
        assertNull(UpdateChecker.parseRelease(releaseJson("sha256:deadbeef")).sha256)
    }

    @Test
    fun parseRelease_badTag_throws() {
        // 沿用 fetchLatest 旧语义：tag 不符契约直接抛
        try {
            UpdateChecker.parseRelease(releaseJson("sha256:$HEX64", tag = "v20261025"))
            throw AssertionError("tag 不符契约应抛 IllegalStateException")
        } catch (_: IllegalStateException) {
            // 预期
        }
    }
}
