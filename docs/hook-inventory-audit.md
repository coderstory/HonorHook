# 挂载点台账与审计决策

> **本文件只记代码里读不到的**：口径、已决的删除决策与理由、**勿删观察项**、真机回归教训。
> **逐点清单不在这里** —— 权威来源 = 数据表 `<Target>Map_<版本>.kt`（theme）与各 `*Hook.kt` 内挂载表（代码），
> 逐点逆向依据 = [`reverse/`](reverse/) 下对应 app 的记录，设计理由 = [`theme-store.md`](theme-store.md)。
> 复核点数（**全命名空间**，勿再漏 `cloud:` / `adsource:` / `btn-hide:` / `ad-request:` / `ad-source:` / `ab-request:`）：
> `grep -rhoE '"[a-zA-Z0-9-]+:[A-Za-z0-9_.$]+"' app/app/src/main/kotlin/top/honorhook/xposed/target/ | sort -u | wc -l`
> 当前结果（2026-10-27 复核）：合计 **161**（较 10-20 的 142：+appmarket 7 / ime +1（T5 开屏总闸）/ systemui +1（极客中心）等 v1.2.x 增量；theme 49 / installer 11 + 旧版表 8 / systemui 8 / exam8 10 / launcher 5 / ouc 11 / **ime 6** / mms 4 / xhs 15 / wallpaper 4 / ads 11 = ads+ops+tracking 按包注册 / sysmgr 3 / settings 2 / jd 1 / mfauth 1 / upgradeguide 1 / mihealth 1【已关线未注册】；分目录去重之和 105 + 顶层散件 56 = 161）。
> ⚠️ 该命令是**全库去重**计数（逐文件去重之和 152；10 个 id 同时出现在源码与旧版表，如 `installer:SafeModeCard.*`，故分列之和 > 142）；且数的是**源码 id 字面量**（代理口径，与运行期任务非一一对应，例：mms 4 id ↔ `installed 2/2`）⇒ 逐 hook 的应 M 以 §五 判据快照为准。

## 一、口径与当前基线

- **作用域包数口径（2026-10-26，T11 统一）**：**24 包**（scope.list 全量）/ 22 个已注册 TargetHook 实现 / 33 个注册实例——以 `RegistryConsistencyTest` 动态断言为准（此前 AGENTS.md「22/20」、测试注释「18 行」等漂移数字已清；另 MiHealthHook 已实现未注册，属死代码，不计入）。

### 版本对照（**别搞反**：代码基线是 hook 编写时反编译的那版，设备实际是跑在真机上的那版）

| 目标 | 代码基线（`HOST_VERSION_BASELINE`） | 设备实际（2026-10-11 快照） | 关系 |
|---|---|---|---|
| 主题商店 | `20.1.63.305` | `20.0.11.327` | 设备**更旧** |
| 包管理器 | `14.0.37.301` | `14.0.22.326` | 设备**更旧** |
| 设置 | `14.0.0.210` | `14.0.0.210` | 一致 |
| 系统桌面 | `18.0.0.234` | `18.0.0.234` | 一致 |
| 成考题库 | `5.9.0.0-release` | `5.9.0.0-release` | 一致 |
| SystemUI | `17.0.0.1` | `17.0.0.1` | 一致 |

> 包管理器现行代码除基线 `14.0.37.301` 外另维护两张额外版本表：`14.0.22.326`（8 点）/ `14.0.38.301`（11 点），随 `FeatureCatalog.extraVersions` 注册、由 `installerTables` 按精确版本分发。
> 38.301 表真机 `installed 11/11`（2026-10-20 16:33）；22.326 表**当前无真机判据**（设备未回退到该版本）。

**2026-10-24 复核（现行设备）**：主题商店 OTA 至 `20.1.65.302`——**`installed 49/49` 全绿**
（10:21 真机，两轮冷启一致，30 通路逐项满绿，「宿主版本与 hook 基线一致: 20.1.65.302」=
精确套命中；49 点 MT 核对 + 新精确套 `ThemeStoreMap_20_1_65_302`）；小红书 OTA 至 `9.51.0`
（`X1 3/3 · X2 3/3 · X4 1/1 · X5 5/5` 全绿，X2b 复活；videoClick 正式不挂载——yi9.f 证伪 + 功能被
noteSaveEntry 覆盖；图片保存主链修复 getShortUrl 服务定位器 i3c.d→z3c.d 后真机三连解析成功，
详见 `docs/reverse/xhs.md` 9.51 节）；短信 T4（AIM 推广按钮 GONE）挂载实证（22:42）；
其余与 10-20 快照一致（installer 14.0.38.301 / launcher 18.0.0.259 / sysmgr 17.0.0.455 /
settings 14.0.0.210 / ouc 18.0.3.230 / mms 19.0.9.306 / systemui 17.0.0.1）。

