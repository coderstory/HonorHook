# 全量 hook 清单与冗余审计（2026-10-10）

> 目的：两个目标（主题商店 + 包管理器）共 **47 个挂载点**的全量台账 + 冗余/废弃/重复审计。
> 长期维护基线：**改 hook 前先看本表**；`多余/废弃` 项附 jadx `file:line` 实证，不靠印象。
> 相关：`docs/theme-store-hook-design.md`（主题商店设计）、`docs/installer-cloud-config.md §六`（包管理器落地）。

## 零、结论摘要

| 级别 | 数量 | 内容 |
|---|---|---|
| **P0 死挂载**（挂了永不生效） | 1 | `theme:TryOut.isSupportTrial`——方法零调用者 |
| **P1 重复**（可与另一挂载点合并，行为不变） | 2 | `theme:o52.addDownloadItem`（被 H2b 完全覆盖）；`theme:ItemInfo.isVipResouce`（应改挂 `getVipOnly` 以覆盖更全） |
| **P2 残留物** | 4 | `SlotProbe.kt` / `VipDebugProbe.kt` + 各自调用行 / `KEY_HOOK_ENABLED` / ThemeStoreHook 私有 `findMethod` |
| **P3 结构性** | 3 | 两套挂载基建重复；源码内 10 处"已删"历史注释；`featureKey` 管道运行时零输出 |
| **P4 观察项**（保留） | 4 | 见 §四 |

清理后：ThemeStoreHook **40 → 38**（−1 死挂 −1 重复），InstallerHook **7 → 7**；挂载基建去重（P3-1）另计。

> **执行状态（2026-10-10 更新）**：**P0 + P1 + P2 已全部执行完毕**（debug/release 均 BUILD SUCCESSFUL）——
> 删除 `TryOut.isSupportTrial`、`o52.addDownloadItem`(H2)；`isVipResouce` 改挂 `getVipOnly`（下表 #4 已更新）；
> 删除 `SlotProbe.kt`/`VipDebugProbe.kt` 及调用行、`KEY_HOOK_ENABLED`、私有 `findMethod`（mountOne 直接走
> `MethodResolver.resolveMethod`）；`UriOwner` 枚举随之收敛为 `THIS`/`GET_DOWNLOAD_INFO` 两值。
> **P3 未执行**（抽公共基类 / 历史注释归档 / featureKey 管道处理——含回归风险，待单独一轮 + 真机验证）。
> 当前真机验证判据：`installed 43/43`（主题商店，39 基线 + VIP 漏斗 2 + getSecondTitle 1 + createTabs 前置 1，见 §六）+ `installed 7/7`（包管理器）。

---

## 一、主题商店 `ThemeStoreHook`（43 点 · 2026-10-10 回归修复后基线，构成见 §6.5）

真机基线 20.1.63.305；全部签名已逐一对 `system-files/theme-store-jadx/sources` 复核。

### F2 全免费（A 组 · ItemInfo 判定/价格）

| # | id | 目标 | 值 | 消费点数 | 判定 |
|---|---|---|---|---|---|
| 1 | `theme:ItemInfo.isNeedPayItem` | `isNeedPayItem()` | false | 37 | 保留（方法体**字段直读** `this.price`，getter hook 覆盖不到） |
| 2 | `theme:ItemInfo.isNeedOrder` | `isNeedOrder()` | false | 16 | 保留（同上，`price>0 && !isPayed` 字段直读） |
| 3 | `theme:ItemInfo.getPayed` | `getPayed()` | true | 25 | 保留 |
| 4 | `theme:ItemInfo.isPayed` | `isPayed()` | true | **1** | 保留（唯一消费 = `OnlineWallpaperPreviewLowActivity:1764`；与 #3 是两个方法，不可互相覆盖） |
| 5 | `theme:ItemInfo.getVipOnly` | `getVipOnly()` | 0 | 24 | **已执行 P1**：原 `isVipResouce()`（实现即 `getVipOnly()==1`）改挂 `getVipOnly`——多覆盖 `BrowseDataHelper` 等直连消费点（子类均未覆盖该方法）；id 为 `theme:ItemInfo.getVipOnly` + `theme:ItemBean.getVipOnly` |
| 6 | `theme:ItemInfo.getPrice` | `getPrice()` | 0.0 | 88 | 保留 |
| 7 | `theme:ItemInfo.getActualPrice` | `getActualPrice()` | 0.0 | 48 | 保留 |
| 8 | `theme:ItemInfo.setPrice` | `setPrice(double)` | 0.0 | 26 | 保留（写点：让 **字段直读** 的判定（#1/#2）也拿到 0） |
| 9 | `theme:ItemInfo.setActualPrice` | `setActualPrice(double)` | 0.0 | 10 | 保留（同上） |

