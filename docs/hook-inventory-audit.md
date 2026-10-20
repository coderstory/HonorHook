# 挂载点台账与审计决策

> **本文件只记代码里读不到的**：口径、已决的删除决策与理由、**勿删观察项**、真机回归教训。
> **逐点清单不在这里** —— 权威来源 = 数据表 `<Target>Map_<版本>.kt`（theme）与各 `*Hook.kt` 内挂载表（代码），
> 逐点逆向依据 = [`reverse/`](reverse/) 下对应 app 的记录，设计理由 = [`theme-store.md`](theme-store.md)。
> 复核点数（**全命名空间**，勿再漏 `cloud:` / `adsource:` / `btn-hide:` / `ad-request:` / `ad-source:` / `ab-request:`）：
> `grep -rhoE '"[a-zA-Z0-9-]+:[A-Za-z0-9_.$]+"' app/app/src/main/kotlin/top/honorhook/xposed/target/ | sort -u | wc -l`
> 当前结果（2026-10-20 复核）：合计 **142**（theme 49 / installer 11 + 旧版表 8 / systemui 7 / exam8 10 / launcher 5 / ouc 11 / ime 5 / mms 4 / xhs 14 / wallpaper 4 / ads 3 = ads 2 + ops 1 / tracking 3 / sysmgr 3 / settings 2 / jd 1 / mfauth 1 / upgradeguide 1 / mihealth 1【已关线未注册】）。
> ⚠️ 该命令是**全库去重**计数（逐文件去重之和 152；10 个 id 同时出现在源码与旧版表，如 `installer:SafeModeCard.*`，故分列之和 > 142）；且数的是**源码 id 字面量**（代理口径，与运行期任务非一一对应，例：mms 4 id ↔ `installed 2/2`）⇒ 逐 hook 的应 M 以 §五 判据快照为准。

## 一、口径与当前基线

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

### 判据快照（**逐 hook 应 M / 最近实测 N 的单一事实源**；2026-10-20 复核）

| hook | 最近实测 | 备注 |
|---|---|---|
| `ThemeStoreHook` | 49/49 | 宿主 = 基线 |
| `InstallerHook` | **11/11** | 宿主 `14.0.38.301`（2026-10-20 16:33 真机）：11 点**全直连命中**，无失败清单、无漂移命中行。**M = 开关过滤后的点数，随选表路径变化**：22.326 = 8 / 37.301（基线源码路径）= 11 / 38.301 = 11。**挂载层 11/11 已达成，行为层 2026-10-20 由用户真机实测通过** |
| `SettingsHook` | 2/2 | |
| `OucHook` | 11/11 | 三个宿主进程（ouc / dataupdateservice / pluginupdateservice）均命中 |
| `UpgradeGuideHook` | 无近期判据 | 引导页不出现即不注入 |
| `MmsHook` | 2/2 | |
| `ImeHook` | 5/5 | |
| `Exam8Hook` | 设备未安装 | 最后已知 10/10 |
| `LauncherHook` | 5/5 | 宿主 `18.0.0.259`（超基线，仅 WARN）；同包 `HonorAdsHook` 2/2、`HonorTrackingHook` 2/3 |
| `SystemUiHook` | 3/3 + U6 3/3 + U7 3/3 | 宿主未变 |
| `JdHook` / `MfaAuthHook` | 1/1 / 1/1 | |
| `SysmgrHook` | 3/3 | 宿主 `17.0.0.455`（超基线，仅 WARN） |
| `XhsHook` | X1 3/3 · X2 3/3 · X4 1/1 · X5 7/7 | X5 的 4/5 已随 v1.1.6 修复；2026-10-20 16:48 换版本源（`resolveHostVersion`）后**复测逐字一致**（宿主 9.50.0，走基线坐标不变） |
| `HonorAdsHook` / `HonorOpsAdHook` | 2/2 / 1/1 | 按 scope 逐包注册 |
| `HonorTrackingHook` | 日历 3/3；桌面·天气 2/3 | 2/3 = 已知旧账「Ex 工厂收口」1 点（`_instance_ex_tag` 类不存在）；2026-10-20 同轮回归仍 2/3（launcher 2/3×37 次、totemweather 2/3×36 次）⇒ **非回退**，历史同进程常态 |
| 壁纸三 hook（Transcode / Loop / Timeline） | 无近期判据 | 需触发壁纸编辑或应用才注入 |

> M 随代码变更、N 随宿主版本：**改完 hook 回来更新本表**。

- 各目标 `installed N/M` 全命中、无 `failed` 清单；
- 详情页付费主题仍走 VIP 通道下载（日志 `theme-download-chain 路由触发` H1/H2b 两条，不再有 H2）；
- 列表/浏览页无 VIP 专属角标（覆盖 `getVipOnly` 直连消费点后）。
