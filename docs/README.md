# docs 索引

> **读法**：动手前先读两份 craft（怎么写 hook / 怎么逆向）→ 按目标读该 app 的**逆向记录** → 需要"为什么这么决策"再翻设计文档。
> 只留活跃文档；历史过程（重构/审计/参考项目分析/已放弃目标长文）已删 —— `git log --diff-filter=D` 可找回。
> **原则：不写"读代码就能得到"的内容**（类名清单、调用链、结构巡览、挂载点表）。方法论细节在项目 skill：`.claude/skills/`（已入库）。**例外：运行时实证行为（业务图谱/失败模式）与跨版本锚点登记表可入库——代码读不出。**

## 一、craft（最该读的两份）

| 文件 | 内容 |
|---|---|
| [`hook-craft.md`](hook-craft.md) | **怎么写好 hook**：选点原则与实证 · 四类"改了也没用"的死点（服务端字段 / null 实例 / 类内直读 / 休眠链）· 旁路与缓存 · 挂载基建与三阶段解析（DexKit 七条修正 / 宿主版本读取纪律）· 失败分级 · 防御与可观测 · 验证铁律 |
| [`reverse-craft.md`](reverse-craft.md) | **怎么逆向分析**：证据标准（file:line / 消费点数 / 可达性 / 版本对齐 / 设备实机优先）· 流程 · 定位手段 · 读法与兜底 · 版本适配 · 坑清单 |

## 二、按目标

| 内容 | 文件 |
|---|---|
| 目标总表（13 行 host 对）+ 能力边界 + 已放弃目标结论 + 各 app 逆向记录 | [`reverse/README.md`](reverse/README.md) |
| **壁纸管线**（wallpapereditor + livewallpaper.paradise 两宿主合一文档。§一–八 = 已放弃路线「视频单独设桌面」W1–W8 留档；§九–十一 = 现行 W16–W19 四点——转码码率 / 时间轴去限 / 桌面循环 / 隐藏暂停，2026-10-18 全验证） | [`reverse/wallpaper-editor.md`](reverse/wallpaper-editor.md) |
| 主题商店**设计**（需求 / 策略与理由 / 判据 / 未解决项） | [`theme-store.md`](theme-store.md) |
| 挂载点**口径、判据快照（M/N 单一事实源）、已决删除决策、勿删观察项、真机回归教训** | [`hook-inventory-audit.md`](hook-inventory-audit.md) |

## 三、模块自身

| 文件 | 内容 |
|---|---|
| **待办任务收件箱**（迭代候选 / 待验证 / 老 bug / 设备环境） | [`TODO.md`](TODO.md) |
| **变更清单 / 实施计划**（改动前先出清单，只改清单内的） | [`plans/2026-10-20-hook-core-hardening.md`](plans/2026-10-20-hook-core-hardening.md) |
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
