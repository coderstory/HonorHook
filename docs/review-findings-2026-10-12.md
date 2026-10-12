# 全库评审发现与方案（2026-10-12，已实施）

> 来源：`/code-review max`（15 条，fork 复核）+ 会话追加 12 条 + 需求 R2–R7。每条经源码逐行亲验（26/27 属实，见 §三）。
> **实施状态（2026-10-12）**：P0/P1/P2-1/P2-2/P2-3/P2-4/P3/R7 全部落码 + dev.sh §七 立修项；build + 单测全绿（7 套件 43 用例）。P4 低危积压仅实施 DexKit 两项 / judgeCount / TargetRegistry 同步 / MmsHook latch / probeJob @Volatile / Choreographer / 进度 total 未知 / Kotlin 双源 / 简介漂移 / rememberAppIcon / dev.sh 积压；其余 §五 P4 条目仍积压。真机回归清单见会话记录，未 commit。判据口径前提：**失效是常态**——一个功能点多 hook 冗余、活一个即生效（memory `redundant-hook-design`）。死点不是缺陷。

## 一、发现清单（#1–#27）

价值档：🔴 高（会咬人/冲突核心价值）｜🟡 中（规模化摩擦/一致性）｜⚪ 低（备注级）

### 崩溃 / 数据完整性

| # | 位置 | 问题 | 档 |
|---|---|---|---|
| 1 | `ui/update/ApkInstaller.kt:76` + `UpdateChecker.kt:111` | probeOne 的 `URL()`/`openConnection()` 在 try 外；checkAsync 的 probe launch 无 catch、scope 无 handler。**加重情节：静默启动检查即触发——未附 .apk 的 release 会让所有用户冷启崩** | 🔴 |
| 4 | `ApkInstaller.kt:134` | downloadFrom 流末即成功，不校验 `done==total`；半截 APK 保留且短路后续换源 | 🔴 |
| 16 | `ui/update/UpdateDialog.kt:105` | install() 无权限跳授权页返回 false → phase Ready 被覆写为 Error；授权回来无安装按钮，重试=全量重下。文案与 UI 自相矛盾 | 🔴 |

### hook 侧隔离 / 运行期

| # | 位置 | 问题 | 档 |
|---|---|---|---|
| 2 | `xposed/HookEntry.kt:27` | forEach 无 per-target 隔离：第一个 target install 抛异常即中断，同包剩余 TargetHook 全灭（calendar 三路场景） | 🔴 |
| 19 | core 全局 | 回调期审计黑盒：PROTECTIVE 框架吞异常、模块侧零痕迹；judge 行只打前 2 次命中。唯一活通路运行期静默死亡 = 功能全死日志无痕。**失效常态下这是判据闭环的必要件** | 🔴 |
| 23 | `MountEngine.kt:74` + `install()` 调用链 | attemptMount 首轮同步跑在 onPackageLoaded 线程（宿主主线程）；直连 miss 后 DexKit 兜底（bridge create 秒级 + 查询）卡宿主启动。**失效常态 ⇒ 死点常态 ⇒ 兜底扫描常态路径**，卡顿随死点数线性涨 | 🔴 |
| 24 | `MountEngine.resolveTask:121` | resolveValue lambda 无 runCatching；有 attemptMount per-task 兜底，后果仅 FAILED/NOT_RESOLVED 误分类（类加载时序型失败被永久判死） | ⚪ |
| 3 | `themestore/ThemeStoreRuntime.kt:271` | fixVipState `runCatching { proceed() }.getOrNull()` 吞异常；静默窗口 = proceed 抛异常且自建实例+填 setter 成功 → 伪造全套 VIP，无任何留痕。其余失败路径有 WARN | 🔴 |

### UI 态

