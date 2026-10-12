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
    const val KEY_LAUNCHER_KEEP_FOLDER_OPEN = "launcher.keep_folder_open"

    const val KEY_SYSTEMUI_HIDE_SIM2 = "systemui.hide_sim2"
    const val KEY_SYSTEMUI_HIDE_5G = "systemui.hide_5g"

    const val KEY_MMS_AD_OFF = "mms.ad_off"

    const val KEY_IME_CODE_FREE = "ime.code_free"

    const val KEY_ADS_BLOCK = "ads.block"
    const val KEY_TRACK_BLOCK = "tracking.block"

    data class Feature(val key: String, val title: String, val description: String)

    /**
     * [iconKey] 虚拟目标（非真实 app）的内置图标提示，UI 侧据此画 vector 而非 PackageManager 图标。
     * 真实 app 目标留 null。当前取值："shield"。
     */
    data class Target(
        val packageName: String,
        val name: String,
        val features: List<Feature>,
        val iconKey: String? = null,
        /** 宿主挂载基线 versionName（反编译实证版本）；null = 不按宿主版本卡（SDK 通杀类/虚拟目标）。
         *  单一事实源：各 hook 的 MountEngine.begin 版本自检读这里（原各文件私有 HOST_VERSION_BASELINE 已上提）；
         *  新增目标必须在此登记，查落空 = 该目标版本自检静默关闭（baselineVersionOf 返回 null）。 */
        val baselineVersion: String? = null,
        /** 基线之外额外适配的精确套版本（如 installer 旧版 14.0.22.326，与 InstallerHook.installerTables 联动，勿单侧改）。 */
        val extraVersions: Set<String> = emptySet(),
    ) {
        /** 全部受支持宿主版本（派生不落盘，UI「版本未适配」tag 用）。
         *  已按数字段升序排序（"14.0.37.301" > "9.9.9.9"），末位恒为最新——UI meta 行取 last 即最新，
         *  不依赖 targets 里的声明序；"-release" 等非数字尾缀截断后按 0 计。 */
        val supportedVersions: List<String>
            get() = (setOfNotNull(baselineVersion) + extraVersions).sortedWith { a, b ->
                val ka = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
                val kb = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
                for (i in 0 until maxOf(ka.size, kb.size)) {
                    val c = (ka.getOrElse(i) { 0 }).compareTo(kb.getOrElse(i) { 0 })
                    if (c != 0) return@sortedWith c
                }
                0
            }
    }

    val targets: List<Target> = listOf(
        Target(
            "com.hihonor.android.thememanager", "主题商店",
            listOf(
                Feature(KEY_THEME_VIP, "VIP 会员解锁", "免费解锁主题商店 VIP 会员，拦截「已过期」挽留弹窗与续费引导"),
                Feature(KEY_THEME_FREE, "主题全免费", "付费主题/壁纸价格归零、已购标记常真，修复付费下载 0% 卡死"),
                Feature(KEY_THEME_TRIAL, "试用永不过期", "试用主题永久有效、无倒计时与「试用中」角标，应用后永不回退默认主题"),
                Feature(KEY_THEME_AD_OFF, "广告净化", "关停云控广告/福利/会员精选入口，裁撤美豆槽位与底部 VIP/福利 tab"),
            ),
            baselineVersion = "20.1.63.305",   // aapt2 实证（原 ThemeStoreHook 私有常量上提）
        ),
        Target(
            "com.android.packageinstaller", "包管理器",
            listOf(
                Feature(KEY_INSTALLER_AD, "广告净化", "安装页不展示广告，不发起广告/AB 实验请求"),
                Feature(KEY_INSTALLER_NO_INTERCEPT, "安装不拦截", "三方来源安装异常跳转不拦截，安全模式默认关闭"),
                Feature(KEY_INSTALLER_NO_SAFE_PROMPT, "安全模式提示净化", "不弹「开启安全模式」引导弹窗与安装页提示卡"),
                Feature(KEY_INSTALLER_SKIP_CHECK, "跳过安全检测", "跳过联网安全检测与安装指纹校验，直装不弹风险页/勾选框"),
            ),
            baselineVersion = "14.0.37.301",  // aapt2 实证（原 InstallerHook 私有常量上提）
            extraVersions = setOf("14.0.22.326"),
        ),
        Target(
            "com.android.settings", "系统设置",
            listOf(
                Feature(KEY_SETTINGS_DISABLE_BTN, "停用按钮恒可点", "应用详情页「停用」按钮对任意应用（含系统应用）恒可点"),
            ),
            baselineVersion = "14.0.0.210",   // pm 实证（原 SettingsHook 私有常量上提）
        ),
        Target(
            "com.exam8.chengrenGK", "成考题库",
            listOf(
                Feature(KEY_EXAM8_SUPER_VIP, "超级会员解锁", "本地伪造超级会员态，解锁会员专属题库与功能（客户端门）"),
                Feature(KEY_EXAM8_BURN, "阅后即焚解锁", "解锁「阅后即焚」特权位，题目/答案可完整查看"),
            ),
            baselineVersion = "5.9.0.0-release",  // dumpsys 实证（原 Exam8Hook 私有常量上提）
        ),
        Target(
            "com.hihonor.android.launcher", "系统桌面",
            listOf(
                Feature(KEY_LAUNCHER_HIDE_TITLE, "隐藏卡片标题", "隐藏桌面卡片堆栈标题（如「YOYO 建议 · 明日天气预报」），不影响图标名"),
                Feature(KEY_LAUNCHER_KEEP_FOLDER_OPEN, "文件夹保持打开", "从文件夹里打开应用后，返回桌面时文件夹自动重新打开（走系统原生动画，含背景模糊）；点击外部/Home 仍正常关闭"),
            ),
            baselineVersion = "18.0.0.234",  // dumpsys package 实证（原 LauncherHook 私有常量上提）
        ),
        Target(
            "com.android.systemui", "SystemUI",
            listOf(
                Feature(KEY_SYSTEMUI_HIDE_SIM2, "隐藏副卡图标", "隐藏副卡(SIM2)状态栏信号图标，双卡时只显示主卡"),
                Feature(KEY_SYSTEMUI_HIDE_5G, "隐藏 5G 标识", "不显示状态栏信号图标旁的 5G/4G 类型标，单卡双卡一律不显示"),
            ),
            baselineVersion = "17.0.0.1",    // dumpsys 实证 2026-10-10（原 SystemUiHook 私有常量上提）
        ),
        Target(
            "com.hihonor.mms", "短信",
            listOf(
                Feature(
                    KEY_MMS_AD_OFF, "广告净化",
                    "去除智能信息营销内容——10010 会话底部自定义菜单（流量充值/1元30G/百元回馈）、卡片按钮（1元30G/抢100G）、应用推广卡（查看应用跳应用市场）",
                ),
            ),
        ),
        Target(
            "com.sohu.inputmethod.sogou.honor", "搜狗键盘",
            listOf(
                Feature(
                    KEY_IME_CODE_FREE, "验证码候选免同意",
                    "键盘候选区「短信验证码一键填充」不再要求同意搜狗联网服务——只解绑本功能的同意擦除（提码/正则全程本地），其余云功能同意门原样",
                ),
            ),
        ),
        Target(
            "com.hihonor.android.totemweather", "荣耀系统净化",
            listOf(
                Feature(
                    KEY_ADS_BLOCK, "广告净化·通杀",
                    "拦截荣耀广告 SDK（com.hihonor.adsdk）渲染：日历/天气/浏览器/游戏中心/视频的信息流·横幅·开屏·插屏·激励广告内容清空并折叠占位",
                ),
                Feature(
                    KEY_TRACK_BLOCK, "去除跟踪",
                    "拦截荣耀埋点 SDK（com.hihonor.hianalytics）上报：日历/天气/浏览器/游戏中心/视频/云音乐的事件·会话·点击·曝光埋点与 OAID 标识注入全部短路，数据不落库不出网",
                ),
            ),
            iconKey = "shield",
        ),
    )

    /** 查表：宿主挂载基线版本（未登记返回 null = 该目标版本自检静默关闭）。 */
    fun baselineVersionOf(packageName: String): String? = targets.firstOrNull { it.packageName == packageName }?.baselineVersion

    /** 查表：基线之外额外适配的精确套版本（未登记返回空集）。 */
    fun extraVersionsOf(packageName: String): Set<String> = targets.firstOrNull { it.packageName == packageName }?.extraVersions ?: emptySet()
}
