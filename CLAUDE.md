# LSPosed 荣耀 Hook 模块 — 项目协作指南

> 本文件是项目级 AI 协作指南。在此仓库做任何开发前必读。

## 一、项目定位

基于 **LSPosed** 框架与 **miuix KMP** UI 框架开发的安卓 Xposed 模块，目标是对**荣耀（Honor）安卓系统**进行 hook 定制。模块以标准 APK 形式产出，经 LSPosed Manager 激活后注入目标系统进程。

## 二、目录结构

| 目录 | 用途 | 当前状态 |
|------|------|----------|
| `app/` | 开发目标 app（Gradle 工程根，模块在 app/app/，含 src/META-INF/产物） | 工程主体完成 + 首次构建通过 |
| `reference/` | 参考项目（4 个子项目） | 含完整源码，只读 |
| `system-files/` | 荣耀系统资源，hook 研究对象，内部资源需处理后才能静态分析 | Rain 逆向产物（apktool/jadx/xposed 配置）+ `rain-strings-decrypted.md`（43 处解密串全还原：SandHook/签名绕过等）；荣耀系统资源待设备连接拉取 |
| `tools/` | 依赖软件（全部用户级便携，无需管理员安装） | `platform-tools/`（adb）、`apktool.jar`、`jadx/`、`rain-decrypt.py`（Rain 字符串批量解密脚本） |
| `docs/` | 调研/逆向分析报告留档 | `rain-analysis.md`、`ui-template-research.md`、`ai-lsposed-temlate-report.md`、`skill-audit-fixes.md`（skill 修正记录 27 条）、`rain-hook-architecture.md`（Rain 分发链/DexKit 双轨/字符串解密破解）、`hc-hook-structure.md`（HC 入口分发/UI↔hook 远程 prefs 配置通信链路） |

**路径全英文铁律**：所有目录用英文名。中文路径在 aapt2 / NDK / Windows GBK 终端 / git 下会踩坑（编译报错、输出乱码、路径转义）。本项目已从中文目录名改为英文，后续新增目录一律英文。

### reference/ 子项目（只读，勿改）

| 子目录 | 类型 | 简介 |
|--------|------|------|
| `LSPosed（框架源码）` | 框架源码 | LSPosed 框架本身（多模块） |
| `LSPFRIDA（同类插件）` | LSPosed 插件 | App 内跑 Frida 脚本，miuix 用得最深 |
| `HyperChanger（同类插件）` | LSPosed 插件 | HyperOS 系统 UI 定制 |
| `notification-guard（同类插件）` | LSPosed 插件 | 通知渠道拦截，miuix 最轻量 |
| `Rain_1.1.5.apk` | LSPosed 插件（高混淆样本） | hook 20 个国产 app，libxposed 102 + DexKit + miuix UI（即本项目 UI 参考来源）；逆向分析见 `docs/rain-analysis.md` |
| `fanqie-purify（AI开发插件实践）` | LSPosed 插件（方法论范本） | 番茄畅听净化；MainHook 注释含完整 hook 方法论与弯路记录 + REVERSE_ENGINEERING 逆向报告 + 自研 dex 工具 |
| `AI-lsposed-temlate` | 工程模板（AI 辅助开发） | libxposed API 101 最小骨架；`.cursor/` 两份 AI 规则文档（API 速查 + Classic API 禁用清单）值得吸收；见 `docs/ai-lsposed-temlate-report.md` |
| `skills/` | android-skills 插件源 | Google 官方 Android 最佳实践 skill 库，已装为 Claude Code 插件（marketplace 源指向此目录） |

## 三、环境约束（重要前提）

1. **无 Windows 系统管理员权限**，理论上开发流程也不需要。
   - 所有工具链**用户级便携安装**，放 `tools/`，用绝对路径或用户级环境变量调用，不依赖系统级安装、不改系统 PATH。
   - Gradle wrapper 自下载 Gradle 到用户级 `~/.gradle`，依赖走 Maven/Google Maven，**不依赖管理员权限**。JDK 用用户级便携版（待确认本机现状）。
   - 任何"需要管理员才能装/跑"的方案直接排除，换用户级替代。
2. **有 Android 系统 root 权限**。
   - 测试设备已 root（Magisk/KernelSU），LSPosed 可正常激活。
   - 荣耀系统资源已由用户置于设备 `/sdcard/` 下，`adb pull /sdcard/<目录>` 即可拉取，无需 `adb root`。
3. **仅真机直连测试，无法安装模拟器**。
   - 本机不能装 Android 模拟器（AVD 等），所有测试在真机 USB 直连进行。
   - 禁用 `connectedAndroidTest` / emulator smoke 等依赖模拟器的测试任务；参考项目 `notification-guard` 的 `ci/emulator-smoke.sh` 不适用本机。

## 四、技术栈基准（开发 app 时以此为准）

来源：reference 调研。三个同类插件技术选型高度一致，本 app 跟随。

