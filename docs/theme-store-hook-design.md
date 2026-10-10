# 主题商店 Hook 设计说明（ThemeStoreHook）

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

需求定义：`docs/honorhook-theme-requirements.md`。
Hook 点清单依据：`docs/honor-theme-payment-analysis.md` §6（目标类名在 keep 名单内可读、不混淆漂移），
已逐一对照 `system-files/theme-store-jadx/sources` 复核全部目标类/方法签名（行号与报告一致）。

真机基线：主题商店 `20.1.63.305`（= hook 基线）。当前挂载表 **40 项**（2026-10-10：新增 `S1` 槽位裁剪、`S2` 底部 tab 裁剪，并删除 2 个冗余的 `VipModel` 漏斗点，详见 §八·2；**40/40 待真机验证**，此前实测 `installed 41/41`；更早口径见各节历史记录——**一律以真机 `installed N/M` 的 M 为准**）。

## 二、功能位归组规则

同一方法只属一个功能位；归组不确定时保守归 F2；总开关为上位语义。

| 功能位 | 挂载点 |
|---|---|
| F2 = `KEY_F2_FREE_ENABLED` | A1–A11 + T1 + H1/H2/H2b + C1/C2 + 广告链路关停 |
| F1 = `KEY_F1_VIP_ENABLED` | B1/B2 + `VipState` getter ×7 + 挽留弹窗 |
| F3 = `KEY_F3_TRIAL_ENABLED` | C3/C5/C6/C8/C9 |
| F4 = `KEY_F4_NO_RESET_ENABLED` | C4/C7 |

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

> 背景（`docs/theme-ui-real-chain.md`）：`isBuyBtnShow` 等是**门**不是开关——门返 `false` 会让
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
- ~~**双漏斗 VIP_STATE_FIX**~~ —— **已于 2026-10-10 审计删除**（原为 `VipModel.changeToVipState` +
  `checkVipStateOnline` 两点，intercept 后逐 setter 改写 `VipState` 实例字段）。删除依据：
  **`VipState` 字段全部 private**（`VipState.java:32/37/58/69/72`）⇒ 类外读取**只能**经过上面这 7 个 getter，
  而 getter 已被短路成常量 ⇒ 漏斗改写的实例字段**没有任何消费方能读到**（除类内 `toString`/`copy` 等），
  属冗余的第二层兜底。删除后挂载表 **42 → 40**。

**F1 现状 = 9 点**（`VipManager` 读/写 2 + `VipState` getter 7）。

### F1 挽留弹窗拦截（`docs/theme-ui-real-chain.md` §现象四 4.5，F1/F2 双保险）

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

## 四、VIP 状态落地值

| 字段 | 值 |
|---|---|
| 到期时间戳 | `2088-08-08 00:00:00` 东八区毫秒 = `3_742_732_800_000L` |
| 展示字符串 | `"2088-08-08 00:00:00"`（与服端 `displayTime` 同格式，即 fn5 默认解析格式） |
| `vipUser` | true |
| `vipStatus` | 2（会员态；**1 = NOT_VIP 是"新用户专享"卡根因**） |
| `vipType` | 2（连续包月，激活续费入口/账户已开通识别） |
| `remainingTime` | `now + 1_950_000_000_000L`（到 2088 的真实剩余；防消费端 `now+X` 溢出） |

> 注：`Long.MAX` 会让日期格式化/剩余天数判定出现异常值，故用 2088 而非最大值。
> 到期日期**展示链**走 `getDisplayTimeTimestamp`（`MineVipCardView.java:430/441`、
> `HwThemeManagerActivity.java:894/901`）而非 `expireTimeTimestamp`。

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

## 六、挂载健壮性与日志

- **懒加载重试**（`docs/gap-analysis.md` G1/G2）：未解析项 `postDelayed` 约 500ms 重试一轮、再失败
  约 2s 最后一轮（HC `installCustomTileHooks L408-416` 同款），仍失败 WARN 放弃。
- **汇总日志**：`ThemeStoreHook: installed N/M` 始终输出（真机判据）；细节日志由 `DEBUG_LOGGING`
  门控（默认关）。
- **判读位置**：hook 侧 `module.log()` 写入 **LSPosed 框架日志** `/data/adb/lspd/log/modules_*.log`，
  **不在 logcat**（logcat 只有部分转发且 tag 为 `LSPosedFramework`）。`tools/dev.sh hook-test` 已直接
  读框架日志（需 su）。

## 七、不挂的点（如实标注）

- **D 组购买/已购流程**：F2 全免费后无购买场景，且 D4/D5 属服务端单/签名与 IAP SDK 支付页，本地
  hook 无效（§6 D 组注）。
- **B5** `checkVipStateOnline`：报告标"非必须，B1 已够"，网络回写点不如读点稳。
- **T10** `updateButtons` 直改：`theme-ui-real-chain.md` §五 标可选——全读点都走 T1，
  `L3099/1733/2676/2714/3782` 实证，T1 已覆盖 switch 输入，真机不足再上。
