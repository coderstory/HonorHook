# 包管理器 `com.android.packageinstaller` — 逆向分析与实现

> 本文件 = 该 app 的逆向分析记录（云控机制摸底 + `InstallerHook` 落地）。索引见 [`README.md`](README.md)。

> 对象：`com.android.packageinstaller`（AOSP 骨架）+ `com.hihonor.packageinstaller`（荣耀定制），
> 版本 `14.0.37.301`
> 来源：`system-files/installer-jadx/sources`（jadx 反编译，8,422 个 java）、
> 页面文案取自 `aapt2 dump strings`
> 结论一句话：**安装器没有主题商店那种布尔开关表**，它的云控＝「CommonConfig 键值 JSON」+「AB 实验」，
> 且广告是**内容下发型**（服务端返回 `AdApp` 渲染），不是开关型。

---

## 一、机制：与主题商店的差异

| | 主题商店 | 包管理器（安装器） |
|---|---|---|
| 存储 | SP 布尔表，`u15.getBoolean(key, default)` | SP 存 **JSON 串**，`ru1.g("common_config_sp_key")` |
| 读法 | `AgreeRepo` 约 30 个布尔方法 | `CommonConfigManager` 解析 `configVO`（`Map<String,String>`，值为 JSON） |
| 下发 | AMS systemParam（前缀 `10001000`） | `CommonConfigRequestCall` + **AB 实验** `AbExpStrategyRequestCall` |
| 后端 | hihonorcloud.com | `ads-drcn` / `metrics-ad-drcn` / `gamecenter-api-drcn` / `appmarket-lib-...myqcloud.com`（h5）等 |

安装器官方说明（`aapt2 dump strings` 原文，CL_1007040）：
> The package installer is a basic service … **When connected to the Internet, the service will obtain the
> cloud security control policies and recommend hot apps** …

——「云端安全管控策略」+「推荐热门应用」就是它的云控面。

---

## 二、CommonConfig 的 key（`CommonConfigManager` 实证）

管理器：`hncust/commonconfig/CommonConfigManager.java`；SP 键 `common_config_sp_key`、
请求间隔 `common_config_req_interval_key`。

| key | 值形态 | 默认 | 作用 | 读点 |
|---|---|---|---|---|
| `MainPageConfig` | JSON `{installLimit, installedTimeLimit}` | `installLimit=50` | **广告展示门槛**：装机 app 数 / 已安装时长达到阈值才展示广告 | `:78` `:95` |
| `SafeModeConfig` | JSON `{easyModeDefaultOpen, superProtectModeDefaultOpen}` | "" | **安全模式默认开关**（简易模式 / 超级保护模式是否默认开） | `:147` |
| `tencent_game_detection` | JSON `{timeout}` | 超时 `ERR_MODE_KID` | 腾讯游戏检测超时 | `:166` |
| `third_party_installation_exception_jump_interception_list` | `{packageNameList:[...]}` | 无 | **三方安装异常跳转拦截名单**（支持 `"ALL"` 通配） | `:192` |

`SafeModeConfig` 字段（`entity/resp/SafeModeConfig.java`）：
`easyModeDefaultOpen`、`superProtectModeDefaultOpen`（`int`，0/1）。

---

## 三、AB 实验场景（`AbSceneResult.BUSINESS_CODE_*`）

| 业务码 | 作用 |
|---|---|
| `installer_app_release_group` | **灰度分组** |
| `mixed_layout_ad_integration` | **混合布局广告接入** |
| `download_ad_copywriting` | 下载广告文案 |
| `ab_ass_name_high_light` / `ad_button_color` / `ad_button_size` | 广告/助手名高亮、按钮颜色、按钮尺寸 |
| `cancel_installpage_style` / `card_rounded_corners` / `os11_style_button` / `os11_style_margin` | 安装页样式 |
| `guide_button_style` / `more_button` / `normal_below_button` | 按钮位样式 |
| `mini_game_installation_button` | 小游戏安装按钮 |
| `parallel_space_jump` / `jump_back_up_state` / `jump_back_down_state` | 平行空间跳转、返回态 |
| `safemode_visualenhancement_type` | 安全模式视觉增强 |
| `category_label` / `new_installation_power_ex` | 分类标签、安装额外能力 |

结构：`AbExpResp{abPolicyIds, abSceneResultList, trackingParameter}`；
`AbExpPolicy` 常量 = `style/type/button/copywriting/jump/free/moderate/optimize/risk/show/nothing/if/method/…`

---

## 四、其他云控相关 key / 字段

- `webView_url_white_list_config` —— WebView 白名单（云端下发）
- `settingPageEntryRedPointDisplay` —— 设置页入口红点
- `user_close_ad_state` —— 用户关闭广告状态
- `riskInstallPopUp` / `safe_Scene` / `safemode_visualenhancement_type` / `safe_mode_shutdown_process` —— 风险弹窗与安全模式
- `CompatibilityVar`（`utils/CompatibilityVar.java`，Gson `@SerializedName`）—— `cpuAbi` / `cpuAbilist` /
  `bintranslatorEnable` / `hasHCore`，安装兼容性下发
