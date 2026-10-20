# 包管理器 `com.android.packageinstaller` — 逆向分析与实现

> 本文件 = 该 app 的逆向分析记录（云控机制摸底 + `InstallerHook` 落地）。索引见 [`README.md`](README.md)。

> 对象：`com.android.packageinstaller`（AOSP 骨架）+ `com.hihonor.packageinstaller`（荣耀定制），
> 版本 `14.0.37.301`
> 来源：`system-files/installer-jadx/sources`（jadx 反编译，8,422 个 java）、
> 页面文案取自 `aapt2 dump strings`
> 结论一句话：**安装器没有主题商店那种布尔开关表**，它的云控＝「CommonConfig 键值 JSON」+「AB 实验」，
> 且广告是**内容下发型**（服务端返回 `AdApp` 渲染），不是开关型。

> **版本指针（2026-10-20）**：§一~§六 的逐点定位均针对基线 **14.0.37.301**，现已失效，但**原样保留为历史证据**
> （还原 37 版行为时仍以它为准）。现行版本 **14.0.38.301** 的 11 点重定位、检测链重构与判据见文末
> **「14.0.38.301 重定位（2026-10-20）」**——两版之间类名/方法名**整体漂移**，改 hook 前先看那一节，
> 按本节旧类名搜代码会搜到完全无关的类（见文末 §1 的旧名复用表）。
> 两版反编译产物并列：`system-files/installer-jadx/`（37）、`system-files/installer-38-jadx/`（38）。
>
> **现行判据（2026-10-20 16:33 真机）**：`InstallerHook: installed 11/11`（11 点全直连命中，无失败清单）——
> **挂载层已达成；行为层 2026-10-20 由用户按 §六 末清单真机实测通过**（安装流程正常：安装页无广告卡片 /
> 无联网安全检测页 / 指纹直过 / 无安全模式引导）。

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

> 本节 11 点、锚点表、真机判据全部是 **14.0.37.301** 的记录，作为历史证据保留；
> 14.0.38.301 已整体重定位（类名/方法名全漂），**当前宿主请看文末「14.0.38.301 重定位」**。

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

---

## 14.0.38.301 重定位（2026-10-20）

> 证据来源：`system-files/installer-38-jadx/sources/`（jadx，与 §一~§六 用的 `installer-jadx/` 平行）。
> 代码侧：`app/app/src/main/kotlin/top/honorhook/xposed/target/InstallerMap_14_0_38_301.kt`（新表 11 点）
> + `InstallerHook.kt`（新增 `MountKind.CHECK_BYPASS_JOB`，`FINGERPRINT_BYPASS` 改为按签名找 0 参 void 方法）；
> 版本注册：`FeatureCatalog.extraVersions = {"14.0.22.326", "14.0.38.301"}`，`InstallerHook.installerTables`
> 按版本挑表，基线 14.0.37.301 仍走源码路径（`buildSourcePending()`）。
>
> 本节只写**与基线的差异**。§二（CommonConfig key）、§三（AB 业务码）、§四（广告内容下发）在两版之间
> 没有实质变化，不再重复；§六 的「为什么选源头而不是展示门」等论证对 38 同样成立，只是类名换了。

### 1. 这版的漂移形态：短名整体重排 + 旧名被复用

不是「几个方法名小改」，而是 R8 把默认包的短名**整体重排**。11 个挂载点里，**类名和方法名都没变的只有 1 个**：

- **唯一未漂移**：`com.hihonor.packageinstaller.entity.resp.AdRespV4#getAdAppList()`（具名类 + 具名方法，
  服务端下发模型不会被混淆）；
- **只漂方法名**：`CommonConfigManager`（类名可读，方法 `a..e` 单字母）—— `h→c`、`j→e`，`g()` 被内联掉；
- **整体换类**：其余 6 个混淆目标（`lt1`/`sx1`/`n12`/`r12`/`ly1`/`qf2`）全部搬到了别的类。这 6 个旧短名
  在 38 里**依然存在**，但角色完全不同——R8 把短名当可回收资源重新分配：