| # | 位置 | 问题 | 档 |
|---|---|---|---|
| 5 | `ui/screen/SettingsScreen.kt:257` | 重启协程跑 rememberCoroutineScope + 状态非 saveable：旋屏取消协程，远端已同步但目标进程没杀 | 🟡 |
| 6 | `ui/screen/FeaturesScreen.kt:92` | selectedTarget 非 saveable（MainActivity:84 同款已修，自相矛盾） | 🟡 |
| 7 | `ui/screen/SettingsScreen.kt:82,104` | page/showRestart 非 saveable | 🟡 |

### 业务正确性

| # | 位置 | 问题 | 档 |
|---|---|---|---|
| 8 | `FeatureCatalog.kt:172` + `FeaturesScreen.kt:120` | ads.block/tracking.block 管辖 6 包，UI 唯一入口绑 totemweather 且按「已安装」过滤——装浏览器没装天气 ⇒ 开关不可达、hook 默认全开。实证：FeaturesScreen:116-120 getApplicationInfo 过滤 | 🔴 |
| 15 | `themestore/ThemeStoreMap_20_1_63_305.kt:42` vs `ThemeStoreParts.kt:42` | Incentive.getIncentiveExpireTime 用 Long.MAX_VALUE，与同表自附注记（Long.MAX 致格式化异常，故 VIP 路用 TS_2088）自相矛盾 | 🟡 |

### 数据表 / SoT 一致性

| # | 位置 | 问题 | 档 |
|---|---|---|---|
| 9 | theme map 44 处 + installer map 8 处 | `feature=` 裸字符串，不引用 KEY_* 常量，违反 SoT 规则；key 改名零编译错误、miss 走默认 true | 🔴 |
| 10 | `MmsHook.kt:121` / `ImeHook.kt:120` | HOST_VERSION_BASELINE 私有常量未上提 FeatureCatalog，catalog 条目 baselineVersion=null，UI 无失配提示 | 🟡 |
| 14 | `ThemeStoreMap:8` | 表头引用不存在的 `tools/gen_hook_map.py`（实证 tools/ 无此文件）；『挂载表 43 项』与实测 **44** 条不符；第 44 点（W2）无真机 installed N/M 实证 | 🟡 |
| 18 | `DecisionTablesTest.kt` | 测试只盖 4 张配置表；HookPoint kind/feature 合法性、版本表交叉、注册一致性零校验。kind 手滑 ⇒ MountKind.valueOf 抛 ⇒ pending 构建期炸 ⇒ **整目标不挂载**仅一条 ERROR | 🔴 |
| 20 | `InstallerHook.kt:73` | extraVersions ↔ versionTables ↔ 表文件名三处字符串耦合（注释自认「勿单侧改」），无交叉测试 | 🟡 |
| 21 | `FeatureCatalog.targets` + `TargetRegistry.init` + `META-INF/xposed/scope.list` | 新增 app 三点人肉同步，无 guard（当前一致系人工核对） | 🟡 |

### 架构 / 扩展性（服务 R4：多 app × 多版本）

| # | 位置 | 问题 | 档 |
|---|---|---|---|
| 22 | 全 target/ | 挂载点声明 4 格式并存（HookPoint 表 / addConst 源码函数 / OucHook 字符串对模板 / SDK 内联 MountTask）；adapt_new_version.py 提取器只盖前三种 + MountTask 具名式——格式分裂 = 部分目标永远无法自动核对 | 🔴 |
| 11 | `OucHook.kt:36` | AUTO_RECEIVERS/AUTO_SERVICES 字符串对 + 模板展开，第四种格式，adapt 提取盲区 | 🟡 |
| 12 | `ThemeStoreHook.kt:174` | PRE_PROCESS 按 task.id 魔法串 if/else 分派；按流程加表行 ⇒ 编译过但静默跑错逻辑 | 🟡 |
| 13 | `ThemeStoreHook.kt:176` | 44 点最大目标绕开 engine.mount 自行内联 resolve+hook+fold；engine 未来任何增强（隔离/日志/策略）对 theme 不生效，两份 fold 已漂移 | 🟡 |
| 17 | 11 个文件 | 各 target 手写零逻辑 `private class MountTask : MountEngine.Task`（6 字段透传），grep 实证 11 份 | 🟡 |
| 25 | `HookPoint` + MountEngine | 冗余组无数据形态：功能点分组只活在 description 自由字符串（"F2 A1"）；引擎不知「功能点剩几条通路」，无 `通路存活` 汇总，「全组死亡」与单点死同级呈现 | 🔴 |
| 26 | `tools/adapt_new_version.py` | 无死点基线 diff：逐代死活变化是常态，全量报告无注意力焦点（新死点才值得人看） | 🟡 |