- **F3/F5 挽留弹窗源头拦截**：`getData` 拦截连带试用资源回收副作用（`doResRecycle`）、
  `handleArrivedPushMsg` 影响推送入库面大。

## 八、2026-10-09 哲学审计（12 点 → 4 点）

删除 10 点：

1. 7 个 `VipState` 散读 getter（B3/B4/T5/T6/`displayTime` 等）——已由双漏斗覆盖，后于真机回归恢复为
   方法级直改（见 §三 F1）；
2. `T8 MineVipCardView.getVipCardType`（深签名冗余，T5→2 已覆盖其 switch）；
3. 2 个 `fetchShouldDialogBy*` 休眠点——**零调用链实证**：唯一调用点 `VipCheckVM.java:164` 在
   `refreshShouldDialog` 自环内、全工程无真实调用方；真机弹窗走 `overallretentiondialog` 链已由展示
   咽喉拦截。

保留 2 点（B1/B2）+ 新增 2 漏斗。

## 八·2、2026-10-10 全量审计（42 → 40 项 + 基础设施修正）

用户要求"重新看看全部 hook 是否合理/精准/稳定/从源头"。逐项对 jadx 复核 42 个挂载点后的结论与落地：

**确认正确、未动**（避免后续误删）：
- `u15.getBoolean` **两个重载都必须挂** —— 它是 **static** 方法（`defpackage/u15.java:38/218`），且单参版内部
  **直接调 `SharedPreferences.getBoolean`、不调双参版**。
- `ItemInfo.isNeedPayItem()` / `isNeedOrder()` 内部**字段直读 `this.price`**（`ItemInfo.java:724-731`），
  **不调 `getPrice()`** ⇒ 只挂 getPrice 覆盖不到判定，A1/A2 不可省。
- `ItemInfo.price` 是 **public 字段**（`:88`）⇒ Gson 反射直写绕过 setter，A5 的 `setPrice`/`setActualPrice`
  写点仅在"手写 setter 路径"生效（保留作防御，非主防线）。
- `VipState` 字段全 private ⇒ getter 是唯一外部读路径（也正是删漏斗的依据）。
- `findTabIndexByItem` / `findPageIndexByItem` 找不到时**返回 0**（`HwThemeManagerActivity.java:1070+`）
  ⇒ S2 删 tab 后即使 itemId 恰为 VIP 也优雅落回推荐页，不 NPE。
- `DownloadInfo.isPayedItem()` 确实只看 `this.price`（`:939-947`）。
- C6 的 `Long.MAX_VALUE` **安全** —— 唯一消费点 `ResInitHelper:69/71/133/135` 仅比大小取优先，不格式化。

**修正项**：

| # | 项 | 处理 |
|---|---|---|
| P1 | **DexKit 第二轨"假兜底"**：`MethodResolver` 对**默认包类**（`u15`/`o52` 无包名）把 `packages` 传成 null ⇒ DexKit 退化为"全 APK 按同名同签名找第一个"，误命中风险高于收益；且 `usingStrings`（唯一混淆不改的可靠特征）从未接通 | ① 无包名且无 `usingStrings` ⇒ **诚实跳过**（不再假装兜底）；② 打通 `usingStrings` 通道（`MountTask.strings` → `resolveMethod` → `findMethodByFeatures`）；③ 缓存 key 纳入特征串 |
| P2 | `VipDebugProbe.ENABLED = true` —— VIP 探针仍在生产注册 3 个 hook、持续反射 + 打日志 | 置 `false`（与 `SlotProbe` 一致）。因是编译期常量，R8 将其代码整体 DCE ⇒ **release 426KB → 410KB**、运行时零开销 |
| P3 | S2 缺"裁后仍 ≥3"保护 —— 宿主 `size < 3` 会走静态硬编码回退分支，那支的 VIP tab 由 `isSupportVip` 控制**会复活** | `pruneBottomTabPayload` 增加：裁后不足 `BottomTabFilter.MIN_LIST_SIZE`(=3) 则**整体放行**，并打 WARN |
| P4 | VIP 双漏斗被 7 个 getter 架空（见 §三 F1） | 删除 2 个 `VIP_STATE_FIX` 挂载点 + `MountKind.VIP_STATE_FIX` + `fixVipState`/`invokeVipSetter` + `CLS_VIP_MODEL` |

**保留的观察项**（不处理）：
- `VipState.getRemainingTime` 返回"挂载时刻 + 61.8 年"的固定值而非动态算到 2088 的剩余（60 年内无实害）；
- 云控表里 `help_consumer_support_online`（帮助与客服）/ `redpoint_support_online`（红点角标）/
  `AICREATION_SHOWORHIDE_CONFIG`（AI 创作）属**功能入口**而非广告（用户既定清单，保留）；
- 调试探针文件（`SlotProbe.kt` / `VipDebugProbe.kt`）与 `install()` 里两行调用仍在（`if (!ENABLED) return`
  早退 + R8 DCE ⇒ 零开销），保留以便后续排查。

**判据（待真机）**：`installed 40/40`。

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

**表内容与 key 读点明细**见 `docs/cloud-switches.md §四`。

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

