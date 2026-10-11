---
last_mapped_commit: fe2e820eb97798af089b982b570191734c867fcb
last_mapped_at: 2026-10-11
---
# Coding Conventions

**Analysis Date:** 2026-10-11

> 源码根是 `app/app/src/main/kotlin/top/honorhook/`（**不是** `src/main/java/`，该目录不存在）。
> 约定全部由 `CLAUDE.md` + `docs/` 的纪律条款 + 代码实际形态归纳；本文只写"用哪个模式"，不写"读代码就能得到"的清单。

## Naming Patterns

### Files

- **类名 = 文件名**，一个文件一个顶层声明（Kotlin 标准）。例外是 `themestore/CollectorsSource.kt` / `CollectorsRes.kt` / `ThemeStoreParts.kt` 这类按职责拆分的超大目标（单目标可跨多文件）。
- **hook 侧按层分包**，固定四层：`xposed/HookEntry.kt`（入口）→ `xposed/core/`（挂载基建：`MountEngine` / `MountScheduler` / `MethodResolver` / `DexKitHelper`）→ `xposed/data/`（纯决策表，无 Android 依赖）→ `xposed/target/`（逐目标 hook）。
- **目标 hook 类名恒为 `<Target>Hook`**，且恒为 `object`：`ThemeStoreHook`、`InstallerHook`、`SettingsHook`、`Exam8Hook`、`LauncherCardTitleHook`、`SystemUiHook`（注册于 `app/app/src/main/kotlin/top/honorhook/xposed/target/TargetRegistry.kt:19-24`）。新增目标照此命名，不引入别的后缀。
- **UI 侧**：`ui/MainActivity.kt`（单 Activity）、`ui/screen/<Tab>Screen.kt`、`ui/component/<组件>.kt`，玻璃相关组件进 `ui/component/liquid/`。
- **跨双端共享的唯一文件**是包根的 `app/app/src/main/kotlin/top/honorhook/FeatureCatalog.kt`——它必须**不含任何 Compose/Android import**（见 `FeatureCatalog.kt:5-8`），否则 hook 侧进程会缺类。
- **vendored 移植件在 `package` 之前加三行表头**（来源 + 许可 + `vendored` 标记 + 禁压指针），例如 `app/app/src/main/kotlin/top/honorhook/ui/component/FloatingBottomBar.kt:1-3`：
  ```kotlin
  // Adapted from compose-miuix-ui example (IosLiquidGlassNavigationBar) — Apache 2.0.
  // 照抄 KernelSU `ui/component/FloatingBottomBar.kt`，仅改包名与取色（MiuixTheme）。
  // vendored：上游移植件，勿为压缩行数改写（见 docs/ui-miuix-overview.md §压缩边界）
  ```
  `ui/component/liquid/*`、`ui/component/InteractiveHighlight.kt`、`ui/component/DampedDragAnimation.kt`、`ui/component/DragGestureInspector.kt` 同样适用。
- **测试目录镜像主包路径**：`app/app/src/test/kotlin/top/honorhook/xposed/data/DecisionTablesTest.kt` ↔ `app/app/src/main/kotlin/top/honorhook/xposed/data/`。
- **ProGuard 规则文件存在**：`app/app/proguard-rules.pro`（35 行，已入库）。无 `consumer-rules.pro`、无 `lint.xml`。

### Functions

- 标准 lowerCamelCase；hook 侧功能函数带角色前缀，且这些前缀是**约定词**，不要换：
  - `collect*` —— 往 `pending` 挂载表里追加任务的表驱动构造器：`collectVipHooks(pending)`、`collectBurnAfterReadingHooks(pending)`（`xposed/target/themestore/ThemeStoreHook.kt:31-49`、`xposed/target/exam8/Exam8Hook.kt:73,94,127`）。
  - `addConst` / `addConditional` / `addForceArg` —— 挂载行的位置式构造器。
  - `mountOne(task, module, loader): MountEngine.Result` —— 逐目标 intercept 分派，签名在所有目标里一致。
  - `logDisabled(module, key, hookName)` —— 功能被远端开关关闭时的留痕（`xposed/target/TargetHook.kt:21`）。
  - `engine.judge(...)` / `engine.begin(...)` / `engine.attemptMount(...)` / `engine.mount(...)` —— 挂载基建四件套。
