# 审计整改 + 彩蛋清理 开发计划（2026-10-25）

> **来源**：审计意见书 [`docs/audit-remediation-2026-10-25.md`](../audit-remediation-2026-10-25.md)（dev @ 4c65cbd）+ 同日逐条重审。
> **与意见书冲突处以本计划为准**——重审纠偏已合入各条目（P0-2「有意 track」认定错误、P1-1「无任何提示路径」不成立、P2-4 exam8「显示态」推断方向错、P2-2 脱敏方案否决等，证据见各任务「纠偏」行）。
> **状态跟踪在本文件**（重审否决了往 `docs/hook-inventory-audit.md` §五 判据快照塞整改记录——该台账只记 N/M 实测）。

**Goal：** 清掉 2×P0 + 3×P1，P2 分批；顺带删除面板双彩蛋（用户 2026-10-25 拍板）。

**执行纪律**（AGENTS.md）：每任务独立 commit，改前出变更清单、只改清单内的；构建 `tools/dev.sh build`、测试 `tools/dev.sh test`（后台 shell 复用，不双开）；禁 reboot，真机验证项留清单给用户自执行。

---

## 排期总览

| # | 任务 | 来源 | 批次 | 工作量 | 状态 |
|---|---|---|---|---|---|
| T1 | build 产物全量出库（506 文件，含白名单产物） | P0-2 + 重审纠偏 | 本周 | 0.5 天 | 待开始 |
| T2 | 删除面板双彩蛋（FFF 文字 / 状态卡变红） | 用户新增 2026-10-25 | 本周 | 0.5 天 | 待开始 |
| T3 | 更新链路 SHA-256 校验 + 直连优先 | P0-1 | 本周 | 1 天 | 待开始 |
| T4 | 开关下发失败的行内反馈 | P1-1 + 重审纠偏 | 本迭代 | 1 天 | 待开始 |
| T5 | master 发布链路补测试门禁 | P1-2 | 本迭代 | 0.5 天 | 待开始 |
| T6 | 挂载线程 / DexKit 内存度量基线 | P1-3 + 重审读证 | 本迭代 | 1 天 | 待开始 |
| T7 | MmsHook / xhs 注释下沉 | P2-1 | 后续·顺路 | — | 待开始 |
| T8 | xsec_token 日志挂 debug 门控 | P2-2 + 重审纠偏 | 后续 | 0.5 天 | 待开始 |
| T9 | 未知 feature key 断言测试 | P2-3 | 后续 | 0.5 天 | 待开始 |
| T10 | 安全类功能分组标注 + exam8 表述纠正 | P2-4 + 重审纠偏 | 后续 | 0.5 天 | 待开始 |
| T11 | 接手指南 + 包数漂移清理 | P2-5 + 重审实证 | 后续 | 1 天 | 待开始 |

---

## T1：build 产物全量出库（P0-2）

**背景**：`git ls-files` 含 506 个 `build/` 文件，**全部**在 `libs/xhs-dn-core/build/` 下（`.gitignore` 已有 `app/app/build/`，无需动）。

**纠偏**：意见书认定 `generated/xhsdn` 7 文件「有意 track」——**错误**。`libs/xhs-dn-core/build.gradle.kts:33` 注释明写「同步白名单文件到 build/generated（**构建产物，不入库**）」，且 AGENTS.md 声明「本仓不存上游任何源码」；当前 git status 6/7 个文件常年 `M` 态即是反证。**全清即可，无需保留、无需评估**。

**变更清单**：
- [ ] `git rm -r --cached libs/xhs-dn-core/build`（506 文件全清，含 `generated/xhsdn` 7 文件；`--cached` 只出索引，本地文件由 `syncXhsDnUpstream` 任务从 submodule 重建）
- [ ] `.gitignore`：`libs/xhs-dn-core/build/.transforms/` 单条规则改为 `libs/xhs-dn-core/build/`（防再犯；gitignore 对已 track 文件无效，故必须配合上面的 rm）
- [ ] 核对 AGENTS.md「本仓不存上游任何源码」声明恢复成立

**验收**：干净 checkout 后连续两次 `tools/dev.sh build`，`git status` 保持 clean。

**注意**：submodule pin（`d5c0070` / 1.4.1）不动；AGPL 归因靠 submodule pin + 白名单 + NOTICE，不靠 track 副本。