- `AdReqV4` 请求模型含 `isRecommend` / `safeMode` / `kidMode` / `updateScene` / `honorMarketInstalled` /
  `adPolicyId` 等（广告请求画像）
- `AdApp` 响应模型（广告应用）—— `buttonText` / `deeplinkUrl` / `landingPageUrl` / `adId` / `impId` …
  **广告是这里渲染出来的**

---

## 五、能不能"关"？

**没有一键总开关**。与主题商店不同，安装器的云控粒度是「广告文案/按钮样式/分组」的 AB 试验 + 内容下发，
不存在 `isShowAd` 这类布尔门。可控/可关的点：

| 目标 | 思路 |
|---|---|
| 不展示安装页广告 | `MainPageConfig.installLimit` 置 0（门槛拉到永远不满足）；或从 `AdResp`/`AdApp` 渲染链拦截 |
| 三方安装异常跳转不拦截 | `third_party_installation_exception_jump_interception_list` 置空/不含目标 |
| 安全模式默认不开启 | `SafeModeConfig.easyModeDefaultOpen`/`superProtectModeDefaultOpen` → 0 |
| 不请求广告/AB | 拦 `AdReq`/`AbExpStrategyRequestCall` 请求层（面大，慎用） |

> 本模块（HonorHook）已于 **2026-10-10** 落地安装器目标 `InstallerHook`（五项「清净化」能力），
> 见下方 **§六**。主题商店的云控开关见 `docs/reverse/theme-store.md`。

---

## 六、落地实现：`InstallerHook`（2026-10-10）

目标包：`com.android.packageinstaller`（荣耀定制 **14.0.37.301**，`aapt2 dump badging` 实证）。
代码：`app/.../xposed/target/InstallerHook.kt` + 数据表 `app/.../xposed/InstallerConfigTable.kt`；
注册：`TargetRegistry.register(InstallerHook)`；作用域：`scope.list` 增 `com.android.packageinstaller`。

六项需求 → **11 个挂载点**（全部"源头优先"，无视图隐藏）：

| 需求 | 挂载点 | 类型 | 落地 |
|---|---|---|---|
| R4 不请求广告 | `lt1#b(String)`（`AdRequestConditionImpl.checkIsSupportAdRequest`） | CONST false | `wt1` 接口**唯一实现**；V4（`zv1.m`）/V3（`mt1.m`）都先问它 → 恒 false 走宿主自带 early-return（写错误码 + `vt1.d(code)` 回调），**不发请求且回调完整** |
| R1 不展示安装页广告 | `AdRespV4#getAdAppList()` | CONST 空表 | 广告响应**数据源**恒空 → 渲染链 `AbstractAdBusinessPresenter.v1()` 走宿主自带 `"ad is empty after filter"` 分支（带正常上报），不渲染 |
| R4 不请求 AB | `sx1#Q(boolean)`（`AbExpUtils.updateAbExpStrategy`） | **BLOCK** | 不发 `AbExpReq`；唯一调用点 `InstallStart:373` / `wb2:14` |
| R2 三方安装异常跳转不拦截 | `CommonConfigManager#j(String)` | CONST false | `isNewTaskStartInstallBySourcePkg` 恒不在拦截名单（含 `"ALL"` 通配） |
| R3 安全模式默认不开启 | `CommonConfigManager#g()` + `#h(CommonConfigResponse)` | CONST JSON | 恒返 `{"easyModeDefaultOpen":0,"superProtectModeDefaultOpen":0}`；`g()` 页面懒加载点 / `h()` 网络响应点（→ 写 Settings `uz1.d/e`） |
| R5 不提示开启安全模式 | `n12#f(int, SafeModeRise, RbiTempBean, boolean)` | CONST false | `isShowGuideOpenSafeModeDialog` 唯一展示门；`InstallSuccess` 两处调用（立即弹 `if(f(1,..)) r1()` → 落 `else finish()`；延后弹 `k0=f(2,..)` → `l0()` 不再弹） |
| R5 安装页安全模式提示卡 | `r12#j(CardTipsRecord, String, boolean, String)` | CONST false | `isSupportSafeModeCardShow` 恒 false ⇒ 提示卡整卡隐藏 |
| R5 安装页底部推广卡 | `r12#s(PackageInstallerActivity, RbiTempBean)` | **BLOCK** | `setSafeModeBottomSheetShow` 空实现阻断 ⇒ 底部推广 sheet 不展示 |
| R6 跳过联网安全检测 | `ly1#h(boolean, sy1)` | **CHECK_BYPASS** | 联网安全检测**唯一入口**（全工程唯一调用点 `PackageInstallerActivity:1847`）；构造器按 `isHn` 选 `py1`（荣耀云）/`qy1`（通用）两条管线，**在分发器层短路即两条一起跳过**：不调原方法（不发检测请求 ⇒ 无"当前网络不佳"横幅），复用宿主静态工厂 `ly1.a(isHn)` 造"通过"结果，主线程经 `sy1.b` 回吐 ⇒ 无风险判定 ⇒ 风险拦截页/「已了解」勾选框/指纹校验整条下游链不出现。准备失败**放行原方法**（降级为有检测，不死锁） |
| R6 安装指纹校验直通 | `qf2#g(Context, qf2$a)` | **FINGERPRINT_BYPASS** | 安装前指纹/密码校验唯一入口。回调 `c()`（清待决弹窗）+ `a(true)`（判定通过），不调原方法 ⇒ 校验弹窗不出现；检测短路后该链通常已不触发，此点为双保险 |

