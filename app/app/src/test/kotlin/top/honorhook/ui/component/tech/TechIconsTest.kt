package top.honorhook.ui.component.tech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 科技蓝主题的原创"电路符文"图标回归。
 *
 * 为什么必须有：
 * - 图形是**按名字哈希生成**的，生成规则一改（格点、步长、节点）就可能两个图标撞成同一个形状；
 * - 生成失败时会静默退回占位括号，界面上只会"看起来没图标"，只能在真机肉眼发现
 *   （2026-11-27 就发出去过一版全是占位圆的包）。
 *
 * 这里把 52 个真构造一遍，钉住：数量、没有退回占位、两两形状不同。
 */
class TechIconsTest {

    @Test
    fun `every tech icon builds a real glyph`() {
        val built = TechIcons.buildAllForTest()

        assertEquals("图标数量应与 AppIcons 的映射表一致", 52, built.size)

        val broken = built.filterValues { it.name.startsWith("tech-fallback-") }.keys
        assertTrue("这些图标生成失败、退回了占位括号: $broken", broken.isEmpty())

        val empty = built.filterValues { it.root.size == 0 }.keys
        assertTrue("这些图标没有路径: $empty", empty.isEmpty())
    }

    @Test
    fun `glyphs are pairwise distinct`() {
        val seen = HashMap<String, String>()
        TechIcons.buildAllForTest().keys.forEach { name ->
            val sig = TechIcons.shapeSignature(name)
            val clash = seen.put(sig, name)
            assertTrue("「$name」与「$clash」生成的符文完全相同", clash == null)
        }
        assertEquals("签名表应覆盖全部图标", 52, seen.size)
    }

    @Test
    fun `camelCase lookups from AppIcons resolve`() {
        // AppIcons 传 camelCase（acUnit），生成器按名字哈希，两边必须都能命中
        listOf("acUnit", "qrCodeScanner", "add", "settings", "autoAwesome", "localFlorist").forEach { name ->
            val icon = TechIcons.of(name)
            assertTrue("$name 退回了占位括号", !icon.name.startsWith("tech-fallback-"))
        }
    }
}