- **表查询谓词**统一"可空入参 + 安全默认出参"：`isPrunedSlotType` / `isPrunedTargetId`（`xposed/data/AdSlotFilter.kt:31,34`）、`ruleFor`（`BottomTabFilter`）、`forced`（`CloudSwitchTable`）。这是被 JVM 单测覆盖的那一层，必须保持纯函数、无 Android 依赖。
- **开关读写**恒为 `isEnabled(key)` / `setEnabled(key, enabled)`（`xposed/data/RemoteConfig.kt:31`、`ui/FeatureStore.kt:44,52`），初始化恒为 `attach(...)`（hook 侧 `RemoteConfig.kt:26`）/ `init(context)`（UI 侧 `FeatureStore.kt:34`、`ui/AppSettings.kt:109`）。
- Compose：每个 Tab 一个 `@Composable fun XxxScreen(bottomInnerPadding: Dp = 0.dp)`（`ui/screen/FeaturesScreen.kt:86`），子页一律 `private` 且显式传参（`TargetListScreen` / `FeatureListScreen`）。
- **Kotlin 测试方法名用反引号英文句子**，描述行为契约而非方法名：`fun \`unknown slot type is kept\`()`（`app/app/src/test/kotlin/top/honorhook/xposed/data/DecisionTablesTest.kt:29`）。

### Variables

- **宿主标识常量一律 `private const val` + 大写蛇形**，并带语义前缀：
  - `CLS_*` 宿主类名 —— `CLS_APP_DETAILS`（`xposed/target/SettingsHook.kt:21`）、`CLS_VIP_UTILS`（`xposed/target/exam8/Exam8Hook.kt:46`）、`CLS_SECURITY_CHECK = "ly1"`（混淆名也照此命名，`xposed/target/InstallerHook.kt:30`）。
  - `ID_*` 宿主资源 id **名**（不是数值）：`ID_BUTTON_PANEL` / `ID_BUTTON_DISABLE`（`SettingsHook.kt:50-51`）——资源 id 数值逐版漂移，一律 `getIdentifier(name, type, pkg)` 运行时取。
  - `PKG_*` 宿主包前缀，允许 Kotlin 模板拼接：`private const val PKG_HN = "com.honor.packageinstaller"` 后 `CLS_COMMON_CONFIG = "$PKG_HN.hncust.commonconfig.CommonConfigManager"`（`InstallerHook.kt:20,38`）。
  - `HOST_VERSION_BASELINE` —— 每个目标必声明，挂载首轮做版本自检（`SettingsHook.kt:47`、`SystemUiHook.kt:39`、`ThemeStoreHook.kt:164`）。
  - `JUDGE_PREFIX` —— 判据日志前缀恒为 `<target>-hook`：`"settings-hook"` / `"systemui-hook"` / `"exam8-hook"` / `"launcher-hook"`。
  - 调参常量集中在 `MountEngine.companion`：`MAX_RETRY_ROUNDS` / `RETRY_DELAY_MS` / `RETRY_FINAL_DELAY_MS` / `JUDGE_LOG_PER_TASK` / `DEBUG_LOGGING`（`xposed/core/MountEngine.kt:204-215`）。
- **功能 key 只能用 `FeatureCatalog.KEY_*` 常量，禁止内联字符串字面量**——双端（宿主 UI + 注入进程）共用同一份 key 是硬约束（`FeatureCatalog.kt:14-32`，`docs/ui-miuix-overview.md:7`）。
- 局部变量用能读出判据的名字，不用 `i`/`tmp`：`pending` / `unresolved` / `failedIds` / `kept` / `orig` / `cur` / `out`。挂载表条目对象统一叫 `task`。
- **标识符、路径、包名一律 ASCII 英文**；中文只出现在注释、KDoc 与面向用户的字符串字面量里（`CLAUDE.md:22` "路径全英文"）。注意这条对 `tools/*.sh` 同样成立，见 §Environment。

### Types

- **无状态基建与 hook 目标一律 `object` 单例**：`MountScheduler`、`MethodResolver`、`DexKitHelper`、`RemoteConfig`、`FeatureStore`、`AppSettings`、`FrameworkState`、全部 `*Hook`。
- **只在需要实例状态时用 `class`**：`MountEngine(hookName, hostPackage)`（`MountEngine.kt:9`）、`MainActivity`、`HonorHookApp`。
- **表行用 `data class`**：`Feature(key, title, description)` / `Target(packageName, name, features)`（`FeatureCatalog.kt:34-36`）、`Rule(value, note)`（`AdSlotFilter.kt:11`）、`MountEngine.DexHint(strings, paramTypes, classStrings, classFieldTypes)`（`MountEngine.kt:15-20`）。表行的第二列永远是**语义与依据**，不是装饰。
- **枚举带 payload**：`enum class Result { OK, FAILED, NOT_RESOLVED }`（`MountEngine.kt:12`）、`MountKind`、`enum class ColorMode(val value: Int)`（`ui/AppSettings.kt:14-21`）。
- **扩展点用 `interface`**：`TargetHook { val packageName; fun install(module, param) }`（`xposed/target/TargetHook.kt:25-32`）、`MountEngine.Task`（`MountEngine.kt:23-36`）。`Task` 把 kind/route 这类目标私有字段留在目标侧，基建只认 `id/className/methodName/paramCount/returnType/description/dex/resolveValue`。
- **跨线程可见性显式标注**：`@Volatile`（`RemoteConfig.kt:18`、`DexKitHelper.kt:17`）、`@Synchronized`（`FrameworkState.kt:33`、`MethodResolver.kt:24`）；缓存用 `ConcurrentHashMap` / `WeakHashMap`（`MethodResolver.kt:18-21`），一次性告警用 `AtomicBoolean`（`SystemUiHook.kt:84`）。
- **可见性**：默认 `private`；同模块跨文件共享用 `internal`（`logDisabled`）；常量与 `TAG` 放 `companion object`（`HookEntry.kt:37` 用 `private companion object`）。
- 无 typealias、无扩展函数滥用、无 data class 单字段包装。