## 二、审计结论（独立复核）

26/27 属实零误报（#24 降档：实际有 per-task 兜底，仅误分类）。审计中修正/加重：
- #1 比原报重：静默启动检查即崩（非用户操作触发）
- #3 精确化：静默窗口 = proceed 异常 + 自建填充成功；其他失败路径有 WARN
- #14 实证：gen_hook_map.py 不存在（tools/ grep 空）；theme 表 44 条 vs 注释 43
- #17 实证：11 文件各一份 MountTask
- #23 重定性：失效常态设计下 DexKit 兜底是常态路径非异常路径
- #26 补证：adapt 工具提取器覆盖 3 格式，无 diff 逻辑（grep 无 baseline/死点比对）

## 三、需求清单（本轮会话累计）

- R1 提升架构与代码质量 + 业务问题评审（已完成评审）
- R2 更新弹窗授权返回按钮丢失（#16）
- R3 hook 机制架构评审（§一 hook 侧全部）
- R4 扩展：后续加一批 app，每个多版本适配
- R5 隔离：一个 hook 坏不影响别的
- R6 失效常态需被架构承接（冗余、判据）
- R7 **版本不适配时按当前支持的最新版本盲挂**（现状已是隐式行为，缺显式契约+留痕，方案见 §五 P1-5）

## 四、目标不变量（修复验收口径）

1. 单点异常最大爆炸半径 = 该挂载点
2. 单 target install 异常不影响同进程其他 target
3. 宿主主线程零同步磁盘 IO
4. 所有静默路径都有 modlog 痕迹
5. 判读单位 = 功能点是否至少一条通路存活（非单点 M）
6. 宿主版本未收录 ⇒ 显式盲挂最新支持版本 + 留痕

## 五、技术方案（拍板后按序出变更清单）

