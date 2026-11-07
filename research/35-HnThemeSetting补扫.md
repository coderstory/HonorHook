# HnThemeSetting 补扫（2026-10-24，15MB/4dex）

res/xml 零 preference 键（主题/显示偏好全在代码构建），新增全来自 dex strings 门控反查。

## 新增开关（32 号差集）
- **hn_memc_switch（MEMC 插帧，关联 PWDualMEMC）**★
- tint_mode（色彩/色温模式，配套新 prop msc.config.tint）
- themed_icon_enabled（图标跟随主题）
- local_sms_outchat_ring/sent_ring_switch（短信提示音）
- hn_tme_new_rule_switch（TME 铃声规则，HnThemeSetting 独有）
- launcher_transparency_mode、new_simple_mode、single_hand_switch、tab_mode、haptic_feedback_enabled、aod_switch（代码内构建键）

## 新 prop/门
msc.config.tint（色温页能力）、msc.config.custom_wallpaper、**msc.keyguard.liquid（液态锁屏）**、msc.aod.aod_support_feature、ro.config.color_aod_type、msc.launcher.magic_cube_enable、persist.deep.theme_ 前缀（运行时拼接）、ro.config.diff_themes/ota_update_theme/custom_font/lockscreen_lensflare（主题能力旗标）
中间产物 .tmp_scan/{keys3,dexstrings2,props2}。
