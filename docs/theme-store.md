# 主题商店目标（ThemeStoreHook）

> 包名 `com.hihonor.android.thememanager` · 真机基线 **20.1.63.305** · 源码 `app/app/src/main/kotlin/top/honorhook/xposed/target/themestore/`
> 本文件 = 该目标的**唯一设计依据**（需求 / 挂载点 / 策略 / 判据 / 未定项）。源码内只留一行指针。
> 逆向过程证据（逐条 file:line 推导、下载链全图、UI 真链反查、云控开关全表）在 [`reverse/theme-store.md`](reverse/theme-store.md) —— **只在需要翻证据时读**。
> 改 hook = 先改本文件，再改代码。

## 〇、需求（2026-10-09 用户确认）

## 功能需求

| # | 功能 | 说明 |
|---|------|------|
| F1 | **开启 VIP** | 会员状态判定全部改为 VIP——所有 isVip/member/premium 校验点 |
| F2 | **全部资源免费** | 免费/价格判定全部放行——付费判定点返回免费/0 元 |
| F3 | **试用无倒计时** | 试用到期判定失效——试用不因时间/次数到期而中断 |
| F4 | **任何场景不恢复默认** | 主题授权校验失败时的"恢复默认主题"回退**全部阻断**（含引擎侧校验失败/试用期结束/账号状态变化等一切触发回退的路径） |

## 范围定义

**"主题" = 主题应用内的全部资源**：主题（皮肤）、壁纸、字体、锁屏样式、图标等——应用内全部可下载/可应用的资源都在 F1-F4 覆盖范围内。

## 实现路径

1. 付费机制逆向（进行中）→ hook 点清单（本地判定类/方法/字段）
2. 每个判定点 → HonorHook `TargetHook` 实现（主题商店 + 主题引擎各一个目标类）
3. 本地判定 hook 改返回值/字段；**纯服务端校验点 hook 无效**（如实标注，不硬做）
4. F4 需专门定位引擎侧"校验失败 → 恢复默认"的回退路径并阻断

## 验证标准（真机）

开启模块后：选任意付费主题/壁纸/字体 → 直接应用成功，无付费弹窗、无试用倒计时、任意操作（重启/切账号/到期）后**不回退默认主题**。

---

> 归属：`app/app/src/main/kotlin/top/honorhook/xposed/target/ThemeStoreHook.kt`
> 说明：本文件是 `ThemeStoreHook.kt` 的设计依据与实现理由的**唯一归档地**。源码内只保留一行指针，
> 避免注释体积超过代码本身。修改 hook 逻辑时，先在此更新理由，再改代码。

## 一、目标与范围

荣耀主题商店（`com.hihonor.android.thememanager`）目标，落地 F1–F4 四项功能：

| 功能位 | 含义 | 归组 key |
|---|---|---|
| F1 | 开启 VIP（会员身份永久有效） | `KEY_F1_VIP_ENABLED` |
| F2 | 全部资源免费（含下载链修复） | `KEY_F2_FREE_ENABLED` |
| F3 | 试用无倒计时 | `KEY_F3_TRIAL_ENABLED` |
| F4 | 任何场景不恢复默认（防回退） | `KEY_F4_NO_RESET_ENABLED` |

需求定义：`docs/theme-store.md`。
Hook 点清单依据：`docs/reverse/theme-store.md` §6（目标类名在 keep 名单内可读、不混淆漂移），
已逐一对照 `system-files/theme-store-jadx/sources` 复核全部目标类/方法签名（行号与报告一致）。

真机基线：主题商店 `20.1.63.305`（= hook 基线）。当前挂载表 **40 项**（2026-10-10：新增 `S1` 槽位裁剪、`S2` 底部 tab 裁剪，并删除 2 个冗余的 `VipModel` 漏斗点，详见 §八·2；**40/40 待真机验证**，此前实测 `installed 41/41`；更早口径见各节历史记录——**一律以真机 `installed N/M` 的 M 为准**）。

## 二、功能位归组

同一方法只属一个功能位（不确定时保守归 F2）；总开关是上位语义。**逐点归属看代码**（`Collectors*.kt` 的 featureKey 参数 + `FeatureCatalog`）。

