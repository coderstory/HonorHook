# 2026-11-09 · Hook 系统架构评审：多 App × 多版本

> 用户提问：「没有架构方面的？尤其是 hook 系统，支持多个 app，然后又支持多个版本」。
> 本文只谈**结构**（事实源、扩展点、失败语义、可观测性），不重复 `docs/plans/2026-11-09-optimization-audit.md` 里的点状问题。
> 证据行号以 2026-11-09 05:00 工作区为准。

## 0. 结论

**逐点挂载层（`MountSession`/`Result` 分级/DexKit 三轨/懒加载重试）是这套系统里最扎实的部分，不要动。**
缺的是它**上面一层**：多版本这件事，被同时编码在四处，且没有任何运行时出口把「版本」与「挂载结果」连起来。

成本曲线现在是：**每来一个宿主小版本 ≈ 反编译 diff + 抄一张表**。theme 在 14 天内出了 3 个精确套
（`20.1.65.302` / `67.302` / `67.303`，分别对应若干混淆类改名）——约 **4 天一张**。
版本数再涨，四处编码之间的漂移就是概率问题，不是态度问题。

## 1. 现状分层（明确保留）

| 层 | 位置 | 职责 | 评价 |
|---|---|---|---|
| 入口 | `xposed/HookEntry.kt`（49 行） | 包名 → `TargetRegistry.find` → 逐 target `install`，per-target 隔离、`installed` 对象级去重、`RemoteConfig` 一次注入 | 干净；新增目标零改动，这条承诺成立 |
| 边缘 | `xposed/target/TargetHook.kt` + 34 处 `register(...)`（24 个作用域包 / 22 实现 / 33 实例） | 每目标自己决定挂什么 | 灵活但无护栏（见 A6/A8） |
| 核心 | `xposed/core/MountSession.kt`(305) + `MountEngine.kt`(124) | 会话工厂、重试 2 轮、`Result{OK,FAILED,NOT_RESOLVED,MERGED,MERGED_CONFLICT}`、汇总行 | **上乘**：`MERGED` 计成功、`MERGED_CONFLICT` 不计成功，这类语义边界做得比多数模块认真 |
| 版本 | `xposed/core/HostVersion.kt`(84) | 读宿主 versionName（两取法、失败盲挂）、三级选表、`matchedVersion` 自检 | 读版本这一层是稳的；**选什么表**这一层没有事实源 |
| 数据 | `xposed/data/HookMap.kt` + `target/*Map_*.kt` + 各 hook 内源码式 `pending` | 挂载点声明 | 四种格式并存（见 A7） |

## 2. 八个结构性缺口

### A1 · 「支持哪些版本」有三个事实源，且互不校验
- `FeatureCatalog.Target.baselineVersion` + `extraVersions`（`FeatureCatalog.kt:102`，UI 绿标只读这里）；
- 各 hook 内的 `HostVersion.Table(setOf("20.1.67.303"))` 字面量（`ThemeStoreHook.kt:33-38`、`InstallerHook.kt:119-125`）；
- 表文件名/表对象名（`ThemeStoreMap_20_1_67_303.kt`、`INSTALLER_POINTS_14_0_38_301`）。
- **后果**：UI 说「已适配 20.1.67.302」，而 hook 侧表被改名/漏注册时，`pick` 落默认套**盲挂**——界面绿标、实际按旧表挂，`installed N/M` 只在日志里。这正是 installer 曾 `0/11` 那类事故的温床。
- 修法：一张 `HostSupport` 清单（包名 → 基线 + 每条版本 → 表引用 + 策略 + 判据来源），UI 与 hook 都读它；配一条一致性单测。
- **已修（2026-11-09，第一批）**：保留 `FeatureCatalog` 作为唯一声明处（不再新造第三处），theme 的版本集合改为 `FeatureCatalog.extraVersionsOf(packageName)` 派生，hook 内字面量删除；新增 `Target.versionsSharingBaseline` 表达「这个版本无需独立表」；`VersionSupportTest` 双向断言 catalog ↔ 表。

