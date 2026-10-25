package top.honorhook

/**
 * 功能目录：目标应用 → 功能开关（key/标题/说明）的单一事实源。
 *
 * 纯 Kotlin 数据结构（无 Compose/miuix 依赖），宿主 UI（Tab2 功能页）与 hook 侧（注入目标进程）
 * 共用同一份 key 字面量，避免双端漂移。远端 prefs group 名也在此统一声明。
 *
 * 展示文本（目标名 / 功能名 / 说明）不落在这里，统一走 `R.string`（strings.xml 单一事实源，
 * 命名约定见该文件注释）；本类只持有资源 id，hook 侧只用 key、不读展示文本。
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
    const val KEY_SETTINGS_HIDE_ACCOUNT_DOT = "settings.hide_account_dot"

    const val KEY_EXAM8_SUPER_VIP = "exam8.super_vip"
    const val KEY_EXAM8_BURN = "exam8.burn_reading"

    const val KEY_LAUNCHER_HIDE_TITLE = "launcher.hide_card_title"
    const val KEY_LAUNCHER_KEEP_FOLDER_OPEN = "launcher.keep_folder_open"
    const val KEY_LAUNCHER_RECENTS_KEEP_ATMS = "launcher.recents_keep_current_atms"
    const val KEY_LAUNCHER_RECENTS_KEEP_MANAGER = "launcher.recents_keep_current_manager"

    const val KEY_SYSTEMUI_HIDE_SIM2 = "systemui.hide_sim2"
    const val KEY_SYSTEMUI_HIDE_5G = "systemui.hide_5g"
    const val KEY_SYSTEMUI_DUAL_SIGNAL = "systemui.dual_signal"
    const val KEY_SYSTEMUI_DUAL_SIGNAL_LABEL = "systemui.dual_signal_label"
    const val KEY_SYSTEMUI_ICON_WIFI = "systemui.icon_wifi"
    const val KEY_SYSTEMUI_ICON_STATUS = "systemui.icon_status"
    const val KEY_SYSTEMUI_HIDE_CHARGE_NOTIF = "systemui.hide_charge_notif"
    const val KEY_SYSTEMUI_HIDE_HOTSPOT_NOTIF = "systemui.hide_hotspot_notif"
    const val KEY_SYSTEMUI_HIDE_ADB_NOTIF = "systemui.hide_adb_notif"
    const val KEY_SYSTEMUI_FLASHLIGHT_IGNORE_LOW_BATT = "systemui.flashlight_ignore_low_batt"

    const val KEY_MMS_AD_OFF = "mms.ad_off"

    const val KEY_JD_SPLASH = "jd.splash"
    const val KEY_MFA_SPLASH = "mfa.splash"
    const val KEY_MIH_FACE_FREE = "mih.face_free"
    const val KEY_SYSMGR_ANTIVIRUS = "sysmgr.antivirus"

    const val KEY_XHS_SAVE_UNLOCK = "xhs.save_unlock"
    const val KEY_XHS_TAKEOVER_SAVE = "xhs.takeover_save"

    const val KEY_WALLPAPER_TRANSCODE_QUALITY = "wallpaper.transcode_quality"
    const val KEY_WALLPAPER_TIMELINE_UNLIMITED = "wallpaper.timeline_unlimited"
    const val KEY_WALLPAPER_LOOP = "wallpaper.loop"

    const val KEY_IME_CODE_FREE = "ime.code_free"

    const val KEY_ADS_BLOCK = "ads.block"
    const val KEY_TRACK_BLOCK = "tracking.block"
    const val KEY_OTA_BLOCK = "ota.block"
    const val KEY_MARKET_PURIFY = "market.purify"

    data class Feature(
        val key: String,
        val titleRes: Int,
        val descRes: Int,
        /** 默认开关态（全新安装落盘值）；全插件产品规则 = 默认开（2026-10-15 用户拍板，取代 2026-10-14 全关规则），显式传 false 才默认关。 */
        val defaultEnabled: Boolean = true,
        /** 互斥功能 key：本开关被打开时该 key 强制落盘 false（UI 与 store 单点实现，见 FeatureStore.setEnabled）。 */
        val excludesWith: String? = null,
    )

    /**
     * [iconKey] 虚拟目标（非真实 app）的内置图标提示，UI 侧据此画 vector 而非 PackageManager 图标。
     * 真实 app 目标留 null。当前取值："shield"。
     */
    data class Target(
        val packageName: String,
        val nameRes: Int,
        val features: List<Feature>,
        val iconKey: String? = null,
        /** 宿主挂载基线 versionName（反编译实证版本）；null = 不按宿主版本卡（SDK 通杀类/虚拟目标）。
         *  单一事实源：各 hook 的 MountEngine.begin 版本自检读这里（原各文件私有 HOST_VERSION_BASELINE 已上提）；
         *  新增目标必须在此登记，查落空 = 该目标版本自检静默关闭（baselineVersionOf 返回 null）。 */
        val baselineVersion: String? = null,
        /** 基线之外额外适配的精确套版本（如 installer 旧版 14.0.22.326、新版 14.0.38.301，与 InstallerHook.installerTables 联动，勿单侧改）。 */
        val extraVersions: Set<String> = emptySet(),
        /** 仅从「功能」一级列表隐藏（功能入口归并到别的卡）；scope 页与版本查表仍走这里。
         *  用于多宿主合一的功能：宿主 Target 留壳（baseline/包名），UI 不单独出卡。 */
        val hidden: Boolean = false,
    ) {
        /** 全部受支持宿主版本（派生不落盘，UI「版本未适配」tag 用）。
         *  已按数字段升序排序（"14.0.37.301" > "9.9.9.9"），末位恒为最新——UI meta 行取 last 即最新，
         *  不依赖 targets 里的声明序；"-release" 等非数字尾缀截断后按 0 计。
         *  比较器唯一实现在共享层 [VersionName.compare]（hook 侧选表共用，消双处漂移）。 */
        val supportedVersions: List<String>
            get() = (setOfNotNull(baselineVersion) + extraVersions).sortedWith { a, b ->
                VersionName.compare(a, b)
            }
    }

    val targets: List<Target> = listOf(
        Target(
            "com.hihonor.android.thememanager", R.string.target_name_thememanager,
            listOf(
                Feature(KEY_THEME_VIP, R.string.feature_theme_vip_title, R.string.feature_theme_vip_desc),
                Feature(KEY_THEME_FREE, R.string.feature_theme_free_title, R.string.feature_theme_free_desc),
                Feature(KEY_THEME_TRIAL, R.string.feature_theme_trial_title, R.string.feature_theme_trial_desc),
                Feature(KEY_THEME_AD_OFF, R.string.feature_theme_ad_off_title, R.string.feature_theme_ad_off_desc),
            ),
            baselineVersion = "20.1.63.305",   // aapt2 实证（原 ThemeStoreHook 私有常量上提）
            extraVersions = setOf("20.1.65.302"),   // 2026-10-23 真机升级版：49 点全存活，仅 3 混淆类改名（ThemeStoreMap_20_1_65_302）
        ),
        Target(
            "com.android.packageinstaller", R.string.target_name_installer,
            listOf(
                Feature(KEY_INSTALLER_AD, R.string.feature_installer_ad_title, R.string.feature_installer_ad_desc),
                Feature(KEY_INSTALLER_NO_INTERCEPT, R.string.feature_installer_no_intercept_title, R.string.feature_installer_no_intercept_desc),
                Feature(KEY_INSTALLER_NO_SAFE_PROMPT, R.string.feature_installer_no_safe_prompt_title, R.string.feature_installer_no_safe_prompt_desc),
                Feature(KEY_INSTALLER_SKIP_CHECK, R.string.feature_installer_skip_check_title, R.string.feature_installer_skip_check_desc),
            ),
            baselineVersion = "14.0.37.301",  // aapt2 实证（原 InstallerHook 私有常量上提）
            extraVersions = setOf("14.0.22.326", "14.0.38.301"),
        ),
        Target(
            "com.android.settings", R.string.target_name_settings,
            listOf(
                Feature(KEY_SETTINGS_DISABLE_BTN, R.string.feature_settings_disable_btn_title, R.string.feature_settings_disable_btn_desc),
                Feature(KEY_SETTINGS_HIDE_ACCOUNT_DOT, R.string.feature_settings_hide_account_dot_title, R.string.feature_settings_hide_account_dot_desc),
            ),
            baselineVersion = "14.0.0.210",   // pm 实证（原 SettingsHook 私有常量上提）
        ),
        Target(
            "com.exam8.chengrenGK", R.string.target_name_exam8,
            listOf(
                Feature(KEY_EXAM8_SUPER_VIP, R.string.feature_exam8_super_vip_title, R.string.feature_exam8_super_vip_desc),
                Feature(KEY_EXAM8_BURN, R.string.feature_exam8_burn_reading_title, R.string.feature_exam8_burn_reading_desc),
            ),
            baselineVersion = "5.9.0.0-release",  // dumpsys 实证（原 Exam8Hook 私有常量上提）
        ),
        Target(
            "com.hihonor.android.launcher", R.string.target_name_launcher,
            listOf(
                Feature(KEY_LAUNCHER_HIDE_TITLE, R.string.feature_launcher_hide_card_title_title, R.string.feature_launcher_hide_card_title_desc),
                Feature(KEY_LAUNCHER_KEEP_FOLDER_OPEN, R.string.feature_launcher_keep_folder_open_title, R.string.feature_launcher_keep_folder_open_desc),
                Feature(
                    KEY_LAUNCHER_RECENTS_KEEP_ATMS, R.string.feature_launcher_recents_keep_atms_title,
                    R.string.feature_launcher_recents_keep_atms_desc, defaultEnabled = false,
                    excludesWith = KEY_LAUNCHER_RECENTS_KEEP_MANAGER,
                ),
                Feature(
                    KEY_LAUNCHER_RECENTS_KEEP_MANAGER, R.string.feature_launcher_recents_keep_manager_title,
                    R.string.feature_launcher_recents_keep_manager_desc, defaultEnabled = false,
                    excludesWith = KEY_LAUNCHER_RECENTS_KEEP_ATMS,
                ),
                // 壁纸三开关（2026-10-18 拆分）：宿主是 wallpapereditor/paradise 两个隐藏 Target，
                // 功能入口归到「系统桌面」卡——用户视角这是桌面能力，不是两个独立 app。
                Feature(KEY_WALLPAPER_TRANSCODE_QUALITY, R.string.feature_wallpaper_transcode_quality_title, R.string.feature_wallpaper_transcode_quality_desc),
                Feature(KEY_WALLPAPER_TIMELINE_UNLIMITED, R.string.feature_wallpaper_timeline_unlimited_title, R.string.feature_wallpaper_timeline_unlimited_desc),
                Feature(KEY_WALLPAPER_LOOP, R.string.feature_wallpaper_loop_title, R.string.feature_wallpaper_loop_desc),
            ),
            baselineVersion = "18.0.0.234",  // dumpsys package 实证（原 LauncherHook 私有常量上提）
        ),
        Target(
            "com.android.systemui", R.string.target_name_systemui,
            listOf(
                Feature(KEY_SYSTEMUI_HIDE_SIM2, R.string.feature_systemui_hide_sim2_title, R.string.feature_systemui_hide_sim2_desc, defaultEnabled = false),
                Feature(KEY_SYSTEMUI_HIDE_5G, R.string.feature_systemui_hide_5g_title, R.string.feature_systemui_hide_5g_desc, defaultEnabled = false),
                Feature(KEY_SYSTEMUI_DUAL_SIGNAL, R.string.feature_systemui_dual_signal_title, R.string.feature_systemui_dual_signal_desc),
                Feature(KEY_SYSTEMUI_DUAL_SIGNAL_LABEL, R.string.feature_systemui_dual_signal_label_title, R.string.feature_systemui_dual_signal_label_desc),
                Feature(KEY_SYSTEMUI_ICON_WIFI, R.string.feature_systemui_icon_wifi_title, R.string.feature_systemui_icon_wifi_desc),
                Feature(KEY_SYSTEMUI_ICON_STATUS, R.string.feature_systemui_icon_status_title, R.string.feature_systemui_icon_status_desc),
                Feature(KEY_SYSTEMUI_HIDE_CHARGE_NOTIF, R.string.feature_systemui_hide_charge_notif_title, R.string.feature_systemui_hide_charge_notif_desc),
                Feature(KEY_SYSTEMUI_HIDE_HOTSPOT_NOTIF, R.string.feature_systemui_hide_hotspot_notif_title, R.string.feature_systemui_hide_hotspot_notif_desc),
                Feature(KEY_SYSTEMUI_HIDE_ADB_NOTIF, R.string.feature_systemui_hide_adb_notif_title, R.string.feature_systemui_hide_adb_notif_desc),
                Feature(KEY_SYSTEMUI_FLASHLIGHT_IGNORE_LOW_BATT, R.string.feature_systemui_flashlight_ignore_low_batt_title, R.string.feature_systemui_flashlight_ignore_low_batt_desc),
            ),
            baselineVersion = "17.0.0.1",    // dumpsys 实证 2026-10-10（原 SystemUiHook 私有常量上提）
        ),
        Target(
            "com.hihonor.ouc", R.string.target_name_ouc,
            listOf(
                Feature(KEY_OTA_BLOCK, R.string.feature_ota_block_title, R.string.feature_ota_block_desc),
            ),
            baselineVersion = "18.0.3.230",  // 2026-10-13 荣耀 OTA（真机日志 dumpsys 实证）；挂载点 11/11 全活（manifest 稳定组件名）
        ),
        Target(
            "com.hihonor.android.upgradeguide", R.string.target_name_upgradeguide,
            listOf(
                Feature(KEY_OTA_BLOCK, R.string.feature_ota_block_guide_title, R.string.feature_ota_block_guide_desc),
            ),
            baselineVersion = "16.0.0.005",  // aapt2 实证（HnUpgradeGuide.apk 拉自真机 2026-10-12）
        ),
        Target(
            "com.hihonor.mms", R.string.target_name_mms,
            listOf(
                Feature(KEY_MMS_AD_OFF, R.string.feature_mms_ad_off_title, R.string.feature_mms_ad_off_desc),
            ),
            baselineVersion = "19.0.9.306",  // dumpsys 实证 2026-10-19（设备 AAK-AN00；原 MmsHook 私有常量上提）；304→306 挂载点零漂移，adapt 双侧核对（宿主+插件 10.0.41.308）
        ),
        Target(
            "com.sohu.inputmethod.sogou.honor", R.string.target_name_ime,
            listOf(
                Feature(KEY_IME_CODE_FREE, R.string.feature_ime_code_free_title, R.string.feature_ime_code_free_desc),
            ),
            baselineVersion = "10.32.44.202608251503",  // pm dump 实证 versionName（设备 AAK-AN00 2026-10-12；原 ImeHook 私有常量上提）
        ),
        Target(
            "com.hihonor.android.totemweather", R.string.target_name_purify,
            listOf(
                Feature(KEY_ADS_BLOCK, R.string.feature_ads_block_title, R.string.feature_ads_block_desc),
                Feature(KEY_TRACK_BLOCK, R.string.feature_tracking_block_title, R.string.feature_tracking_block_desc),
            ),
            baselineVersion = "18.1.6.312",  // dumpsys 实证 2026-10-19（设备 AAK-AN00）；Tracking Ex 点在本版 SDK 无实现类属预期（a.d() return null），判据仍看 installed N/M
            iconKey = "shield",
        ),
        Target(
            "com.jingdong.app.mall", R.string.target_name_jd,
            listOf(
                Feature(KEY_JD_SPLASH, R.string.feature_jd_splash_title, R.string.feature_jd_splash_desc),
            ),
            baselineVersion = "16.1.0",  // dumpsys 实证 2026-10-12（dex 级锚点核对见 docs/reverse/jd.md §二·2）
        ),
        Target(
            "com.aimazhishi.mfa.authenticator", R.string.target_name_mfa,
            listOf(
                Feature(KEY_MFA_SPLASH, R.string.feature_mfa_splash_title, R.string.feature_mfa_splash_desc),
            ),
            baselineVersion = "1.0.5",  // dumpsys 实证 2026-10-12（docs/reverse/mfa-authenticator.md）
        ),
        Target(
            "com.hihonor.appmarket", R.string.target_name_appmarket,
            listOf(
                Feature(KEY_MARKET_PURIFY, R.string.feature_market_purify_title, R.string.feature_market_purify_desc),
            ),
            baselineVersion = "16.1.9.302",  // aapt2 实证（2026-10-25 真机拉取，docs/reverse/appmarket.md）
        ),
        Target(
            "com.hihonor.systemmanager", R.string.target_name_sysmgr,
            listOf(
                Feature(KEY_SYSMGR_ANTIVIRUS, R.string.feature_sysmgr_antivirus_title, R.string.feature_sysmgr_antivirus_desc),
            ),
            baselineVersion = "17.0.0.414",  // jadx 反编译产物实证（sysmgrsvc-jadx，2026-10-13）
        ),
        Target(
            "com.xingin.xhs", R.string.target_name_xhs,
            listOf(
                Feature(KEY_XHS_SAVE_UNLOCK, R.string.feature_xhs_save_unlock_title, R.string.feature_xhs_save_unlock_desc),
                Feature(KEY_XHS_TAKEOVER_SAVE, R.string.feature_xhs_takeover_save_title, R.string.feature_xhs_takeover_save_desc),
            ),
            baselineVersion = "9.50.0",  // dumpsys 实证 2026-10-19（设备已升）；X1/X4 零漂移，X2b/X3/X5 重选点（docs/reverse/xhs.md §八）
            extraVersions = setOf("9.49.1", "9.51.0"),  // 9.51.0 = 2026-10-23 真机升级版（XhsPoints 9.51 套；albumCopy 落盘闸未定位诚实跳过）
        ),
        // 壁纸两宿主：功能入口已归并到「系统桌面」卡（见 launcher Target），此处留壳供
        // scope 页与 baselineVersionOf 查表——hidden 且 features 为空，UI 不出卡。
        Target(
            "com.hihonor.wallpapereditor", R.string.target_name_wallpapereditor,
            listOf(),
            baselineVersion = "153.0.0.423",  // dumpsys 实证 2026-10-16（docs/reverse/wallpaper-editor.md §一）
            hidden = true,
        ),
        Target(
            "com.hihonor.livewallpaper.paradise", R.string.target_name_livewallpaper,
            listOf(),
            baselineVersion = "11.0.0.252",  // dumpsys 实证 2026-10-16（docs/reverse/wallpaper-editor.md §一）
            hidden = true,
        ),
    )

    /** 查表：宿主挂载基线版本（未登记返回 null = 该目标版本自检静默关闭）。 */
    fun baselineVersionOf(packageName: String): String? = targets.firstOrNull { it.packageName == packageName }?.baselineVersion

    /** 查表：基线之外额外适配的精确套版本（未登记返回空集）。 */
    fun extraVersionsOf(packageName: String): Set<String> = targets.firstOrNull { it.packageName == packageName }?.extraVersions ?: emptySet()
}