| 旧名（37 里是什么） | 38 里这个短名现在是什么（jadx 实证） | 对旧 hook 的后果 |
|---|---|---|
| `lt1` = 37 的 `AdRequestConditionImpl` | 巨型静态工具类（`lt1.L/h/g/D0` 等，含 `PwdControlUtil` 日志） | 旧点 `lt1#b(String)` 不存在；新指纹点恰好落在 `lt1#w0` |
| `sx1` = 37 的 `AbExpUtils` | `implements Runnable` 的 bottom-sheet 动画闭包（持有 `HnBottomSheet`） | 按名直连必失败 |
| `n12` = 37 的 `InstalledGuideDialogHelper` | `extends m12` 的 bottom-sheet 实现（字段 `x/y/z/A`，`f()` 是 0 参 void） | 同名 `f` 在，签名不符 ⇒ 不匹配 |
| `r12` = 37 的 `SafeModeShowUtils` | 广告 SDK 的 bottom-sheet **接口**（`a()Z`/`d(Z)`/`reset()`…） | 不再是可挂的静态工具类 |
| `ly1` = 37 的检测分发器 | `interface ly1 { void a(View, ky1, ky1) }` | 检测分发器整体消失（改为作业对象，见 §3） |
| `qf2` = 37 的 `PwdControlUtil` | `implements jf2.a` 的 `HwViewPager` 回调 | 指纹点搬到 `lt1#w0` |

> 这解释了基线 hook 在新宿主上「只挂上 1/11」：唯一能走**直连轨**命中的只有 `AdRespV4.getAdAppList`
> （具名类 + 具名方法），其余 10 点的旧类名/旧签名在 38 里都不成立（能否被 DexKit 兜底救回，取决于锚点在
> 新类里是否仍唯一——这不改变结论）。**跨版本只信内容锚点，不能信短名**——短名在 R8 眼里是可回收资源。

### 2. 11 个挂载点：旧定位 → 新定位 → 锚点 → 语义

与 `InstallerMap_14_0_38_301.kt` 逐条一致（行号 = 当前 jadx 产物真实行号）。
下表中 `Z` = `boolean`、`Rsp` = `CommonConfigResponse`；**第 6 行是删点**（该类内已无对应方法），故有效挂载点 = 12 行 − 1。

