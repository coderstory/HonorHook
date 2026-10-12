package top.honorhook.xposed.target

import top.honorhook.xposed.target.ads.HonorAdsHook
import top.honorhook.xposed.target.ads.HonorOpsAdHook
import top.honorhook.xposed.target.exam8.Exam8Hook
import top.honorhook.xposed.target.themestore.ThemeStoreHook
import top.honorhook.xposed.target.tracking.HonorTrackingHook

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
        register(ThemeStoreHook)
        register(InstallerHook)
        register(SettingsHook)
        register(Exam8Hook)
        register(LauncherHook)
        register(SystemUiHook)
        // 系统更新阻断（ota.block 一个开关管两个包，同 ads.block 跨包先例）
        register(OucHook)
        register(UpgradeGuideHook)
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
    }

    /** 注册目标（同包多次注册并存为列表；仅 [init] 期静态注册使用）。 */
    private fun register(target: TargetHook) {
        targets.getOrPut(target.packageName) { mutableListOf() }.add(target)
    }

    /** 按包名查全部目标（未注册返回空：被注入但无适配器时静默，照 Rain 同情形）。 */
    fun find(packageName: String): List<TargetHook> = targets[packageName].orEmpty()

    /**
     * 目标对象是否已装（对象级防重装门控）：多键共享安装状态，别名包共享进程时防 double mount。
     */
    fun isInstalled(target: TargetHook): Boolean = target in installed

    fun markInstalled(target: TargetHook) {
        installed += target
    }
}
