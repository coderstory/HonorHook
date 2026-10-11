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

| 项                  | 结论                                                                                                                                                                  |
| ------------------ | ------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 关闭实际发生在何时          | **返回桌面的那一刻**（不是点开应用的瞬间）                                                                                                                                             |
| 回桌面的两条路各有独立关闭链     | ① **三键 Home**：`onNewIntent(ACTION_MAIN)` → `doMainActionIntent` → `doShowWorkspaceAnim` → `closeFolder`；② **手势上滑**：`onGestureStarted` 现场的 quickstep 例行重置（两条，见 §3.4） |
| 这些链是否受旗控制          | **都不受**——既不读 `mNeedCloseFolderOnSaveState`，也不经 `getNeedCloseFloderOnSaveState()`                                                                                    |
| 确实受旗控制（但本场景早已不是真凶） | 5 处返回动画消费点读 `getNeedCloseFloderOnSaveState()` 后 `closeFolder()`；`Launcher.onStop()` 直读字段关闭                                                                          |
| **最终修复（2026-10-12，路线已更换）** | **不在关闭链上做任何拦截**：应用切换全程交给系统（图标回收动画/背景模糊原生完成），回到桌面后（`Launcher.onResume`）用系统自身的开文件夹路径 `FolderManager.openFolder(FolderIcon)` 把 R1 记下的文件夹重开。见 §七 |

> ⚠️ §三~§六记载的 L2/L3/L4/L5 各版拦截方案**均已废弃**（代码已移除），保留作取证与教训记录：
> 它们能各自解释一次症状，但合在一起与「非线性应用关闭动画」互相踩，实测「有时不生效 / 有时只剩一层模糊遮罩」。
> **结论：这类"拦住不让关"的思路在此场景结构性不可靠。**

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

## 四、实现（已废弃，2026-10-11 的拦截方案 —— 见 §七 现行方案）

> 本节及 §五/§六描述的是**已移除**的拦截实现（L2a/L2b/L3/L4/L5a/L5b/DX），保留用于复盘取证过程。

并入桌面目标 `LauncherHook`（数据表模式，与主题商店/包管理器同构）：

1. `FeatureCatalog.KEY_LAUNCHER_KEEP_FOLDER_OPEN`（默认开启），登记于 `launcher` Target。
2. 挂载点写入 `LauncherMap_18_0_0_234.kt`（**8 点**，L2a/L2b/L3/L4/L5/DX 同属该 feature）：

| 点       | kind                            | 目标                                                                                               | 作用                            |
| ------- | ------------------------------- | ------------------------------------------------------------------------------------------------ | ----------------------------- |
| L1      | `CONST`                         | `ThemeUtilImpl.isHiddenTitle → true`                                                             | 卡片标题隐藏（无关文件夹，同目标）             |
| L2a     | `CONST`                         | `Launcher.getNeedCloseFloderOnSaveState → false`                                                 | 5 处返回动画消费点不再关（旗路径）            |
| L2b     | `KEEP_FOLDER_ON_STOP`           | `Launcher.onStop`，命中反射置 `mNeedCloseFolderOnSaveState=false` 后放行                                  | 拦 `onStop` 直读字段（旗路径）          |
| L3      | `DROP_FOLDER_ON_HOME`           | `Launcher.doMainActionIntent`，命中把第 2 个实参（folder）置 `null` 后 `proceed(argv)`                       | 三键 Home 回桌面不再收起               |
| **L4**  | `KEEP_FOLDER_ON_RETURN_GESTURE` | **`Launcher.closeFolder(boolean)`，命中「从应用返回手势」时直接返回不执行**                                          | **手势上滑回桌面不再收起（文件夹主修复）**       |
| L5a     | `BLOCK_BLUR_CLEAR`              | `DragLayer.setBlur(Drawable, boolean)`，返回转场窗口内 `drawable == null` 时不执行                           | 静态模糊分支的遮罩保持（本机不适用，见 §六）       |
| **L5b** | `RESTORE_FOLDER_BLUR`           | **`LevelAnimationMgr.changeScene(int,int,boolean,long,boolean)`，窗口内把 app 模糊退场改写成文件夹模糊进场 + 尾部补正** | **返回桌面背景维持文件夹模糊（本机主修复，见 §六）** |
| DX      | `TRACE_FOLDER_CLOSE`            | `FolderManager.closeFolder(Folder, Z)`                                                           | 只打「谁调的」一行，常备取证                |