### A2 · 「版本不适配时怎么办」是隐式约定，不是显式策略
- theme：表列表末尾有 `Table(versions = emptySet())` 默认套 ⇒ 任何新版本都盲挂基线表（`ThemeStoreHook.kt:38`）。
- installer：`installerTables` 没有默认套，新版本回落到源码式 `buildSourcePending()` 基线点（`InstallerHook.kt:126-128`）。
- **后果**：同为「未适配」，theme 会**尝试挂**（可能挂到错方法，静默错行为），installer 是**照旧挂基线**。两种都合理，但维护者无法从任何地方读到「这个包的策略是哪一种」。
- 修法：`enum MountPolicy { BlindLatest, Skip, FallbackTo(version) }` 写进 A1 的清单，`pick` 只认策略，不再靠「有没有空集表」推断。
- **已修（2026-11-09，第一批）**：`FeatureCatalog.MountPolicy{BlindLatest, Skip}` + `Target.mountPolicy`（installer 显式 `Skip`），`VersionSupportTest` 断言「策略 ⇔ 表结构」（BlindLatest 必须有默认套、Skip 必须没有）。

### A3 · 增量表达（delta）已经存在，但只是惯例而非契约
- theme 做对了：`THEME_POINTS_20_1_67_302 = THEME_POINTS_20_1_63_305.map { if (className == CLS_..._63) copy(className = CLS_..._67) else it }`（`ThemeStoreMap_20_1_67_302.kt:15-21`）——1 个类改名只写 1 行，注释还留了 `63.305=f71 → 65.302=g71 → 67.302=wb1` 的推导链。
- installer/launcher 是整表复制（`InstallerMap_14_0_38_301.kt` 11 点、`LauncherMap_18_0_0_234.kt`）。
- **后果**：新版本工作量与出错面取决于「上一个人用的是哪种写法」，review 无法用统一标准看 diff。
- 修法：`MountTable = base + List<Overlay>`，overlay 只允许白名单字段，并加测试：overlay 产物必须与显式全量表等价。

### A4 · 版本号渗进 hook 业务逻辑
- `ThemeStoreHook.kt:70`：`decryptToolClass = if (pick.matchedVersion == "20.1.67.303") THEME_DECRYPT_TOOL_67_303 else null`。
- **后果**：版本差异无法数据化 ⇒ 不能审阅、不能测试，新版本只能读 Kotlin 控制流。
- 修法：这类「某版本某点要换实现」写成表的字段（如 `toolClassOverride`），hook 体只读表；约束 `matchedVersion ==` 只允许出现在 `HostSupport` 适配层。

### A5 · 判据没有运行时出口（最贵的一条）
- `installed N/M` 只有 `MountSession.logLines`（`MountSession.kt:161-166`）→ LSPosed 日志。
- UI 侧唯一状态是 `FrameworkState.active`（服务是否绑上），**不等于「挂上了」**。
- **后果**：所有「开关看着开着其实没挂上」的投诉，只能让用户抓 LSPosed 日志；UI 无法区分「已连接未挂载」「部分挂载」「版本未适配盲挂」。
- 修法：`MountReport` —— 每会话结束写 `{pkg, hook, actualVersion, matchedVersion, ok, total, failedIds[]}` 到模块私有文件或远端 prefs 的 JSON key，UI 读它。这是多版本架构的可观测性底座。

### A6 · 多包目标被建模成 N 次独立注册，改一处要动四处
- `HonorAdsHook("com.hihonor.calendar")` × 5 包等（`TargetRegistry.kt`）。
- 加一个包要动：`TargetRegistry` 一行 + `scope.list` 一行 + `FeatureCatalog.Target` 一项（+ 可能的 key 常量）。
- **后果**：作用域与注册表两处手工同步——不一致时 `HookEntry` **收不到回调且零报错**，真机只表现为「没反应」。
- 修法：`PackagedTarget(hook, packages: List<String>)` 一次注册多包；`scope.list` 由 Gradle 任务从注册表**生成**。

