# HonorHook 待办（2026-10-11 立档）

> 本文件是任务收件箱，不是设计文档。每项动工前先把「待定决策」问清/定清，
> 结论沉淀进 `docs/` 对应文件，本条目标记完成或删除。

## P1 · 双排信号（U3）——已废弃结案

代码全删（`MergedSignalHook.kt` / `SignalLevelParseTest.kt` /
`FeatureCatalog.KEY_SYSTEMUI_MERGE_SIGNAL` / `SystemUiHook` U3 分派），
结论与五轮真机根因链留档 `docs/reverse/systemui.md` §7.9。
若要重做：走数据源轨（`getPrimaryLevel()` StateFlow），别在渲染点硬刚。

## P2 · 通用 hook 版本机制框架

- **背景（实证，方向别搞反）**：挂点是照**更新**的版本写的，设备跑的是**更旧**的版本——
  包管理器代码基线 `14.0.37.301` vs 设备 `14.0.22.326`（`InstallerHook: installed 0/11`）；
  主题商店代码基线 `20.1.63.305` vs 设备 `20.0.11.327`（`ThemeStoreHook: installed 4/43`）。
  适配 = 补一套**旧版**映射，不是追新版。完整版本对照见 `docs/hook-inventory-audit.md` §一。
  现状只有一个 `HOST_VERSION_BASELINE` 常量 + `MountEngine.checkHostVersionOnce`
  打 WARN，**没有任何分发能力**。
- **立场（用户方法论，CLAUDE.md「跨版本兼容」节）**：版本分发是**最后手段**，
  优先序 = 没混淆的目标 > 通用 hook 手法 > DexKit 内容锚点 > 才轮到版本分发。
  框架设计必须服务这个层级，不能让人觉得「先分版本再说」。
- **落地记录（2026-10-11，主题商店全量 43 点已迁入数据表）**：
  1. 匹配单位 → **精确 versionName 集合**（`HostVersion.Table.versions`；空集合 = 默认兜底套）。
  2. 分发粒度 → **hook 级多套挂载表**（每版一个 `<Target>Map_<版本>.kt` 数据文件），
     per-task 版本条件不做（噪声大、可读性差）。
  3. 不匹配行为 → 当前默认套 = 基线点（行为与迁移前一致）；一旦某版本出现专用表，
     未命中的版本仍落默认套——后续如需「无表即诚实跳过」，把默认套摘掉即可（一处改动）。
  4. 汇总行 → `installed N/M（宿主 <versionName>）`，版本错位一眼可见。
- **已交付件**：`xposed/data/HookMap.kt`（HookPoint 数据形态）、`xposed/core/HostVersion.kt`
  （resolve + pick）、`ThemeStoreMap_20_1_63_305.kt`（43 点基线表）、
  `ThemeStoreHook` 读表接入（feature 过滤 + `toMountTask` 桥）。
  迁移保真由一次性等价性单测逐字段验证后，collect* 源与测试一同删除。
  表生成器 `gen_hook_map.py` 已随 collect 源删除——**数据表即唯一事实源，直接手改表**。
- **真机回归（已完成）**：`installed 4/43（宿主 20.0.11.327）`，选表器 WARN / 失败分级 /
  重试 2 轮 / 汇总行全部按设计输出；4 成功点构成已查实（T1 真命中 + A11×3 DexKit 兜底
  误打误撞，见 `docs/hook-inventory-audit.md` §一）。
- **决策（2026-10-11）**：**不做旧版适配**。设备侧升级主题商店至基线 `20.1.63.305` 即全量生效；
  P3 包管理器旧版映射同批废弃。框架保留（HostVersion 选表 + 数据表），未来若要适配其他
  版本 = 反编译 → 手写 `<Target>Map_<版本>.kt` → 注册 `versionTables` 精确套。

## P3 · 包管理器 hook 版本机制（2 个版本）——已废弃结案（2026-10-11）

决策同 P2：不做旧版适配，设备升级宿主至基线 `14.0.37.301`。此前反编译/锚点验证的
前置工作不再执行。若未来重启：`tools/adapt_new_version.py <新版APK>` 可一键校验
挂载点存活（提取器已支持数据表 HookPoint 与全部源码格式，68 点全绿）。

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

## P5 · 在线更新（检测 GitHub 最新 release）——已落地（2026-10-11）

- **版本对比契约（关键）**：CI tag_name = `v<versionName>.r<run_number>`，run_number 经
  `-PbuildNum=${{ github.run_number }}` 注入 `BuildConfig.BUILD_NUM`（本地构建 = 0）。
  对比规则 = semver 三段逐段比，相等再比构建号（`UpdateChecker.isNewer`）。
- **决策落定**：仓库 `coderstory/HonorHook`；网络层零依赖（`HttpURLConnection`，不引 OkHttp）；
  检查时机 = MainActivity 打开时静默查一次（失败无感）+ 设置页手动触发。
- **代码**：`ui/update/UpdateChecker.kt`（解析/对比/API/状态）、`ui/update/ApkInstaller.kt`
  （流式下载 + FileProvider + 系统安装器跳转 + 未知应用授权引导）、`ui/update/UpdateDialog.kt`
  （确认 → 下载进度 → 安装/失败四态）；设置页第三组 Card「检查更新」行实时反映远端状态。
- **权限**：manifest 补 `REQUEST_INSTALL_PACKAGES` + FileProvider（`@xml/file_paths` 仅暴露
  `cacheDir/update/`）。
- **回归**：`UpdateCheckerTest`（parseTag 契约 + isNewer 表驱动）。
