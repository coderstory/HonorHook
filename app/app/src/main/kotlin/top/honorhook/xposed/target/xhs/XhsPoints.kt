package top.honorhook.xposed.target.xhs

import top.honorhook.xposed.core.HostVersion

/**
 * xhs 版本坐标唯一事实源（2026-10-20 用户拍板「旧宿主保持旧 hook、坐标抽层」）。
 *
 * 只收**随宿主 R8 漂移的混淆坐标**；未混淆点（xec.w、okhttp3、com.xingin.entities.*、
 * com.xingin.capa.*）跨版本同名，留在各 hook 文件不进表。
 *
 * 版本适配流程（masterplan §五·A）：新宿主 = 反编译核对漂移点 → [overrides] 加一行
 * （或在 [baseline] 换新基线 + 旧版本降为 override 套）。点在某版本不存在/未定位 = 值 null，
 * 挂载处跳过该点（逻辑层回落既有兜底轨），`installed N/M` 的 N 随之变化可判。
 *
 * 差异账本（9.49.1 → 9.50.0，23ce86a 与 2026-10-19/20 适配实证）：
 * - saveClick：mh9.b → ji9.b（X5 图片长按面板，双串锚两版同在）
 * - videoClick：d49.q → a59.q（X5 视频保存点击轨，classFieldTypes 锚两版同构）
 * - x2b：qc8.j → nd8.j（AI 水标位图，findings-x2b）
 * - noteSaveEntry / aigcEditLaunch / downloaderEntry：9.50.0 反编译新发现（ab9.r1#b / mg4.b#invoke /
 *   b75.h#e），9.49.1 未定位 → null（旧宿主走 videoClick 轨 + X4 拦截 + 落盘闸 = 9.49 期行为）
 */
object XhsPoints {

    /** 基线套（= FeatureCatalog xhs baselineVersion 的坐标）。 */
    private val baseline = mapOf(
        "saveClick" to "ji9.b",
        "videoClick" to "a59.q",
        "x2b" to "nd8.j",
        "noteSaveEntry" to "ab9.r1",
        "aigcEditLaunch" to "mg4.b",
        "downloaderEntry" to "b75.h",
    )

    /** 版本覆盖套：版本名 → 点 id → 坐标（null = 该版本无此点）。 */
    private val overrides: Map<String, Map<String, String?>> = mapOf(
        "9.49.1" to mapOf(
            "saveClick" to "mh9.b",
            "videoClick" to "d49.q",
            "x2b" to "qc8.j",
            "noteSaveEntry" to null,
            "aigcEditLaunch" to null,
            "downloaderEntry" to null,
        ),
    )

    /**
     * 查点坐标，三态：
     * ① 命中覆盖套且值为 null → null（该版本无此点，挂载处跳过该点）；
     * ② 命中覆盖套且有值 → 覆盖值；
     * ③ 版本无覆盖项 / 版本为 null → 基线值（基线也查不到 = 未知 key → null）。
     * 挂载处契约：`XhsPoints.cls(v, key)?.let { …SimpleTask… } ?: 跳过（不进 pending）`。
     */
    fun cls(hostVersion: String?, key: String): String? {
        val map = hostVersion?.let { overrides[it] } ?: return baseline[key]
        if (!map.containsKey(key)) return baseline[key]
        // 覆盖表里的显式 null = 「该版本无此点」（文件头 KDoc 语义），必须与「该版本无覆盖项」区分：
        // 原实现 `over ?: baseline[key]` 把两者混同 ⇒ 9.49.1 的三个 null 点会错误回落基线坐标。
        return map[key]
    }
}
