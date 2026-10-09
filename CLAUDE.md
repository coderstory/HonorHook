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
| `docs/` | 调研/逆向分析报告留档（索引见 `docs/README.md`，共 14 份） | 逆向（Rain×2/主题付费/下载链/UI真实链/荣耀预研/插件整合）、调研（UI 模板/HC 结构/AI 模板/gap 分析）、需求、审计（skill 修正） |

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
- [x] 主题 hook v1（38 挂载点：F1-F4 + 到期 2088 + 下载试用路由 H1/H2/H2b + 挽留弹窗双保险 + T1-T10 按钮机制修正 + 独立开关；哲学审计收敛 46→38：VipState 七点散读合并双漏斗 + 删休眠点/深签名点）
- [x] InstallerPurify 整合（F6，10 点，真机基线校验 8 组全命中，混淆类名一致）
- [x] UI 资源抄改（Rain 底栏三图标 + Rain launcher 全套 + manager 对勾 + 状态卡浅绿大对勾实现；零自绘）
- [x] demo UI 定稿（桌面 HonorHook-ui-demo.html：三 tab / Tab2 app→功能二级 / 主题设置含 manager 主题组 19 色板 / 液态玻璃 pill 底栏 / 彩蛋）
- [x] 4 个真机基线 apk 反编译就绪（installer/theme/market/honorui/systemui-jadx）
- [x] docs/ 13 份 + .claude/skills/ 5 个（已审计修正）
- [x] **/code-review max 全量审计 + 15 项 CONFIRMED 修复**（3 commit：hook 侧 P0×3+正确性×3、UI/配置 4 项、构建规则+README；新增 RemotePrefsHolder/MethodResolver 基建；debug/release 编译全过，release java_init.list 恢复全限定名；新包已上桌面）
- [x] **hook 设计哲学入档**（§九，用户口述定稿：宽容/根源/外部接口/公用/最少点/依赖权重）

### 进行中（子任务并发）
- [ ] 第二批 LiquidGlass 整合（F7 面板 10 组 + 真机基线 systemui-jadx 校验 + boot-loop 纪律）
- [ ] 玻璃悬浮底栏（LSPFRIDA FloatingMiuixNavigationBar 抄改 + 悬浮/贴底双形态开关真实生效）

