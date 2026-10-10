# apk-reverse — 外部逆向知识库（vendored 副本，未维护）

> 位置：`.claude/skills/apk-reverse/`（作为项目 skill 入库，可被自动发现）。

## 这是什么

第三方 skill `apk-reverse`（v1.0，MIT，作者见 `NOTICE`）的**全量副本**（`references/` 46 篇 + `scripts/` 56 个 + `evidence/`）。
**收录原因**：该插件已废弃不更新，而其内容正好覆盖本项目的三个硬缺口（见下）。原件在插件目录，随时可能消失，故整份 vendor 进仓库。

## 未维护警告

- **原样复制，零改动**：不修 bug、不跟随上游、不保证在当前 macOS/新 Android 上仍可用。
- 上游口径写于 Linux/Windows 混合环境，路径与安装命令（`apt`、`C:\...`）需自行换算成本机的 `tools/` 与 `dev.sh`。
- `SKILL.md` = 上游主文件，里面的 `routing.md`（按需加载表）、`coverage-and-limits.md`（覆盖与边界）是这套资料的索引，**先读它再按需翻**。

## 对本项目最相关的几篇

| 我们的缺口 | 去哪看 |
|---|---|
| 「LSPosed 模块够不到哪一层」——三个放弃目标的通用判据 | `references/lsposed-and-modules.md`（含 Java/native/VMP-dex/Dart-AOT/服务端 的层归属总表） |
| 伪造会员态的两种失败模式（含**白屏**风险）、门归客户端还是服务端 | `references/membership-and-limits.md`、`references/server-api.md` |
| DEX 虚拟化双壳（wangxiao 走不下去的那条路） | `references/code-virtualization-and-custom-linkers.md`、`vmp-differential-analysis.md`、`advanced-unpacking.md`；脚本 `dex_mem_scan.py` + `dex_dump_validate.py` |
| Flutter / Dart AOT（s20cxq 类） | `references/dart-aot.md` |
| 落盘数据探查（DataStore/SP/SQLite/proto） | `references/runtime-data.md` |
| 失败目录（1290 行血泪清单，长任务前扫一眼） | `references/pitfalls.md`；纪律 `long-task-discipline.md`、`verification.md`、`patch-audit.md` |

> 本项目自己的方法论见 `docs/hook-craft.md`、`docs/reverse-craft.md`（更短、更贴本项目）；本目录是**深度参考**，冲突时以本项目文档与真机实证为准。

## 脚本怎么跑

- **纯标准库**（Python 3.9+），无 pip 依赖；`find_refs.py` / `dex_find_insn.py` / `dex_dump_validate.py` 依赖同目录的 `dexutil.py`，须整目录一起用。
- 环境自检：`python3 .claude/skills/apk-reverse/scripts/doctor.py --json`（逐项报告"这台机器能不能做 X、缺什么、装它大概多久"，允许 `BLOCKED`）。
- 常用：`dex_strings.py <dex|apk> --urls`（提 URL/字符串）、`find_refs.py`（字符串/类型/方法 → 所有引用点）、`probe_api.py`（服务端接口探测）、`dex_mem_scan.py`（内存里找 dex，对付抽取壳）。
- 反编译/挂钩仍走本项目入口：`tools/dev.sh decompile|jadx|aapt2`。
