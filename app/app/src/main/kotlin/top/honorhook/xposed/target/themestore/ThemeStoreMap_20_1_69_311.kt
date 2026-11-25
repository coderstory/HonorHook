package top.honorhook.xposed.target.themestore

import top.honorhook.xposed.data.HookPoint

/**
 * 宿主 20.1.69.311 精确套（2026-11-25，DexKit 运行期漂移判据 + 自写 dex 类名直查双证；设备 2026-11-24 OTA 后实装版本）。
 *
 * 与基线 `THEME_POINTS_20_1_63_305` 的差异同样是**四个混淆类改名**（R8 重排；非混淆类/方法/签名全部原样）：
 * - `u15` → `yh5`（源名 SharepreferenceUtils；双 getBoolean 重载 + getInt/readString/removeKey 全在此类。
 *   运行期证据：69.311 未登记时按基线盲挂，DexKit 宽松轨对 `cloud:ShareprefUtils.getBoolean1/2` 报
 *   「期望 u15#getBoolean → 实际 yh5#getBoolean(String,boolean)/(String)」仍挂上）
 * - `bz2` → `j83`（源名 LiveWallpaperHelper；`createApplyWallPaper(WallPaperInfo)V`、
 *   `getDecryWallpaperPath(String)String` 均在此类，classes20.dex 方法名直查实证）
 * - `f71` → `zb1`（源名 DownloadItemIfVip；`downloadItem()V`、
 *   `productSendFinish(I,String,String,kotlin.Pair)V` 签名不变。同族 ub1/xb1/yb1/wb1/vb1/tb1/bc1/cc1
 *   也带同名方法（R8 复制的兄弟体），唯一性来自运行期：.311 盲挂时 DexKit 宽松轨用基线锚串
 *   把 `downloadItem` 解析到 zb1，且该点仍挂上 = 锚串命中该类）
 * - `e26` → `z16`（源名 ThemeCheckTool；`getDecryptTheme(String,String,String,Z)ThemeInfo`、
 *   `getDecryptFile(String,File)File`、`getDecryptWallpaperFile(String,File)File` 三个方法全在此类，
 *   全 dex 唯一持有者——69.311 盲挂时这三点 `installed 51/54` 正是缺这条改名）
 *
 * 与 67.303 套的关系：.303 是 xh5/b83/vb1/b26，.311 是又一次构建、四个类名全变 ⇒ 本表**取代**
 * 67.303 套（后者保留给仍停在 .303 的设备）。手维护纪律同基线表（勿脚本生成）。
 */
internal val THEME_POINTS_20_1_69_311: List<HookPoint> = THEME_POINTS_20_1_63_305.map { point ->
    when (point.className) {
        CLS_SHAREPREF_UTILS_63 -> point.copy(className = CLS_SHAREPREF_UTILS_69_311)
        CLS_WALLPAPER_DECRYPT_63 -> point.copy(className = CLS_WALLPAPER_DECRYPT_69_311)
        CLS_DOWNLOAD_ITEM_VIP_63 -> point.copy(className = CLS_DOWNLOAD_ITEM_VIP_69_311)
        CLS_THEME_CHECK_TOOL_63 -> point.copy(className = CLS_THEME_CHECK_TOOL_69_311)
        else -> point
    }
}

/** 20.1.69.311 混淆名（2026-11-25 dex 方法名直查实证：classes19/j83 在 classes20）。 */
internal const val CLS_SHAREPREF_UTILS_69_311 = "yh5"
internal const val CLS_WALLPAPER_DECRYPT_69_311 = "j83"
internal const val CLS_DOWNLOAD_ITEM_VIP_69_311 = "zb1"
internal const val CLS_THEME_CHECK_TOOL_69_311 = "z16"

/**
 * W2 付费壁纸解密要直调的宿主工具类混淆名（`getDecryptWallpaperFile(String,File)` 的宿主）。
 *
 * 69.311 实证该三方法仍在 ThemeCheckTool 本体（z16），独立类 gl5 已无此方法 ⇒ 本版本必须覆盖
 * Runtime 的 gl5 回落；gl5 本身在 .311 里是否还被 R8 复用成无关类未逐一核对（走不到，无需核）。
 */
internal const val THEME_DECRYPT_TOOL_69_311 = CLS_THEME_CHECK_TOOL_69_311