1. `LauncherHook.mountOne` 按 `kind` 分派；`DROP_FOLDER_ON_HOME` 用 `Chain.proceed(Object[])` 改写实参  
   （libxposed API 102 的 `XposedInterface$Chain#proceed(Object[])` 支持换参调用）。
2. **L4 判定（`fromAppReturnGesture`）**：`Launcher` 有 4 个 `closeFolder` 重载，`(Folder)V` 与 `(Z)V` 同为  
   「1 参 + void」⇒ 必须用 `dexParamTypes = listOf("boolean")` 锚定（`MethodResolver` 直连轨按参数类型精确匹配）。  
   命中条件 = 栈上**同时**出现：
   - `OtherActivityInputConsumer`（quickstep 在**别的应用前台**时接管手势的消费者）—— 把场景锁死为"从应用回桌面"，  
     桌面内切 Recents（`HomeInputConsumer` 等）不满足，"点外部关闭/桌面内上滑"等原有行为不受影响；
   - §3.4 的两个重置入口之一（`closeOverlay` / `processStateForOverView`）。  
     两帧顺序不定（消费者在重置入口**下方**），故整栈扫完再判，不能提前 return。
3. 真机判据 `LauncherHook: installed 6/6`；命中行 `launcher-hook 生效: launcher:Launcher.closeFolder`。
4. `TargetRegistry` 已注册；包 `com.hihonor.android.launcher` 已在 `scope.list`。

## 五、副作用与验证

- **不受影响**：点击文件夹外部空白、长按空白、拖拽图标 —— L4 只在「别的应用在前台的手势返回」链上生效，  
  这些桌面内动作栈上既无 `OtherActivityInputConsumer` 也无两个重置入口。
- **同样变化的**：推荐应用 / 云文件夹（CloudFolder）在返回桌面时也不再自动收起 —— 更合意，可接受。
- `ProviderCallUtils.prepareAppSwitch` 中 `!getter` 的提前返回会跳过退出动画数据下发；该分支本就是系统  
  为「文件夹不该关」预留的早退（注释 "folder no need to close when share…"），语义一致。
- **验证**：`tools/dev.sh deploy-release`（`install-release` 已改 `adb install -g`；需屏亮且解锁）  
  → 强停桌面 → 打开文件夹 → 点应用 → **手势上滑回桌面** → 文件夹仍展开；点外部空白仍需正常关。
  - 判据：`LauncherHook: installed 8/8`；命中行 `launcher-hook 生效: launcher:Launcher.closeFolder（从应用返回手势的例行重置短路…）`。
  - 真机旁证：同场景 `FOLDER-CLOSE 触发方:` 应为 **0 行**（修复前每次返回 1~2 行）。

## 六、背景遮罩丢失（L5，L4 的配套修复）

L4 生效后文件夹确实留住了，但**返回桌面的同一时刻背景遮罩（压暗/模糊）没了**。

- 那层遮罩不是 `Folder` 自己画的，是桌面**全局背景模糊**，两条实现分支（`Utilities.isDynamicBlurFolder`）：
  - **静态支**：`Folder.getBackgroundDrawable()` → `FolderAnimationHelper.onAnimationStart` →  
    `DragLayer.setBlurEffect(drawable, …)` → `DragLayer.setBlur`，落在 `DragLayer.mBlurEffect`，  
    由 `DragLayer.drawBlurEffect(canvas)` 绘制。清空 = `setBlurEffect(null, false)`。
  - **动态支**（`getUseSpaceHierarchyAnim() && isEnableWindowBlur() && !isInEditMode`）：  
    改用 `LevelAnimationMgr` 的窗口模糊场景（`0=recents / 1=folder / 2=application`，SF 合成），  
    `onAnimationStart` 会**跳过** `setBlurEffect`，关文件夹时 `changeScene(1, 0, false, …)`。
