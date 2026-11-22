# appmarket（荣耀应用市场）逆向档案

宿主：`com.hihonor.appmarket` **16.1.9.302**（aapt2 实证，2026-10-25 真机拉取 base.apk 到 system-files/）。
规格来源：`docs/research/2026-10-24-system-audit/53-应用市场净化规格书.md`。本档是 53 号在本版落地的实证修正。

## Hook 点现状（AppMarketHook.MARKET_POINTS，7 点）

| id | 类#方法 | 签名 | 判据 |
|---|---|---|---|
| market:recv.AlarmCheckReceiver | slientcheck.alarmwake.AlarmCheckReceiver#onReceive | (Context,Intent)→void，manifest 组件 + AOSP 回调 | installed N/M |
| market:dlg.OperationDialog.k0 | operation.ui.OperationDialog#k0 | (FragmentActivity,OperationVO,Runnable)→void（jadx L326） | 同上 |
| market:dlg.ToolPagePop.g0 | operation.toolpage.ToolPageActivityPopDialog#g0 | ()→void，private final（jadx L87） | 同上 |
| market:dlg.FloatingPush.h | operation.FloatingPushHandler#h | (String,String)→void（jadx L246） | 同上 |
| market:notif.notifyMultiIcon | UpdateNotificationManager#B | (I,NotificationInfo,Creator$a,ArrayList)→Z 返 false，private static（MT MCP 核签，140 insns） | 同上 |
| market:notif.notifyQueueStyle | UpdateNotificationManager#C | (NotificationInfo,UpdateNotificationManager$a)→Z 返 false（99 insns） | 同上 |
| market:notif.createNotify | UpdateNotificationManager#D | (I,NotificationInfo,Creator$a)→Z 返 false（131 insns，独占 createNotify 串） | 同上 |

**更新提醒通知族钉死说明（链路级实证，2026-10-25 MT MCP xref）**：
- 调用链 `checkNotifyAppUpdate 协程 → c(NotificationInfo,ea5)→Z → f(NotificationInfo,Channel)→Z → B/C/D`
- B/C/D 全部 `invoke-static`（无 thisObject 依赖，static 拦截返 false 合法）；f 内每调用点只包一次
  Kotlin `Result.constructor-impl(Boolean.valueOf(...))`，**无重试循环**；f 自身的
  `maxNumberLimit → return false` 证明 false = 「不发通知」的原生语义
- B/C/D 是 R8 单字母名（OTA 必漂移），每点带 `dexStrings`（各方法独占日志串）走 DexKit 宽松轨兜底
- 弹窗三点 k0/g0/h 为 show 入口短路（void 不执行 = 不展示）；Runnable 参数是点击行为，不 show 不消费，无泄漏面

## 16.1.11.302 补充：tamer 六个虚挂点的真因是 returnType（2026-11-22）

现象：`TamerWakeHooks 7/9`、`TamerPushHooks 7/8`、`TamerFeedHooks 6/9` 长期不变，失败清单写「类/方法不存在」。
实证：这些类和方法**都在**（jadx 全量产物可逐个核），错的是挂载点声明的 `returnType` —— `MountEngine`
的解析把 returnType 当**硬条件**（`xposed/core/MethodResolver.kt` 精确比对 canonical 名），不符即 miss，
汇总行却统一显示成「类/方法不存在」，很容易误导成宿主删点。

| 点 | 声明（错） | 宿主真实签名（jadx 实证） |
|---|---|---|
| `boot.BootController#J` | void | `public static Object J(rv, boolean, SuspendLambda)` — `boot/BootController.java:174` |
| `boot.provider.WakeMarketProvider#call` | Object | `public final Bundle call(String, String, Bundle)` — `boot/provider/WakeMarketProvider.java:26` |
| `msgcenterview.repository.a#y` | void | `Object y(List, ContinuationImpl)` — `msgcenterview/repository/a.java:509` |
| `mine.viewmodel.MarketManageViewModel#I` | Object | `kotlinx.coroutines.s I(MutableLiveData, int, String, boolean)` — `mine/viewmodel/MarketManageViewModel.java:249` |
| `appupdate.UpdateManagerActivity#access$setRecommendResponseData` | void | `Object access$setRecommendResponseData(UpdateManagerActivity, BaseResp, boolean, nv2)` — `appupdate/UpdateManagerActivity.java:731` |
| `base.details.recommend.AppDetailRecommendViewModel#h` | void | `kotlinx.coroutines.s h(11 参)` — `base/details/recommend/AppDetailRecommendViewModel.java:32` |

