# 整改意见书 — HonorHook 多维度深度审计（2026-10-25）

审计对象：`dev` 分支 @ 4c65cbd。方法：逐文件精读核心链路（入口/挂载引擎/开关通道/更新链路/root 工具箱/UI 存储）+ 构建与 CI 配置 + git 仓库卫生抽查，所有发现附文件:行号证据。**本审计未做真机动态验证**，标注「待验证」的项须上机复测后才可定级。

严重度分级：**P0** = 安全/正确性问题，应立即整改；**P1** = 本迭代内整改（可靠性、契约兑现）；**P2** = 计划内整改（卫生、文档、防御深度）。

---

## 一、发现清单（按严重度）

### P0-1 在线更新链路无完整性校验，且信任第三方加速镜像

**维度**：安全工程　**发现人视角**：应用安全工程师

- **证据**：`ui/update/ApkInstaller.kt:25-32` 下载源候选含 5 个第三方镜像（ghfast.top / gh-proxy.com / ghproxy.net / ghproxy.cc / gh.llkk.cc）；`download()`（:35-48）下载完成后 `install()`（:156-175）直接经 FileProvider 交给系统安装器，**全程无哈希/签名摘要校验**。
- **风险**：HTTPS 只保证传输未被窃听，不保证镜像诚实。镜像被接管/换主后可分发任意内容。缓解因素：覆盖安装时 Android 会校验与已装模块签名一致，直接换包会被拒——但**首次安装的用户没有任何防护**（恶意 APK 自带合法自洽签名照样能装成新应用），且坏镜像至少构成拒绝服务（半截/损坏 APK 反复安装失败）。probe 的赛马策略（`probeSources` 取最先握手成功者）进一步放大了「实际下载源不可预测」的问题。
- **整改建议**：
  1. CI 发布时把 APK 的 SHA-256 写进 release 元数据（GitHub API 的 asset `digest` 字段已支持，或写入 release notes 固定段落 + `UpdateChecker.fetchLatest()` 解析）；
  2. `install()` 前强制本地校验 SHA-256，不匹配即删除文件并报错，不进系统安装器；
  3. 镜像仅在直连确认不通后逐个降级使用，且校验逻辑对镜像路径同样生效（哈希来自 api.github.com 主源，不走镜像）。
- **验收**：篡改一个字节的 APK 下载文件，应用侧拒绝安装并提示校验失败。

### P0-2 506 个构建产物被 git track

**维度**：构建与发布工程　**发现人视角**：构建工程师

- **证据**：`git ls-files | grep build/` = 506 个文件，含 `.transforms/` 下的 dex 产物、`intermediates/` 的 merger.xml、`kotlin/compileDebugKotlin/cacheable/last-build.bin` 等纯构建缓存（工作区当前就有一片 `M` 状态）。其中 `libs/xhs-dn-core/build/generated/xhsdn/` 是白名单同步机制的输出，属有意 track，其余全部是误入。
- **风险**：每次构建仓库变脏、掩盖真实变更、PR diff 噪音、仓库体积持续膨胀、二进制文件 merge 冲突无解。
- **整改建议**：
  1. `git rm -r --cached` 清掉 `generated/xhsdn` 之外的全部 `build/` 路径；
  2. `.gitignore` 加 `**/build/`，再为 `generated/xhsdn` 显式加回（`!libs/xhs-dn-core/build/generated/xhsdn/`）；
  3. 评估 `generated/xhsdn` 是否也可改为不 track（构建时由白名单任务从 submodule 同步生成，产物本可复现）——若是，一并清掉，仓库只留 `reference/` submodule 一个事实源。
- **验收**：干净 checkout 后连续两次 `tools/dev.sh build`，`git status` 保持 clean。

### P1-1 开关下发的失败被 UI 静默吞掉——自家注释契约未兑现

**维度**：产品可靠性　**发现人视角**：终端用户权益 / 可用性

- **证据**：`ui/FeatureStore.kt:55-57` 注释明确承诺「`false` 表示框架未连接或写入异常……UI 必须如实告知用户（不能只改本地就当生效了）」；但 `ui/screen/FeaturesScreen.kt:681` 的 `onCheckedChange = { FeatureStore.setEnabled(feature.key, it) }` **丢弃了返回值**，无任何提示路径。
- **风险**：用户在 LSPosed 框架未连接/写入异常时关掉了某功能，UI 显示已关、hook 侧按远端旧值继续跑。对「跳过安装安全检测」「禁用病毒扫描」这类默认开的高影响功能，这是直接的信任问题——用户以为关了，实际没关。
- **整改建议**：消费 `setEnabled` 返回值；失败时开关行内标注「未生效：LSPosed 未连接」并保留开关态回滚或提示重启目标应用（现有「下次注入才生效」语义一并讲清）。
- **验收**：断开框架（或 mock `FrameworkState.current() = null`）切开关，UI 出现明确失败提示。

