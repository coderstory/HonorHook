# 主题商店云控开关全表

> 对象：`com.hihonor.android.thememanager`（主题商店 20.1.63.305）
> 来源：`system-files/theme-store-jadx/sources`（jadx 反编译，行号可核）
> 说明：本表把该 APK 的**云端可控开关**梳理分类。改动 hook 前先在这里查"有没有云控开关能直接关"——
> 关云控是**根源解**，优先于逐 UI 隐藏。

---

## 一、云控机制

| 环节 | 实现 |
|---|---|
| 下发 | AMS systemParam，key 前缀 **`10001000`**（`i.SUPPORT_ONLINE_KEY_PREFIX`），按"key 前缀 + key 名"请求（`i.buildParamsRequest(sign, new String[]{ "10001000", key })`） |
| 落地 | 写入本地 SP（`themename`），由 **`u15.getBoolean(key, default)`** 读（`u15` = `SharepreferenceUtils`，默认包混淆类） |
| key 常量 | `common/i.java`（主）、`common/c.java`（本地/杂项） |
| 读点收敛类 | **`ams/mgr/AgreeRepo.java`** —— 约 30 个 `public static boolean is*()`，是**事实上的开关总表** |
| 相关前缀 | `10001000` 系统参数 / `10001100` 优惠券（`SUPPORT_COUPON_PREFIX`）/ `10001200` 问卷（`SUPPORT_QUESTIONNAIRE_PREFIX`）/ `10001300` 挂件（`CONFIG_PENDANT_PREFIX`） |

全 APK 共 **83 处 `u15.getBoolean` 调用**（另有单参重载 `getBoolean(String)` 若干）；
其中 `AgreeRepo` 占大头，其余为散读。除 `u15` 外**没有任何代码直接读这批 key**
（其余 `getBoolean` 命中全部来自 `ads_mobile_sdk` 三方广告 SDK 与 `androidx`，与云控无关）——
因此 **`u15` 的 `getBoolean` 两个重载即云控布尔开关的唯一收口**（本项目据此做统一拦截，见 §四）。

---

## 二、AgreeRepo 开关总表（约 30 项）

### 2.1 内容模块支持（决定某类资源/功能是否上线）

| 方法 | 云控 key | 默认 | 控制 |
|---|---|---|---|
| `isSupportVip()` | `vip_support` | false | VIP 模块（VIP tab / 详情 VIP 区） |
| `isTrialSupportOnline()` | `trial_support_online` | **true** | 试用（离线/在线试用） |
| `isPayedSupportOnline()` | `payed_support_online` | false | 付费资源 |
| `isIconSupportOnline()` | `icon_support_online` | false | 图标 |
| `isAodSupportOnline()` | `aod_support_online` | **true** | 息屏显示（AOD） |
| `isLiveWallpaperSupportOnline()` | `live_wallpaper_support_online` | **true** | 动态壁纸 |
| `isCubeSupportOnline()` | `custom_cube_support` | **true** | 自定义立方体 |
| `isWidgetSupportOnline()` | `widget_support_online` | false | 桌面小组件 |
| `isSupportOnline()` | `country_support_online` | false | 国家/地区支持总开关 |
| `isHelpConsumerSupportOnline()` | `help_consumer_support_online` | **true** | 消费者帮助（非俄罗斯区） |
| `isMagicBabySupportOnline()` | `magic_baby_support_online` | false | 魔法宝贝（国行 + 精灵启用） |

### 2.2 变现 / 广告 / 会员（**与本项目最相关**）

| 方法 | 云控 key | 默认 | 控制 |
|---|---|---|---|
| `isOpenFreeClaimSwitch()` | `free_claim_show_value` | **true** | **「免费领」入口**（激励/美豆链路，`NewRankListFragment:485`） |
| `isMemeberCardSupport()` | `member_card_support` | false | **「福利中心 / 会员卡」入口**（`LocalMineFragment:2886`） |
| `isOpenMemberSelection()` | `MEMBER_SELECTION` | false | **「会员精选」**（`NewRankTabFragment:345` 等） |
| `isVipNoAd()` | `vip_no_ad` | false | **VIP 免广告** |
| `isAdSupportFromSettingOnline()` | `detail_ad_support_from_settings` | false | 详情页广告（设置来源） |
| `isAdSupportOfficialResourceOnline()` | `detail_ad_support_official_res` | **true** | 详情页广告（官方资源） |
| `isCloudSupportAIMulRecommend()` | `ai_mul_recommend_switch` | false | AI 多重推荐 |

