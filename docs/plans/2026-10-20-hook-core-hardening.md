# Hook 核心加固 变更清单（2026-10-20）

> **实施状态（2026-10-20 当日）**：本清单**部分落地**，保留为实施记录 + 未完成项清单。
> - 已落地：Task 2/3/4 的核心（严格轨唯一选择纯函数 `DexHitSelector.pickStrict`、无锚点不猜、弃权留痕）、
>   Task 8 的漂移 WARN 节流与「两挂载点落同一方法」合并计成功（`MountEngine.Result.MERGED`）、
>   Task 9（真机回归 + 文档回写）。
> - **未做**：Task 1（MountSummary 聚合纯函数）、Task 5（MountEngine session 化）、Task 6（FeatureGate 抽象 + 开关下沉 core）、
>   Task 7（挂载表护栏补全）——各自单独立项，勿在本文件里当成已完成。

> **For agentic workers:** REQUIRED SUB-SKILL: 用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans 逐任务执行本清单。步骤用复选框（`- [ ]`）跟踪。
> 本清单只覆盖「hook 核心」三个问题：解析器判定不严、核心机制不可测、引擎两阶段初始化。
> **声明层去重（4 份 MountTask/MountKind）与 XHS 瘦身/外移不在本清单**，见文末「后续独立清单」。

**Goal：** 让 hook 核心在「失效是常态」前提下**只挂对、不猜挂**，且判定逻辑可 JVM 回归。

**Architecture：** 不改目标侧行为语义，只改 core。三步递进：① 把判定（唯一性、聚合、过滤）抽成纯函数并补单测；② 用观测数据确认现状后翻转严格轨策略；③ 把 `MountEngine` 的两阶段初始化（`begin`）改成构造期一次性传入的 session。

**Tech Stack：** Kotlin 2.x / JVM（无 Android 依赖的纯逻辑放 `xposed/core`）/ JUnit4（`app/app/src/test`）/ libxposed API 102。

**Spec：** 本清单即 spec（无独立设计文档）。依据 = 2026-10-20 架构评审结论；原始证据见 `docs/hook-inventory-audit.md`（判据口径）、`docs/review-findings-2026-10-12.md`（#13/#17/#22 格式分裂）、`app/app/src/main/kotlin/top/honorhook/xposed/target/xhs/XhsBatchSave.kt:44-47`（严格轨误绑真机实证）。

---

## 复核增量（2026-10-20 16:xx 代码变更后重排）

负责人当日在未提交工作区里改了大量代码（installer 14.0.38.301 适配 + 严格/宽松双轨解析抽取 + 宿主版本读取重做）。本节按**新代码**重排本清单。

### 复核结论：已被新代码关闭的项（不再执行）

| 原任务 | 关闭方式（证据） |
|---|---|
| Task 2 `selectStrict` | 已被 `DexHitSelector.pickStrict(candidates, expect, hasAnchor, complete)` 取代，且更严：声明类优先 → 无锚点不猜 → 候选集不完整不采纳 |
| Task 3 严格轨观测 | 已被「严格轨候选未采纳…转宽松轨」WARN + 漂移 WARN（`DRIFT_WARN_PER_TASK=1` 节流）覆盖 |
| Task 4 翻转策略 | 已被 `pickStrict` + `DexQueryPlan.strict/loose` 三层宽松轨覆盖；闸门已是「任何类都需锚点」 |
| Task 9 真机回归（installer 部分） | 38.301 真机 16:33 全直连命中，`installed 11/11`（`docs/hook-inventory-audit.md` §五） |

原评审**问题①（严格轨无唯一性判定 / 无锚点仍猜）已关闭**；**问题③关闭一半**：判定逻辑已有 37 个 JVM 单测（`DexQueryPlanTest`）+ 6 例（`XhsPointsTest`），但 `MountEngine` 本体仍零单测。

### 仍然开放的任务（本轮执行顺序 = 下面的编号顺序）

| 任务 | 状态 | 说明 |
|---|---|---|
| Task 1 MountSummary | 待做 | `MountEngine` 聚合抽纯函数（可测性的第一步） |
| Task 5 MountSession | 待做 | 消两阶段 `begin`（23 处调用点） |
| Task 6 FeatureGate | 待做 | 功能关闭 ⇒ 点不挂；M 口径不变 |
| Task 7 护栏补全 | 待做 | launcher/sysmgr/ouc 三张表 + sysmgr/ouc 透传 group/feature |
| Task 8 杂项 | 待做 | VersionName 下沉 / 汇总行带包名 / HookEntry attach 门控 / 注释纠偏 |
| Task 10 xhs 后台探测 | **新增** | N1：xhs 宿主版本读取在宿主主线程做同步 binder + 归档解析 |
| Task 11 统一版本入口 | **新增** | N2：`HostVersion.resolve` 恒 null 仍被 launcher/theme 引用（潜伏陷阱） |
| Task 12 MERGED feature 护栏 | **新增** | N3：合并两点若 feature 不同 ⇒ `installed N` 虚高 |

### 新发现（本轮复核新增）

- **N1（Important）** `XhsBatchSave.install()` / `XhsWatermark.install()` 在宿主主线程（package-loaded 回调线程）直接调 `HostVersion.resolveHostVersion`；其路径① `getPackageArchiveInfo` 是同步 IPC 到 system_server 解析 APK ⇒ 宿主启动期主线程阻塞。installer 已用 `MountScheduler` 延后探测（`{0,200,600}ms`）规避，xhs 未对齐。
- **N2（Important）** `HostVersion.resolve` 走 `ActivityThread.currentPackageManager` —— 该成员本 ROM 不存在（真机 + AOSP14 源码 0 命中）⇒ **恒 null**。`LauncherHook` / `ThemeStoreHook` 仍在用它；两者当前只有默认套（`versions=emptySet`）⇒ 当前无功能影响，**但将来加精确套 = 静默失效**。
- **N3（Medium）** `Result.MERGED` 计为 installed（`isInstalled` 含 MERGED），但「合并的两点同 feature / 同意图」只是巧合不是约束：installer 38.301 的两个 `CommonConfig` 点同 feature 但意图不同（R2 拦截名单 vs R3 安全模式 JSON），靠 `viable` 的返回类型归一（boolean≠String）才隔开。将来两点返回类型相同而 feature 不同 ⇒ N 满而该 feature 实际死掉。
- **N4（Minor）** `XhsBatchSave.kt:44-47` 顶部注释已过时（仍写「pkg 非空即绕过无锚点闸门 + firstOrNull 即采纳…留拍板」；实际锚点已补、`pickStrict` 已上闸、漂移 WARN 已留痕）。
- **N5（Minor）** 直连反射轨多候选仍「取第一个」（候选均满足归一返回类型 + 有 WARN）——风险低，接受。

### Task 10: xhs 宿主版本读取挪出宿主主线程（N1）

**Files:**
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/target/xhs/XhsBatchSave.kt`
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/target/xhs/XhsWatermark.kt`

**Interfaces:**
- Consumes: `xposed.core.MountScheduler`（`postDelayed`，与 installer 同一入口）；`HostVersion.resolveHostVersion(apkPath, packageName)`。
- Produces: 无新公开接口。

- [ ] **Step 1: 先读现状**

  读两个文件的 `install()`：确认「版本读取 → 选覆盖套 → `engine.begin` → `engine.attemptMount`」的当前顺序与线程。**记下 `engine.begin` 当前传的期望版本**（自检用），Step 3 要保持它不变。

- [ ] **Step 2: 写失败测试**

  把「按读到的版本选覆盖套，读不到回落基线」抽成纯函数（放 `XhsPoints.kt` 或同包新文件 `XhsVersionPick.kt`）：
  ```kotlin
  /** 按宿主版本选覆盖套：命中覆盖套用覆盖套；未命中或读不到版本回落基线套。 */
  internal fun pickXhsPoints(actualVersion: String?): XhsPoints
  ```
  测试（`app/src/test/kotlin/top/honorhook/xposed/target/xhs/XhsPointsTest.kt` 追加）：
  - `unknown version falls back to baseline`：`pickXhsPoints(null)` ⇒ 基线套；`pickXhsPoints("9.49.1")`（覆盖套版本）⇒ 覆盖套；`pickXhsPoints("9.50.0")`（未适配）⇒ 基线套。
  - Run: `tools/dev.sh exec ./app/gradlew -p app :app:testDebugUnitTest --tests 'top.honorhook.xposed.target.xhs.XhsPointsTest'`
  - Expected: FAIL（`Unresolved reference: pickXhsPoints`）。

