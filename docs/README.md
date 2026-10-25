# docs 索引

> **读法**：动手前先读两份 craft（怎么写 hook / 怎么逆向）→ 按目标读该 app 的**逆向记录** → 需要"为什么这么决策"再翻设计文档。
> 只留活跃文档；历史过程（重构/审计/参考项目分析/已放弃目标长文）已删 —— `git log --diff-filter=D` 可找回。
> **原则：不写"读代码就能得到"的内容**（类名清单、调用链、结构巡览、挂载点表）。方法论细节在项目 skill：`.claude/skills/`（已入库）。**例外：运行时实证行为（业务图谱/失败模式）与跨版本锚点登记表可入库——代码读不出。**
> **新接手/新会话？先走 §六「十分钟接手指南」**——判据怎么看 → 工具怎么跑 → hook 怎么加。

## 一、craft（最该读的两份）

| 文件 | 内容 |
|---|---|
| [`hook-craft.md`](hook-craft.md) | **怎么写好 hook**：选点原则与实证 · 四类"改了也没用"的死点（服务端字段 / null 实例 / 类内直读 / 休眠链）· 旁路与缓存 · 挂载基建与三阶段解析（DexKit 七条修正 / 宿主版本读取纪律）· 失败分级 · 防御与可观测 · 验证铁律 |
| [`reverse-craft.md`](reverse-craft.md) | **怎么逆向分析**：证据标准（file:line / 消费点数 / 可达性 / 版本对齐 / 设备实机优先）· 流程 · 定位手段 · 读法与兜底 · 版本适配 · 坑清单 |

## 二、按目标

| 内容 | 文件 |
|---|---|
| 目标总表（13 行 host 对）+ 能力边界 + 已放弃目标结论 + 各 app 逆向记录 | [`reverse/README.md`](reverse/README.md) |
| **2026-10-24 全系统调研交接**（新会话入口：设备铁律/三层法/三轮调研成果/开发路线/未竟清单。62 份报告在 `research/2026-10-24-system-audit/`——含 13 份拿走即写码规格书：22 融合状态栏 / 26 胶囊注入 / 28 工具箱 / 51 相机电影模式 / 52 OTA 拦截 / 53 应用市场 / 54 pushagent / 55 通知 / 56 桌面网关 / 57 负一屏 / 58 锁屏时钟 / 61 hiview / 43 验证剧本） | [`research/HANDOFF-2026-10-24.md`](research/HANDOFF-2026-10-24.md) |
| **壁纸管线**（wallpapereditor + livewallpaper.paradise 两宿主合一文档。§一–八 = 已放弃路线「视频单独设桌面」W1–W8 留档；§九–十一 = 现行 W16–W19 四点——转码码率 / 时间轴去限 / 桌面循环 / 隐藏暂停，2026-10-18 全验证） | [`reverse/wallpaper-editor.md`](reverse/wallpaper-editor.md) |
| 主题商店**设计**（需求 / 策略与理由 / 判据 / 未解决项） | [`theme-store.md`](theme-store.md) |
| 挂载点**口径、判据快照（M/N 单一事实源）、已决删除决策、勿删观察项、真机回归教训** | [`hook-inventory-audit.md`](hook-inventory-audit.md) |

## 三、模块自身

| 文件 | 内容 |
|---|---|
| **待办任务收件箱**（迭代候选 / 待验证 / 老 bug / 设备环境） | [`TODO.md`](TODO.md) |
| **变更清单 / 实施计划**（改动前先出清单，只改清单内的） | [`plans/2026-10-20-hook-core-hardening.md`](plans/2026-10-20-hook-core-hardening.md) |
| **审计整改 + 彩蛋清理计划**（2026-10-25 意见书 2×P0+3×P1+5×P2 的执行清单，含重审纠偏——与意见书冲突处以本计划为准；状态跟踪在计划内） | [`plans/2026-10-25-audit-remediation.md`](plans/2026-10-25-audit-remediation.md) |
| [`ui-miuix-overview.md`](ui-miuix-overview.md) | 宿主 UI（Miuix Compose）结构与开关下发通道 |
| [`hook-philosophy-manifesto.md`](hook-philosophy-manifesto.md) | hook 设计哲学（哲学表述版 + 工程条款版，七条原则与审计应用） |