### 任务队列（依赖排序，按计划自主执行，不等催）
1. [x] **git 仓库初始化 + 分层 commit** ✅（19 commit：基线 3 + submodule 11 + 功能 5；10 参考项目 submodule 化）
2. [x] **Tab2 重构** ✅（commit 22871db：AppListScreen 一级 + AppFunctionsScreen 二级，manager 模式）
3. [x] **主题系统完整版** ✅（commit 561f2fb：Monet 构建链 + 12 色板 + 暗色三态 + 纯黑 + 跟随取色，抄 LSPFRIDA）
4. [x] **第三批 MarketTamer 整合** ✅（commit 362ffe2：MarketHook 464 行 15 点真机基线 15/15 命中，F5）
5. [x] 版本适配脚本 ✅（tools/adapt_new_version.py，基线 56/56 全存活留档）
6. [x] release 签名配置 ✅（commit 62666ec：混淆+收缩，**release 2.8MB**（-82%），混淆正确性验证全 PASS——java_init.list 改写对齐、注册链 keep、字符串常量完好）
7. [ ] **真机验证闭环（T1 门禁 / T10 全量验收）**——用户装桌面包（debug 或 release）→ LSPosed 日志五判据：`installed N/M`（主题 38/38、安装器 10/10、玻璃 15/15、市场 15/15）/ `theme-download-chain 路由触发` / `vip-state 漏斗改写` / `挽留弹窗已拦截` / 重启不回退 / F5-F7 行为 → 反馈现象逐点迭代
8. [ ] 第四个插件整合（HonorLsposedFix？待用户确认）
9. [ ] skill 沉淀更新（观测验证铁律/门类放行/真机基线校验/版本错位排查——✅ 已完成；二期候选锁屏玻璃 600 行、MarketTamer 细分开关、Monet 九风格）

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
- **代码注释纪律（2026-10-09）**：注释是给 AI 看的——AI 能看懂就行，不罗里吧嗦；**禁止描述代码逻辑**（代码本身自证，注释复述逻辑 = 纯噪音）；**只写从代码里获取不到的信息**：逆向实证来源（jadx file:line / 真机基线版本号）、设计决策的"为什么"（选点依据/取舍/坑史）、外部约束（服务端行为/框架语义）、真机验证判据、版本适配依据。
- **桌面路径是 `D:\Data\desktop`**（非 C 盘用户桌面）——所有"放桌面"的产物（APK/PDF/报告等）一律复制到 `D:\Data\desktop\`，禁止放 C 盘用户桌面

## 九、Hook 设计哲学与 LSPosed 插件方法论

> 用户 2026-10-09 口述定稿（架构七大原则 + 选点操作细则合一）。新增/评审任何挂载点与模块设计时逐条对照。
> **一句话总纲**：在精确的边界内，寻找最稳定的锚点，以最小的侵入完成行为重定向，并时刻准备着优雅地失败。

### 1. 边界优先（Scope First）——先画地为牢，再施展拳脚
- **权力最小化**：hook 本质是在目标进程里获得至高控制权，写第一行逻辑前先界定影响范围。
- **进程级隔离思维**：每个 App、甚至同一 App 的不同组件（UI/后台服务/WebView）都跑在独立进程与 ClassLoader 中——模块只注入必须依赖它的那个进程。
- **白名单即设计**：作用域机制（staticScope/scope.list）是架构上的"防火墙"，不是配置负担。

### 2. 稳定锚点（Stable Anchor）——钉在契约上，不插在实现的流沙里
- **找契约，不找实现**：优先有"API 语义"的稳定入口（生命周期回调、公开方法、配置读取接口）；远离内部逻辑、工具函数、UI 渲染细节——前者随版本极少变化，后者随时重构。
- **找决策，不找表现**：改做出决策的地方（开关判断、数据请求构造），不改最终 UI 展示——改决策点改动小、通用性强、抗变化；改表现层牵一发而动全身。
- **根源原则**：改数据源返回值（如 VIP 体系的用户信息查询直接返回值），不 hook 后续业务代码里的 vip 判断——数据源一处改、下游全生效；消费端判断点散落多处且随版本增删。
- **稳定性排序**：外部接口 > 内部实现；公用代码（框架层/公共链路）> 业务专用代码。
- **单一职责**：选参数与返回值清晰、逻辑内聚的方法；避开被内联、被混淆、被过度重载的复杂方法。
- **依赖权重**：代码 hook 有多种方式——构造函数、替换方法、替换入参、替换出参、捕获异常、直接修改返回值等；另有资源 hook（资源替换/注入）独立成轨。按"签名依赖的目标代码量"加权选择——无参返回 boolean 远优于"第二参数必须是特定 DTO 类且内部有 int 字段"的深签名；每多一个具体类型依赖，版本迭代破坏概率就多一分。
- **最少 hook 点**：能实现目标的前提下越少越好——每个点都是版本漂移的暴露面。

### 3. 最小侵入与关注点分离（Minimal Invasion & SoC）——hook 是接线员，不是车间
- **轻量拦截**：before/after 只做参数校验、短路返回、结果修正；任何阻塞操作（IO/网络/复杂计算）必须剥离出去。
- **逻辑外移**：重逻辑、配置读取、状态管理放独立模块/线程/服务；hook 点只负责"触发"与"结果回写"。
- **无状态与幂等**：假设逻辑可能被多次调用、被多模块重复注入——无状态或幂等，不产生副作用叠加。

### 4. 生命周期与时机感知（Timing & Lifecycle）——在正确的时间，找到正确的类，做正确的事
- **理解加载时序**：清楚目标进程启动流程、类加载时机、ClassLoader 父子委派；hook 必须在目标类加载之后、被调用之前完成注册（本项目形态：懒加载重试 500ms/2s 两轮 + NOT_RESOLVED 随类加载重试）。
- **动态加载应对**：插件化/热修复/多 Dex 的 app 不能指望静态入口一次找齐——监听类加载事件或找更早锚点（如 Application 构造）延迟注入。
- **进程差异**：同一应用不同进程的初始化顺序与可用类完全不同；模块必须识别自己跑在哪个进程并调整策略（本项目形态：TargetRegistry 按包分发 + 对象级防重装）。

### 5. 防御性编程与可降级（Defensive & Graceful Degradation）——永远假设会出错，并确保错误不扩散
- **异常隔离**：hook 回调里的任何异常都必须捕获消化，绝不能让插件崩溃炸宿主——底线（本项目形态：PROTECTIVE 异常模式 + runCatching）。
- **日志与可观测**：分级日志便于调试但生产不刷屏——日志是黑盒里唯一的眼睛（本项目形态：installed N/M 汇总 + 触发日志计数节流）。
- **功能开关与模块化**：每个子功能可独立关闭降级，而不是逼用户卸载整个模块（本项目形态：F1-F7 per-key 开关 + 总开关上位语义）。
- **失败自愈**：解析失败要有 DexKit 特征兜底 + 懒加载重试，不能一次失败即永久失效。
- **冲突规避**：多模块 hook 同一方法时，优先"作用域不重叠"规避，其次才靠框架优先级机制。

### 6. 抽象层与适配策略（Abstraction for Compatibility）——把变化封装在底层，让上层保持稳定
- **适配层隔离**：为不同系统版本建立独立适配层；上层业务逻辑不关心底层是哪个 Android 版本，只调统一接口。
- **特征检测优于版本号**：尽可能按方法签名、类是否存在等特征动态判断，不硬编码 SDK_INT——面对厂商定制 ROM 更健壮。

### 7. 合规与伦理底线（Ethics & Compliance）
- **授权边界**：只对自己拥有或获得明确授权的应用进行修改。
- **目的纯粹**：不开发用于作弊、窃取隐私、规避安全的模块。本模块定位为自用设备的系统定制。

### 审计应用
多路专家审计（/code-review max、哲学审计）发现的问题按上述原则定性：违反根源原则（数据源可达却 hook 消费端）、违反公用原则、依赖过深（浅签名等价可达）、侵入过重（hook 内做重活）、无降级路径——五类列为高优先改进项；既有挂载点若违反，列入改进清单权衡迁移。

> 哲学表述版（先验框架宣言：先验时空/因果锚点/先验律令/现象界适配——与上述工程条款一一对应，供架构判断力内化）见 `docs/hook-philosophy-manifesto.md`。
