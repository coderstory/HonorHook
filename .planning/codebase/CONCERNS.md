---
last_mapped_commit: fe2e820eb97798af089b982b570191734c867fcb
last_mapped_at: 2026-10-11
---
# Codebase Concerns

**Analysis Date:** 2026-10-11

> 范围：`app/app/src/main/kotlin/`、`app/app/build.gradle.kts` + `app/app/proguard-rules.pro`、`tools/dev.sh` + `tools/*.py`、`docs/`、`.github/workflows/ci.yml`、根 dotfiles。
> 方法：`git ls-files app tools` 枚举 + 逐文件精读 + 定向实证（提取器实跑、全命名空间 grep、per-file 计数）。
> 不在范围：`reference/`（第三方只读 submodule）、`.claude/skills/apk-reverse/`（vendor 的已废弃上游 skill）、`system-files/`（gitignore 的逆向产物）。

---

## Tech Debt

### 1. 挂载基建仍是 4 份（P3 结构性清理，明确未执行）

`docs/hook-inventory-audit.md` §二记录的「P3 结构性（两套挂载基建重复）」决策是「未执行，含回归风险」，代码现状印证：

- `app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt:145` 提供统一模板 `mount()`（解析 → 挂 PROTECTIVE → 结果分级）。
- 但只有 3 个目标走它：`InstallerHook.kt:325`、`Exam8Hook.kt:195`、`SystemUiHook.kt:87`、`SettingsHook.kt:64`、`LauncherCardTitleHook.kt:76`（5 个）。
- `ThemeStoreHook.kt:55-161` **完全绕开** `engine.mount`，自己 `resolveTask` + 手工 `runCatching{...}.fold{ OK / FAILED }` 重写一遍，并把 `task.resolveValue` 调了两次（`ThemeStoreHook.kt:60` 与 `:63`）。
- 每个目标各自定义一份近乎相同的 `MountTask` 数据类：`SettingsHook.kt:53`、`SystemUiHook.kt:70`、`LauncherCardTitleHook.kt:66`、`InstallerHook.kt:279`、`Exam8Hook.kt:149`、`ThemeStoreParts.kt:80` —— **6 份**。前 3 份连 `dex: DexHint?` 字段都一致。

**修法（分两步，单独立项 + 真机回归）**：
1. 先把 `ThemeStoreHook.mountOne` 收敛到 `MountEngine.mount`——`MountKind` 从枚举分派改成 `intercept` 工厂参数即可，`MountEngine.mount` 已支持任意 ` Hooker`。
2. 再把 6 份 `MountTask` 上提为 `MountEngine.MountTask`（`value`/`dex`/`resolveValue` 已有公共契约，私有字段如 `uriEmptyRoute`/`forceArgIndex` 保留为可选参数）。
   验收判据 = 6 个目标 `installed N/M` 与 README 台账逐字一致（43/11/10/1/1/1）。

### 2. `tools/adapt_new_version.py` 只提取到 9/67 个挂载点，且包名全空 —— 版本漂移守卫实质失效

实跑（只解析、不反编译）：

```
python3 -c "...import adapt_new_version; print(len(adapt_new_version.extract_mounts()))"
→ total extracted: 9   Counter({'': 9})   Counter({'InstallerHook.kt': 9})
```

代码有 67 个挂载点（`docs/hook-inventory-audit.md` §一），脚本只认出 9 个，且 9 个的 `pkg` 全是空串。四处独立成因：

| # | 位置 | 问题 |
|---|---|---|
| a | `tools/adapt_new_version.py:120` | `TARGET_DIR.glob('*Hook.kt')` **非递归** → `target/themestore/ThemeStoreHook.kt`（43 点）与 `target/exam8/Exam8Hook.kt`（10 点）整目录被跳过 |
| b | `:81` `CLS_DEF_RE` | 只认 `private const val CLS_X`，而 `ThemeStoreParts.kt:20-72` 全是 `internal const val` → 43 个主题商店点的类名一个都解析不出 |
| c | `:82` `PKG_DEF_RE` | 只认 `private const val PKG =`，`ThemeStoreParts.kt:17` 是 `internal const val PKG`、`InstallerHook.kt:20` 是 `private const val PKG_HN` → `pkg=''` → `$PKG` 模板不展开、报告里的 `--expect-pkg` 判定失效 |
| d | `:88` `NAMED_MOUNT_RE` | 要求 `MountTask(kind = ...)` 打头，而 `SettingsHook.kt:33`、`SystemUiHook.kt:56`、`LauncherCardTitleHook.kt:54` 是 `MountTask(className = ...)` 无 `kind` → 3 个单点目标全部漏检 |

**危害等级最高**：`:149` 的守卫只有 `if not mounts`（==0）才报错，9/67 会正常生成一份「存活 9/9 · 结论通过」的报告。这是**静默假通过**，比没有工具更危险。

**修法**：`glob('**/*Hook.kt')` 递归；`CLS_DEF_RE`/`PKG_DEF_RE` 放宽到 `(?:private|internal|public)?\s*const\s+val`；新增「同一常量文件的 `override val packageName = "..."`」作为 `pkg` 首选来源，`$PKG` 仅作兜底；把守卫从 `==0` 改成**期望值断言**——`--expect-mounts 67`（默认值按台账），提取数不符直接 `SystemExit`，报告标红。最后加一条自测：提取点数 == 台账点数 == 67，否则失败。

### 3. `tools/adapt_new_version.py` 是 Windows-only，与项目主力开发环境（macOS）不兼容

- `:66` `JADX_BAT = REPO/'tools'/'jadx'/'bin'/'jadx.bat'`、`:344` `cmd = ['cmd','/c',str(JADX_BAT),...]` —— 只认 Windows 入口。
- `:327`/`:330` JDK 候选是 `C:\Program Files\Eclipse Adoptium\jdk-21*` / `C:\Program Files\Java\jdk-21*`，只认 `.exe` 布局。
- `tools/README.md:24` 自己写明 jadx 跨平台入口是 `bin/jadx`（macOS/Linux），`tools/dev.sh:33-37` 也优先解析 `bin/jadx`。

**修法**：让脚本复用 `tools/dev.sh` 已解析的环境——`dev.sh exec python3 tools/adapt_new_version.py …`（`dev.sh:629` 注入 `PATH/JAVA_HOME/ANDROID_HOME`），脚本内改为 `shutil.which('jadx')` + `os.environ['JAVA_HOME']`，删掉 `cmd /c` 与 Windows 路径候选。

### 4. `tools/rain-decrypt.py` 引用已删除的文档，且服务对象是第三方 submodule

`:5`、`:492`、`:513` 三处指向 `docs/rain-hook-architecture.md`——该文件已随「历史过程文档清理」删除（`docs/README.md` §开头），现为断链。脚本本体是 `reference/Rain_1.1.5.apk` 的 short[] XOR 解密器，与本项目 6 个目标无关；`tools/README.md:17` 承认「Rain 参考模块字符串解密（逆向期工具）」。

**修法**：二选一——删掉（连带 `tools/README.md` 表格行），或把算法说明就地写进脚本 docstring 并把三处断链改为自引用。不建议保留现状：断链文档会让后来者去找一份不存在的依据。

### 5. `tools/dev.sh log-file` 指向一个无人写入的落点