| # | 需求 / 挂载点 id | 旧定位（37） | 新定位（38） | 内容锚点（38） | 语义是否变化 |
|---|---|---|---|---|---|
| 1 | R4 不请求广告<br>`ad-request:AdCondition.checkIsSupportAdRequest` | `lt1#b(String)Z` | `p81#a(String)Z`（`p81.java:18`） | `checkIsSupportAdRequest() personalizedRecommendationSwitchOpen = false`（`:25`）+ `paramTypes=[String]` | **不变**。`p81` 就是 `AdRequestConditionImpl`（日志 TAG）；37 的 `wt1` 接口没了，V4 改为 `pa1.c(o81, new p81(), w81)`（`pa1.java:111`）、V3 在 `AbstractAdBusinessPresenter#g`（日志里叫 `requestAdData()`）里 `new p81()`（`:1654`）。两处都是 `if (!p81.a(pkg))` → 取 `p81.b` 错误码 → `s81.d(errorCode, i)` 后 return（`pa1.java:145-150`、`AbstractAdBusinessPresenter.java:1686-1691`），**与 37 的 early-return 同构** |
| 2 | R1 不展示安装页广告<br>`ad-source:AdRespV4.getAdAppList` | `AdRespV4#getAdAppList()` | **同左（未漂移）**（`AdRespV4.java:49`） | 无（直连轨足够） | **不变**。恒空表 ⇒ `showAdInfoDetailV4` 的 `getAdAppList()==null` 判定跳过（`AbstractAdBusinessPresenter.java:2104`），落到宿主自带的空广告分支 `D("1100", "ad is empty after filter")`（`:2111-2112`，带正常上报）。注意必须返**空表而非 null**，null 会走另一条 `"cloud data returned null"` 分支 |
| 3 | R4 不请求 AB<br>`ab-request:AbExpUtils.updateAbExpStrategy` | `sx1#Q(Z)V` | `kc1#u(Z)V`（`kc1.java:296`） | `updateAbExpStrategy() network close`（`:302`）+ `paramTypes=[boolean]` | **不变**（TAG `AbExpUtils` 未变，`ab_exp_strategy_data_key` 旧值继续沿用）。调用点变了：`InstallStart:528` `u(true)`；`DataUpdateJobService:268/285` `u(false)`（37 记的 `wb2:14` 已不存在） |
| 4 | R2 三方异常跳转不拦截<br>`installer:CommonConfig.isNewTaskStartInstallBySourcePkg` | `CommonConfigManager#j(String)Z` | `#e(String)Z`（`CommonConfigManager.java:174`） | `isNewTaskStartInstallBySourcePkg`（`:180/186/193`）+ `[String]` | **不变**：同名日志、同 key `third_party_installation_exception_jump_interception_list`、同 `"ALL"` 通配（`:189-193`） |
| 5 | R3 安全模式默认关（网络响应点）<br>`installer:CommonConfig.getSafeModeConfigJsonFromRsp` | `#h(Rsp)String` | `#c(Rsp)String`（`:129`） | `initGetSafeModeConfig`（`:134/141/144/147`）+ `[CommonConfigResponse]` | **不变**：仍返 `{"easyModeDefaultOpen":0,"superProtectModeDefaultOpen":0}` |
| 6 | R3 安全模式默认关（页面懒加载点）<br>`installer:CommonConfig.getSafeModeConfigJson` | `#g()String` | **已消失 → 删点** | — | `c(CommonConfigResponse)` 的 `rsp == null` 分支（`:130-132`）**就是**旧 `g()`：读 `common_config_sp_key` 后取 `SafeModeConfig`。所以新类只剩 `a()`/`b(Z)`/`c(Rsp)`/`d()`/`e(String)` 五个静态方法，**唯一的 String 返回点就是 `c()`**，一点覆盖旧 `g()`+`h()` 两个点 |
| 7 | R5 不弹"开启安全模式"引导<br>`installer:SafeModeDialogGate.isShowGuideOpenSafeModeDialog` | `n12#f(int, SafeModeRise, RbiTempBean, Z)Z` | `lc1#A1(int, SafeModeRise, RbiTempBean, Z)Z`（`lc1.java:177`） | `isShowGuideOpenSafeModeDialog input params not available. triggerType = `（`:181`）+ 四参序列 | **不变**。方法体整体搬进 `lc1`，日志 TAG `InstalledGuideDialogHelper` 保留，4 个参数的类型/顺序逐字一致 |
| 8 | R5 安装页提示卡<br>`installer:SafeModeCard.isSupportSafeModeCardShow` | `r12#j(CardTipsRecord, String, Z, String)Z` | `lc1#D1(CardTipsRecord, String, Z, String)Z`（`:533`） | `isSupportSafeModeCardShow() pageIndex=`（`:535`）+ 四参序列 | **不变**。TAG `SafeModeShowUtils` 保留 |
| 9 | R5 底部推广卡<br>`installer:SafeModeCard.setSafeModeBottomSheetShow` | `r12#s(Activity, RbiTempBean)V` | `PackageInstallerActivity#B0()V`（`:924`） | `setSafeModeBottomSheetShow() first return`（`:940`） | **位置变化**：逻辑从外部工具类**内联进 Activity**（方法体自带 `notFirstShowBottom()` + `SafeModeShowUtils` 两套日志），参数 2→0。空实现阻断效果不变：门槛判断、弹窗计数/时间戳、弹 `SafeModeBottomSheetActivity` 全部跳过。调用点 `:6132`、`:6185` |
| 10 | R6 联网安全检测·荣耀云管线<br>`installer:SecurityCheck.bypass` | `ly1#h(boolean, sy1)`（单分发器，37 一条点） | `ed1#b(kd1, Z)V`（`ed1.java:271`，TAG `HnAppCheckJob`） | `startCheck()`（`:272`）+ `[kd1, boolean]` | **重构**：分发器变成作业对象 + 管线接口，见 §3。两条管线各算一个挂载点（运行时只有一条会被调用） |
| 11 | R6 联网安全检测·通用管线<br>`installer:SecurityCheck.bypass.generic` | 同上（被分发器一并覆盖） | `id1#b(kd1, Z)V`（`id1.java:165`，TAG `HwAppCheckJob`） | `startCheck()`（`:166`）+ `[kd1, boolean]` | 同上 |
| 12 | R6 指纹校验直通<br>`installer:FingerprintAuth.bypass` | `qf2#g(Context, qf2$a)V` | `lt1#w0(Context, cv1)V`（`lt1.java:1644`） | `classStrings=["PwdControlUtil"]` + `[Context, cv1]` | **接口改名**：回调 `qf2$a` → `cv1`（只有 `a(Z)` / `b()` 两个方法，实现类 `bv1`），清待决弹窗 `c()` → `b()`。`lt1.w0` 自己第一句就是 `((bv1) cv1Var).b()`（`:1646`），认证结束回 `cv1.a(i == 0 || i == 10001)`。拦截侧因此改成「优先叫 `c`（37 旧名），否则取唯一的 0 参 void（38 命中 `b`）」，不再硬编码方法名 |