### F2 全免费（A11 · 服务端 bean 层）

| # | id | 目标 | 值 | 消费点数 | 判定 |
|---|---|---|---|---|---|
| 10 | `theme:ItemBean.getPrice` | `BaseResItemBean.getPrice()` | "0" | — | 保留（列表页走服务端 bean，与 ItemInfo 是**两层**） |
| 11 | `theme:ItemBean.getActualPrice` | `getActualPrice()` | "0" | 64 | 保留 |
| 12 | `theme:ItemBean.getVipOnly` | `getVipOnly()` | 0 | 33 | 保留 |

### F2 按钮/下载链

| # | id | 目标 | 值 | 判定 |
|---|---|---|---|---|
| 13 | `theme:IncentiveData.getThemeDetailButtonType` | → -1 | 消费点 `ToolBarGroupLayout:209/928`、`DetailModel:918`、`WallpaperDetailView:724` | 保留 |
| 14 | `btn-hide:buy` | `OnlinePreviewBaseActivity.changeVipBtnsVisibility` buy→false | — | 保留（用户显式要求） |
| 15 | `theme:DownloadInfo.isPayedItem`（H1） | → true（**uri 空才触发**） | 分派点 `o52:604` | 保留 |
| 16 | `theme:o52.addDownloadItem`（H2） | 第 3 参 → 3（**uri 空才触发**） | — | **P1 删除**（见 §二.1） |
| 17 | `theme:DownloadModel.getDownloadType`（H2b） | → 3（**uri 空才触发**） | 分派点 `o52:616` 读的就是它 | 保留 |

### 云控统一读点

| # | id | 目标 | 判定 |
|---|---|---|---|
| 18 | `cloud:ShareprefUtils.getBoolean2` | `u15.getBoolean(String,boolean)` | 保留（15 项表驱动） |
| 19 | `cloud:ShareprefUtils.getBoolean1` | `u15.getBoolean(String)` | 保留——**不是** #18 的重复：单参版内部**直接调 `SharedPreferences`**，不调双参版（`u15.java:38`） |

### 服务端配置源头裁剪

| # | id | 目标 | 判定 |
|---|---|---|---|
| 20 | `adsource:SlotData.getList` | 美豆槽位裁剪（`AdSlotFilter`：类型 5/20/22/25/27 + targetId 1019001/1019002） | 保留 |
| 21 | `adsource:BottomTabConfigs.getBottomTabConfigs` | 底部 tab 裁剪（`BottomTabFilter`：3=VIP / 12=福利，含 `MIN_LIST_SIZE=3` 保护） | 保留 |

### F1 VIP

| # | id | 目标 | 值 | 判定 |
|---|---|---|---|---|
| 22 | `theme:VipManager.isVipUser` | → true | 136 处消费 | 保留 |
| 23 | `theme:VipManager.setVipUser` | 写点 → true | 同上 | 保留 |
| 24 | `theme:VipState.getExpireTimeTimestamp` | → 2088-08-08 | **1**（`VipResSavedActivity:611`） | 保留 |
| 25 | `theme:VipState.getRemainingTime` | → now+1.95e12 | `VipCardViewModel:446` | 保留 |
| 26 | `theme:VipState.getVipStatus` | → 2 | `MineVipCardView` switch | 保留 |
| 27 | `theme:VipState.getVipType` | → 2 | 续费入口判定 | 保留 |
| 28 | `theme:VipState.isVipUser` | → true | `VipState.java:355` 独立方法 | 保留（与 #22 是不同类，非重复） |
| 29 | `theme:VipState.getDisplayTimeTimestamp` | → 2088 | `MineVipCardView:430/441`、`HwThemeManagerActivity:894/901` | 保留 |
| 30 | `theme:VipState.getDisplayTime` | → "2088-08-08 00:00:00" | `VipCheckRepo:171+` | 保留 |