`tools/dev.sh:553-561` 从 `/data/user/0/top.honorhook/files/honorhook.log` 拉日志，并提示「走 App 内日志页查看」。全仓 grep `honorhook.log|openRemoteFile|FileOutputStream` 在 `app/app/src` 下**零命中**：hook 侧只写 LSPosed 框架日志（`Module.log`），UI 侧只有首页/功能/设置三个 Tab，**没有日志页**。该子命令恒返回「未取到」。

**修法**：删掉 `log-file` 子命令与 `help` 中对应行（`tools/dev.sh:101`），判据日志的正规出口已在 `modlog` / `log-hook` / `wait-hook`。

### 6. 宿主版本基线字面量在 6 处手工同步，无单一事实源

`ThemeStoreHook.kt:164` `20.1.63.305`、`InstallerHook.kt:69` `14.0.37.301`、`SettingsHook.kt:47` `14.0.0.210`、`LauncherCardTitleHook.kt:42` `18.0.0.234`、`SystemUiHook.kt:39` `17.0.0.1`、`Exam8Hook.kt:69` `5.9.0.0-release`，另加 `.github/workflows/ci.yml:66-67` 的 release body 手写副本与 `README.md:12-17` 表格。改一处基线要同步 3 个文件、其中 1 个在 CI 里。

**修法**：把 `HOST_VERSION_BASELINE` 上提到 `FeatureCatalog.Target`（加 `baselineVersion: String?`），各目标从目录读；CI 的 release body 改为 `grep` 目录文件生成，不要手写。

### 7. 小额：无害但会误导

- `tools/dev.sh:202-213` `dexstat` 与 `:228-234` `size` 只 `unzip classes.dex` 单文件，多 dex APK 的统计与体积都是残值。
- `SettingsHook.kt:68-85` `runCatching { ... }` 块闭合花括号缩进错位（比块体多 4 空格），且 `engine.judge(...)` 落在块内——反射一旦抛异常判据日志就不会打，`installed 1/1` 仍绿。
- `app/settings.gradle.kts:26-30` 注释写「本地 ~/.m2 有货时优先命中」，实际 `mavenLocal()` 排在阿里云/central **之后**，只在远端缺件时兜底。注释与顺序相反，会让人误判依赖来源。
- `ThemeStoreParts.kt:335` `private var vipFixLogCount = 0` 是非原子静态计数，`fixVipState` 可被多线程并发调用（`VipManager.getVipState` 是 LiveData 汇聚点）。危害仅限日志条数，改 `AtomicInteger` 即可。

---

## Known Bugs

### 1. `FeatureStore.setEnabled` 的失败返回值在唯一调用点被丢弃 —— 2026-10-11 记的「写失败必须可见」只落实了一半

`FeatureStore.kt:52-67` 精心设计了 `setEnabled(...): Boolean`，`runCatching{...}.getOrElse{ log; false }` 把「框架未连接 / 写入抛异常」如实回传。`docs/ui-miuix-overview.md` §开关下发通道据此写了「写失败必须可见（2026-10-11）：一级列表顶部显示提示卡」。

但唯一调用点 `FeaturesScreen.kt:527` 是 `onCheckedChange = { FeatureStore.setEnabled(feature.key, it) }` —— **返回值直接丢弃**。提示卡 `SwitchSemanticsCard`（`FeaturesScreen.kt:552-582`）判据是 `!FrameworkState.active`，即 Binder 的**绑定态快照**，与本次写入结果无关。后果：

- Binder 在渲染后、点击前断开 → 开关照常翻转、卡片仍显示「已激活」、hook 侧收不到 → 又一次假反馈。
- `getRemotePreferences(...).edit().putBoolean(...).apply()` 用的是 `apply()`（异步落盘、无返回），异常只在后续 flush 抛，`runCatching` 抓不到 → 返回 `true` 但实际没写进去。

**修法**：`FeaturesScreen.kt:527` 改为 `val ok = FeatureStore.setEnabled(...)`；`false` 时把 `states[key]` 回滚为原值并在二级页顶部替换成一条错误卡（复用 `SwitchSemanticsCard`，新增 `writeFailed` 快照态）；同时给 `FeatureStore` 加一条启动对账（见下条）。

### 2. 远端 prefs 与本地 prefs 无对账 → UI 说「关」，hook 侧跑「全开」

- hook 侧 `RemoteConfig.kt:32`：`prefs?.getBoolean(key, true) ?: true` —— 读不到 / 未连接 / 异常**一律 true**。
- UI 侧 `FeatureStore.kt:34-41`：`init` 只从本地 `honorhook_features` 灌 `states`，**从不读远端**；也没有任何 `syncFromRemote()`。

场景：用户在 LSPosed 里清了模块数据 / 重装 LSPosed / Zygisk 侧重置 → 远端 prefs 消失，但 App 本地 prefs 仍在 → 功能页显示该功能「已关闭」，实际 hook 侧 6 个目标全开。全模块 67 个挂载点默认生效，而 UI 给的是相反的信号。

**修法**：`HonorHookApp.onServiceBind`（`HonorHookApp.kt:20`）在拿到 `XposedService` 后调一次 `FeatureStore.reconcileFromRemote()`：遍历 `FeatureCatalog` 全部 key 读远端，缺失的按 `true` **回写 UI 镜像**（并打一条 log），让 UI 追上 hook 侧的「默认全开」真相。语义写在 `docs/ui-miuix-overview.md`。

### 3. 挽留弹窗挂载点被 `KEY_THEME_VIP` 单键门控，关 VIP 会连带关掉试用相关的弹窗拦截

`ThemeStoreHook.kt:30-33`：

```kotlin
if (RemoteConfig.isEnabled(FeatureCatalog.KEY_THEME_VIP)) {
    collectVipHooks(pending)
    collectRetentionDialogHooks(pending)   // ← 只这一处
}
```

`CollectorsRes.kt:113-134` 的两个点（`OverallRetentionDialogActivity$OverallRetentionDialogManager.showDialog` 空实现 + `OverallRetentionDialogActivity.onCreate` finish）注释自述拦的是「**全部 6 型**」挽留弹窗，其中包含试用到期型；`collectTrialHooks`（`CollectorsRes.kt:137`）才是试用功能位的归属。用户关掉「VIP 会员解锁」、保留「试用永不过期」时，试用到期弹窗会重新弹出——开关语义与功能边界不一致。

**修法**：把 `collectRetentionDialogHooks` 提出来，条件改为 `VIP || TRIAL`（`ThemeStoreHook.kt:30-44`），或按弹窗类型拆成两个 MountKind 分别归属；在 `FeatureCatalog.kt:42-45` 的 description 里写清「VIP 开关同时控制挽留弹窗拦截」。

### 4. 功能全关时三个多点目标只打印 `installed 0/0`，不留任何原因

`SettingsHook.kt:25-28`、`SystemUiHook.kt:49-52`、`LauncherCardTitleHook.kt:46-49` 都在开关关闭时 `logDisabled(...)` 后 `return`。但 `ThemeStoreHook.kt:29-51`、`InstallerHook.kt:48-65`、`Exam8Hook.kt:56-65` 是「按开关逐个 collect」，全部关闭时 `pending` 为空 → `MountEngine.attemptMount`（`MountEngine.kt:77`）把 `totalCount=0` → 打出 `ThemeStoreHook: installed 0/0`。真机上这条既不像成功也不像失败，与「模块没注入」无法区分。

