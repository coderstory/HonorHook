package top.honorhook

/**
 * 功能目录：目标应用 → 功能开关（key/title/description）的单一事实源。
 *
 * 纯 Kotlin 数据结构（无 Compose/miuix 依赖），宿主 UI（Tab2 功能页）与 hook 侧（注入目标进程）
 * 共用同一份 key 字面量，避免双端漂移。远端 prefs group 名也在此统一声明。
 */
object FeatureCatalog {

    /** 远端 prefs group 名（宿主经 XposedService 写，hook 侧经 getRemotePreferences 只读）。 */
    const val REMOTE_GROUP = "honorhook_config"

    // ---- 功能 key（与 collect* 分组一一对应）----
    const val KEY_THEME_VIP = "theme.vip"
    const val KEY_THEME_FREE = "theme.free"
    const val KEY_THEME_TRIAL = "theme.trial"
    const val KEY_THEME_AD_OFF = "theme.ad_off"

    const val KEY_INSTALLER_AD = "installer.ad"
    const val KEY_INSTALLER_NO_INTERCEPT = "installer.no_intercept"
    const val KEY_INSTALLER_NO_SAFE_PROMPT = "installer.no_safe_prompt"
    const val KEY_INSTALLER_SKIP_CHECK = "installer.skip_check"

    const val KEY_SETTINGS_DISABLE_BTN = "settings.disable_btn"

    const val KEY_EXAM8_SUPER_VIP = "exam8.super_vip"
    const val KEY_EXAM8_BURN = "exam8.burn_reading"

    const val KEY_LAUNCHER_HIDE_TITLE = "launcher.hide_card_title"

    const val KEY_SYSTEMUI_HIDE_SIM2 = "systemui.hide_sim2"

    data class Feature(val key: String, val title: String, val description: String)

    data class Target(val packageName: String, val name: String, val features: List<Feature>)

    val targets: List<Target> = listOf(
        Target(
            "com.hihonor.android.thememanager", "主题商店",
            listOf(
                Feature(KEY_THEME_VIP, "VIP 会员解锁", "免费解锁主题商店 VIP 会员，拦截「已过期」挽留弹窗与续费引导"),
                Feature(KEY_THEME_FREE, "主题全免费", "付费主题/壁纸价格归零、已购标记常真，修复付费下载 0% 卡死"),
                Feature(KEY_THEME_TRIAL, "试用永不过期", "试用主题永久有效、无倒计时与「试用中」角标，应用后永不回退默认主题"),
                Feature(KEY_THEME_AD_OFF, "广告净化", "关停云控广告/福利/会员精选入口，裁撤美豆槽位与底部 VIP/福利 tab"),
            ),
        ),
        Target(
            "com.android.packageinstaller", "包管理器",
            listOf(
                Feature(KEY_INSTALLER_AD, "广告净化", "安装页不展示广告，不发起广告/AB 实验请求"),
                Feature(KEY_INSTALLER_NO_INTERCEPT, "安装不拦截", "三方来源安装异常跳转不拦截，安全模式默认关闭"),
                Feature(KEY_INSTALLER_NO_SAFE_PROMPT, "安全模式提示净化", "不弹「开启安全模式」引导弹窗与安装页提示卡"),
                Feature(KEY_INSTALLER_SKIP_CHECK, "跳过安全检测", "跳过联网安全检测与安装指纹校验，直装不弹风险页/勾选框"),
            ),
        ),
        Target(
            "com.android.settings", "系统设置",
            listOf(
                Feature(KEY_SETTINGS_DISABLE_BTN, "停用按钮恒可点", "应用详情页「停用」按钮对任意应用（含系统应用）恒可点"),
            ),
        ),
        Target(
            "com.exam8.chengrenGK", "成考题库",
            listOf(
                Feature(KEY_EXAM8_SUPER_VIP, "超级会员解锁", "本地伪造超级会员态，解锁会员专属题库与功能（客户端门）"),
                Feature(KEY_EXAM8_BURN, "阅后即焚解锁", "解锁「阅后即焚」特权位，题目/答案可完整查看"),
            ),
        ),
        Target(
            "com.hihonor.android.launcher", "系统桌面",
            listOf(
                Feature(KEY_LAUNCHER_HIDE_TITLE, "隐藏卡片标题", "隐藏桌面卡片堆栈标题（如「YOYO 建议 · 明日天气预报」），不影响图标名"),
            ),
        ),
        Target(
            "com.android.systemui", "SystemUI",
            listOf(
                Feature(KEY_SYSTEMUI_HIDE_SIM2, "隐藏副卡图标", "隐藏副卡(SIM2)状态栏信号图标，双卡时只显示主卡"),
            ),
        ),
    )
}