修法：声明改成宿主真实返回类型（`kotlinx.coroutines.s` 照写全名即可，解析按字符串比对）。改后真机
`installed 9/9 · 8/8 · 9/9`（2026-11-22，主进程判据行）。**教训**：汇总行把「解析 miss」写成
「类/方法不存在」；遇到 N<M 且失败清单点得（类名/方法名清楚）时，先用 jadx 核 returnType 与参数个数，
再怀疑宿主删点。

## §16.1.11.302 版本适配（2026-11-19，MT MCP outline 实证）

OTA 后 `installed 5/7`，失败两点均为 R8 重命名（通知族三点 dexStrings 兜底坐标全中，未漂移）：

- **运营弹窗渲染入口**：静态 `OperationDialog.k0(FragmentActivity,OperationVO,Runnable)V` 不复存在
  （k0 被重用为无关的 private final k0()V）。调用链实查：MainFrameFragment$reShowOperationDialog$2 →
  `OperationDialog.n0(Landroidx/fragment/app/FragmentActivity;Lcom/hihonor/appmarket/operation/ui/OperationVO;Ljava/lang/Runnable;)V`
  （public 实例，同 3 参形状）。
- **工具页弹窗展示**：`ToolPageActivityPopDialog.g0()V 私有 final` → `j0()V 私有 final`（形态一致）。

落地：AppMarketHook 首次引入版本选表（仿 ThemeStoreHook）——`versionTables` 内 16.1.11.302 精确套
`copy(methodName="n0"/"j0")`，默认套 MARKET_POINTS 兜底；FeatureCatalog extraVersions 加 16.1.11.302、
16.1.10.302 登记 versionsSharingBaseline（同一默认套）。VersionSupportTest 对账已纳入 AppMarketHook。

## §缓期清单（签名不可靠，不硬挂）

1. **SplashManager / CachedSplashScreenService（开屏族 P1）**：`Lcom/hihonor/appmarket/main/splash/SplashManager;`
   与 CachedSplashScreenService 的**外层类描述符在全部 6 个 dex 中不存在**——只有
   `SplashManager$startSplashFromInitByV3$1` 这类 Kotlin `@DebugMetadata` 残留串。规格书把
   DebugMetadata 名当成了运行期名；真类已被 R8 改名。再挂 = NOT_RESOLVED 假目标。
   补点路径：以 `newsplash:` 日志串做 DexHint 内容锚（宽松轨，唯一胜出才采纳）+ 真机判据验证。
2. **OperationServiceImpl 四 suspend（弹窗数据源 P1）**：类名真（classes2/3 双 dex），但
   tryFetchWindowConfig 等是 suspend 函数（字节码 `(..., Continuation) → Object` + 状态机 invokeSuspend），
   jadx 16.1.9.302 反编译退化为寄存器输出，挂点选择（公有 suspend vs 内部 invokeSuspend）
   需要 MT MCP 离线核签后再定（勿给非空型 null——53 号注意原文）。
3. **UpdateNotificationManager 通知族**：✅ 已核签落地 3 点（B/C/D，见上表）。UpdateBadgeManager /
   MessageCenterBadgeManager 仍是 DebugMetadata 幽灵名（外层类 6 dex 零命中）。
4. **checkNotifyAppUpdate 调度入口（未挂）**：`UpdateNotificationManager$checkNotifyAppUpdate$1`（SuspendLambda）真类在，
   invokeSuspend 返 null 语义未核（勿给非空型 null——53 号注意原文），缓期待 xref 追踪。
4. **MainFrameFragmentTemp 推荐流（P2）**：类名真（classes2/3/4），checkPersonalizedAdsForHomePage/
   loadPersonalizedAdsInfo 串在场；方法签名待核，且勿动 NewMainViewModel 主链（白屏风险）。

## 工具足迹

- dex 类→方法签名提取：`scratch/dexmethods2.py`（dex 原生解析，方法名/参数数/返回类型可靠；
  **参数类型名不可靠**——MUTF8 索引有暗坑，以 jadx 输出为准）
- jadx 单类：`tools/jadx/bin/jadx.bat --single-class <fqcn> -d <out> scratch/am/classesN.dex`
  （JAVA_HOME 须反斜杠格式；9-10MB 单 dex 约 1-3 分钟）
