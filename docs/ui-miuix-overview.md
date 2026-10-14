# 宿主 UI（Miuix Compose）— 只记非代码可得的部分

> 界面结构、组件、开关清单、产物大小读代码（`ui/`、`FeatureCatalog.kt`）。此处只留**语义与取舍**。

## 开关下发通道（语义）

- 单一事实源 `top.honorhook.FeatureCatalog`：双端共用 key 字面量，**禁止任一侧手写字符串**。
- UI 写远端 prefs（group `honorhook_config`）→ hook 侧 `HookEntry` 注入时缓存 `getRemotePreferences` 一次，install 阶段本地查表（无重复 Binder）。
- **默认全开**：读不到 / 异常 / 未写入一律视为开；关某功能 = 显式写 false，且**下次目标进程注入才生效**（不是立刻）。
- **写失败必须可见**（2026-10-11）：`FeatureStore.setEnabled` 返回远端下发是否成功。框架未连接或写入抛异常时，
  一级列表顶部显示提示卡「LSPosed 未激活 —— 开关只会保存到本地，hook 侧仍按默认全开运行」。
  滑点在于：只写本地时 UI 状态照样翻转，用户会以为改生效了 —— 这属于必须有反馈的一类假状态。
- ~~**生效时机必须写在界面上**~~（2026-10-14 作废）：原二级功能页顶部「开关何时生效」提示卡已按用户要求全局删除
  （`SwitchSemanticsCard` 的 `restartHint` 分支移除，只保留「LSPosed 未激活」一种提示）。
  **语义仍成立**：开关写远端 prefs，目标进程下次注入才读到 ⇒ 改完仍需强停重开对应应用；
  设置页「重启目标应用」仍是该操作的入口，但功能页不再有文案提示。
- **UI 侧状态是 Compose 可观察镜像**（`FeatureStore.states`）：直接读 `SharedPreferences` 不是 snapshot state，
  会让「已启用 N/M」「待启用」停在旧值；所有读写统一走镜像，一处改动同时刷新二级开关与两级计数。
  （标签口径 2026-10-14 统一：详情页头部原「运行中」已改「已启用」，与一级列表一致。）

## 取舍（为什么这么选）

- 依赖对齐 HyperChanger（AGP 9.2.1 / Kotlin 2.3.21 / Miuix 0.9.4），避免 LSPFRIDA 的 Kotlin 2.4 工具链漂移。
- **激活判定**：只有收到 LSPosed 的 `XposedService` Binder 才算「已激活」（照 LSPFRIDA），不靠包名/文件探测。
- **液态玻璃**：`miuix-blur`（0.9.4 起提供 `Backdrop`/`blur`/`lens`）+ `io.github.kyant0:backdrop` 2.0.0 已引入，底栏走真·背景模糊；
  低版本/开关关闭时退回实色底栏（`MainActivity` 的 `enableBlurSdk` 守卫）。`miuix-blur` 声明 minSdk 33，靠 `tools:overrideLibrary` 覆盖到本项目的 minSdk 26。

## 压缩边界（2026-10-11 实测）

全模块 6624 行 / 41 个 kt：代码 5130、注释 906、空行 588。hook 侧 2805（注释占 22%）、UI 侧 3735（注释占 8%）。

**三类行不该压**：

1. **注释（906 行）= 唯一审计痕迹**。逆向实证（file:line）、"为什么这么选"、判据都只存在于注释里 —— 这是本工程"读代码得不到的信息"的全部载体，压掉就等于把可审计性清零。CLAUDE.md 的纪律条已写明。
2. **vendored 移植件（约 1300 行）**：`component/FloatingBottomBar.kt`、`component/liquid/*`、`component/InteractiveHighlight.kt`、`component/DampedDragAnimation.kt`、`component/DragGestureInspector.kt` 是 KernelSU / AndroidLiquidGlass 体系的上游实现原样移植（表头已注明来源并加了 `vendored` 标记）。改写会与上游脱钩，升级时无法逐行比对，收益为零。
3. **数据行**：43 个挂载点声明、16 条云控表项、13 个功能 key。这些是"表即真相"，DSL 化能少几行但更难核对，得不偿失。

**实测结论：可压空间约 2~4%（100~200 行）**，且大部分"看起来能压"的样板抽取（如 5 处 `Scaffold + TopAppBar + insets` 抽 `MiuixPage`）在计入 helper 自身后是**行数中性**的 —— 收益在一致性而非行数。已做的只有真垃圾清除（单成员 `MountKind` 枚举、嵌套 composable、重复图标）。**不要再为行数做大规模重构**；真要动，先看这里。
