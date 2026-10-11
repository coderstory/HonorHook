---
last_mapped_commit: fe2e820eb97798af089b982b570191734c867fcb
last_mapped_at: 2026-10-11
---
# Testing Patterns

**Analysis Date:** 2026-10-11

> 本仓库**不是**"有单测套件"的工程，也不该按那个模型理解它。真实形态是三层门禁 + 真机判据 + 用户自执行的功能验收。以下全部按现状描述。

## Verification Model

### 判据的真实形态：LSPosed 框架日志里的 `installed N/M`

**ground truth = 真机 `/data/adb/lspd/log/modules_*.log` 里的 `installed N/M`，明确不在 logcat**（`CLAUDE.md:14`、`README.md:53-55`、`docs/hook-craft.md:68`）。判据行由 `MountEngine.logSummary` 产出，格式 `"$hookName: installed $okCount/$totalCount"`，失败时追加 `" — 失败: …"`（`app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt:129-137`）。

logcat 里也有 `TAG=HonorHook` 的行（`tools/dev.sh log-hook`），但那只是宿主 logger 转发的副产品，**不能当判据**——宿主 logger 的 INFO 常不落 logcat，载荷类证据必须靠模块侧探针打（`docs/hook-craft.md:68`）。

### 三层门禁

| 层 | 手段 | 命令 | 覆盖面 |
|---|---|---|---|
| 1. 编译 | 定向 gradle 编译（子任务只跑定向编译） | `tools/dev.sh build` / `build-release` | 全部代码（类型与链接正确性） |
| 2. JVM 单测 | 表驱动决策回归 | `tools/dev.sh test` → `:app:testDebugUnitTest`（`tools/dev.sh:243-247`） | 仅 4 张纯决策表 |
| 3. 真机判据 | 注入 → 读框架日志 | `tools/dev.sh deploy-release` + `wait-hook` / `modlog` | 全部挂载点（唯一能证明"hook 真的挂上了"的层） |
| 4. 功能验收 | **用户自执行**清单 | `README.md:66-75` | 行为是否达到目的 |

### 交付边界（不可协商）

> **AI 交付 = 代码 + 构建通过 + 用户自执行的验证清单。**（`CLAUDE.md:28`、`docs/hook-craft.md:76`）

AI 不在用户主力机上实测破坏性 hook 的效果。给出代码 + 编译通过 + 一份可照着点的清单，**停下**。

### 设备铁律（`CLAUDE.md:26-29`）

- 🚫 **禁 reboot（任何形态）**——包括 `adb reboot`、`svc power reboot`、`stop && start` 之类伪装形式。需要重启才生效的事：**停下告诉用户，等用户自己重启**。
- 🚫 **禁破坏性/难回退操作**（恢复出厂、`pm uninstall` 系统应用、擦数据）——**先问用户**。
- 🚫 **不在用户主力机实测未验证 hook 的破坏性效果**。
- 只真机直连验证；`reference/` 只读。
- 覆盖安装后 LSPosed 会停用模块 ⇒ 必须重新启用（见下 Device Verification）。

## Device Verification

**唯一入口是 `tools/dev.sh`**（`CLAUDE.md:11` "禁手拼 gradle/adb"）。任何新设备操作**先加 `dev.sh` 子命令再使用**。第一条命令永远是：

```bash
tools/dev.sh env      # 打印 JAVA_HOME / adb / aapt2 / apktool / jadx / python3 的解析路径
tools/dev.sh help     # 全量子命令索引
```

### 子命令全量清单（摘自 `tools/dev.sh:76-123`）