### P1-2 master 发布链路不跑任何测试

**维度**：测试与 CI　**发现人视角**：测试工程师

- **证据**：`.github/workflows/ci.yml` 中 `Run tests` 步骤仅 `if: github.ref_name == 'dev'`；master 的 release 构建直接 `assembleRelease` → 发 Release → 同步 Xposed-Modules-Repo，全程零测试。
- **风险**：dev→master 的合并/樱桃挑选引入的回归可以直达发布产物。现有 16 个测试文件（含钉 N/M 口径、注册表一致性、SafeCommands 注入面）恰恰是防「静默失效」的契约，发布时反而不执行。
- **整改建议**：release job 前置 `testDebugUnitTest`（增量成本约一分钟）；或至少在 release job 里加 `./app/gradlew -p app test`。顺带：dev 的 `test` 任务跑的是全部变体，可收敛为 `testDebugUnitTest` 缩短门禁。
- **验收**：构造一个必挂的测试推 master，CI 应红且不发 Release。

### P1-3 每个被注入进程常驻一条挂载线程，DexKit 内存峰值未度量

**维度**：性能 / SRE　**发现人视角**：性能工程师

- **证据**：`xposed/core/MountScheduler.kt:9`——`object` 单例首次触达即 `start()` 一条 `HonorHook-mount` HandlerThread；`scope.list` 声明 23 个包（日历/天气/浏览器/云音乐/输入法等高频进程全部在内）。DexKit 兜底轨（`MethodResolver` → `DexKitHelper`）在这些进程内做磁盘扫描时的内存峰值、dex 句柄是否释放——**本次审计未读证，待验证**。
- **风险**：单个进程的开销可能不大，但乘以 23 个宿主进程且多为系统级常驻应用；一旦 DexKit 在低端机上扫描大 dex（云音乐/浏览器）造成可感知卡顿或内存压力，用户会归因于宿主而非模块。
- **整改建议**：
  1. 先度量再优化：每宿主进程记录模块带来的线程数/常驻内存增量，进 `docs/hook-inventory-audit.md` 同款台账；
  2. 核查 `DexKitHelper` 的 dex 数据源是否随会话关闭（若用 `File#readBytes` 全量读进内存，改为 DexKit 支持的按需打开）；
  3. 挂载全成功后该线程是否可 idle 退出（无重试任务时 quit），值得评估。
- **验收**：真机 dump 各宿主进程，模块增量内存/线程数有书面基线。

### P2-1 源码内注释越界承载文档内容

**维度**：可维护性 / 文档工程　**发现人视角**：技术文档工程师

- **证据**：`xposed/target/mms/MmsHook.kt:29-88` 头部约 60 行注释完整承载了云端消息→视图收口的数据链叙述、Honor/HW 分支形态、AimButtonLayout 布局 id 对比——内容质量很高，但违反了项目自订纪律「长篇进 `docs/`，源码留指针」（AGENTS.md 写代码的纪律节）。同类模式在 xhs 目录亦存在。
- **风险**：同一逆向结论在源码注释与 `docs/reverse/sms-ads.md` 双处存在，宿主版本演进时只改一处即漂移——而漂移恰恰是这个项目最忌讳的事（它的判据体系全部建立在「实证记录可信」上）。
- **整改建议**：数据链/分支形态叙述下沉到 `docs/reverse/sms-ads.md`（部分已在），源码注释留「结论 + 指针 + 行号锚点」。不必一刀切，以「叙述性段落」为界：讲数据流/分支史的搬走，讲约束/判据/为什么这么写的留下。

### P2-2 模块日志输出含 xsec_token 的完整 URL

**维度**：安全工程（低危）　**发现人视角**：应用安全工程师

- **证据**：`xposed/target/xhs/XhsBatchSave.kt:847`——「全量 URL 不截断（原图链路诊断）：PC 端 curl 比对需完整 token/query」，全量 URL（含 `xsec_token`）写入 LSPosed 模块日志。该 token 是笔记访问凭证，日志文件可被其他 root 应用/导出诊断读取。
- **整改建议**：token 做中段脱敏（保留前后 4 位足够 curl 比对定位）；若诊断确需全量，挂在 debug 日志门控（`MountEngine.DEBUG_LOGGING`）之后。
- **验收**：`modlog-grep` 抓不到完整 32 位 token。

### P2-3 RemoteConfig 未知 key 回退 `false`，与「默认全开」哲学相悖