**2026-10-27 复核（现行设备）**：24 scope 包 dumpsys 全量直读（root `su -c`，adb 通道不在位时的等价只读取法）+ 当日冷启动判据（明细见 §五 重采注记）。变化项：**sysmgr 457（SysmgrHook 3/3）**、**京东 16.1.50（JdHook 1/1）**、日历 20.0.9.325（三 hook 全绿）、快游戏 16.0.34.320（用户停用无判据）、**appmarket 16.1.9.302（v1.2.2 新目标 `AppMarketHook` 7/7 首采，此前 §五 漏行已补）**；其余与 10-24 快照一致。

**2026-10-20 复核（现行设备）**：主题商店 `20.1.63.305`（`49/49` 全绿）；**包管理器 `14.0.38.301`**（代码基线 `14.0.37.301`；升级当时 `installed 1/11` → 同日 16:33 真机复测 **`installed 11/11`**，11 点**全部直连命中**——同一会话内无「挂载点漂移命中」行、无「宿主版本读取失败」行 ⇒ 38.301 精确表逐点正确；**挂载层 11/11 已达成，行为层 2026-10-20 由用户真机实测通过**）；**系统桌面 `18.0.0.259`**（`5/5` 挂载全活，仅版本自检 WARN）；**系统管家 `17.0.0.455`**（`3/3` 挂载全活，WARN）；其余与基线一致。

> **历史（2026-10-11；结论已作废，勿再引用这两个数字）**：当时 theme `4/43` / installer `0/11`，原因是代码基线比设备实际新；
> 设备升级后 theme 全绿（2026-10-12 起；表扩至 49 点后仍 `49/49`）。
> **现行（2026-10-20 16:33 真机）**：installer 设备已升至 `14.0.38.301`（代码基线 `14.0.37.301`）→ 升级当时 `installed 1/11`（历史快照）→ 同日复测 **`installed 11/11`**，无失败清单；该版适配代码（11 点表）判据**已达成**。
> 查设备版本：`tools/dev.sh exec adb shell dumpsys package <pkg> | grep versionName`。

- **版本适配决策（2026-10-11）**：theme 维持"不做旧版映射"（设备升级宿主到 `20.1.63.305` 即全绿——当时 43 点，2026-10-20 已扩至 49 点）；
  installer 因**设备无升级途径**恢复旧版适配——`InstallerMap_14_0_22_326.kt`（8 点）已建并接入
  `HostVersion.pick` 精确套，逐点漂移明细见该表头注；云控 3 点旧版无对应物（诚实跳过）。
  **2026-10-20 追加**：设备升至 `14.0.38.301` 后新增 `InstallerMap_14_0_38_301.kt`（11 点）并注册精确套，
  代码/构建/JVM 单测已过；**真机判据已于同日 16:33 达成 `installed 11/11`**（11 点全直连命中）。
  配套机制：宿主版本不再在 `install()` 里读，改由 `MountScheduler.postDelayed` 延后探版本（重试延迟表
  `0 / 200 / 600 ms`），读取走**全公开 API 双取法** `HostVersion.resolveHostVersion(apkPath, packageName)`
  （系统上下文 PM 直读 APK 磁盘 → 宿主 Application PM 查包名），选表 = `HostVersion.pick(installerTables, actual)`。
  旧取法 `ActivityThread.currentPackageManager` 在本 ROM **根本不存在**（详见 §四·6）⇒ 选表曾恒读不到版本。
  HostVersion 选表框架：未来适配其他版本 = 反编译 → 手写 `<Target>Map_<版本>.kt` → 注册精确套。