- **构建**：`build`（debug）、`build-release`（混淆+收缩）、`clean`、`test`
- **安装部署**：`install`、`install-release`、`enable-module`、`deploy-release`、`devices`
- **判据日志**：`log`、`log-hook`、`log-tag`、`log-grep`、`grep-log <正则>`、`grep-logd <正则>`、`modlog [tag] [行数]`、**`lspd-log`**、`crash`、`logcrash`、`log-file`、`clear-log`、`logcat`、**`diag-start`**、**`diag-module`**、`diag-capture`、**`wait-hook`**、**`hook-test`**
- **设备操作**：`launch`、`launch-theme`、`top`、`shot`、`ui`、`viewdump`、`tap`、`swipe`、`text`、`key`、`scopes`、`targets`、`size`、`dexstat`、`verify`
- **逆向**：`decompile <apk> [名]`、`jadx <apk> [名]`、`aapt2 <参数...>`、`apkinfo <apk>`、`exec <命令...>`
- **环境**：`env`、`help`

### 标准的「改完验证」回路

```bash
tools/dev.sh build              # 1. 定向编译（子任务只跑到这一步）
tools/dev.sh deploy-release     # 2. release 全流程：编译 → 卸载重装 → 自动重新启用 → 强停作用域进程
tools/dev.sh wait-hook          # 3. 清日志后轮询 ≤3 分钟等 installed 判据
tools/dev.sh modlog HonorHook 60   # 4. 读框架日志里的判据行
tools/dev.sh hook-test          # 或：一步完成"清日志 → 强停拉起主题商店 → 抓判据"
```

调试向：`tools/dev.sh lspd-log`（模块根本没被加载时先看这里）、`tools/dev.sh diag-module`（四步自检：包是否安装 / 宿主进程存活 / LSPosed 模块记录 / 全量日志 grep）、`tools/dev.sh crash` 与 `logcrash`（宿主侧崩溃堆栈）、`tools/dev.sh ui` 与 `viewdump`（真机 UI 取证，含 text / content-desc / resource-id）。

### 两个必踩的坑（已在脚本里处理，别绕过）

1. **重装即被停用**：覆盖安装后 LSPosed 停用模块，表现为"进程活着但零模块日志"（`docs/hook-craft.md:75`）。必须跑 `enable-module` —— 它会自动点 LSPosed「模块未激活」系统通知深链并点开启用开关（通知栏与寄生管理器对 uiautomator 不可见，全程 screencap + PIL 像素定位，`tools/dev.sh:289-332`）。安装子命令本身也先 `adb uninstall` 再 install（`tools/dev.sh:251-252,258-262`，用户约定：避免覆盖安装残留）。
2. **作用域进程必须全部强停**：`enable-module` 会 force-stop `scope.list` 的全部 6 个包（`tools/dev.sh:368-372`）。漏一个就会出现"部署报成功但该目标 hook 没生效"的**假成功**。新增目标时同步改这个列表。

### 环境约束（写新子命令时必须遵守）

- 工具链自解析顺序：项目内便携版 → brew / 本机安装 → PATH（`tools/dev.sh:10-52`）。
- **JDK 21 必须含 `jlink`**（release 打包需要，`tools/dev.sh:238`；缺 jlink 的 JDK 只能编 debug）。
- 脚本一律 **LF**（CRLF 在 bash 3.2 直接报错，`.gitattributes:1` 保证 `eol=lf`）。
- bash 3.2：`$VAR` 后紧跟中文必须写 `${VAR}`，否则 unbound variable 退出（`CLAUDE.md:35`）。
- 只支持**真机 arm64 直连**（`abiFilters += "arm64-v8a"`，`app/app/build.gradle.kts:20-22`）；没有模拟器路径。

## Acceptance Criteria

### 判据行的读法

- **N == M** ⇒ 全部挂载点解析成功并挂上。
- **N < M** ⇒ 版本错位或选点失效。**第一步不是改 hook 实现**，而是 `dumpsys package <pkg>` 对宿主版本 vs 逆向基线（`docs/hook-craft.md:72`）。
- **M 是稳定分母**：只在首轮快照，重试轮不覆盖（`MountEngine.kt:75-77`），所以 `installed 12/43` 的 43 恒等于本次声明的挂载表大小。
- 失败清单以 `— 失败: <description>…` 追加在同一行（`MountEngine.kt:131`），直接点名哪个 task 没挂上。

