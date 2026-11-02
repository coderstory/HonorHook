package top.honorhook.xposed.target.themestore

import top.honorhook.xposed.data.HookPoint

/**
 * 宿主 20.1.67.302 精确套（2026-11-02，MT MCP 锚点实证）。
 *
 * 真机判据：installed 48/49（基线表）——唯一失效点 = F-fallback②
 * `theme:DownloadItemVip.productSendFinish`，即混淆类 `f71` 在本版改名。
 * 锚点串 "Start to download vip product" 定位：63.305=f71 → 65.302=g71 → 67.302=**wb1**
 * （outline 实证 source=DownloadItemIfVip.java，downloadItem()V 与
 * productSendFinish(ILjava/lang/String;Ljava/lang/String;Lkotlin/Pair;)V 签名均不变）。
 * 其余 48 点在基线表下全部命中 ⇒ 本版只重排了这一个类名。
 */
internal val THEME_POINTS_20_1_67_302: List<HookPoint> = THEME_POINTS_20_1_63_305.map { point ->
    if (point.className == CLS_DOWNLOAD_ITEM_VIP_63) point.copy(className = CLS_DOWNLOAD_ITEM_VIP_67)
    else point
}

/** 20.1.67.302 混淆名（MT MCP dex 字符串锚点实证，2026-11-02）。 */
internal const val CLS_DOWNLOAD_ITEM_VIP_67 = "wb1"