## 三、策略要点（**逐点清单看代码**：`themestore/Collectors*.kt` 挂载表；逐点 file:line 依据见 [`reverse/theme-store.md`](reverse/theme-store.md) §F）

| 主题 | 做法与理由（代码里读不到的"为什么"） |
|---|---|
| 资源免费化 | 改**判定数据源**（`ItemInfo` 判定/价格 getter、服务端 bean 源头归零）⇒ 列表/详情/搜索全生效；**按钮显隐不做逐门硬掰**（见 §五） |
| 按钮组 | 详情页激励/免费领按钮组由 `themeDetailButtonType` 决定，取 **`-1`**：0/1 直接显示"免费领"（美豆入口），2–5 在 VIP 用户下同样落到免费领视图 ⇒ **任何中段值都会漏出美豆入口**；`-1` 只隐藏激励类视图，不碰下载/应用/购买 |
| 下载 0% | 付费资源**服务端不在详情 JSON 下发可用直链**（真实 URL 只在授权接口返回）⇒ 免费化后走免费分支必然 0%。解法：**uri 为空时把 downloadType 改成 VIP 通道**（取真实 URL + 内容密钥直下完整资源，不走支付）；条件严格限定 uri 为空，非空（真免费 / 已购直链）放行不误伤 |
| 动态壁纸「应用」无反应 | 免费化令 `packagePath` 计算与实际落盘目录撕裂（付费资源落 `PAYED_HWTHEMES/`）⇒ `isFileExists` 失败静默 return。解法：**前置改写（PRE_PROCESS）**用宿主自己的路径探测方法修正 `packagePath`，修正失败一律不动 |
| 入口裁剪 | 云控布尔表管本地 UI 入口 + 服务端槽位/底 tab 在**唯一出口**裁剪（见 §九）；不在消费方逐个隐藏 |
| VIP 态 | 读点（`VipManager` 静态 getter）+ 写点 + `VipState` 7 个 getter 直改；**漏斗点必须保留**（教训见 §四） |

## 四、VIP 落地值的三个"为什么"（具体数值看代码）

- **不用 `Long.MAX`**：会让日期格式化 / 剩余天数判定出异常值 ⇒ 取 `2088-08-08` 这种"够远但正常"的时间；剩余时长用 `now + 固定量`，防消费端 `now + X` 溢出。
- **`vipStatus = 2`**：`1` 是 NOT_VIP，正是「新用户专享」卡片的根因。
- **到期日期展示链走 `getDisplayTimeTimestamp`**，不是 `expireTimeTimestamp`（`MineVipCardView` / `HwThemeManagerActivity` 多处实证）——只改后者 UI 不变。

## 五、按钮策略：数据态驱动（原逐按钮硬掰已拆除）

真机现象（旧策略）：详情页同时出现 **试用/免费获取/下载/购买/VIP免费** 五个按钮；已下载后另出现
**混搭/购买/应用**。

**根因**：旧策略"逐门硬掰 + 汇总点藏按钮"是错的——
1. 5 个 `is*BtnShow` 各管一个按钮，全置 true → 五按钮齐现；
2. `changeVipBtnsVisibility`（`OnlinePreviewBaseActivity.java:4161`，参数序 `0=buy 1=trial 2=download
   3=apply 4=vip 5=freeAccess … 9=mashup`）虽能藏按钮，但那是**表现层打补丁**，与服务端按钮类型打架。

**修正**：全部拆除（5 门 + 汇总点 + `needShowBuyButton` 购买门）。按钮显隐**交给数据态**——
"用户 VIP + 资源免费"后，App 原生 switch 自然给出可用按钮组。

**但 T1 必须取 `-1`（不是 0）**：`0/1` 显示「免费领」视图、`2/3/4/5` 在 VIP 态也落到同一视图，
只有 `-1`（`hideLayout` 只关激励视图）才能清掉美豆入口而保留下载/应用。详见 §三 T1。

相关章节：§三（T1）、§十（顶层策略）。

## 六、判读位置