**M 的口径没变**：新表仍是 11 点（删 1 加 1）。`installed 11/11` 里的 11 指**挂载成功数**，
不是「运行时命中数」——两条检测管线的类都可加载、都能挂上，但 `bd1` 构造器按 `isHn` 二选一，
运行时只有一条的 `b()` 会被调用，所以判据行 `installer-hook 生效: installer:SecurityCheck.bypass*`
只会出现其中一条。

### 3. 检测链新架构：`bd1`（作业）+ `ld1`（管线）+ `dd1`（结果）

37 版是「一个 `ly1.h(boolean, sy1)` 分发方法 + `sy1` 回调」；38 版拆成了三层对象，
**旧分发方法不存在**（`ly1` 现在是无关接口，见 §1）。这是本次适配最有价值的逆向结论：

```
PackageInstallerActivity.S()   // = updateCheckingView()，主线程（:1542 / job 创建在 :1770-1781）
  ├ new bd1(isHn = W0, …)                  // 二选一：isHn ? ed1 : id1
  ├ D0.b.a(N0.i)                           // ld1.a(me1)：绑定检测过程回调（me1）
  └ D0.b.b(new b(), this.v1)               // ld1.b(kd1, boolean)：全 APK 唯一调用点
        ├ ed1.b / id1.b                    // startCheck()：系统扫描 + 云检测/名单检测
        └ handler(msg=6) → ((PackageInstallerActivity.b) p).c(dd1, vd1)
                                           // mCompatControlResult= → N0.c → P() → F0 → b1()
```

**`bd1` = HnAppCheckJob（作业门面）**（`bd1.java`）

- 字段只有两个：`public final boolean a`（isHn）、`public final ld1 b`（选中的管线）；
- 构造器 `bd1(boolean z, Activity, md1, od1, nd1, String, int, int)`：`this.a = z;`
  `this.b = z ? new ed1(activity, md1, od1, str, i, i2) : new id1(activity, md1, od1, nd1);`（`:13-20`）——
  **两条管线是构造期二选一，不是运行期分支**，所以两处都能挂、只会命中一处；
- 静态工厂 `public static dd1 a(boolean z)`（`:22-38`）＝ 宿主自己的「**无需检测/通过**」结果：
  - `z == true` → `new HnControlResult{p=false, o=false, e=1, b=true}` 包进 `dd1`（`isSupportHnCompat=true` 口径）；
  - `z == false` → `new jd1{q = new ControlResult{e=0, h=1, d=true}}` 包进 `dd1`（通用口径）。
- 宿主自己就在用这个工厂：`PackageInstallerActivity:1693`（`H0 || kt1.s(...)` → "no need check app"）、
  `:1710`（腾讯免检）、`InstalledPkgHintDialog:136`。**复用它 = 与宿主「无需检测」路径逐字段同构，不需要伪造任何类型。**

`isHn` 的真实含义（容易误解，记一笔）：`PackageInstallerActivity.W0 = z81.b`（`:5419-5420`），
而 `z81.b` 在 `PackageInstallerApplication` 里被赋成
`rv0.e1(rv0.F(this, "com.hihonor.appmarket", PackageManager.MATCH_ANY_USER))`（`:104-105`；`rv0.e1` 判 `FLAG_SYSTEM`）——
即「**荣耀应用市场是否为系统应用**」，不是设备型号/地区判断。机型刷过市场包的场景会翻转这条分支。

**`ld1` = 管线接口**（`ld1.java:4-9`）：`a(me1)` / `b(kd1, boolean)` / `shutdown()`。`kd1`（`kd1.java`）
是**空标记接口**，全 APK 唯一实现 = `PackageInstallerActivity.b`（`:417`，非静态内部类，合成字段持有外部 Activity）。