- 返回桌面的**状态转场**会撤掉遮罩，且这些调用**都不看文件夹开关**（这正是 L4 保住文件夹后遮罩仍丢的原因）：
  - `Workspace.doAnimatorListenerEndWork()` → `getDragLayer().setBlurEffect(null, false)` + `switchBlurBackgroundType(STATIC_BG)`；
  - `LauncherStateTransitionAnimation` 的 `setBlurEffect(null, false)`（3 处，`onAnimationCancel` 等）；
  - `Workspace` 状态动画 `onAnimationCancel` → `setBlurEffect(null, false)`。
- **本机走哪一支（真机实证，2026-10-12）**：
  - `HnLauncherSwipeHandlerV2:1876` 只在 `useSpaceHierarchyAnim` 为真时才发 `changeScene(2, 0, false, …)`  
    —— 该调用在真机被 L5b 轨迹点抓到（`scene=2 enter=false dur=222`）⇒ **空间层级动画为真**；
  - `ro.surface_flinger.supports_background_blur = 1` ⇒ `WINDOW_BLUR_SUPPORTED = true` ⇒ `isEnableWindowBlur()` 真；
  - ⇒ `Utilities.isDynamicBlurFolder()` 成立 ⇒ **遮罩走窗口模糊（动态支），`DragLayer.setBlur` 不参与**。  
    首版 L5a 因此完全无效（实测）。
- 真机场景轨迹（`RESTORE_FOLDER_BLUR` 点，返回转场窗口内）：
  ```
  scene=2 enter=false dur=222 ← HnLauncherSwipeHandlerV2…lambda(HnLauncherSwipeHandlerV2.java:1867)   # app 模糊退场
  ```


scene=0 enter=false dur=0   ← HnLauncherRecentsView.clearWindowBlurAndScale(HnLauncherRecentsView.java:569)  
scene=0 enter=false dur=0   ← 同上（约 +600ms）

