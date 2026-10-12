# 主题商店 `com.hihonor.android.thememanager` — 逆向分析记录（证据层）

> 本文件 = 该 app 的**逆向分析记录**：逐条 file:line 推导、下载链全图、UI 真链反查、云控开关全表。
> **设计与策略**（需求 / 挂载点清单 / 按钮与下载链决策 / 判据）在 [`../theme-store.md`](../theme-store.md) —— 日常改 hook 只读那份；本文件按需翻证据。
> 索引：[`README.md`](README.md)（总览）· [`../README.md`](../README.md)（docs 总索引）
> 行号相对 jadx 产物根 `system-files/theme-store-jadx/sources`（20.1.63.305）；该产物 2026-10-10 已清理，需要时 `tools/dev.sh jadx <商店 apk>` 重建。


---

## A · 付费机制逆向与 hook 点清单（引擎 apk + 商店 apk）

# 荣耀主题付费机制逆向分析（Hook 点清单）

> 目标：给 HonorHook 主题 hook 提供直接 hook 输入。分析对象为两 apk：
> - `荣耀主题引擎_11.0.1.122.apk`（1.3MB，com.hihonor.mocoolengine）
> - `主题_20.1.63.305.apk`（98MB，com.hihonor.android.thememanager，主题商店）
> 反编译产物：`system-files/theme-engine-apktool`（smali）、`theme-store-jadx/sources`（jdax 1.5.6，50532 java 文件）
> 结论依据均为 source 行号（file:line 相对 theme-store-jadx/sources 根）。

## 1. 两 Apk 基本盘

| 项 | 引擎 | 主题商店 |
|---|---|---|
| 包名 | `com.hihonor.mocoolengine` | `com.hihonor.android.thememanager` |
| 版本 | 11.0.1.122 | 20.1.63.305 |
| dex | 1 个，991 类 / 7104 方法 | 19 个 dex，33064 类（jadx 成功解出 98%，589 类失败） |
| 混淆 | **未混淆**（com.hihonor.zookingsoft 供应商代码，类名全可读） | 半混淆：应用主包 ~9800 类**可读**（keep 名单），`defpackage/` 下 8074 类混淆为 a0/a01/… 单字母类名，不可读 |
| 身份 | 锁屏/桌面主题 **渲染引擎**（2D/3D 动画 + ExoPlayer 视频 + AOD + 音乐节奏），以 `<library>` + RemoteProvider（`com.hihonor.zk.remote.RemoteProvider`）被主题商店/系统加载 | 荣耀主题商店（列表/详情/下载/应用/会员/VIP/金币/广告激励） |

**关键结论（引擎）**：引擎内**零付费逻辑**。全文 smali 检索 `free/vip/member/price/trial/pay` 仅命中 `Ldalvik/annotation/MemberClasses;` 注解与 AV 播放器 DRM 错误码（`AV_ERROR_DRM_LICENSE_ACQUISITION_FAILED/EXPIRED`，播放加密媒体用）。引擎只做"渲染+解析主题 XML+读写持久变量"，**不做任何免费/收费/试用/会员判定**，不需要 hook。付费门禁全部在主题商店侧（第 5 节说明 ROM 侧额外校验）。

## 2. 免费/收费判定机制（全本地可 hook）

判定权威集中在 **`com.hihonor.android.thememanager.common.ItemInfo`**（resources bean），字段 `price`/`actualPrice`（double，来自服务端 String 价格）与若干派生方法：

| 方法 | 逻辑（file:line） | Hook 建议 |
|---|---|---|
| `ItemInfo.isNeedPayItem()` | `price > 0 \|\| price == -100.0`（common/ItemInfo.java:728-731） | **常返 false 的核心点** |
| `ItemInfo.isNeedOrder()` | `price > 0 && !isPayed`（:723） | 常返 false |
| `ItemInfo.getPayed()` / `isPayed()` | 返回 `isPayed` 字段（:496 / :741），来源=服务端已购列表 | 常返 true |
| `ItemInfo.isVipResouce()` | `getVipOnly() == 1`（:781-783） | 常返 false（VIP 专属资源当普通资源） |
| `ItemInfo.price` / `actualPrice` 字段 | `-100.0` = 免费赠送标记 `DownloadInfo.FREE_PRESENT_PRICE`（common/DownloadInfo.java:61） | 字段级 hook 改 0 |
| `ItemInfo.discountItem()` | `price>0 && actualPrice<price`（:336） | 不需要 |

判定语义（实测推导）：
- **免费**：price ≤ 0（服务端无独立 isFree 字段，由价格推导；`-100`=免费赠送，展示为 "0" 元，common/DownloadInfo.java:969）
- **限免/免费领**：`actualPrice <= 0 && price > 0`（原价>0 现价 0）
- **已购**：serviceId 命中服务端已购列表 `DetailSource.payedList()` → `NetHelper.getPayedIdList(buildPayedListRequest)`（detail/source/DetailSource.java:298-304）；订单状态 4 = 未付费（mvvm/pay/MyPayedAdapter.java:116-118）

**详情页按钮矩阵**（`OnlinePreviewBaseActivity`，付费 UX 的总开关，4170-4365 行）——hook 这一组即可让所有收费资源表现为免费/可下载：

| 方法 | 显示条件摘要 | Hook 目标 |
|---|---|---|
| `isBuyBtnShow(ItemInfo)` | 需付费且未购且非 VIP 场景免单 | **常返 false**（不显示"购买"） |
| `isDownloadBtnShow(ItemInfo)` | 已购，或 actualPrice≤0 且 price≤0（真免费） | **常返 true**（免费资源直接显示下载） |
| `isFreeAccessBtnShow(ItemInfo)` | 限免（actualPrice≤0 且 price>0）且未购 | 常返 true（"免费获取"可用） |
| `isVipBtnShow(ItemInfo)` | VIP 区：vipOnly 且未购；非 VIP 区恒 false | 常返 false |
| `isTrialBtnShow(ItemInfo)` | 需付费支持、非已购非免费非 VIP 用户/资源、云控 `AgreeRepo.isTrialSupportOnline()` 开、`u02.isSupportPayed()` 支持、非激励期 | 常返 true（"试用"按钮常显） |
| `isChinaFreeResource()` | 中国区 && price≤0 && 非 VIP 按钮 | 无所谓 |
| `ItemInfo.isRunning()/isCurrent()/isDownloaded()` | type 位掩码：256=当前应用、32=已下载、48=**试用下载**（common/ItemInfo.java:696-773） | 观察用 |

**普通页面（非详情页）**：列表/格子的下载按钮逻辑同样走 ItemInfo 判定（`BaseOnlineListActivity`、`NewRankListFragment` 等），所以 hook 住 ItemInfo 的 5 个方法即可全局生效。

## 3. 试用机制（双轨）

### 轨道 A：在线试用授权（限时激励/任务/免费领）——服务端主导，本地执行到期判断
- 流程：详情页"试用/免费领" → 激励任务（激励视频/下载 App/游戏/金币，`incentive/request/IncentiveBaseRequestHelper`，免费次数由服务端任务模型下发，本地无计数）→ 完成任务后 `DetailModel.grantTrialRoleByTaskOrderId(...)` 调服务端授权接口 → 返回 `GrantTrialRoleResponse.getResult()/getTryEndTime()`（detail/model/DetailModel.java:2293-2310）→ 写入 `ItemInfo.timeLimitIncentiveInfo = new TimeLimitIncentiveInfo(resHitopId, tryEndTime)`
- 到期判定：**`TimeLimitIncentiveInfo.duringIncentive()` = `incentiveExpireTime > System.currentTimeMillis()`**（incentive/entity/TimeLimitIncentiveInfo.java:100-102）
- 服务端下发的试用时长字段：`GetMultiResourceRspBean.MultiResourceBean.trialTime`（String，hitop/entity/res/GetMultiResourceRspBean.java:51-52）→ `ItemInfo.trialTime`

### 轨道 B：离线"8 分钟试装"（对已装收费主题的体验）——纯本地，最值得 hook
核心类 `com.hihonor.android.thememanager.tryout.a`（单例 TryOutHelper）：
- 试用标记：`c.TRIAL_PATH`（路径含 "trial"，common/c.java:485）+ 标记文件 `PATH_DATA_SKIN/tried`（tryout/a.java:529, TRY_OUT_FILE="tried"）+ SP key `tryout_type<type>time`（type=1 主题/2 字体…，tryout/a.java:708-710）
- **到期判定 `isWareOutDated(i)`**：SP 存的开始时间距现在 > `TIME_OUT_INTERVAL=480000ms`（8 分钟）→ 过期（tryout/a.java:708-710）
- **有效判定 `isValidTheme()`**（tryout/a.java:660-702，hook 首选）：按 ROM 是否启用 ThemeProvider 分两路，共同条件 = 未超 8 分钟窗 + 系统设置"自动时间"开启（`Settings.System.auto_time==1`，否则直接无效）
- 计时器：`ThemeTimeFenceManager`（FENCE_TIME=300000ms 每 5 分钟 TIME_TICK + AlarmManager）检查当前主题试用是否到期（tryout/ThemeTimeFenceManager.java），到期发广播/弹窗（文案"试用已结束"）
- 试用态落地：`ThemeResManager.getThemeApplyState()` 读主题库 attribute=="trial" → `ThemeApplyState.TRIAL`（theme/apply/ThemeResManager.java:359-366）；应用成功更新库
- 试用历史：`logic/trialhistory/`（TrialHistoryActivity，入口 `/theme/TrialHistoryActivity`，common/Const.java:346）；到期恢复：`quitTrialThemeInfo()` / `releaseTheme()` / `moveTrialTheme2Pay()`（购买后试用转正式，tryout/a.java:319,751,837）

云控开关（ams/mgr/AgreeRepo.java:298,338,448-459）：`TRIAL_RECORD_ENTRY_SWITCH`、`TRIAL_SUPPORT_ONLINE`（默认 true）、`VIP_TRIAL_ARRIVED_POP_VALUE/TIME_OUT_VALUE`——均走 `u15` 云控配置读取，hook `AgreeRepo.isTrialSupportOnline()` 常返 true。

## 4. 付费会员/VIP 判定（本地判定 + 服务端校验并存）

- 状态容器：`com.hihonor.android.thememanager.vip.manager.VipManager`（Kotlin companion 对象）——**静态字段 `isVipUser`（boolean）** + `vipState`（`vip/data/VipState`）
- `VipManager.isVipUser()` 直接返回静态布尔（vip/manager/VipManager.java:111-112）——**hook 最优点**：常返 true 或拦截 `setVipUser` 强制 true
- `VipState` 字段：`isVipUser`、`vipType`（1=单月 2=连续包月 3=**已过期** 4=无 VIP）、`startTimeTimestamp`、`expireTimeTimestamp`、`vipStatus`、`remainingTime`、`fromNetWork`（vip/data/VipState.java:60-75）
- 服务端校验：`VipModel.checkVipStateOnline(isForceRefresh)`（vip/model/VipModel.java:142+）——本地 10 秒内存节流（vipLastSuccessRequestTime）→ 构造带签名的 `VipStateReq`（requestSign）→ 服务端返回 VipState → `VipManager.setVipUser(data.isVipUser())` + `setVipType()` → `WidgetUtil.sendCheckVipStateBroadcast` 广播全局（vip/model/VipModel.java:240-266）。**注意：hook 网络回调（setVipUser）不如 hook 读点（isVipUser 静态 getter）稳，因为多处从静态字段直读**
- 会员卡（Honor 会员）：`com.hihonor.membercard`（McConfig/McConst/datasource/response/McResponse）——独立会员体系，主题商店读它判断"荣耀会员"身份
- 会员/VIP 页面与"过期/续费"流（manifest 组件 + 文案）：`vip/ui/` 下 `VipMemberCenterActivity`、`StandardVipMemberCenterActivity`、`OpenVipRecordActivity`、`RenewalManagementActivity`（续费管理）、`VipRedeemActivity`（兑换码）、`VipResSavedActivity`（VIP 资源使用）；文案"月卡已过期""会员即将到期""已失去全部会员特权""开通 VIP""立即开通"（arsc 字符串池）
- 会员到期入口：`EntrySource.LAUNCHER_VIP_EXPIRE`（analytics/helper/EntrySource.java:23）——桌面图标角标/点击提示 VIP 过期

