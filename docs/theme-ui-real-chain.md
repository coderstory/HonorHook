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
| T1 | incentive.IncentiveData | `getThemeDetailButtonType` | `()I` | 常返 **0**（免费领小按钮组）或 **1**；绝不留 -1（会 hideLayout 全隐藏） | 详情页按钮组显示 |
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

**警告**：T1 返回 -1 会把按钮组全部 hideLayout()（含下载/应用按钮），勿用 -1；hook `isBuyBtnShow` 常返 false 的旧做法会使 updateButtons 不执行、按钮停留初始态，**不要再单独用**（可配合 T2 用常返 true 保持门开着）。

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