```
没有 `scene=1` —— 文件夹模糊在打开应用时被 `scene=2`（app 模糊）顶掉，返回时只退了 `scene=2`、没人再进 `scene=1`。
- **对策**：
- **L5b（主）**：窗口内把 `changeScene(2, 0, false, j)` 改写成 `changeScene(1, 0, true, j)`（`Chain.proceed(Object[])` 换参）
  —— 文件夹还开着，背景本就该停在文件夹模糊上；改写后转场直接落到文件夹模糊。
- **尾部补正**：改写后 `postDelayed(800ms)` 再发一次 `changeScene(1, 0, true, 0, false)`，压住随后
  `clearWindowBlurAndScale` 打回的 `scene=0`。该调用幂等，落在已进场景上无副作用。
- **L5a（次）**：窗口内拦住 `DragLayer.setBlur(null, …)`，留给静态模糊分支的机型（本机不生效，但零成本）。
- 侧滑返回应用（back 手势）时同样是 `changeScene(2, 0, false, 100L)`
（`LauncherBackAnimationControllerEx.createLauncherAnimForCancelled`，其实现在
`createLauncherAnimForStarted` 先 `changeScene(2, 0, true, 100L)`）⇒ L5b 的改写同样覆盖这条路。

---

## 七、路线更换：从「拦住不让关」到「返回后重开」（2026-10-12，现行方案）

### 7.1 为什么放弃拦截

拦截方案（§四）跑起来后真机症状是**不稳定**：上滑返回桌面"有时不生效、有时只剩一层模糊遮罩"。
根因不在某一条链没堵住，而在思路本身：

- **覆盖必然不全**：`Launcher` 的 `closeFolder` 有四个重载，最终都汇入 `FolderManager.closeFolder(Folder,Z)`，
  但调用方各走各的（`HnLauncherSwipeHandlerV2` / `HnRecentsViewStateController.processStateForOverView` /
  `DragLayer.closeFolderIfNeeded` / 返回动画消费点 …）。日志实证：L4 在 `(Z)V` 上命中了短路，
  紧接着仍有一条关闭从 `processStateForOverView` 走到 `FolderManager.closeFolder`。
- **遮罩与文件夹状态会脱钩**：L5 只要"认为文件夹该开着"就补模糊，一旦那次关闭实际成功，就剩下"只有遮罩、没有文件夹"。
- **根本冲突**：**非线性应用关闭动画会把应用图标收回文件夹所在的位置**，此时文件夹同时处于打开态
  是自相矛盾的状态 —— 两边互相踩。这不是补钩子能消除的。

⇒ 教训：**当"不让系统做某件事"需要同时在 N 条链上打补丁时，说明该状态本就不该由模块维持。**

### 7.2 现行方案（R1 + R2）

顺着系统状态机走，应用切换全程零干预：

| 点 | kind | 目标 | 作用 |
|----|------|------|------|
| L1 | `CONST` | `ThemeUtilImpl.isHiddenTitle → true` | 卡片标题隐藏（同目标，无关文件夹） |
| **R1** | `ARM_FOLDER_REOPEN` | **`Folder.startActivityByClickIcon(View, ShortcutInfo)`（private，1091 行）** | **记下"用户从哪个文件夹里点了应用"**（此刻文件夹必然还开着，是唯一无歧义的时刻；`onPause`/`onStop` 时可能已被关掉） |
| **R2** | `REOPEN_FOLDER_ON_RESUME` | **`Launcher.onResume()`** | **回到桌面后，按系统自身路径重开该文件夹** |

- 重开调用：`FolderManager.getInstance().openFolder(FolderIcon)`（与手点文件夹图标同一条路，
  `FolderIcon` 由 `Folder.getFolderIcon()` 取，取不到再退到 `FolderInfo.getFolderIcon()`）。
  ⇒ 展开动画、窗口模糊（`animateIn` → `changeScene(1,0,true,350L)`）、`FolderInfo.isOpened` 全由系统负责，
  模块**不碰任何视觉状态**。
- 时机：`postDelayed(320ms)`（应用关闭动画约 300~500ms，太早会被 `FolderManager.canOpenFolder` 的动画互斥挡掉），
  失败重试一次；动手前先查 `Workspace.getOpenFolder()`，已有文件夹开着就不动（不顶掉用户当下状态）。
- 代价：返回桌面时多一次文件夹展开动画。两端都是原生动画，衔接自然。
- 判据：`LauncherHook: installed 3/3`；日志 `FOLDER-REOPEN 已按系统路径重开文件夹`。
  若失败会打 `FOLDER-REOPEN 重开失败: <原因>`（无 ClassLoader / 拿不到 FolderIcon / 异常）。

### 7.3 已验证的宿主 API（smali 核对）

```
Folder.startActivityByClickIcon(Landroid/view/View;Lcom/hihonor/android/launcher/ShortcutInfo;)V   private
Folder.getFolderIcon()Lcom/hihonor/android/launcher/FolderIcon;                                    public
Folder.getInfo()Lcom/hihonor/android/launcher/FolderInfo;                                          public
FolderManager.getInstance()Lcom/hihonor/android/launcher/folder/FolderManager;                     public static
FolderManager.openFolder(Lcom/hihonor/android/launcher/FolderIcon;)V                               public
Launcher.onResume()V                                                                              public
Launcher.getLauncher()Lcom/hihonor/android/launcher/Launcher;                                      public static
Launcher.getWorkspace()Lcom/hihonor/android/launcher/Workspace;                                    public
Workspace.getOpenFolder()Lcom/hihonor/android/launcher/Folder;                                     public（DragLayer 里 isOpened 的 Folder）
```

---

*状态：§三~§六的拦截方案（L2a/L2b/L3/L4/L5a/L5b）已废弃并从代码移除，保留作取证与教训记录；
现行实现为 §七（R1 记录 + R2 重开），2026-10-12。*

*本文的四次误判——首版「onStop 死分支」、二版「onStop 主路径 + getter」、三版「onNewIntent 即真凶」、
五版「L5 按时间窗补遮罩」——都是同一件事：**用间接信号（关闭旗 / getter / 调用栈 / 时间窗）代替目标状态本身**。
可复用的两条：① 调用栈能定性"这一次是谁"，静态读码不能；② 闸门要读宿主状态，不要读"离上次事件多久"。*