## 5. 引擎/ROM 侧授权校验（为什么只 hook 商店可能不够）

- 引擎 apk 无校验（第 1 节）。但新 ROM 上应用主题走 **ThemeProvider（ROM 系统服务）**：`ThemeResManager.isThemeProviderEnabled()` 为 true 时，应用用 `applyThemeViaThemeProvider`，主题库中 attribute 标记 `trial`（ThemeApplyState.TRIAL）——**校验是 ROM 侧 Provider 与主题商店通过数据库/属性协作的**，商店 hook 后"显示"放行，真正"应用"是否被 ROM 拒取决于 ROM 侧实现（本次只有商店侧证据，ROM 侧 Provider 未在分析范围内）
- 服务端收紧点：试用授权（GrantTrialRole/tryEndTime）、已购列表（PayedBean）、VIP 状态（VipStateReq）**都是服务端下发**；若目标效果只是"界面放行/按钮全开"，本地 hook 足够；若目标是"真正免费应用+长期有效"，需配合 hook `duringIncentive()`/`isValidTheme()` 并把 `TimeLimitIncentiveInfo.incentiveExpireTime` 拉远（Long.MAX）
- 防绕过小点：`ItemInfo.isSpecialEncrypt`（isSpecialEncrypt==1 的资源文件加密，hitop/entity/res/BaseResItemBean.java:49-51）——付费资源文件本身可能加密，hook 判定后需确认下载器解密链路（本次未深挖解密）

## 6. Hook 点清单（HonorHook 直接使用）

进程/包名：`com.hihonor.android.thememanager`（商店）。以下类名全部可读、不会被混淆漂移（keep 名单内）。建议用静态 getter/setter 拦截而非字段直改（LSPosed 字段 hook 也支持，但 getter 更稳）。

### A. 免费判定（把收费资源变"免费/可下载"）—— 建议全上
| # | 类 | 方法/字段 | 签名 | hook 策略 |
|---|---|---|---|---|
| A1 | common.ItemInfo | `isNeedPayItem` | `()Z` | 常返 false |
| A2 | common.ItemInfo | `isNeedOrder` | `()Z` | 常返 false |
| A3 | common.ItemInfo | `getPayed` / `isPayed` | `()Z` | 常返 true |
| A4 | common.ItemInfo | `isVipResouce` | `()Z` | 常返 false |
| A5 | common.ItemInfo | `price` / `actualPrice`（字段 double） | `D` | 改 0（或 hook setPrice/setActualPrice） |
| A6 | OnlinePreviewBaseActivity | `isBuyBtnShow` | `(ItemInfo)Z` | 常返 false（隐藏购买按钮） |
| A7 | OnlinePreviewBaseActivity | `isDownloadBtnShow` | `(ItemInfo)Z` | 常返 true |
| A8 | OnlinePreviewBaseActivity | `isFreeAccessBtnShow` | `(ItemInfo)Z` | 常返 true（"免费获取"常显） |
| A9 | OnlinePreviewBaseActivity | `isVipBtnShow` | `(ItemInfo)Z` | 常返 false |
| A10 | OnlinePreviewBaseActivity | `isTrialBtnShow` | `(ItemInfo)Z` | 常返 true（"试用"常显） |
| A11 | hitop.entity.res.BaseResItemBean | `getPrice`/`getVipOnly`/`getActualPrice` | `()String`/`()I` | 改 0（服务端 bean 源头，列表接口就用它） |

### B. VIP/会员判定
| # | 类 | 方法/字段 | 签名 | hook 策略 |
|---|---|---|---|---|
| B1 | vip.manager.VipManager | `isVipUser`（Kotlin companion 静态） | `()Z` | **常返 true（首选）** |
| B2 | vip.manager.VipManager | `setVipUser` | `(Z)V` | 拦截强制置 true |
| B3 | vip.data.VipState | `getExpireTimeTimestamp` / `getRemainingTime` | `()J` / `()Ljava/lang/Long;` | 返 Long.MAX（会员永久） |
| B4 | vip.data.VipState | `getVipStatus` / `getVipType` | `()I` | 返 1（单月生效态） |
| B5 | vip.model.VipModel | `checkVipStateOnline` 返回值 | — | 网络返回后 overwrite（非必须，B1 已够） |

### C. 试用/限免判定
| # | 类 | 方法/字段 | 签名 | hook 策略 |
|---|---|---|---|---|
| C1 | tryout.a | `isValidTheme` | `()Z` | **常返 true（离线 8 分钟试用窗永久有效）** |
| C2 | tryout.a | `isWareOutDated` | `(I)Z` | 常返 false（SP tryout_type<type>time 超 8 分钟也判有效） |
| C3 | tryout.a | `isSupportTrial` | `()Z` | 常返 true |
| C4 | tryout.a | `checkCurrentTheme` | `()Z` | 常返 true（防 fence 到期回收） |
| C5 | incentive.entity.TimeLimitIncentiveInfo | `duringIncentive` | `()Z` | **常返 true（在线试用/任务授权窗永久有效）** |
| C6 | incentive.entity.TimeLimitIncentiveInfo | `getIncentiveExpireTime` | `()J` | 返 Long.MAX |
| C7 | theme.apply.ThemeResManager | `getThemeApplyState` | `()Lcom/hihonor/android/thememanager/theme/apply/ThemeApplyState;` | 返 PERMANENT（新 ROM provider 路径） |
| C8 | ams.mgr.AgreeRepo | `isTrialSupportOnline` | `()Z` | 常返 true（云控开关） |
| C9 | theme.apply.ThemeResManager | `isTrialTheme` | `(I)Z` | 常返 false（避免"试用中"角标/到期提醒） |

### D. 购买/已购（服务端为主，hook 有限）
| # | 类 | 方法/字段 | 签名 | hook 策略 |
|---|---|---|---|---|
| D1 | detail.source.DetailSource | `payedList` | `(ILkotlin/coroutines/Continuation;)Ljava/lang/Object;` | 追加入本地 serviceId（模拟已购） |
| D2 | mvvm.pay.MyPayedAdapter | convert 中 `orderStatus==4 → setPayed(false)` | — | 改判 true（我的已购页） |
| D3 | hitop.NetHelper | `getPayedIdList` | — | 本地回包假数据 |
| D4 | hitop.entity.OrderBean/OrderManagerImpl | `createOrder` | — | 不建议 hook（服务端单/签名，改了也没用） |
| D5 | com.hihonor.iap.sdk 包 | `PayActivity` | — | IAP SDK 支付页，一般无需 hook（A 组已放行 UI） |

### E. 存储位置（供排查/回写用）
- SP（应用私有）：`tryout_type<type>time`（试用开始时间）、`tryout_theme_hitop_id`、`tryout_resource_font_id/title`、`TRYOUT_TYPE`；`tried` 标记文件在皮肤数据目录 `PATH_DATA_SKIN/`（引擎侧 RemoteProvider 只读该目录做文件操作）
- 主题库 attribute：`trial`（ROM ThemeProvider 数据库，ThemeApplyState 数据源）
- 内存态：`VipManager` 静态字段（进程内，hook 必中）

## 7. 未确定项（如实标注）

1. **ROM ThemeProvider 服务侧是否独立校验主题授权**——商店 hook 后"应用"步骤若被 ROM 侧拒绝，需要额外 hook 系统主题服务（本次无 ROM 系统 apk 证据，未证实）
2. **付费资源文件加密链路**——`isSpecialEncrypt==1` 的资源下载后如何解密、解密密钥是否服务端下发（此为关键的"真正用上"路径，未深挖）
3. **"免费次数"（免费领任务）**——字符串"免费次数已用完""您本月的试用"存在，但计数逻辑在服务端任务模型（IncentiveCommonManager 拉取），本地无计数器可 hook
4. **`u02.isSupportPayed()`**（设备是否支持付费，混淆类 defpackage/u02）——具体实现未读（影响 isTrialBtnShow 前置条件，可 hook 常返 true 双保险）
5. **试用角色（GrantTrialRole）服务端接口的签名/风控**——若服务端拒绝授权，轨道 A 的"免费领"仍不可用（Hook 只能让 UI 全开，任务/广告链路仍需服务端配合）
6. 大 apk 的 `--no-res` 解码导致部分资源 ID 与代码引用无法一一对应（arsc 已手工导出关键文案），个别页面级门禁可能漏判——核心判定链（ItemInfo/OnlinePreviewBaseActivity/TimeLimitIncentiveInfo/tryout.a/VipManager）已闭环，风险低


---

## B · 下载链反查与 0% 卡点定位

# 付费主题下载卡 0% — 下载链路反查与卡点定位

> 真机现象：付费主题被 hook 免费化后，详情页"下载"按钮可点，进度永远 0%。
> 分析对象：`system-files/theme-store-jadx/sources`（主题商店 20.1.63.305）。
> 行号均为该目录相对路径；hook 现状见 `app/.../ThemeStoreHook.kt`（37 项，F2 全开）。
> 结论先行：**卡点 = 候选 2（下载 URL 获取失败）**——F2 免费 hook 把付费主题推入"免费直下"分支，
> 而该分支直接用详情接口 JSON 里的 `fileUrl` 当下载地址；付费主题服务端不在 JSON 里下发可用直链
> （真实 URL 只在购买/试用授权接口 `sendProduct` 返回）。空/占位 URL 在下载器里**静默短路、不报错**，
> UI 按钮停留在"已开始、进度 0%"状态，永不更新。下文给全链路 + 证据 + 可行的 hook 绕法。

## 1. 下载链路全图（每环 file:line，均在 jadx 源码实证）

```
详情页"下载"按钮点击
  └─ OnlinePreviewBaseActivity$DownloadStartState.start()         OnlinePreviewBaseActivity.java:1051-1078
       （1055 行用 isNeedPayItem 只做埋点；1059 行 startItemDownload(1) → downloadType=NORMAL）
  └─ OnlineThemePreviewActivity.downloadItem(1)                   OnlineThemePreviewActivity.java:1158-1163
  └─ common.d.startDownloadTheme(ctx, itemInfo, type=1, src=1)    common/d.java:225-234
       ├─ new DownloadInfo(itemInfo) → setDownloadInfo():         common/d.java:169-223
       │    172 行 downloadInfo.uri = itemInfo.downloadUrl        ←── 核心：URL 来源是 ItemInfo.downloadUrl
       │    180-182 行 downloadInfo.price 由 itemInfo 价格字段推导（→ isPayedItem 判定源头）
       │    233 行 setNeedLogin(!themeInfo.isNoNeedLogin())
       └─ o52.addDownloadItem(builder)                            defpackage/o52.java:563-634
             ├─ 604 行 if (!isPayedItem()) → 免费分支（hook 后付费主题走这里）:
             │    609-611 isNeedLogin → h71 登录门（仅弹登录，不改 URL）  defpackage/h71.java:57-68
             │    612 行 z61Var.downloadItem()
             │        └─ z61.downloadItemInfo()                    defpackage/z61.java:41-61
             │              └─ o52.resumeDownload(info, false)      defpackage/o52.java:771-833
             │                    └─ 814 行 z71.download(downloadInfo.uri, filePath, …)  defpackage/z71.java:18-36
             │                          └─ DownloadHandler 裸 GET   downloader/http/DownloadHandler.java
             │                                (104-129 getHttpUrlConnection：只设 Method+连接超时15s，无请求头无读超时)
             │                                (218-267 sendRequest；320 行置 STARTED；onLoading 354-358 才报进度)
             └─ 615-633 行 else → 付费分支（真实购买路径，hook 后不再走）：
                   按 downloadType 选策略：2→e71(试用) 3→f71(VIP) 4→a71(审计) 5→b71(金币) 6→d71(限时) 默认 c71(普通购买)
                   所有策略的第一步都是调 c.getInstance().getDownloadUrl() = OrderManagerImpl
                   （c71.java:58、e71.java:30、f71.java:30、a71.java:28 均调 sendProduct 拿真实 URL 后才下载）
```