**修法**：`MountEngine.attemptMount` 开头加 `if (pending.isEmpty()) { module.log(INFO, TAG, "$hookName: 本次注入无待挂载项（远端开关全部关闭）"); return }`，一处修好三个目标。

### 5. 挂载点 id 字面量重复，导致按文件计数出现 44 ≠ 43

`"theme:WallpaperPreview.fixPackagePath"` 在两处出现：`CollectorsSource.kt:54`（声明 `id`）与 `ThemeStoreHook.kt:135`（`if (task.id == "theme:WallpaperPreview.fixDeviceTitle")` 形式的分派比较）。按源文件计数得 `35 + 8 + 1 = 44`，全局去重才是 43。台账写的是 43（正确），但任何「按文件加总」的复核都会得出 44。

**修法**（同时消除一个静默失效面）：`ThemeStoreParts.kt:80-98` 的 `MountTask` 加一个枚举字段（`preProcess: PreProcessKind?`，两个值 `WALLPAPER_PACKAGE_PATH` / `BOTTOM_TAB_STATE_FLOW`），`ThemeStoreHook.kt:135` 改判枚举。**当前风险是实的**：若有人改了 `CollectorsSource.kt:54` 的 id 字面量，壁纸修复不会报错，而是静默走进 `else` 分支执行底部 tab 裁剪逻辑（对壁纸 Activity 反射 `getModel`/`getBottomTabConfig`，必然失败被 `runCatching` 吞掉），`installed 43/43` 全绿。这条正是本仓库纪律「禁止任一侧手写字符串」（`FeatureCatalog.kt:6-7`）要防的事。

### 6. DexKit 第二轨命中多条时静默取第一条，无歧义告警

`MethodResolver.kt:124`：

```kotlin
).firstOrNull() // 兜底语义：类名可能已被混淆重映射，按方法特征命中即采纳
```

直连轨对同样的情形是**有告警**的（`MethodResolver.kt:69-73`：「直连反射命中 N 个同名同参数量方法……取第一个」+ 签名）。两条轨的纪律不对称，而「挂错方法」正是 `MethodResolver.kt:68` 自己认定的「最难查的一类失效」。

触发条件真实存在：`common.a#getDownloadType` 的锚点是纯类字段类型（`CollectorsSource.kt:40-45`），OTA 后若 `common` 包内出现第二个持有 `WeakReference` + `DownloadInfo` 的类，就会在两个候选里随机取一个。

**修法**：`DexKitHelper.findMethodByFeatures` 返回全量命中（已做签名去重），`MethodResolver.kt:112-125` 改成：`size > 1` 时 `Log.w(TAG, "DexKit 兜底命中 N 个候选，取第一个: …全部签名…")`，与直连轨同款输出。

### 7. 已排除：挂载点口径漂移（**假问题，勿复查**）

`docs/hook-inventory-audit.md` §一记录的「文档 40/43/39 与代码 37 不一致」经 2026-10-11 全命名空间复查确认为**复核命令写错**造成的假漂移：代码计数 theme 43 / installer 11 与 README 一致。`CLAUDE.md` §未解决已划掉该条。

本次独立复核确认 43 / 11 成立，但**留一条方法论尾巴**：文档给定的复核命令（`hook-inventory-audit.md:7`）只输出**总数 67**，不输出分目标数；而按 id 前缀分组会得到 `theme: 37`、`installer: 8`——正是引发误报的那两个数（theme 的 6 个点用 `cloud:` / `adsource:` / `btn-hide:` 前缀，installer 的 3 个用 `ad-request:` / `ab-request:` / `ad-source:`）。按**源文件**分组才是无歧义口径：

```
InstallerHook.kt 11 / Exam8Hook.kt 10 / SettingsHook.kt 1 /
SystemUiHook.kt 1 / LauncherCardTitleHook.kt 1 / themestore/ 43（三个文件去重后）
```

**修法**：把 `docs/hook-inventory-audit.md:6-8` 的复核命令从「总数 + 手写分项」换成一条能直接打分目标的 per-file 命令，避免下一个人再踩同一个坑。

---

## Security Considerations

### 1. release 包用 debug 签名 —— 模块完整性无保障

`app/app/build.gradle.kts:26-33`：`isMinifyEnabled=true` + `isShrinkResources=true` + `signingConfig = signingConfigs.getByName("debug")`。签名密钥来自 AGP 自动生成的 `~/.android/debug.keystore`（**不在本仓库内**；仓库内不存在 `*.jks` / `*.keystore` / `local.properties` / `*.pem` / `*.key`，`.gitignore:26` 已排除 `local.properties`）。

风险：任何能访问设备的人都可以用同一把公开的 debug 密钥**重签一个同包名 `top.honorhook` 的 APK**，覆盖安装后 LSPosed 仍按原作用域注入它 —— 相当于在 6 个系统/第三方进程里获得任意代码执行。这是 root 级设备上的既有能力放大，不引入新权限，但把「谁在跑我的 hook」从可验证变成了不可验证。
**当前缓解**：无。这是为「本机无正式 keystore」做的有意取舍（`build.gradle.kts:27` 注释）。
**建议**：正式发布前生成一把项目自有 keystore（不入库，`.gitignore` 追加 `*.jks`），并在 `README.md` 标注「发布包为 debug 签名，校验签名后再信任」。

### 2. 全作用域 + 默认全开 + 只改内存态 —— 失效面是「静默」而非「崩溃」

`app/app/src/main/resources/META-INF/xposed/scope.list` 声明 6 个包，`module.prop` 里 `staticScope=true`，`HookEntry.kt:19-35` 对任何注入进程都分发，`RemoteConfig.kt:32` 默认全开。

三者叠加的后果：**任何一个目标版本漂移，模块都不会拒绝工作**。它要么静默不生效（`installed N/43` + 失败清单，用户不看日志就发现不了），要么挂到错误的方法上（第 6 条）。对一个改 6 个系统进程的模块来说，「错了但看不出来」比「崩了」更难处置。
**当前缓解**：`module.prop` 的 `exceptionMode=protective` + 全量 `setExceptionMode(PROTECTIVE)`（`MountEngine.kt:154`、`ThemeStoreHook.kt:65`）保证异常不炸宿主；`installed N/M` 恒输出汇总行。
**建议**：把「上次 `installed N/M`」回读到 UI（见 Missing Critical Features §5），让漂移在功能页可见，而不是只躺在 `/data/adb/lspd/log/modules_*.log`。

### 3. 跳过联网安全检测与指纹校验 —— 这是**主动削弱设备安全能力**

`InstallerHook.kt:230-267` 的 R6 两点（`ly1.h` 分发器短路 + `qf2.g` 指纹直通）让安装任意来源 APK 不再有 HONOR 的联网检测、风险拦截页与安装指纹。README 已如实列出，但没写清后果。
**当前缓解**：`KEY_INSTALLER_SKIP_CHECK` 是独立开关（`FeatureCatalog.kt:23`），可单独关；`InstallerConfigTable.kt:26-31` 保证「门恒不成立」不被误改成 true，且有单测（`DecisionTablesTest.kt:96-102`）。
**建议**：在 `README.md` 的免责声明段补一句该能力边界（装未知来源 APK 时设备侧不再做风险判定），避免用户把「装得上」误读成「装得安全」。