**两条管线**（都实现 `ld1`，`b()` 第一行都打 `startCheck()`）：

| | `ed1`（荣耀云，TAG `HnAppCheckJob`） | `id1`（通用，TAG `HwAppCheckJob`） |
|---|---|---|
| `b(kd1,Z)` 入口 | `:271`，打 `startCheck()` | `:165`，打 `startCheck()` |
| 回调字段 | `this.p = kd1Var`（`:290`，**在缓存短路之后**） | `this.j = kd1Var`（`:170`） |
| 缓存短路 | `!z && 缓存命中` → `postDelayed(1000)` 直接回吐缓存的 `dd1` 后 return（`:275-287`）；**此路径 `p` 保持 null** | 无此路径 |
| 检测面 | 系统扫描 `lc1.m2(...)` + 云检测 `SecurityDetectV3RequestCall`（`DetectReqV3`）+ 本地高风险名单 `high_risk_app_list`；请求回调 `fd1`（`ud1`）→ `ed1.c(...)` | 系统扫描 `lc1.m2(...)` + `SecurityStrategyBean` 名单（`xp1.n()`）+ `jd1/ControlResult`；请求回调 `hd1` → `id1.c(...)` |
| 结果对象 | `HnControlResult` → `dd1.a=true` | `jd1.q(ControlResult)` → `dd1.a=false` |
| 最终回调 | handler `msg=6` → `((PackageInstallerActivity.b) p).c(new dd1(o), I)`（`:120-147`） | handler `msg=6` → `((PackageInstallerActivity.b) j).c(new dd1(f), 空 vd1)`（`:76-88`） |
| `shutdown()` | `:674`：停线程池、清 handler、`e/E/z = null`，最后 `p = null`；**不读 `p`** | `:474`：同上，`j = null` |

**`dd1` = `CompatControlResult`**（`dd1.java`；`toString` 前缀 `CompatControlResult{isSupportHnCompat=`，`:156`）：
`a` = 是否荣耀口径、`b` = `HnControlResult`、`c` = `jd1`。**所有 getter 都按 `a` 二选一取值**，
所以「通过结果」必须用 `bd1.a(isHn)` 生成，不能手搓 `dd1`。

**回调终点**（`PackageInstallerActivity.b`，`:417`）：

- `c(dd1, vd1)`（`:616`）是**最终回调**：打 `mCompatControlResult=` 日志 → `vd1.a(activity.c0())` 回填图标 →
  `N0.c(c1, dd1)`（福利推荐即时展示）→ `PackageInstallerActivity.P(activity, dd1)`（`:866`）把结果落到
  `activity.F0`、刷新 `RbiTempBean` 若干字段后 `b1()` 继续安装流程；
- `vd1`（`vd1.java`）只有一个方法 `a(Drawable)`，是**图标接收端**（宿主要把 app 图标交给它做风险应用入库），
  传空实现代理是安全的（`ed1` 的 `I` 字段实现里对 `drawable/G/H` 都做了 null 判断）；
- `ld1.b(...)` 全 APK **唯一调用点**是 `PackageInstallerActivity:1781`，落在 `S()`（`updateCheckingView`）
  的主线程流程里 —— 这是「同步回吐结果」成立的前提。

### 4. 拦截策略：挂管线入口，不调原方法

挂 `ed1.b` / `id1.b`（不是挂 `bd1`：`bd1` 只有构造器、静态工厂和 `releaseCache`，没有检测入口方法）：

1. **不调原方法** ⇒ 检测请求根本不发（云检测、名单拉取、系统扫描全都不会启动），
   下游的「当前网络不佳」横幅、风险拦截页、「已了解」勾选框、指纹弹窗整条链不会出现；
2. 从 `chain.args[0]`（`kd1`）取回 Activity：`PackageInstallerActivity.b` 的合成外部引用字段，
   按字段类型 `com.android.packageinstaller.PackageInstallerActivity` 找，不依赖字段名（R8 会改名）；
3. 读 `activity.D0`（`public bd1 D0`，`:265`）→ `D0.a`（isHn）→ 调 `bd1.a(isHn)` 拿宿主自己的通过结果；
   取不到 `a` 字段时回落到表里写的 `value`（`ed1` 点 true / `id1` 点 false，与各自管线的口径对应）；