## 2. 两条 URL 来源路线（关键分叉）

| 路线 | 触发条件 | URL 来源 | 结果 |
|---|---|---|---|
| A 免费直下 | `DownloadInfo.isPayedItem()==false`（F2 价格 hook 后为 false，DownloadInfo.java:939-947 与 ItemInfo 同款 price 逻辑） | `itemInfo.downloadUrl` ← 服务端资源 JSON 的 `fileUrl` 字段（u02.java:190 `setDownloadUrl(themeBean.getFileUrl())`；deep-link 路径 common/g.java:596 同） | 裸 GET，无任何鉴权头 |
| B 付费/试用授权 | `isPayedItem()==true` | `OrderManagerImpl.getDownloadUrl()` → `sendProduct` 接口（OrderManagerImpl.java:292-352）：请求字段只有 hitopId/resId/resType/trialFlag（302-306），**不含订单号**；服务端按账户的购买/授权记录校验，返回 `SendProductInfo.downUrl`（334）+ 内容密钥（335 saveResContentKey） | 真实 URL 形如 `…download.do?k=<key>`（DownloadInfo.java:927-933 isFixUrl 的判定串），K 全部服务端下发，客户端无任何本地拼接（全库检索 `?k=` 仅此一处） |

## 3. 卡点结论与证据链

**结论：候选 2 —— 下载 URL 获取失败（本地 hook 把 URL 获取环节跳过了）。**

证据链（逐级实证）：

1. **hook 后的实际走向**：ThemeStoreHook.kt 挂的是 ItemInfo/BaseResItemBean 的价格与判定（:135-168），
   未挂 `DownloadInfo` 系。`setDownloadInfo` 用**价格字段**推导 `DownloadInfo.price`→0 → `isPayedItem()`=false
   → o52.addDownloadItem 走免费分支（o52.java:604-613），**跳过了 sendProduct URL 获取**（那是付费分支 615-633 才有）。
2. **免费分支下载的就是 itemInfo.downloadUrl**（common/d.java:172），该字段来自服务端 JSON `fileUrl`（u02.java:190）。
   付费主题的 `fileUrl` 服务端不下发可用直链（证据：付费四大策略无一用 item JSON 的 URL，全部重新调 sendProduct；
   且 isFixUrl 要求付费 URL 必须含服务端签发的 `download.do?k=`）。
3. **为什么是静默 0% 而不是报错**（与现象完全吻合）：
   - uri 为空/占位 → `z71.download` 对非 null 空串放行（z71.java:25-27 只挡 null），
     DownloadHandler.getHttpUrlConnection 见 `TextUtils.isEmpty(r)` 直接返 null（DownloadHandler.java:107-109），
     sendRequest 记一条 log 后 **return null，不抛异常**（:226-229）→ 线程状态停在 STARTED（:320），
     无 FAILURE、无 onLoading、无任何回调；
   - uri 为 null → z71.java:25-27 返 null handler → o52.resumeDownload :819-822 提前 return，同样无状态推进；
   - uri 存在但被服务端挂起（DownloadHandler 只有 connect 超时 15s、**无 read 超时**，:118）→ 读响应会永久阻塞，
     进度同样永远 0%。
   - 期间 UI 侧按钮早在 DownloadStartState.start() 被置为"开始+进度 0"（OnlinePreviewBaseActivity.java:1060-1067），
     之后没有更新事件 → "永远 0%"画面定格。
4. **排除候选 1（本地下载鉴权拦截）**：DownloadHandler 是裸 `HttpURLConnection` GET，无 token/签名/header
   （getHttpUrlConnection 仅 setRequestMethod + setConnectTimeout，:116-118）；链路上唯一的"门"是 h71 登录
   （只弹账密登录，h71.java:57-68），登录后放行，不产出/不校验 URL。
5. **候选 3（服务端流级拒绝）的判定**：若 fileUrl 存在而 CDN 拒流，`getResponseCode()>=300` 会抛 HttpException
   （DownloadHandler.java:135-139，HttpException 不重试 :246-247）→ FAILURE 状态 → UI 明确显示失败/重试，
   **不是**静默 0%。现状为静默 0%，故主因是 URL 为空/占位（候选 2）；流级拒绝只是同族第二子机制
   （连接挂起无读超时），可用真机日志区分（见 §5）。

## 4. 可行修复（hook 方案）

**原理**：服务端不需要真实订单也能签发的授权 URL 通道 = **试用授权 `sendProduct(trialFlag=1)`**
（e71 试用策略正是为公开"试用"按钮走的链路：e71.java:30 传 1 → 服务端发试用 URL + 试用内容密钥 →
productSendFinish 成功回调里 `uri=str; super.downloadItem()`，e71.java:62-66）。F3/F4 已把试用态永久化，
把付费主题导入"试用下载"策略即可完成下载。

**推荐 hook 组合（两个点，条件均判 `uri 为空`，避免误伤真免费资源）**：

| # | 目标 | 签名 | hook 改法 | 效果 |
|---|---|---|---|---|
| H1 | `common.DownloadInfo.isPayedItem` (common/DownloadInfo.java:939) | `()Z` | uri 为空/占位时返回 `true`，否则放行原逻辑 | 空 URL 的项进入付费分支 |
| H2 | `o52.addDownloadItem(Context, DownloadInfo, int, boolean)` (defpackage/o52.java:882) | `(Landroid/content/Context;Lcom/hihonor/android/thememanager/common/DownloadInfo;IZ)Z` | 当 `downloadInfo.uri` 为空时把 int 参数强制为 `2`（TRIAL），否则放行 | 空 URL 的项走 e71 试用策略（付费分支 617-618 行选中 e71） |

- 组合后流程：点击下载 → isPayedItem()=true 且 type=2 → e71 → 异步 sendProduct(trialFlag=1) →
  服务端签发试用 URL+密钥 → 下载开始 → 应用时 F4 的 getThemeApplyState→PERMANENT 等 hook 让试用不回收
  （apply 侧 hook 已就位，ThemeStoreHook.kt:249-271）。
- **前置条件**：设备主题商店**已登录荣耀账户**（sendProduct 按账户授权；体检现链路上 h71 登录门同样需要）。
- **注意（勿踩）**：若只 hook H1 不 hook H2，type=1 会走 c71 普通购买策略 → 弹 IAP 支付页（c71.java:70-83），
  不是目标；H2 必须只对空 URL 项强制 2，真免费资源（有 fileUrl）保持 NORMAL 分支不受影响。
- **备选方案（更少侵入但更弱）**：hook `z71.download` 入口，uri 为空时用
  `OrderManagerImpl.getInstance().getDownloadUrl(downloadInfo, 1)` 现场补 URL——但该调用是阻塞网络
  （OrderManagerImpl.java:308 `.execute()`），而免费分支调用链在 UI 线程，有 ANR/NetworkOnMainThread 风险，不推荐。

## 5. 真机验证方法（判据）

LSPosed 日志（TAG `HonorHook` / 商店日志 `DownloadService` / `DownloadItemCommand` / `DownloadHandler`）：
- 出现 `DownloadHandler: httpURLConnection is null` 或 `DownloadUtils: download error url may not be null`
  → 确认 uri 为空（候选 2 主机制，修复方案 §4 适用）；
- 出现 `DownloadHandler: handleResponse statusCode 403/401` → 服务端流级拒绝（候选 3 边缘），
  需另想真实购买通道，§4 方案也可能被服务端风控拒（如实评估）；
- 出现 `DownloadItemIfTrial: productSendFinish success ,start To download` →
  §4 hook 生效、URL 已拿到，再看后续 onLoading 是否推进。
- 无日志时可用 `DownloadInfo` DB 行（表列 `url`）旁证 uri 实际值。

## 6. 结论一句话

付费主题的下载 URL 只由服务端购买/试用授权接口（sendProduct）签发，item JSON 的 `fileUrl` 对付费资源
不包含可用直链；F2 免费 hook 把流程推入"免直下"分支跳过了签发环节，空 URL 在无读超时、无错误回调的
DownloadHandler 里静默悬停，UI 定格 0%。修法：H1+H2 把空 URL 的项导入试用授权下载策略，配合已就位的
F3/F4 试用永久化 hook 闭环。


---

## C · UI 真实渲染链反查（详情页按钮 / VIP tab / 新用户专享）

# 主题商店 UI 真实渲染链反查（详情页按钮 / VIP tab / 新用户专享）

> 背景：已 hook 的 `OnlinePreviewBaseActivity.isBuyBtnShow/isVipBtnShow/isFreeAccessBtnShow/isTrialBtnShow` 真机无效（详情页按钮没变）。本文件从 UI 现象反查真实链路，结论均为源码实证（file:line 相对 `system-files/theme-store-jadx/sources` 根）。
> 分析对象：主题_20.1.63.305.apk（com.hihonor.android.thememanager）。

## 0. 启示性结论（先看这个）

1. **旧 hook 无效的机制原因**：`isBuyBtnShow()` 不是"渲染开关"而是"入口门"。真流程＝门通过后读**服务端下发的按钮类型** `IncentiveData.getThemeDetailButtonType()` → `updateButtons()` switch 决定显示哪组按钮。hook 门常返 false 会**跳过 updateButtons/hideLayout**，按钮停留在 XML 初始 visible 态＝看起来"没变"。
2. **存在平行判定副本**：壁纸/挂件详情的购买门是 `ToolBarGroupLayout.needShowBuyButton()`（entity/ToolBarGroupLayout.java:489）与 `WallpaperDetailView.needShowBuyButton()`（detail/WallpaperDetailView.java:405）——与 `isBuyBtnShow` 逻辑几乎逐行相同但**是独立方法**，旧 hook 没覆盖。
3. **VIP 判定有两套读点**：`VipManager.isVipUser()`（静态布尔，详情页分支用）与 `VipState` **实例**字段（VIP tab 页、我的页 VIP 卡、续费入口都读实例 getter）。只 hook 静态布尔覆盖不全。

---

## 一、详情页按钮真实渲染链（【免费领】【购买】）

### 1.1 文案来源（全部是资源字符串，代码内无硬编码）

| 按钮文案 | 资源名/ID | jadx 引用处（setText 点） |
|---|---|---|
| 【免费领】 | `string/free_to_receive` = **0x7f1403b2** | `OnlinePreviewBaseActivity.java:2284`（freeReceiveBtn，主题详情）；`defpackage/e66.java:1818`（壁纸工具栏 h66 统一设置）；`incentive/view/TimeLimitedIncentiveSlideView.java:770,1067,1419`（激励滑窗）；`magicbaby/MagicBabyPreviewActivity.java:6902` |
| 【购买】 | `string/display_buy_new` = **0x7f1402c5** | `defpackage/e66.java:1756`（壁纸工具栏左按钮）；`defpackage/ob4.java:67` |
| 购买（带价） | `string/display_buy` = **0x7f1402c4** | `common/j.java:397-409`（菜单/价格文案拼装 getDisplayPay） |
| 【免费获取】 | `string/free_access` = **0x7f1403af** | `defpackage/e66.java:1788` |

资源命名的布局归属（APK 二进制 XML 扫描实证）：
- `res/layout/toolbar_layout_vip.xml` 同时引用 3 个按钮文案（free_to_receive / display_buy_new / free_access）＝**详情页付费工具栏本体**
- `new_online_list_item.xml`（列表项免费领）、`preview_info_q.xml`（详情信息区，被 `detail/DetailContainerView.java:150` 绑定）

### 1.2 渲染类与真实判定（主题详情＝OnlinePreviewBaseActivity 系）