### 4. `QUERY_ALL_PACKAGES` 权限

`app/app/src/main/AndroidManifest.xml:14` 声明，只为在功能页渲染 6 个宿主应用的真实图标与「已安装」过滤（`FeaturesScreen.kt:111-118`、`:186-205`）。这是安装类应用的标准做法，风险面等于全包可见性；`android:allowBackup="false"`（manifest:28）已挡掉备份通道。
**当前缓解**：只读元数据，不读用户数据。
**建议**：无。保持现状即可。

### 5. 日志内容

hook 侧日志会写宿主内部信息（包名、字段路径、资源文案、判据计数），落点 `/data/adb/lspd/log/modules_*.log`（root/LSPosed 可读）。均为逆向诊断所需的**工程信息**，不含凭据、token、设备标识或用户数据。`MountEngine.kt:211` 的 `JUDGE_LOG_PER_TASK=2` + 各处节流把量压在可接受范围。
**当前缓解**：无 PII。
**建议**：无。

### 6. 凭据入库情况

无。仓库内不存在 keystore / `local.properties` / `*.pem` / `*.key` / 凭据文件；`.gitignore:26` 排除 `local.properties`、`:11-19` 排除 `tools/` 下的本地工具链与 `*.apk`；`app/settings.gradle.kts:31-35` 的 `mavenLocal()` 仅限 `includeGroup("io.github.libxposed")`，不会把本地仓库其它内容带入构建。

---

## Performance Bottlenecks

### 1. 挂载热路径上的无谓 `ConcurrentHashMap.merge`

云控读点 `u15.getBoolean` 挂在 `SharedPreferences` 读取上，是全模块频次最高的 hook（主题商店多处循环内读取，`ThemeStoreParts.kt:214` 注释自述）。命中路径：

- `ThemeStoreParts.kt:220-225` `logCloudOverride` → `cloudHitLogCount.merge(key, 1){...}` **每次命中都执行 merge**，只有 `n <= 2` 才打印。也就是说超过 2 次之后，每一次调用都在做一次哈希合并 + lambda 分配，纯粹为了「不打印」。
- `MountEngine.kt:172` `judge()` → `judgeCount.merge(task.id, ...)` 同样的形状，覆盖全部目标的判据点。
- 同形：`ThemeStoreParts.kt:156`（`routeLogCount`）、`:232`（`slotPruneLogCount`）、`:242`（`bottomTabPruneLogCount`）。

**修法**：先 `get()` 判阈值（`if ((count ?: 0) >= LIMIT) return`），只有真要打印时才 `merge`。把「节流」与「计数」解耦——现在的实现把计数和门控绑成了一件事。

### 2. 直连反射轨不缓存（有意设计），代价是每轮重试全量重扫

`MethodResolver.kt:53` 注释明说第一轨「不缓存——重试靠它赌类稍后可加载」。代价：主题商店 43 个点 × 最多 3 轮（`MountEngine.kt:206-208` 的 500ms / 2s 两轮重试）= 最多 129 次 `loadClass` + `declaredMethods`（后者每次返回新数组拷贝，主题商店的 `ItemInfo` / `VipState` 等大类的 `declaredMethods` 不小）。

**修法**：加一层「负结果 TTL 缓存」——同一 `(loader, className, methodName)` 的 **null** 结果在 N 毫秒内不重查（N 取小于最小重试间隔，如 200ms），命中缓存则直接返回 null 走 `NOT_RESOLVED`；成功结果继续不缓存（保持赌类加载的行为）。这能砍掉 2/3 的重复反射，又不破坏重试语义。

### 3. DexKit 全 dex 索引是秒级操作（已知且已缓解）

`DexKitHelper.kt:44-64` 自述「秒级磁盘扫描」；进程级 bridge 缓存（`:20-26`、`:67-86`）+ 命中/未命中双桶缓存（`MethodResolver.kt:18-21`）已经把重复扫描压到「每 key 一次」。当前设计合理。
**唯一残留**：`MethodResolver.kt:21` 的 `missBuckets` 对**每个 miss key 永久记一次**（弱键随 ClassLoader 回收，可接受）。若某目标类永远解析不到，miss 集合会随挂载点数线性增长 —— 规模 67，可忽略。

### 4. SystemUI 的逐元素反射读 `slotId`

`SystemUiHook.kt:93-99` 对订阅列表每个元素做 `javaClass.getField("slotId").getInt(it)`（带 `runCatching`，无缓存）。订阅列表变化频率极低（插拔/启停 SIM），实测成本可忽略。
**不修**，记录在此以免后人误判为热点。

### 5. UI 侧：已处理，记录为正面

`FeaturesScreen.kt:111-118` 用 `produceState` + `withContext(Dispatchers.IO)` 把 6 次跨进程 `getApplicationInfo` 移出组合期主线程；`:424-437` 的 `PackageInfo` / UID 查询走 `remember(target.packageName)`。无剩余明显热点。

---

## Fragile Areas

> 这一节是本仓库的真实风险面。以下每条给出：**文件 → 为什么脆弱 → 如何安全地改 → 测试覆盖缺口**。

### F1. 锚点解析 vs 混淆 + 版本漂移（整个 hook 体系的地基）

**文件**：`app/app/src/main/kotlin/top/honorhook/xposed/core/MethodResolver.kt`、`core/DexKitHelper.kt`、`core/MountEngine.kt:15-20`（`DexHint`）、各 `Collectors*.kt` 里的 `DexHint(...)` 实例、`InstallerHook.kt:74-267`。

**为什么脆弱**：宿主每轮 OTA 都可能让 R8 重新映射混淆名。仓库自己的实证是双向的——`docs/hook-inventory-audit.md` §四.4 记「据 jadx 给 `VipState.getIsVipUser` 加挂载点，真机挂载失败，设备实机 APK 的 dex 里只有 `isVipUser()`」；`ThemeStoreParts.kt:49`（`tryout.a`）、`:63`（`u15`，默认包）、`InstallerHook.kt:24-34`（`lt1`/`sx1`/`n12`/`ly1`/`qf2`/`r12`，六个默认包混淆类）全部是这类。直连轨靠类名+方法名，漂移即失效；DexKit 轨靠锚点，而**锚点强度本身分档**：`InstallerHook.kt:240` 自己标注 `ly1` 只有 `paramTypes` 弱锚点、「OTA 重混淆时诚实 NOT_RESOLVED」。

更关键的是 `docs/theme-store.md` §八·3 末尾那句「**待真机验证**：锚点只在『直连失败』时才被用到，正常版本不会触发第二轨」——**这些锚点从未在真机上跑过一次**。它们目前是一批未经证实的保险。