## Code Style

### Formatting

- **Kotlin 官方风格**：`app/gradle.properties:4` `kotlin.code.style=official`。4 空格缩进、无 tab、行宽约 120、尾逗号。
- 链式调用换行时 `.` 置于行首（IDE 默认），例如 `xposed/core/DexKitHelper.kt:107-113`；字符串拼接用 `+` 续行并保持可读断点（`MountEngine.kt:94-95`）。
- 复杂闭包体允许整体缩进 + `return@label` 提前返回，而不是层层 `if`（`SettingsHook.kt:68-85` 的 `runCatching { … ?: return@runCatching … }` 是标准写法）。
- Compose Modifier 链顺序固定 `fillMaxSize → layerBackdrop/graphicsLayer → padding`，条件修饰符用 `.then(if (cond) Modifier.x() else Modifier)`（`ui/MainActivity.kt:131`）。
- **Shell / Python 一律 LF**：`.gitattributes:1` `* text=auto eol=lf`（CRLF 在 bash 3.2 直接报错，`CLAUDE.md:34`）。`tools/dev.sh`、`tools/bin/apktool` 均为 `#!/usr/bin/env bash` + `set -euo pipefail`（`tools/dev.sh:1-5`）；Python 脚本首行编码声明 + 模块 docstring 写清用法与算法（`tools/adapt_new_version.py:1-47`、`tools/rain-decrypt.py:1-17`），并统一 `sys.stdout.reconfigure(encoding='utf-8', errors='replace')` 防中文输出炸管道（`adapt_new_version.py:61`）。

### Linting

- **未检测到** `.editorconfig` / ktlint / detekt / Android Lint baseline（`app/` 与仓库根均无这些文件）。
- 风格实际由三条途径保证，不要指望工具：
  1. **对齐上游**：UI 侧大量"照抄 KernelSU / AndroidLiquidGlass / HC"的实现，改写即脱钩（`FloatingBottomBar.kt:1-3`、`MainActivity.kt:53-59`、`FeaturesScreen.kt:76-84`）。新写 UI 组件先找 KernelSU 对应实现再动手。
  2. **编译 + 单测门禁**：`dev` 分支 CI 跑 `assembleDebug` + `test` 且**不加 `|| true`**（`.github/workflows/ci.yml:41-42`）。
  3. **`CLAUDE.md` §纪律人工评审**（见 §Comments）。
- 新增 lint 工具前先确认它不会与 vendored 移植件冲突——那批文件按上游原样保留。

## Import Organization

- 单一 import 块置于文件顶部，**组间不空行，整体按完整限定名字母序**（IDE 默认，Kotlin 无 java/javax 惯例）：
  `android.*` → `androidx.*` → `io.github.libxposed.*` → `kotlinx.*` → `org.*` → `top.honorhook.*` → `top.yukonga.miuix.*`。
  实例：`xposed/target/exam8/Exam8Hook.kt:3-10`、`ui/MainActivity.kt:3-50`、`ui/screen/FeaturesScreen.kt:3-74`。
- **不分组、不加注释分隔、不用通配符 `*`、不用 `import ... as`**（全库未见别名 import）。
- 同名前缀按字母序自然排列：`top.honorhook.xposed.core.MountEngine` 紧跟 `top.honorhook.xposed.core.MountEngine.DexHint`（`SettingsHook.kt:8-9`）。
- **宿主/反射类型允许写全限定名内联而不 import**，前提是这个名字必须在代码里保持可读（读者要对着它去 dex 里查）：`android.util.WeakHashMap`（`MethodResolver.kt:18`）、`android.content.res.Resources` / `android.view.ViewGroup`（`SettingsHook.kt:70-76`）、`android.app.Activity` 的强转（`ThemeStoreHook.kt:125`）。
- 跨包同名前缀冲突时用限定名内联而非 alias：`top.honorhook.xposed.data.CloudSwitchTable.forced(key)`（`ThemeStoreHook.kt:84`）。