## T2：删除面板双彩蛋（用户新增）

**背景**：`ui/Glitch.kt`——每次打开 App 摇一次骰子，两效果各 1% 且可同中：`textBroken` 命中时**全 UI 文字变 "FFF"**（`Glitch.text()` 包装，170 处调用面）；`cardRed` 命中时**首页 LSPosed 状态卡变鲜红**（含文案「老色批已爆炸」，`HomeScreen.kt:126/:194`）。与「文案是给用户看的」铁律相悖，用户拍板删除。

**行为等价性**：非命中态 `Glitch.text` 是恒等函数、`cardRed` 分支不命中即落后续分支——删除后 UI 显示零变化（彩蛋本身除外），属行为等价删除，可放心机械执行。

**变更清单**：
- [ ] 删除 `app/app/src/main/kotlin/top/honorhook/ui/Glitch.kt`（整个 object）
- [ ] `MainActivity.kt`：删 `:75` `Glitch.roll()` 及其注释行；解包 `:135/:136/:138/:139` 底栏 label
- [ ] `HomeScreen.kt`：**删两个 when 分支** `:126`（`Glitch.cardRed -> Glitch.text("老色批已爆炸")`）与 `:194`（`Glitch.cardRed -> Color(0xFFFF1744)`）；解包其余 `Glitch.text`
- [ ] 解包 8 文件全部 `Glitch.text(X)` → `X`：FeaturesScreen(26)、ToolboxScreen(40)、ThemeSettingsScreen(26)、SettingsScreen(38)、UpdateDialog(15)、UpdateChecker(2)、MiuixBackButton(1)、ApkInstaller(1)；删 9 条 `import top.honorhook.ui.Glitch`
- [ ] **还原 10 处注释残迹**（当初机械包装误伤注释内字符串——不是解包，是改回正常文字）：`FeatureStore.kt:63`、`SettingsScreen.kt:74/:368`、`ThemeSettingsScreen.kt:78`、`HomeScreen.kt:306`、`ApkInstaller.kt:142`、`FeaturesScreen.kt:166/:324/:701/:769`

**验收**：`grep -rn "Glitch" app/app/src/main/` 零命中；`tools/dev.sh build` + `tools/dev.sh test` 通过。

**注意**：解包是纯机械替换，**不顺手改任何文案/重构**（变更清单纪律）；注释残迹逐处人工还原，别对注释跑 sed。

## T3：更新链路 SHA-256 校验 + 直连优先（P0-1）

**背景**：`ApkInstaller.kt:25-32` 五镜像 + 直连；`download()` 后直进 `install()`（:156-175），全程无校验；`probeSources` 赛马事实上偏好镜像（CDN 握手普遍快于直连）。

**纠偏**：意见书「首次安装的用户没有任何防护」主语错位——应用内更新器只服务已装用户；真实无防护场景 = **异包名恶意 APK 作为新应用安装**（对所有应用内更新成立；同包名覆盖安装受签名校验保护）+ 坏镜像 DoS。元数据（tag/apkUrl）来自 `api.github.com` 直连，可信源与不可信下载面已分离——哈希走 API 主源的方案架构成立。

**变更清单**：
- [ ] `UpdateChecker.kt`：`Release` 增加 `sha256` 字段；`fetchLatest()` 解析 asset 的 `digest` 字段（GitHub API 已返回 `sha256:...` 格式，对存量资产同样生效，无需依赖 release notes 文案）
- [ ] `ApkInstaller.kt`：`download()` 成功后校验 SHA-256，不匹配删文件并抛错（不进 `install()`）；校验对镜像路径同样生效
- [ ] `UpdateState` / `UpdateDialog.kt`：校验失败走既有 notice/错误路径，文案写明「校验失败，已删除下载文件」
- [ ] `probeSources` 改直连优先：直连握手成功即用直连，镜像仅在直连失败后逐个降级（重审：这是行为反转，不是小补丁）

**验收**：意见书验收保留——篡改一字节的下载文件 → 拒绝安装并提示校验失败；镜像路径下同样生效。

**决策（2026-10-26 拍板）**：fail-closed——API 响应缺 `digest` 字段时拒绝下载安装并明确报错。理由：更新器只服务已装用户，fail-open 等于给「镜像接管」留静默后门，与 P0-1 整改初衷相悖。

## T4：开关下发失败的行内反馈（P1-1）