**如何安全地改**：
- 加/改锚点时，只改 `DexHint`，**不动直连轨的类名方法名**——直连轨优先是 `MethodResolver.kt:112-125` 的既定次序，锚点写错的最坏后果是漂移后回到「诚实跳过」，不是挂错。
- 默认包类（`className` 不含 `.`）**没有内容锚点就允许走 DexKit** 是明确禁止的（`MethodResolver.kt:87-99`）——新增混淆名目标时照此办，别为了「多一层保险」而放开。
- 兜底缓存 key 必须纳入**全部**特征（`MethodResolver.kt:101-107`），否则不同锚点会串味，命中一条错误的缓存比不命中更糟。
- 存在实机 APK 上的验证闭环：`docs/hook-inventory-audit.md` §四.4 的规矩 —— **目标方法存在性以设备实机 APK 的 dex 为准，jadx 仅供阅读**。

**测试覆盖缺口**：零。`DexKitHelper.findMethodByFeatures` 与 `MethodResolver` 的全部判定逻辑（`matchesParameters`、`canonicalTypeName` 的基本类型归一、桥接/合成过滤、歧义 firstOrNull、hit/miss 双桶、cacheKey 组成）**一行测试都没有**。其中 `matchesParameters`/`canonicalTypeName`（`MethodResolver.kt:139-160`）是纯 JVM 逻辑，可直接写单测。

### F2. 远端 prefs 的「下次注入才生效」语义

**文件**：`xposed/data/RemoteConfig.kt`、`ui/FeatureStore.kt`、`ui/screen/FeaturesScreen.kt:522-582`、`docs/ui-miuix-overview.md` §开关下发通道。

**为什么脆弱**：hook 侧在 `HookEntry.kt:21` 注入期读一次并缓存 `RemoteConfig.prefs`，`ThemeStoreHook.kt:30-48` / `InstallerHook.kt:49-63` / `Exam8Hook.kt:57-63` 在 `install()` 里按开关**决定要不要 collect**，因此开关只能改变「挂哪些点」，不能改变「已挂的点」。用户改开关后必须强停目标进程（`tools/dev.sh:368-372` 批量强停 6 个包）。

**用户影响**：改完开关什么都不变，除非重开应用。界面靠 `SwitchSemanticsCard(..., restartHint = true)` 提示（`FeaturesScreen.kt:563`），一级列表不提示（一级 `restartHint = false`，`:149`）——在功能页外改开关看不到任何提示。
**这是有意的、已文档化的限制，不是 bug**；但它是本项目最容易被当成 bug 报上来的一条。

**如何安全地改**：
- 若要让开关热生效，语义要改成「挂载点始终挂，intercept 内部每次读 `RemoteConfig.isEnabled`」。代价是每次调用多一次 map 查询（已 `runCatching` 缓存），以及失去「关掉就完全不挂」的性能与风险隔离。**不建议**，除非有明确需求。
- 任何改动都必须同步 `FeatureCatalog.kt`（双端共用的 key 字面量，禁止任一侧手写）与 `docs/ui-miuix-overview.md`。
- 必须保住 `RemoteConfig.kt:32` 的 `getOrDefault(true)` 默认开语义 —— 它是「模块在没有任何 UI 写入时也全功能工作」的兜底。

**测试覆盖缺口**：`RemoteConfig` / `FeatureStore` 的默认开与「写失败回传」语义无任何测试（两者都依赖 `SharedPreferences`，需要 mock 或 Robolectric，当前未引入）。见 Known Bugs §1、§2。

### F3. R8 / `java_init.list` 的打包契约

**文件**：`app/app/build.gradle.kts:51-59`、`app/app/proguard-rules.pro`。

**为什么脆弱**：这是全仓唯一的「构建配置错了但零报错、运行时静默失效」的面。`excludes += "**"` 会把所有非代码资源排除掉，`merges += "META-INF/xposed/*"` 是唯一的活路；`-adaptresourcefilecontents META-INF/xposed/java_init.list`（`proguard-rules.pro:10`）与 `-keep ... HookEntry`（`:17`）必须**严格锁步**——`proguard-rules.pro:12-16` 自己记了实证：「R8 曾把 `HookEntry` 改名进默认包（release 产物 `java_init.list` 变成 `'r10'`）」，此时 LSPosed 静默跳过整个模块、**构建零报错**。`:21-23` 还记了 `onPackageReady` 空方法体已被 dead-code 消除、未来填逻辑会被静默裁掉。

**如何安全地改**：
- 动 `packaging` 或 proguard 规则后，**必须**回归 release 产物的三份元数据：

```bash
tools/dev.sh build-release
unzip -p app/app/build/outputs/apk/release/app-release.apk META-INF/xposed/java_init.list
unzip -p app/app/build/outputs/apk/release/app-release.apk META-INF/xposed/scope.list
tools/dev.sh apkinfo app/app/build/outputs/apk/release/app-release.apk   # label/description 必须是 @string 来源
```

- 永远不要给 `HookEntry` 加 `allowobfuscation`（`proguard-rules.pro:12-16` 已论证通配 + allowobfuscation 是锁步脆弱设计）。
- 新增第二个 `XposedModule` 子类时，**不要**依赖通配 keep——具名加一行，并把类名同步进 `java_init.list`。

**测试覆盖缺口**：零。CI（`.github/workflows/ci.yml`）的 master 路径只有 `assembleRelease` + 发 Release，**不对产物做任何元数据断言**；dev 路径只跑单测（`:37-42`）。AGP/R8 升级会让这个契约在无人察觉的情况下断掉。见 Missing Critical Features §1。

### F4. DexKit 兜底轨本身的可用性

**文件**：`app/app/src/main/kotlin/top/honorhook/xposed/core/DexKitHelper.kt:32-86`。

**为什么脆弱**：`System.loadLibrary("dexkit")`（`:37`）依赖 `libdexkit.so` 在宿主进程可加载。它来自 `libs.dexkit` 的 AAR，**arm64 only**（`build.gradle.kts:20-22` 的 `abiFilters` 只有 `arm64-v8a`）。失败路径是 `:40` 打一条 `Log.e` 后 `dexKitLoaded` 永远为 false —— **兜底线整体静默失效**，而 UI/日志汇总里没有任何「兜底不可用」的信号，只有每个点的 `NOT_RESOLVED`。

另有 native 资源生命周期：`cachedBridge` 缓存后**不 close**（`:44-49` 注释），仅在 `apkPath` 变化时关旧桥（`:80-83`）；而 `apkPath` 来自 `param.applicationInfo?.sourceDir`（`ThemeStoreHook.kt:24`），是**基础 APK**路径 —— 若目标类落在 split dex 里，第二轨扫不到。

**如何安全地改**：
- 不要给 `DexKitHelper` 加 `use{}` 语义或随手 close（缓存复用是刻意的）。
- `dexKitLoaded` 失败时应当让 `MountEngine` 在汇总行里标一句「DexKit 兜底不可用」，否则「43 点里 3 个 NOT_RESOLVED」与「so 没加载进来」在日志里长得一样。
- 若将来支持 32 位/模拟器，`abiFilters` 要与 `DexKitHelper.kt:37` 的假设一起改，不能只改一处。

**测试覆盖缺口**：零，且**无法在 JVM 单测覆盖**（需要真实 so + 宿主 APK）。只能在真机上通过「让某个点直连必失败」来间接验证（当前无此手法）。

### F5. 失败分级的跨实现一致性

**文件**：`core/MountEngine.kt:11-12`（`enum Result`）、`:145-164`（`mount`）、`ThemeStoreHook.kt:145-161`（自建 fold）、`InstallerHook.kt:324-404`、`Exam8Hook.kt:194-209`。

