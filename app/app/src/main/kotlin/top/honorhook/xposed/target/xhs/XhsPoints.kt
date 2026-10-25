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
 * 差异账本：逐版本坐标迁移与证据（file:line / dexdump / smali 实证）见 docs/reverse/xhs.md
 * §八「新挂载点对照」（9.49.1→9.50.0）与 §「9.51.0 适配」坐标迁移表——本表只存结论坐标，
 * 不复制论证（2026-10-26 下沉后口径；9.49.1 的三个 null 点 = 该版未定位，旧宿主走 videoClick
 * 轨 + X4 拦截 + 落盘闸 = 9.49 期行为）。
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
        // 9.50 时是「未混淆跨版本同名」留在 hook 文件；9.51 实证 xec.w 整类消失 ⇒ 升格进表走版本分发
        "albumCopy.h0" to "xec.w",
        "albumCopy.e0" to "xec.w",
        // 9.51 新形态落盘闸：VideoStorageManager#a（视频拷贝进 MediaStore，串锚 "saveVideo start copy file."）。
        // 基线 null = 9.49/9.50 无此点（走 h0/e0 闸）；类本体未混淆跨版本同名概率高，仍进表留分发口
        "albumCopy.videoStore" to null,
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
        // 9.51.0（2026-10-23 真机升级，MT MCP 逐点核对；逐点证据、yi9.f 误判澄清与 xec.w 消失定位
        // 全案见 docs/reverse/xhs.md §「9.51.0 适配」坐标迁移表，2026-10-26 下沉后只留结论）：
        // - saveClick：ji9.b → yi9.b（真机 DexKit 漂移命中实证后落表，运行时兜底轨可退役一程）
        // - videoClick：**9.51 起不挂载（无需，非跳过）**——点击拦截价值已被 noteSaveEntry（pb9.o1）
        //   上移覆盖，效果等价；未来 noteSaveEntry 失效且需前置拦截时再重启此点
        // - x2b：nd8.j → ce8.j；noteSaveEntry：ab9.r1 → pb9.o1；aigcEditLaunch：mg4.b → rg4.b
        //   （mg4.b 成 0 方法空壳）——串锚/签名细节见 doc 迁移表
        // - downloaderEntry：b75.h → g75.h。⚠️ 坑（留在源码）：9.51 的 b75.h 是同名 SuspendLambda
        //   （R8 跨版本名字复用），按类名存在性查会误判存活；真身认串锚 addDownloadTask 唯一宿主，
        //   e 仍 13 参但改 static（首参 receiver，合成尾 I/Object 计数不变）
        // - albumCopy.h0/e0：xec.w 整类消失、新家未定位（形状不同，错挂会拦全 App 媒体落盘）→
        //   null 诚实跳过（downloaderEntry 接管时宿主零下载零落盘，闸只是第二层防线）
        "9.51.0" to mapOf(
            "saveClick" to "yi9.b",
            "videoClick" to null,
            "x2b" to "ce8.j",
            "noteSaveEntry" to "pb9.o1",
            "aigcEditLaunch" to "rg4.b",
            "downloaderEntry" to "g75.h",
            "albumCopy.h0" to null,
            "albumCopy.e0" to null,
            "albumCopy.videoStore" to "dz8.t",
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
