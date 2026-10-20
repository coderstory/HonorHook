package top.honorhook.xposed.target.xhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 挂载点集合按宿主版本选取（X5 批量保存 / X2 去水印）。
 *
 * 这段映射原先埋在 install() 里（读版本 → 选坐标 → 建 pending），无任何回归保护：
 * 坐标表改了、覆盖套改了、某点被挪走，编译期与单测都发现不了，只有真机 installed N/M 能看出来。
 * 这里把「哪个宿主版本挂哪些类」钉死——版本坐标表的语义（[XhsPoints] 三态）由 XhsPointsTest 覆盖，
 * 本类覆盖「坐标 → 挂载点集合」的落地。
 */
class XhsPendingTest {

    @Test
    fun `batch pending uses the 9_49_1 override coordinates`() {
        val pending = XhsBatchSave.buildPending("9.49.1")

        val saveClick = pending.first { it.id == "xhs:batchSave.saveClick.invoke" }
        assertEquals("mh9.b", saveClick.className)
        val videoClick = pending.first { it.id == "xhs:batchSave.videoSaveClick.invoke" }
        assertEquals("d49.q", videoClick.className)
    }

    @Test
    fun `batch pending skips points the host version does not have`() {
        // 9.49.1 未定位这三点（覆盖套显式 null）⇒ 该点不进 pending，不是回落基线坐标
        val pending = XhsBatchSave.buildPending("9.49.1")

        assertFalse(pending.any { it.id == "xhs:videoSave.noteSaveEntry" })
        assertFalse(pending.any { it.id == "xhs:videoSave.aigcEditLaunch" })
        assertFalse(pending.any { it.id == "xhs:videoSave.downloader.entry" })
    }

    @Test
    fun `batch pending falls back to baseline coordinates for an unadapted version`() {
        val pending = XhsBatchSave.buildPending("9.50.0")

        assertEquals("ji9.b", pending.first { it.id == "xhs:batchSave.saveClick.invoke" }.className)
        assertEquals("ab9.r1", pending.first { it.id == "xhs:videoSave.noteSaveEntry" }.className)
    }

    @Test
    fun `batch pending falls back to baseline when the host version is unknown`() {
        // 版本读不到（public 取法都没值）= 盲挂基线坐标，不是「不挂」
        val pending = XhsBatchSave.buildPending(null)

        assertEquals("ji9.b", pending.first { it.id == "xhs:batchSave.saveClick.invoke" }.className)
        assertEquals("ab9.r1", pending.first { it.id == "xhs:videoSave.noteSaveEntry" }.className)
    }

    @Test
    fun `batch pending always contains the album sink points`() {
        // 落盘闸是未混淆点（xec.w），跨版本同名 ⇒ 不随版本坐标变化
        val pending = XhsBatchSave.buildPending(null)

        assertTrue(pending.any { it.id == "xhs:videoSave.albumSink.h0" })
        assertTrue(pending.any { it.id == "xhs:videoSave.albumSink.e0" })
    }

    @Test
    fun `watermark pending uses the version coordinates for the x2b point`() {
        assertEquals(
            "qc8.j",
            XhsWatermark.buildPending("9.49.1").first { it.id == "xhs:noWatermark.qc8j.b" }.className,
        )
        assertEquals(
            "nd8.j",
            XhsWatermark.buildPending(null).first { it.id == "xhs:noWatermark.qc8j.b" }.className,
        )
    }

    @Test
    fun `watermark pending always contains the static and dynamic points`() {
        val pending = XhsWatermark.buildPending(null)

        assertTrue(pending.any { it.id == "xhs:noWatermark.mediaSaveConfig.c" })
        assertTrue(pending.any { it.id == "xhs:noWatermark.imageBean.disableAi" })
    }
}