**为什么脆弱**：`FAILED`（重试无意义）与 `NOT_RESOLVED`（随类加载重试）的语义是整个挂载体系的可观测性基础，`CLAUDE.md` §构建 gotchas 有明文。但 6 个目标里 5 个走 `engine.mount`、1 个（`ThemeStoreHook`）手写 fold —— 分级一旦在某处写反，表现是「该重试的点不重试」或「永远重试」，而 `installed N/M` 的 M 仍然正确（`MountEngine.kt:76-77` 只在首轮记总数），排起来极难。

**如何安全地改**：统一到 `engine.mount`（见 Tech Debt §1）。在此之前，任何改 `ThemeStoreHook.kt:145-161` 的改动必须逐行核对 `onSuccess → OK` / `onFailure → FAILED` / `resolveTask` 返回 null → `NOT_RESOLVED` 三条路径。

**测试覆盖缺口**：零。`MountEngine` 的重试轮计数（`:77`、`:100-105`）、`MAX_RETRY_ROUNDS` 耗尽后的清单合并（`:90-99`）、`totalCount` 只在首轮赋值（`:76-77`）—— 这些是可纯 JVM 测的语义（把 `XposedModule` mock 掉即可），一条测试都没有。

### F6. 三处包名清单必须一致

**文件**：`app/app/src/main/resources/META-INF/xposed/scope.list`（6 行）、`xposed/target/TargetRegistry.kt:19-24`（6 次 `register`）、`FeatureCatalog.kt:38-82`（6 个 `Target`）、`tools/dev.sh:368-371`（`enable-module` 硬编码 6 包）、`.github/workflows/ci.yml:68-69`（release body 手写 6 包）、`README.md:12-17` 与 `:51`。

**为什么脆弱**：`TargetRegistry.kt:18` 注释已经点明「`scope.list` 须同步声明目标包名（targets 与 scope 不一致时 `HookEntry` 收不到该包的回调）」。漏一处的后果被 `tools/dev.sh:365-372` 的注释称为「**假成功**」——部署脚本报成功，但该目标的 hook 从未注入。

**如何安全地改**：新增目标时按 `docs/README.md` 的纪律，**先在 `dev.sh` 加子命令**，然后同步这 6 处。更稳的做法是让 `tools/dev.sh scopes` / `targets` / CI release body 从 `scope.list` 与 `FeatureCatalog.kt` 生成，消灭手写副本。

**测试覆盖缺口**：零。`scope.list` 与 `FeatureCatalog.targets` 的一致性完全可以写成一个 JVM 单测（同模块内，无需 Android）——这是性价比最高的一条待补测试。

### F7. 混淆名默认包类的锚点纪律

**文件**：`ThemeStoreParts.kt:49`（`$PKG.tryout.a`）、`:59`（`$PKG.common.a`）、`:63`（`u15`，**无包名**）；`InstallerHook.kt:24-34`（`lt1`/`sx1`/`n12`/`ly1`/`qf2`/`r12`，全部无包名）。

**为什么脆弱**：这 9 个类的名字是「当前这一版 R8 的产物」，没有任何稳定性保证。它们能工作完全依赖 DexKit 锚点，而锚点强度分三档——

- 强（方法体独有字符串）：`tryout.a#isValidTheme`（`"not register theme ware"`）、`u15#getBoolean`（`"themename"` + 参数序列）、`lt1#b` / `sx1#Q` / `n12#f` / `r12#j` / `r12#s`（独有日志串 + 含可读类名的参数序列）。
- 中（类字段类型）：`common.a#getDownloadType`（`WeakReference` + `DownloadInfo`，类内无字符串、方法无参数）。
- 弱：`ly1#h`（类内零字符串常量，只有 `paramTypes=[boolean, sy1]`，且 `sy1` 自身也是混淆名）——`InstallerHook.kt:240` 自己标注「诚实 NOT_RESOLVED」。

**如何安全地改**：新增混淆名目标前先查 `docs/theme-store.md` §八·3 的锚点强度排序表，照抄纪律；**无锚点就别挂**，让汇总行暴露缺口，不要发明「同名同签名第一个」这种假兜底（`MethodResolver.kt:87-99` 明确禁止）。

**测试覆盖缺口**：零，且原理上无法在 CI 覆盖（需要对应版本的宿主 APK）。最现实的兜底是把 `tools/adapt_new_version.py` 修好（Tech Debt §2）当成本地/人工的漂移探测器。

---

## Scaling Limits

- **新增 1 个目标**的固定成本：1 个 `TargetHook` object + 1 个 `MountEngine` 实例 + 1 份私有 `MountTask` + 判据日志前缀常量 + `TargetRegistry.kt` 注册一行 + `scope.list` 一行 + `FeatureCatalog.kt` 一个 `Target` + `tools/dev.sh:368` 强停列表一行 + CI release body 一行 + `docs/reverse/<app>.md` + README 表格一行。**9 处手工同步，无一处在构建期校验一致性**（F6）。
- **新增 1 个挂载点**的成本是 1 行声明，但随之而来的是：判据/描述文案、`docs/` 台账口径同步、**一次真机 A/B**（`docs/hook-inventory-audit.md` §四.1 明确「静态推理不足以作为删除依据，必须真机 A/B」）。挂载点总数当前 67，验证成本线性增长且无法自动化 —— 这是本项目最硬的规模天花板。
- **无构建期不变量**：没有「`ThemeStoreHook` 必须正好 collect 43 个点」的断言，也没有「`scope.list` ⊆ `FeatureCatalog.targets`」的断言。台账与代码的一致性 100% 靠人工 + 人工 grep（且 grep 口径本身踩过坑，见 Known Bugs §7）。
- **单 APK 双运行面**（hook 侧无 UI 依赖 + UI 侧 Compose/MP）已经是一个耦合点：`compileSdk = 37` 而 `targetSdk = 34`（`build.gradle.kts:9`/`:14`）—— 编译期 API 面与运行期行为面不一致是刻意的（模块要兼容旧宿主），但任何 `Build.VERSION` 相关判断都要想清楚是哪一层。
- **UI 侧 3735 行 / hook 侧 2805 行**（`docs/ui-miuix-overview.md` §压缩边界）比例接近 1:1，也就是说维护成本的一半在一个不参与 hook 的配置面板上 —— 有意的产品形态，但没有为它设任何质量门禁（见 Test Coverage Gaps）。

---

## Dependencies at Risk