- [ ] **Step 3: 实现纯函数 + 挪到后台线程**

  1. 实现 `pickXhsPoints`（纯函数，无 Android 依赖）并让两个 target 复用它。
  2. 两个 `install()` 改为：主线程只准备 `pending` 列表，**版本读取 + `engine.begin` + `engine.attemptMount` 全部放进同一个后台任务**（`MountScheduler.postDelayed(0) { ... }`），保证 `begin` 与 `attemptMount` 在同一线程 ⇒ 不引入新的跨线程状态竞态。
  3. `engine.begin` 的期望版本参数：**读到版本就用它，读不到用基线**（与 installer 的盲挂兜底一致），并在读不到时打 WARN（口径照 installer：「宿主版本读取失败…按基线挂」）。
  4. 注释写清「为什么挪」：版本读取路径① 是同步 IPC 到 system_server 解析 APK，放在宿主主线程会阻塞小红书启动。

- [ ] **Step 4: 跑测试 + 构建**

  Run: `tools/dev.sh test` → PASS；`tools/dev.sh build` → 成功。

- [ ] **Step 5: 提交**

  ```bash
  git add app/app/src/main/kotlin/top/honorhook/xposed/target/xhs/ \
          app/app/src/test/kotlin/top/honorhook/xposed/target/xhs/XhsPointsTest.kt
  git commit -m "fix(xhs): 宿主版本读取挪出主线程（避免同步 IPC 阻塞宿主启动）"
  ```

- [ ] **Step 6: 交给用户真机验证（AI 不执行）**

  验证清单见文末「Task 9 合并后的真机验证清单」。

---

### Task 11: 统一宿主版本读取入口（N2）

