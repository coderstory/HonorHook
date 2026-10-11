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
| 关闭实际发生在何时 | **点开应用的瞬间**（launcher 因启动应用而 `onStop` 退后台），故「返回时」看到的是已关状态 |
| 启动应用的瞬间是否会关 | **会关** —— 但不在 `Folder.startActivityByClickIcon`，而在随后的 `Launcher.onStop()` |
| 总门控旗 | `Launcher.mNeedCloseFolderOnSaveState`（public 字段，getter 名拼错为 `Floder`） |
| 主关闭路径 | `Launcher.onStop()`：`if (mNeedCloseFolderOnSaveState && mNeedCloseFolderWhenGoingLazyMode) closeFolder(false)` —— **直接读字段，不经 getter** |
| 次关闭路径 | 5 处返回动画消费点读 `getNeedCloseFloderOnSaveState()` 后 `closeFolder()`（swipe-home / icon-back / exit-app） |
| 修复（两点·缺一不可） | ① getter → `false`（覆盖 5 消费点）；② `onStop` 命中时把字段置 `false` 再放行（覆盖直读字段的主路径） |

**关键教训**：同一旗有「直接读字段」与「经 getter」两条读取路径。**只钩 getter 无效**（实测：`installed 2/2` 且判据日志打出，文件夹仍关）——因主路径 `onStop` 绕过了 getter。

## 三、证据链（base `HnLauncher6.apk`，jadx Java + apktool smali 双证）

### 3.1 启动应用不在此处关闭

- `Folder.startActivityByClickIcon`（smali ~8120）→ `startActivitySafely`（~8278），**不含** `closeFolder`。
  ⇒ 点开图标本身不收起；收起由「launcher 退后台」的事件触发。

### 3.2 门控字段与 getter

`com/hihonor/android/launcher/Launcher.java`：

```java
972:  public boolean mNeedCloseFolderOnSaveState = true;        // 默认 true
973:  public boolean mNeedCloseFolderWhenGoingLazyMode = true;  // 默认 true（注意：不是 false）
...
3338: this.mNeedCloseFolderOnSaveState = true;                 // doResumeWork（每次回前台武装）
10564: this.mNeedCloseFolderOnSaveState = z;                  // setNeedCloseFloderOnSaveState
10567: public boolean getNeedCloseFloderOnSaveState() { return this.mNeedCloseFolderOnSaveState; }
```

### 3.3 主关闭路径：`Launcher.onStop()` 直读字段（现症根因）

```java
public void onStop() {
    ...
    this.mStoped = true;
    if (this.mNeedCloseFolderOnSaveState && this.mNeedCloseFolderWhenGoingLazyMode) {  // 3451：iget 字段
        closeFolder(false);                                                            // 3452
    } else { Logger.info("Launcher ", "onStop, mNeedCloseFolderOnSaveState=..."); }
    ...
    this.mNeedCloseFolderWhenGoingLazyMode = true;                                     // 3457：复位为 true
}
```

- 两字段**默认均 true**；`mNeedCloseFolderWhenGoingLazyMode` 全工程**无 false 写入**（仅默认 true 与 onStop 复位 true）。
- `mNeedCloseFolderOnSaveState` 每次 `doResumeWork` 被重新武装 true。
- ⇒ 条件**恒为真**，launcher 每次退后台都 `closeFolder(false)`。
- 用户点开文件夹内应用 → 该应用转前台 → launcher `onStop` → 文件夹被静默收起。**这就是「返回时文件夹已关」的直接成因。**
- 该处 `iget` **直接读字段**，与 getter 无关 ⇒ 钩 getter 拦不住。

### 3.4 次关闭路径：5 处返回动画消费点（读 getter）

```
extension/hndesktop/HnDesktopVisibilityController            （桌面重见 + freeform 任务）
exitapp/ExitAppAnimationMananger                             （closeFloatViewIfNeed / 退应用）
quickstep/iconback/IconBackAnimation$11                       （图标回位动画）
extension/quickstep/OtherActivityInputConsumerEx             （上滑回桌面手势）
utils/ProviderCallUtils                                       （prepareAppSwitch）
```