- MT MCP：appmarket 无保存工作区（研究期 amwork/{classes,methods}.json 工件未入库，旧机遗留）；
  MT 文件策略拒绝 /data/app 与新建 /sdcard 路径——补点核签建议用 jadx 或重建工作区
  （需在 MT UI 内手工打开一次以生成 workspace）。

## §机型/设备门控审计（2026-11-19，全量 smali 实证）

证据来源：16.1.11.302 base.apk apktool 全量反编译（scratch/am-smali/，root 拷贝自
/data/app/~~ncdstmfRVjSZ7eyf8D-_jw==/…/base.apk），grep 全 smali 命名空间复核。

### ① 机型/设备判定中枢

- **`ev9` / `fv9`（SystemPropertyUtil.kt，双 dex 重复副本，classes4）**：唯一系统 prop 读取工具。
  `a()`=ro.build.version.release、`b()`=ro.product.model、`c()`=ro.build.version.magic、
  `d(prop,def)`=反射 `com.hihonor.android.os.SystemPropertiesEx`（fallback `android.os.SystemProperties`），
  失败回落默认值并打 `RemoteConfigManager` 日志。
- **调用点全部是遥测上报**（smaliq xref，全库仅两处调用方）：
  `com/hihonor/hm/remoteconfig/track/RemoteConfigTrackEvent` 与
  `com/hihonor/hm/msgcenter/tracking/MsgCenterTrackEvent` —— model/magic 版本只用于打点，不用于本地分支。
- **每次 API 请求的机型画像在 header 层**：`com/hihonor/appmarket/network/intercept/HeaderIntercept`
  逐请求带 `model`(直接 sget Build.MODEL)、`sysVersion`、`magicSysVersion`、`androidVersion`、
  `udid`、`apkVer`、`flavor` 等；`network/SenderDataProvider.createTerminalInfo/getTerminalInfo`
  组装 `baselib.TerminalInfo`（deviceType 经 `l35.c()`）+ `getTargetedParamMap` + `cloudAbResultMap`。
- **OS 版本判定**：`cc3.a()` 解析 ro.build.version.magic 为 int（串 "MAGICUI"/"MAGICOS"/"MagicOS"），
  调用方仅 `com/hihonor/android/support/ui/BaseActivity` 与 `module/onlineservice/OnlineServiceActivity`；
  `qgb` 反射 `com.hihonor.android.os.Build$VERSION.MAGIC_SDK_INT`（cloudservice SDK 兼容层）。
  属 OS 版本兼容，不是按机型白名单。
- **唯一的"设备"本地分支是形态因子**：`l35.c()I` = `FoldScreenManagerCompat.isFoldable()`→2 /
  折叠态→1 / 其它 0，全库几十处调用全是 UI 布局适配（详情页/更新列表/半屏弹层），非机型门控。
- **反证**：全 6 dex 字符串/常量搜 `AAK`、`HNAAK`、`marketname` 零命中；appmarket 命名空间内
  `const-string "HN…"` 机型码零命中。无本地机型白/黑名单。

### ② 受控功能清单

- **云端下发（客户端不可控）**：机型定向推荐/运营弹窗/开屏素材/AB 实验 —— 判定输入 =
  每请求 header（model/sysVersion/magicSysVersion/udid）+ TerminalInfo 上报 +
  `com.hihonor.hm.remoteconfig` 云配置。服务端按 header 画像返回，客户端改不了已下发内容。
- **本地 prop 判断**：仅 ① 所列遥测打点 + OS 版本兼容（cc3/qgb）+ 形态因子布局（l35.c）。
  **未发现任何"本地 prop 判定 → 功能开关/弹窗/推荐差异"的路径**。

### ③ hook 候选（均未验证，按证据强度排序）

1. **改机型画像 header（中）**：进程内改写 Build.MODEL 值（静态 final，需反射写）+ hook `ev9.b`/`fv9.b`
   同步返回值 → HeaderIntercept/TerminalInfo 全链画像变化，服务端可能按新机型下发定向内容。
   前提未证实：服务端是否信任 header（大概率同时校验 udid/签名，纯改 model 可能只影响推荐不影响资格类功能）。
2. **magic 版本虚高（中低）**：hook `ev9.c`/`fv9.c` 与 `cc3.a()` 返回更高 MagicOS 版本 →
   BaseActivity/OnlineService 的 OS 版本兼容分支可能放开新特性；收益与具体版本阈值相关，需抓目标功能再核。