## Error Handling

### 底线：异常绝不允许炸宿主进程

两层强制防线，新挂载点必须两层都套上：

1. **挂载级**：`ExceptionMode.PROTECTIVE` —— `engine.mount` 统一施加（`xposed/core/MountEngine.kt:154`）；自建 hook 的目标侧同样显式写 `.setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)`（`themestore/ThemeStoreHook.kt:65`）。
2. **回调级**：`runCatching { … }.fold/onFailure/onSuccess` 包裹整个 intercept 体，失败只记日志、不重抛（`HookEntry.kt:25-34`、`MountEngine.kt:152-163`、`MountEngine.kt:103-105`）。

### 失败分级 FAILED vs NOT_RESOLVED

分级定义在 `MountEngine.Result`（`xposed/core/MountEngine.kt:11-12`），消费点在 `attemptMount` 的 `when` 分派（`MountEngine.kt:79-85`）：

| 级别 | 触发条件 | 处置 |
|---|---|---|
| `OK` | 解析成功且 `module.hook(...)` 返回 | `okCount++`，进 `installed N/M` 的 N |
| `FAILED` | `module.hook(...)` 抛异常（挂载失败） | **重试无意义** → 记入 `failedIds`，不进重试轮；WARN "hook 挂载失败（不阻断其他 hook）" |
| `NOT_RESOLVED` | 类/方法未就绪、或 `resolveValue` 依赖值未解析 | **随类加载重试** → 追加进 `unresolved` 子列表，`postDelayed` 再来一轮；WARN "hook 未挂载（…，将重试）" |

- 重试调度固定两轮：第 1 轮延 `RETRY_DELAY_MS = 500L`，第 2 轮延 `RETRY_FINAL_DELAY_MS = 2000L`，上限 `MAX_RETRY_ROUNDS = 2`（`MountEngine.kt:206-208`），执行线程是共享后台 `HandlerThread("HonorHook-mount")`（`xposed/core/MountScheduler.kt:7-17`）。**不要在目标里另起 `postDelayed`**。
- 重试耗尽 → unresolved 全部并入 `failedIds` 并打 WARN "部分 hook 最终未挂载（类/方法不存在，已重试 N 轮，放弃）"（`MountEngine.kt:90-98`），最终仍打汇总行。
- `totalCount` 只在首轮快照（`MountEngine.kt:75-77`），否则 M 会随轮次漂移、判据行失去意义。
- 规则原文见 `docs/hook-craft.md:60` 与 `CLAUDE.md:44`。

### DexKit 兜底的 NOT_RESOLVED 与"诚实跳过"

- `resolveTask` 是解析的唯一收口：`resolveValue` 返 null → WARN "hook 未挂载（依赖项未解析，将重试）"；`MethodResolver.resolveMethod` 返 null → WARN "hook 未挂载（类/方法不存在，版本差异，将重试）"；两者都返回 `null = NOT_RESOLVED`（`MountEngine.kt:113-127`）。
- DexKit 轨自身失败一律降级为 null 而不抛：`withBridge` 在 so 加载失败 / `DexKitBridge.create` 失败 / 查询抛错时返回 null，**兜底线失败不炸主流程**（`xposed/core/DexKitHelper.kt:54-64`）。
- **无内容锚点就诚实跳过，绝不猜类名**：默认包混淆类（`className` 不含 `.`，如 `u15` / `o52`）且 `usingStrings` / `paramTypes` / `classStrings` / `classFieldTypes` 全空时直接 `return null` 并 WARN "DexKit 兜底跳过（默认包类且无内容锚点，避免全 APK 误命中）"（`MethodResolver.kt:87-99`）。这条同时写进 `CLAUDE.md:43` 与 `docs/hook-craft.md:59`。
- 兜底轨缓存 null 结果（`missBuckets`），防止版本漂移场景每轮重试都做秒级磁盘扫描；按 ClassLoader 分桶 + 弱键防串味与泄漏（`MethodResolver.kt:12-21`）。

### 宿主侧反射读取失败 → 放行原逻辑

所有 `getField` / `getMethod` / `getIdentifier` 读取都单独 `runCatching`，失败时**恢复原状态并放行**，绝不中断：