- **theme 4/43 的成功点构成（反编译 20.0.11.327 对照查实）**：T1
  `IncentiveData.getThemeDetailButtonType` 真命中；A11×3（`BaseResItemBean` 三读点）是
  **DexKit 兜底误打误撞**——基线统一基类在旧版不存在（旧版为 GetThemeRspBean$ThemeBean 等
  每资源独立 bean），直连失败后按"包名+方法名+返回类型"兜底 firstOrNull 命中其中一个 bean。
  命中哪个 bean 顺序不保证 ⇒ **不可依赖**，升级宿主后即恢复直连精确命中。

- **唯一权威 = 真机 `installed N/M` 的 M**（落 LSPosed 框架日志）。**口径已于 2026-10-11 对齐**：此前记录的"文档 40/43/39 与代码 37 不一致"是**复核命令写错**造成的假漂移——旧命令的正则只含 6 个命名空间，漏了 `cloud:` / `adsource:` / `btn-hide:` 等，报出的 theme 37 / installer 8 并非真实点数。用上方的全命名空间命令复查，代码计数与 README 完全一致（theme 43 / installer 11）。
- 2026-10-10 晚真机基线：主题商店 `installed 43/43`、包管理器 `11/11`、设置 `1/1`。SystemUI / 桌面 / exam8 各 1 / 1 / 10。
- **system_server 守卫 hook = 放弃决策（2026-10-10）**：该 fork 只回调 `onSystemServerLoaded`，libxposed api 102 无此回调类型，合成 stub 成本 > 收益。**勿重新评估**。

## 二、已决清理（2026-10-10，含理由 —— 复删/复挂前先读）

| 项 | 决策 | 理由（实证） |
|---|---|---|
| `theme:TryOut.isSupportTrial` | **删** | 方法**零调用者**（死挂载，挂了永不生效） |
| `theme:o52.addDownloadItem`（H2） | **删** | 被 H2b 完全覆盖（详情页 builder 版链不经 4 参版） |
| `theme:ItemInfo.isVipResouce` | **改挂 `getVipOnly`** | 原方法覆盖不全，`getVipOnly` 是更本源的数据源（根源原则） |
| `SlotProbe.kt` / `VipDebugProbe.kt` / `KEY_HOOK_ENABLED` / 私有 `findMethod` | **删** | 调试残留物；`mountOne` 直走 `MethodResolver.resolveMethod` |
| P3 结构性（两套挂载基建重复 / 源码内历史注释 / `featureKey` 管道零输出） | **未执行** | 含回归风险，需单独一轮 + 真机验证 |
| `InstallerHook` 7 → 11 | **增** | +安全模式提示卡/推广卡 2 + 安全检测分发器短路 1 + 指纹直通 1 |

## 三、观察项（**勿删**，仅记录语义）

| 项 | 说明 |
|---|---|
| `theme:ItemInfo.isPayed`（1 消费点）/ `theme:VipState.getExpireTimeTimestamp`（1） | 消费点少但**可用**，保留 |
| 云控表 `help_consumer_support_online` / `redpoint_support_online` / `AICREATION_SHOWORHIDE_CONFIG` | 是**功能入口**（客服/红点/AI 创作），**不是广告**；来自用户给定清单，保留 |
| `ItemInfo.getPayed()` 与 `isPayed()` | 同读一个字段的两个**不同方法**（25 / 1 调用点），互不可替代 ⇒ **非重复** |
| `cloud:ShareprefUtils.getBoolean1` 与 `getBoolean2` | 单参版**不调**双参版（`u15.java:38` 直走 `SharedPreferences`）⇒ **必须都挂** |
| `ad-source:AdRespV4.getAdAppList` | 在请求门 `lt1.b` 生效后属**第二层防御**（正常链路永不触发），保留防缓存/旁路；要极简可删（11 → 10） |

## 四、真机回归教训（2026-10-10，删除与排查的判据）