> `VipState` 字段**全 private**（`vipState.java:37`）；getter 是类外唯一读路径 ⇒ 不存在"漏斗可替代 getter"的可能（2026-10-10 已删 VipModel 双漏斗，见 design §八·2）。

### F1 挽留弹窗（双保险，非同质重复）

| # | id | 目标 | 判定 |
|---|---|---|---|
| 31 | `theme:RetentionMgr.showDialog` | 展示咽喉空实现 | 保留（调用链 `VipPopCfgVM:652` → `BuildAndShowDialog:72`） |
| 32 | `theme:RetentionActivity.onCreate` | 放行后 finish（防 ARouter 直启） | 保留（与 #31 机制不同，非重复） |

### F3/F4 试用与回退

| # | id | 目标 | 值 | 判定 |
|---|---|---|---|---|
| 33 | `theme:TryOut.isValidTheme` | → true | 消费 `ThemeTimeFenceManager:170`；内部调 `isWareOutDated` | 保留 |
| 34 | `theme:TryOut.isWareOutDated` | → false | 被 #33 内部调用（`tryout/a.java:661`） | 保留 |
| 35 | `theme:TryOut.isSupportTrial` | → true | **全仓零调用者**（仅 `tryout/a.java:642` 定义）；且实现 = `AgreeRepo.isTrialSupportOnline()`，已被云控表 `trial_support_online=true` 覆盖 | **P0 删除** |
| 36 | `theme:TryOut.checkCurrentTheme` | → true | 消费 `ThemeInfo:359/700/4310`、`RecycleTrialTheme:135` | 保留 |
| 37 | `theme:Incentive.duringIncentive` | → true | 消费 `ItemInfo:346`、`DownloadInfo:814`（限时激励窗） | 保留 |
| 38 | `theme:Incentive.getIncentiveExpireTime` | → Long.MAX | 消费 `ResInitHelper:69/71/133/135` | 保留 |
| 39 | `theme:ThemeResManager.getThemeApplyState` | → PERMANENT | 消费 `tryout/a:526/682`、`RecycleTrialTheme:278`、`TryOutTimeFenceService:318/341` | 保留 |
| 40 | `theme:ThemeResManager.isTrialTheme` | → false | **不是** #39 的包装：`isTrialTheme(int)` 独立查询 provider 列表（`ThemeResManager:428`），两条不同代码路径 | 保留 |

---

## 二、包管理器 `InstallerHook`（7 点）

| # | id | 目标 | 值/类型 | 判定 |
|---|---|---|---|---|
| 1 | `ad-request:AdCondition.checkIsSupportAdRequest` | `lt1.b(String)` | false（CONST） | 保留（`wt1` 唯一实现，V3/V4 两条广告链的公共条件门） |
| 2 | `ad-source:AdRespV4.getAdAppList` | `AdRespV4.getAdAppList()` | 空表（CONST） | 保留（第二层防御：万一有响应数据也不渲染） |
| 3 | `ab-request:AbExpUtils.updateAbExpStrategy` | `sx1.Q(boolean)` | BLOCK | 保留（唯一调用点 `InstallStart:373`/`wb2:14`） |
| 4 | `installer:CommonConfig.isNewTaskStartInstallBySourcePkg` | `CommonConfigManager.j(String)` | false | 保留 |
| 5 | `installer:CommonConfig.getSafeModeConfigJson` | `CommonConfigManager.g()` | 全关 JSON | 保留（页面懒加载读点） |
| 6 | `installer:CommonConfig.getSafeModeConfigJsonFromRsp` | `CommonConfigManager.h(...)` | 全关 JSON | 保留（网络响应点；`g()` 内部调 `h(null)`，二者都挂防 R8 内联） |
| 7 | `installer:SafeModeDialogGate.isShowGuideOpenSafeModeDialog` | `n12.f(...)` | false | 保留（弹窗唯一展示门） |

