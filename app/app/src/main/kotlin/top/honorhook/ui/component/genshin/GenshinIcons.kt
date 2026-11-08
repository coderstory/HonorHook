package top.honorhook.ui.component.genshin

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 原神风格的图标集（2026-11-08 用户：「紫色主题下，所有的图标能同步替换吗？改成游戏原神相关的」）。
 *
 * 为什么是**手绘几何符号**而不是贴官方素材：
 * - 官方的元素徽记 / 神之眼 / 原石都是米哈游的美术资产，直接把提取出来的位图打进仓库是侵权；
 *   这里按「元素 → 抽象几何」重画（风=三道弧、岩=六边、雷=折线、草=叶形、水=水滴、
 *   火=焰形、冰=雪晶），能读出元素感，又不复制任何原生线条。
 * - 全部是 24×24 的 `ImageVector`，与 Material 图标同型：调用点只换 `Icons.Rounded.X` →
 *   `GenshinIcons.X`，`IconBadge` / `Icon` 的尺寸与 tint 逻辑一行不用改。
 *
 * 约定：所有图形都画在 24×24 视口、四周留 2 单位空隙，保证与 Material 图标并排时视觉重量一致；
 * 颜色一律 `Color.Black` 由 `Icon(tint = ...)` 染色（`ImageVector` 不携带主题色）。
 */
object GenshinIcons {

    private fun icon(name: String, block: ImageVector.Builder.() -> Unit): ImageVector =
        ImageVector.Builder(
            name = name,
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).apply(block).build()

    private val stroke = SolidColor(Color.Black)
    private val fill = SolidColor(Color.Black)

    /** 风：三道叠起的弧线（元素符号被抽象成「气流」）。 */
    val Anemo: ImageVector = icon("Anemo") {
        path(stroke = stroke, strokeLineWidth = 1.9f, strokeLineCap = StrokeCap.Round) {
            moveTo(4f, 7.5f)
            quadTo(12f, 3.5f, 20f, 7.5f)
            moveTo(5.5f, 12f)
            quadTo(12f, 8.8f, 18.5f, 12f)
            moveTo(7f, 16.5f)
            quadTo(12f, 14f, 17f, 16.5f)
        }
    }

    /** 岩：六边形（晶石切面）。 */
    val Geo: ImageVector = icon("Geo") {
        path(fill = fill) {
            moveTo(12f, 2.4f)
            lineTo(20.3f, 7.2f)
            lineTo(20.3f, 16.8f)
            lineTo(12f, 21.6f)
            lineTo(3.7f, 16.8f)
            lineTo(3.7f, 7.2f)
            close()
        }
    }

    /** 雷：折线闪电（与原神雷元素同构的「Z 形」而不是 Material 的实心闪电）。 */
    val Electro: ImageVector = icon("Electro") {
        path(fill = fill) {
            moveTo(13.6f, 2f)
            lineTo(5.2f, 13.4f)
            lineTo(11f, 13.4f)
            lineTo(9.6f, 22f)
            lineTo(18.8f, 10.2f)
            lineTo(12.8f, 10.2f)
            close()
        }
    }

    /** 草：叶片（两段对称弧线收成尖）。 */
    val Dendro: ImageVector = icon("Dendro") {
        path(fill = fill) {
            moveTo(12f, 2.6f)
            quadTo(20.6f, 11.2f, 12f, 21.4f)
            quadTo(3.4f, 11.2f, 12f, 2.6f)
            close()
        }
    }

    /** 水：水滴。 */
    val Hydro: ImageVector = icon("Hydro") {
        path(fill = fill) {
            moveTo(12f, 2.2f)
            curveTo(16.6f, 8.2f, 19.4f, 11.6f, 19.4f, 14.9f)
            arcToRelative(7.4f, 7.4f, 0f, false, true, -14.8f, 0f)
            curveTo(4.6f, 11.6f, 7.4f, 8.2f, 12f, 2.2f)
            close()
        }
    }

    /** 火：焰形（外焰 + 内焰缺口）。 */
    val Pyro: ImageVector = icon("Pyro") {
        path(fill = fill) {
            moveTo(12f, 1.8f)
            curveTo(12.6f, 6.4f, 18.8f, 8.2f, 18.8f, 14.2f)
            arcToRelative(6.8f, 6.8f, 0f, false, true, -13.6f, 0f)
            curveTo(5.2f, 10.6f, 8.2f, 8.4f, 9.4f, 4.6f)
            curveTo(10.6f, 7.4f, 11.4f, 8.2f, 12f, 1.8f)
            close()
        }
    }

    /** 冰：六芒雪晶（三条线 + 六个短枝）。 */
    val Cryo: ImageVector = icon("Cryo") {
        path(
            stroke = stroke,
            strokeLineWidth = 1.9f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(12f, 2.4f); lineTo(12f, 21.6f)
            moveTo(3.7f, 7.2f); lineTo(20.3f, 16.8f)
            moveTo(3.7f, 16.8f); lineTo(20.3f, 7.2f)
            // 枝杈：每根主轴在中段外折出两根短枝
            moveTo(12f, 7.2f); lineTo(9.4f, 5.2f)
            moveTo(12f, 7.2f); lineTo(14.6f, 5.2f)
            moveTo(12f, 16.8f); lineTo(9.4f, 18.8f)
            moveTo(12f, 16.8f); lineTo(14.6f, 18.8f)
        }
    }

    /** 神之眼：外环 + 内部菱形（原神里「佩戴者」的标记）。 */
    val Vision: ImageVector = icon("Vision") {
        path(stroke = stroke, strokeLineWidth = 1.9f) {
            moveTo(12f, 12.2f)
            arcToRelative(8.2f, 8.2f, 0f, true, true, 0f, -0.1f)
            close()
        }
        path(fill = fill) {
            moveTo(12f, 6.6f)
            lineTo(16.4f, 12.1f)
            lineTo(12f, 17.6f)
            lineTo(7.6f, 12.1f)
            close()
        }
    }

    /**
     * 原石（Primogem）：菱形轮廓 + 内切面线。
     *
     * 全部走 stroke、不用「白线叠黑面」：`Icon` 的 tint 是整张矢量统一上色，
     * 白线会被染成与底色相同而彻底消失。
     */
    val Primogem: ImageVector = icon("Primogem") {
        path(stroke = stroke, strokeLineWidth = 1.9f, strokeLineJoin = StrokeJoin.Round) {
            moveTo(12f, 2f)
            lineTo(20.4f, 12f)
            lineTo(12f, 22f)
            lineTo(3.6f, 12f)
            close()
        }
        path(stroke = stroke, strokeLineWidth = 1.1f) {
            moveTo(12f, 2f); lineTo(12f, 22f)
            moveTo(3.6f, 12f); lineTo(20.4f, 12f)
        }
    }

    /** 摩拉（Mora）：外圆 + 方孔（evenOdd 挖空）。 */
    val Mora: ImageVector = icon("Mora") {
        path(fill = fill, pathFillType = PathFillType.EvenOdd) {
            moveTo(12f, 2.2f)
            arcToRelative(9.8f, 9.8f, 0f, true, true, 0f, 19.6f)
            arcToRelative(9.8f, 9.8f, 0f, true, true, 0f, -19.6f)
            close()
            moveTo(9f, 9f)
            lineTo(15f, 9f)
            lineTo(15f, 15f)
            lineTo(9f, 15f)
            close()
        }
    }
}
