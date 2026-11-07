package top.honorhook.xposed.target.themestore

import top.honorhook.xposed.data.HookPoint

/**
 * 宿主 20.1.67.303 精确套（2026-11-07，MT MCP 锚点实证；设备 2026-11-06 OTA 后实装版本）。
 *
 * 与基线 `THEME_POINTS_20_1_63_305` 的差异是**四个混淆类改名**（R8 重排；非混淆类/方法/签名全部原样）：
 * - `u15` → `xh5`（源名 SharepreferenceUtils；云控统一读点，双 getBoolean 重载签名不变，
 *   `"themename"` 串锚仍在此类）
 * - `bz2` → `b83`（源名 LiveWallpaperHelper；`createApplyWallPaper(WallPaperInfo)V`、
 *   `getDecryWallpaperPath(String)String` 均在此类，2026-11-07 dex_names 直查实证）
 * - `f71` → `vb1`（源名 DownloadItemIfVip；锚串 `"Start to download vip product"` 与
 *   `"productSendFinish downloadUrl is Empty ? "` 同在此类；`downloadItem()V`、
 *   `productSendFinish(I,String,String,kotlin.Pair)V` 签名不变。同族 qb1/tb1/ub1 只有
 *   productSendFinish 无 downloadItem 锚，故不取）
 * - `e26` → `b26`（源名 ThemeCheckTool；`getDecryptTheme(String,String,String,Z)ThemeInfo`、
 *   `getDecryptFile(String,File)File`、`getDecryptWallpaperFile(String,File)File` 三个方法全在此类）
 *
 * 与 67.302 套的关系：67.302 只重排了 f71→wb1（2026-11-02 采集），.303 是又一次构建，四个类名全变
 * ⇒ 本表**取代** 67.302 套（后者保留给仍停在 .302 的设备）。手维护纪律同基线表（勿脚本生成）。
 */
internal val THEME_POINTS_20_1_67_303: List<HookPoint> = THEME_POINTS_20_1_63_305.map { point ->
    when (point.className) {
        CLS_SHAREPREF_UTILS_63 -> point.copy(className = CLS_SHAREPREF_UTILS_67_303)
        CLS_WALLPAPER_DECRYPT_63 -> point.copy(className = CLS_WALLPAPER_DECRYPT_67_303)
        CLS_DOWNLOAD_ITEM_VIP_63 -> point.copy(className = CLS_DOWNLOAD_ITEM_VIP_67_303)
        CLS_THEME_CHECK_TOOL_63 -> point.copy(className = CLS_THEME_CHECK_TOOL_67_303)
        else -> point
    }
}

/** 基线（20.1.63.305）ThemeCheckTool 混淆名 —— 基线表里是字面量 "e26"，本表起改用常量。 */
internal const val CLS_THEME_CHECK_TOOL_63 = "e26"

/** 20.1.67.303 混淆名（MT MCP dex outline + 方法名直查实证，2026-11-07）。 */
internal const val CLS_SHAREPREF_UTILS_67_303 = "xh5"
internal const val CLS_WALLPAPER_DECRYPT_67_303 = "b83"
internal const val CLS_DOWNLOAD_ITEM_VIP_67_303 = "vb1"
internal const val CLS_THEME_CHECK_TOOL_67_303 = "b26"

/**
 * W2 付费壁纸解密要直调的宿主工具类混淆名（`getDecryptWallpaperFile(String,File)` 的宿主）。
 *
 * 历史版本里这个工具是**独立类 gl5**（不在挂载表内，运行时按名加载）；20.1.67.303 实证 gl5 已被 R8
 * 复用成不相关的 Rx 类，解密三方法整体并入 ThemeCheckTool（b26）⇒ 只有本版本需要覆盖这个默认值，
 * 其余版本继续走 ThemeStoreRuntime 的 gl5 回落。
 */
internal const val THEME_DECRYPT_TOOL_67_303 = CLS_THEME_CHECK_TOOL_67_303