3. **TerminalInfo/getTargetedParamMap（推测）**：改 `SenderDataProvider.getTargetedParamMap` /
   `cloudAbResultMap` 注入可诱导云配置实验分组；结构跨版本易变，R8 名不稳定，兜底锚点弱，不建议首选。
- **边界结论**：纯服务端机型定向（商店首页推荐、按机型适配的版本分发）客户端 hook 无法解锁——
  内容在响应里，不经过本地判定。本地无机型门控 ⇒「强改 prop 解锁机型专属功能」这条路在本 app 不成立。

## tamer 1.4.11 移植（2026-11-19）

「荣耀市场净化」（com.tamer.honormarket 1.4.11，scratch/tamer/tamer.apk，jadx 产物
scratch/tamer/out/sources/com/tamer/honormarket/）的全部 hook 与开关已并入本模块：

- 开关：FeatureCatalog `market.purify.*` 37 个细分 key（默认值随 tamer：splash/op_dialog/
  op_float/widget_tip/pers_ad/toolpage_entry/push/wake/mine_feed/search_feed/update_feed/
  detail_rec/search_ad_booth/search_must 默认开，其余默认关）+ systemui 的
  `systemui.fp_vibrate`（指纹解锁震动，原 fingerprint_vibrate）。
- 实现：`xposed/target/appmarket/` 12 个文件（TamerActivityBlocker/WakeHooks/PushHooks/
  DialogHooks/SplashHooks/SearchAdHooks/UiMiscHooks/TabStripper/MineSectionBlocker/
  FeedHooks/HealthGateHook/TamerUtil），TargetRegistry 逐一注册；健康传感器锁屏门控
  （TamerHealthGateHook）注册到 com.hihonor.health，指纹震动挂 SystemUiHook。
- 与既有 7 点的避让：OperationDialog 3 参族（主开关已挂 n0/j0，16.1.11 名）与
  AlarmCheckReceiver#onReceive（主开关已占）不重复挂，其余 tamer 点全量移植。
- 新版本证据：设备市场 16.1.11.302（scratch/tamer/market-16.1.11.302.apk，apktool 全量
  baksmali 在 system-files/market-1611-apktool-apktool/）。tamer 面向更旧版本，其混淆点
  （fg7/kg7/tp5/rk4/v56、splash 族、BootController#J 等）在 16.1.11.302 的存活情况由
  adapt-report（scratch/tamer/adapt-report.md）核对，DEAD 点已在实现中按 NOT_RESOLVED
  兜底（缺失类/方法随类加载重试、打日志跳过，不硬挂）。

## tamer 移植补遗：页签隐藏链路（2026-11-19 深夜收官）

16.1.11.302 底部页签有**两条独立装配链**，tamer 原逻辑（g/BO getter）只能拦到埋点路，UI 不受影响：

1. **T0 LiveData 路**：`LiveData.considerNotify → $c.onChanged → fq3 → MainFrameFragmentTemp.T0(static, List<BottomNavItemVO>) → yu1.l/n → AgHwBottomNavigationView.K(String,Drawable,IZ)` 逐项添加。
2. **$a.d 装配路**：`vv6.d → vv6.e → MainFrameFragmentTemp$a.d(ui4,List,ArrayList,ArrayList,int[],ArrayList) → E1 → HwBottomNavigationView.A(Drawable,String)` 逐项添加（四平行列表同下标）。

**MountEngine 系统性坑**：对 `$a` 类方法（tab.d、tab.t0）挂载显示成功但 intercept 从不执行（23:25 会话实证：addviewprobe 15 条命中而分支日志零输出）——运行期直挂（`module.hook(m).intercept{}`）则正常。凡挂 ANCHOR 内部方法的任务都要走直挂兜底。

**BottomNavItemVO 反射坑**：R8 后 `javaClass.methods` 查不到 getText（mFound=false），必须 `getDeclaredField("text")` 直读；字段名稳（SerializedName 语义未变）。

最终七层拦截（TamerTabStripper）：g/vmB/vmF/j/BO getter（数据源）+ t0direct + ddirect（两链直挂）+ sinkA/sinkK（A/K 出口按标题拦，兜一切漏网）。真机实证页签 5→3。

**市场进程冷启动会把 Settings.System accelerometer_rotation 写成 1**（实测值 0 → 冷启动 → 1）——rotblock 直挂 Settings$System.putInt 族拦 ACCELEROMETER_ROTATION 非 0 写入。
