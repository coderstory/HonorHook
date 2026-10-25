# 挂载开销基线（P1-3 / T6，issue #11）

> 来源：[`plans/2026-10-25-audit-remediation.md`](plans/2026-10-25-audit-remediation.md) §T6。**度量先行，本任务不改代码**；后续「挂载线程 idle-quit / DexKit 生命周期」类优化决策，只认本表实测数字。

## 已知事实（2026-10-25 重审读证）

- `MountScheduler` object 首触达即起 `HonorHook-mount` HandlerThread：`scope.list` 全部 **24 个作用域包**各常驻一条。
- `DexKitHelper` 的 `DexKitBridge` 进程级缓存且刻意永不 close（注释明说「释放交给系统」）；**没有** `File#readBytes` 全量读内存。真问题：create 时 native 侧全 dex 索引峰值 + 索引常驻进程生命周期。

## 取数原语

`tools/dev.sh meminfo <包名> [包名...]`——进程须在运行；stdout 打 Native Heap / TOTAL PSS / 线程数，全量 `dumpsys meminfo` 落 `.buildtmp/meminfo_<pkg>_<时间戳>.txt` 归档。

## 注入前后对照流程（用户真机执行，每宿主一轮）

1. **基线（未注入）**：LSPosed 里停用 HonorHook → `tools/dev.sh relaunch <包名>` 冷启动 → 等约 10s → `tools/dev.sh meminfo <包名>`，记「未注入」列
2. **注入态**：LSPosed 里重新启用 HonorHook → `tools/dev.sh enable-module` → `tools/dev.sh relaunch <包名>` → `tools/dev.sh wait-hook`（等 installed N/M 判据出现）→ `tools/dev.sh meminfo <包名>`，记「注入后」列
3. 差值 = 模块在该宿主的常驻成本（**Native Heap 差值为主要判据**）

## 基线表（待实测回填）

| 宿主 | 包名 | 未注入 NH/PSS/线程 | 注入后 NH/PSS/线程 | Native Heap 差值 |
|---|---|---|---|---|
| 小红书（DexKit 密集） | com.xingin.xhs | 待测 | 待测 | — |
| 荣耀浏览器（DexKit 密集） | com.hihonor.browser | 待测 | 待测 | — |
| 荣耀音乐（DexKit 密集） | com.hihonor.cloudmusic | 待测 | 待测 | — |
| 其余 21 包 | 见 `tools/dev.sh scopes` | 余力补测 | 余力补测 | — |

## 阈值与后继

- 任一宿主 Native Heap 增量 **> 30MB** → 另立优化任务（含挂载线程 idle-quit、DexKit 生命周期评估），不在 T6 范围内动 `DexKitHelper`
- 全部低于阈值 → 本表归档为基线；宿主大版本升级后复测对照