4. 反射找 `c(dd1, vd1)`（判据 = 恰好 2 参且第 1 参能接收 `dd1`），第 2 参用空实现动态代理，
   **同步**调用（唯一调用点在主线程，宿主自己的缓存短路路径也是 `postDelayed` 后回调，时序语义一致）；
5. **任何一步失败 → `chain.proceed()`**，降级为「有检测」的正常流程，安装不死锁（这点比 37 更保守：
   37 的 `CHECK_BYPASS` 用 `Handler.post` 异步回吐，失败时宿主已经拿不到结果，只能打 WARN）。

指纹侧（`FINGERPRINT_BYPASS`）也顺手加固了：清弹窗方法**按签名找**（0 参 void，优先叫 `c`），
判定通过方法按「1 个 boolean 参数、优先叫 `a`」找 —— 因为 37 的 `qf2$a.c()` 在 38 变成了 `cv1.b()`，
硬编码名字必然跨版本失效。`bv1.a(true)` 的副作用是 `X0=false; I0(true); activity.D0()`（继续安装），
与真人认证通过完全一致。

### 5. 判据与验证

- **JVM 单测**：`tools/dev.sh test` 全绿（62 项）。与本目标相关的是 `HookPointTablesTest`
  （38 表的 `kind` 必须命中 `MountKind` 枚举、`feature` 必须命中 `FeatureCatalog` KEY 常量、
  id 唯一、`extraVersions ↔ installerTables` 双向交叉）与 `DecisionTablesTest`（云控/配置表契约）。
- **构建**：debug / release 均通过（release 走混淆 + 资源收缩 + 稳定签名，Xposed 元数据回归同 §六）。
- **静态交叉校验**：新表 11 点在 38 版 jadx 产物里逐条命中「类名 + 方法名 + 参数个数 + 返回类型」，
  锚点串逐条 grep 到（见 §2 表）；本文引用的每个类/方法都在 `installer-38-jadx/sources` 里存在。
- **真机判据（2026-10-20 16:33 已达成）**：`InstallerHook: installed 11/11`，无失败清单；
  同会话内 `InstallerHook 宿主版本与 hook 基线一致: 14.0.38.301`，且**无「挂载点漂移命中（DexKit 兜底）」行、
  无「宿主版本读取失败」行** ⇒ 38.301 精确表 11 点**全部直连命中**（未走 DexKit 兜底）。
  **交叉印证**：盲挂基线那次真机跑出的 8 条 DexKit 漂移命中，其实际签名与本表**逐点一致**
  （`p81#a(String)Z`、`kc1#u(Z)V`、`CommonConfigManager#e(String)Z`、`CommonConfigManager#c(Rsp)String`、
  `lc1#A1(int,SafeModeRise,RbiTempBean,boolean)Z`、`lc1#D1(CardTipsRecord,String,boolean,String)Z`、
  `PackageInstallerActivity#B0()V`、`lt1#w0(Context,cv1)V`）——真机独立印证了本表内容。
  下一版复测看点：
  1. 两条检测管线**都算挂载点**，`生效:` 行只会出现其中一条（另一条运行时不被调用），这不是缺挂；
  2. 若出现 `挂载点漂移命中（DexKit 兜底）` 的 WARN，说明直连轨 miss 而锚点轨补上了，
     实际位置以 WARN 里的签名为准（短名在下一版还会变，锚点才是长期契约）；
  3. 若检测点失败，先确认该机 `z81.b`（荣耀市场是否系统应用）决定走的是 `ed1` 还是 `id1`，
     再查对应那一行的类/方法。
- **行为复核清单**（与 §六 末相同）：安装确认页/安装中页/安装完成页无广告卡片、不弹"开启安全模式"引导、
  无安全模式提示卡与底部推广卡、无"当前网络不佳"横幅、无风险拦截页与「已了解」勾选框、无指纹弹窗，
  安装成功页「完成」正常关闭。**2026-10-20 用户真机实测：以上各项均正常。**

**残余风险（行为层已由用户实测通过；以下为代码层未逐指令复刻的部分，挂载层 11/11 已达成）**：