### 基线判据值（`README.md:53-55`，与代码声明一致）

```
ThemeStoreHook: installed 43/43
InstallerHook: installed 11/11
SettingsHook: installed 1/1
Exam8Hook: installed 10/10
LauncherCardTitleHook: installed 1/1
SystemUiHook: installed 1/1
```

### 辅助判据行

- **功能生效行**：`"<target>-hook 生效: <task.id> — <description>（detail）"`，每 task 最多 2 条（`MountEngine.kt:166-177`）。
- **功能关闭留痕**：`"<hookName>: 功能已由远端开关关闭（<key>），本次注入跳过挂载"`（`TargetHook.kt:21-23`）。
- **版本自检**：首轮挂载后 1s 在后台线程比对 `HOST_VERSION_BASELINE`，不一致打 WARN "宿主版本 X 与 hook 基线 Y 不一致——挂载点可能漂移"（`MountEngine.kt:179-202`）。
- **静默 no-op 告警**：判据行 `installed 1/1` 但功能无效的情况必须另有 WARN。例如 SystemUI 读不到 `SubscriptionModel.slotId` 时一次性告警（`SystemUiHook.kt:100-119`）。**所以判据行只是必要条件，不是充分条件。**

### 功能验收（第二半，由设备所有者执行）

`README.md:66-75` 的清单逐条手点，AI 不代跑：

```bash

# 主题商店：下载付费动态壁纸 → 点「应用」应生效（日志：壁纸packagePath 已修正）

# 包管理器：安装任意第三方 APK → 无网络不佳横幅 / 无风险拦截页 / 无指纹弹窗

# 设置：任意应用详情页「停用」按钮可点

# 系统桌面：YOYO 卡片标题不再显示，桌面应用图标名保留

# 成人高考题库：会员页显示「超级会员」+ 远期有效期；「阅后即焚」列表不再显示「立即抢购」

#   （试卷正文仍会「加载失败」——正文由服务端按订单态下发，属预期边界）

```

### 版本基线是验收的一部分

每个目标声明 `HOST_VERSION_BASELINE`，超出基线即结论作废、需重新逆向而非改 hook（`docs/reverse-craft.md:47`、`.github/workflows/ci.yml:66-67`）：主题商店 20.1.63.305 / 包管理器 14.0.37.301 / 系统桌面 18.0.0.234 / 成人高考题库 5.9.0.0-release / 设置 14.0.0.210 / SystemUI 17.0.0.1。

**方法存在性以设备实机 APK 的 dex 为准**：`adb shell pm path` 拉包后检索 dex，jadx 产物只作阅读参考（曾出现 jadx 有 `getIsVipUser`、设备包只有 `isVipUser` ⇒ 真机挂载失败，`docs/hook-craft.md:73`）。

## Test File Organization

- **唯一的测试文件**：`app/app/src/test/kotlin/top/honorhook/xposed/data/DecisionTablesTest.kt`（103 行），package `top.honorhook.xposed.data` 与主源码镜像。依赖只有 `testImplementation(libs.junit)`（`app/app/build.gradle.kts:99-100`）。
- **androidTest / instrumented test：未检测到** —— `app/app/src/androidTest/` 不存在。没有 Robolectric、没有 Espresso、没有截图回归、没有 Compose UI 测试。
- **命名**：类名 `<被测对象>Test`；方法名用反引号英文句子描述**契约**而非方法名，如 `` `unknown slot type is kept` ``（`DecisionTablesTest.kt:29`）；分节用 `// ---------------- AdSlotFilter ----------------` 横线分隔（`DecisionTablesTest.kt:19`）。
- **断言带中文消息**，失败时直接可读：`assertTrue("MIN_LIST_SIZE 必须 >= 3，否则裁到触发宿主回退分支", …)`（`DecisionTablesTest.kt:60-63`）。
- **为什么范围这么窄**：`build.gradle.kts:99` 的注释写明——只有"无 Android 依赖的纯决策表"能上 JVM；hook 挂载、DexKit 解析、Compose UI、remote prefs 通道全部需要真机。

