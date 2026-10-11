# 系统桌面「文件夹保持打开」— 逆向分析与钩子方案

> 本文件 = 桌面标准文件夹「返回桌面后自动关闭」机制的逆向记录 + 钩子落点提案。
> 索引见 [`README.md`](README.md)。相关：卡片标题 [`launcher.md`](launcher.md)。

> 真机实证：AAK-AN00 / MagicOS 11.0.0 / launcher `18.0.0.234`（2026-10-11 从设备拉取分体 APK 反编译）。
> 反编译产物：`system-files/HnLauncher6-apktool/`（gitignored，按需从设备重拉 `HnLauncher6.apk` + `AppMarketFolderPlugin.apk`）。

---

## 一、需求复述

打开桌面文件夹 → 点击其中某个应用 → 该应用启动、用户回到桌面时，**之前打开的文件夹应仍处于展开状态**。
现状：返回桌面时文件夹被自动收起。

## 二、结论速览

| 项 | 结论 |
|----|------|
| 关闭发生在何时 | **返回桌面**（`onStop` / 可见性变化），不是「点开应用」那一刻 |
| 启动应用的瞬间是否会关 | **不会** —— `Folder.startActivityByClickIcon` 只 `startActivitySafely`，无 close |
| 门控标志 | `Launcher.mNeedCloseFolderOnSaveState`（public 字段，名拼错为 `Floder`） |
| 真正的消费点 | 5 处读 `Launcher.getNeedCloseFloderOnSaveState()` 后 `closeFolder()` |
| 推荐钩子（单点·充分） | `Launcher#getNeedCloseFloderOnSaveState()Z` → 特征开启时恒返回 `false` |
| `onStop` 里的关闭分支 | **死分支**（额外要求 `mNeedCloseFolderWhenGoingLazyMode==true`，该标志全工程无 true 写入） |

## 三、证据链（base `HnLauncher6.apk`）

### 3.1 启动不关，返回才关

- `Folder.smali` `startActivityByClickIcon`（~8120）→ 调用 `startActivitySafely`（~8278），**不含** `closeFolder`。
  ⇒ 点开应用不收起，关闭由「返回」侧触发，与用户体感一致。

### 3.2 门控字段与 getter

- `Launcher.smali:475` —— `.field public mNeedCloseFolderOnSaveState:Z`
- `Launcher.smali:17682` —— `getNeedCloseFloderOnSaveState()Z`，直接 `iget` 返回该字段：

```smali
.method public getNeedCloseFloderOnSaveState()Z
    iget-boolean p0, p0, Lcom/hihonor/android/launcher/Launcher;->mNeedCloseFolderOnSaveState:Z
    return p0
.end method
```

### 3.3 返回时的关闭消费点（5 处读 getter）

```
smali_classes2/.../extension/hndesktop/HnDesktopVisibilityController.smali
smali_classes2/.../exitapp/ExitAppAnimationMananger.smali      (closeFloatViewIfNeed)
smali_classes2/.../quickstep/iconback/IconBackAnimation$11.smali
smali_classes2/.../extension/quickstep/OtherActivityInputConsumerEx.smali
smali_classes3/.../utils/ProviderCallUtils.smali
```

每处均在「桌面重新可见 / 退出应用动画」路径上读取该 getter，为 true 时 `closeFolder()`。
这 5 处即「返回桌面时文件夹收起」的全部触发源。

### 3.4 `onStop` 关闭分支是死分支

`Launcher.onStop()`（smali:35830-35838）条件：

```smali
iget mNeedCloseFolderOnSaveState:Z        # line 4731
if-eqz v1, :cond_4
iget mNeedCloseFolderWhenGoingLazyMode:Z  # line 4731（第二个条件）
if-eqz v1, :cond_4
invoke closeFolder(Z)V                    # line 4732
```

`mNeedCloseFolderWhenGoingLazyMode`（字段 `:477`）全工程**只有构造器置 false 与 onStop 自复位写入（`:35880`），无任何 true 写入** ⇒ 该分支恒不触发。
⇒ 真正生效的是 §3.3 的 5 个 getter 消费点，而非 onStop。

### 3.5 标志如何被「武装」

- 构造器默认 `false`（`:2215`）。
- onResume 路径：`doResumeWork()` 在 `:14547` **直接 `iput` 置 true**（`v3 = const/4 v3, 0x1`），绕过 setter `setNeedCloseFloderOnSaveState`（`:44771`，本就在 `:35038` 的 onResume 链中被调）。
  ⇒ 每次回前台都重新武装该标志，所以「返回时必关」是默认行为。

## 四、为什么钩 getter 而非 setter / 字段

`doResumeWork()` 用**直接字段写入**（`iput`，`:14547`）武装标志，**绕过 setter**。
若只 hook `setNeedCloseFloderOnSaveState`，这处 `iput` 会绕过它，标志仍被置 true。
钩 `getNeedCloseFloderOnSaveState()` 返回 `false`：

- 对全部 5 个消费点（§3.3）生效；
- onStop 死分支原本就不关，无影响；
- 字段真实值仍可保持 true（不破坏桌面其它读字段的逻辑，若有）。

⇒ **单点、必要且充分**。

## 五、实现草图（待确认后落地）

1. `FeatureCatalog` 新增 `KEY_LAUNCHER_KEEP_FOLDER_OPEN`（默认开启，遵循项目「默认全开」）。
2. 新增 `LauncherKeepFolderOpenHook`（或并入现有桌面 hook），挂载
   `com.hihonor.android.launcher.Launcher#getNeedCloseFloderOnSaveState()Z`
   → 特征开启时覆写返回 `false`（复用现有返回值覆盖型 MountEngine）。
3. `TargetRegistry` 注册；包 `com.hihonor.android.launcher` 已在 `scope.list`，无需改作用域。
4. base ClassLoader、`classes2.dex`、方法名未混淆 —— 与 `LauncherCardTitleHook`（挂 base `ThemeUtilImpl`）同路，易挂。

## 六、副作用与验证

- **不受影响**的路径：点击文件夹外部空白、按 Home、拖拽图标、熄屏时云文件夹展开视图关闭（`closeFolderOnSaveState()→closeFolder()` 直接调用，不经此 flag）。
- **同样变化的**：推荐应用 / 云文件夹（CloudFolder）返回时也不再自动收起 —— 更合意，可接受。
- 验证：`dev.sh deploy-release` → 强停桌面 → 打开文件夹→点应用→返回→文件夹应仍开；点外部空白应正常关闭。可观察 `onStop, mNeedCloseFolderOnSaveState=` 日志佐证路径（该日志在死分支 `cond_4` 打印，与关闭与否无关）。

---

*状态：仅完成逆向分析，代码实现未落地（待用户确认 §五 方案）。*