详情页 Activity：`theme/OnlineThemePreviewActivity.java:251` extends `OnlinePreviewBaseActivity<T extends ItemInfo>`（ConsumerBaseActivity 链：detail/BasePreviewActivity ← logic/onlinepreview/OnlinePreviewSlotActivity ← OnlinePreviewBaseActivity）。

按钮状态刷新主链路（`OnlinePreviewBaseActivity.java`）：
1. **L3094-3103**（详情数据刷新回调内）：
   `if (isBuyBtnShow(mItemInfo)) { themeDetailButtonType = IncentiveCommonManager.INSTANCE.getIncentiveData().getThemeDetailButtonType(); updateButtons(duringIncentive, themeDetailButtonType, download); }`
2. **L5686 `updateButtons(boolean duringIncentive, int themeDetailButtonType, boolean download)`** switch 按钮类型：
   - `-1` → hideLayout()（**全部隐藏**，服务端激励数据失败时 `IncentiveCommonManager.java:636` 强制置 -1）
   - `0` → 免费领**小**按钮组显示（freeReceiveSmallView）
   - `1` → 免费领**大**按钮组显示（freeReceiveBigView）
   - `2/3/4/5` → **非 VIP**（`!VipManager.isVipUser()`）显示"免费领"按钮 + VIP 领取区；**是 VIP** 显示免费领小按钮组（L5769-5849…）
3. **L4109-4162 `initVipBtnsNormalState()`**：调用 `changeVipBtnsVisibility(z2=isBuyBtnShow&&!isAudit, isTrialBtnShow, isDownloadBtnShow, …, isVipBtnShow, isFreeAccessBtnShow, …)`（L4161）
4. **L2837-2924 `changeVipBtnsVisibility`** 最终落点：
   `mBuyBtn.setVisibility(buy && !isShowIconCustom && u02.isSupportPayed() && !isAudit ? 0 : 8)`（L2920-2924，【购买】唯一显隐控制）
5. 点击分流（L1480-1487）：`R.id.free_receive_button` 点击 → `if (!VipManager.isVipUser() || (buttonType != 4 && != 5))` → 常规免费领流程，否则 VIP 免费领取。

### 1.3 渲染类与真实判定（壁纸/挂件详情＝toolbar_layout_vip 链）

- `entity/ToolBarGroupLayout.java`（工具栏 Layout，onClick 含 R.id.apply_button/free_use/vip_button 等）：
  - **L489 `needShowBuyButton(WallPaperInfo)`**：`actualPrice<=0 || getPayed() || （VIP 场景不满足免单条件）` → false；否则 true ＝【购买】显示总门
  - L919 `updateButtons(WallPaperInfo, Boolean)`：门过后同样读 `getThemeDetailButtonType()` switch（结构同 1.2），再调 `freeReceiveSmallLayout()` / setVipBtnVisibility / setFreeUseButvisiblity
- `detail/WallpaperDetailView.java`：**L405 needShowBuyButton**（同构副本）、L717 updateButtons（同构）
- 文案装配在 `defpackage/e66.java`（WallPaperHelper，t52.info("WallPaperHelper")）：L1756 购买按钮；L1785-1790 免费获取；L1818 免费领；L1821 统一 `updateButtons(wallPaperInfo, bool)`；L1828 `initVipBtn`（VIP 按钮 showVipBtn/isRunning/isNeedOrder 判定 + `R.string.vip_free_use`）

### 1.4 数据从哪来（服务端按钮类型）

`incentive/IncentiveCommonManager.queryIncentiveRes()`（L614-644）：请求激励数据 → `IncentiveData`（incentive/IncentiveData.java:142 getThemeDetailButtonType）→ `themeDetailButtonType.postValue(...)`（LiveData）→ 失败 `setThemeDetailButtonType(-1)`（L636）。`ButtonType.java` 只是该 int 的 Kotlin data 壳。**按钮类型＝服务端配额/云控决定，本地价格只决定"门"**。

---

## 二、VIP tab 开通页判定链

- 入口路由：`common/Const.java:466 ACTIVITY_URL_VIP_CENTER = "/theme/VipMemberCenterActivity"`（我的页 VIP 卡点击同路由）。
- `vip/ui/VipMemberCenterActivity.java` 只是壳（initView L526-554：FrameLayout `R.id.vip_fragment` 承载 `VipCenterFragment` 并传 VIP_ID bundle）。
- **页面本体 `vip/ui/VipCenterFragment.java`**（VIP 套餐中心，adapter 多 provider 渲染）：
  - `VipCenterModel.loadVipCenterData()` 拉服务端 VipState + 套餐（`vip/model/VipCenterModel.java:427` 起；`loadNetVipData` L363 附近）→ **`vipCenterData.setVipState(vipState)`（VipCenterModel.java:714）** → 账户卡/套餐 item 携带 VipState
  - `onModel$lambda$9`（VipCenterFragment.java:623-630）：`acountVipType = vipState.getVipType()`（**已开通/未开通的页面级判定字段**）
  - `onModel$lambda$7`（574-620）：套餐 records 非空 → 显示"我的 VIP"横条（binding.C.C visible）；空 → 隐藏＝全开通引导（"开通 VIP"）
  - `onModel$lambda$3`（545-551）：`VipCenterData.getVipItemType()==3` → 显示套餐购买卡（showVipBuy L780）
- `VipMemberCenterActivity.initView$lambda$6`（274-283）：续费入口 = `vipState.isVipUser() && vipState.getVipType()==2`（连续包月）。
- VipState 实例数据来源：`vip/model/VipModel.java`（服务端 VipStateReq，已购分析与 L142+ 一致）→ `VipManager.setVipState`。

---

## 三、"新用户专享 VIP"提示判定链（任务三）

- 文案：`string/vip_not_open_title` = **0x7f140b50**，zh-rCN "新用户专享"（资源名直译＝"VIP 未开通标题"）。Java 代码无引用（R.java:24599 仅常量），**二进制 XML 扫描唯一命中 `res/layout/layout_mine_vip_card_1_not_vip.xml`** → ViewBinding `LayoutMineVipCard1NotVipBinding`。
- 渲染类：**`logic/mine/widget/MineVipCardView.java`**（1278 行 Kotlin，"我的"页 VIP 卡）：
  - `getVipCardType(VipState)` **L1880-1904**：`switch (vipState.getVipStatus())` → **1=NOT_VIP**（default 也 NOT_VIP）；2=SINGLE_WITH_SAVE；3=SINGLE_NO_SAVE；4/5=CONTINUE_*；6/7=SINGLE_15D_*；8/9=EXPIRED_*（共 9 卡型）
  - `setDatanew(vipState)` L1932-1942 → `renderCard`（L538-590 按卡型 inflate 九布局之一）→ `bindNotVipCard`（L395-401：`vip_not_open_save_amount_text`/首月价）
  - NOT_VIP 卡点击 → `jumpToVipPage()`（L1916）→ `/theme/VipMemberCenterActivity`（开通引导闭环）
- 数据源：`logic/mine/viewmodel/VipCardViewModel.java`（observe VipState → setDatanew）。
- 判定本质：**`VipState.getVipStatus()==1` 即显示"新用户专享"开通引导卡**。

---

## 四、新旧对照表（旧 hook 点 vs 新关键点）

| 维度 | 旧 hook 点（静态分析，真机无效/半效） | 新关键点（真实链路） |
|---|---|---|
| 购买按钮门 | `OnlinePreviewBaseActivity.isBuyBtnShow`（4158/3097 被调用，但只是"门"；hook 常返 false 反而跳过 updateButtons，按钮停在 XML 默认可见） | `entity/ToolBarGroupLayout.needShowBuyButton`(:489)、`detail/WallpaperDetailView.needShowBuyButton`(:405)（**平行独立副本**）；`ItemInfo.isNeedPayItem`/`getPayed`（价格门） |
| 按钮组最终显隐 | `isVipBtnShow/isFreeAccessBtnShow/isTrialBtnShow`（只喂 changeVipBtnsVisibility 的布尔参数，不是最终仲裁） | **`incentive/IncentiveData.getThemeDetailButtonType()`（服务端下发，updateButtons switch 主控）**；`OnlinePreviewBaseActivity.updateButtons`(:5686)/`ToolBarGroupLayout.updateButtons`(:919)/`WallpaperDetailView.updateButtons`(:717) |
| 详情/列表按钮文案 | — | `R.string.free_to_receive`(0x7f1403b2) 引用处：OnlinePreviewBaseActivity:2284、e66:1818、MagicBabyPreviewActivity:6902 |
| VIP 已开通判定 | `VipManager.isVipUser()`（静态布尔，只覆盖详情按钮分支） | **`VipState` 实例 getter**：`getVipStatus()`（我的页卡型）、`getVipType()`（VipCenterFragment:623-630 / VipMemberCenterActivity:274-283）、`isVipUser()`（续费入口）；VipCenterModel.loadNetVipData→setVipState（vipCenterData 携带态） |
| 新用户专享提示 | 无（未覆盖） | `MineVipCardView.getVipCardType`（vipStatus==1 → NOT_VIP 卡） |

---

## 五、新 hook 点清单（类/方法/建议）

| # | 类 | 方法/字段 | 签名 | 建议改法 | 覆盖现象 |
|---|---|---|---|---|---|
| T1 | incentive.IncentiveData | `getThemeDetailButtonType` | `()I` | 常返 **-1**（无激励，只隐藏激励视图，保留下载/应用/购买） | 详情页按钮组显示 |
| T2 | entity.ToolBarGroupLayout | `needShowBuyButton` | `(LWallPaperInfo;)Z` | **常返 false**（购买门直接关，走免费/下载分支） | 壁纸/挂件详情【购买】 |
| T3 | detail.WallpaperDetailView | `needShowBuyButton` | `(LWallPaperInfo;)Z` | 常返 false（同 T2 副本） | 壁纸详情弹窗【购买】 |
| T4 | common.ItemInfo | `isNeedPayItem` / `getPayed` | `()Z` | isNeedPayItem 常返 false + getPayed 常返 true（列表/详情价格门） | 列表与详情所有收费态 |
| T5 | vip.data.VipState | `getVipStatus` | `()I` | 常返 **2**（SINGLE_WITH_SAVE，会员态） | 我的页"新用户专享"卡消失、显示会员卡 |
| T6 | vip.data.VipState | `getVipType` | `()Ljava/lang/Integer;` | 常返 2（连续包月，激活续费入口/账户态） | VIP tab 页识别已开通 |
| T7 | vip.data.VipState | `isVipUser` | `()Z` | 常返 true（配合 T6） | VIP 中心/详情 VIP 分支 |
| T8 | logic.mine.widget.MineVipCardView | `getVipCardType` | `(Lvip/data/VipState;)L…VipCardType;` | 常返 `SINGLE_WITH_SAVE`（最直接，兜底 T5） | 新用户专享卡 |
| T9 | vip.manager.VipManager | `isVipUser`（companion 静态） | `()Z` | 常返 true（保留，覆盖详情按钮分支 5769+） | 详情页 VIP 领取区 |
| T10 | OnlinePreviewBaseActivity | `updateButtons` | `(ZIZ)V` | 可选：强制改调用为 (false, 0, download)，绕过服务端类型 | 主题详情兜底 |

**组合建议（最小集）**：T1 + T2 + T4 + T5（或 T8）＋ T9。T2/T3 二选一按真机进入壁纸还是主题详情，建议都上（方法短、无副作用）。

**警告**：T1 必须返回 -1（详见 §F2 T1 更正记录——旧版"−1 会隐藏全部按钮"是误读，实际只隐藏激励视图）；hook `isBuyBtnShow` 常返 false 的旧做法会使 updateButtons 不执行、按钮停留初始态，**不要再单独用**（可配合 T2 用常返 true 保持门开着）。

---

## 六、未确定项（如实标注）