---

## 三、清理明细（逐项实证）

### 3.1 P0：`theme:TryOut.isSupportTrial`（死挂载点）

```
grep -rn "isSupportTrial" --include=*.java .   →  仅 1 行
./com/hihonor/android/thememanager/tryout/a.java:642:    public boolean isSupportTrial() {
```
- **零调用者** ⇒ hook 永远不会被触发（挂了也只是内存里一个死 Method 引用）。
- 且实现体 `return AgreeRepo.isTrialSupportOnline();`（`tryout/a.java:642-644`）—— 该 key 已在
  `CloudSwitchTable` 强制 `trial_support_online = true` ⇒ 即便有人调用，结果也已由云控表决定。

**结论：双重冗余，删除。**

### 3.2 P1：`theme:o52.addDownloadItem`（H2）被 H2b 完全覆盖

```java
// defpackage/o52.java:882  4 参重载 —— 只是"建模型 + 转发"
public synchronized boolean addDownloadItem(Context context, DownloadInfo downloadInfo, int i, boolean z) {
    return addDownloadItem(new a.C0583a().setContext(context).setDownloadInfo(downloadInfo)
        .setNeedLogin(z).setThemeInfo(null).setDownloadType(i).build());   // ← i 只被塞进模型
}
// defpackage/o52.java:604/616  唯一的策略分派点 —— 读的是"模型的 getter"
if (!aVar.getDownloadInfo().isPayedItem()) { ...免费通道... }
int downloadType = aVar.getDownloadType();     // ← H2b 的挂载目标
if (downloadType == 2) ... else if (downloadType == 3) { f71 } ...
```
- 4 参重载的 `i` 参数**最终只流入 `DownloadModel.downloadType` 字段**，而分派点读的是
  `DownloadModel.getDownloadType()` —— 正是 H2b 挂载点。
- H2 与 H2b 的触发条件也等价（都以 `DownloadInfo.uri` 为空为门；4 参重载的 arg1 就是 H2b 的
  `getDownloadInfo()` 同一个对象）。
- 结论：**H2 的两条链路都被 H2b 单独覆盖**，删除 H2 零行为变化（省 1 个挂载点 + 1 个条件路由分支）。

### 3.3 P1：`theme:ItemInfo.isVipResouce` → 应改挂 `getVipOnly`

```java
// common/ItemInfo.java:781
public boolean isVipResouce() { return getVipOnly() == 1; }     // ← 内部就是调 getVipOnly()
// common/ItemInfo.java:688
public int getVipOnly() { return this.vipOnly; }                // ← 纯 getter
```
- 只挂 `isVipResouce` ⇒ **漏掉所有直连 `getVipOnly()` 的消费点**：
  `logic/browse/helper/BrowseDataHelper.java:60/98/129/229/266`
  （`fontInfo/wallPaperInfo/themeInfo/magicbabyInfo.getVipOnly()`，写入浏览模型 `setVipOnly`）。
- 而这些对象（`ThemeInfo`/`WallPaperInfo`/`FontInfo`/`MagicBabyInfo`）**都 `extends ItemInfo` 且未覆盖
  `getVipOnly()`** ⇒ 全部落到 `ItemInfo.getVipOnly()`。
- 改挂 `getVipOnly → 0`：**同时**让 `isVipResouce()` 自然返回 false（覆盖不缩水）+ 补上直连消费点。
- 结论：**点数不变（1→1），覆盖更全，是净改进**。

### 3.4 P2：残留调试物与无用符号