1. **静态推理不足以作为删除依据，必须真机 A/B**。当日以"`VipState` 字段全 private ⇒ 只走 getter ⇒ getter 已短路 ⇒ 漏斗冗余"删掉双漏斗，真机横幅复活。两处漏洞：① `getVipState()` 可返 **null**（首查未落缓存），getter 短路对 null 实例无意义；② `equals/copy/writeToParcel/toString` 等**类内方法直读字段**，不经 getter。
2. **文案可能来自服务端字段**：「VIP 已过期」真实来源是服务端下发的 `secondTitle`（按真实购买记录生成，`MineVipCardView:1789-1792` 判 `secondTitle`+`linkUrl` 非空才渲染）⇒ 客户端伪造 getter 改不了，须单独挂 `getSecondTitle → ""`。
3. **同一配置可能有旁路读取**：底 tab 裁剪只覆盖 AMS 在线配置链不够——还有 MMKV 缓存链（`refreshBottomTabCacheConfigTask` 直解 `bottom_tab_config`）绕过；且 `createTabs` 的 fallback 分支（列表 null / size∉[3,5]）会**本地硬编码建 VIP tab**，单清缓存反而必然复活 ⇒ 处置 = 在 `createTabs` 前置就地裁剪（PRE_PROCESS）。
4. **jadx 产物 ≠ 设备运行包**：曾据 jadx 给 `VipState.getIsVipUser` 加挂载点，真机挂载失败——拉设备实机 APK 检索 dex 证实设备只有 `isVipUser()`。**规矩：目标方法存在性以设备实机 APK 的 dex 为准**（`adb shell pm path` 拉包 → dex 字符串检索），jadx 仅供阅读。
5. **重装即被停用**：`adb install -r` 覆盖安装后 LSPosed 会停用模块（表现为进程活着但零模块日志）⇒ 必须重新启用（`dev.sh enable-module` / `deploy-release` 已封装）；按需拉起的进程还要 force-stop 一次才以新身份注入。
6. **「成员不存在」与「被 hidden-API 黑名单拒绝」抛同一种异常类型**（ART 侧同为 `NoSuchMethodException`/`IllegalAccessException`）⇒ 反射探针拿到异常**不能**直接判成黑名单拦截。实证：`ActivityThread.currentPackageManager` 在 Android 14 AOSP 源码全文 0 命中，真机 `getMethod` 与 `getDeclaredMethod` 均 `NoSuchMethodException` 且成员清单里也没有 ⇒ 是**成员不存在**；早先误判为 hidden-API 拦截并差点引入绕过库（实为公开 API 即可解决，零新依赖）。**纪律：异常类型只说明「调用失败」，不说明「为什么失败」——下「被拦」的结论必须另找证据**（成员清单 / AOSP 源码 / 另一条不依赖该成员的公开路径能取到值）。

## 五、应验判据

### 判据快照（**逐 hook 应 M / 最近实测 N 的单一事实源**；2026-10-27 复核）

> **最近一次重采：2026-10-27 21:11–21:52**（模块 1.2.2 当日判据；adb 通道不在位，改走本机 KernelSU root `su -c dumpsys package <pkg>` 直读版本 + `monkey` 冷启动采判据，判据行仍从 LSPosed 框架日志抓）。
>
> **当日版本复核（24 scope 包全量）**：较 10-24 快照变化的宿主 = **系统管家 17.0.0.455→17.0.0.457**、**京东 16.1.0→16.1.50**（10-23 夜间更新还带来日历 20.0.9.325 / 快游戏 16.0.34.320 / 应用市场 16.1.9.302；小红书 9.51.0 同夜但 10-24 已核）；其余（theme 20.1.65.302 / installer 14.0.38.301 / launcher 18.0.0.259 / settings 14.0.0.210 / ouc 18.0.3.230 / mms 19.0.9.306 / systemui 17.0.0.1 / ime 10.32.44 / 天气 18.1.6.312 / 云音乐 3.7.30）与既有记录一致。
>
> **结论：更新后的宿主挂载层全绿**——SysmgrHook 3/3（457，仅版本自检 WARN）、JdHook 1/1（16.1.50，6 进程各一行，仅 WARN）、日历三 hook 2/2·1/1·3/3（20.0.9.325）、XhsHook X1 3/3·X2 3/3·X4 1/1·X5 5/5（9.51.0）、AppMarketHook 7/7（16.1.9.302 首采）；theme 49/49 / installer 11/11 / settings 2/2 / launcher 5/5 / systemui 4/4（v1.2.2 单行新口径）/ ouc 11/11 / mms 2/2 / ime 5/5 / 天气 ads 2/2·tracking 2/3（旧账）/ 云音乐 tracking 3/3 当日复测一致。**快游戏与荣耀浏览器 enabled=3（用户停用）无进程 ⇒ 无判据行**；壁纸三 hook / UpgradeGuideHook 无新判据；Exam8Hook / 健康/优酷未安装。行为层（京东开屏跳过、系统管家弹窗抑制）待用户下次自然使用时顺带确认。