**背景**：`FeaturesScreen.kt:681` 丢弃 `setEnabled` 返回值；`FeatureStore.kt:56-57` 注释承诺「UI 必须如实告知用户」未兑现。

**纠偏**：意见书「无任何提示路径」**不成立**——`SwitchSemanticsCard`（`FeaturesScreen.kt:165/:611`）已覆盖「框架未激活」场景（常驻提示「LSPosed 没连上，开关改不动」）。真缺口是：① 框架已连但**写入异常**无提示；② **开关本体不回滚**（`states` 先写、视觉已变，与 banner「改不动」自相矛盾）。本任务只补这两点，不动 `SwitchSemanticsCard`。

**变更清单**：
- [ ] `FeaturesScreen.kt`：`onCheckedChange` 消费返回值，失败时该开关行内标注「未生效：下发失败」
- [ ] 开关态（2026-10-26 已定）：**失败回滚视觉态** + 行内标注「未生效：下发失败」——回滚后开关位置如实反映 hook 侧仍为旧值，与「UI 必须如实告知用户」及现有 banner「开关改不动」语义一致
- [ ] `FeatureStore.kt`：`:100/:110` 日志文案「保持目录默认」→「保持上次远端值」（重审：下发失败时 hook 侧保持的是上次远端值，非目录默认；UI 提示语同源修正）

**验收**（重审修正版）：mock 写入异常（框架在、`apply` 抛）→ 行内出现失败标注且开关态如实；框架未激活场景维持现有 banner 行为不变。

## T5：master 发布链路补测试门禁（P1-2）

**背景**：`ci.yml:61-63` `Run tests` 仅 `if: github.ref_name == 'dev'`；master 的 release 构建 → 发 Release → 同步 Xposed-Modules-Repo 全程零测试。

**变更清单**：
- [ ] `ci.yml`：master 段在 `Build release` **之前**加 `./app/gradlew -p app testDebugUnitTest`
- [ ] dev 段 `test` 收敛为 `testDebugUnitTest`（与 `tools/dev.sh test` 口径对齐，缩短门禁）

**验收**：意见书验收保留——构造必挂测试推 master，CI 红且不发 Release（发布步骤在测试之后，天然阻断）。

## T6：挂载线程 / DexKit 内存度量基线（P1-3）

**背景**：`MountScheduler.kt:9` object 首触达即起 `HonorHook-mount` HandlerThread，**全部注入进程**（`scope.list` 实际 **24 包**，意见书写 23 属笔误）各常驻一条。

**重审读证**（意见书标「待验证」，已读证关闭）：`DexKitHelper` 的 `DexKitBridge` **进程级缓存且刻意永不 close**（`DexKitHelper.kt:19-21/:47-48` 注释明说「释放交给系统」）；**没有** `File#readBytes` 全量读内存（意见书整改建议 2 的假设不成立）——真问题是 create 时 native 侧全 dex 索引峰值 + 索引常驻进程生命周期。

**变更清单**（度量先行，本任务不改代码）：
- [ ] 真机各宿主进程注入前后 `dumpsys meminfo` 对照（Native Heap / 总 PSS / 线程数），重点 DexKit 密集宿主（xhs / browser / cloudmusic）
- [ ] 产出 `docs/perf-baseline.md`（新文件；不塞 `hook-inventory-audit.md` §五）
- [ ] 若 Native Heap 增量超阈值（建议 30MB 起谈），另立优化任务（含挂载线程 idle-quit 评估）——不在本任务内动 `DexKitHelper`

**验收**：有书面基线文档；后续优化决策有数据可依。

## T7：MmsHook / xhs 注释下沉（P2-1，顺路原则）

**背景**：`MmsHook.kt:15-92` 约 75 行叙述性注释（数据链 / Honor-HW 分支 / 布局对比）与 `docs/reverse/sms-ads.md:62` 双处存在，漂移即失实——本项目判据体系建立在实证记录可信上。

**原则**：**搬移 + 留指针，不删除**（AGENTS.md「注释是唯一的审计痕迹」与「长篇进 docs/」的张力以此化解）；以「叙述性段落」为界——讲数据流/分支史的搬走，讲约束/判据/为什么这么写的留下。

**变更清单**：下次触碰 `MmsHook.kt` / xhs 目录时顺路做，不单开 PR；源码留「结论 + 指针 + 行号锚点」。