- 通过结果 `HnControlResult.a`（控制码）为 null、`L=false`，走 `P()` 时 `kt1.i(dd1)` 返回 null，
  `"0001".equals(null)` 为 false ⇒ 不判风险、`riskAppsCount` 不累加；`dd1.q()/p()` 对通过结果都是 false。
  读代码是安全的，但它**不是**宿主「无需检测」路径的逐指令复刻（宿主那条是直接 `F0 = bd1.a(W0)` 后 `b1()`，
  我们走的是异步回调 `c()` 路径，会多执行 `N0.c(c1, dd1)` 与 `vd1.a(icon)`）——**用户实测未复现异常**
  （2026-10-20 安装流程正常）：
  `N0.c`（`no1.java:222`）在非加速态且 `dd1.e() != 1` 时直接 return（`:239`），通过结果是 `b.Y=0`，
  按代码不会展示福利装配，但真机未验。
- `ed1` 的缓存短路路径（`!z && 缓存命中`）说明宿主自己也接受「延迟 1s 回吐」；我们改成同步回吐，
  前提是 `c()` 只做 UI 收尾（日志 + 图标 + 福利 + `P()`），无阻塞调用。**待复核（未在极慢机型验证）**：极慢机型上
  `c()` 内 `b1()` 的重布局是否会在 hook 回调栈里超时（`ld1.b` 的调用方是主线程，属于同栈同步执行）。

### 6. 版本读取与选表机制（2026-10-20 修复；`installed 11/11` 的前提）

> 为什么单起一节：`installed 1/11` 那次失败**不在挂载点本身**，而在**选表没选对表**——宿主版本读不到 ⇒
> 落基线盲挂 ⇒ 37 版基线表里 10 个类名/方法名在 38 版已漂 ⇒ 只挂上唯一未漂的 `AdRespV4.getAdAppList`。

**根因（写准，别再误判）**：旧取法 `ActivityThread.currentPackageManager` ——该成员在现代 Android/AOSP
**根本不存在**（Android 14 源码全文 0 命中；真机 `getMethod` 与 `getDeclaredMethod` 均 `NoSuchMethodException`，
成员清单里也没有）。ART 对「成员不存在」与「被 hidden-API 黑名单拒绝」抛**同一种异常类型** ⇒ 本会话早先把
它误判成 hidden-API 黑名单拦截，结论是错的。**没有引入任何隐藏 API 绕过库**，全程公开 API、零新依赖。
另注：`ApplicationInfo` 里**没有 versionName 字段**（AOSP + 公开 SDK stub 皆无）⇒ libxposed 的
`getApplicationInfo()` 拿不到宿主版本号。

**修复后的机制**（代码：`xposed/core/HostVersion.kt` + `xposed/target/InstallerHook.kt`）：

1. **宿主版本不再在 `install()` 里读**，改由 `MountScheduler.postDelayed` 延后探版本，重试延迟表
   `0 / 200 / 600 ms`（3 次尝试）——给宿主 Application 起来的窗口（延迟只影响选表时机，不影响挂载正确性）。
2. **版本读取走全公开 API 双取法**（`HostVersion.resolveHostVersion(apkPath, packageName)`）：
   ① `ActivityThread.currentActivityThread()` → `getSystemContext()` →
   `packageManager.getPackageArchiveInfo(apkPath, 0)?.versionName`——**无时机依赖**（系统上下文在 `attach()`
   后即可用，`ContextImpl.createSystemContext` 惰性创建，不依赖宿主 Application）；
   ② 兜底 `currentApplication.context.packageManager.getPackageInfo(packageName, 0)?.versionName`
   （公开路径，但宿主 Application 创建之后才有值）。
   两条各失败各吞；都读不到 = 版本未知 ⇒ 按基线盲挂并留痕（`宿主版本读取失败…按基线盲挂` WARN /
   `宿主 X 不在适配表…按基线盲挂` INFO）。
3. **选表**：`HostVersion.pick(installerTables, actual)`——命中则用该版本专属表
   （`14.0.38.301` → 11 点表），未命中才落基线源码路径盲挂。

> 通用教训：**版本分发的前提是「读得到宿主版本」**。读不到时不要退化成「按最新基线盲挂再祈祷」——
> 先确认版本读取路径本身可用（本例：公开 API 双取法 + 延后探测时机），再去调挂载点表。