1. 主题详情页 `mBottomBtnRoot`（旧式按钮区）与 `toolbar_layout_vip`（新式）是否并存于同一页面——OnlinePreviewBaseActivity 成员 mBuyBtn/freeReceiveBtn 属旧区，ToolBarGroupLayout 属新区；主题详情走旧区（1.2 链），壁纸详情走新区（1.3 链），但 20.1.63.305 版本界面可能混用，建议真机 LSPosed 日志确认实际进的是哪条（`t52.error(TAG, "duringIncentive = ...")` 日志键）。
2. `VipCenterFragment` 的"账户横条"渲染 provider 未逐行读完（VipPackagesItemProvider 读 VipState 字段处未列行号），但绑定入口（onModel$lambda$7/9）已实证。
3. 服务端激励数据（themeDetailButtonType）具体下发接口与失败时本地缓存策略未深挖（IncentiveData 载荷在 `incentive/IncentiveData.java`，解析 bean 未读全）。
4. VIP tab 是否还存在服务端下单风控（VipOrderReq 签名）——hook 只改展示层，支付按钮走 IAP 不受影响（与上版结论一致）。
5. 【免费领】按钮在列表页（new_online_list_item.xml）的判定在 `logic/rank/ui/NewResListAdapter.java:119`（列表自有 isBuyBtnShow + duringIncentive），如需列表全免费需一并 hook `ItemInfo.duringIncentive()` 常返 true。

---

## 现象四：VIP 已过期挽留弹窗（"VIP已过期，立即行动，重享尊贵体验"）

> 追加日期：2026-10-09。真机在 `fetchShouldDialogByDefault / fetchShouldDialogByCloud → false` 已挂载后仍弹出该提示，反查确认它是**独立触发链**（整体挽留弹窗 MEMBER_EXPIRE 型），与之前分析的两个弹窗判定（VipCheckRepo 系）毫无关系。

### 4.0 结论先行

1. **为什么旧 hook 拦不住**：`fetchShouldDialogBy{Default,Cloud}` 的完整消费链是 `VipCheckRepo.getShouldDialog` → `VipCheckVM.refreshShouldDialog` → `HwThemeManagerActivity.showVipCheckDialog`（"VIP 续订提醒"AlertDialog，dialog_vip_check 布局）。这条链在 20.1.63.305 里是**休眠代码**：`refreshShouldDialog` 与 `showVipCheckDialog` 除定义外**全工程零调用方**（grep 实证），`VipCheckVM.shouldDialogRes` 无任何 observer。hook 拦的是一个根本不触发的弹窗。
2. 真机上弹的是 **`overallretentiondialog`（整体挽留弹窗）子系统的 MEMBER_EXPIRE 型**，服务端推送的 **pushType 代码**就是开关（VIP_ARRIVED=4 与 OverallRetentionDialogType.MEMBER_EXPIRE=4 一对一），本地 VIP 状态读取的是一批**独立存储字段**（`VipPopState.isVipUser/isFreeUseRes`、`VipState.isVipUser`），全部**不由已 hook 的 `VipState.getVipStatus()`（现返 2）派生**——所以 T5 hook 值没错、是路径完全没走它。
3. 拦截点应放在**展示咽喉**：`OverallRetentionDialogManager.showDialog`（F1，一条 hook 覆盖全部 6 型挽留弹窗）或 `OverallRetentionDialogActivity.onCreate → finish()`（F2 兜底）。

### 4.1 文案来源（本地资源 + 服务端拼装）

**"VIP已过期，立即行动，重享尊贵体验" 整句在主题商店 APK 与主题引擎 APK（com.hihonor.mocoolengine）的全部资源池中均无逐字命中**（aapt2 全量 dump 搜索 立即行动/尊贵/重享/尊享/过期 零命中；引擎仅 111 条资源）→ **判定为服务端下发文案**（PushData → PopupReq/PopupResp 载荷，经 `PopupConfig` 装配），真机显示为标题/正文/按钮多段拼合（含用户转述差异）。本地最近邻资源对照（MEMBER_EXPIRE 型，均在 `BuildAndShowDialog.java`）：

| 段 | 资源名 | ID | zh-rCN | 装配点（jadx 行） |
|---|---|---|---|---|
| 标题 | `overall_retention_vip_has_expired` | 0x7f140727 | 主题 VIP 已到期 | `setTitleResId` :690 |
| 正文（无免费资源在用） | `overall_retention_privileges_lost_renewal_resume` | 0x7f14071b | 已失去全部会员特权，续费立即恢复 | `setSubTitleData` :382 |
| 正文（有免费资源在用） | `overall_retention_privileges_lost_resources_expire` | 0x7f14071c | 已失去全部会员特权，正在使用的%s会员资源即将失效 | :368 |
| 主按钮（三选一，带服务端价格） | `overall_retention_activate_vip` / `low_renewal` / `limited_renewal` | 0x7f14070a / 0x7f14071a / 0x7f14070e | 开通主题 VIP / 低至 %s 续费会员 / 限时 %s 续费会员 | `setMainButtonConfig` :544-571 |
| 关闭 | `overall_retention_close` | 0x7f14070c | Cancel（default） | `setThirdButtonConfig` :606 |

同族 6 型枚举：`OverallRetentionDialogType.java:7-13` RESOURCE_TRIAL_END=1 / MEMBER_SOON_EXPIRE=2 / EXPIRED_RESOURCES=3 / **MEMBER_EXPIRE=4** / EXPIRED_SPRITE=5 / MEMBER_LOGOUT=6。服务端 popupName 只有 TrialEnded/MemberExpiringSoon 型当标题（`setTitleData` :622-646）；MEMBER_EXPIRE 标题固定走资源。

### 4.2 展示形态与展示类

- **Activity 弹窗**（非 AlertDialog/DialogFragment）：`overallretentiondialog/ui/OverallRetentionDialogActivity.java`（FragmentActivity，ARouter `@Route("/theme/OverallRetentionDialogActivity")`，`common/Const.java:337`）。
- 入场唯一入口：`OverallRetentionDialogManager.showDialog(config)`（:150-163）→ ARouter 带 Parcelable `DIALOG_PARAMS`（OverallRetentionDialogConfig）+ `DIALOG_FROM`。
- 渲染：`parseParameterSettingsUI()`（:969-1013）装配 TitleComponent / ImagesComponent / CountdownComponent / ButtonComponent（ui/ 目录）；副标题按 `config.getSubTitleResIdList()`（`subTitleVisibility` :1078-1082）；按钮点击走 `ActionType`（GO_TO_MEMBER 开通页 / GO_TO_RESOURCE / OPEN_H5_PAGE / CLOSE，`ButtonComponent$clickAction$N`）。
- 真机验证日志键：`OverallRetentionDialogActivity` "dialogParams = ..."（showDialog）；`BuildAndShowDialog` "dialogConfig = ..."；`VipPopCfgVM` "updateVipCfg: sendDialogData: ..."。

### 4.3 触发判定链（pushType 代码驱动，服务端是开关）

1. **PushData 三个来源**：
   - 荣耀推送消息：`push/HonorMsgUtil.handleArrivedPushMsg`（:312/:326 `new PushData(..., PushType.fromCode(popupType), ...)` → `savePushData`），popupType 即 PushType.code；
   - App 启动扫库：`HwThemeManagerActivity` onResume 后延时 300ms（生命周期 RESUMED 门）→ `ArrivedPushManager.checkArrivedTheme()`（HwThemeManagerActivity.java:411 区域）；
   - 试用到期 fence：`mvp/model/helper/tryout/RecycleTrialTheme` / `TryOutTimeFenceService` → `trialSavePushData`（ArrivedPushManager.java:6810，pushType=TRIAL_ARRIVED）。
2. **排队与展示**：`ArrivedPushManager.checkPushWithRoom`（9 处 `VipPopCfgVM.INSTANCE.getData(pushData)` 调点：:3598/3716/3869/4030/4193/4354/4472/4611/4737；日志键 "dialogSupportShow = ... dialogPushType = ..."）→ `VipPopCfgVM.getData`（:942-953）：SPRITE_ARRIVED → `updateSpriteDialog`，其余 → `updateVipCfg`。
3. **类型映射**：`updateVipCfg`（VipPopCfgVM.java:937；方法体 2832 条指令 jadx 未反编译，见 4.6）内按 pushType 定类型。佐证：PushType（ArrivedPushManager.java:177-179 TRIAL_ARRIVED=1/VIP_WILL_ARRIVED=2/LIMIT_ARRIVED=3/VIP_ARRIVED=4/SPRITE_ARRIVED=5）与 OverallRetentionDialogType.type **code 完全一致**，且 updateSpriteDialog 内同构循环实证 `t.getType() == pushData.getPushType().getCode()`（C21662 :456-461）→ **VIP_ARRIVED=4 → MEMBER_EXPIRE=4**。→ `typeMap`（VipPopCfgVM.java:98）→ `DialogCfgType.MemberExpired(vipPopState)`（`VipPopCfgVM$typeMap$4.java`）。
4. **装配与展示**：`VipPopCfgVM` 静态块执行 `BuildAndShowDialog.INSTANCE.listenerData()`（:652）注册 GetResultInterface → `BuildAndShowDialog$AnonymousClass1.getResult`（:83-98）→ `buildData`（:105-131）→ `OverallRetentionDialogManager.showDialog`（:72）→ ARouter → Activity。
5. updateVipCfg 同时拉取：服务端 popup 配置（PopupConfig：popupName/按钮文本/链接/价格/背景图）、VipState（`VipModel().vipState(...)` :997-1003）、会员价格/到期天数 → 写入 `VipPopState`（**全部独立存储字段**，VipPopState.java:31-74）。

### 4.4 为什么已挂 hook 拦不住

| 断言 | 实证 |
|---|---|
| fetchShouldDialogBy* 消费链休眠 | `grep -rn "refreshShouldDialog\|showVipCheckDialog"` 除定义外零调用方；`getShouldDialogRes` 无 observer（HwThemeManagerActivity 18 个 observe 里没有它） |
| 真机弹窗不经 VipCheckRepo 任何方法 | 展示链唯一出口 `OverallRetentionDialogManager.showDialog`，上游是 ArrivedPushManager/VipPopCfgVM/BuildAndShowDialog，与 vip/repository/ 无引用关系 |
| 链上判定字段与已 hook 的 getVipStatus 无派生关系 | `VipState.isVipUser()` 是独立 boolean 字段（VipState.java:37,355-356）；`VipPopState.isVipUser/isFreeUseRes/isAllExpiredResourcesNotApplied` 全是 set 进去的字段（VipPopState.java:31-74）；BuildAndShowDialog 通篇不读 getVipStatus |
| 结论 | T5（getVipStatus→2）只作用于我的页 VIP 卡卡型（现象三），对本弹窗值没有错、是**路径不同** |

### 4.5 新 hook 点（F 归组）

| # | 类 | 方法 | 签名 | 建议改法 | 覆盖现象 | 备注 |
|---|---|---|---|---|---|---|
| **F1** | overallretentiondialog.ui.OverallRetentionDialogActivity$OverallRetentionDialogManager | `showDialog` | `(L…data/OverallRetentionDialogConfig;)V` | **空实现**（首选，公共咽喉） | 全部 6 型挽留/试用/登出弹窗（含真机 MEMBER_EXPIRE） | 单点全覆盖；保留埋点可取回显逻辑 |
| **F2** | overallretentiondialog.ui.OverallRetentionDialogActivity | `onCreate` | `(Landroid/os/Bundle;)V` | 首行 `finish(); return;` | 同上 | 兜底防 ARouter 直启绕过 F1；与 F1 并行最稳 |
| F3 | overallretentiondialog.data.VipPopCfgVM | `getData` | `(L…data/PushData;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;` | 常返 `Pair(false, false)` | 弹窗喂料源头 | checkPushWithRoom 收 first=false 会 `removeBySaveTime` 清掉该条积压 push（:3621）；注意对 TRIAL_ARRIVED 同时触发 doResRecycle（试用资源回收），不想触发回收就只用 F1/F2 |
| F4 | overallretentiondialog.util.BuildAndShowDialog$AnonymousClass1 | `getResult` | `(L…data/DialogCfgType;)V` | 不调 buildData/showDialog（可保留 reportReceiveData 埋点） | 装配前拦截 | 可在此加日志确认 dialogType 后再拦 |
| F5（可选） | push.HonorMsgUtil | `handleArrivedPushMsg` | （HonorMsgUtil.kt，生成 PushData 处） | 跳过 savePushData | 服务端推送源头 | 防新 push 入库；已入库数据仍需 F1/F2 兜 |