### 2.3 入口 / 推荐 / 展示

| 方法 | 云控 key | 默认 | 控制 |
|---|---|---|---|
| `isOpenDetailHotRank()` | `hot_ranking_show_detail_config_value` | false | 详情页热榜 |
| `isShowTrialRecordEntry()` | `trial_record_entry_switch` | false | 试用记录入口 |
| `isShowAICreation()` | `AICREATION_SHOWORHIDE_CONFIG` | false | AI 创作入口 |
| `isSpatialPhotoEnable()` | `spatial_photo_enable_value` | false | 空间照片 |
| `isRedpointSupportOnline()` | `redpoint_support_online` | **true** | 红点提示 |
| `isFavoriteDisable()` | `themes_favorite_disable` | false | 禁用收藏 |

### 2.4 非云控（同表内但读本地/系统，非 AMS 下发）

| 方法 | 来源 |
|---|---|
| `isAMGroup()` | 系统属性 `msc.sys.vendor/country` |
| `isLiveDeskSupport()` | `CommonEngine.isMocoolEngineSupported` |
| `isSupportCharging/Fingerprint/Gestures/Notification/StatusBar/NewControl()` | `ThemeAssetsProviderHelper.checkSupportModule(...)` 能力探测 |
| `isNeedStopNet()` | 本地 `local_need_stop_net` |
| `isLocalSigned()` | 本地 `LOCAL_HAS_SIGNED` |
| `readAnalysisImprovementSwitchStatus()` | 本地 `analysisImprovementSwitchStatus`（默认 true） |
| `readExpandBusinessSwitchStatus()` | 本地 `expandBusinessSwitchStatus`（默认 true） |

---

## 三、AgreeRepo 之外的云控读点（散读）

这些 key 定义在 `common/i.java` / `common/c.java`，代码里直接 `u15.getBoolean`：

| key | 常量 | 读点 | 默认 | 控制 |
|---|---|---|---|---|
| **`ader_show_global_switch`** | `i.SUPPORT_AD_DISPLAY` | `ads/AdStrategy.java:634` | false | **广告全局开关** |
| `download_datails_display` | `i.DOWN_DATAILS_DISPLAY` | `detail/DetailContainerView.java:258` | true | 下载详情展示 |
| `deeplink_white_list_switch` | `i.DEEPLINK_WHITE_LIST_SWITCH` | `logic/link/LinkSecurityHelper.java:96` | true | 深链白名单 |
| `show_feed_back_dialog` | `i.SHOW_FEED_BACK_DIALOG` | `logic/recommend/utils/FeedBackUtil.java:47` | false | 反馈弹窗 |
| `theme_provider_switch_support` | `i.THEME_PROVIDER_SWITCH_SUPPORT` | `theme/apply/BaseManager.java:797` | true | ThemeProvider 路径 |
| `get_lock_screen_guide` | `i.GET_LOCK_SCREEN_GUIDE_CONFIG` | `theme/guide/LockScreenGuideManager.java:285` | false | 锁屏引导 |
| `support_mirror_end_unlock` | `i.SUPPORT_MIRROR_END_UNLOCK` | `theme/superwallpaper/SuperWallpaperUtils.java:251` | true | 镜像解锁超级壁纸 |
| `NOT_SHOW_THEME_GIF` | `c.NOT_SHOW_THEME_GIF_KEY` | `common/j.java`、`common/ThemeInfo.java`（多处） | true | 主题 GIF 预览 |
| `push_switch_status_key` | `c.PUSH_SWITCH_AGREE` | `push/PushManager.java:737` | 地区相关 | 推送开关 |
| `update_status` | `c.UPDATE_STATUS` | `mvvm/settings/SettingsModel.java:2241` | false | 更新入口 |