- `SettingsHook.kt:68-85`：视图/资源取不到 → `return@runCatching`；失败 WARN "停用按钮启用失败（放行原状态）"。
- `SystemUiHook.kt:88-110`：`slotId` 读不到 → 计数 `unreadable`，一个都没剔掉时用 `AtomicBoolean` 打**一次性** WARN（`SystemUiHook.kt:113-119`）——判据行会照打 `installed 1/1` 却毫无效果，必须留痕才排得动。
- `themestore/CollectorsSource.kt` 的裁剪/修复助手（`pruneSlotPayload:148` / `pruneBottomTabPayload:211` / `fixWallpaperPackagePath:253` / `pruneTabStateFlowBeforeCreateTabs:277` / `fixVipState:344`）：全部 "失败放行原值/原配置" 语义（`CollectorsSource.kt:183,267,322,328,352,365,384`）。

### UI 侧错误处理

- 用户操作永不抛异常：**写操作必须返回成功与否**。`FeatureStore.setEnabled(key, enabled): Boolean` 在框架未连接或写入抛错时返回 false 并 WARN "远端 prefs 下发失败（本地已生效，hook 侧保持默认开）"（`ui/FeatureStore.kt:52-67`），UI 据此显示"LSPosed 未激活"提示卡（`docs/ui-miuix-overview.md:10-12`）。**这是本项目对"假状态"的硬要求**。
- 枚举/常量解析用 `runCatching { … }.getOrDefault(…)`：`AppSettings.paletteStyle` / `specVersion`（`ui/AppSettings.kt:171-176`）。
- Binder 读取失败降级到常量：`FrameworkState.publish` 在取 `apiVersion` 失败时回落到 `XposedService.API_102`（`ui/FrameworkState.kt:55-65`）。
- 日志必须把 throwable 作为第 4 参交给 logger（`module.log(Log.WARN, TAG, msg, t)`），不要 `t.message` 转字符串——框架日志要留堆栈。

## Logging

### 框架与落点

- **单一 TAG `"HonorHook"`**，hook 侧与 UI 侧共用，禁止自造 per-class tag：`MountEngine.TAG`（`MountEngine.kt:205`）、`MethodResolver.kt:10`、`DexKitHelper.kt:14`、`HookEntry.kt:38`、`FeatureStore.kt:20`、`TargetHook.kt:22`、`InstallerConfigTable` 等均用字面量 `"HonorHook"`。
- **hook 侧主通道是 `module.log(level, TAG, msg, throwable?)`**（libxposed API），落到 **LSPosed 框架日志 `/data/adb/lspd/log/modules_*.log`** —— 这是唯一判据来源，**不在 logcat**（`CLAUDE.md:14`、`docs/hook-craft.md:68`）。
- `android.util.Log` 只在**拿不到 module 句柄的基建内部**（`MethodResolver`、`DexKitHelper`）与 **UI 侧进程**使用。

### 必须打的三类行

1. **汇总判据行** —— 始终输出，**不受任何 debug 门控**：
   `"$hookName: installed $okCount/$totalCount"`，失败时追加 `" — 失败: …"`（`MountEngine.kt:129-137`）。
2. **功能生效行（judge）** —— `engine.judge(module, task, JUDGE_PREFIX, detail?)`，每 task 最多 `JUDGE_LOG_PER_TASK = 2` 次（`MountEngine.kt:166-177`），行格式 `"$prefix 生效: ${task.id} — ${task.description}$suffix"`。前缀沿用历史口径便于按前缀 grep。
3. **功能被关的留痕行** —— `logDisabled` 打 INFO "$hookName: 功能已由远端开关关闭（$key），本次注入跳过挂载"（`TargetHook.kt:16-23`）。理由写在该函数 KDoc：没有这行就分不清"用户关掉了功能"与"模块根本没注入"。

### 分级与节流

| 级别 | 用于 | 位置 |
|---|---|---|
| INFO | 生效、版本一致、拦截成功、开关关闭留痕 | `MountEngine.kt:175,194`；`ThemeStoreParts.kt:223,234,264` |
| WARN | 未解析/挂载失败/重试耗尽/**静默 no-op**/宿主版本漂移/直连反射歧义 | `MountEngine.kt:92,116,124,160,195`；`SystemUiHook.kt:115`；`MethodResolver.kt:69` |
| ERROR | DexKit 轨整体不可用、懒加载重试异常 | `DexKitHelper.kt:40,61,75`；`MountEngine.kt:104` |
| DEBUG | 受门控的原值诊断 | `Exam8Hook.kt:223` |

节流三件套，新代码照抄其一：