> **最近一次重采：2026-10-20 18:41–18:43**（新判据行格式 `HookName[包名]: installed N/M`；模块 1.1.7 覆盖安装 + `enable-module` 后，逐目标 `tools/dev.sh relaunch <pkg>` 强停冷启动，判据行用 `tools/dev.sh modlog-grep 'installed'` 从 LSPosed 框架日志抓）。
>
> **重采结果与上一版快照逐值一致**（theme 49/49、launcher 5/5、settings 2/2、sysmgr 3/3、ime 5/5、ouc 11/11、ads 2/2、ops 1/1、tracking 3/3、jd 1/1、mfa 1/1、mms 2/2、xhs X1 3/3·X2 3/3·X4 1/1·X5 7/7）⇒ **功能开关过滤下沉（Task 6）与判据行带包名（Task 8）都没有改变 M/N 口径**（开关全开时 M 与过滤前相同）。
>
> **本轮未采到新格式判据行的目标**（冷启动未起 / 需人工触发，不等于失效）：ads+tracking 的 quickgame / browser / youku 三包、壁纸三 hook（Transcode / Loop / Timeline）、`UpgradeGuideHook`、`Exam8Hook`（最近已知 10/10）、`SystemUiHook·U6/U7`（force-stop SystemUI 有黑屏风险且本项目禁 reboot 无兜底 ⇒ 主动跳过，留人工）。`InstallerHook` 已于 18:51 用 `am start` 拉起进程补采（新格式 11/11），不在此列。

| hook | 最近实测 | 备注 |
|---|---|---|
| `ThemeStoreHook` | 49/49 | 宿主 20.1.65.302（精确表命中；2026-10-27 复测一致） |
| `InstallerHook` | **11/11** | 宿主 `14.0.38.301`（2026-10-20 **18:51 真机，新判据行格式** `InstallerHook[com.android.packageinstaller]: installed 11/11`）：11 点**全直连命中**，无失败清单、无漂移命中行；随后 `宿主版本与 hook 基线一致: 14.0.38.301`（精确表命中 ⇒ 自检一致）。**M = 开关过滤后的点数，随选表路径变化**：22.326 = 8 / 37.301（基线源码路径）= 11 / 38.301 = 11。**挂载层 11/11 已达成，行为层 2026-10-20 由用户真机实测通过**。冷启动方式：`am start -n com.android.packageinstaller/com.android.packageinstaller.PackageInstallerActivity`（无需真实走安装流程） |
| `SettingsHook` | 2/2 | |
| `OucHook` | 11/11 | 三个宿主进程（ouc / dataupdateservice / pluginupdateservice）均命中 |
| `UpgradeGuideHook` | 无近期判据 | 引导页不出现即不注入 |
| `MmsHook` | 2/2 | |
| `ImeHook` | 6/6 | T5 开屏总闸并入（2026-10-27，`ime:px5.b` 直连命中；feature=`ime.splash_ad_off`，M 随开关过滤；pitfall 见 `docs/reverse/sogou-ime.md` §一） |
| `Exam8Hook` | 设备未安装 | 最后已知 10/10 |
| `LauncherHook` | 5/5 | 宿主 `18.0.0.259`（超基线，仅 WARN）；同包 `HonorAdsHook` 2/2、`HonorTrackingHook` 2/3 |
| `SystemUiHook` | 4/4 | v1.2.2 起单行新口径（极客中心图标点并入，M 3→4）；宿主 17.0.0.1 未变，2026-10-27 实测 |
| `AppMarketHook` | 7/7 | 宿主 `16.1.9.302`（2026-10-27 21:37 首采；v1.2.2 应用市场净化 7 点，逆向档案 `docs/reverse/appmarket.md`，基线即本版） |
| `JdHook` / `MfaAuthHook` | 1/1 / 1/1 | 京东宿主 `16.1.50`（2026-10-27 21:51，6 进程各一行 1/1；仅版本 WARN vs 基线 16.1.0）；MFA 1.0.5 未变 |
| `SysmgrHook` | 3/3 | 宿主 `17.0.0.457`（2026-10-27 21:12 实测；455→457 OTA 后挂载全活，仅版本自检 WARN vs 基线 414） |
| `XhsHook` | X1 3/3 · X2 3/3 · X4 1/1 · X5 7/7 | X5 的 4/5 已随 v1.1.6 修复；2026-10-20 16:48 换版本源（`resolveHostVersion`）后**复测逐字一致**（宿主 9.50.0，走基线坐标不变）。**M 按宿主版本分档**：9.50.0 = 上表数值；**9.49.1 覆盖套只有 X5 变 ⇒ X5 M=4**（noteSave / aigcEdit / downloader 三点该版本无此坐标 ⇒ 显式缺席，不是失效；X1/X2/X4 与版本无关，M 不变）——旧宿主自查时勿把 N<M 判成宿主版本错位 |
| `HonorAdsHook` / `HonorOpsAdHook` | 2/2 / 1/1 | 按 scope 逐包注册；**判据行按包分列**：`HonorAdsHook` 注册 5 个包（`com.hihonor.calendar` / `com.hihonor.android.totemweather` / `com.honor.browser` / `com.hihonor.quickgame` / `com.hihonor.youku.video`）⇒ 5 行 `installed 2/2`；`HonorOpsAdHook` 仅日历 ⇒ 1 行 `installed 1/1`。本行的 2/2、1/1 是**单包口径**（声明级共 3 点 = ads 2 + ops 1）；2026-10-27：日历 `20.0.9.325` 2/2·1/1、天气 `18.1.6.312` 2/2 实测一致；快游戏 `16.0.34.320` / 荣耀浏览器 `3.7.1.301` enabled=3（用户停用）无判据行，优酷未安装 |
| `HonorTrackingHook` | 日历 3/3；桌面·天气 2/3；云音乐 3/3 | 2/3 = 已知旧账「Ex 工厂收口」1 点（`_instance_ex_tag` 类不存在）；2026-10-20 18:41 重采仍 2/3，且**安装前的旧模块在 17:23 同样是 2/3** ⇒ **非回退**，历史同进程常态；对应 WARN「类/方法不存在，已重试 2 轮，放弃」；2026-10-27：日历 `20.0.9.325` 3/3、云音乐 `3.7.30` 3/3、天气 2/3（旧账未变） |
| 壁纸三 hook（Transcode / Loop / Timeline） | 无近期判据 | 需触发壁纸编辑或应用才注入 |