| 维度 | 选型 |
|------|------|
| LSPosed API | `io.github.libxposed:api:102.0.0`（compileOnly）+ `io.github.libxposed:service:102.0.0` |
| Hook 入口注册 | `src/main/resources/META-INF/xposed/java_init.list` + `module.prop`（新版规范，**非** `assets/xposed_init`） |
| Hook 基类 | `io.github.libxposed.api.XposedModule`（**非** `IXposedHookLoadPackage`） |
| UI 框架 | Jetpack Compose + miuix KMP（`top.yukonga.miuix.kmp:*`，Maven Central） |
| 主语言 | Kotlin（JVM target 17） |
| UI 范式 | 单 Activity + Compose（setContent / Navigation），MiuixTheme 包根 |
| 构建产物 | 标准 APK（非 Magisk zip） |

参考插件版本速查：

| 项目 | Gradle | AGP | Kotlin | compileSdk | minSdk | targetSdk | miuix |
|------|--------|-----|--------|------------|--------|-----------|-------|
| LSPFRIDA | 9.5.0 | 9.3.1 | 2.4.10 | 37 | 26 | 34 | 0.9.4-rc01 |
| HyperChanger | 9.6.1 | 9.2.1 | 2.3.21 | 37 | 35 | 37 | 0.9.3 |
| notification-guard | — | 8.13.2 | 2.3.20 | 36 | 31 | 35 | 0.8.8 |

miuix 版本建议：跟随 LSPFRIDA 的 `0.9.4-rc01`，或 HC 的 `0.9.3`（稳定）。

miuix 常用组件：`Scaffold` / `SmallTopAppBar` / `Card` / `Button` / `NavigationBar` / `SwitchPreference` / `ArrowPreference` / `DropdownImpl` / `Backdrop`（miuix-blur 玻璃拟态）。

## 五、开发约定

1. **路径全英文**（见铁律）。
2. **包名**：建议 `top.<id>.honorhook`（待定，参考 LSPFRIDA `com.bail.lspfrifa`、HC `btm.m.os4.systemuihook` 结构）。
3. **Hook 入口**：继承 `XposedModule`，全限定名写入 `java_init.list`；`module.prop` 声明 minApi/targetApi/staticScope。
4. **Scope**：作用域包名用 `scope.list`（staticScope=true）或交由 Manager 勾选（staticScope=false）。荣耀目标进程待 system-files 静态分析后确定（候选：`com.android.systemui`、`com.hihonor.*` 等）。
5. **UI**：参考 `reference/` 下同类插件的 miuix 设计（注：用户原话"UI 参考【参考软件内的 UI 设计】"含占位符，若另指某具体 app 请告知修正）；单 Activity + Compose，`MiuixTheme` 包根，`isSystemInDarkTheme()` 切暗色（或用 ThemeController，见 LSPFRIDA）。
6. **静态分析 system-files**：荣耀系统资源（apk/jar/dex）需处理后才能静态分析。处理工具放 `tools/`：apktool（反编译 apk）、jadx（dex→java）、baksmali（dex→smali）。处理后产物放 `system-files/decompiled/`，源文件保留。
7. **reference/ 只读**：参考项目是他人源码，只引用不修改。
8. **AI 工作重心**：逆向分析 system-files（预处理 apk/jar/dex + 静态分析找 hook 点）+ 编写 LSPosed hook 代码。UI/项目骨架非核心，照搬参考项目拼装即可。
9. **拼装策略**：hook 入口/scope/Gradle 借鉴 HyperChanger（系统 UI hook 业务最像、纯 Kotlin 无 NDK）；UI 体系借鉴 LSPFRIDA（ThemeModeStore/NavHost+Pager/UiTokens/Screen 分文件，结构最清晰）；荣耀系统 hook 逻辑新写（基于 system-files 逆向分析，目标类与 MIUI 完全不同）。
10. **项目级 skill**（`.claude/skills/`，自动激活，共 5 个）：
    - 工具层：`reverse-decompile`（反编译还原+反混淆）、`static-analysis`（smali+伪代码静态分析、hook 点定位）、`lsposed-hook-expert`（hook 策略与开发、libxposed API 102、方法论与弯路）
    - 方法论层：`build-lsposed-module`（从零构建 LSPosed 插件工程：Gradle 脚手架/libxposed 依赖/入口注册/miuix UI 拼装/构建 APK）、`reverse-and-hook`（逆向 dex/so/资源 + hook 实现内存级功能修改：UI 与业务改动手法表、版本适配、真机验证）
    沉淀自 Rain + fanqie-purify + reference 三插件实践。

## 六、adb 与设备操作