hook 侧 `module.log()` 落 **LSPosed 框架日志** `/data/adb/lspd/log/modules_*.log`，**不在 logcat**（logcat 只部分转发，tag `LSPosedFramework`）。`dev.sh modlog` / `log-hook` 已封装。汇总日志 `installed N/M` 恒输出，细节日志有 `DEBUG_LOGGING` 门控。

## 七、不挂的点（如实标注）

- **D 组购买/已购流程**：F2 全免费后无购买场景，且 D4/D5 属服务端单/签名与 IAP SDK 支付页，本地
  hook 无效（§6 D 组注）。
- **B5** `checkVipStateOnline`：报告标"非必须，B1 已够"，网络回写点不如读点稳。
- **T10** `updateButtons` 直改：`reverse/theme-store.md` §五 标可选——全读点都走 T1，
  `L3099/1733/2676/2714/3782` 实证，T1 已覆盖 switch 输入，真机不足再上。
- **F3/F5 挽留弹窗源头拦截**：`getData` 拦截连带试用资源回收副作用（`doResRecycle`）、
  `handleArrivedPushMsg` 影响推送入库面大。

## 七·2、未解决 / 待核查（勿当成已完成）

| 项 | 现状 | 线索 |
|---|---|---|
| 搜索页「免费领」入口仍在 | 云控开关（已被本模块强制 false）与槽位裁剪都管不到 | 怀疑是**本地硬编码 tab**：`SecondTabBean.synthesisTab(app)`（调用点 `SearchResultReporter:473`），不走云控；命中链参考 `NewRankListAdapter:144`（`isFreeReceiveShow && isOpenFreeClaimSwitch && !duringIncentive && isBuyBtnShow`）——需查 search 模块 |
| 服务端/ROM 侧边界 | 见证据层 §A.5/§A.7：ROM ThemeProvider 是否独立校验、`isSpecialEncrypt` 资源解密链 均未证实 | 商店 hook 只保证"界面放行"，"真正应用+长期有效"取决于 ROM/服务端 |

## 八·3、混淆名目标的 DexKit 锚点（2026-10-10）

直连反射依赖"当前版本的混淆名"，OTA 后 R8 重新映射即失效。为此给**全部混淆名目标**配了
"内容性锚点"（= 混淆**不改**的东西），直连失败时由 `MethodResolver` 第二轨按特征兜底。

**锚点强度排序**：方法体字符串常量 > 参数类型序列（含可读类名）> 所在类字段类型 > 返回类型/参数个数 > 方法名。

| 目标（混淆名） | 锚点 | 依据（jadx 实证） |
|---|---|---|
| `u15#getBoolean(String, boolean)` | `strings=["themename"]` + `paramTypes=[String, boolean]` | `defpackage/u15.java:218-221` 方法体内 `getSharedPreferences(app, "themename")` |
| `u15#getBoolean(String)` | 同上 + `paramTypes=[String]` | 同上（两重载方法体同锚点，靠参数序列区分） |
| `o52#addDownloadItem(Context, DownloadInfo, int, boolean)` | `paramTypes=[Context, common.DownloadInfo, int, boolean]` | `defpackage/o52.java:882` 签名（参数含**未被混淆**的 `common.DownloadInfo`） |
| `common.a#getDownloadType()` | `classFieldTypes=[java.lang.ref.WeakReference, common.DownloadInfo]` | `common/a.java:11-12` 字段声明（类内无字符串、方法无参数，只能靠字段类型） |
| `tryout.a#isValidTheme()` | `strings=["not register theme ware"]` | `tryout/a.java:662` 方法体独有日志 |
| `tryout.a#checkCurrentTheme()` | `strings=["isFileExists"]` | `tryout/a.java:530` 方法体独有日志 |
| `tryout.a#isWareOutDated(int)` | `classStrings=["TryOutHelper"]` + `paramTypes=[int]` | 类内日志 TAG（该方法体本身无字符串，退一级用类级锚点） |
| `tryout.a#isSupportTrial()` | `classStrings=["TryOutHelper"]` | 同上（无独有字符串，**锚点最弱**；另有云控表 `trial_support_online` 双保险） |

