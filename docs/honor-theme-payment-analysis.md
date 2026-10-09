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