> M 随代码变更、N 随宿主版本：**改完 hook 回来更新本表**。

> **「应 M 值」脚本化评估结论（2026-10-26，T11）**：**不可脚本化，维持人工维护，勿再评估**。原因：源码 id 字面量与运行期任务非一一对应（mms 4 id ↔ `installed 2/2`）、M 随宿主版本分档（installer 22.326=8 / 37.301=11 / 38.301=11）且受开关过滤影响、MERGED/MERGED_CONFLICT 合并口径需人工判读——脚本只能数声明 id，推不出运行期 M。

> **判据行格式（2026-10-20 起）**：`HookName[宿主包名]: installed N/M`（如 `HonorAdsHook[com.hihonor.calendar]: installed 2/2`）—— 同一 hook 名按包注册多次（HonorAds 5 个包）时靠包名区分归属；`installed N/M` 子串本身不变，按它抓的脚本不受影响。

> **两种粒度（2026-10-20 补）**：本表的点数是**声明级**（一个挂载点声明一次，跨包共用），判据行是**按包**（按包注册的 hook 每个包各出一行）。二者不可直接相加：HonorAds 声明级 2 点，判据行则是 5 个包各 `2/2`。对照时先按 `HookName[包名]` 对齐到本表的**单包**口径，再比 N。

> **合并口径（2026-10-20 起）**：两个挂载点落到同一方法时**只挂一次**（`MERGED`）⇒ 计成功、不扣 N、不进失败清单；若**占用方的功能开关与本点不同**（`MERGED_CONFLICT`）⇒ 挂上去的是别人的拦截器，本点意图未生效 ⇒ **计失败不计 N**（该点会出现在失败清单里）。判读时看到 `N<M` 且失败清单里躺着「合并」条目，先看是不是这个口径，别当成宿主版本错位去改实现。

- 各目标 `installed N/M` 全命中、无 `failed` 清单；
- 详情页付费主题仍走 VIP 通道下载（日志 `theme-download-chain 路由触发` H1/H2b 两条，不再有 H2）；
- 列表/浏览页无 VIP 专属角标（覆盖 `getVipOnly` 直连消费点后）。
