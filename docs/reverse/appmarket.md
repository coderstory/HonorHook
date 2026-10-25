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