- 挂载细节：`if (MountEngine.DEBUG_LOGGING)` 门控，常量默认 `false`（`MountEngine.kt:213-214`；用法 `ThemeStoreHook.kt:151-153`、`InstallerHook.kt:401`）。
- 高频命中：`engine.judge` 的 per-task 计数上限（`MountEngine.kt:172-173`）。
- 一次性告警：`AtomicBoolean` / `ConcurrentHashMap.merge` 计数（`SystemUiHook.kt:84`、`Exam8Hook.kt:216-221` 的 `origLogCount`）。

### 绝不打的

- **不打用户数据**：账号、token、订单号、设备标识、宿主返回体原文。判据行只写 `id` + `description` + 可选的统计 detail（如 `SystemUiHook.kt:106` 的"剔除副卡后 1/2 卡"）。
- **不打凭据与文件内容**：不 dump prefs 内容、不 dump APK 路径以外的本地文件。
- **不在热路径刷屏**：列表滚动、每次订阅变化、每次 `setTitle` 都不打逐次日志；必须打就套 `judge` / `AtomicBoolean` / 计数节流。
- **不打堆栈 spam**：异常一律交 logger 4 参，而不是 `printStackTrace` 或字符串拼接。
- **UI 绘制路径零日志**：`MainActivity.kt` 与 Compose composable 里不出现任何 `Log` 调用。
- 宿主 logger 的 INFO 常不落 logcat，载荷类证据要靠模块侧探针打（`docs/hook-craft.md:68`）。

## Comments

> **这是本仓库第一优先级约定。** `CLAUDE.md:3` 开宗明义："只写代码里读不到的。类名/方法/挂载点/结构读代码或查 `docs/`。**写得简短**。"

### 只写"读不到的东西"

注释与 KDoc 只允许承载四类内容：**逆向实证**（`file:line`、jadx/dexdump 结论、宿主版本基线）、**为什么这么选**（选点理由、替代方案为何否决）、**外部约束**（LSPosed 语义、宿主缓存链、服务端门 vs 客户端门）、**判据**（真机该看到什么日志/什么 UI）。

真实样例（`app/app/src/main/kotlin/top/honorhook/xposed/target/LauncherCardTitleHook.kt:20-25`）：

> 与桌面设置「隐藏应用名称」的区别：官方开关写 SP `launcher_icon_title_hidden`，由 `HiddenTitleManager#isHiddenTitle()` 读，该读点**同时**被图标名路径消费 ⇒ 会连带隐藏图标名。本 hook **不改 SP、不动 HiddenTitleManager**，只把 ITheme 出口置 true ⇒ 仅卡片标题空态。

这二十几行里没有一行能从那 6 行 `mountOne` 实现推出来——这就是注释存在的唯一理由。

### 禁止的注释

- **禁止复述代码**：`// 遍历 list`、`// 初始化 prefs`、`// 判断是否启用`。`FeatureStore.isEnabled` 的 KDoc 只有一行 `/** 读开关（Compose 可观察；默认开）。 */`（`ui/FeatureStore.kt:43`），因为代码已经说完了。
- **禁止写可从 IDE 推出来的东西**：类型签名、参数顺序、返回值结构。
- **禁止长篇进源码**：逆向全流程、方法论、选点对照表一律进 `docs/`，源码只留指针（如 `TargetRegistry.kt:7` 指向 `.claude/skills/lsposed-hook-expert/references/rain-dexkit-and-mount-patterns.md`；`docs/README.md:11-12` 的 craft 索引）。

### 写法细则

1. **每个非平凡声明带 KDoc 写"契约"而非"实现"**：默认值、失败语义、安全默认。`RemoteConfig.kt:31` `/** 读功能开关；默认开（true）。 */`、`AdSlotFilter.kt:31` `/** 该槽位类型是否整体丢弃（null/未命中 → 保留）。 */`。
2. **用 KDoc 链接保持可导航**：`[Result.NOT_RESOLVED]`、`[MountEngine.mount]`、`[engine]`、`[cachedBridge]`（`MountEngine.kt`、`Exam8Hook.kt` 全篇如此）。写 `@param` 只在有默认值/异常语义时写（`MethodResolver.kt:33-40`）。
3. **决策带日期**：看起来任意的决定要写日期 + 理由。`ThemeStoreHook.kt:144` "2026-10-10 真机回归后恢复——getter 短路覆盖不了 null 实例与类内字段直读"；`MethodResolver.kt:87` "★ 2026-10-10 审计修正"；`docs/ui-miuix-overview.md:25` "2026-10-11 实测"。
4. **行内注释写"非显然机制"**：`MountEngine.kt:70-71` "自检延后到后台线程：PackageLoadedParam 时机 Application 尚未创建，直查必拿 null"；`SettingsHook.kt:31` "挂载表 1 项；**以真机 `installed N/M` 的 M 为准。**"
5. **注释是唯一审计痕迹，永不压缩、永不因为重构被删**。`docs/ui-miuix-overview.md:31` 的原话：注释 906 行是"本工程'读代码得不到的信息'的全部载体，压掉就等于把可审计性清零"。
6. **vendored 文件用三行表头声明来源与禁改**（见 §Naming Patterns / Files）。
7. **注释正文用中文，标识符/路径/命令/API 名保持英文原样**（`installed N/M`、`getVipState()`、`/data/adb/lspd/log/`）。