### P0 崩溃与流程（纯 UI/update 层，低风险，先行）
- P0-1 (#1)：probeOne URL 构造入 try；checkAsync probe launch 加 onFailure
- P0-2 (#16)：install false 保持 Phase.Ready + hint 独立状态；再点先重查 canRequestPackageInstalls
- P0-3 (#4)：downloadFrom 收尾校验 total>0 && done!=total 抛异常换源
- P0-4 (#5/#6/#7)：UI 态 saveable 化；重启协程拎进程级 scope（saveable 救不了协程取消）

### P1 隔离加固（hook 层，需真机回归）
- P1-1 (#2)：HookEntry forEach 内 per-target runCatching（3 行）
- P1-2 (#23)：attemptMount 首轮 post 到 MountScheduler；验收 = 真机 installed N/M 逐目标与改前一致
- P1-3 (#24)：resolveTask 内 resolveValue invoke 包 runCatching 归 NOT_RESOLVED
- P1-4 (#19)：engine 统一 safely(task){} 回调包裹（runCatching+节流打点）。**前提：核实 libxposed 102 PROTECTIVE 框架语义（是否已回退原方法）**，未核实不动
- P1-5 (#27/R7)：`HostVersion.Pick` 加 fallbackTo；pick 第三级 = 取版本号最大精确套（排序比较器从 FeatureCatalog.supportedVersions 提取公共复用，防两处漂移）；盲挂留痕日志「宿主 X 未适配，按最新支持版本 Y 盲挂」；installer 的 matched=false 落 buildSourcePending 路径补同款留痕；pick 三级逻辑 JVM 单测（真机造不出未知版本场景，测试是主判据）

### P2 数据表演进（三步独立交付，顺序固定：护栏先行）
- P2-1 (#25)：HookPoint/MountEngine.Task 加 `group: String = ""`（功能点 id）；logSummary 按 group 输出通路存活汇总；全死 group 才升 ERROR；theme 表 44 行补字段
- P2-2 (#18/#20/#21)：DecisionTablesTest 扩：① 全表遍历断言 kind ∈ MountKind、feature ∈ FeatureCatalog 全集 ∪ ""；② extraVersions ↔ versionTables 交叉（installerTables 提 internal）；③ scope.list ↔ FeatureCatalog ↔ TargetRegistry 一致性
- P2-3 (#22/#11/#12/#13/#17/#9/#14) 格式收敛（大件）：OucHook 转 HookPoint 行 → 共享 SimpleTask（删 11 份 MountTask）→ engine.mount 加 intercept 定制回调 theme 回框架 → kind→handler 映射表化 → 52 处 feature= 改 KEY_* 常量 → 表头注释改「手维护」+ 43→44。feature 改常量后 #18 校验升级为编译期
- P2-4 (#8)：净化入口——推荐虚拟目标（iconKey 非空）跳过「已安装」过滤，卡片恒显，desc 写覆盖范围

### P3 工具链
- P3-1 (#26)：adapt_new_version.py 加 `--diff <上代report>`，死点集 diff，新死点才列

### P4 积压（不进本轮）
DexKit bridgeFor 快路径双字段非原子、`Modifier.isInterface` 恒 false 死过滤、无 Content-Length 进度恒 0、begin() 不重置 judgeCount、rememberAppIcon 主线程 Binder、probeJob 无 happens-before、version.properties configuration 期自增、TargetRegistry 非同步集合、ThemeStoreMap 描述组标错位、三处简介漂移、Kotlin 版本双源（settings 2.4.21 vs toml 2.3.21）、Choreographer 取色回调永不注销、MmsHook 一次性标志失败不重试。

## 六、待拍板决策点

1. #8 净化入口：方案 b（虚拟目标跳过安装过滤）？或 a（每真实包一卡）/ c（双入口）
2. P1-2 主线程改动：接受一次真机回归成本？
3. P2-3 节奏：theme 先行试点 or 全量一次切？
4. P1-4：先核实 PROTECTIVE 框架语义（reference/ 是否有 libxposed 源码）
5. R7 是否含 UI 文案项（「版本未适配」tag 补「按 Y 尝试」）

## 七、dev.sh 审查（2026-10-12 补，764 行全读）

质量好（set -euo pipefail / per-command require / `\r` 纪律 / 实证注释）。真问题 2 个半，无高危（无 reboot/卸系统应用路径）。

**立修**
- d1 `verify` 调未定义函数 `warn`（全脚本仅 die/info/stamp/require）——日志不可读时 command not found → exit 127 → set -e 杀脚本。改 info 或补定义
- d14 pipefail 尾管道：多个子命令末尾 `grep | tail` 无匹配时 exit 1 → pipefail → dev.sh 整体报错退出（lspd-log/log-grep/diag-module④ 等），查无结果呈现为命令失败。末尾补 `|| true`

**并入 #21 同修**
- d8 enable-module 强停 6 包硬编码（注释自认与 scope.list 人肉对应）= 第四处同步点；enable-cli + restart-scope（读 scope.list）已建成，可改读 scope.list 或退役 enable-module

**积压**：d3 modlog tag 引号嵌套自伤；d4 restart-scope 只 kill 首 pid 多进程杀不彻底；d6 text 空格/中文无提示；d9 dexstat 取 build-tools 最低版本与 aapt2 相反；d5 install-root 裸 python3 未走 PYVENV 探测链。
