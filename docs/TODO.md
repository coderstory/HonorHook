# HonorHook 待办（2026-10-11 立档）

> 本文件是任务收件箱，不是设计文档。每项动工前先把「待定决策」问清/定清，
> 结论沉淀进 `docs/` 对应文件，本条目标记完成或删除。

## P1 · 双排信号（U3）——已废弃结案

代码全删（`MergedSignalHook.kt` / `SignalLevelParseTest.kt` /
`FeatureCatalog.KEY_SYSTEMUI_MERGE_SIGNAL` / `SystemUiHook` U3 分派），
结论与五轮真机根因链留档 `docs/reverse/systemui.md` §7.9。
若要重做：走数据源轨（`getPrimaryLevel()` StateFlow），别在渲染点硬刚。

## P2 · 通用 hook 版本机制框架

- **背景（实证）**：目标 app OTA 即失效——包管理器宿主从基线 `14.0.37.301`
  变成设备实际 `14.0.22.326`，11 个挂载点全废（`InstallerHook: installed 0/11`）。
  现状只有一个 `HOST_VERSION_BASELINE` 常量 + `MountEngine.checkHostVersionOnce`
  打 WARN，**没有任何分发能力**。
- **立场（用户方法论，CLAUDE.md「跨版本兼容」节）**：版本分发是**最后手段**，
  优先序 = 没混淆的目标 > 通用 hook 手法 > DexKit 内容锚点 > 才轮到版本分发。
  框架设计必须服务这个层级，不能让人觉得「先分版本再说」。
- **待定决策**：
  1. 匹配单位：精确 versionName 串相等，还是语义段（major.minor）范围？
  2. 分发粒度：`MountTask` 带版本条件（per-task），还是 hook 级声明多套挂载表？
  3. 宿主版本不匹配任何一套时的行为：整 hook 跳过（明确 NOT_RESOLVED）
     还是挑最近的一套硬挂？
  4. 汇总判据行 `installed N/M` 在按版本分发后如何保持可读（按版本分组输出？）。
- **下一步**：先出一版框架设计（小改可聊天说清），再动 `MountEngine`。

## P3 · 包管理器 hook 版本机制（2 个版本）

- **背景**：InstallerHook 实测 11 挂载点全废，根因 = 宿主 OTA
  （基线 `14.0.37.301` → 设备 `14.0.22.326`）。
  已知旧版结构：4 个可读点（`CommonConfigManager`、`AdRespV4.getAdAppList` 等）
  + 6 个混淆点（`lt1.b` / `sx1.Q` / `n12.f` / `r12.j` / `r12.s` / `ly1.h`）。
- **前置工作**（P2 框架定型后再做）：
  1. 反编译 `system-files/PackageInstaller-14.0.22.326.apk`（已 pull，未反编译）；
  2. 验证 `com.hihonor.packageinstaller` 前缀是否仍在（`PKG_HN` 写死的原因）；
  3. 4 个可读点在新版的真实包路径；
  4. 6 个混淆点的内容锚点字符串是否随版变化（不变 → 走 DexKit 锚点，
     根本不需要版本分发——先验证这个，可能直接省掉 P3 的一半）。
- **坑（已实证）**：
  - `tools/adapt_new_version.py` 的挂载点提取覆盖不全（只 glob `target/*Hook.kt`
    非递归、只认 3 种书写格式），**别拿它的 N/M 当覆盖度结论**，
    以 `docs/hook-inventory-audit.md` 台账为准；
  - `tools/` 下 apktool/jadx 曾被磁盘清理删过，反编译前先 `tools/dev.sh env` 自检；
  - `system-files/` 下 jadx 产物（systemui-jadx 等）已被删，需要时重新生成。

## P4 · 设置页「强制重启所有作用域目标 app」——已落地（2026-10-11）

代码：命令层 `ui/scope/ScopeCommand.kt` + 执行层 `ui/scope/ScopeRestarter.kt` +
UI `SettingsScreen.RestartScopeDialog`（Miuix `WindowDialog` 三态：勾选 → 执行中 → 结果）；
判据回归 `ScopeCommandTest`（10 例，CI 门禁内）。包名来源 `FeatureCatalog.targets`。

定死的决策（拷问结论，改代码前先读）：

- **只有 root 一条通道**：`Shizuku` 兜底已砍——Shizuku 13.x 把 `newProcess` 变成
  `private` 且标注「planned to be removed in API 14」（`Shizuku.java:491`），官方路径是
  `bindUserService`（要写 AIDL + Service）；而它唯一能覆盖的场景「root 在、本 app 未获授权」
  的正确解是引导去 KernelSU 管理器授权，不是再装一个 Shizuku。
- **su 路径候选链 `/system/bin/su` 打头**：KernelSU 的 su 兼容层只认这个字符串
  （`kernel/sucompat.c:20` `SU_PATH`），已授权 UID exec 它时内核才改写成 ksud 并提权；
  `/data/adb/ksu/bin/su` 不在 hook 命中范围，排后面只为兼容 Magisk/APatch。
- **不单独探测 su**：首次执行的 `ps -A -o NAME` 本身就是探测（少一次 fork，
  也不会出现「探测通过但执行失败」）。成功路径进程内缓存，5s 超时。
- **判据**：`am force-stop` 对没在跑的包同样返回 0 ⇒ 先取进程快照，结果分
  已停止 / 未运行 / 失败三类，汇总只报「已停止 N / 在跑 M」，与 `installed N/M` 同口径。
- **失败分类**：`NO_SU`（无 su）/ `NOT_GRANTED`（去 KernelSU 管理器允许 top.honorhook）/
  `TIMEOUT`——KernelSU 未授权是**快速失败**（内核不等用户点允许），所以没有长等待。
- **交互**：确认弹窗内 6 个目标可勾选（默认全选），launcher / systemui 带副作用说明
  （桌面重建、状态栏重建）；结果在弹窗原地呈现，含「开关改动在下次注入时生效」。

## P5 · 在线更新（检测 GitHub 最新 release）

- **目标**：模块内检测 GitHub 最新 release，引导更新。
- **用户已定的关键点**：**必须把 release 版本号写进 app 的版本号，否则无法对比**
  ⇒ 需要定 `versionName`/`versionCode` 与 GitHub release tag 的映射规则
  （如 tag `v1.2.3` ↔ versionName `1.2.3`，或 versionCode 对齐 release 序号）。
  发布流程也要对齐：CI（`.github/workflows/ci.yml`）master push 自动发 Release。
- **待定决策**：
  1. 仓库地址（需要确认真实 repo URL，勿猜）；
  2. 网络层：项目现有依赖能否直接用（避免引新库）；
  3. UI 落点：SettingsScreen 一行（当前版本 / 最新版本 / 下载入口）；
  4. 下载后安装：`REQUEST_INSTALL_PACKAGES` 权限 + FileProvider 的接线；
  5. 检查时机：进设置页时拉一次？频率限制？