## Test Types

1. **表驱动决策回归（唯一自动化类型）**。三类断言覆盖每个表的三种失效面：
   - 表内条目**必须命中**：`AdSlotFilter.PRUNED_SLOT_TYPES.forEach { assertTrue(...) }`（`DecisionTablesTest.kt:22-26`）。
   - 表外输入**必须走安全默认**（放行 / 保留 / 返 null）：`assertNull(BottomTabFilter.ruleFor(1))`（`DecisionTablesTest.kt:47-54`）。
   - **重复 key 检测**：`CloudSwitchTable.ENTRIES` 的 key 去重后 size 必须相等，因为 MAP 构造会静默丢弃前一条（`DecisionTablesTest.kt:82-85`）。
   - **语义常量不得翻转**：安全模式那几个"门恒不成立"的常量被改成 true 就等于把广告/拦截全放回来（`DecisionTablesTest.kt:96-102`）。
   - **数值下限契约**：`BottomTabFilter.MIN_LIST_SIZE >= 3`，调小会静默触发宿主回退分支（`DecisionTablesTest.kt:57-64`）。
2. **版本适配回归（离线脚本，不在 CI）**：`python3 tools/adapt_new_version.py <新版本 APK> --expect-pkg <pkg>` —— jadx 反编译新版 → 自动从 hook 源码提取挂载点清单 → 逐点校验"类存在 + 方法存在（名 + 参数数）"→ 输出**存活 N/M + 丢失点清单**（`tools/adapt_new_version.py:1-47`）。
   **清单不是硬编码的**：脚本只认两种书写格式 —— 位置式 `addConst(pending, CLS_X, "方法名", 参数数, "返回类型", …)` 与具名式 `pending += MountTask(kind = …, className = …, methodName = "…", paramCount = …)`（`tools/adapt_new_version.py:16-25`）。**改 hook 代码时必须保持这两种调用形态之一**，否则适配脚本会静默漏点。类名常量 `CLS_*` 与 `className` 位置的字符串字面量都被识别，`$PKG` 模板与 `\$` 转义自动展开。
3. **真机判据（`dev.sh` + 人工）**：见 Device Verification / Acceptance Criteria。这不是测试框架，是本项目的主验证机制。
4. **CI**（`.github/workflows/ci.yml`）：
   - `dev` push → `assembleDebug` + `test`，**真门禁不加 `|| true`**（`:41-42`），上传 APK artifact。
   - `master` push → `assembleRelease` + 自动发 GitHub Release（`v<versionName>.r<构建号>`），release body 里带宿主基线、安装步骤与 `installed N/M` 判据说明（`:66-70`）。
   - **无 lint job、无设备农场、无 instrumentation。**

## Coverage

- **自动化覆盖的**：仅 4 张决策表 —— `AdSlotFilter`、`BottomTabFilter`、`CloudSwitchTable`、`InstallerConfigTable`。它们被设计成"数据与 hook 逻辑分离的唯一改动点"，改表即改线上行为（`DecisionTablesTest.kt:10-16`）。
- **自动化未覆盖、只能真机判定的**：挂载解析（直连反射 + DexKit 双轨）、重试与失败分级、宿主版本自检、remote prefs 通道与默认全开语义、三个 Tab + 二级页的 Compose UI、液态玻璃/backdrop 渲染、release 打包下 `java_init.list` 与 `HookEntry` 的存活（`app/app/proguard-rules.pro` 的 keep 规则来自真实 R8 事故）。
- **覆盖率工具：未检测到**（无 JaCoCo / detekt / kover 配置）。本项目的"覆盖率"语义 = **判据行的分母 M**，即代码里声明的挂载点数 vs 真机上实际挂上的点数；两者的差集就是判据行尾部的失败清单。声明数与 README 的台账必须一致（`docs/hook-inventory-audit.md`）。
- **规模口径**（`docs/ui-miuix-overview.md:27`）：全模块 6624 行 / 41 kt —— hook 侧 2805 行、UI 侧 3735 行；其中注释 906 行是唯一审计痕迹。