**组合建议（最小集）**：F1 一条即拦下真机弹窗；怕有旁路加 F2。**不要只依赖 T5 或任何 VipState 系列 hook 拦本弹窗——该链不读取它们**。

### 4.6 未确定项（如实标注）

1. `updateVipCfg` 方法体（`VipPopCfgVM.C21672.invokeSuspend`，2832 指令）被 jadx 跳过且本机无 JDK11+（jadx 需要 class v55）/无 baksmali CLI，未逐行实证；PushType→OverallRetentionDialogType 映射为"枚举 code 完全一致 + updateSpriteDialog 同构循环"双重佐证，置信高但非逐行证据。若要确证：换 JDK11+ 后 `jadx --comments-level debug classes18.dex` 或解包 apktool.jar 内 baksmali 手工反编译。
2. popupType=4（VIP_ARRIVED→MEMBER_EXPIRE）在真机的具体推送来源（荣耀推送消息 vs 服务端弹层配置）未抓包；判别日志键见 4.2。
3. "立即行动，重享尊贵体验"作为服务端文案的具体下发字段（popupName / 按钮文本）未抓包；两 APK 资源池已穷尽扫描，本地代码无此文案。


---

## D · 云控开关全表（AgreeRepo ~30 项 + 散读 key）

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

> 相关：美豆/福利中心链路的完整分析见 `docs/theme-store.md §九`。
> 包管理器（软件包安装程序）的云控机制不同（CommonConfig + AB 实验，无布尔表），见 `docs/reverse/installer.md`。

---

## E · 广告变现链路（美豆 / 福利中心）拦截细节（原主文档 §九）

## 九、美豆 / 福利中心（广告变现链路）拦截

**两条独立机制**（云控开关管"本地 UI 入口"，槽位裁剪管"服务端下发的入口"）：

| 层 | 管什么 | 实现 | 小节 |
|---|---|---|---|
| 云控布尔表 | 本地代码里的开关（免费领按钮、会员卡入口、广告总闸…） | `CloudSwitchTable` + `u15` 读点 | 9.2 / 9.4 |
| 服务端槽位裁剪 | 服务端下发的槽位（我的页腰封、搜索框两侧、灯笼位内嵌免费领、福利中心插件） | `AdSlotFilter` + `SlotData.getList()` 出口 | **9.5** |

### 9.1 这是什么东西

「美豆」在 APK 里的英文串是 **bonus**（`%s bonus to get this theme` / `消耗 %d 美豆永久兑换`），
内部模型叫 **金币 GoldCoin**（`theme/goldcoins/bean/GoldCoinInfo`）。它是**广告变现链路**：

> 看视频广告 / 调应用市场下载广告 App（激励任务）→ 赚美豆 → 兑换主题

「福利中心」是**H5 页**（`IncentiveCommonManager.getWelfareCenterH5Url()`，
经 `startToWelfareCenterActivity(isAdOpenChecked, contentId, contentType)` 打开）——**签到 / 免费领
都在这个 H5 内**，所以 APK 资源里搜不到「福利中心 / 签到」中文字串（服务端下发）。广告加载走
`ads/loader/PictureTextLoader$loadAdForWelfareCenter`。

美豆本体的宿主是 `com.hihonor.android.thememanager.incentive`（65 个类）：
`IncentiveVideoTaskInfo`（看视频）/ `IncentiveDownloadTaskInfo`（下载 App）/ `IncentiveGameTaskInfo`
（玩游戏）→ 赚币；`TimeLimitedIncentiveSlideView`（美豆任务滑窗）、`FreeReceiveBottomSheetDialogFragment`
（免费领弹层）。

### 9.2 云控总开关（用户所指的"总的一堆开关"）

云控开关集中在 **`com.hihonor.android.thememanager.ams.mgr.AgreeRepo`**（45 个 boolean 方法，读
`u15.getBoolean(key, default)`），key 常量定义在 `common/i.java`，由 AMS systemParam 下发（前缀
`10001000`，见 `hitop/entity/SystemRequestParams`）。与美豆相关的三个：

| AgreeRepo 方法 | 云控 key | 默认 | 控制什么 | 调用方（UI） |
|---|---|---|---|---|
| `isOpenFreeClaimSwitch()` | `free_claim_show_value` | **true** | 「免费领」入口 | `NewRankListFragment:485` → `NewResListAdapter` |
| `isMemeberCardSupport()`（原文拼写如此） | `member_card_support` | false | 「福利中心 / 会员卡」入口 | `LocalMineFragment:2886`（"我的"页） |
| `isOpenMemberSelection()` | `MEMBER_SELECTION` | false | 「会员精选」 | `NewRankTabFragment:345` / `RankSource:1345` / `SubTabBaseActivity:330` |

### 9.3 详情页「免费领」按钮

详情页激励按钮组由 `IncentiveData.getThemeDetailButtonType()` 主控（T1，见 §三）。
`0`/`1` 直接显示「免费领」视图，`2/3/4/5` 在 VIP 态也落到 `freeReceiveSmallView`——
**任何中段值都会显示美豆入口**。故 **T1 → `-1`**：`hideLayout()` 只隐藏激励视图
（`freeReceiveSmall/BigView`/`freeReceiveBtn`/`vipReceiveSmall/BigView`/`timeLimitDownloadBtn`），
下载/应用/购买按钮不受影响。**不再用 `changeVipBtnsVisibility` 单独藏按钮**（该点已拆除，见 §五）。

### 9.4 实现：**表驱动 · 统一读点**（原逐方法 hook 已拆除）

**关键事实（决定了实现方式）**：主题商店**所有**云控布尔开关最终都由 `SharepreferenceUtils`
（默认包混淆类 **`u15`**）从 SP `themename` 读出——`getBoolean(String, boolean)` 与 `getBoolean(String)`
两个重载就是**唯一收口**（83 处读点全走它；除它之外没有任何代码直读这批 key，其余 `getBoolean`
来自 `ads_mobile_sdk` / `androidx`）。

因此不做"每个开关挂一个方法"，而是**挂两个读点 + 一张表**：

| 层 | 位置 | 职责 |
|---|---|---|
| 数据（唯一改动点） | `xposed/CloudSwitchTable.kt` | 声明 `key → 强制值`（12 个 false + 3 个 true） |
| 逻辑（通用，无 key） | `ThemeStoreHook.collectCloudSwitchHooks` + `MountKind.KEY_OVERRIDE` | 读 `chain.getArg(0)` 作 key 查表：命中→短路返回强制值；未命中→放行原逻辑 |

**收益**：
- 新增/删除开关**只改表文件**，hook 代码零改动；
- 同一 key 只有**一个**拦截点（不再"方法级 + 读点级"并存）；
- 版本升级若新增读点，**自动被覆盖**（挂在收口上）。

**被本机制取代而删除的 4 个旧挂载点**：`AgreeRepo.isOpenFreeClaimSwitch` / `isMemeberCardSupport` /
`isOpenMemberSelection`（三者的 key 已在表中）与 `AgreeRepo.isTrialSupportOnline`（key
`trial_support_online` 已迁入表，值不变）。挂载表 **41 → 39**（−4 方法级 +2 读点），
真机实证 `ThemeStoreHook: installed 39/39` + `cloud-switch 强制: local_has_signed → true`。

**表内容与 key 读点明细**见 `docs/reverse/theme-store.md §四`。

> 原则：**关云控总开关**（根源）优先于逐 UI 隐藏。开关一关，H5 入口、签到、任务、推荐位一并消失。
> 未挂的点：`IncentiveCommonManager.startToWelfareCenterActivity`（H5 直启）——若关开关后仍有残留
> 入口深链，再补此拦截点（如同挽留弹窗的 ARouter 直启兜底）。

### 9.5 服务端槽位源头裁剪（S1，2026-10-10）

**背景**：关掉 9.4 的云控开关后，「我的」页腰封「我的页面腰封-福利中心」与推荐页搜索框左侧
「免费领」**仍然在**——因为这两个入口**不是本地 UI、也不受任何云控布尔 key 管**，而是**服务端下发的"槽位"**。

**数据从哪来（真机载荷实证）**

| 入口 | 接口 | 响应里的位置 |
|---|---|---|
| 我的页腰封 | `POST themeServer/themeBiz/slot`（`MineApi.getSlotInfo`，`appPage=1800`） | `SlotList{slotType="5"(腰封), title=我的页面腰封-福利中心, coinsActivityFlag=1}` → `Slot{resType/targetType=1019, targetId=1019001, url=hnt://…immersveUrl=https://h5-marketing-drcn.platform.hihonorcloud.com/act}` |
| 推荐页搜索框左 | `POST themeServer/themeBiz/searchSlot`（`RecommendApi.getSearchSlotInfo`） | `SlotList{slotType="22"(搜索·左), title=25福利中心左-手机}` |
| 推荐页搜索框右 | 同上 | `SlotList{slotType="20"(搜索·右), title=24耀宝家园二期}` |
| 推荐页灯笼位内「免费领」 | `POST themeServer/themeBiz/slot`（`appPage=2957`） | `SlotList{slotType="2"(灯笼位)}` 的 8 个 `Slot` 中第 1 个：`title=免费领, resType/targetType=1019, targetId=1019001` |
| 福利中心 H5 插件 | 同上 | `SlotList{slotType="27"(福利中心插件), title=福利中心}` → `Slot{targetId=1019002}` |

- 响应经 Retrofit/Gson 直接反序列化为 `hitop.mate.SlotData`；**域模型出口**是 `SlotData.getList(): List<SlotList>`，
  所有消费方（`MineModel` / `RecommendModel` / `RecommendRootFM` / 各 Provider）都从它取数。
- **请求体只有 `appPage`**（1800 我的 / 2957 推荐 / 2100 搜索框）等参数，**没有"关掉某一槽位"的开关**；
  `common/i.java` 里也没有 welfare/waist/slot 相关 key ⇒ 云控表覆盖不到，只能在**载荷进入域模型之前**按内容裁。
- 槽位类型字符串与 `hitop/mate/SlotList.java` 的命名常量对应（`TYPE_WAIST=6`/`TYPE_SEARCH_LAYOUT=20`/
  `TYPE_SEARCH_LEFT_LAYOUT=22`/`TYPE_WELFARE_CENTER=25`/`TYPE_WELFARE_PLUGIN=27`；`onSlotIntType(6)=="5"`
  印证线上字符串 "5" 即腰封）；`RecommendFM.checkHasLanternWithWelfareCenter` 亦以 `targetId=="1019001"`
  判定"灯笼位里夹了福利中心"——与实测载荷一致。

**实现（挂载点 S1）**

| 层 | 位置 | 职责 |
|---|---|---|
| 数据（唯一改动点） | `xposed/AdSlotFilter.kt` | `PRUNED_SLOT_TYPES`（整体丢弃的 `SlotList` 类型：5/20/22/25/27）+ `PRUNED_TARGET_IDS`（只删单个 `Slot` 的 `targetId`：1019001/1019002） |
| 逻辑（通用，无硬编码） | `ThemeStoreHook.collectAdSlotPruneHooks` + `MountKind.SLOT_PRUNE` | 钩 `SlotData.getList()`：proceed 拿载荷 → 命中表则剔除 → 返回裁剪后的表；未命中/反射失败**原样放行**（PROTECTIVE） |