均为 `if (launcher.getNeedCloseFloderOnSaveState()) { launcher.closeFolder(...); }`（个别用 `!getter` 做提前返回守卫）。
它们是「返回桌面时」的二次关闭源；主路径若已关则空转，主路径修好后即成为需要处理的下一道。

### 3.5 标志的写入点

- 默认 **true**（`Launcher.java:972`）。
- `doResumeWork()`（`3338`）**直接赋值 true**，绕过 setter。
- setter `setNeedCloseFloderOnSaveState`（`10564`）——钩它无用，因 `doResumeWork` 不走 setter。

## 四、为什么必须「两点齐上」

- **L2b（onStop 清字段）**：主路径 `onStop` 直读字段，只有让字段在 `onStop` 读到 false 才能短路
  `if (mNeedCloseFolderOnSaveState && ...) closeFolder(false)`。getter 钩子对它无效。
- **L2a（getter→false）**：`onStop` 不再关后，文件夹会保持展开；但用户按 Home / 上滑返回时，
  §3.4 的动画消费点会读到字段（被 `doResumeWork` 重新武装为 true）而再次 `closeFolder`。
  故需 getter 恒 false 覆盖。
- 二者覆盖**互不重叠**的两条读取路径，任一单独都不充分。

## 五、实现（已落地，2026-10-11）

并入桌面目标 `LauncherHook`（数据表模式，与主题商店/包管理器同构）：

1. `FeatureCatalog` 新增 `KEY_LAUNCHER_KEEP_FOLDER_OPEN`（默认开启），登记于 `launcher` Target。
2. 挂载点写入 `LauncherMap_18_0_0_234.kt`（**3 点**，同属该 feature）：
   - **L1** `CONST`：`ThemeUtilImpl.isHiddenTitle → true`（卡片标题隐藏）
   - **L2a** `CONST`：`Launcher.getNeedCloseFloderOnSaveState → false`
   - **L2b** `KEEP_FOLDER_ON_STOP`：`Launcher.onStop`，命中时反射置 `mNeedCloseFolderOnSaveState=false` 后 `proceed()`
3. `LauncherHook.mountOne` 按 `kind` 分派：`CONST` 返回 `task.value`；`KEEP_FOLDER_ON_STOP` 清字段再放行。
4. 真机判据 `LauncherHook: installed 3/3`。
5. `TargetRegistry` 已注册；包 `com.hihonor.android.launcher` 已在 `scope.list`。

## 六、副作用与验证

- **不受影响**：点击文件夹外部空白、长按空白、拖拽图标、上滑进多任务（recents/overview 走 `closeFolder(false)`
  但**不经**本旗，仍正常收起）、熄屏时云文件夹展开视图关闭（`closeFolderOnSaveState()→closeFolder()` 直接调用）。
- **同样变化的**：推荐应用 / 云文件夹（CloudFolder）在退后台/返回时也不再自动收起 —— 更合意，可接受。
- **`closeFloatViewIfNeed` 的 `!getter` 提前返回**会跳过 `closeLauncherView()`，但其实现仅关两个对话框
  （`CotaForceLoadDialog` / `MatchCardLayout`），且该方法受 `getOpenFolder()!=null` 前置门控，副作用可忽略。
- **验证**：`dev.sh deploy-release`（本机需 `adb install -g -r` 绕设备授权拦截）→ 强停桌面 → 打开文件夹 →
  点应用 → 返回 → 文件夹应仍开；点外部空白仍需正常关。判据日志：`LauncherHook: installed 3/3`，
  命中行 `launcher-hook 生效: launcher:Launcher.onStop`。

---

*状态：已实现（`LauncherHook` + `LauncherMap_18_0_0_234.kt`，2026-10-11；含对 2026-10-11 首版「onStop 死分支」误判的修正）。*
