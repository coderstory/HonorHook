# 系统桌面「文件夹保持打开」— 逆向分析与钩子方案

> 本文件 = 桌面标准文件夹「返回桌面后自动关闭」机制的逆向记录 + 钩子落点。
> 索引见 [`README.md`](README.md)。相关：卡片标题 [`launcher.md`](launcher.md)。

> 真机实证：AAK-AN00 / MagicOS 11.0.0 / launcher `18.0.0.234`（2026-10-11 从设备拉取分体 APK 反编译）。
> 反编译产物：`system-files/HnLauncher6-apktool/`（apktool smali）+ `.buildtmp/launcher-jadx/`（jadx Java，均 gitignored，按需重拉）。

---

## 一、需求复述

打开桌面文件夹 → 点击其中某个应用 → 该应用启动、用户回到桌面时，**之前打开的文件夹应仍处于展开状态**。
现状：返回桌面时文件夹已被收起。

## 二、结论速览

| 项 | 结论 |
|----|------|
| 关闭实际发生在何时 | **返回桌面的那一刻**（不是点开应用的瞬间） |
| 回桌面的两条路各有独立关闭链 | ① **三键 Home**：`onNewIntent(ACTION_MAIN)` → `doMainActionIntent` → `doShowWorkspaceAnim` → `closeFolder`；② **手势上滑**：`onGestureStarted` 现场的 quickstep 例行重置（两条，见 §3.4） |
| 这些链是否受旗控制 | **都不受**——既不读 `mNeedCloseFolderOnSaveState`，也不经 `getNeedCloseFloderOnSaveState()` |
| 确实受旗控制（但本场景早已不是真凶） | 5 处返回动画消费点读 `getNeedCloseFloderOnSaveState()` 后 `closeFolder()`；`Launcher.onStop()` 直读字段关闭 |
| 修复 | **L4（手势）**：`Launcher.closeFolder(boolean)` 命中「从应用返回手势」时短路；**L3（三键 Home）**：置空 `doMainActionIntent` 的 folder 实参；L2a/L2b 保留作旗路径防御 |

**关键教训（三次误判）**：
1. 只钩 getter **无效** —— 同一旗存在「直接读字段」与「经 getter」两条读取路径，但都不是真凶。
2. 钩 getter + onStop **仍无效** —— `onNewIntent` 链**完全不看这两个东西**。首版凭 smali 静态推断把 `onStop` 当成主路径，是错的（该场景下 `Launcher.onStop` 甚至没被执行到）。
3. 补 L3（`doMainActionIntent`）后**仍无效** —— 该链只管**三键 Home**。用户实际用**手势上滑**回桌面，走的完全是另一套（§3.4），`onNewIntent` 根本没发生。**只有调用栈能定性**：静态读代码只能看见"存在这条链"，看不见"这次是哪条"。故 DX 诊断点保留为常备取证。

## 三、证据链（base `HnLauncher6.apk`，jadx Java + apktool smali + 真机日志三证）

### 3.1 真机日志（决定性，2026-10-11 23:40 复现）

按 Home 返回桌面时：

```
23:40:41.717 I/Launcher (26175): onNewIntent() DragLayer visibility = 0, alpha = 1.0
23:40:41.720 D/HnAppRec_15.5.0.515 IActionImpl(26175): onCloseFolder method
23:40:41.725 D/HnAppRec_15.5.0.515 IActionImpl(26175): onCloseFolder method
```

`onNewIntent` 后 **3 ms** 即 `onCloseFolder`（= `FolderManager.closeFolder` 末尾的 `PluginManager.getAction().onCloseFolder(...)`）。
同一时段**没有** `Launcher.onStop` 的 `onStop` 日志行 ⇒ 该场景下 `Launcher.onStop()` 未被执行。

> 取证方法：桌面 `Logger` 受 `sHwInfo`（类初始化时读 `Log.isLoggable("HnLauncher", 3/4)`）门控，默认关。
> `setprop log.tag.HnLauncher VERBOSE` → 强停桌面 → 回桌面（重启进程）后其日志即以 tag `Launcher ` / `FolderManager ` 进 logcat。

### 3.2 `onNewIntent` 关闭链（smali + jadx 双证）

`Launcher.onNewIntent`（smali 33585）：`action == "android.intent.action.MAIN"` 时

```smali
iget-object v0, p0, Launcher;->mWorkspace:...
invoke-virtual {v0}, Workspace;->getOpenFolder()Folder
move-result-object v0                      # v0 = 当前打开的文件夹
...
invoke-virtual {p0, p1, v0, v2}, Launcher;->doMainActionIntent(Intent, Folder, ManualStackExpandGroup)V
```

`Launcher.doMainActionIntent`（jadx 5021，唯一调用点 5041）：