> 2026-10-26 执行说明：用户指令「处理全部」——顺路件提前入本批执行：xhs 半边随 T8 同会话落地，MmsHook 半边独立 commit。仍不单独成 PR，与顺路原则的成本意图一致。

## T8：xsec_token 日志挂 debug 门控（P2-2）

**背景**：`XhsBatchSave.kt:847-848` 全量 URL（含 `xsec_token`）写入模块日志，无门控。

**纠偏**：意见书首选「前后 4 位脱敏足够 curl 比对」**不成立**——token 是完整凭证，脱敏后无法重组可 curl 的 URL，照做会废掉 :847 注释声明的诊断用途（PC 端 curl 比对）。**采用其第二方案：挂 `MountEngine.DEBUG_LOGGING` 门控**。

**变更清单**：
- [ ] `XhsBatchSave.kt:847-848`：该日志移入 `DEBUG_LOGGING` 门控内；门控关闭时打脱敏版（URL path + token 前 4 位）或不打

**验收**：`tools/dev.sh modlog-grep` 常态（非 debug）抓不到完整 token。

## T9：未知 feature key 断言测试（P2-3）

**背景**：`RemoteConfig.kt:39` `catalogDefaults[key] ?: false`——未知 key 静默回「关」，与「读不到回目录默认」哲学相反。现网 key 全走 `FeatureCatalog.KEY_*` 常量，风险低故 P2。

**变更清单**：
- [ ] 新增 JVM 测试：遍历全部 `TargetHook` 挂载表引用的 feature key，断言 ⊆ `FeatureCatalog` 目录（基建沿用 `RegistryConsistencyTest` 的反射遍历模式）

**验收**：测试入 `tools/dev.sh test` 门禁；故意写一个错 key 能红。

## T10：安全类功能分组标注 + exam8 表述纠正（P2-4）

**背景**：安全类功能随安装即默认生效，README 功能表平铺无警示分组。

**纠偏**（两处，意见书原文有误）：
1. 意见书漏了 **`ota.block`（系统更新阻断）默认开**——阻断安全补丁，影响面不小于已列三项，一并入清单；
2. exam8「从 README 表述看是显示态」**方向性错误**——`Exam8Hook` 拦 `getVipType`/`getIsValid`/`hasPrivilege` 等整组 getter（含阅后即焚特权门），是**本地会员判定门控置真**。照意见书写「只改 UI 展示」会制造不实合规声明（阅后即焚是真实功能解锁）。正确表述：「本地会员判定置真，解锁本地特权门；不与服务端交互、不触碰真实账户/计费」。

**变更清单**：
- [ ] 功能页（`FeaturesScreen.kt` / `strings.xml`）：安全类功能（installer 三项 / sysmgr 病毒扫描 / ota.block）单独分组 + 显著标注默认开启与影响
- [ ] README 功能表：安全类功能补标注；`:66` exam8 行「显示成超级会员」同步纠正
- [ ] 免责声明落为逐功能核实的「本机内存态修改」清单（前提：逐功能核实成立）

## T11：接手指南 + 包数漂移清理（P2-5）

**背景**：bus factor≈1（351 commit 中 95.4% 单人）；判据快照纯人工维护。

**重审实证**（P2-5 最佳佐证，应收编）：同一个「作用域包数」四处四个值——AGENTS.md「22 个作用域包/20 TargetHook」、意见书「23 个包」、`RegistryConsistencyTest` 注释「实测 18 行」、`scope.list` 实际 **24 包 / 22 实现类 / 33 注册实例**。

**变更清单**：
- [ ] `docs/README.md` 加「十分钟接手指南」（判据怎么看 → 怎么跑 dev.sh → 怎么加一个 hook）
- [ ] 修三处过时数字：AGENTS.md「22/20」、`RegistryConsistencyTest` 注释「18 行」、判据快照口径说明——以 `RegistryConsistencyTest` 动态断言为准（24 包）；意见书已发表不再改，本计划开头已纠偏
- [ ] 判据快照「应 M 值」脚本化对照（挂载表条目数 → M 推导，人工只填实测 N）——评估成本后可降级为手抄

---

## 完成后回写

- 本文件排期总览状态列更新 + 各任务验收记录一行
- 涉及 N/M 口径的改动（若有）才进 `docs/hook-inventory-audit.md` §五；纯整改项不进