`i.java` 里其余云控 key（按 key 名可推语义，未逐一追读点）：
`bottom_tab_config`（底部 tab 配置）、`hot_ranking_show_config(_value)`、`new_rank_list`、`RANK_LIST`、
`RANK_CATEGORY_MERGE`、`MERGE_SORT_POSITION(_value)`、`night_mode_change`、`recommend_popup_remaind_interval`、
`resource_refresh_value`、`sprite_apply_notice_value`、`vip_reminder` / `vip_expired_reminder` /
`vip_remind_frequency` / `vip_remind_tabs` / `vip_trial_arrived_pop_value` / `vip_trial_arrived_time_out_value`
（**VIP 挽留/提醒链，与 §三 挽留弹窗相关**）、`ringtone_support_online`、`theme_card_update_interval`、
`themes_children_protection`、`cookie_white_list`、`pendant_display_white_list`、`forbid_icon_custom_list_value`、
`fling_velocity_factor_value`、`gather_interval_value`、`nfc_ad_block_duration_value`、`net_cache_duration`、
`coupon_support` / `redeem_code`、`THEME_AI_PARAM`、`theme_support_preview_icons/values`、`support_super_unlock`、
`OVERSEA_AD_RULE_CONFIG`。

---

## 四、本项目当前接管的云控开关（**表驱动 · 统一读点**）

实现方式：**不逐个挂 `AgreeRepo` 方法**，而是挂在唯一收口 `u15.getBoolean(String, boolean)`
与 `u15.getBoolean(String)` 上，按第 1 参 key 查表决定是否短路（命中返回强制值，未命中放行原逻辑）。

- 数据表（**唯一改动点**）：`app/.../xposed/CloudSwitchTable.kt`
- 拦截逻辑（通用，不含 key）：`ThemeStoreHook.collectCloudSwitchHooks` + `MountKind.KEY_OVERRIDE`
- 新增/删除开关**只改 `CloudSwitchTable.kt`**，不动 hook 代码；版本升级新增的读点也自动覆盖。

| 云控 key | 强制值 | 作用 / 读点 |
|---|---|---|
| `ader_show_global_switch` | **false** | 广告全局总闸（`ads/AdStrategy.isSupportAdDisplay:634`） |
| `free_claim_show_value` | **false** | 「免费领」美豆广告入口（`AgreeRepo.isOpenFreeClaimSwitch`） |
| `member_card_support` | **false** | 会员卡 / 福利中心入口（`AgreeRepo.isMemeberCardSupport`） |
| `MEMBER_SELECTION` | **false** | 「会员精选」（`AgreeRepo.isOpenMemberSelection`） |
| `detail_ad_support_from_settings` | **false** | 详情页广告·设置来源（`AgreeRepo:203`） |
| `detail_ad_support_official_res` | **false** | 详情页广告·官方资源（`AgreeRepo:207`） |
| `hot_ranking_show_detail_config_value` | **false** | 热榜详情（`AgreeRepo:272`） |
| `trial_record_entry_switch` | **false** | 试用记录入口（`AgreeRepo:298`） |
| `AICREATION_SHOWORHIDE_CONFIG` | **false** | AI 创作入口（`AgreeRepo:294`） |
| `redpoint_support_online` | **false** | 红点角标（`AgreeRepo:288`） |
| `magic_baby_support_online` | **false** | 魔法宝宝（`AgreeRepo:255`） |
| `help_consumer_support_online` | **false** | 帮助与客服入口（`AgreeRepo:227`） |
| `vip_no_ad` | **true** | VIP 免广告（`AgreeRepo.isVipNoAd:342`） |
| `local_has_signed` | **true** | 本地已登录标记（`AgreeRepo.isLocalSigned:243`，走**单参**重载） |
| `trial_support_online` | **true** | 试用能力在线开关（原 C8 方法级 hook 迁入表，值不变） |

**尚未接管、但值得关注的**：
- `vip_expired_reminder` / `vip_reminder` / `vip_trial_arrived_pop_value` 等 VIP 提醒链 ——
  与"2088 仍显示已过期 / 挽留弹窗"现象可能同源，需要时**加进 `CloudSwitchTable` 一行即可**；
- `vip_support` —— 当前**不动**（我们要的是 VIP 态，不是关 VIP 模块）。

> 相关：美豆/福利中心链路的完整分析见 `docs/theme-store-hook-design.md §九`。
> 包管理器（软件包安装程序）的云控机制不同（CommonConfig + AB 实验，无布尔表），见 `docs/installer-cloud-config.md`。