**维度**：架构（防御深度）　**发现人视角**：Xposed 架构师

- **证据**：`xposed/data/RemoteConfig.kt:39`——`catalogDefaults[key] ?: false`。key 不在 `FeatureCatalog` 登记时静默回「关」，与全模块「读不到一律视为开」的容错方向相反。现网 key 全部经 `FeatureCatalog.KEY_*` 常量引用（编译期同一事实源），打错字概率低，故降为 P2。
- **整改建议**：补一条 JVM 测试钉住：遍历全部 `TargetHook` 挂载表里引用的 feature key，断言全部存在于 `FeatureCatalog` 目录（`HookPointTablesTest`/`RegistryConsistencyTest` 已有基建，加一个断言遍历即可）；或未知 key 打 ERROR 日志留痕。

### P2-4 高风险功能的默认态与用户知情面

**维度**：产品 / 合规　**发现人视角**：法务视角提示（非法律意见）

- **证据**：默认全开（`FeatureCatalog` 默认 `true`，读不到 = 开）的产品规则下，「跳过联网安全检测」「禁用病毒扫描」「跳过安装器的三方拦截」随安装即静默生效；README 的功能表格未标注哪些是「安全类功能」。
- **整改建议**（产品侧，非代码）：功能页给安全类功能单独分组并显著标注默认开启与影响（「关闭了荣耀的应用安装安全检测」）；免责声明「24 小时内删除」类表述无实际法律效力，不如具体写明「各功能均为本机内存态修改、不修改计费/账户/服务端」——前提是逐功能核实成立（exam8「会员档位显示超级会员」从 README 表述看是显示态，应钉住这一边界并写明：只改 UI 展示，不触碰真实计费）。

### P2-5 知识集中度风险（bus factor = 1）

**维度**：可持续性　**发现人视角**：工程管理

- **证据**：16 天 351 commit 单人提交；判据快照（`docs/hook-inventory-audit.md` §五）、适配台账等关键知识以「人工维护文档」形式存在，无机械同步手段；`docs/` 体系完备但读者需同时接受自创的判据概念体系（N/M 口径、三阶段解析、挂载表四格式）才能接手。
- **整改建议**：判据快照的「应 M 值」可由测试/静态分析半自动核对（挂载表条目数 → M 的推导已写进文档，可脚本化生成对照，人工只填实测 N）；`docs/README.md` 加一页「十分钟接手指南」（判据怎么看 → 怎么跑 dev.sh → 怎么加一个 hook），把隐含上下文显性化。

---

## 二、保持项（审计中确认的优良实践，勿在重构中丢失）

| 项 | 证据 |
|---|---|
| root 命令全量白名单工厂，注入面收敛 | `ui/scope/RootShell.kt:125-156`（`SafeCommands`，KEY/VALUE 正则拒引号分号 `$`） |
| 重装后远端 prefs 残留漂移的主动修复 | `ui/FeatureStore.kt:65-82`（`pushAllToRemote`） |
| 挂载全链路防御：双层隔离 + PROTECTIVE + 回调留痕不吞语义 | `xposed/core/MountSession.kt:46-118, 242-252` |
| 契约由 JVM 测试钉死（N/M 口径 / 注册表与 scope 一致 / 注入面 / 版本比较） | `MountSummaryTest`、`RegistryConsistencyTest`、`SafeCommandsTest`、`VersionNameTest` 等 16 文件 |
| R8 具名 keep 的锁步风险论证与 `onPackageReady` 显式锁 | `app/app/proguard-rules.pro`（具名 keep 段，含历史事故记录） |
| AGPL 归因合规（submodule pin + 7 文件白名单 + NOTICE） | `README.md` 开源许可节、`libs/xhs-dn-core/build.gradle.kts` |
| CI secrets 签名注入、Xposed-Modules-Repo 幂等发布 | `.github/workflows/ci.yml`（master 段） |
| 主线程零同步磁盘 IO 的挂载调度不变量 | `MountSession.kt:52-63` 注释 + `MountScheduler` |

---

## 三、整改路线建议

1. **本周**：P0-2（一条 git rm + gitignore，半天）；P0-1 的哈希校验（发布侧 + 客户端各一处，约一天）。
2. **本迭代**：P1-1（UI 消费失败返回值）、P1-2（release job 加测试）、P1-3 的度量部分（先出基线文档再谈优化）。
3. **后续迭代**：P2 按目录分批：注释下沉（P2-1）跟随下一次触碰对应文件时顺路做，不单开 PR；其余 P2 各自独立小 PR。
4. **每项整改落地后**，在 `docs/hook-inventory-audit.md` §五 追加一行实测记录，保持判据台账的单一事实源地位。
