package top.honorhook.xposed.target.xhs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * XhsPoints.cls() 的三态语义回归：①命中覆盖套且值为 null → null（该版本无此点）、
 * ②命中覆盖套且有值 → 覆盖值、③版本无覆盖项/版本为 null → 基线值。
 *
 * 缺陷①（版本源恒 null）由 [XhsPoints.cls] 的调用方修（HostVersion.resolveHostVersion 取代已删除的
 * 旧取法——后者走 ActivityThread.currentPackageManager，现代 Android 无此成员 ⇒ 恒 null
 * ⇒ 覆盖套从未生效）；本类钉的是纯函数语义，版本源是否读得到由调用方保证。
 *
 * 缺陷②（显式 null 语义被吞）：旧实现 `over ?: baseline[key]` 把「覆盖表里显式 null = 该版本无此点」
 * 与「该版本无覆盖项」混成同一种 null ⇒ 9.49.1 的三个 null 点错误回落基线坐标。本类用
 * `explicit null in override suite stays null` 钉死。
 */
class XhsPointsTest {

    // ---------------- ③ 版本无覆盖项 / 版本为 null → 基线值 ----------------

    @Test
    fun `unknown version falls back to baseline`() {
        assertEquals("ji9.b", XhsPoints.cls("9.99.9", "saveClick"))
    }

    @Test
    fun `null version falls back to baseline`() {
        assertEquals("ji9.b", XhsPoints.cls(null, "saveClick"))
    }

    // ---------------- ② 命中覆盖套 → 覆盖值 ----------------

    @Test
    fun `override suite wins over baseline`() {
        assertEquals("mh9.b", XhsPoints.cls("9.49.1", "saveClick"))
        assertEquals("d49.q", XhsPoints.cls("9.49.1", "videoClick"))
        assertEquals("qc8.j", XhsPoints.cls("9.49.1", "x2b"))
    }

    // ---------------- ① 覆盖套里显式 null → null（缺陷②核心） ----------------

    @Test
    fun `explicit null in override suite stays null`() {
        assertNull(XhsPoints.cls("9.49.1", "noteSaveEntry"))
        assertNull(XhsPoints.cls("9.49.1", "aigcEditLaunch"))
        assertNull(XhsPoints.cls("9.49.1", "downloaderEntry"))
    }

    // ---------------- 未知 key → null ----------------

    @Test
    fun `unknown key resolves to null`() {
        assertNull(XhsPoints.cls("9.49.1", "noSuchKey"))
        assertNull(XhsPoints.cls("9.50.0", "noSuchKey"))
        assertNull(XhsPoints.cls(null, "noSuchKey"))
    }

    // ---------------- 9.50.x（无覆盖套）逐键 = 基线，与修复前逐字一致 ----------------

    @Test
    fun `9_50 host resolves every key to baseline`() {
        // 修复前后逐键比对：9.50.x 无覆盖套 ⇒ 旧实现（over=null ⇒ 回落基线）与新实现同结果
        assertEquals("ji9.b", XhsPoints.cls("9.50.0", "saveClick"))
        assertEquals("a59.q", XhsPoints.cls("9.50.0", "videoClick"))
        assertEquals("nd8.j", XhsPoints.cls("9.50.0", "x2b"))
        assertEquals("ab9.r1", XhsPoints.cls("9.50.0", "noteSaveEntry"))
        assertEquals("mg4.b", XhsPoints.cls("9.50.0", "aigcEditLaunch"))
        assertEquals("b75.h", XhsPoints.cls("9.50.0", "downloaderEntry"))
    }
}
