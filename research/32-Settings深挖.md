# Settings 应用深挖（2026-10-24，v14.0.0.210，155MB 单体 + HnThemeSetting split）

（本文件合并两棒交卷：32 号主体 + 第一棒 cb9eb224 迟到增补）
全景：540 处 Switch 元素/302 唯一 key/2830 总条目（496 屏）；搜索索引=SettingsSearchIndexablesProvider 运行时构建（索引含隐藏项，搜索可见列表不可见=被门控项，可搜索直达验证）；FeatureFlag 面=feature_flags_dashboard+FeatureFlagPreference 实证。

## 未曝光开关（与 01/11 差集，27 个）
**快可落地（system DB 直写候选，低风险）**：
- extremely_dark_mode_all_day_switch/_schedule_switch（极暗模式全天/定时）
- hibernation_switch（应用休眠，app_hibernation_enabled）
- otg_switch（OTG 反向供电，otg_enabled）
- sunlight_mode_switch（阳光屏，先核 KEY_SUNLIGHT_MODE_SUPPORT）

**显示/护眼**：oasis_lake/oasis_tianshan_characteristic_switch（绿洲护眼特征色，hn_tianshan 姊妹）、hn_natural_tone_display_switch、care_display_switch（呵护显示）
**开发项（development_prefs.xml）**：185hz_high_refresh_rate（isSupport185Hz 门!）、show_crs_switch（CRS）、show_5g_switch（5G UI 键=11 号 prop 的 UI 面）、video_ring_switch（视频铃声）
**功能**：immersive_mode_switch（沉浸模式）、beidou_navigation/hn_use_beidou_only（北斗/仅北斗，isOwnerUser 限定）、pgps_switch、wifi_bridge_switch、xpan_switch（LE Audio 高保真）、relieved_connection_switch（轻松连）、smart_cover_switch、follow_cam_switch、intelligent_awaken_switch、timing_boot/shutdown（定时开关机）、screensaver_switch（ro.feature.display_screensaver_lite 硬件门）、single_hand（msc.config.singlehand）、smart_network_switching/intelligent_wifi_switch、back_led_switch 全家桶（+always/time_slot/call/charge/clock/fans，"back_led_strip not supported"门）

## 增补（第一棒交卷增量，带中文语境）
**C 层·运营商城控门（改 prop 不可靠，SIM/渠道条件）**：call_encryption_switch（CMCC 定制屏独立 xml）、video_ring_switch（换卡自动复位）、wifi_display_certification、wifi_bridge_switch（区域性）
**B 层·ro.config build-prop 门（resetprop 会话层可试）**：otg_switch（hw_otgFeature）、hide_privacy_user_entry_switch（support_privacyspace）、timing_boot/shutdown、immersive_mode/open_assistant、single_hand、aod 族
**更多新开关（含标题）**：hibernation_switch（应用休眠/暂停，HibernationSwitchPreferenceController——高价值 hook 候选）、suspend_button_switch（悬浮 dock）、child_face_unlock（人脸→儿童模式）、face_anti_peeking（防偷窥人脸）、settings_fast_felx_switch（Fast-Flex 折叠分屏，原拼写）、dual_clocks（双时钟）、show_pedometer（锁屏计步）、bluetooth_anc/bt_noise_control/audio_sharing(Auracast)/xpan 族（耳机硬件门）、wifi_plus_smart(WLAN+)/intelligent_wifi、display_app_on_launcher（简易模式）、long_background_tasks（长后台白名单）、diagkit_enable/camera_laser_sensor/connectivity_monitor（开发项隐藏）、fp_onlylock_enable（仅锁屏指纹）、intelligent_awaken（智能亮屏）、smart_cover
置信度：msc 表高/ro.config 中高/运营商城控中（三角证据：字符串↔controller↔xml）。

## 通用总门（新发现，SettingsHook 候选）
- **msc.config.opta / msc.config.optb**：国家/区域能力总门（与 msc.sys.country 联动控制大量页面可见性）——比逐键更高层
- **msc.config.hide_\* 族**（4 处）：整页隐藏 prop
- 新 prop：msc.keychain_switch、wifi_wps_disable、remove_touch_mode、multiwindow_optimization、swing.face/eye_detect_show_aod_enabled（注视不息屏）、persist.settings.large_screen_opt(_for_dp).enabled