| 依赖 | 版本（`app/gradle/libs.versions.toml`） | 风险与现状缓解 |
|---|---|---|
| libxposed api / service | `102.0.0`，api 为 `compileOnly`、service 为 `implementation` | 框架侧提供运行期类。已知缺口：该 fork 只有 `onSystemServerLoaded`，api 102 无此回调类型，`docs/reverse/README.md` §三 记为「放弃，勿重新评估」。api 升到 103+ 可能改变回调集合，届时 `HookEntry.kt:19` 的覆写面要重新核对，且**没有测试会发现**（编译期能过，运行时静默不回调）。 |
| DexKit | `2.2.0` | 唯一的混淆名兜底依赖，专有 native so、**arm64 only**（`build.gradle.kts:20-22`）。API 已在 `docs/theme-store.md` §八·3 逐项 `javap` 核对过（这是好实践）。风险：so 加载失败无用户可见信号（`DexKitHelper.kt:40`）。 |
| Miuix (KMP) | `0.9.4` | 5 个 artifact 全用同一版本线（core/ui/preference/icons-android/blur）。UI 全部构建在上面，一次上游破坏性变更 = 全 UI 编译失败（至少会被 CI 抓到）。 |
| `miuix-blur` 的 minSdk override | `AndroidManifest.xml:8` `tools:overrideLibrary` | 用 `minSdk = 26` 覆盖库声明的 33。安全前提是「只在 `SDK_INT >= 33` 时调用」（`MainActivity.kt:94` 的 `enableBlurSdk` 守卫）。**若哪天有人去掉那个守卫，API 26 设备会 NoSuchMethodError** —— 编译期与单测都发现不了。 |
| Compose Multiplatform / Material3 | `compose = 1.11.0`（稳定）、`composeMaterial3 = 1.11.0-alpha07`（**alpha**） | Material3 只有 alpha 线（`libs.versions.toml:9-10` 注释）。alpha 版的 API/行为漂移会直接打到 UI，且 UI 侧零测试。 |
| MaterialKolor / Backdrop | `5.0.1` / `2.0.0` | 动态取色与液态玻璃的关键色来源；改动直接影响视觉，无回归手段（只能人工看）。 |
| AGP + Kotlin | `9.2.1` / `2.3.21` | **最危险的一条**：`proguard-rules.pro:12-16` 实证过「R8 改名行为」会打破 `java_init.list` 契约且构建零报错（F3）。AGP/R8 升级 = 必须做 F3 的回归三连。 |
| 阿里云镜像 + `repo1.maven.org` 直连 | `app/settings.gradle.kts:9-31` | Compose MP 的 material3/material-icons 只在 Maven Central（注释明说阿里云未同步）→ **构建硬依赖外网可达**；同时 `mavenLocal()`（限 `io.github.libxposed`）排在最后作兜底，注释却说「优先命中」，语义与顺序相反（Tech Debt §7）。 |
| `.claude/skills/apk-reverse/` | vendor 的第三方 skill | 上游已废弃不更新（`docs/README.md` §四已标注 MIT + 原样未改）。**不是构建依赖**，但 `docs/reverse/README.md` §三·2 的放弃判据引用它的结论 —— 上游若被静默替换，判据随之漂移。 |
| `reference/*`（10 个 submodule） | 固定 commit | 只读第三方仓库，`.gitmodules` 里含中文目录名（`reference/HyperChanger（同类插件）`）。不是构建依赖；是「照抄来源」。`app/app/src/main/kotlin/top/honorhook/ui/component/` 下的 vendored 件（`FloatingBottomBar.kt` 等约 1300 行）保留了上游对齐义务（`docs/ui-miuix-overview.md` §压缩边界明写「改写会与上游脱钩，升级时无法逐行比对」）。 |

---

## Missing Critical Features

1. **release 产物元数据回归**（对应 F3）。`.github/workflows/ci.yml:52-54` 只 `assembleRelease` 就发 Release。`java_init.list` / `scope.list` / `module.prop` / `label`+`description` 是否完好**无人检查**。建议在 master job 加一步：`unzip -p app-release.apk META-INF/xposed/java_init.list` 必须等于 `top.honorhook.xposed.HookEntry`，`scope.list` 必须等于 `FeatureCatalog` 的 6 个包（用 `dev.sh scopes` 取），任一不符即 fail。

2. **挂载点数量的构建期不变量**（对应 Scaling Limits / Known Bugs §7）。建议加 JVM 单测：`collect*` 系列在给定开关全开时产出的 `pending.size` 必须等于台账数（43 / 11 / 10 / 1 / 1 / 1）。这需要把 `collect*` 提到可测作用域（目前部分 `private`），或用源码解析（`tools/adapt_new_version.py` 的路子）——后者顺带把 §2 的提取器修好。

3. **版本漂移的自动化守卫**（对应 F1 / F7）。当前只有运行期的 WARN（`MountEngine.kt:183-202`），无构建期/提交期检查，而唯一的漂移探测脚本 `tools/adapt_new_version.py` 已失效（Tech Debt §2）。修好它是这里 ROI 最高的一件事。

4. **开关状态对账与自愈**（对应 Known Bugs §1、§2）。UI 与 hook 侧各存一份状态、无对账、无失败反馈。建议合并为：`FeatureStore.reconcileFromRemote()` + `setEnabled` 返回值驱动 UI 提示。

5. **`installed N/M` 回读通道**（对应 Security §2）。判据日志是唯一的生效证明，但落在 `/data/adb/lspd/log/modules_*.log`，用户看不到，宿主 App 也没有「日志页」（`dev.sh log-file` 指向的页面不存在，Tech Debt §5）。建议用 `XposedService.openRemoteFile` 落一份结构化结果到 App 私有目录，首页/功能页展示「上次挂载：43/43 · 3 分钟前」。这一条同时补掉 §5 的断链。

6. **全局总闸**。`FeatureCatalog` 只有 13 个细粒度 key，**没有「模块整体停用」总开关**（历史上删过 `KEY_HOOK_ENABLED`，`docs/hook-inventory-audit.md` §二记为调试残留物清理）。对一个 hook 6 个系统进程、且默认全开的模块，缺少一键全停意味着宿主升级导致行为异常时，用户必须逐项关 13 个开关或直接卸载。

7. **已文档化的未交付能力**（不在「缺功能」但在「没做完」，此处只列指针）：主题商店搜索页「免费领」入口是本地硬编码 tab，云控与槽位裁剪都够不到 → `docs/theme-store.md` §七·2。这是当前最高优先级的未解决项，`AdSlotFilter` 里的 `Rule("22", ...)` 与 `Rule("1019001", ...)` 已在打这场仗的边（搜索框左槽位 / 灯笼位内嵌入口），但 `SecondTabBean.synthesisTab(app)` 这条本地硬编码路径完全在覆盖范围外。

---

## Test Coverage Gaps

**现状**：`app/app/src/test/kotlin/top/honorhook/xposed/data/DecisionTablesTest.kt` 是**唯一**的测试文件，10 个 `@Test`，全部针对 4 张决策表（`AdSlotFilter` / `BottomTabFilter` / `CloudSwitchTable` / `InstallerConfigTable`）。`app/app/build.gradle.kts:100` 只引入 `junit`，无 Robolectric、无 mockk、无 androidTest 源集。CI 在 dev 分支跑 `./app/gradlew -p app test` 作为真门禁（`ci.yml:41-42`，无 `|| true`），master 分支**不跑任何测试**（`ci.yml:52-54`）。

**按优先级该补的（全是纯 JVM，不需要 Robolectric）**：