**为什么"不请求广告"选 `lt1.b` 而不是直接阻断 `zv1.m`/`mt1.m`**：`zt1.m` 是 `void` 且靠回调回传结果；
直接阻断会让 presenter 的 `vt1` 回调永不触发（可能挂起等待）。`lt1.b` 是宿主自己的"是否支持请求"判定，
恒 false ⇒ 宿主**主动**走"不支持"分支并回调 errorCode，语义完整、零副作用。

**为什么"不展示广告"选响应数据源而不用展示门**：`AbstractAdBusinessPresenter.b1()`（`isSupportShowAd`）
虽是展示总门，但其 false 分支会 `b()`（onAdShowFailed）+ `R0(...)` 清理/上报，强改会跳过这些副作用；
改 `getAdAppList()` 出口空表则走宿主**自带**的空广告分支，副作用全由宿主自理。

**DexKit 锚点**（混淆名目标；直连轨优先，锚点仅在直连失败时生效）：

| 目标 | 混淆情况 | 锚点 |
|---|---|---|
| `lt1#b` | 默认包 + 单字母方法 | `strings=["checkIsSupportAdRequest() personalizedRecommendationSwitchOpen = false"]` + `paramTypes=[String]` |
| `sx1#Q` | 默认包 + 单字母方法 | `strings=["updateAbExpStrategy() network close"]` + `paramTypes=[boolean]` |
| `n12#f` | 默认包 + 单字母方法 | `strings=["isShowGuideOpenSafeModeDialog input params not available. triggerType = "]` + `paramTypes=[int, SafeModeRise, RbiTempBean, boolean]` |
| `CommonConfigManager#j` | 类名可读、方法名混淆 | `strings=["isNewTaskStartInstallBySourcePkg"]` |
| `CommonConfigManager#g` | 同上（方法体无字符串） | `classStrings=["CommonConfigManager"]` |
| `CommonConfigManager#h` | 同上 | `strings=["initGetSafeModeConfig"]` + `paramTypes=[CommonConfigResponse]` |
| `r12#j` | 默认包 + 单字母方法 | `strings=["isSupportSafeModeCardShow() pageIndex="]` + `paramTypes=[CardTipsRecord, String, boolean, String]` |
| `r12#s` | 默认包 + 单字母方法 | `strings=["setSafeModeBottomSheetShow() first return"]` + `paramTypes=[PackageInstallerActivity, RbiTempBean]` |
| `qf2#g` | 默认包 + 单字母方法 | `classStrings=["PwdControlUtil"]` + `paramTypes=[android.content.Context, qf2$a]` |
| `ly1#h` | 默认包 + 单字母方法 | **类内零字符串常量**，仅 `paramTypes=[boolean, sy1]` 弱锚点 —— OTA 重混淆时诚实 `NOT_RESOLVED`，由汇总行暴露 |
| `AdRespV4#getAdAppList` | 类名可读、方法名可读 | 无（直连轨足够） |

**真机判据（R1–R5 已实测 `installed 11/11`；R6 两项待验证——加入时设备已断开）**：
`InstallerHook: installed 11/11`（无失败清单）＋ 首 2 次命中各打一行
`installer-hook 生效: <id> — <desc>`；UI 复核：安装确认页/安装中页/安装完成页 无广告卡片、
不弹"开启安全模式"引导弹窗、无"当前网络不佳"横幅、无风险拦截页、无「已了解」勾选框、无指纹弹窗，
安装成功页「完成」正常关闭。

**R6 的残余风险（未实测，如实记录）**：`CHECK_BYPASS` 是否走短路是**同步**判定的，而真正回吐"通过"结果的
`Handler.post` 是异步的 —— 若那段回调抛异常，宿主会等不到检测结果。代码里只打 WARN，可能出现"安装流卡住"
的表现。真机验证时若遇到安装无响应，优先查这条（日志特征：`安全检测通过结果回调异常`）。
