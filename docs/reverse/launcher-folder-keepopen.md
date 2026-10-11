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
| 关闭实际发生在何时 | **按 Home / 返回桌面的那一刻**（不是点开应用的瞬间） |
| 主关闭路径（现症根因） | `Launcher.onNewIntent(ACTION_MAIN)` → `doMainActionIntent(intent, workspace.getOpenFolder(), …)` → `doShowWorkspaceAnim(z, folder, …)` → `closeFolder(folder, z)` |
| 该路径是否受旗控制 | **不受**——不看 `mNeedCloseFolderOnSaveState`，也不经 `getNeedCloseFloderOnSaveState()` |
| 次关闭路径 | 5 处返回动画消费点读 `getNeedCloseFloderOnSaveState()` 后 `closeFolder()`；`Launcher.onStop()` 直读字段关闭 |
| 修复 | **L3（主）**：`doMainActionIntent` 命中时把 `folder` 实参置 `null` 再放行；L2a/L2b 保留作次路径防御 |

**关键教训（两次误判）**：
1. 只钩 getter **无效** —— 同一旗存在「直接读字段」与「经 getter」两条读取路径，但都不是真凶。
2. 钩 getter + onStop **仍无效** —— 真凶 `onNewIntent` 链**完全不看这两个东西**。首版凭 smali 静态推断把 `onStop` 当成主路径，是错的（`Launcher.onStop` 在真机该场景下甚至没打日志，未被执行到）。

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

## 四、实现（已落地，2026-10-11）

并入桌面目标 `LauncherHook`（数据表模式，与主题商店/包管理器同构）：

1. `FeatureCatalog.KEY_LAUNCHER_KEEP_FOLDER_OPEN`（默认开启），登记于 `launcher` Target。
2. 挂载点写入 `LauncherMap_18_0_0_234.kt`（**4 点**，同属该 feature）：

| 点 | kind | 目标 | 作用 |
|----|------|------|------|
| L1 | `CONST` | `ThemeUtilImpl.isHiddenTitle → true` | 卡片标题隐藏（无关文件夹，同目标） |
| L2a | `CONST` | `Launcher.getNeedCloseFloderOnSaveState → false` | 5 处返回动画消费点不再关（次路径） |
| L2b | `KEEP_FOLDER_ON_STOP` | `Launcher.onStop`，命中反射置 `mNeedCloseFolderOnSaveState=false` 后放行 | 拦 `onStop` 直读字段（次路径） |
| **L3** | `DROP_FOLDER_ON_HOME` | **`Launcher.doMainActionIntent`，命中把第 2 个实参（folder）置 `null` 后 `proceed(argv)`** | **主路径：按 Home 返回桌面不再收起** |

3. `LauncherHook.mountOne` 按 `kind` 分派；`DROP_FOLDER_ON_HOME` 用 `Chain.proceed(Object[])` 改写实参
   （libxposed API 102 的 `XposedInterface$Chain#proceed(Object[])` 支持换参调用）。
4. 真机判据 `LauncherHook: installed 4/4`；命中行 `launcher-hook 生效: launcher:Launcher.doMainActionIntent`。
5. `TargetRegistry` 已注册；包 `com.hihonor.android.launcher` 已在 `scope.list`。

## 五、副作用与验证

- **不受影响**：点击文件夹外部空白、长按空白、拖拽图标、进多任务/概览（recents/overview 走 `closeFolder(false)`
  不经 `doMainActionIntent`）、熄屏关闭云文件夹展开视图。
- **同样变化的**：推荐应用 / 云文件夹（CloudFolder）在返回桌面时也不再自动收起 —— 更合意，可接受。
- `ProviderCallUtils.prepareAppSwitch` 中 `!getter` 的提前返回会跳过退出动画数据下发；该分支本就是系统
  为「文件夹不该关」预留的早退（注释 "folder no need to close when share…"），语义一致。
- **验证**：`adb install -g <release.apk>`（本机 `dev.sh install-release` 会被设备弹窗拒
  `INSTALL_FAILED_ABORTED`，需屏亮且解锁）→ 强停桌面 → 打开文件夹 → 点应用 → 按 Home →
  文件夹仍展开；点外部空白仍需正常关。
  - 判据：`LauncherHook: installed 4/4`。
  - 真机旁证：修复后同场景 logcat `grep -c onCloseFolder` = **0**（修复前为 2）。

---

*状态：已实现并真机验证（`LauncherHook` + `LauncherMap_18_0_0_234.kt`，2026-10-11）。本文含对前两版误判
（2026-10-11 首版「onStop 死分支」、二版「onStop 主路径 + getter」）的修正：真凶是 `onNewIntent → doMainActionIntent → doShowWorkspaceAnim → closeFolder`。*