| 优先级 | 目标 | 覆盖什么 | 为什么现在没被发现问题 |
|---|---|---|---|
| P0 | `MethodResolver.matchesParameters` / `canonicalTypeName` | `paramTypes` 精确匹配、`int`↔`java.lang.Integer` 归一、无 `paramTypes` 时退化为 `paramCount` | 重载消歧（`SystemUiHook` 的 `updateSubs(List)`/`updateSubs(MobileCallBack)`）全靠它，写错会挂到重载上且无告警 |
| P0 | `MountEngine` 结果分级 | `FAILED` 不进重试、`NOT_RESOLVED` 进重试、`MAX_RETRY_ROUNDS` 耗尽后清单合并、`totalCount` 只在首轮赋值 | `installed N/M` 的 M 是全仓唯一的真机判据，它的正确性没有一行测试 |
| P0 | `FeatureCatalog.targets` ↔ `scope.list` ↔ `RemoteConfig.isEnabled` 调用点 | 包名/key 双向齐全、无孤儿 key | drift = 静默不注入（F6）；`FeatureCatalog.kt:6-7` 的「禁止手写字符串」纪律只有注释保护 |
| P1 | `collect*` 挂载点数量 | 全开时 `pending.size` == 台账 43/11/10 | 台账口径已踩过一次假漂移（Known Bugs §7） |
| P1 | `tools/adapt_new_version.py::extract_mounts` | 提取点数 == 67、`pkg` 非空 | 脚本现在只提 9 个且包名全空，**没有任何机制发现它坏了** |
| P1 | `pruneBottomTabPayload` / `pruneTabStateFlowBeforeCreateTabs` 的放弃分支 | 裁后 < `MIN_LIST_SIZE` 时返回 `null`（放行原配置）而非裁到 2 项 | 裁少了会触发宿主静态回退分支、VIP tab 反而复活（`BottomTabFilter.kt:31-38`）。`MIN_LIST_SIZE >= 3` 有测试（`DecisionTablesTest.kt:57-64`），但**调用方是否真的用它兜住**没有 |
| P1 | `FeatureCatalog` 13 个 key 的语义（哪些是「门恒不成立」的负向常量） | 类似 `DecisionTablesTest.kt:96-102` 对 `InstallerConfigTable` 的断言，扩展到主题商店的固定值 | `TS_2088_08_08_MILLIS`（`ThemeStoreParts.kt:42`）、`vipStatus=2`（`CollectorsRes.kt:73`）、`downloadType=3`（`CollectorsSource.kt:32`）这类魔法数被改成别的值时，只有真机能发现 |
| P2 | `RemoteConfig` 默认开 / `FeatureStore` 失败回传 | 读不到时恒 `true`；写失败返回 `false` | 需要 mock `SharedPreferences`（可用 `SharedPreferences` 空实现，无须 Robolectric） |
| P2 | `AdSlotFilter` / `BottomTabFilter` 表与宿主实测值的对应 | 已有 round-trip 测试；缺的是「线上真值仍在表里」的证据 | 表项来自探针/线上观测，宿主改 ID 就静默失效 |
| P2 | `MethodResolver` cacheKey 组成（`:101-107`） | 全部特征进 key，不同锚点不串缓存 | 串味 = 命中错误的缓存结果，比不命中更难查 |

**无法用单测覆盖、只能靠真机 A/B 的部分**（需要如实承认）：全部 DexKit 锚点的有效性（F1）、`java_init.list` 的 R8 契约（F3，需产物级回归而非单测）、所有 UI 行为（`app/app/src/main/kotlin/top/honorhook/ui/screen/*` 共 1703 行，**零测试**，含 `FeaturesScreen.kt:527` 这类已知的返回值丢弃）、`system-files/` 的 file:line 逆向引用（该目录被 `.gitignore:7` 排除，新人无法复现验证）。

---

## Unresolved Questions

1. **DexKit 锚点从未在真机上被触发过。** `docs/theme-store.md` §八·3 末尾原话：「待真机验证：锚点只在『直连失败』时才被用到，正常版本不会触发第二轨」。这 9 组锚点目前是**未验证的保险**——它们的正确性没有任何证据。可行验证手法：在某个混淆名目标上临时删掉直连轨（让 `MethodResolver.kt:80` 的 `if (direct != null) return direct` 之前直接跳过），看 DexKit 轨能否命中并维持 `installed 43/43`。**建议做一次**，成本低、信息量高。

2. **`ly1#h` 的弱锚点在 OTA 后是否还有意义。** `InstallerHook.kt:240` 自己标注「类内零字符串常量 ⇒ 仅参数类型序列弱锚点（OTA 重混淆时诚实 NOT_RESOLVED）」，而 `paramTypes = [boolean, sy1]` 里的 `sy1` 本身也是混淆名 —— 两个混淆名互相做锚点，本质上不构成稳定特征。要么找真锚点，要么明确接受「R6 在漂移后失效」并写进 README。

3. **`SettingsHook` 的宿主基线版本号格式不一致。** `SettingsHook.kt:47` 是 `14.0.0.210`（三段），其余五个是四段（`20.1.63.305` / `14.0.37.301` / `18.0.0.234` / `14.0.37.301`）；`SystemUiHook.kt:39` 是 `17.0.0.1`（三段）。而 `MountEngine.kt:194` 用的是 **`versionName` 精确字符串相等**比较 —— 任何 OEM 后缀（`20.1.63.305-xxx` 之类）都会触发 WARN。是噪声还是有意，是开放问题。建议改成「前缀/主版本号匹配」或干脆降为 INFO 级提示。

4. **`exam8` 超级会员档位编码 `4` 的证据强度。** `Exam8Hook.kt:39` 注释写「后端档位编码 1=轻享 2=功勋 4=超级；**待真机诊断日志确认**」，而 `README.md:17` 已把它当作既定结论写进功能表。代码注释与文档口径不一致 —— 要么补真机证据，要么把「待确认」同步到 README。

5. **发布通道不过测试门禁。** `ci.yml:52-54` 的 master 分支直接 `assembleRelease` 然后发 GitHub Release。发出去的包没跑过 `DecisionTablesTest`。dev 分支的单测门禁对发布路径无效。建议 master job 也先 `test`。

6. **`abiFilters` 只有 arm64 的长期取向。** `build.gradle.kts:18-22` 注释说「项目只做真机直连验证」，据此裁掉 x86/x86_64/armeabi-v7a。这对当前工作流是对的，但意味着**没有任何模拟器路径**可用来做 hook 冒烟测试 —— 也意味着 `DexKitHelper.kt:37` 的 so 加载在 CI 上无法被验证。是长期选择还是临时状态，需明确。

7. **`system-files/` 不入库导致证据不可复现。** `.gitignore:7` 排除了整个逆向产物目录，而 `docs/theme-store.md` / `docs/reverse/*.md` 里大量 `file:line` 引用（如 `common/a.java:11-12`、`u15.java:218-221`）都指向那里。新克隆的仓库无法复核任何一条挂载点依据。是有意的（体积），但配合「静态推理不足以作为删除依据，必须真机 A/B」（`docs/hook-inventory-audit.md` §四.1），新人实际上**无法独立复核任何删除决策**。

8. **`v2.3 boot-loop 事故模式** 只在注释里留了一句（`CollectorsRes.kt:161`），没有事故记录。注释说「仅运行于主题商店进程挂载/重试线程（非 SystemUI/launcher 启动路径，v2.3 boot-loop 事故模式不适用）」—— 这是一条真实踩过的坑，但触发条件与处置方式已随历史文档删除（`docs/README.md` §开头）。同类事故复发时无人知道红线在哪。建议补进 `docs/hook-craft.md`。

---

*Concerns audit: 2026-10-11*