## 四、外部知识库（已 vendor 成项目 skill）

| 位置 | 内容 |
|---|---|
| [`.claude/skills/apk-reverse/`](../.claude/skills/apk-reverse/) | 第三方 skill `apk-reverse` 全量副本（MIT · **上游已废弃不更新** · 原样未改 · 作为项目 skill 入库，**会被自动发现**）：117 个文件（44 篇参考 + 54 个脚本 + 证据清单）里的深度逆向路线，覆盖「LSPosed 够不到哪一层」「服务端门 vs 客户端门」「DEX 虚拟化双壳 / Dart AOT / 落盘探查」等本项目缺口。索引见其 `README.md`，用法与冲突处理也在那里 |

## 五、不在 docs/

- 协作纪律与铁律：根 `CLAUDE.md`；功能台账与验证清单：根 `README.md`
- 命令与工具路径：`tools/dev.sh help` / `tools/dev.sh env`，索引见 skill `honorhook-dev`
- 方法论详证（smali/jadx 用法、样本分析、脚手架）：各 skill 的 `SKILL.md` 与 `references/`

## 六、十分钟接手指南（2026-10-26，P2-5/T11）

> 给新会话/新接手者的最短路线，细节都在链接文档里。为什么有这页：判据体系（N/M 口径、三阶段解析、挂载表四格式）是自创概念体系，此前只能通读 AGENTS.md + 全部 docs 才能上手。

### ① 判据怎么看（5 分钟建立世界观）

- hook 有效性判据**只有一条**：`installed N/M`，来自 **LSPosed 框架日志** `/data/adb/lspd/log/modules_*.log`（**不在 logcat**）。M = 本次注入尝试的挂载点数，N = 成功数。
- 逐 hook 的应 M / 最近实测 N 只看一份：[`hook-inventory-audit.md`](hook-inventory-audit.md) §五 判据快照。**N 明显小于 M = 宿主版本错位或选点失效，先别改 hook 实现**（§四 有真机回归教训清单）。
- 作用域包数 = **24**（scope.list 全量；`RegistryConsistencyTest` 动态断言钉死，引用包数以它为准）。
- 功能开关默认全开、跨进程远端 prefs 下发、**下次注入才生效**——改开关后要强停宿主再看效果。
- 覆盖安装后 LSPosed 会停用模块：跑 `tools/dev.sh enable-module`。

### ② 工具怎么跑（2 分钟）

- 一切命令走 `tools/dev.sh`：`help` 看全量、`env` 看工具链解析。常用：`build` / `test` / `modlog-grep 'installed'` / `relaunch <pkg>` / `hook-test` / `meminfo <pkg>`（挂载内存基线，[`perf-baseline.md`](perf-baseline.md)）。
- 裸 shell 里没有 java——定向 gradle 任务走 `tools/dev.sh exec ./app/gradlew -p app <task>`。
- 设备铁律：**禁 reboot**；破坏性操作先问；AI 交付 = 代码 + 构建通过 + 用户自执行的验证清单。

### ③ hook 怎么加（3 分钟路线图）

1. **逆向**：读 [`reverse-craft.md`](reverse-craft.md)（证据标准：file:line / 消费点数 / 可达性 / 设备实机优先），产出落 `docs/reverse/<app>.md`。
2. **写码**：读 [`hook-craft.md`](hook-craft.md)（选点原则 / 三阶段解析 / 失败分级 / 防御）。新建 `xposed/target/<Target>/<Target>Hook.kt` 挂载表 → `TargetRegistry` 加一行 → `scope.list` 声明包名 → `FeatureCatalog.Target` 加一项（**四点同步**，`HookEntry` 零改动；`RegistryConsistencyTest` 钉注册契约，`FeatureKeyCoverageTest` 钉 feature key 目录）。
3. **验证**：`build` → 装机 → `enable-module` → `relaunch <pkg>` → `modlog-grep 'installed'` 对照 §五 判据快照 → **改完 hook 回来更新快照表**（M 随代码、N 随宿主版本）。
