package top.honorhook.xposed.target.themestore

import top.honorhook.xposed.data.HookPoint

/**
 * 宿主 20.1.65.302 精确套（2026-10-23，MT MCP 逐点核对 49/49 存活）。
 *
 * 与基线 `THEME_POINTS_20_1_63_305` 的**唯一差异是三个混淆类改名**（R8 重排，非混淆类/方法全部原样）：
 * - `u15` → `t15`（源名 SharepreferenceUtils.java；云控统一读点，双 getBoolean 重载签名不变，
 *   "themename" 锚点仍在此类）
 * - `bz2` → `ez2`（源名 LiveWallpaperHelper.java；createApplyWallPaper(WallPaperInfo)V 签名不变）
 * - `f71` → `g71`（源名 DownloadItemIfVip.java；downloadItem()V 不变；productSendFinish 仍 4 参
 *   但末参类型 I/String/String/kotlin.Pair——DOWNLOAD_FALLBACK 只按方法名分派，不受影响）
 *
 * 核对方式：MT MCP dex outline 逐点比对（类在 + 方法名 + smali 描述符参数数）；
 * S3 shouldBlockDetailAd 曾因 outline 分页截断误报丢失，dex_method 直查证实存活。
 * 手维护纪律同基线表（勿脚本生成）。
 */
internal val THEME_POINTS_20_1_65_302: List<HookPoint> = THEME_POINTS_20_1_63_305.map { point ->
    when (point.className) {
        CLS_SHAREPREF_UTILS_63 -> point.copy(className = CLS_SHAREPREF_UTILS_65)
        CLS_WALLPAPER_DECRYPT_63 -> point.copy(className = CLS_WALLPAPER_DECRYPT_65)
        CLS_DOWNLOAD_ITEM_VIP_63 -> point.copy(className = CLS_DOWNLOAD_ITEM_VIP_65)
        else -> point
    }
}

/** 基线（20.1.63.305）混淆名 —— 仅本文件与基线表引用，避免散落字面量。 */
internal const val CLS_SHAREPREF_UTILS_63 = "u15"
internal const val CLS_WALLPAPER_DECRYPT_63 = "bz2"
internal const val CLS_DOWNLOAD_ITEM_VIP_63 = "f71"

/** 20.1.65.302 混淆名（MT MCP smali 实证，2026-10-23）。 */
internal const val CLS_SHAREPREF_UTILS_65 = "t15"
internal const val CLS_WALLPAPER_DECRYPT_65 = "ez2"
internal const val CLS_DOWNLOAD_ITEM_VIP_65 = "g71"