### 纪律一：改前出变更清单，只改清单内

`CLAUDE.md:18`：**不顺手改 bug / 不重构 / 不删死代码**。执行方式：

- 动手前先列出本次要改的文件与函数，范围外的"顺便修一下"一律不做。
- 发现清单外的问题（如另一个挂载点也漂了），**写进结论交给用户判断**，不直接改。代码里留有此类记录：`SystemUiHook.kt:63` 注明"此前本目标手写解析绕开了 DexKit 兜底与失败分级"——说明偏离基建的地方是被显式记录并在清单覆盖时收敛的，不是随手改的。
- 删死代码同样要进清单：删挂载点必须真机 A/B，不能凭静态推理（`docs/hook-craft.md:74`）。

### 纪律二：不为压缩行数重构

`CLAUDE.md:19` + `docs/ui-miuix-overview.md:25-35` 的实测口径：全模块 6624 行 / 41 kt（代码 5130、注释 906、空行 588），hook 侧 2805（注释占 22%）、UI 侧 3735（注释占 8%）。

**三类行不许压**：

1. **注释 906 行**（见上条第 5 点）。
2. **vendored 移植件约 1300 行**：`ui/component/FloatingBottomBar.kt`、`ui/component/liquid/*`、`ui/component/InteractiveHighlight.kt`、`ui/component/DampedDragAnimation.kt`、`ui/component/DragGestureInspector.kt` —— 改写会与上游脱钩、升级时无法逐行比对。
3. **数据行**：43 个挂载点声明、16 条云控表项、13 个功能 key —— "表即真相"，DSL 化更少行但更难核对。

**实测可压空间只有 2~4%（100~200 行）**，且样板抽取（如 5 处 `Scaffold + TopAppBar + insets` 抽 `MiuixPage`）计入 helper 自身后行数中性。**已做的只有真垃圾清除**（单成员 `MountKind` 枚举、嵌套 composable、重复图标）。写新代码时把这条当硬约束：不要为了"看起来更紧凑"动这三类行，也不要启动"顺手清理"。

## Function Design

- **hook 回调只做接线，重逻辑外移**。`when (task.kind)` 的每个分支里调具名 helper（`pruneSlotPayload` / `fixVipState` / `fixWallpaperPackagePath` / `pruneTabStateFlowBeforeCreateTabs` / `pruneBottomTabPayload`，全部定义在 `app/app/src/main/kotlin/top/honorhook/xposed/target/themestore/CollectorsSource.kt:148-344`），而不是把逻辑内联进 intercept 闭包。
- **`install()` 骨架是固定的五步**，新目标照抄（`SettingsHook.kt:23-44`、`SystemUiHook.kt:47-68`、`Exam8Hook.kt:52-66`、`ThemeStoreHook.kt:21-52`）：
  1. `val loader = param.defaultClassLoader`
  2. 远端开关门控：`if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_*)) { logDisabled(...); return }`
  3. `engine.begin(param.applicationInfo?.sourceDir, HOST_VERSION_BASELINE)`
  4. 构建 `pending` 挂载表（按功能分组 `collect*`）
  5. `engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }`
- **挂载模板不重写**：`解析 → PROTECTIVE → 结果分级` 由 `engine.mount` / `engine.attemptMount` 统一负责，目标只提供 intercept 构造（`MountEngine.kt:139-164`）。历史上有目标私造挂载循环，已收敛（`SystemUiHook.kt:63` 的注释即该收敛记录）。
- **按功能分组门控，不按单个挂载点门控**：`if (RemoteConfig.isEnabled(KEY_EXAM8_SUPER_VIP)) { collectVipUserStatusHooks(pending); collectVipPrivilegeHooks(pending) }`（`Exam8Hook.kt:57-63`、`ThemeStoreHook.kt:30-49`）。这样"关一个功能"= 关整组，语义与 UI 开关一一对应。
- **重试时机只从 `MountEngine` 取常量**，目标里不出现裸 `500L` / `2000L`。
- **幂等在注册表层保证，实现内不加 `installed` 标志**（`TargetHook.kt:30`；`TargetRegistry.kt:35-42` 对象级防重装，防别名包共享进程 double mount）。
- **表查询写成可单测的纯函数**：可空入参、表外输入走安全默认（放行/保留/返 null），不引入 Android 依赖（`build.gradle.kts:99` 明确这一点）。
- **Compose**：
  - 每 Tab 一个公开 `XxxScreen(bottomInnerPadding: Dp = 0.dp)`，子页 `private` 且显式传参。
  - 状态提升到 `object` 单例 + `mutableStateOf`（`AppSettings` / `FeatureStore` / `FrameworkState`），composable 只读。
  - **设置类状态不走 `CompositionLocal`**——`MainActivity.kt:83-87` 记录了故障根因（provider 在 `HonorHookTheme` 里，返回二级页时不触发重组），正确做法是直接订阅 `AppSettings.xxx`。

