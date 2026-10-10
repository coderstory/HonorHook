# HonorHook Gap 分析报告（vs 7 个参考项目）

> 日期：2026-10-09 · 方法：9 份已有分析文档（rain-analysis / rain-hook-architecture / hc-hook-structure / ai-lsposed-temlate-report / ui-template-research / honorhook-theme-requirements / honor-theme-payment-analysis / honor-system-preliminary / skill-audit-fixes）+ 源码抽查实证（HonorHook 全 16 个 Kotlin 文件、三个同类插件的 module.prop/注册文件/关键类、fanqie tools/ 脚本）
> 基准：`app/` 工程现状（已抽查）。

## 0. HonorHook 现状速览（抽查实证）

| 维度 | 现状 |
|---|---|
| 工程 | AGP 9.x + Kotlin 2.3.21 + miuix 0.9.3 + DexKit 2.2.0；阿里镜像；packaging merges 保 META-INF/xposed ✓ |
| 注册 | java_init.list 单入口 HookEntry；module.prop（min=102/target=102/staticScope=true/**无 autoHotReload/无 exceptionMode**）；scope.list 2 目标（thememanager + packageinstaller） |
| Manifest | MODULE_SETTINGS + LAUNCHER alias ✓；**无 queries 声明**（Rain 有） |
| UI | MainActivity + MiuixTheme 三态（ThemeModeStore 无 Monet）；NavHost(main/about) + HorizontalPager 3 页 + 原生 NavigationBar；四页齐（首页/目标/设置/关于） |
| 通信 | HookSettings 远程 prefs 双写 + syncRemote + 热监听（仅 log）+ intercept 现读 ✓（HC 模式完整） |
| hook 架构 | TargetHook 接口 + TargetRegistry 静态注册 + isFirstPackage 门控 + installed 防重装 ✓；ThemeStoreHook 26 处（F1-F4）已实现；DexKit 双轨基建 ✓（3 个 API 标"待真机验证"）；HonorInstallerHook 占位 |
| 日志 | module.log 逐条挂载/失败日志（INFO/WARN）有；**无挂载汇总（N/M）、无 debug 门控、无进程内日志可查** |
| 其余 | release 未开 minify/无签名配置；无 CI；无版本适配工具；无单测 |

---

## 1. 逐项目对比（7 个）

### 1.1 LSPFRIDA（同类插件，miuix UI 最深）

| 亮点机制 | HonorHook 有无 | 缺什么 |
|---|---|---|
| ThemeModeStore 完整版：keyColor 种子色 + paletteStyle + wallpaperMonet（Monet 动态取色） | 简化版（仅三态，已砍这几项） | Monet 主题 / 种子色设置 |
| FloatingMiuixNavigationBar 悬浮玻璃底栏 + GlassSurface | 无（初版按计划用原生 NavigationBar） | 悬浮底栏（可选） |
| LogStore 文件持久化（files/logs/\<pkg\>/\<date\>.log 有界队列批量 flush）+ EarlyLogBuffer（500 行环形缓冲）+ LogScreen 页 | 无 | 进程内日志查看能力 |
| module.prop：exceptionMode=protective + minApiVersion=82（兼容老框架） | 无（hook 级已有 PROTECTIVE） | 全局 exceptionMode 声明 |
| HookModeStore per-feature 开关（loadclass_watch 默认关） | 只有全局总开关 | 独立开关体系 |
| 类加载感知（loadClass 监听、入队等类加载、500ms 超时兜底） | 无 | 过度工程，重试即可（见 G1） |
| proguard-rules.pro 详尽（入口 keep + libxposed keep + JNI + adaptresourcefilecontents） | rules 文件已备但 minify 关 | 发布时启用验证 |

### 1.2 HyperChanger（同类插件，hook 工程组织 + 通信）

| 亮点机制 | HonorHook 有无 | 缺什么 |
|---|---|---|
| OsCompatibility 版本门控（ro.mi.os.version.code 一票否决） | 无 | 系统版本门控（软/硬） |
| postDelayed 1.5s 类懒加载重试（installCustomTileHooks L408-416） | **无（关键缺口）** | 类未加载时重试挂载 |
| 每 hook 一组 installed 布尔防重装 | 有（TargetRegistry 集中版） | — |
| setId 幂等 + PROTECTIVE + runCatching 整体防护 | 有（每 hook + 入口层） | — |
| 远程 prefs 现读 + 热监听 + SettingsAppearanceProvider 资产通道（query/openFile + 白名单槽位） | 现读/热监听有；Provider 无 | 现阶段不需要（见"不要补"） |
| module.prop：autoHotReload=true | 无 | 一行声明（调试期收益） |
| RELEASE_NOTES 系列发布纪律 / languages/ 多语言目录 | 无 | 多语言（可选）；发布记录（可选） |
| scope.list 含 android/system 特殊域（system_server/SystemUI） | 无（主题目标不需要；安装器系统 hook 候选） | 真机确认后补 |

### 1.3 notification-guard（同类插件，最简模式）

| 亮点机制 | HonorHook 有无 | 缺什么 |
|---|---|---|
| GuardModule 30 行：handles 列表持有 HookHandle + attach() 统一挂载口 | 有等价物（TargetHook.install + mountConst） | — |
| error() 统一脱敏日志（"Do not log exception messages from Android: they can contain notification text"） | **无（隐私纪律）** | 异常消息脱敏规范 |
| onSystemServerStarting + onPackageReady 分开按包校验、ui != null 防重装 | onSystemServerStarting 无（占位目标未定） | 系统服务目标确定后补 |
| CI 全套：.github/workflows/build.yml（temurin + sdkmanager + gradle + secrets 签名 + testDebugUnitTest/lintDebug/assembleRelease + upload artifact + 签名材料清理） | 无 | CI（可选） |
| 单测（StatisticsTest）+ androidTest（ManagerSmokeTest） | 无 | JVM 单测（可选） |

### 1.4 LSPosed（框架源码，manager app 视角）

| 亮点机制 | HonorHook 有无 | 缺什么 |
|---|---|---|
| Manager UI 结构（底部导航/折叠大标题/分组设置/Monet 19 色/暗色三态） | 已按 ui-template-research 简化吸收 | 仅 Monet 未搬（S2） |
| 远程 prefs 链路（daemon SQLite + binder 推送 + LSPosedRemotePreferences 快照/监听） | 有（HC 模式 1:1） | — |
| hotReloadModule API 102（配合 autoHotReload 声明） | 无声明 | 一行（S3） |
| 模块列表/scope 勾选/全局日志/Repo | — | **不需要**（manager 专属，见"不要补"） |
| 远程 prefs 1MB 上限（ConfigManager L499-501） | 已知留档 | 大资产走文件通道（暂无需求） |

### 1.5 fanqie-purify（AI 开发插件实践，版本适配方法论）

| 亮点机制 | HonorHook 有无 | 缺什么 |
|---|---|---|
| adapt_new_version.sh：拉包 → 资源 id 新旧比对 → hook 类/方法存在性比对 → bump 版本 → 构建安装 | **无（关键缺口）** | 一键适配检查脚本 |
| apkver.py：二进制 manifest 版本读取/断言（CI 免 build-tools） | 无 | 并入适配脚本 |
| 版本无关 hook 点（如 android.app.Dialog.show() 收口点）+ 文案结构兜底（不逐类名枚举） | 部分（ThemeStoreHook 走 keep 名单类名，report 已核） | 方法论写进 skill（已入 lsposed-hook-expert），工程侧靠 G3 |
| MainHook 头部每版适配史注释（v1.9.x 适配 6.7.x 记录 + 变化清单） | 无 | 目标版本适配记录约定 |
| Hook注册报告（adHookReport "…✓" 汇总行）+ XposedBridge.log 全链 | 逐条日志有、**汇总无** | 挂载 N/M 汇总（G2） |

### 1.6 AI-lsposed-temlate（最小骨架 + AI 规则）

| 亮点机制 | HonorHook 有无 | 缺什么 |
|---|---|---|
| packaging merges 保 META-INF/xposed（excludes+merges 并存硬手法） | 有 ✓ | — |
| .cursor 规则：libxposed API 速查 + Classic API 禁用负面清单 | 有等价（lsposed-hook-expert skill 已吸收 27 条修正） | — |
| release minify + shrinkResources + debug 签名 | minify 关 | 发布期启用（S9） |
| CI（temurin + assembleDebug/Release） | 无 | CI（可选） |
| 编译纪律（compileOnly / onPackageReady / getPackageName 校验） | 有（compileOnly ✓、isFirstPackage ✓；onPackageReady 分发还是 TODO） | onPackageReady 目标分发待目标确定 |

### 1.7 Rain apk（高混淆同类插件，分析已落盘）

| 亮点机制 | HonorHook 有无 | 缺什么 |
|---|---|---|
| "installed N/M resolved hook(s)" 挂载汇总 + "Hook-Helper" 统一 tag + s90.a enable_debug_logging 门控 | 无汇总、无 debug 门控 | G2 |
| Adapted apps 页（Installed·%s / Not installed / Not adapted·%s，strings 实证） | TargetsScreen 空态 | S7 |
| 设置项：Color Mode 三态 + Monet + Floating bottom bar + Hide desktop icon | 三态有；后三项无 | S2/O1/O3 |
| 每进程 isFirstPackage + synchronized 一次性挂载 + 包名查表分发 | 有（registry installed + isFirstPackage） | — |
| scope 声明与实现一致性（mipay.wallet 有 scope 无适配器 = 反例） | 现一致（2 目标均在 registry） | 纳入 G3 脚本检查 |
| 反篡改时间炸弹 / XOR 字符串加密 + 诱饵 / 控制流混淆 / 阿拉伯类名 | — | **不需要**（见"不要补"） |

---

## 2. Gap 清单（重点）

### 2.1 必要（影响功能正确性 / 可用性 / 长期维护）

**G1. 类懒加载重试挂载**
- 来源：HC（installCustomTileHooks 找不到目标类时 postDelayed 1.5s 自重试）
- 现状：ThemeStoreHook 在 onPackageLoaded 一次性 findMethod，类未加载即失败 → 只记 WARN → 本进程生命周期内不再挂；主题商店详情页类（OnlinePreviewBaseActivity 等）大概率懒加载，直接影响 F1-F4 生效
- 工作量：小。要点：HookEntry/TargetHook 加轻量重试器——findMethod 全失败时 postDelayed 1.5s 重试（上限 3-5 次），installed 集合幂等照旧；不做 LSPFRIDA 式 loadClass 三阶再武装（过重）

**G2. 挂载汇总报告 + debug 日志门控 + hook 失效检测**
- 来源：Rain（"installed N/M resolved hook(s)" + enable_debug_logging 远程 pref）、fanqie（Hook注册报告）、LSPFRIDA（日志体系）
- 现状：逐条 INFO/WARN 日志已有，但无汇总、无等级门控；目标 app/ROM 升级后 hook 静默失效（仅散落 WARN 难察觉）
- 工作量：小。要点：①统计挂载 attempted/resolved/failed（含 method==null 与 hook() 异常两类失败 + 原因），进程级别汇总日志；②远程 pref `debug_logging`（默认关）门控 DEBUG 日志（Rain s90.a 模式）；③失败清单可被首页状态卡消费（S5）

**G3. 版本适配一键检查（目标更新后的失效检测）**
- 来源：fanqie（adapt_new_version.sh 四步 + apkver.py + 每版适配史注释）、Rain（scope 一致性教训）
- 现状：无任何适配工具；ThemeStoreHook 目标类名集中在常量表（好事），但无脚本比对"新版 APK 里目标类/方法还在不在"
- 工作量：中。要点：①瘦身版脚本：adb 拉目标 apk → 提取 hook 清单（从代码常量表或独立 .list 文件）→ 用 apkver.py 式二进制解析拿版本 → 比对类/方法存在性 → 输出"N 个会失效"报告（比 fanqie 少资源 id 比对——HonorHook 无资源 hook）；②代码注释约定：每次适配记录目标版本（fanqie MainHook 头部模式）；③脚本顺带校验 scope.list 与 TargetRegistry 一致性（防 Rain mipay 式空注册）

**G4. 日志异常消息脱敏纪律**
- 来源：NG（error() 注释："Do not log exception messages from Android: they can contain notification text"）
- 现状：mountConst/mountForceFirstArg 失败日志直接打 exception message——主题名/包名等用户数据可能进 logcat
- 工作量：小。要点：统一失败日志只打异常类型 + hook id + 描述，不打 message 全文（msg 仅在 debug_logging 开时输出）

### 2.2 建议（提升质量 / 体验）

**S1. 每目标 / 每功能独立开关**
- 来源：HC（per-feature KEY_*，如 dock_enabled）、LSPFRIDA（HookModeStore per-switch）
- 现状：仅全局 KEY_HOOK_ENABLED，主题 F1-F4 与安装器全绑总开关
- 工作量：中。要点：HookSettings 扩为 key 表（theme_enabled / installer_enabled，可选再拆 F1-F4 四键）；设置页每 Card 一个 SwitchPreference；hook 侧 intercept 现读各自 key；总开关保留为上位语义（总关 = 全部放行）

**S2. Monet 动态取色 + 种子色设置**
- 来源：LSPFRIDA ThemeModeStore（keyColor/paletteStyle/wallpaperMonet）、LSPosed manager（DynamicColors 19 色）、Rain
- 现状：三态主题已实现，这几项在拼装时按计划砍掉
- 工作量：小。要点：ThemeModeStore 补 keyColor（hex String? 可空）+ wallpaperMonet 布尔 + paletteStyle；设置页"主题"组加 Monet 开关与色板；ThemeController 透传（MainActivity 已按 mode remember 重建，加 keyColor 依赖即可）

**S3. module.prop 补 exceptionMode=protective + autoHotReload=true**
- 来源：LSPFRIDA（exceptionMode=protective）、HC（autoHotReload=true）
- 现状：两键都缺（hook 级已逐条 PROTECTIVE，module.prop 声明是全局默认双保险；autoHotReload 让 Manager 在模块更新后自动重注入作用域进程，调试期少一次手动重启）
- 工作量：超小（两行 + README 说明）

**S4. README / 文档与实现同步**
- 来源：自查发现——README 写 `staticScope=false`，module.prop 实为 `staticScope=true`（主题 v1 起已改）；README 记 docs 7 份，实际 9 份
- 工作量：超小

**S5. 首页 Hook 健康状态卡（替代死卡 "Hook 数 0"）**
- 来源：Rain Adapted apps 页（app_version_unsupported "Not adapted·%s" 模式）+ G2 数据
- 现状：HomeScreen 状态卡硬编码 "Hook 数 0 —— 目标应用待逆向分析后实现"
- 工作量：小。要点：接 G2 统计 StateFlow：每目标 挂载 N/M、失败明细数、Not adapted 提示；未激活框架时引导文案

**S6. 进程内日志查看（app 内日志页）**
- 来源：LSPFRIDA（LogStore 文件持久化 + EarlyLogBuffer 500 行补发 + LogScreen 页）
- 现状：只有 logcat 可查，真机验证时 LSPosed Manager 日志远不如模块自有页面直观
- 工作量：中。要点：hook 进程日志落模块 filesDir（API 102 listRemoteFiles/openRemoteFile 可回读）；设置页"日志"入口 + 简单列表页；量级控制：有界队列 + 批量 flush（照 LogStore 模型），不做 IPC 实时流

**S7. TargetsScreen 实现（Adapted apps 页）**
- 来源：Rain（Installed·%s / Not installed / Not adapted·%s，strings.xml 实证）
- 现状：空态页 + 无 queries 声明
- 工作量：中。要点：Manifest 补 queries（2 目标包）；TargetsScreen 用 packageManager 查 installed 版本 + 与适配版本（G3 数据）对照，逐项状态行；图标 + 包名 + 状态

**S8. 系统版本门控（OsCompatibility 模式）**
- 来源：HC（ro.mi.os.version.code 一票否决）
- 现状：只有包名匹配。荣耀系统版本碎片多（MagicOS 8/9/10），hook 点按版本漂移概率高
- 工作量：小。要点：object OsCompatibility 读荣耀系统属性（键名需真机 getprop 确认，预研未覆盖）；先软门控（不匹配只提示不阻断），确认键名与值域后转硬门控

**S9. release 构建配置（minify + 签名 + proguard 实测）**
- 来源：AI-temlate（release minify+shrinkResources）、LSPFRIDA（proguard 详尽规则）
- 现状：release 未开 minify、无签名配置；proguard-rules.pro 已备（skill 层已补 adaptresourcefilecontents 等）
- 工作量：中（发布期做）。要点：开 minify + shrinkResources；debug/release 双签名配置；验证入口类 keep + java_init.list 内容改写生效（打包后 unzip 核对，skill 已沉淀此法）

### 2.3 可选（锦上添花）

**O1. 悬浮玻璃底栏** —— 来源 LSPFRIDA（FloatingMiuixNavigationBar）/ Rain。工作量：中。初版已定原生 NavigationBar，UI 稳定后搬。
**O2. CI** —— 来源 NG/AI-temlate GitHub Actions（build + test + lint + artifact）。工作量：中。注意：本机无 GitHub 推送通道，仓库上线云端后再配。
**O3. 隐藏桌面图标** —— 来源 Rain（Hide desktop icon）。工作量：小。动态 disable activity-alias 即可。
**O4. 多语言** —— 来源 HC languages/、manager。工作量：中。当前硬编码中文与 stringResource 混用，先统一 stringResource 再谈翻译。
**O5. JVM 单测** —— 来源 NG（StatisticsTest）。工作量：小。纯逻辑可测点少（Settings 编解码/清单解析），hook 挂载需真机，价值有限。

---

## 3. 明确"不要补"清单（逐条说明）

1. **LSPosed Manager 专属功能**（模块列表管理 / scope 勾选 / 全局日志页 / 框架版本信息 / Repo）：scope 由 Manager 管理是设计选择（README 明示"作用域不由模块写死"）。HonorHook 是配置界面，不是管理器。来源：LSPosed 框架（manager 视角）、ui-template-research §1 已定剔除。
2. **fanqie 的番茄目标 hook 逻辑**（com.dragon.read.* 类、广告位黑名单 9 项、hybrid bridge 拦截、旧版 Xposed API）：目标 app 不同，逻辑零复用；只吸收其方法论（版本适配、收口点、报告模式）。来源：fanqie-purify。
3. **Rain 反篡改 / 加固三件套**（时间炸弹日期校验、short[] 单轮 XOR 字符串加密 + Math.random 诱饵解密器、ApkControlFlowConfusion + 阿拉伯类名）：我们自己的模块不需要防自己；且 docs/rain-hook-architecture §4 已证明该强度破解成本极低（201 调用点批量脚本），而混淆会显著牺牲可维护性与 AI 协作效率。Rain 的 scope 空注册（mipay.wallet 声明无适配器）是反例，不学。来源：Rain。
4. **HC DockMaterialProvider 反向 RPC**（system_server 经 provider call() 让模块 UI 进程建 SurfaceControlViewHost 玻璃视图回传 SurfacePackage）：SurfaceControlViewHost 玻璃渲染属高端定制，hc-hook-structure §6 已定首版不做。来源：HC。
5. **HC SettingsAppearanceProvider 二进制资产通道**（query 标量 + openFile 文件槽位 + 750ms TTL 缓存）：HonorHook 配置全是标量（走远程 prefs 自带 daemon 缓存），无自定义图片/背景需求；将来有再补（照 §3.5 白名单 + UID 鉴权模式）。来源：HC。
6. **notification-guard 的 emulator smoke CI**：本机禁模拟器（CLAUDE.md 环境约束三条），`connectedAndroidTest` 相关任务不适用。来源：NG ci/。
7. **旧版 Xposed API 及其注册**（XposedHelpers/XposedBridge + manifest meta-data 四件套）：四参考统一新版 libxposed 102，skill 已立"Classic API 禁用"负面清单，混用是典型错误。来源：AI-temlate .cursor 规则、skill-audit-fixes #13。
8. **HC 单文件 14,560 行巨类组织**：已用"入口 + TargetRegistry + 每目标一文件"模式替代，目标增多也只加文件不加入口逻辑。来源：HC（反模式）。
9. **LSPFRIDA 类加载感知三级再武装**（loadClass 监听 + 轮询 + 500ms 超时兜底）：面向"任意脚本 hook 任意未加载类"的通用场景；HonorHook 目标是固定系统类，G1 轻量重试已覆盖，不需要侵入宿主最热路径（LSPFRIDA 自身也把 loadClass 监听默认关）。来源：LSPFRIDA HookModeStore。
10. **Rain 式模块 UI 双开关模式之外的功能**（app 内 Frida 脚本引擎 / IPC 服务）：LSPFRIDA/NG 的场景与荣耀系统定制无关。来源：LSPFRIDA。

---

## 4. 验证依据（抽查点索引）

- HonorHook 现状：app/app/src/main/kotlin/top/honorhook/ 全部 16 文件；app/app/build.gradle.kts；AndroidManifest.xml；app/app/src/main/resources/META-INF/xposed/{module.prop,java_init.list,scope.list}
- LSPFRIDA：module.prop（min=82/target=102/staticScope=false/exceptionMode=protective）；ThemeModeStore.kt L34-44（keyColor/paletteStyle/wallpaperMonet）；EarlyLogBuffer.kt（500 行容量）；LogStore.kt（files/logs 落盘）
- HC：OsCompatibility.kt（ro.mi.os.version.code）；HyperSystemUiModule.kt postDelayed 重试（L408-416 起多处）；module.prop（autoHotReload=true）；DockSettings.kt（per-feature KEY_*）
- notification-guard：module.prop + scope.list（android, com.android.systemui）+ GuardModule.java 30 行（handles/attach/error 脱敏）；.github/workflows/{build,smoke}.yml
- fanqie-purify：tools/adapt_new_version.sh（四步流程文档头）；tools/apkver.py；MainHook.java 头部版本适配史 + "Hook注册报告" 汇总行
- Rain：system-files/rain-apktool 产物 strings.xml（app_installed/app_not_installed/app_version_unsupported）+ docs/rain-hook-architecture（installed N/M、enable_debug_logging、mipay 空注册）
- 文档交叉：docs/ 9 份（行号证据见各文档），本次未复核的结论均标"照文档"，未标 = 已抽查实证