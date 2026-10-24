package top.honorhook.xposed.target

import top.honorhook.xposed.target.ads.HonorAdsHook
import top.honorhook.xposed.target.ads.HonorOpsAdHook
import top.honorhook.xposed.target.ads.HonorQinxuanHook
import top.honorhook.xposed.target.exam8.Exam8Hook
import top.honorhook.xposed.target.ime.ImeHook
import top.honorhook.xposed.target.jd.JdHook
import top.honorhook.xposed.target.mfauth.MfaAuthHook
import top.honorhook.xposed.target.mms.MmsHook
import top.honorhook.xposed.target.themestore.ThemeStoreHook
import top.honorhook.xposed.target.wallpaper.WallpaperLoopHook
import top.honorhook.xposed.target.wallpaper.WallpaperTimelineHook
import top.honorhook.xposed.target.wallpaper.WallpaperTranscodeHook
import top.honorhook.xposed.target.tracking.HonorTrackingHook
import top.honorhook.xposed.target.xhs.XhsHook

/**
 * 目标注册表（融合 Rain va 静态注册表 + HC 布尔防重装；`.claude/skills/lsposed-hook-expert/references/rain-dexkit-and-mount-patterns.md`）。
 * 同包可注册多个 TargetHook（如日历的 ads/ops/tracking 三路）；[installed] 是完成首次 install 的
 * **目标对象**集合——对象级判定：同一对象多包键共享安装状态，防别名包共享进程双装。
 */
object TargetRegistry {

    private val targets = mutableMapOf<String, MutableList<TargetHook>>()
    private val installed = mutableSetOf<TargetHook>()

    init {
        // 静态注册（Rain va.<clinit> 硬编码单例的等价物）：新增目标 = 新建 TargetHook + 此处一行，HookEntry 零改动。
        // scope.list 须同步声明目标包名（targets 与 scope 不一致时 HookEntry 收不到该包的回调）。
        // 壁纸视频增强（W16/W17/W18，2026-10-18）：转码补码率 + 桌面循环 + 时间轴去 10s 上限；scope.list 已同步
        register(WallpaperTranscodeHook)
        register(WallpaperLoopHook)
        register(WallpaperTimelineHook)
        register(ThemeStoreHook)
        register(InstallerHook)
        register(SettingsHook)
        register(Exam8Hook)
        register(LauncherHook)
        register(SystemUiHook)
        // 系统更新阻断（ota.block 一个开关管两个包，同 ads.block 跨包先例）
        register(OucHook)
        register(UpgradeGuideHook)
        // 荣耀短信（第 7 目标，T1 骨架：只观测不干预）。
        register(MmsHook)
        // 搜狗输入法荣耀版（第 8 目标，T4：验证码候选免联网服务同意）。
        register(ImeHook)
        // 荣耀系统广告 SDK（com.hihonor.adsdk）通杀净化：同一 SDK 库，按包分别注册挂载。
        register(HonorAdsHook("com.hihonor.calendar"))
        register(HonorAdsHook("com.hihonor.android.totemweather"))
        register(HonorAdsHook("com.honor.browser"))
        register(HonorAdsHook("com.hihonor.quickgame"))
        register(HonorAdsHook("com.hihonor.youku.video"))
        // 日历专属运营推荐位（com.hihonor.osoperationsdk，仅日历内置 228 类）：adsdk 渲染层之外的第二条广告通路。
        register(HonorOpsAdHook("com.hihonor.calendar"))
        // 荣耀埋点 SDK（com.hihonor.hianalytics）通杀拦截：同上，按包分别注册（多云音乐，无广告 SDK 但埋点全量）。
        register(HonorTrackingHook("com.hihonor.calendar"))
        register(HonorTrackingHook("com.hihonor.android.totemweather"))
        register(HonorTrackingHook("com.honor.browser"))
        register(HonorTrackingHook("com.hihonor.quickgame"))
        register(HonorTrackingHook("com.hihonor.youku.video"))
        register(HonorTrackingHook("com.hihonor.cloudmusic"))
        // 运动健康（2026-10-24，docs/reverse/health.md）：adsdk+埋点通杀已核对存在；
        // 另有内嵌亲选电商（cn.honor.qinxuan 496 类，自建 View 不经 BaseAdView）→ HonorQinxuanHook 收口。
        // osoperationsdk 不存在于 health，HonorOpsAdHook 不注册（挂了只产生 NOT_RESOLVED 噪音）。
        register(HonorAdsHook("com.hihonor.health"))
        register(HonorTrackingHook("com.hihonor.health"))
        register(HonorQinxuanHook("com.hihonor.health"))
        // 京东开屏（第 9 目标）：hookBefore JDHomeManager.a 短路 = SplashFragment 永不挂载。
        register(JdHook)
        // MFA身份验证器开屏（第 10 目标）：360 加固，框架 Activity.onCreate 收口 + 类名门控跳页。
        register(MfaAuthHook)
        // 系统管家服务（第 11 目标）：病毒检测/查杀/弹窗三路全禁；scope.list 已同步。
        register(SysmgrHook)
        // 小红书（第 12 目标）：保存解锁/去水印/原图/视频直链/批量五路；scope.list 已同步。
        register(XhsHook)
    }

    /** 注册目标（同包多次注册并存为列表；仅 [init] 期静态注册使用）。 */
    private fun register(target: TargetHook) = synchronized(this) {
        targets.getOrPut(target.packageName) { mutableListOf() }.add(target)
    }

    /** 按包名查全部目标（未注册返回空：被注入但无适配器时静默，照 Rain 同情形）。 */
    fun find(packageName: String): List<TargetHook> = synchronized(this) {
        targets[packageName].orEmpty()
    }

    /**
     * 目标对象是否已装（对象级防重装门控）：多键共享安装状态，别名包共享进程时防 double mount。
     */
    fun isInstalled(target: TargetHook): Boolean = synchronized(this) {
        target in installed
    }

    fun markInstalled(target: TargetHook) {
        synchronized(this) { installed += target }
    }
}