```java
closeShortcutsContainer(z3);
doShowWorkspaceAnim(z3, folder, manualStackExpandGroup, intent);   // folder 原样传入
```

`Launcher.doShowWorkspaceAnim`（jadx 5091，唯一调用点 = doMainActionIntent）：

```java
if (folder == null) { ... runnable.run(); return; }        // ← 传 null 就只做工作区动画
if (folder.isAllAppsFolder()) { closeFolder(folder, false); runnable.run(); }
else { closeFolder(folder, z); ... }                       // ← 普通文件夹在此被关
```

⇒ **只钩住这个方法，就能在不影响其余逻辑的前提下跳过关闭**。

`FolderManager.closeFolder`（jadx 548）末尾：

```java
PluginManager.getAction().ifPresent(iAction -> iAction.onCloseFolder(folder.getInfo()));
```

⇒ 日志里的 `onCloseFolder` 即「文件夹确实被关了」的旁证。

### 3.3 为什么前两版钩子无效

`com/hihonor/android/launcher/Launcher.java`：

```java
972:  public boolean mNeedCloseFolderOnSaveState = true;        // 默认 true
973:  public boolean mNeedCloseFolderWhenGoingLazyMode = true;  // 默认 true
3338: this.mNeedCloseFolderOnSaveState = true;                 // doResumeWork（每次回前台武装）
10567: public boolean getNeedCloseFloderOnSaveState() { return this.mNeedCloseFolderOnSaveState; }
```

- `Launcher.onStop()`（jadx 3426）：`if (mNeedCloseFolderOnSaveState && mNeedCloseFolderWhenGoingLazyMode) closeFolder(false);`
  —— 直读字段，是**存在但本场景未触发**的关闭点（实测该场景无 `onStop` 日志）。
- 5 处 `getNeedCloseFloderOnSaveState()` 消费点：`HnDesktopVisibilityController` / `ExitAppAnimationMananger`
  / `IconBackAnimation` / `OtherActivityInputConsumerEx` / `ProviderCallUtils` —— 均为 `if (getter) closeFolder(...)`。
- 以上两条与 §3.2 的 `onNewIntent` 链**互不相干**。仅修前两条 ⇒ 真凶仍照关（实测 `installed 3/3`、判据全绿，文件夹照样收起）。

### 3.4 真机调用栈（决定性，2026-10-11 23:58 复现，**手势上滑回桌面**）

补 L3 后仍"没用"，遂改**埋诊断点**：钩 `FolderManager.closeFolder(Folder, Z)`（`IAction.onCloseFolder` 的
唯一汇聚点）打 `Throwable` 调用栈。四次复现得**两条**互不相同的链，且都在 `onGestureStarted` 现场发生：

```
① at Launcher.closeFolder(Launcher.java:9113)          # closeFolder(boolean)
   at LauncherActivityInterface.closeOverlay(...:396)   # = createdActivity.closeFolder(false)
   at AbsSwipeUpHandler.onGestureStarted(...:1559)

② at Launcher.closeFolder(Launcher.java:9113)
   at HnRecentsViewStateController.processStateForOverView(...:512)   # mLauncher.closeFolder(false)
   at HnRecentsViewStateController.setState → StateManager.goToState
   at BaseActivityInterface$DefaultAnimationFactory.initBackgroundStateUI
   at HnLauncherActivityInterface.prepareRecentsUI            # initBackgroundStateUI()
   at AbsSwipeUpHandler.lambda$onLauncherStart$8 → MultiStateCallback.setStateOnUiThread
   at AbsSwipeUpHandler.onGestureStarted(...:1594)

两条栈底部同为：
   at OtherActivityInputConsumer.onMotionEvent(...:680)   # ← 有**别的应用在前台**才会出现的消费者
   at TouchInterationManager.goToGestureNavigation(...:2317)
```

smali 双证（`HnLauncher6.apktool`, 行号与运行时栈一致）：

- `LauncherActivityInterface.closeOverlay()V`：`const/4 v0, 0x0` → `invoke-virtual {p0, v0}, Launcher;->closeFolder(Z)V`。
- `HnRecentsViewStateController.processStateForOverView(LauncherState)V`：
  `if (state == BACKGROUND_APP || state == QUICK_SWITCH_FROM_HOME) mLauncher.closeFolder(false);`

⇒ **定性**：手势导航下从应用上滑回桌面，launcher 在**手势开始的同一帧**先被 quickstep 铺成 Recents 背景
（`BACKGROUND_APP`），其间两处「例行重置」无条件关掉文件夹。二者都不看 §3.3 的任何旗 ⇒ L2a/L2b 拦不住；
又不经 `onNewIntent` ⇒ L3 也拦不住。**这也是前三次静态推断反复落空的根因：能读到"存在哪些链"，读不出"这次是哪条"。**