| 项 | 证据 | 动作 |
|---|---|---|
| `xposed/target/SlotProbe.kt` + `ThemeStoreHook.install()` 里 1 行调用 | `private const val ENABLED = false`；美豆槽位裁剪已落地并真机复核 | 删文件 + 删调用行 |
| `xposed/target/VipDebugProbe.kt` + 1 行调用 | 同上；VIP 落地值已定稿（design §四） | 删文件 + 删调用行 |
| `xposed/HookKeys.kt` 的 `KEY_HOOK_ENABLED` | 声明后**零引用**（`grep -rn KEY_HOOK_ENABLED` 仅 1 处 = 定义） | 删 |
| `ThemeStoreHook.findMethod`（私有） | 与 `MethodResolver.resolveMethod` 第一轨**逐字同构**（`loadClass().declaredMethods.firstOrNull{ name/paramCount/returnType }`），`mountOne` 里先调它、失败再调 MethodResolver（后者又做一遍同样的直连） | 删，直接调 MethodResolver（顺带消除一次重复反射） |

### 3.5 P3：结构性（长期维护成本，非缺陷）

1. **两套挂载基建完全复制**：`attemptMount` / `mountOne` / `MountResult` / `MountTask` / `DexHint` /
   `addConst` / `addForceArg` / `logSummary` / 重试常量 —— ThemeStoreHook（19 处符号）与 InstallerHook
   （16 处符号）各写一份。**这是后续最大的维护成本源**（改一处必须记得改两处，漏改会造成两个目标行为分叉）。
   建议：抽 `xposed/HookMount.kt` 公共基类（`abstract class TargetHookBase`：通用 kinds + 重试 + 汇总），
   `TargetHook` 只保留 `packageName` / `install` / 各自的 collect* 与特殊 kind。
   ⚠️ 这是一次**含真机回归风险**的重构（ThemeStoreHook 40 点已真机验证），建议单独一轮做、做完立刻真机验。
2. **源码内堆积 10 处"已删/已废弃"历史注释**（约 40 行，散落在常量区与 collect* 函数里）。
   与项目自身规范冲突（`CLAUDE.md`：源码内只留指针、理由进 `docs/`）。建议：统一挪进
   `docs/theme-store-hook-design.md` §八（审计记录），代码里每处留一行"已删 X，理由见 design §八"。
3. **`featureKey` 管道运行时零输出**：`KEY_F1..F4` / `KEY_I1` 仅供 `MountTask.featureKey` 使用，而
   `featureKey` 只在 `if (DEBUG_LOGGING)`（`private const val false`）分支里被打印 ⇒ R8 直接 DCE，
   运行时**永远不输出**。二选一：① 删掉 `featureKey` 字段与 `KEY_*`（约 20 行）；② 打开 `DEBUG_LOGGING`
   让分组标注真正可用（代价：挂载期日志变多）。

---

## 四、观察项（保留，仅记录语义，避免后人误删）

| 项 | 说明 |
|---|---|
| `theme:ItemInfo.isPayed`（1 个消费点） / `theme:VipState.getExpireTimeTimestamp`（1 个） | 消费点少但**可用**，保留 |
| 云控表 `help_consumer_support_online` / `redpoint_support_online` / `AICREATION_SHOWORHIDE_CONFIG` | 是**功能入口**（客服 / 红点 / AI 创作），不是广告；来自用户给定清单，保留 |
| `ItemInfo.getPayed()` 与 `isPayed()` | 同读一个字段的两个**不同方法**（25 / 1 调用点），互相不可替代 ⇒ 非重复 |
| `cloud:ShareprefUtils.getBoolean1` 与 `getBoolean2` | 单参版**不调**双参版（`u15.java:38` 直接走 `SharedPreferences`）⇒ 必须都挂，非重复 |
| `InstallerHook` 的 `ad-source:AdRespV4.getAdAppList` | 在 `lt1.b` 生效后属**第二层防御**（正常链路永不触发）。保留可防"缓存/旁路"；若要极简可删（7 → 6） |

---

## 五、改后应验判据（真机）

- `ThemeStoreHook: installed 38/38`（清理 P0+P1 后）
- `InstallerHook: installed 7/7`
- 详情页付费主题仍走 VIP 通道下载（`theme-download-chain 路由触发` H1/H2b 两条，不再有 H2）
- 列表/浏览页无 VIP 专属角标（覆盖 `getVipOnly` 直连消费点后）
- 无 `failed` 清单