**为什么不做成"逐消费方拦"**：`MineModel.findFirstValidSlotListByType`、`countSlotListFilter`、
`RecommendRootFM.onSearchSlotLeft`、`filterGoldCoinsLinkInAmGroup` 这些都是**已经解析完之后**的收集/渲染环节
（旧方案 W1/W2 就是挂在这两个上，属"丢已解析数据"而非源头）。挂在 `SlotData.getList()` 出口，一处覆盖
我的页腰封 / 推荐页搜索框左右 / 灯笼位内嵌免费领 / 福利中心插件，且新版本新增消费方自动被覆盖。

**真机判据（2026-10-10）**：`installed 41/41`，日志出现
`美豆槽位已从服务端载荷剔除: slotList=5 title=我的页面腰封-福利中心` /
`slot=1019001 title=免费领` / `slotList=27 title=福利中心` /
`slotList=22 title=25福利中心左-手机` / `slotList=20 title=24耀宝家园二期`；
`uiautomator` 复核：推荐页 `search_slot_left`/`search_iv_left` 节点消失（`search_layout` 左边界由 252→63 补位）、
我的页 `content-desc=我的页面腰封-福利中心` 节点与 `rv_recommend_resource_middle` 容器一并消失，
其余入口（我的购买/下载/收藏/铃声/官方预置主题、VIP 到期 2088/8/8）无损伤。

> **观察项（未裁）**：同为美豆/耀宝营销但类型通用的槽位——`slotType=13`(开屏) / `14`(悬浮球) / `15`(精选页弹窗)，
> 载荷内多为 `inner=0`（无内容）或指向 `h5-marketing…` 活动页。这三类容器也承载**非广告**内容（功能悬浮球/活动弹窗），
> 整体丢弃会误伤；当前真机 UI 未观察到它们渲染出美豆文案，故**不纳入裁剪**，需要时按 `targetId`/`targetUrl` 内容再收紧。

### 9.6 底部导航 tab 源头裁剪（S2，2026-10-10）

**问题**：VIP tab 点进去是 `vip.ui.VipMemberCenterActivity`（开通/购买页），底部主按钮固定显示「立即开通 ¥18」。

**溯源（真机现场 + jadx 实证）**：该按钮唯一的赋值点是
`VipCenterFragment.showVipActivatePrice(VipMeal):766` ——
`hwButton.setText(String.format(getString(R.string.vip_packages_activate), j.getVIPShowPrice(price, symbol)))`；
资源实值（`aapt2 dump resources`，`0x7f140b54`）为 **"Activate now with %1$s"**（中文「立即开通 %s」）。
触发条件**只有** `vipMealOrder != null`（服务端 `vipMealListData.data.records` 非空），**不读任何 VIP 状态**；
全工程也没有"续费"文案变体 ⇒ 它是这个购买页的固有按钮，与用户是不是 VIP 无关。
真机 hook 日志印证：`VipMemberCenterActivity#initView$lambda$6` 入参已是
`vipStatus=2, vipType=2, isVipUser=true`（页面自己知道是会员，右上「续费管理」入口亮着），按钮文案照旧。
F1 的 VipState 漏斗碰不到它——它在 `VipCenterFragment` 里，走 `VipCenterModel` + `VipMealListRspData` 套餐链，
与 `VipModel.changeToVipState` 是**平行的两条路**。⇒ 用户决定：**直接从底部导航删掉 VIP tab**。

**数据从哪来（源头链）**：
`AMS systemParam 在线配置 JSON` → Gson → `hitop.entity.BottomTabConfigs`
→ `SystemParamSource.countBottomTabConfig()`（内部已按 `isSupportVip` 过滤 VIP 项）
→ `HwThemeManagerModel.getBottomTabConfig(): StateFlow<List<BottomTabItem>>`
→ `HwThemeManagerActivity.createTabs()` 建底部 tab ＋ `doSupportOnline()` 建 Fragment 页面。
读 `BottomTabConfigs` 的**唯一出口 = `getBottomTabConfigs()`**（全工程仅 `SystemParamSource.java:208` 一处调用）。

**实现（挂载点 S2）**

| 层 | 位置 | 职责 |
|---|---|---|
| 数据（唯一改动点） | `xposed/BottomTabFilter.kt` | `PRUNED_TABS`：`bottomTabId ∈ {3 VIP, 12 WELFARE「免费拿」}`（`MenuType` dex 实证：RECOMMEND=1 CATEGORY=2 VIP=3 RANKING=4 MINE=5 RANKING_CATEGORY=11 WELFARE=12） |
| 逻辑（通用，无硬编码） | `ThemeStoreHook.collectBottomTabPruneHooks` + `MountKind.BOTTOM_TAB_PRUNE` | 钩 `BottomTabConfigs.getBottomTabConfigs()`：proceed 拿列表 → 命中表则剔除 → 返回裁剪后的表；未命中/反射失败**原样放行**（PROTECTIVE） |

**为什么不关云控总闸 `AgreeRepo.isSupportVip()`**（= key `vip_support`，`common/i.java:158` 的 `THEME_SUPPORT_VIP`，
默认值本就是 false、线上由服务端下发 true）：它确实是 VIP tab 的总闸
（`createTabs()` 里 `if (zIsSupportVip) createActionTab("VIP", …)`），但全工程有 **20+ 处**独立消费点——
详情页按钮组 `ToolBarGroupLayout:492` / `WallpaperDetailView:408`、字体预览 `setVipArea(OnlineFontPreviewActivity:1577)`、
推荐流卡片副文案（`MateFlowAdapter:441` / `MateCrossProvider:346` / `RankAdapter:109` …）——
强改会连带改变详情页/列表的按钮与文案形态，外溢严重。裁底部 tab 列表出口则只影响 tab 与对应页面。

**为什么不会错位**：底部 tab（`createTabs()`）与 Fragment 页面列表（`doSupportOnline()`）遍历的是**同一份**
`getModel().getBottomTabConfig()`（`HwThemeManagerActivity.java:965/977`）⇒ 同步少一项，不会出现"tab 少了页面还在"。

> **前提**：裁剪后列表须仍满足 `3 ≤ size ≤ 5`，否则会走 `size<3` 的静态硬编码回退分支（那支的 VIP tab 由
> `isSupportVip` 控制、会重新出现）。真机当前 4 项（推荐 / 排行·分类 / VIP / 我的）→ 裁后 3 项，满足。

**真机判据（待验证——改动完成时设备已断开）**：`installed 40/40` ＋
`底部 tab 已从服务端配置剔除: VIP(bottomTabId=3)（BottomTabFilter）`；UI 复核底部仅剩 3 个 tab、
推荐/排行·分类/我的 可正常切换且 VIP 页不再可达。（若配置裁后不足 3 项，会输出
`底部 tab 裁剪已放弃：裁后仅 N 项…` 并保持原样——见 §八·2 P3。）

---

## F · 挂载点逐点理由（原主文档 §三）

## 三、挂载点清单

### F2 A 组：全部资源免费

- **A1–A4** `ItemInfo` 判定短路（`isNeedPayItem`/`isNeedOrder`→false、`getPayed`/`isPayed`→true、
  `isVipResouce`→false）。**列表/详情全局生效**——所有页面按钮逻辑都走它。
- **A5** `price`/`actualPrice` 是 `double` 字段，libxposed 102 **不能 hook 字段** → getter 归零 +
  setter 首参强 0 组合覆盖。
- **A11** 服务端 bean（`BaseResItemBean`）源头归零（列表接口直接用）。

### F2 按钮矩阵（A6–A10）与按钮显隐汇总 —— 均已删除

逐按钮策略（5 个 `is*BtnShow` 门置 true、`changeVipBtnsVisibility` 藏按钮、`needShowBuyButton` 购买门）
**已全部拆除**。理由：真机实证"逐门硬掰"会导致按钮全显/全隐，且与服务端按钮类型打架。改为**数据态驱动**
—— 用户是 VIP + 资源免费，App 自己的 `ToolBarGroupLayout` switch 就会给出正确按钮组。详见 §十。

> 背景（`docs/reverse/theme-store.md`）：`isBuyBtnShow` 等是**门**不是开关——门返 `false` 会让
> `updateButtons` 整段不执行、按钮停留 XML 初始态（真机"hook 了但没变"的机制根因）。

### F2 T1：按钮组类型 → **-1（无激励）**

`themeDetailButtonType` 决定详情页**激励/免费领按钮组**（`OnlinePreviewBaseActivity.updateButtons`:5686
switch 实证；`ToolBarGroupLayout:928-975` 同构）：

| 值 | 行为 |
|---|---|
| **`-1`** | `hideLayout()` —— **只隐藏激励类视图**（`freeReceiveSmall/BigView`、`freeReceiveBtn`、`vipReceiveSmall/BigView`、`timeLimitDownloadBtn`，`OnlinePreviewBaseActivity.hideLayout`:1751 逐行实证），**不碰 `mDownloadBtn/mApplyBtn/mBuyBtn`** |
| `0` | `freeReceiveSmallView` 显示 =「免费领」小视图 ← **美豆入口** |
| `1` | `freeReceiveBigView` 显示 =「免费领」大视图 ← **美豆入口** |
| `2/3/4/5` | 非 VIP → 显示对应购买按钮；**VIP → 同样显示 `freeReceiveSmallView`** ← 美豆入口 |

**结论：必须取 `-1`。** 因为 ① `0/1` 本身就是"显示免费领"；② 我们把用户强制成 VIP 后，
`2/3/4/5` 也全部落到 `freeReceiveSmallView.setVisibility(0)`——**任何中段值都会显示美豆入口**，
`-1` 是唯一既不显示激励组、又不影响下载/应用/购买的取值。

> 更正记录（2026-10-09 真机回归）：本文档旧版写"−1 会把按钮全隐藏"是**误读**——那是把
> `ToolBarGroupLayout.hideLayout()` 当成了 `OnlinePreviewBaseActivity.hideLayout()`。
> 后者只隐藏激励视图，故 `-1` 正是"只关免费领/美豆、保留下载"的正确取值。
> `-1` 另有 `if (IncentiveCommonManager.getIncentiveData() != null)` 守卫，无激励数据时本来就保持隐藏态，安全。

### F2 H1/H2/H2b：下载 0% 修复 → 改走 VIP 通道

**卡点**：付费主题被 F2 免费化后，详情页"下载"永远 0%——`o52.addDownloadItem` 在
`isPayedItem()==false` 时走免费分支（`o52.java:604`）直接用 item JSON 的 `fileUrl` 下载，而付费资源
服务端**不下发可用直链** → uri 空 → UI 定格 0%。

**策略修正（2026-10-09）**：`isPayedItem==true` 时按 `downloadType` 选策略（`o52.java:616-626`）：

| downloadType | 类（jadx 保留的原名） | 语义 |
|---|---|---|
| 2 | `e71` = `DownloadItemIfTrial` | 试用 → 签发**试用资源**，应用后"文件损坏"（旧取值，已弃用） |
| **3** | **`f71` = `DownloadItemIfVip`** | **VIP 通道 → 取真实 URL + 内容密钥，直下完整资源（不走支付）** |
| 4 | `a71` = `DownloadItemIfAudit` | 审核/加密资源 |
| 5 | `b71` = `DownloadItemIfCoinsPayed` | 金币（美豆）支付 |
| 6 | `d71` = `DownloadItemIfTimeLimit` | 限时 |
| 其它(0/1/7…) | `c71` = `DownloadItemIfPayed` | 普通购买 → **弹 IAP 支付页** |

（`f71.downloadItem()` 原文日志 "Start to download vip product"；`productSendFinish` 里把服务端返回的
URL 写入 `downloadInfo.uri` 后 `super.downloadItem()` 直下。）

**取值：H2/H2b → 3（VIP 通道）** —— 配合 F1 把用户设成 VIP，即"用户 VIP → 主题免费直下完整资源"。

| 点 | 位置 | 动作 |
|---|---|---|
| H1 | `DownloadInfo.isPayedItem`（`:939`，uri 字段 L145） | uri 空 → **true**（进付费策略分派分支） |
| H2 | `o52.addDownloadItem` 4 参版（`:882`） | uri 空 → 第 3 参 `downloadType` **3**（VIP 通道） |
| H2b | `common.a.getDownloadType`（`a.java:78`） | 兜底详情页 builder 版链（`common/d.java:233` 不经 4 参版），同条件返 **3** |