## 四、实现（已落地，2026-10-11）

并入桌面目标 `LauncherHook`（数据表模式，与主题商店/包管理器同构）：

1. `FeatureCatalog.KEY_LAUNCHER_KEEP_FOLDER_OPEN`（默认开启），登记于 `launcher` Target。
2. 挂载点写入 `LauncherMap_18_0_0_234.kt`（**6 点**，L2a/L2b/L3/L4/DX 同属该 feature）：

| 点 | kind | 目标 | 作用 |
|----|------|------|------|
| L1 | `CONST` | `ThemeUtilImpl.isHiddenTitle → true` | 卡片标题隐藏（无关文件夹，同目标） |
| L2a | `CONST` | `Launcher.getNeedCloseFloderOnSaveState → false` | 5 处返回动画消费点不再关（旗路径） |
| L2b | `KEEP_FOLDER_ON_STOP` | `Launcher.onStop`，命中反射置 `mNeedCloseFolderOnSaveState=false` 后放行 | 拦 `onStop` 直读字段（旗路径） |
| L3 | `DROP_FOLDER_ON_HOME` | `Launcher.doMainActionIntent`，命中把第 2 个实参（folder）置 `null` 后 `proceed(argv)` | 三键 Home 回桌面不再收起 |
| **L4** | `KEEP_FOLDER_ON_RETURN_GESTURE` | **`Launcher.closeFolder(boolean)`，命中「从应用返回手势」时直接返回不执行** | **手势上滑回桌面不再收起（现症主修复）** |
| DX | `TRACE_FOLDER_CLOSE` | `FolderManager.closeFolder(Folder, Z)` | 只打「谁调的」一行，常备取证 |

3. `LauncherHook.mountOne` 按 `kind` 分派；`DROP_FOLDER_ON_HOME` 用 `Chain.proceed(Object[])` 改写实参
   （libxposed API 102 的 `XposedInterface$Chain#proceed(Object[])` 支持换参调用）。
4. **L4 判定（`fromAppReturnGesture`）**：`Launcher` 有 4 个 `closeFolder` 重载，`(Folder)V` 与 `(Z)V` 同为
   「1 参 + void」⇒ 必须用 `dexParamTypes = listOf("boolean")` 锚定（`MethodResolver` 直连轨按参数类型精确匹配）。
   命中条件 = 栈上**同时**出现：
   - `OtherActivityInputConsumer`（quickstep 在**别的应用前台**时接管手势的消费者）—— 把场景锁死为"从应用回桌面"，
     桌面内切 Recents（`HomeInputConsumer` 等）不满足，"点外部关闭/桌面内上滑"等原有行为不受影响；
   - §3.4 的两个重置入口之一（`closeOverlay` / `processStateForOverView`）。
   两帧顺序不定（消费者在重置入口**下方**），故整栈扫完再判，不能提前 return。
5. 真机判据 `LauncherHook: installed 6/6`；命中行 `launcher-hook 生效: launcher:Launcher.closeFolder`。
6. `TargetRegistry` 已注册；包 `com.hihonor.android.launcher` 已在 `scope.list`。

## 五、副作用与验证

- **不受影响**：点击文件夹外部空白、长按空白、拖拽图标 —— L4 只在「别的应用在前台的手势返回」链上生效，
  这些桌面内动作栈上既无 `OtherActivityInputConsumer` 也无两个重置入口。
- **同样变化的**：推荐应用 / 云文件夹（CloudFolder）在返回桌面时也不再自动收起 —— 更合意，可接受。
- `ProviderCallUtils.prepareAppSwitch` 中 `!getter` 的提前返回会跳过退出动画数据下发；该分支本就是系统
  为「文件夹不该关」预留的早退（注释 "folder no need to close when share…"），语义一致。
- **验证**：`tools/dev.sh deploy-release`（`install-release` 已改 `adb install -g`；需屏亮且解锁）
  → 强停桌面 → 打开文件夹 → 点应用 → **手势上滑回桌面** → 文件夹仍展开；点外部空白仍需正常关。
  - 判据：`LauncherHook: installed 6/6`；命中行 `launcher-hook 生效: launcher:Launcher.closeFolder（从应用返回手势的例行重置短路…）`。
  - 真机旁证：同场景 `FOLDER-CLOSE 触发方:` 应为 **0 行**（修复前每次返回 1~2 行）。

---

*状态：已实现，待真机验证（`LauncherHook` + `LauncherMap_18_0_0_234.kt`，2026-10-11）。本文含对前三版误判
（首版「onStop 死分支」、二版「onStop 主路径 + getter」、三版「onNewIntent 即真凶」）的修正：真凶是**手势返回**触发的
quickstep 例行重置（`closeOverlay` + `processStateForOverView` 两条链），定性靠调用栈，静态读码做不到。*