## Common Patterns

### 优雅失败分级：FAILED vs NOT_RESOLVED

见 `docs/hook-craft.md:60` 与实现 `app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt:11-12,79-98`：

- **`FAILED`** = `module.hook(...)` 抛异常 ⇒ **重试无意义** ⇒ 记入 `failedIds`、不重试、不阻断同批其他任务。
- **`NOT_RESOLVED`** = 类/方法/依赖值未就绪 ⇒ **随类加载重试** ⇒ 进 `unresolved` 子列表。
- 重试调度：`MAX_RETRY_ROUNDS = 2`，延 `500L` → `2000L`，跑在共享后台 `HandlerThread("HonorHook-mount")`（`MountEngine.kt:206-208`、`xposed/core/MountScheduler.kt:7-17`）。
- 耗尽后并入 `failedIds` 并 WARN"部分 hook 最终未挂载（…已重试 N 轮，放弃）"，**仍然打汇总行**。

### DexKit 的 NOT_RESOLVED 重试与"诚实跳过"

- 依赖值未就绪 → WARN "hook 未挂载（依赖项未解析，将重试）"（`MountEngine.kt:116`）。
- 类/方法不存在（版本差异）→ WARN "hook 未挂载（类/方法不存在，版本差异，将重试）"（`MountEngine.kt:124`）。
- **无内容锚点则诚实跳过，不猜类名**：默认包混淆类且四维锚点全空 → 直接返回 null 并 WARN "DexKit 兜底跳过（默认包类且无内容锚点，避免全 APK 误命中）"（`xposed/core/MethodResolver.kt:87-99`；规则见 `CLAUDE.md:43`）。判定结果是 `NOT_RESOLVED`，随重试再走，而不是硬兜一个猜出来的类名。
- DexKit 轨整体不可用（so 加载 / bridge 创建 / 查询抛错）→ 返回 null 降级，不影响直连反射轨（`xposed/core/DexKitHelper.kt:54-64`）。

### 通用模式清单

- **表外安全默认**：所有决策表对未知输入返回"放行/保留/返 null"（`AdSlotFilter.kt:31,34`、`CloudSwitchTable.forced`）；**安全默认方向被单测钉死**。
- **宿主侧读取失败一律放行原逻辑**，并留 WARN，不中断（`SettingsHook.kt:68-85`、`themestore/CollectorsSource.kt:183,267,322,328,352,365,384`）。
- **静默 no-op 必须显式化**：挂上了但没效果是最难查的一类，判据行会照打 N/M —— 用 `AtomicBoolean` 一次性告警兜住（`SystemUiHook.kt:100-119`）。
- **节流三件套**：per-task 计数上限（`JUDGE_LOG_PER_TASK`）、布尔门控（`DEBUG_LOGGING` / 目标内 `DEBUG`）、`AtomicBoolean` 一次性告警。
- **双层异常隔离**：挂载 `ExceptionMode.PROTECTIVE` + 回调体 `runCatching`，绝不让插件炸宿主（`CLAUDE.md:29`）。
- **幂等在注册表层**：`TargetRegistry.installed` 对象级防重装，目标实现内**不加** installed 标志（`TargetHook.kt:30`）。
- **假成功是头号敌人**：部署成功 ≠ 目标生效（漏强停作用域进程、重装后模块被停用、版本漂移）。每一步都要有对应判据行，不要用"看起来好了"结案。
- **删挂载点必须真机 A/B**：静态推理"这段冗余"被真机回归打脸过一次（漏斗/旁路/null 实例都出自静态推理的盲区，`docs/hook-craft.md:74`）。删除类改动一律要真机验证，不做纸面清理。
- **破坏性效果不由 AI 实测**：给清单、给命令、让用户跑。

---

*Testing analysis: 2026-10-11*