**Files:**
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/HostVersion.kt`
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/target/LauncherHook.kt`
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/target/ThemeStoreHook.kt`

**Interfaces:**
- Consumes: 现有 `HostVersion.resolveHostVersion(apkPath, packageName)`、`HostVersion.pick(tables, actual, fallback)`。
- Produces: `HostVersion.resolve(apkPath)` **被删除**（恒 null 的死 API）。

- [ ] **Step 1: 写失败测试**

  `app/src/test/kotlin/top/honorhook/xposed/core/HostVersionTest.kt` 追加：
  - `pick with null actual version returns default table`：`pick(tables, null)` ⇒ 默认套（构造 tables：一张精确套 + 一张默认套）。
  - `pick with unmatched actual version returns default table`：`pick(tables, "9.9.9")` ⇒ 默认套。
  - Run: `tools/dev.sh exec ./app/gradlew -p app :app:testDebugUnitTest --tests 'top.honorhook.xposed.core.HostVersionTest'`
  - Expected: 若行为已成立则直接 PASS（此时 Step 2 仍必做——这是**契约钉住**，不是驱动开发的红）。若 FAIL，按实际行为补齐。

- [ ] **Step 2: 删除恒 null 的 `HostVersion.resolve`**

  删掉 `resolve(apkPath)`；`LauncherHook` / `ThemeStoreHook` 改调 `resolveHostVersion(apkPath, packageName)`。
  注释写清「为什么删」：`ActivityThread.currentPackageManager` 在本 ROM 不存在 ⇒ 恒 null ⇒ 将来一旦依赖它选表会**静默失效**（不报错、不挂错、只是永远走默认套）。

- [ ] **Step 3: 全局搜残留**

  Run: `grep -rn "HostVersion.resolve(" app/app/src/main/kotlin/`
  Expected: 无输出（或只剩 `resolveHostVersion` 的调用点，确认不是漏网的 `resolve(`）。

- [ ] **Step 4: 跑测试 + 构建 + 提交**

  Run: `tools/dev.sh test` → PASS；`tools/dev.sh build` → 成功。
  ```bash
  git add app/app/src/main/kotlin/top/honorhook/xposed/core/HostVersion.kt \
          app/app/src/main/kotlin/top/honorhook/xposed/target/LauncherHook.kt \
          app/app/src/main/kotlin/top/honorhook/xposed/target/ThemeStoreHook.kt \
          app/app/src/test/kotlin/top/honorhook/xposed/core/HostVersionTest.kt
  git commit -m "refactor(core): 删除恒 null 的 HostVersion.resolve，统一走 resolveHostVersion"
  ```

---

### Task 12: MERGED 计成功的 feature 等价性护栏（N3）

**Files:**
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt`
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/MethodResolver.kt`（合并占位携带 feature）

**Interfaces:**
- Consumes: Task 6 产出的 `MountEngine.Task.feature`（默认 `""`）。
- Produces: 合并两点的 feature 不一致时**不计入 installed**，并打 WARN。

- [ ] **Step 1: 写失败测试**

  在 `core/MountSummaryTest.kt`（Task 1 产出）追加：
  - `merged outcome with different feature is not counted as installed`：首轮 `begin(2, mapOf("A" to 2))`，`record(MountOutcome(id="x", group="A", feature="f1", result=MERGED))` + `record(MountOutcome(id="y", group="A", feature="f2", result=MERGED))` ⇒ `ok() == 1`（只有 feature 相同的那次合并计成功），且存在一条 WARN 级日志说明 feature 不一致。
  - `merged outcome with same feature is counted as installed`：两点同 feature ⇒ `ok() == 2`。
  - Run: `tools/dev.sh exec ./app/gradlew -p app :app:testDebugUnitTest --tests 'top.honorhook.xposed.core.MountSummaryTest'`
  - Expected: FAIL（新断言不成立）。

- [ ] **Step 2: 实现**

  1. `MountOutcome` 增加 `feature: String = ""` 字段。
  2. `MountSummary` 记录「每个方法槽位已被哪个 feature 占用」；`record` 收到 `MERGED` 且该槽位已有 feature、且与本次 feature 不同 ⇒ 不计 ok，并产出 WARN 行（`MountLogLevel.WARN`）。
  3. `MountEngine.mount()` 在 `Result.MERGED` 分支带上 task 的 feature；`MethodResolver` 的宽松轨占位记录 owner 的 feature（当前占位只存了 owner 标识，需扩成 owner + feature）。

- [ ] **Step 3: 跑测试 + 构建 + 提交**

  Run: `tools/dev.sh test` → PASS；`tools/dev.sh build` → 成功。
  ```bash
  git add app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt \
          app/app/src/main/kotlin/top/honorhook/xposed/core/MethodResolver.kt \
          app/app/src/main/kotlin/top/honorhook/xposed/core/MountOutcome.kt \
          app/app/src/test/kotlin/top/honorhook/xposed/core/MountSummaryTest.kt
  git commit -m "fix(core): 合并挂载点 feature 不一致时不再计入 installed（判据 N 不虚高）"
  ```

---

## Global Constraints

以下为全局约束，每个任务都隐含适用（逐字来自 `AGENTS.md` / `CLAUDE.md`）：

- **一切走 `tools/dev.sh`**：禁手拼 `gradle` / `adb` / `java -jar apktool` / `jadx`。
  单测全量：`tools/dev.sh test`；定向单测：`tools/dev.sh exec ./app/gradlew -p app :app:testDebugUnitTest --tests 'top.honorhook.xposed.core.XxxTest'`；构建：`tools/dev.sh build`。
- **只改本清单内的文件**；不顺手修 bug、不重构、不删死代码（死点/冗余点是刻意设计，见 `docs/hook-craft.md` §一）。
- **注释是唯一的审计痕迹，不许为压缩行数删改**；注释只写代码读不到的（逆向实证、为什么这么写、外部约束、判据）。
- **hook 回调里任何异常必须吞掉并记日志**，绝不能炸宿主进程。
- **挂载日志按 `task.id` 节流**（`JUDGE_LOG_PER_TASK` / `CALLBACK_WARN_PER_TASK` 口径不变）。
- **判据只有一条**：`installed N/M`（LSPosed 框架日志，不在 logcat）。`M` = 首轮 pending 数，**重试轮不得覆盖**。
- **失败分级不变**：`FAILED` = 挂载抛异常（重试无意义）；`NOT_RESOLVED` = 类/依赖未就绪（随类加载重试）。
- **DexKit 兜底必须有内容锚点**（混淆不动的字符串常量 / 参数类型序列）；**没有锚点就诚实跳过返回 `NOT_RESOLVED`，绝不猜类名硬兜**。
- **`FeatureCatalog` 是功能 key 的唯一事实源**；任何一侧禁止手写 key 字符串。
- **禁 reboot**；**不在用户主力机上实测未验证的破坏性 hook**。AI 交付 = 代码 + 构建通过 + **用户自执行的验证清单**。
- **真机验证由用户执行**（本清单只产出「验证清单」，不代跑）；AI 不改设备状态。

---

## 前置：基线提交（执行本清单前必做）

当前工作区**有未提交改动**（2026-10-20 的 installer 14.0.38.301 适配 + 严格/宽松双轨解析抽取 + 宿主版本读取重做 + 对应护栏/文档）：

- 已改未提交：`AGENTS.md`、`CLAUDE.md`、`README.md`、`app/app/proguard-rules.pro`、`FeatureCatalog.kt`、`xposed/core/{DexKitHelper,HostVersion,MethodResolver,MountEngine}.kt`、`xposed/target/InstallerHook.kt`、`xposed/target/wallpaper/WallpaperTranscodeHook.kt`、`xposed/target/xhs/{XhsBatchSave,XhsPoints,XhsWatermark}.kt`、`app/src/test/.../HookPointTablesTest.kt`、`docs/*` 若干；
- 新增未跟踪：`xposed/core/DexHitSelector.kt`、`xposed/core/DexQueryPlan.kt`、`xposed/target/InstallerMap_14_0_38_301.kt`、`app/src/test/.../core/DexQueryPlanTest.kt`、`app/src/test/.../target/xhs/XhsPointsTest.kt`、`docs/plans/`。

本清单的 Task 6/7/12 会 `git add` `DexHitSelector.kt` / `DexQueryPlan.kt` / `InstallerMap_14_0_38_301.kt`——若不先落基线，这批既有改动会被裹进本清单的提交，评审与回滚都会失真。

- [ ] **Step 0: 先把既有改动单独提交（基线）**

  ```bash
  tools/dev.sh test          # 先确认基线是绿的
  git add -A
  git commit -m "feat(installer): 14.0.38.301 适配表 + 严格/宽松双轨解析抽取（基线）"
  ```
  提交粒度由负责人自行决定（也可拆分多次）；**唯一硬要求是本清单 Task 1 开工前工作区干净**。

---

## Review Focus

评审与实现时最可能咬人的五类输入/失效（每条都对应下面某个任务的测试）：

1. **宿主升级后类名漂移 + 包内存在多个同名同签名方法** → 严格轨必须**不采纳**（挂错的 `CONST`/`BLOCK` 会直接改坏宿主行为，用户侧表现为"别的功能坏了"）。
2. **用户关闭某功能** → 该功能的所有挂载点必须都不挂，且 `installed N/M` 的 **M 口径不变**（关功能不改变判据行的含义）。
3. **重试轮** → M 不变、N 累计；两轮后放弃时失败清单包含该点（判据行是唯一验收口径，漂移即验收失效）。
4. **同一方法被两个挂载点宽松命中** → 只挂第一次，后来者跳过（既有 `Duplicate` 保护，不得被新逻辑破坏）。
5. **libdexkit.so 加载失败 / 无锚点** → 直连反射轨仍工作、模块不崩、无锚点时**不进 DexKit 轨**（兜底不可用 ≠ 猜一个方法）。

---

## Task 1: 挂载结果聚合抽成纯函数（MountSummary）

**Files:**
- Create: `app/app/src/main/kotlin/top/honorhook/xposed/core/MountOutcome.kt`
- Create: `app/app/src/test/kotlin/top/honorhook/xposed/core/MountSummaryTest.kt`
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt`（`attemptMount` / `logSummary` 改用 MountSummary）

**Interfaces:**
- Consumes: 现有 `MountEngine.Result`（`OK` / `FAILED` / `NOT_RESOLVED`）、`MountEngine.Task.group`。
- 前置：「前置：基线提交」Step 0 已完成（工作区干净）。
- Produces（后续任务依赖）：
  ```kotlin
  // core/MountOutcome.kt
  internal data class MountOutcome(
      val id: String,
      val group: String,
      val result: MountEngine.Result,
  )

  internal enum class MountLogLevel { INFO, WARN, ERROR }

  internal data class MountLogLine(val level: MountLogLevel, val text: String)

  internal class MountSummary {
      fun begin(total: Int, groups: Map<String, Int>)   // 仅首轮调用；重试轮不覆盖 total
      fun record(outcome: MountOutcome)
      fun total(): Int
      fun ok(): Int
      fun failedIds(): List<String>
      fun groupLines(): List<String>      // 形如 "A1: 2/3"，顺序 = group 首次出现序
      fun deadGroups(): List<String>      // ok == 0 的 group
      fun logLines(hookName: String, hostPart: String = ""): List<MountLogLine>
  }
  ```
  `logLines` 必须产出与现状**逐字一致**的三类行：
  1. `"$hookName: installed $ok/$total$hostPart$failurePart"`（无失败 ⇒ INFO，有失败 ⇒ WARN）
  2. `"$hookName 通路: " + group 段`（INFO）
  3. 每个全灭 group 一行 `"$hookName 通路 $g 全灭 0/$total — 失败: ..."`（ERROR）

- [ ] **Step 1: 写失败测试**（`core/MountSummaryTest.kt`）

  用例与断言（名称即测试名，Kotlin 反引号命名，照 `DexQueryPlanTest` 风格）：
  - `total is set once by the first round`：`begin(3, mapOf("A1" to 2))` 后 `total() == 3`；再 `begin(1, emptyMap())` 不应改变 `total()`（重试轮不覆盖 M）。
  - `ok accumulates across rounds`：`record(OK)` 一次 → `ok() == 1`；`record(NOT_RESOLVED)` 不增加 ok。
  - `failed ids are collected in order`：两次 `record(FAILED, id="x")` / `record(FAILED, id="y")` → `failedIds() == listOf("x", "y")`。
  - `group lines show survival ratio`：`begin(3, mapOf("A1" to 2, "A2" to 1))` + 两条 A1 记 OK → `groupLines() == listOf("A1: 2/2", "A2: 0/1")`。
  - `dead groups are those with zero ok`：`deadGroups()` 只含 `"A2"`。
  - `summary lines keep the installed N over M wording`：`logLines("ThemeStoreHook")` 首行文本以 `"ThemeStoreHook: installed 2/3"` 开头（hostPart 为空、failedIds 为空时）。
  - `dead group produces an error line`：`logLines(...)` 中存在文本含 `"通路 A2 全灭 0/1"` 且 `level == MountLogLevel.ERROR` 的行。

- [ ] **Step 2: 跑测试确认失败**

  Run: `tools/dev.sh exec ./app/gradlew -p app :app:testDebugUnitTest --tests 'top.honorhook.xposed.core.MountSummaryTest'`
  Expected: FAIL —— 编译错误 `Unresolved reference: MountSummary`（类还不存在）。

- [ ] **Step 3: 实现 `core/MountOutcome.kt`**

  四个类型按 Interfaces 块的签名落地。`MountSummary` 内部用 `LinkedHashMap<String, Int>` 记 group 总数/成功数、`LinkedHashMap<String, MutableList<String>>` 记 group 失败明细、`MutableList<String>` 记失败 id（保持插入序）。
  `logLines` 的 `failurePart` 口径照现状：`failedIds` 非空时追加 `" — 失败: " + failedIds.joinToString()`。
  注释只写"为什么"：M 只在首轮记（重试轮传入的是 unresolved 子列表，逐轮覆盖会让判据行变成"累计成功/本轮待挂"）、group 首次出现序即声明序。

- [ ] **Step 4: 跑测试确认通过**

  Run: 同 Step 2。Expected: PASS（7 个用例）。

- [ ] **Step 5: 接入 `MountEngine`（行为零变化）**

  改 `MountEngine.attemptMount`：删 `totalCount` / `okCount` / `groupTotal` / `groupOk` / `groupFailed` 五个字段，改用 `MountSummary`：
  - 首轮（`retryRounds == 0`）调 `summary.begin(pending.size, pending.groupCounts())`；
  - 每次 `mountOne` 返回后 `summary.record(MountOutcome(task.id, task.group, result))`；
  - `Result.FAILED` 分支仍收集失败明细（`MountSummary` 内部按 group 归集），`NOT_RESOLVED` 分支仍只进 unresolved 列表。
  改 `MountEngine.logSummary`：`module.log(...)` 改为遍历 `summary.logLines(hookName, hostPart)`，按 `MountLogLevel` 映射到 `Log.INFO/WARN/ERROR`。
  **不要**改任何日志文案、字段顺序、节流常量。

- [ ] **Step 6: 全量单测 + 构建**

  Run: `tools/dev.sh test` → Expected: 全部 PASS（现有 8 套件 + 新增 1）。
  Run: `tools/dev.sh build` → Expected: 构建成功。

- [ ] **Step 7: 提交**

  ```bash
  git add app/app/src/main/kotlin/top/honorhook/xposed/core/MountOutcome.kt \
          app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt \
          app/app/src/test/kotlin/top/honorhook/xposed/core/MountSummaryTest.kt
  git commit -m "refactor(core): 挂载结果聚合抽成纯函数 MountSummary（判定口径不变）"
  ```

---

## Task 2: 严格轨唯一选择抽成纯函数（selectStrict）

> ✅ **已关闭（2026-10-20 复核）**：新代码已实现 `DexHitSelector.pickStrict`（更严：声明类优先 → 无锚点不猜 → 候选集不完整不采纳），并有 37 个 JVM 单测覆盖。本任务**不再执行**。

**Files:**
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/DexHitSelector.kt`
- Create: `app/app/src/test/kotlin/top/honorhook/xposed/core/DexHitSelectorTest.kt`

**Interfaces:**
- Consumes: 现有 `DexHitSelector.Expectation(className, methodName, returnType, paramCount, paramTypes)`、`DexHitSelector.isHookable`。
- Produces:
  ```kotlin
  // DexHitSelector 内新增
  /**
   * 严格轨候选选择：**唯一命中才采纳**；多命中时优先「声明类名 == 期望类名」的那一个；
   * 仍不唯一 ⇒ 返回 null（歧义不猜）。
   * 严格轨的查询条件已钉死方法名/签名，候选之间的差别主要在所属类，故只用类名做判别。
   */
  fun selectStrict(expect: Expectation, candidates: List<Method>): Method?
  ```
  本任务**只加函数不接线**（Task 3/4 才改 `MethodResolver`）。

- [ ] **Step 1: 写失败测试**（`core/DexHitSelectorTest.kt`）

  造候选方法用 `java.lang.reflect.Method`（`String::class.java.getDeclaredMethod(...)` 之类即可），用例：
  - `single candidate is accepted`：1 个候选 → 返回它。
  - `candidate in expected class wins over others`：2 个候选，其中一个 `declaringClass.name == expect.className` → 返回那一个。
  - `ambiguous candidates are rejected`：2 个候选，声明类名都不是期望类名 → 返回 `null`。
  - `abstract candidates are filtered`：abstract 方法不在候选里 → 视为空 → `null`（用 `DexHitSelector.isHookable` 同一判据）。

- [ ] **Step 2: 跑测试确认失败**

  Run: `tools/dev.sh exec ./app/gradlew -p app :app:testDebugUnitTest --tests 'top.honorhook.xposed.core.DexHitSelectorTest'`
  Expected: FAIL —— `Unresolved reference: selectStrict`。

- [ ] **Step 3: 实现 `selectStrict`**

  放在 `DexHitSelector` 内（`internal object`，与 `selectLoose` 同级）：
  1. `candidates.filter { isHookable(it) }`；
  2. 空 → `null`；单条 → 该条；
  3. 多条 → `filter { it.declaringClass.name == expect.className }`；恰好一条 → 该条；否则 `null`。
  注释写清"为什么不用 `selectLoose` 的打分"：严格轨候选已由查询条件钉死方法名/签名，类名是唯一剩余判别维度；打分会把"名字恰好相同"当成加分项，与严格轨的语义重叠。

- [ ] **Step 4: 跑测试确认通过**

  Run: 同 Step 2。Expected: PASS（4 个用例）。

- [ ] **Step 5: 提交**

  ```bash
  git add app/app/src/main/kotlin/top/honorhook/xposed/core/DexHitSelector.kt \
          app/app/src/test/kotlin/top/honorhook/xposed/core/DexHitSelectorTest.kt
  git commit -m "feat(core): 严格轨候选唯一选择 selectStrict（歧义不猜）"
  ```

---

## Task 3: 严格轨观测落地（不改采纳行为）

> ✅ **已关闭（2026-10-20 复核）**：新代码已打「严格轨候选未采纳（多候选且声明类不符 / 无内容锚点不猜），转宽松轨」WARN，并加了漂移 WARN（按 `task.id` 节流）。本任务**不再执行**。

**Files:**
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/MethodResolver.kt`

**Interfaces:**
- Consumes: Task 2 的 `DexHitSelector.selectStrict`（本任务**不用**它，只用候选数做日志）。
- Produces: 无新公开接口；只新增三类日志行（判读口径见下）。

- [ ] **Step 1: 在严格轨加观测日志**

  `MethodResolver.resolveMethod` 的严格轨分支（约 132-151 行）当前是 `... .orEmpty().firstOrNull()`。改为先取候选列表 `hits`，再打日志，**采纳逻辑仍取 `firstOrNull()`（行为不变）**：
  - `hits.size > 1` → `Log.w(TAG, "DexKit 严格轨多命中（当前仍取第一个，待翻转策略后改为不采纳）: $className#$methodName → " + hits.joinToString { DexKitHelper.signatureOf(it) })`；
  - 本次严格轨查询**未带任何锚点**（`usingStrings` / `paramTypes` / `declaredClassStrings` / `declaredClassFieldTypes` 全空）→ `Log.w(TAG, "DexKit 严格轨无锚点猜测命中（名称+签名猜测，宿主升级后可能挂错方法）: $className#$methodName → " + DexKitHelper.signatureOf(hit))`。
  两类都是 WARN（宿主 logger 的 INFO 常不落 logcat，见 `docs/hook-craft.md` §五）。

- [ ] **Step 2: 记录观测口径到文档**

  在 `docs/hook-inventory-audit.md` §五 判据快照表下方加一行说明：
  「2026-10-20 起严格轨会打两类 WARN：多命中 / 无锚点猜测。Task 4 翻转策略后这两类将变成"未挂载"，届时按本表对比 N 的变化。」

- [ ] **Step 3: 构建 + 全量单测**

  Run: `tools/dev.sh test` → PASS；`tools/dev.sh build` → 成功。

- [ ] **Step 4: 提交**

  ```bash
  git add app/app/src/main/kotlin/top/honorhook/xposed/core/MethodResolver.kt \
          docs/hook-inventory-audit.md
  git commit -m "feat(core): 严格轨加多命中/无锚点猜测观测（采纳行为不变）"
  ```

- [ ] **Step 5: 交给用户跑一次真机观测（AI 不执行）**

  交付给用户的验证清单（写进本任务交付说明，不代跑）：
  ```bash
  tools/dev.sh install          # 装 debug 包（会先卸载旧包）
  tools/dev.sh enable-module    # 覆盖安装后 LSPosed 会停用模块，必须重新启用
  tools/dev.sh modlog-grep 'DexKit 严格轨'   # 抓两类 WARN
  ```
  期望产出：**哪些挂载点在靠"无锚点猜测"挂上**、哪些是"多命中取第一个"。这份数据是 Task 4 翻转策略的影响面评估依据。

---

## Task 4: 翻转严格轨策略（唯一性 + 无锚点跳过）

> ✅ **已关闭（2026-10-20 复核）**：新代码已用 `pickStrict` 翻转（唯一命中才采纳）+ 闸门统一为「任何类都需锚点」+ `DexQueryPlan.strict/loose` 三层宽松轨。本任务**不再执行**。

**Files:**
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/MethodResolver.kt`
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/DexHitSelector.kt`（`selectStrict` 正式接线，仅此一处）

**Interfaces:**
- Consumes: Task 2 的 `selectStrict`。
- Produces: 严格轨的采纳契约（写进 `docs/hook-craft.md` §四）。

- [ ] **Step 1: 翻转采纳逻辑**

  `MethodResolver.resolveMethod` 严格轨分支：
  1. 采纳改为 `DexHitSelector.selectStrict(expect, hits)`；`null` → 走既有的"未命中 → 宽松轨"路径（`hits` 为空才进宽松轨；多命中不唯一时**不进**宽松轨，直接 `missSet += cacheKey` 返回 `null` + WARN）。
  2. **无锚点闸门统一**：现闸门是 `if (pkg == null && !hasAnchor) return null`（仅默认包类生效）。改为 `if (!hasAnchor) { WARN + return null }`（**任何类**都跳过 DexKit 轨）。
     口径说明（写进注释）：`hasAnchor` = `usingStrings` / `paramTypes` / `declaredClassStrings` / `declaredClassFieldTypes` 任一非空 —— 其中 `paramTypes` 是参数类型序列（混淆不动），按 `AGENTS.md`「DexKit 兜底必须有内容锚点（混淆不动的字符串常量 / 参数类型序列）」属合法锚点。

- [ ] **Step 2: 确认直连反射轨不受影响**

  严格轨之前有直连反射轨（不缓存，赌类稍后加载），本次**不动**直连轨。无锚点的点在直连失败后直接 `NOT_RESOLVED`（不再猜）。

- [ ] **Step 3: 补一条 JVM 单测钉住新契约**

  在 `core/DexQueryPlanTest.kt`（或新建 `core/MethodResolverPolicyTest.kt`）加纯逻辑断言（不依赖 DexKit）：
  - `no anchor means no dexkit track`：给定"无锚点"输入，纯判定函数返回"不进 DexKit 轨"。
  - `anchor present means dexkit track allowed`：给定 `paramTypes` 锚点 → 允许进轨。
  为此把闸门抽成可测的纯函数（放 `core/DexQueryPlan.kt`）：
  ```kotlin
  /** 是否允许进 DexKit 兜底轨：无任何锚点 ⇒ 不进（诚实跳过，绝不猜类名硬兜）。 */
  fun allowsDexKitTrack(
      usingStrings: List<String>?,
      paramTypes: List<String>?,
      declaredClassStrings: List<String>?,
      declaredClassFieldTypes: List<String>?,
  ): Boolean
  ```
  `MethodResolver` 改为调用它（唯一调用点）。

- [ ] **Step 4: 跑测试 + 构建**

  Run: `tools/dev.sh test` → PASS；`tools/dev.sh build` → 成功。

- [ ] **Step 5: 提交**

  ```bash
  git add app/app/src/main/kotlin/top/honorhook/xposed/core/MethodResolver.kt \
          app/app/src/main/kotlin/top/honorhook/xposed/core/DexQueryPlan.kt \
          app/app/src/main/kotlin/top/honorhook/xposed/core/DexHitSelector.kt \
          app/app/src/test/kotlin/top/honorhook/xposed/core/
  git commit -m "fix(core): 严格轨只在唯一命中时采纳、无锚点不猜（对齐无锚点不猜纪律）"
  ```

- [ ] **Step 6: 真机对比（用户执行）+ 判据快照更新**

  交付验证清单：同 Task 3 Step 5；本任务额外要求对比 `installed N/M` 与 `docs/hook-inventory-audit.md` §五 快照。
  **若某目标 N 下降**：说明该点原先靠"猜"挂上 → 正确处置是给该挂载点补**内容锚点**（`dexStrings` / `dexParamTypes` / `dexClassStrings`），**不是**放宽闸门。补锚点后重测。
  判据快照更新写回 `docs/hook-inventory-audit.md` §五（改完后回来更新本表，AGENTS.md 要求）。

---

## Task 5: MountEngine session 化（消除两阶段初始化）

**Files:**
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt`
- Modify（23 个目标文件，见下表）:
  `xposed/target/mihealth/MiHealthHook.kt`、`xposed/target/StatusBarIconFeature.kt`、`xposed/target/xhs/XhsWatermark.kt`、`xposed/target/SysmgrHook.kt`、`xposed/target/UpgradeGuideHook.kt`、`xposed/target/InstallerHook.kt`、`xposed/target/jd/JdHook.kt`、`xposed/target/xhs/XhsHook.kt`、`xposed/target/DualSignalFeature.kt`、`xposed/target/tracking/HonorTrackingHook.kt`、`xposed/target/xhs/XhsVideoSave.kt`、`xposed/target/ime/ImeHook.kt`、`xposed/target/LauncherHook.kt`、`xposed/target/exam8/Exam8Hook.kt`、`xposed/target/mms/MmsHook.kt`、`xposed/target/xhs/XhsBatchSave.kt`、`xposed/target/SettingsHook.kt`、`xposed/target/SystemUiHook.kt`、`xposed/target/ads/HonorAdsHook.kt`、`xposed/target/mfauth/MfaAuthHook.kt`、`xposed/target/OucHook.kt`、`xposed/target/ads/HonorOpsAdHook.kt`、`xposed/target/themestore/ThemeStoreHook.kt`
  （路径前缀均为 `app/app/src/main/kotlin/top/honorhook/`）

**Interfaces:**
- Consumes: 现有 `MountEngine.Task` / `SimpleTask` / `DexHint` / `Result` / `TAG`（**全部保留原名与位置**，`MountEngine` 由 `class` 改 `object`，嵌套类型访问方式不变）。
- Produces:
  ```kotlin
  // core/MountEngine.kt
  object MountEngine {
      const val TAG = "HonorHook"
      enum class Result { OK, FAILED, NOT_RESOLVED }
      class DexHint(val strings: List<String>?, val paramTypes: List<String>?, val classStrings: List<String>?, val classFieldTypes: List<String>?)
      interface Task { /* 字段不变 */ }
      class SimpleTask(...) : Task   /* 字段不变 */

      /** 打开一次挂载会话：apkPath / 基线版本在构造期一次性确定，调用方无法"忘记 begin"。 */
      fun open(
          hookName: String,
          hostPackage: String,
          apkPath: String?,
          expectedHostVersion: String? = null,
      ): MountSession
  }

  class MountSession internal constructor(
      val hookName: String,
      val hostPackage: String,
      private val apkPath: String?,
      private val expectedHostVersion: String?,
  ) {
      fun <T : MountEngine.Task> attemptMount(
          pending: List<T>,
          module: XposedModule,
          loader: ClassLoader,
          mountOne: (T) -> MountEngine.Result,
      )
      fun mount(task: MountEngine.Task, module: XposedModule, loader: ClassLoader, intercept: XposedInterface.Hooker): MountEngine.Result
      fun resolveTask(task: MountEngine.Task, module: XposedModule, loader: ClassLoader): Method?
      fun judge(module: XposedModule, task: MountEngine.Task, prefix: String, detail: String? = null)
      fun logSummary(module: XposedModule)
  }
  ```

- [ ] **Step 1: 改 `MountEngine` 为 `object` + 抽出 `MountSession`**

  把现有 `MountEngine` 的实例字段（`hookName` / `hostPackage` / `apkPath` / `expectedHostVersion` / `hostVersionChecked` / `actualVersion` / 计数与节流 map）与实例方法（`begin` / `attemptMount` / `resolveTask` / `mount` / `judge` / `logSummary` / `checkHostVersionOnce`）整体搬进 `MountSession`；`MountEngine` 留 `TAG` / `Result` / `DexHint` / `Task` / `SimpleTask` + 新增 `open()`。
  **行为零变化**：重试轮数（`MAX_RETRY_ROUNDS=2`）、延迟（500ms / 2000ms）、节流常量、失败分级、判据文案全部照搬。
  `begin()` 的"重置计数"语义由"每进程每目标只 open 一次"天然满足（`TargetRegistry` 的对象级防重装保证）。

- [ ] **Step 2: 迁移 23 个目标文件（机械改动，逐文件同款）**

  每个目标文件：
  1. 删 `private val engine = MountEngine("名字", packageName)`；
  2. `install()` 内改为：
     ```kotlin
     val session = MountEngine.open(
         hookName = "XxxHook",
         hostPackage = packageName,
         apkPath = param.applicationInfo?.sourceDir,
         expectedHostVersion = FeatureCatalog.baselineVersionOf(packageName),
     )
     ```
     （原来 `engine.begin(apkPath, baseline)` 的两个实参原样搬过来；`HonorAdsHook` / `HonorTrackingHook` 原来 `begin` 不传基线 → `expectedHostVersion = null`，保持不变。）
  3. `engine.attemptMount(...)` → `session.attemptMount(...)`；`engine.mount(...)` → `session.mount(...)`；`engine.judge(...)` → `session.judge(...)`；
  4. `mountOne(task, module, loader)` → `mountOne(session, task, module, loader)`（**session 作为第一参数显式传递，不存字段** —— 避免新的可变全局态），函数体里 `engine.` 全量替换为 `session.`。
  5. `XhsHook.install` 会连续调 `XhsWatermark.install` / `XhsVideoSave.install` / `XhsBatchSave.install`（各自开自己的 session）——保持原样，各目标独立 session、独立 `installed N/M`。

- [ ] **Step 3: 编译期校验（编译器兜住全部 23 个调用点）**

  Run: `tools/dev.sh build` → Expected: 成功。旧的 `MountEngine("x", pkg)` 构造调用若漏改会**编译失败**（`object` 不可实例化）——这正是本任务要的"忘记即报错"。

- [ ] **Step 4: 全量单测**

  Run: `tools/dev.sh test` → Expected: PASS（`RegistryConsistencyTest` 会加载 `TargetRegistry` → 触发全部目标类加载，类型签名改动能被它兜住一部分）。

- [ ] **Step 5: 提交**

  ```bash
  git add app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt \
          app/app/src/main/kotlin/top/honorhook/xposed/target/
  git commit -m "refactor(core): MountEngine 改 session 化（apkPath/基线构造期固定，忘 begin 不可再发生）"
  ```

- [ ] **Step 6: 真机对比（用户执行）**

  验证清单：`tools/dev.sh install` → `tools/dev.sh enable-module` → 逐目标看 `installed N/M`，**期望与 `docs/hook-inventory-audit.md` §五 完全一致**（本任务不改任何挂载语义，N/M 不应变）。任一目标 N 变化即为回归，必须定位后再继续。

---

## Task 6: FeatureGate 抽象 + 功能开关下沉到 core

**Files:**
- Create: `app/app/src/main/kotlin/top/honorhook/xposed/data/FeatureGate.kt`
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/data/RemoteConfig.kt`（实现 `FeatureGate`）
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt`（`Task.feature` / `SimpleTask.feature` / session 过滤）
- Modify: `xposed/target/themestore/ThemeStoreHook.kt`、`xposed/target/themestore/ThemeStoreParts.kt`、`xposed/target/InstallerHook.kt`、`xposed/target/LauncherHook.kt`、`xposed/target/SysmgrHook.kt`、`xposed/target/OucHook.kt`（表行 feature 透传 + 删本地 filter）

**Interfaces:**
- Consumes: `FeatureCatalog` 的 `KEY_*`（禁止手写 key 字符串）。
- Produces:
  ```kotlin
  // data/FeatureGate.kt
  /** 功能开关读取接口（hook 侧唯一入口）。 */
  interface FeatureGate {
      fun isEnabled(key: String): Boolean
  }
  // RemoteConfig : FeatureGate —— 现有 isEnabled 实现不变，只加 override
  ```
  ```kotlin
  // core/MountEngine.kt
  interface Task {
      ...
      /** 所属功能开关 key（FeatureCatalog）；空 = 常挂，不受开关控制。 */
      val feature: String get() = ""
  }
  class SimpleTask(..., val feature: String = "")   // 新参数放最后（位置传参安全）
  ```
  ```kotlin
  // core/FilterByFeature.kt（纯函数，可测）
  /** 按功能开关过滤挂载点（空 feature = 常挂，保留）。过滤在 M 统计之前，判据口径不变。 */
  internal fun <T : MountEngine.Task> filterByFeature(pending: List<T>, gate: FeatureGate): List<T>
  ```
  `MountSession.open(..., gate: FeatureGate = RemoteConfig)`；`attemptMount` 首行做 `val effective = filterByFeature(pending, gate)`，**之后所有计数/M 统计用 `effective`**。

- [ ] **Step 1: 写失败测试**（新建 `app/app/src/test/kotlin/top/honorhook/xposed/core/FilterByFeatureTest.kt`）

  - `empty feature is always kept`：feature 为空的行保留。
  - `enabled feature keeps its points`：gate 返回 true → 保留。
  - `disabled feature drops its points`：gate 返回 false → 该行被剔除。
  - `filter preserves order`：剔除后剩余行顺序不变。
  fake gate 用匿名对象实现 `FeatureGate`。

- [ ] **Step 2: 跑测试确认失败**

  Run: `tools/dev.sh exec ./app/gradlew -p app :app:testDebugUnitTest --tests 'top.honorhook.xposed.core.FilterByFeatureTest'`
  Expected: FAIL —— `Unresolved reference: filterByFeature`。

- [ ] **Step 3: 实现 `FeatureGate` / `RemoteConfig` / `filterByFeature` / `Task.feature`**

  按 Interfaces 块落地。`RemoteConfig.isEnabled` 的**默认语义一字不改**（读不到 ⇒ 回 `FeatureCatalog` 目录默认；目录里没有的 key ⇒ `false`）。
  `Task.feature` 用 `get() = ""` 默认实现 → 现有 23 个自定义 `MountTask`（theme/installer/launcher/exam8）**不需要改**即可编译（它们不实现 feature）。

- [ ] **Step 4: 接线 core 过滤**

  `MountSession.attemptMount` 首行过滤；`totalCount`/`groupTotal` 统计用过滤后的列表（**M 口径与今天 theme 的行为一致**：被关功能的行不进 M）。

- [ ] **Step 5: 迁移 5 个表驱动目标**

  - theme / installer / launcher：**删掉**本地 `.filter { it.feature.isEmpty() || RemoteConfig.isEnabled(it.feature) }`（改由 core 统一过滤）；`toMountTask()` 补 `feature = point.feature` 透传（theme 的 `MountTask` / installer 的 `MountTask` / launcher 的 `MountTask` 需加 `override val feature` 字段，位置放最后）。
  - sysmgr / ouc：其 `toTask()` 现在**丢弃** `point.feature`；补上 `feature = point.feature`（两者行级 feature 与其全局 gate 同 key ⇒ 行为等价，M 不变）。

- [ ] **Step 6: 跑测试 + 构建**

  Run: `tools/dev.sh test` → PASS；`tools/dev.sh build` → 成功。

- [ ] **Step 7: 提交**

  ```bash
  git add app/app/src/main/kotlin/top/honorhook/xposed/data/FeatureGate.kt \
          app/app/src/main/kotlin/top/honorhook/xposed/data/RemoteConfig.kt \
          app/app/src/main/kotlin/top/honorhook/xposed/core/ \
          app/app/src/main/kotlin/top/honorhook/xposed/target/
  git commit -m "refactor(core): 功能开关下沉到 core 统一过滤（FeatureGate 抽象，语义不变）"
  ```

- [ ] **Step 8: 真机对比（用户执行）**

  验证清单：装包 → 启用模块 → 逐目标 `installed N/M`（期望与 §五 快照一致）→ **手动关一个开关再确认该功能的点不挂**（判据行 M 应减少且无失败清单）。

---

## Task 7: 挂载表护栏补全

**Files:**
- Modify: `app/app/src/test/kotlin/top/honorhook/xposed/data/HookPointTablesTest.kt`
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/target/LauncherHook.kt`（`LAUNCHER_POINTS_18_0_0_234` 已是顶层 `val`，测试可访问；确认可见性）
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/target/SysmgrHook.kt`、`xposed/target/OucHook.kt`（表由 `private val` 提为 `internal val`，供测试遍历）

**Interfaces:**
- Consumes: 现有 `HookPoint`（`kind` / `feature` / `group` / `returnType` / `value`）。
- Produces: 无新接口；只加测试。

- [ ] **Step 1: 扩表覆盖**

  `HookPointTablesTest` 增加三个表的遍历（与现有 theme / installer 同样的断言三件套：kind 合法、feature ∈ `FeatureCatalog.KEY_*` 全集 ∪ `""`、group 匹配 `^[A-Z][0-9]+[a-z]?$`）：
  - `LAUNCHER_POINTS_18_0_0_234`（launcher，`MountKind` 集合来自 `LauncherHook` 私有枚举 → 需把该枚举提为 `internal` 才能在测试引用）
  - `SYSMGR_POINTS`（sysmgr）
  - `OUC_POINTS`（ouc）
  sysmgr / ouc 的 `kind` 当前被 `toTask()` 丢弃 ⇒ 断言口径改为"kind 非空即可"（不校验取值集合），并在注释里写清"这两张表的 kind 是占位，返回值由 mountOne 按 returnType 分派"。

- [ ] **Step 2: 加 id 唯一性断言**

  三张表各自 `id` 不重复（id 是判据汇总与失败清单的键，重复会让两个点在日志里变一个）。

- [ ] **Step 3: 加 value ↔ returnType 匹配断言（仅限"value 即返回值"的表）**

  适用范围：**theme / installer / launcher**（这三处的 `kind=CONST` 行，value 直接作为方法返回值）。
  sysmgr / ouc **不适用**（它们的 value 是占位，返回值由 `mountOne` 按 `returnType` 分派）——在测试注释里写明原因，避免后人误扩。
  断言：`returnType = "boolean"` ⇒ value ∈ {true, false}；`"int"` / `"long"` ⇒ 整数字面量；`"double"` ⇒ 数值字面量；`"java.lang.String"` ⇒ 字符串字面量；`"void"` ⇒ 允许任意（部分行 value 表达"参数值"而非返回值，如 installer 的 `CHECK_BYPASS_JOB`）→ **void 行跳过断言**。
  实施方式：把该断言抽成测试内的私有函数 `assertValueMatchesReturnType(table, label)`，逐行按 `kind`/`returnType` 分支。

- [ ] **Step 4: 跑测试**

  Run: `tools/dev.sh test` → Expected: PASS。
  若新断言**失败**：说明表里已存在不一致行 —— 处置是**修表**（对齐 value 与 returnType），不是放宽断言。修表时同步 `docs/hook-inventory-audit.md`（该表是判据来源）。

- [ ] **Step 5: 提交**

  ```bash
  git add app/app/src/test/kotlin/top/honorhook/xposed/data/HookPointTablesTest.kt \
          app/app/src/main/kotlin/top/honorhook/xposed/target/
  git commit -m "test: 挂载表护栏覆盖 launcher/sysmgr/ouc + value 与 returnType 匹配"
  ```

---

## Task 8: 杂项四项（低风险清理）

**Files:**
- Create: `app/app/src/main/kotlin/top/honorhook/VersionName.kt`
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/HostVersion.kt`、`app/app/src/main/kotlin/top/honorhook/FeatureCatalog.kt`、`app/app/src/main/kotlin/top/honorhook/ui/update/UpdateChecker.kt`
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt`（汇总行带包名）
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/HookEntry.kt`（attach 门控）
- Modify: `app/app/src/main/kotlin/top/honorhook/xposed/data/HookMap.kt`、`app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt`（注释漂移）

**Interfaces:**
- Produces:
  ```kotlin
  // top/honorhook/VersionName.kt
  /** 版本名比较（数字段逐段比，缺段按 0）：a<b 负 / 相等 0 / a>b 正。 */
  object VersionName {
      fun compare(a: String, b: String): Int
  }
  ```
  `HostVersion.compareVersionNames` 改为委托 `VersionName.compare`（保留旧名做薄转发，避免 `FeatureCatalog` / `UpdateChecker` 之外的调用点全改；`HostVersion` 内部的 fallback 选表继续用 `VersionName.compare`）。

- [ ] **Step 1: 层级倒置修正**

  新建 `VersionName`；`HostVersion.compareVersionNames` → 委托；`FeatureCatalog`（共享目录）与 `ui/update/UpdateChecker` 改 import `top.honorhook.VersionName`（**解除 UI/共享层对 `xposed.core` 的依赖**）。
  注释写清动机：`compareVersionNames` 是纯函数，属于共享层；放在 `xposed.core` 会让 UI 侧反向依赖 hook 包。

- [ ] **Step 2: 汇总行带包名**

  `MountSession.logSummary` 的 `hostPart` 由 `"（宿主 $it）"` 改为 `"（$hostPackage $it）"`（`actualVersion` 为空时 `"（$hostPackage ?）"`）。
  动机：`HonorAdsHook` / `HonorTrackingHook` 按包注册多次，汇总行同名会分不清是哪个宿主包。

- [ ] **Step 3: `HookEntry` attach 门控**

  `HookEntry.onPackageLoaded`：`RemoteConfig.attach(...)` 移到 `TargetRegistry.find(param.packageName)` 非空之后（无目标的包不做 Binder 读）。
  **注意**：attach 必须在任何 `target.install()` 之前（install 内会读 `RemoteConfig`）——顺序保持"先 find → 再 attach → 再 install"。

- [ ] **Step 4: 注释漂移修正**

  - `MountEngine.attemptMount` 的注释"首轮挂载由各 hook 的 install() 在 onPackageLoaded 线程（宿主主线程）同步触发"与实际（首行即 `post` 到 `MountScheduler`）不符 → 改成"首轮一律转投 MountScheduler（宿主主线程零同步磁盘 IO）"。
  - `HookMap` 注释"kind 用字符串而非目标私有枚举，避免抽象层反向依赖某个目标的实现"与实际（target 侧 `MountKind.valueOf` 仍会抛）不符 → 补一句"字符串 kind 仍由目标侧 `valueOf` 解析，字符串打错在目标构建期抛（护栏见 HookPointTablesTest）"。

- [ ] **Step 5: 跑测试 + 构建 + 提交**

  Run: `tools/dev.sh test` → PASS；`tools/dev.sh build` → 成功。
  ```bash
  git add app/app/src/main/kotlin/top/honorhook/
  git commit -m "refactor: 版本名比较下沉共享层 + 汇总行带包名 + attach 门控 + 注释纠偏"
  ```

---

## Task 9: 真机回归 + 文档回写

**Files:**
- Modify: `docs/hook-inventory-audit.md`（§五 判据快照）
- Modify: `docs/hook-craft.md`（§四 补严格轨契约：唯一命中才采纳 / 无锚点不进轨）

**Interfaces:**
- Consumes: Task 1–8 的全部产出。

- [ ] **Step 1: 用户执行真机回归（AI 不跑）**

  本轮（2026-10-20 复核后）**具体验证清单**——逐条按 §五 快照核对：

  ```bash
  tools/dev.sh install          # 装包（会先卸载旧包）
  tools/dev.sh enable-module    # 覆盖安装后 LSPosed 会停用模块，必须重新启用
  tools/dev.sh modlog           # 抓 LSPosed 框架日志（判据行在这里，不在 logcat）
  tools/dev.sh modlog-grep 'installed'   # 只看判据行
  tools/dev.sh hook-test        # 主题商店专项回归
  ```

  | # | 验证项 | 期望 | 判读 |
  |---|---|---|---|
  | 1 | 判据行格式 | `HookName[宿主包名]: installed N/M`（如 `HonorAdsHook[com.hihonor.android.ads]: installed 2/2`） | 格式变更是预期；`installed N/M` 子串不变 |
  | 2 | theme | 49/49 | N<M ⇒ 先查宿主版本，**别改 hook 实现** |
  | 3 | installer | 11/11（宿主 14.0.38.301） | 换宿主版本时 M 变（22.326=8 / 38.301=11） |
  | 4 | launcher / sysmgr / ouc / systemui | 5/5 · 3/3 · 11/11 · 3/3 + U6 3/3 + U7 3/3 | — |
  | 5 | **xhs（重点）** | 9.50.0：X1 3/3 · X2 3/3 · X4 1/1 · X5 7/7；**9.49.1 覆盖套：X5 M=4**（noteSave/aigcEdit/downloader 三点该版本显式缺席，**不是失效**） | 本轮 xhs 首次真正按版本选坐标（9.49.1 覆盖套此前从未生效） |
  | 6 | **关功能**（重点） | 关掉某功能 ⇒ 该功能挂载点不挂、**M 变小**、N/M 同步变化；失败清单不含被关的点 | 本轮 feature 过滤下沉到引擎（口径：过滤在统计 M 之前） |
  | 7 | **MERGED** | 合并到**别的功能**的同名方法 ⇒ 该点进失败清单、不计入 N | 本轮新增 `MERGED_CONFLICT` 口径 |
  | 8 | **线程**（重点） | 宿主启动**无卡顿**（小红书 / 包管理器首启各观察一次） | 本轮 xhs/theme/launcher/installer 的版本读与挂载全挪到 `MountScheduler` 后台线程 |
  | 9 | 版本未适配 | 日志出现「宿主 X 未适配，按基线 Y 挂载表盲挂，判据以 installed N/M 为准」 | 盲挂提示是预期 |
  | 10 | 兜底诚实 | 有漂移风险时看到「严格轨候选未采纳…转宽松轨」/ 漂移 WARN | 猜挂已被闸门堵死，N 下降＝补锚点，**不是**放宽闸门 |

  **判读规则**（写进交付说明）：N 明显小于 M ⇒ 宿主版本错位或选点失效，**先别改 hook 实现**（`docs/hook-craft.md` §六·1）；唯一例外是 §五 已分档的 9.49.1 覆盖套（X5 M=4）。

- [ ] **Step 2: 回写判据快照**

  按实测结果更新 `docs/hook-inventory-audit.md` §五 的"最近实测"列；若有目标因 Task 4 翻转而 N 下降，必须在同表注明"原先靠无锚点猜测挂上，现已补锚点/诚实跳过"。

- [ ] **Step 3: 回写契约**

  `docs/hook-craft.md` §四「三阶段解析」补两句：
  - 严格轨**唯一命中才采纳**（多命中不猜；判别维度 = 声明类名是否等于期望类名）；
  - **无任何锚点（字符串常量 / 参数类型序列 / 类级锚点）时不进 DexKit 轨**，诚实 `NOT_RESOLVED`（`paramTypes` 属合法锚点）。

- [ ] **Step 4: 提交**

  ```bash
  git add docs/
  git commit -m "docs: 回写判据快照与严格轨契约"
  ```

---

## 后续独立清单（本轮不做，各自单独立项）

| 清单 | 范围 | 为什么独立 |
|---|---|---|
| **声明层收敛** | 4 份私有 `MountTask`/`MountKind`（theme 11 kinds / installer 6 / launcher 5 / exam8 2）→ 公共 kind + core handler 表；`HookPoint` 18 字段并集记录 → `Action` sealed 类型；`XhsPoints` 纳入 `HostVersion.Table` 语义 | 触及 theme 49 点与 installer 版本表，真机回归成本高；需单独排期与两轮真机验证 |
| **XHS 瘦身与外移** | 移除未用的 `OkHttpXhsPageSource`（连带删掉 `okhttp` 的 `compileOnly` 依赖）→ vendored 目录加"禁 okhttp 引用"构建期检查；保存链路加硬截止时间 + 超时回落宿主原生保存；中期把下载编排外移到模块 App 进程（Binder） | 属于"宿主进程里跑第二个应用"的风险治理，改动面与判据体系都不同（涉及网络与 MediaStore） |
| **多包共享进程去重** | `TargetRegistry` 对象级去重不覆盖"两个 scope 包共享进程 + 同一 SDK hook 类"（`HonorAdsHook` 按包 new）→ 按 `(hookClass, process)` 去重 | 当前各包独立进程，属潜在问题；等真机出现共享进程再动 |

---

## Self-Review

1. **Spec 覆盖**：评审结论的 6 条问题 → 覆盖情况：
   - ① 严格轨无锚点仍猜 + 无唯一性 → Task 2/3/4 ✔
   - ② `begin()` 两阶段初始化 → Task 5 ✔
   - ③ 核心机制不可测 → Task 1（聚合）+ Task 6（过滤）+ Task 2（唯一选择）✔（`MethodResolver` 的 DexKit 调用本身仍不可测，属框架耦合，**已知未覆盖**）
   - ④ 声明层 4 形态 / 4 份重复 / 护栏覆盖一半 → Task 7 只补护栏，**形态统一进「后续独立清单」** ✔（明确不在本轮）
   - ⑤ XHS 迷你应用 → 「后续独立清单」✔（明确不在本轮）
   - ⑥ 杂项（层级倒置 / 汇总行包名 / attach 门控 / 注释漂移）→ Task 8 ✔
2. **Step scan**：每个 step 只让实现者写一件事；Task 1/2/4/6 有完整 red-green；Task 3/5/7/8/9 是可执行动作 + 明确验证命令。
3. **类型一致性**：`MountSummary` / `MountOutcome` / `MountLogLevel` / `MountLogLine` 只在 Task 1 定义、Task 2/6 引用同名；`MountSession` 只在 Task 5 定义；`FeatureGate` / `filterByFeature` 只在 Task 6 定义；`VersionName` 只在 Task 8 定义。无同名异义。
4. **Review Focus**：5 条分别落在 Task 4（#1）、Task 6（#2）、Task 1（#3）、Task 2/4（#4）、Task 3/4（#5）的测试里。
5. **Proportion**：清单 9 个任务、约 60 个 step，对应一份 3 目标的重构；代码块只出现签名与断言，没有实现体。

---

## 执行台账（2026-10-20，native 执行完毕）

**提交清单**（分支 `dev`，基线 `8693339` → `95e77c3`）：

| 提交 | 任务 | 内容 |
|---|---|---|
| `c35ea7c` | Task 1 | 挂载结果聚合抽成纯函数 `MountSummary`（11 例 JVM 回归，判据口径逐字不变） |
| `846e7b6` | Task 6 | 功能开关过滤下沉到引擎（`FeatureGate` + `filterByFeature`，6 例） |
| `734875e` | Task 12 | 合并到别的功能的挂载点不再计入 installed（`MERGED_CONFLICT`，4 例） |
| `5dc612d` | Task 7 | launcher/sysmgr/ouc 三张表纳入护栏（+7 例） |
| `8ea7cd6` | Task 10 | xhs 宿主版本读取挪出主线程 + 挂载点集合按版本选取可回归（7 例） |
| `b7e6acf` | Task 11 | 删除恒 null 的 `HostVersion.resolve`，统一走 `resolveHostVersion`（+2 例） |
| `8ae3298` | Task 8 | 版本比较下沉共享层 `VersionName` + 判据行带宿主包名 + 无目标包不读远端 prefs（+6 例） |
| `4d1f08e` | Task 5 | `MountEngine` session 化：新增 `MountSession`，23 个目标去掉两阶段 `begin`（+1 例） |
| `1508bcf` | docs | `hook-craft.md` §四 补两条契约；`hook-inventory-audit.md` §五 补判据行格式 |
| `95e77c3` | 复审修复 | 复审 Important 1/2：注释纠偏 + xhs 9.49.1 覆盖套期望 M 分档 |

测试基线：87 → **128 tests / 0 fail / 1 skipped**；`tools/dev.sh build` 全程通过。

### 关键裁决（不在计划正文里、但改变了结果的判断）

| 任务 | 裁决 | 代价 |
|---|---|---|
| 1 | `MountOutcome` 字段用 `(id, description, group, feature, result)`——失败清单打印的是 `task.description` 不是 id | 与计划原案 `(id, group, result)` 不同 |
| 1 | `begin` 用 `began` 布尔标志判首轮（不用 `total != 0`） | 空 pending 首轮会漏记 |
| 1 | 增补 `markUnmounted`（现状 failedIds 同时收 FAILED 与重试耗尽的 unresolved） | 纯函数接口比计划多一个方法 |
| 5 | `MountSession` 独立文件；会话建在**原 `engine.begin(...)` 调用点**（installer/xhs/theme/launcher 的 begin 在后台探测链里） | 建在 install() 开头会改期望版本语义 |
| 5 | 30+ 个辅助函数显式首参 `session: MountSession`、不存字段 | 调用点多一行参数（编译器兜住漏改） |
| 6 | `FeatureGate` 放 `xposed/core/`（不是计划的 `data/`）：消费方是 core；`RemoteConfig` 实现它 ⇒ 依赖方向 data→core | 与计划路径不同 |
| 8 | 版本比较实现整体搬到 `top.honorhook.VersionName`，`HostVersion` 不留同名入口 | `HostVersionTest` 的 4 条用例随之迁到 `VersionNameTest` |
| 8 | 判据行包名贴**行首** `Hook[包名]`（不追加行尾） | 判据行前缀变了，按整行匹配的脚本要改；按 `installed N/M` 抓的不受影响 |
| 10 | xhs 的 `engine.begin` 期望版本由「恒基线」改为「读到版本就用它」 | **判据口径变化**（旧宿主挂覆盖套时不再自报漂移），需真机核对 |
| 11 | theme/launcher 的 `begin` 期望版本**保持基线**（与 xhs 相反） | 两类目标口径分叉，已在代码注释里说明 |
| 12 | 合并冲突判定下沉引擎（`MountSession.mount` 拿得到占用方 feature），纯函数 `mergedFeatureConflict` | 与计划原案（summary 槽位记 feature）实现路径不同 |

### 复审与遗留

- 整分支复审（fresh context subagent，模型 `opencode/glm-5.3-flash-free`）：**Critical 0**；Important 2（两处计划点名的注释被静默跳过、§五 缺 9.49.1 期望 M 分档）已在 `95e77c3` 修；Minor 3 记账不修。
- **Deferred minors**（留给你决定是否补）：① 重试轮会重新过 feature 闸门（窗口极窄，可首轮缓存 eligible）；② 机械迁移把「共享挂载基建」字段 KDoc 搬成局部变量 KDoc（约 12 处，纯外观）；③ `MountSessionTest` 只有 1 例冒烟（session 级重试/冲突路径 JVM 层无直测，属已知未覆盖面）。

### 外部审计 #1（2026-10-20，负责人自跑的第二个模型）

| 等级 | 发现 | 处置 |
|---|---|---|
| Important | `MERGED_CONFLICT` 口径没进耐久文档（`hook-craft.md` §四④ 只写「合并计成功」，`hook-inventory-audit.md` §五 无 MERGED 口径）⇒ 真机出现冲突时按文档读会误判成回归 | 已修（`72d80e6`）：§四④ 补例外、§五 加「合并口径」注记 |
| Minor | `MountOutcome.feature` 是死字段（`record` 不传、`MountSummary.record` 不读） | 已修（`72d80e6`）：删字段 + KDoc（Task 12 把冲突判定下沉引擎后失去消费者） |
| Minor | `SysmgrHook` / `OucHook` 用全限定 `top.honorhook.xposed.data.RemoteConfig` | 已修（`72d80e6`）：改 import + 短名（共 4 处，审计只点了 2 处） |
| Minor | `UpgradeGuideHook.kt:28` 的 `sourceDir` 被改成 `?.sourceDir` | **不成立**：`8693339` 基线本就是 `param.applicationInfo.sourceDir`，迁移逐字保留（已 grep 双向核对） |
| Minor | Task 8 两项注释纠偏在 `1508bcf` 时未执行 | 已在 `95e77c3` 修 |
| Minor | 工作区 `app/app/version.properties` 未提交 bump | 你的文件，未提交 |

**Deferred minors（合计 4 条，留给你决定）**：① 重试轮重新过 feature 闸门；② 机械迁移把字段 KDoc 搬成局部变量 KDoc（约 12 处）；③ `MountSessionTest` 只 1 例冒烟；④ `UpgradeGuideHook.kt:24` 的全限定 `RemoteConfig`（**基线既有**，不在本清单改动面内，未动）。