- adb 路径：`tools/platform-tools/adb.exe`（v37.0.1，用户级，无需管理员）
- Android SDK 已就位：`D:\software\Android\SDK\`（跟随本机 `ANDROID_HOME` 环境变量；含 platforms;android-37.0 + build-tools;36.1.0，由 commandline-tools 经 dl.google.com 安装）。**AGP 编译必须平台 SDK**；`local.properties` 写 `sdk.dir=D:/software/Android/SDK`（**必须正斜杠**——Java Properties 把反斜杠当转义吃掉导致路径非法）
- 设备已 root，LSPosed 可激活；荣耀系统资源已置于 `/sdcard/` 下
- 拉取系统文件到本地：
  ```bash
  tools/platform-tools/adb.exe pull /sdcard/<系统文件目录> system-files/
  ```
  设备上目录实际名以 `adb shell ls /sdcard/` 输出为准（用户口述为"系统文件"，以设备实际为准）

## 七、当前状态与任务清单（2026-10-09 头脑风暴编排版）

### 已完成（当日）
- [x] 工程主体 + 构建 + UI↔hook 通信 + DexKit 双轨 + 目标分发框架（app/ 内，包 top.honorhook）
- [x] 主题 hook v1（46 挂载点：F1-F4 + 到期 2088 + 下载试用路由 H1/H2/H2b + 挽留弹窗双保险 + T1-T10 按钮机制修正 + 独立开关）
- [x] InstallerPurify 整合（F6，10 点，真机基线校验 8 组全命中，混淆类名一致）
- [x] UI 资源抄改（Rain 底栏三图标 + Rain launcher 全套 + manager 对勾 + 状态卡浅绿大对勾实现；零自绘）
- [x] demo UI 定稿（桌面 HonorHook-ui-demo.html：三 tab / Tab2 app→功能二级 / 主题设置含 manager 主题组 19 色板 / 液态玻璃 pill 底栏 / 彩蛋）
- [x] 4 个真机基线 apk 反编译就绪（installer/theme/market/honorui/systemui-jadx）
- [x] docs/ 13 份 + .claude/skills/ 5 个（已审计修正）

### 进行中（子任务并发）
- [ ] 第二批 LiquidGlass 整合（F7 面板 10 组 + 真机基线 systemui-jadx 校验 + boot-loop 纪律）
- [ ] 玻璃悬浮底栏（LSPFRIDA FloatingMiuixNavigationBar 抄改 + 悬浮/贴底双形态开关真实生效）

### 任务队列（依赖排序，按计划自主执行，不等催）
1. **git 仓库初始化 + 首次分层 commit**（立即并行——当前零版本控制是最大工程风险）
2. **Tab2 重构**：一级 app 列表 → 二级该 app 功能开关（照 manager 模式，demo 已定稿）← 等第二批完成（HookSettingsScreen 冲突）
3. **主题系统完整版**：ThemeModeStore 抄 LSPFRIDA 完整版（Monet 九风格/19 色主题色/暗色三态/纯黑）+ Tab1 详情真实数据（系统/Android/LSP 版本、构建时间、设备型号）+ miuix Toast ← 等底栏玻璃完成（ThemeSettingsScreen 冲突）
4. **第三批 MarketTamer 整合**（F5，拆两小批：市场去广告弹窗 + SystemUI 指震部分；market-jadx/honorui-jadx 基线已就绪）
5. 版本适配脚本（fanqie adapt_new_version 四步法移植）
6. release 签名配置
7. **真机验证闭环**（用户装包 → LSPosed 日志三类判据：`installed N/M` / `theme-download-chain 路由触发` / `挽留弹窗已拦截` → 迭代修复）
8. 第四个插件整合（HonorLsposedFix？待用户确认）
9. skill 沉淀更新（真机基线验证方法论 / 观测日志闭环 / "静态点≠运行时关键点"教训）

### 阻塞 / 等待用户
- 真机验证（设备连接 + 装最新桌面包操作一轮）——判据日志回来逐点核对
- 主题商店版本错位根因（"UI 对不上"：真机实际版本 vs apk 基线 20.1.63.305）——需 LSPosed 日志或 dumpsys
- 真机 framework-res.apk + 荣耀私有框架包（/system/framework/）——资源反查依赖

## 八、AI 协作纪律

- 改代码先出**变更清单**（文件/位置/目的），仅清单内修改
- 优先扩展不修改既有逻辑；必须改时先 grep 全部调用方
- 禁顺手改需求无关 BUG / 重构 / 删死代码
- 方案完成对照需求逐项自检后再编码
- 路径全英文，禁中文目录名
- `reference/` 只读，不改源码
- `system-files/` 是研究对象，处理后分析，不改系统
- 工具链用户级便携，不依赖 Windows 管理员权限
- 测试只真机直连，禁模拟器相关任务
- 派子任务时只跑定向编译/测试，禁全量构建
- **桌面路径是 `D:\Data\desktop`**（非 C 盘用户桌面）——所有"放桌面"的产物（APK/PDF/报告等）一律复制到 `D:\Data\desktop\`，禁止放 C 盘用户桌面