**纪律（`MethodResolver.resolveMethod`）**：
- **默认包类**（`u15`/`o52`，className 无 "."）**必须有内容锚点**才允许走 DexKit —— 否则会退化为
  "全 APK 找同名同签名第一个"，属"看起来有兜底、实际有害"的假兜底；无锚点则**诚实跳过**并打日志。
- 有名包类（`common.a`/`tryout.a`）按包名限定范围，锚点用于进一步收紧。
- 兜底缓存 key 纳入**全部特征**（不同特征不串缓存）。
- **直连轨优先**，锚点只在直连失败时生效 ⇒ 锚点写错也不影响当前版本（最坏是漂移后未命中 → 回到"跳过"）。

**API 实证**：全部 DexKit 调用已对 `dexkit-2.2.0-api.jar` 逐项 `javap` 核对 ——
`MethodMatcher.name/returnType/paramCount/paramTypes(vararg)/usingStrings(vararg)/declaredClass(ClassMatcher)`、
`ClassMatcher.create()/usingStrings(vararg)/addFieldForType(String)`、`FindMethod.create()/searchPackages(vararg)`。

> **待真机验证**：锚点只在"直连失败"时才被用到，正常版本**不会**触发第二轨；
> 判据 = `installed 40/40` 不变 + 日志中不出现"DexKit 兜底跳过（默认包类且无内容锚点）"。

## 九、美豆 / 福利中心（广告变现链路）拦截

**两条独立机制**：① 云控布尔表管**本地 UI 入口**（`CloudSwitchTable` + `u15` 读点，含「免费领」`free_claim_show_value`、「会员卡」`member_card_support`、「会员精选」）；② 服务端槽位裁剪管**服务端下发的入口**（`AdSlotFilter` @ `SlotData.getList()` 出口 / `BottomTabFilter` @ `BottomTabConfigs` 出口，见 §三 S1/S2）。

- 美豆 = 英文串 **bonus** / 内部模型 **金币 GoldCoin**（`theme/goldcoins/bean/GoldCoinInfo`）——看广告/装广告 App 赚币换主题的变现链。
- 「福利中心」是**服务端下发的 H5**（`IncentiveCommonManager.getWelfareCenterH5Url()`），签到/免费领都在 H5 内 ⇒ APK 里搜不到对应中文串。
- 挂载只在**出口**做（源头原则）：槽位/底 tab 一律在"服务端载荷 → 域模型"的唯一出口裁剪，不在各消费方逐个 GND。

> 逐点载荷实证、为什么不挂消费方、为什么不关 `AgreeRepo.isSupportVip()` 总闸 → 证据层 §E。

## 十、顶层策略：用户 VIP + 主题免费 + 下载走 VIP 通道

三层**数据态**，不碰表现层：

| 层 | 做法 | 挂载点 |
|---|---|---|
| ① 用户 → VIP | `VipManager.isVipUser`→true、`setVipUser`→true、`VipState` 的 7 个 getter 直返会员态 | F1 B1/B2 + VipState getter ×7（§三 F1） |
| ② 资源 → 免费 | `ItemInfo` 付费/已购判定短路 + 价格归零 + 服务端 bean 源头归零 | F2 A1–A11（§三） |
| ③ 下载 → 可用 | `isPayedItem`→true 进付费分派，`downloadType`→**3（`DownloadItemIfVip`）**取真实 URL 直下完整资源 | F2 H1/H2/H2b（§三） |

**为什么这样对**：
- 用户是 VIP + T1→**-1** ⇒ 激励按钮组（免费领/美豆）被 `hideLayout()` 关掉，下载/应用/购买照常（§三 T1）；
- 资源免费 ⇒ 不触发购买；
- 下载走 **VIP 通道**（而非旧策略的试用通道）⇒ 不再产生"试用资源 → 应用后文件损坏"。

**已拆除的（错误策略）**：5 个 `is*BtnShow` 门硬掰、`changeVipBtnsVisibility` 藏按钮、
`needShowBuyButton` 购买门、`downloadType=2` 试用通道。改动记录见 §五 + §三 H1/H2/H2b。