---

## 六、2026-10-10 真机回归复盘（审计删除的教训）

> 背景：当日 P0–P3 审计删除了若干"静态推断冗余"的挂载点，真机复测发现两处 UI 回归。
> **教训：审计删除必须真机 A/B 验证，静态推理不足以作为删除依据。**

### 6.1 "VIP 已过期"横幅回归（→ 漏斗恢复 + getSecondTitle 置空）

- 当日审计以"`VipState` 字段全 private ⇒ 类外只走 getter ⇒ getter 已短路 ⇒ 漏斗冗余"为由删除
  `VipManager.getLocalVipState/getVipState` 双漏斗。真机复测横幅复活，证明推理有两处漏洞：
  1. `getVipState()` 可返回 **null**（首查未落缓存）——UI 拿到 null 直接走已过期/未登录分支，
     getter 短路对 null 实例无意义；漏斗在 null 时自建实例填会员值；
  2. `VipState` 的 `equals/copy/writeToParcel/toString` 等**类内方法直读字段**，不经任何 getter。
- 且横幅文案真正的来源是 **服务端下发字段 `secondTitle`**（服务端按真实购买记录生成
  "VIP 已过期，立即行动"文案；`MineVipCardView:1789-1792` 判 `secondTitle`+`linkUrl` 均非空才渲染），
  客户端伪造任何本地 getter 都改不了它 ⇒ 需要单独挂 `getSecondTitle → ""` 判空隐藏。
- 处置：漏斗两点恢复（`MountKind.VIP_STATE_FIX`，after 改写实例字段）；`getSecondTitle` 置空串。

### 6.2 VIP tab 删不掉（→ S2b createTabs 前置裁剪）

- S2（`BottomTabConfigs.getBottomTabConfigs` 出口裁剪）只覆盖 **AMS 在线配置刷新链**；
  真机实证存在第二条路径：`refreshBottomTabCacheConfigTask` 读 **MMKV 缓存**
  （`decodeString(bottom_tab_config)` → `GsonUtils.toList` 直解 `List<BottomTabItem>`），
  完全绕过 `BottomTabConfigs`；冷启动用脏缓存建 tab，刷新后 StateFlow 更新 UI 不重建。
- 陷阱：`createTabs` 的 fallback 分支（列表 null / size∉[3,5]）会**本地硬编码建 VIP tab**
  （`if (isSupportVip) createActionTab("VIP")`）——单纯清空缓存反而让 VIP tab 必然复活。
- 处置：新增 S2b `createTabs` 前置裁剪（PRE_PROCESS）：proceed 前把 StateFlow 列表就地裁剪，
  缓存脏不脏无所谓，且列表合法时走宿主正常分支不踩 fallback。

### 6.3 环境陷阱：jadx 反编译包 ≠ 设备运行包

- 曾据 jadx 产物给 `VipState.getIsVipUser` 加挂载点，真机挂载失败（39/1）；
  拉设备实机 APK 用 dex 字符串检索证实：设备包**只有** `isVipUser()`，没有 `getIsVipUser()`。
- **规矩：hook 目标方法存在性必须以设备实机 APK 的 dex 为准验证**（`adb shell pm path` 拉包 →
  zip 解析 dex 查方法名字符串），jadx 产物仅作阅读参考。

### 6.4 环境陷阱：模块重装后被 LSPosed 停用

- `adb install -r` 覆盖安装后，LSPosed 会停用模块 ⇒ 之后拉起的目标进程全部无注入
  （表现为：进程活着、模块日志一条没有）。**必须手动到 LSPosed 管理器重新启用**。
- 排查口诀：目标进程无任何模块日志 ≠ hook 没挂上，先查进程启动时间 vs 模块启用时间，
  再查 LSPosed 是否停用；安装器等按需拉起的进程还要 force-stop 一次才会以新身份注入。

### 6.5 当前基线（2026-10-10 晚）

- `ThemeStoreHook: installed 43/43`（39 基础 + 漏斗 2 + secondTitle 1 + createTabs 前置 1）
- `InstallerHook: installed 7/7`