## Module Design

- **一个 APK 两个运行面**：注入侧 `top.honorhook.xposed.*`，宿主 UI 侧 `top.honorhook.ui.*`，共享层只有包根的 `FeatureCatalog`（无框架依赖）。跨面唯一通道是 **remote prefs**（UI 写、hook 侧只读，见 `RemoteConfig.kt:7-15`），语义为**默认全开、写 false 才关、下次目标进程注入才生效**。
- **新增一个目标的完整改动面**（不多不少）：
  1. `xposed/target/<Name>Hook.kt` 实现 `TargetHook`
  2. `TargetRegistry.init` 加一行 `register(<Name>Hook)`（`TargetRegistry.kt:16-25`）
  3. `app/app/src/main/resources/META-INF/xposed/scope.list` 加包名
  4. `FeatureCatalog` 加 `KEY_*` + `Feature`
  5. `tools/dev.sh enable-module` 的强停包名列表同步加一项（`dev.sh:368-372` 注释：漏一个就会出现"部署报成功但该目标 hook 没生效"的假成功）
  - **`HookEntry` 零改动**（`HookEntry.kt:15` 明确"新增目标 = 新建实现 + 注册表加一行"）。
- **依赖方向单向**：`HookEntry → TargetRegistry → TargetHook → MountEngine → MethodResolver / DexKitHelper`；目标之间**互不引用**；`xposed/data/*` 是两侧共用的叶子依赖。
- **`libxposed.api` 是 `compileOnly`**（`app/app/build.gradle.kts:64`，运行时由框架提供）：只在 hook 侧用，UI 侧只用 `libxposed.service`（`:66`）。不要在 `ui/` 里 import `io.github.libxposed.api`。
- **打包契约不可随手改**：`excludes += "**"` + 显式 `merges += "META-INF/xposed/*"` 与 `META-INF/services/*`（`build.gradle.kts:51-59`）。动 proguard/packaging 必须回归 release 包元数据：`unzip -p app-release.apk META-INF/xposed/java_init.list` 仍是全限定名 `top.honorhook.xposed.HookEntry`（`CLAUDE.md:40`）。
- **release = 混淆 + 资源收缩 + debug 签名**，且 `abiFilters += "arm64-v8a"` 只留 arm64（`build.gradle.kts:18-32`）——没有模拟器/32 位验证路径。
- **ProGuard keep 规则是承重墙**，不是可选优化：`app/app/proguard-rules.pro:17-27` 具名 keep `HookEntry` 及其覆写方法，`:10` `-adaptresourcefilecontents META-INF/xposed/java_init.list`，`:30-31` UI 入口，`:34-35` `FeatureCatalog`/`RemoteConfig` 成员。文件内记录了事故原因：R8 曾把 `HookEntry` 改名进默认包（release 产物里 `java_init.list` 变成 `'r10'`），通配 + allowobfuscation 是"锁步脆弱设计"。
- **`tools/dev.sh` 是构建/安装/设备操作的唯一入口**，新增设备操作先加子命令再使用（`CLAUDE.md:11,21`，`tools/dev.sh:2-4`）。子任务只跑定向编译。
- **环境自解析顺序固定**：项目内便携版 → brew / 本机安装 → PATH（`tools/dev.sh:10-52`）；**JDK 21 必须含 `jlink`**（release 打包需要，`dev.sh:238`）；脚本一律 LF；bash 3.2 下 `$VAR` 后紧跟中文必须写 `${VAR}`，否则 unbound variable 退出（`CLAUDE.md:35`）——新增 shell 代码遵守，写不出就改文案。
- **`reference/` 是只读 submodule**，不得修改；`system-files/`、`tools/jdk-21/`、`tools/platform-tools/`、`local.properties` 等本地资产一律 gitignore（`.gitignore:6-29`）。

---

*Convention analysis: 2026-10-11*