### A7 · 「挂载点数据表」这个抽象并不统一
- 四格式并存：`HookPoint` 表、源码 `addConst`/`addForceArg`、OucHook 字符串对模板、SDK 内联 `MountTask`。
- 字段在半路被丢：Ouc/Sysmgr/AppMarket 的适配器不消费 `dexStrings`/`value`/`forceArgIndex` ⇒ 表里配了锚点，运行时永远直连轨，混淆点必然 `NOT_RESOLVED`。
- 修法：`HookPoint.toTask()` 收口到 core，加字段覆盖单测（未消费字段 = 测试失败）。

### A8 · 护栏测试只钉住数据，没钉住结构
- **更正（2026-11-09 复核）**：①三方一致其实**已有** `RegistryConsistencyTest`（scope.list ↔ FeatureCatalog ↔ TargetRegistry，含 6 个 SDK 通杀包）与 `FeatureKeyCoverageTest`；我写本文时漏查，特此纠正。
- 真缺的只有：②版本声明 ↔ 实际表（现已补 `VersionSupportTest`）；③`HookPoint` 字段覆盖（适配器丢字段，仍缺）。
- 成本 < 半天，收益是这几类事故从「真机发现」变成「CI 发现」。

## 3. 目标结构（v2，最小侵入）

```
FeatureCatalog（功能 key，保持不变）
        │
HostSupport（新，纯数据，UI 与 hook 共读）
  pkg → baseline + [ VersionEntry(version, policy, tableRef, verifiedBy) ]
        │
  ┌─────┴─────┐
  UI 绿标      HostVersion.pick(manifest, actual) ── MountTable(base + overlays)
                                   │
                            MountSession（不动）
                                   │
                            MountReport（新）→ 文件/remote prefs JSON → UI 卡片
```

四条可测约束：
1. 任何 target 的 versions 声明必须来自 `HostSupport`，hook 内禁止裸写版本字面量；
2. `policy` 显式化，禁止用「空集默认表」隐式表达 blind mount；
3. `scope.list` 由注册表生成（或由测试强制同步）；
4. 每次 `MountSession` 必须产出 `MountReport` 一条（缺 = 测试失败）。

## 4. 迁移顺序（止血 → 结构化 → 收敛）

| 序 | 动作 | 解决 | 估计 |
|---|---|---|---|
| 1 | `HostSupport` + 三方一致性 + 字段覆盖测试 | A1/A7/A8（**立即止血**，纯加数据与测试） | 0.5–1 人日 |
| 2 | `MountReport` 出口 + 首页「已挂载 N/M」 | A5（用户可见价值最大） | 1–2 人日 |
| 3 | `MountTable` overlay 契约 + installer/launcher 表迁成 delta | A3 | 1 人日 |
| 4 | `MountPolicy` 显式化 | A2 | 0.5 人日 |
| 5 | `PackagedTarget` + 生成式 `scope.list` | A6 | 2–3 人日 |
| 6 | 版本知识出代码（`toolClassOverride` 等） | A4 | 1 人日 |

第 1 步单独就能把「UI 说适配了、hook 没适配」的窗口关掉，建议先做。

## 5. 明确不做

- **不做自动适配/猜锚点**：没有内容锚点就 `NOT_RESOLVED`，这条哲学比版本覆盖率重要。
- **不把表挪成 JSON/资源**：Kotlin 常量有编译期检查与 IDE 跳转。
- **不合并 theme 的 4 张表**：每张表附带一个版本的实证注释，是跨版本推理的历史证据。
- **不引 KSP/注解处理器**：构建复杂度换来的只是少写几个 `HookPoint(...)`。

## 6. 执行进度

| 时间 | 批次 | 内容 | 提交 |
|---|---|---|---|
| 2026-11-09 | 第一批（A1/A2/A8-②） | theme 版本集从 catalog 派生；`MountPolicy` 显式化；`versionsSharingBaseline`；`VersionSupportTest` 双向对账（build + test 通过） | 见 `git log --grep=架构` |

第一批同时发现一处真实漂移：launcher 的 `18.0.0.259` 在 catalog 里登记为受支持，但 hook 侧没有精确套——此前靠「默认套盲挂」蒙对，现在由 `versionsSharingBaseline` 显式记录「该版本无类改名、可沿用基线表」。这正是本架构评审想防的那类静默一致。

下一批：`MountReport`（A5）——判据出口，用户可见价值最大。
