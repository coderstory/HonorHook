# 荣耀天气 com.hihonor.android.totemweather 逆向记录

## 机型/设备门控审计（2026-11-19，初步）

**状态：初步结论。** 审计中途 MT MCP 服务挂死（tools/mtmcp.py 全部子命令超时于
`mt_apk_workspace_open`；MT 管理器重启后 8787 端口不再监听，需用户解锁手机进入 MT
重新开启 MCP），smali 级调用点核验未完成。以下仅为已完成的 dex 字符串/类名扫描实证，
调用链与判定中枢待补查。

### 实证（dex 字符串扫描，MT MCP strings/names）

- **APK 内不存在机型白名单字符串**：搜 `AAK`、`HNAAK`、`marketname` 均 0 命中。
  → 天气 app 没有按具体机型型号（如 AAK-AN00）硬编码的本地门控表。
  推测：若存在机型定向，走「运行时读系统属性 + 服务端下发」，不是本地字符串比对（推测，
  SystemProperties 调用点未核验）。
- `ro.product` 命中 10 类：`ams/PrivacyJsInterface`、`dr1`、`ig0`、`op`、
  `weathermagic/adapter/DailyHorizontalStyleAdapter`、
  `framework/network/grs/local/model/CountryCodeBean`、`hianalytics/hnha/n1` 等。
  → GRS（华为遗留框架）与埋点 SDK 均读设备属性。
- `deviceModel` 命中 `com/hihonor/framework/network/grs/GrsBaseInfo`
  → GRS 请求携带设备型号：**服务端机型定向的入口**（证据中）。
- `isSupport` 命中（受控功能面）：`totemweather/secondpage/viewmodel/SecondaryAdsManager`
  （二级页广告）、`push/PushUtilInstance`（推送）、`weathermagic/MagicWeatherFragment`、
  `widget/basewidget/BaseWidgetWeatherProvider`（小组件）、`ci0`、`u71`、
  `adsdk/base/promotion/trial/TrialGameManager`、`adsdk/.../SlideActionView`。
  → 二级页广告/推送/天气魔法/小组件存在 isSupport 型能力门控；具体判据（本地/服务端）未核验。

### 已覆盖 hook（xposed/target/，与本审计的关系）

- `ads/HonorAdsHook`：`com.hihonor.adsdk.base.widget.base.BaseAdView.notifyAllChildView`
  + `onAttachedToWindow` 收口，清空一切广告形态内容（渲染层短路，未混淆直连）。
- `tracking/HonorTrackingHook`：`com.hihonor.hianalytics` facade
  （`HiAnalyticsManager.getInstanceByTag` / `getInstanceEx` / `HiAnalyticsInstance$Builder.create`）
  拦上报方法白名单。源码注记 2026-11-02 天气判据 installed 2/3，天气内置新版 SDK 已删 Ex Builder。

### 本地判定 vs 服务端定向边界（基于上述实证的界定）

- **本地机型判定**：未发现（无硬编码机型串）。门控若有，判据是运行时
  Build/SystemProperties 值，hook 该值可影响行为——调用点未核验，属待查项。
- **服务端机型定向**：GRS 请求带 deviceModel（证据中）+ 广告 SDK 请求参数（推测）。
  这类定向**本地 hook 改不了服务端决策**，只能像现有 HonorAdsHook/HonorTrackingHook
  一样在渲染/上报两端收口。

### 待补查（下次 MT MCP 恢复后）

1. `outline GrsBaseInfo` / `smaliq "deviceModel"` → GRS 上报字段构造调用点。
2. `outline SecondaryAdsManager`、`outline TrialGameManager` → isSupport 判据来源。
3. `strings SystemProperties`、`smaliq "Landroid/os/SystemProperties;->get"` → 运行时机型读点。
4. 广告 SDK 请求参数里的机型字段（`adsdk` 包内 `model`/`deviceModel` 锚点）。