条件统一：**uri 为空（null/空串/"null" 占位/非 URL 形态）**才激活；uri 非空（真免费 fileUrl / 已购
`download.do?k=` 直链）一律放行不误伤。漏 H2 只做 H1 → 落 `c71` 普通购买分支弹 IAP 支付页。

### F2 W1：动态壁纸「应用无效果」修复（packagePath 路径撕裂，PRE_PROCESS）

**症状（真机）**：付费动态壁纸被 F2 免费化后可正常下载，详情页出现「应用」按钮，点击**零反应**（无弹窗/无进度/无 toast），壁纸不变。

**根因（jadx 实证）**——付费资源落盘目录与 `packagePath` 计算之间的撕裂：

| 侧 | 判定 | 结果 |
|---|---|---|
| 下载落盘 | `DownloadInfo.isPayedItem()`（`price` **字段直读**，未 hook → true） | 文件写入 `HWTHEMES/PAYED_HWTHEMES/` |
| 预览页 `updateStatus():2660` | `packagePath = generateDownloadItemPath(fileName, mInfo.isNeedPayItem())` —— **isNeedPayItem 被本模块 A1 强制 false** | `packagePath` 指向**不带 PAYED_HWTHEMES 的错误目录** |
| 「应用」门 | `applyLiveWallpaperPrepare()` → `isFileExists(mInfo.packagePath)`（`:644`） | **false → reportApplyFailedEvent("402") 后静默 return**（构造的 Message 从未 send，零 UI 反馈）|

免费壁纸两测一致（price=0 → 同目录）不受影响；付费主题不经此链（主题走 `applyTheme`）。

**修法（`MountKind.PRE_PROCESS`）**：钩 `OnlineWallpaperPreviewLowActivity.applyLiveWallpaperPrepare()`（0 参 void，方法体独有日志串 `applyLiveWallpaper start` 作 DexKit 锚点），proceed 前把 `mInfo.packagePath` 修正为真实存在的文件——复用宿主自己的 `WallPaperInfo.getAbsolutExistFilePath()`（按 `this.price` **字段直读**判 payed，inner/outer × PAYED/正常 四处探测，不受 hook 影响）。修正失败/null 一律不动（PROTECTIVE）；`doLiveWallPaperApply` 读同一 `mInfo.packagePath`，一次修正全链生效。免费项路径本就正确 → File.exists() 命中 → 零干预。

**判据（待真机）**：`installed 39/39` + `壁纸 packagePath 已修正: …/HWTHEMES/x → …/PAYED_HWTHEMES/x`；付费动态壁纸点击「应用」出现进度/选屏弹窗并成功换壁纸。

### F2 S1：美豆/福利**服务端槽位**源头裁剪

| 点 | 位置 | 动作 |
|---|---|---|
| S1 | `hitop.mate.SlotData#getList()`（服务端载荷→域模型的唯一出口） | 按 `xposed/AdSlotFilter.kt` 表：整体丢弃 `slotType ∈ {5 腰封, 20 搜索右, 22 搜索左, 25 福利中心, 27 福利中心插件}` 的 `SlotList`；并从其余 `SlotList.list` 内删掉 `targetId ∈ {1019001 免费领, 1019002 福利中心}` 的单个 `Slot` |

判据、载荷实证与"为什么不挂在消费方"见 **§九 9.5**。

### F2 S2：底部导航 tab（VIP / 福利）**服务端配置**源头裁剪

| 点 | 位置 | 动作 |
|---|---|---|
| S2 | `hitop.entity.BottomTabConfigs#getBottomTabConfigs()`（AMS 在线配置 JSON → 域模型的唯一出口） | 按 `xposed/BottomTabFilter.kt` 表：剔除 `bottomTabId ∈ {3 VIP, 12 WELFARE「免费拿」}` 的 `BottomTabItem` |

判据、溯源与"为什么不关 `AgreeRepo.isSupportVip()` 总闸"见 **§九 9.6**。

### F1 B 组：开启 VIP

- **B1** `VipManager.isVipUser` 读点（`VipManager.java:111` 页面级直接判定，非实例散读）→ true。
- **B2** `VipManager.setVipUser` 写点（覆盖登出/特权撤销/网络回写路径，`setVipUser(false)` 强制 true）。
- **B3–B9：`VipState` 的 7 个 getter 散读直改**（方法级短路，不依赖任何调用链假设）：
  `getExpireTimeTimestamp`（到期时间戳）、`getDisplayTimeTimestamp` / `getDisplayTime`（到期展示）、
  `getRemainingTime`（剩余时长）、`getVipStatus`→2、`getVipType`→2、`isVipUser`→true。
- **双漏斗 VIP_STATE_FIX** —— **2026-10-10 真机回归后恢复**（`VipManager.getLocalVipState` +
  `VipManager.getVipState` 两点，intercept 后逐 setter 改写 `VipState` 实例字段）。恢复依据：
  getter 短路覆盖不了 **null 实例路径**——`getVipState` 首查未落缓存时返回 null，UI 拿到 null
  直接走"已过期/未登录"分支（"VIP 已过期"横幅回归的真机根因）。漏斗改写 after 语义：先 proceed
  拿实例 → 逐 setter 填永久 VIP 值；实例 null 时无参构造自建后返回。与 getter 散读直改**双层并存**。
  恢复后 F1 现状 = **12 点**（`VipManager` 读/写 2 + `VipState` getter 8 + 漏斗 2）。

### F1 挽留弹窗拦截（`docs/reverse/theme-store.md` §现象四 4.5，F1/F2 双保险）

走 `overallretentiondialog` 子系统（`pushType` = `VIP_ARRIVED`→`MEMBER_EXPIRE=4` 等，展示咽喉唯一
出口是 `showDialog`）；旧 `fetchShouldDialogBy*` hook 拦不住——它是休眠消费链（4.4 实证）。

- **F1** `showDialog` 空实现（void 拦截器返 null = 不调原方法，libxposed api 语义，
  `AI-lsposed-temlate SKILL.md:102` 实证）：单点覆盖全部 6 型（`RESOURCE_TRIAL_END=1`/
  `MEMBER_SOON_EXPIRE=2`/`EXPIRED_RESOURCES=3`/`MEMBER_EXPIRE=4`/`EXPIRED_SPRITE=5`/`MEMBER_LOGOUT=6`），
  含真机 `MEMBER_EXPIRE`。
- **F2** `onCreate` 先 proceed 后 finish（ARouter 直启绕过 F1 的兜底）：不 proceed 会让生命周期在未
  初始化状态下走完（onDestroy 等回调可能 NPE），proceed 后再 finish 更稳。

### F3/F4 C 组：试用 + 防回退

- **C1/C2**（归组不确定，保守归 F2）：`tryout.a.isValidTheme`→true（离线 8 分钟窗）、
  `isWareOutDated`→false。
- **C3** `isSupportTrial`→true（支持试用常显）。
- **C4**（F4）`checkCurrentTheme`→true（当前主题试用态核查有效，防 fence 到期回收）。
- **C5/C6** `duringIncentive`→true、`getIncentiveExpireTime`→Long.MAX（在线任务授权窗永久）。
- **C7**（F4）`ThemeResManager.getThemeApplyState`→PERMANENT（永非试用态，不回退；枚举延迟解析，
  未加载时归懒加载重试）。
- **C8** `AgreeRepo.isTrialSupportOnline`→true（云控试用开关常开）。
- **C9** `ThemeResManager.isTrialTheme`→false（移除"试用中"判定 → 无试用角标/到期提醒）。

## E · 下载族补全：免费加密资源与付费项 IAP（2026-10-12，jadx 实证 20.1.63.305）

> 现象（用户报告）：小部件/图标下载后提示「文件已损坏，请重新下载」；字体下载进度卡 0%；
> 下载记录里没有付费主题。本节给前两者的机制与修复；字体与下载记录见 §E.4 待真机分诊。

### E.1 分派真相：DownloadInfo.price 是**字段直拷**，F2 getter hook 管不到下载层

`common/d.setDownloadInfo`（d.java:169-223）构造 DownloadInfo 时：
- :174 `double d = itemInfo.price` —— **字段直读**，不走被 F2 hook 的 `getPrice()`
- :175 `downloadInfo.price = (d <= 0 || actualPrice != 0) ? actualPrice : -100.0` —— 付费项
  （价>0 且无实付价）落到 **-100 已购标记**；`DownloadInfo.isPayedItem()`（:925）认 `>0 或 ==-100`
- :196 `downloadInfo.isSpecialEncrypt = itemInfo.isSpecialEncrypt()`（H5 详情/列表均带此标记，
  g.java:488/:525/:567、u02.java:206）

⇒ o52.addDownloadItem（o52.java:604-634）分派时 isPayedItem 读的是**服务端真实值**：
免费项（price 0）走免费直下分支；`downloadType==4 && isSpecialEncrypt` 才进 a71 审计通道。

### E.2 「文件已损坏」机制（小部件/图标/AOD）

免费但加密的项（price=0、isSpecialEncrypt=1）且 downloadType=1：免费直下分支**跳过 sendProduct**，
裸 GET 拿到**密文**；应用时 `ThemeDecryptTool` 解密/哈希校验失败 → `toast_pkg_error`
（「文件已损坏，请重新下载」，ThemeDecryptTool.java:121；WidgetDetailChooseActivity.java:2460
是文件缺失分支）。密钥 `ThemesKeyStoreManager.saveResContentKey` 只在 sendProduct/sendAuditProduct
链路里保存（OrderManagerImpl.java:335）——免费直下永远拿不到。

### E.3 修复：H2b → H2b'（DOWNLOAD_TYPE_ROUTE）

挂 `DownloadModel.getDownloadType`（o52 分派唯一读点），按 DownloadInfo 实际状态路由：

| 状态 | 判定 | 路由值 | 落点 |
|---|---|---|---|
| 空 URL（空串/"null"/无 `://`） | uri | **3** | f71 VIP 试用授权（原 H2b 语义） |
| isSpecialEncrypt && price≤0 | 加密免费 | **4** | a71 审计通道：`getDownloadUrlForAudit`→`sendAuditProduct`（免购买签发 URL+密钥，OrderManagerImpl.java:369 起）——o52 原生 free+enc+4 路线 |
| price>0 或 ==-100 | 付费（含已购标记） | **3** | f71，避开 c71 普通购买策略弹 IAP 支付页（c71.java:70-83） |
| 其余 | 真免费明文直链 | 放行 | 原值 |

a71/f71 的成功路径一致：`productSendFinish` 校验 secretKeySaveResult（Pair.second）→
`uri=签名URL; super.downloadItem()` → 带密钥闭环下载。失败路径弹「下载失败」并 abort（非静默）。

### E.4 待真机分诊（静态无法定案）

- **字体 0%**：字体详情经 H5（CommonJsPlugin.WebDetailBean）→ g.java:474-520 组 FontInfo →
  `startItemDownload(1)`（OnlineFontPreviewActivity.java:865）→ 同一分派点。路由修复后付费字体
  必进 f71；若仍 0% 或弹「下载失败」= 服务端对 resType=4 拒发试用授权（sendProduct resultCode
  可查），届时需换 4 审计通道或放弃该类。判据：logcat 过 `DownloadItemIfVip|OrderManagerImpl|DownloadHandler`。
- **动态壁纸/息屏「应用没反应」**：下载属壁纸族（g.java:525），isLiveWallpaper 单列（d.java:194）；
  应用链是壁纸专用 apply（非 ThemeApplyState C7 那条），待复现时抓 apply 侧日志定位。
- **下载记录缺付费主题**：记录页 = NewLocalThemeActivity（my_downloads，:315，extends CountActivity），
  列表来源待查（本地 provider 行 or 服务端已购列表）；疑点：f71 下载项是否写 provider。
