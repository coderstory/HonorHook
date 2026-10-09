# HonorHook v1 完整开发设计（Superpowers Spec）

> 日期：2026-10-09（v0.1，供用户 review）
> 材料：CLAUDE.md §七 任务清单；docs/ 13 份（需求、逆向、hook 点、UI 图纸、gap、整合方案）；UI demo 定稿 `D:\Data\desktop\HonorHook-ui-demo.html`
> 状态：git 基线任务并行在跑，本 spec 不提交、不建文件外任何产物

---

## 1. 目标与范围

**HonorHook v1 = 主题四功能（F1-F4） + 三插件整合（F5/F6/F7） + UI 对齐 Rain/LSPosed Manager（抄则抄）**，真机验收闭环。

| 功能位 | 内容 | 来源 | 默认 |
|---|---|---|---|
| F1 | 开启 VIP（会员判定全放行） | 主题商店 hook | 开 |
| F2 | 全部资源免费（价格/付费判定全放行 + 下载路由修复） | 主题商店 hook | 开 |
| F3 | 试用无倒计时（试用到期判定失效） | 主题商店 hook | 开 |
| F4 | 任何场景不恢复默认（回退路径全阻断 + 挽留弹窗拦截） | 主题商店 hook | 开 |
| F5 | 应用市场净化（MarketTamer 28/29 开关移植） | reference/HonorMarketTamer | 关 |
| F6 | 安装器净化（跳过联网检测/指纹验证、禁联网、界面净化） | reference/HonorInstallerPurify | 关（安全敏感） |
| F7 | 液态玻璃恢复（面板全链；锁屏链为 f7_glass_keyguard 子位二期） | reference/HonorLiquidGlassRestore | 关（boot-loop 史，保守） |

**范围界定**：「主题」= 主题商店内全部资源（主题/壁纸/字体/锁屏/图标），F1-F4 全量覆盖；纯服务端校验点 hook 无效——如实标注不硬做（§6）；三插件只移植代码与实际一致的 hook 点，README 宣传与代码不符处（如「允许降级安装」代码中不存在）不移植。UI 以 demo 为终版基准，逐页还原，不增加 demo 外页面。

---

## 2. 现状基线（已完成的，如实提取）

| 维度 | 现状 |
|---|---|
| 工程 | app/ Gradle 工程：AGP 9.x + Kotlin 2.3.21 + miuix 0.9.3 + DexKit 2.2.0；首次构建通过（app-debug.apk 11.2MB / 3m23s，腾讯/阿里/dl.google 镜像均不被公司网拦）；packaging merges 保 META-INF/xposed ✓ |
| 注册 | java_init.list 单入口 HookEntry；module.prop（min=102/target=102/staticScope=true）；scope.list 2 目标（thememanager + packageinstaller） |
| hook 架构 | TargetHook 接口 + TargetRegistry 静态注册 + isFirstPackage 门控 + installed 防重装；DexKit 双轨解析基建（反射优先/DexKit 兜底，3 API 待真机验证） |
| 主题模块（F1-F4） | ThemeStoreHook.kt（830 行）已实现：免费判定 A 组（ItemInfo 系）+ 详情按钮真实链修正（T1-T10，isBuyBtnShow 旧点已弃用）+ 试用 C 组 + VIP B 组 + 下载链修复（H1/H2/H2b 空 URL → 试用授权下载）+ 挽留弹窗拦截（F1/F2 双保险）；内置懒加载重试（500ms/2s 上限）+ 挂载汇总 "installed N/M" 日志（始终输出）；挂载点 demo 口径 46 项、源码实测注册调用 ~50 条（含多版本并存存档） |
| 通信 | HookSettings 远程 prefs 双写 + syncRemote + hook 侧热监听（仅 log）+ intercept 现读（HC 模式完整）；KEY_F1..F4 功能位已存在，hook 侧 DEBUG_LOGGING 门控已有 |
| UI | MainActivity + MiuixTheme 三态（ThemeModeStore 简化版，无 Monet）+ NavHost(main/about) + HorizontalPager 3 页 + 原生 NavigationBar；四页齐（首页/目标/设置/关于）；HomeScreen 状态卡硬编码 "Hook 数 0"（占位） |
| 文档/设计依据 | docs/ 13 份全部就位（需求、付费机制逆向 5 组 30 点、UI 真实渲染链反查、下载链反查、Rain UI 图纸、gap 分析 9 类、三插件整合方案）；UI demo 定稿（今日） |
| 待办缺口 | 真机直连验证未开始（主题模块代码就绪待验收）；HonorInstallerHook.kt 为占位（TargetRegistry.kt:21-23 注释解除即激活）；无挂载汇总 UI 消费、无 debug 日志页、无版本适配脚本、release 未开 minify/无签名 |

---

## 3. 设计

### 3.1 UI 终版（以 demo 为准，逐页）

全局：MiuixTheme + Monet 蓝主色（#3E7BFA）；Squircle 大圆角卡片（26-32dp）+ 卡片阴影；页面大标题（32sp 粗体）；浅/深色双主题；三 tab（模块/应用/设置）与 pager 联动。

**导航壳**：单一 MainActivity + NavHost（main + 二级页栈）。底部**悬浮胶囊底栏双形态**：开启悬浮 = 液态玻璃胶囊（blur + 圆角 999 + 底部悬空，tab 高亮 pill 移动动画）；关闭 = docked 贴底直角（84dp）；形态由主题设置「悬浮底栏」开关切换。

**Tab1 模块页**（大标题 "HonorHook"）：
1. 状态卡：浅绿背景 Squircle + 右侧大对勾圆徽；三行——「已激活」「LSPosed 已启用 · 作用域自动应用」「46 项功能已挂载 · 运行中」；三态（未连接/读取失败/工作中，照 Rain module_status_card）；点击 3 次彩蛋 toast「喵喵喵？」
2. 统计横排两卡：「功能数 8」「已启用 4/8」（实时 = 各功能位 on 计数，demo fcount 逻辑）
3. 设备信息列表 5 行：系统版本 / Android 版本 / LSP 版本（API 102）/ 构建时间 / 设备型号（真实值，非 demo 占位）

**Tab2 应用页**（大标题 "应用"）：
1. 「Hook 总开关」卡（关闭后全部功能恢复原逻辑，上位开关）
2. 分组标题「Hook 目标」+ 4 行 app（badge emoji + 名称 + summary 含已启用 n/N 计数 + ›）：
   - 主题商店（4/4 · 主题/壁纸/字体）、软件包安装程序（0/1 · 安装器净化）、系统界面（0/2 · 液态玻璃）、应用市场（0/1 · 市场净化）
3. 点击行 → **二级页应用功能开关**：topbar 返回键 + app 名 + 功能行列表（icon + 名称 + summary + switch），共 8 功能：
   - 主题商店 4：开启 VIP（到期 2088-08-08）、全部资源免费、试用无倒计时、永不恢复默认（= F1-F4，默认开）
   - 安装器 1：安装器净化（跳过安装检测 · 禁联检测 · **默认关（安全敏感）**）
   - 系统界面 2：液态玻璃恢复（面板/控制中心/通知）、锁屏玻璃（锁屏位图合成链 · **二期**）
   - 市场 1：市场净化（去开屏广告 · 屏蔽弹窗 · 推送管理）
   - 开关状态 → HookSettings 双写 + hook intercept 现读（改动即时生效）

**Tab3 设置页**（大标题 "设置"，Rain 式入口行，5 行）：
1. 深色模式（下拉：跟随系统/浅色/深色）
2. 日志级别（下拉：简略日志/调试——门控 hook DEBUG 日志）
3. 适配目标 ›（二级页）
4. 主题设置 ›（二级页）
5. 关于 ›（版本 v0.1.0，二级页）

**二级页 适配目标**：4 行目标（主题商店 com.hihonor.android.thememanager + 已适配开关；安装器/系统界面/市场标「整合中」无开关；包名作 summary）——v1 为静态列表，不做 Rain 式已装/未装状态机（queries 声明见 3.4 可选）。

**二级页 主题设置**（Rain 三项 + manager 外观组）：
- Rain 三项：悬浮底栏（液态玻璃，联动导航壳双形态）、跟随系统取色（Monet 动态取色 · Android 12+）、隐藏桌面图标（setComponentEnabledSetting，非删图标）
- manager 外观组：「外观」分组——暗色模式（跟随系统/浅色/深色循环 ∨）、主题色（12 色精选色板弹层，列表：默认蓝/红/粉/紫/深紫/靛蓝/青/绿/橙/琥珀/茶/灰蓝；summary「Monet 关闭时生效」）、纯黑模式（AMOLED，仅暗色下 --bg 纯黑 + 卡片 #0C0C0E）

**二级页 关于**：version-badge（HonorHook + 版本 v0.1.0（1）胶囊徽章）+ 模块定位卡 + 开发者卡 + 开源许可行（miuix · libxposed · DexKit）。

交互：miuix Toast（底部胶囊）；状态卡彩蛋照 Rain（3 击）。

### 3.2 三插件整合设计

**功能位体系**（HookSettings.kt FEATURE_DEFAULTS 追加 + 默认值表 + setFeatureEnabled 同款双写）：

| 新键 | 默认 | 语义 |
|---|---|---|
| f5_market_enabled | false | 市场净化总开关（MarketTamer 全套，内部默认即推荐配置，不加 28 子开关） |
| f6_installer_enabled | false | 安装器净化总开关（安全敏感：跳过安全检测削弱防护，UI 副标题明示） |
| f7_glass_enabled | false | 液态玻璃总开关（面板全链） |
| f7_glass_keyguard | false | 锁屏玻璃子位（二期占位） |

总开关（KEY_HOOK_ENABLED）为上位语义：关 = 全部放行原逻辑。

**分批与工作量**（照整合方案 §6，由小到大）：F6 小（1 个 target 文件 ~250 行，最先——最快见真机收益）→ F7 中（面板核心 ~800 行直移，锁屏二期 ~600 行独立）→ F5 大（~1000 行 + 配置机制大砍，分两批：广告/推送/唤醒 → 页面/推荐流）。

**真机基线校验流程**（每插件移植前必做，防「无效还迁移」）：
1. 真机拉取目标 apk（adb pull /system/app 或 Shell 导出）确认**实际安装版本**，与 reference 插件适配版本比对（安装器 MagicOS10 vs 11 混淆完全不同，市场 16.1.6~16.1.8，玻璃 MagicOS 11）
2. 反编译（现有 tools/ apktool + jadx 链），取新版 dex
3. **逐点存在性校验**：把插件源码 hook 点清单（类名+方法+参数形态，从代码实证提取）逐条在新版 dex 检索，每条记 存在 / 漂移 / 缺失
4. **无效不迁移**：缺失/漂移且无 DexKit 兜底可能的点跳过不移植，记录进适配报告（进 docs/）；存在性全部通过才动代码
5. 移植后 armed 日志验证（LSPosed 日志过滤各 target TAG，「类名漂移 = 静默失效」设计必须保留懒加载重试 + 挂载汇总）

**旧 Xposed API → libxposed 102 对应**（照整合方案 §5.4 已实证表）：hookAllMethods → declaredMethods filter 逐个 hook(PROTECTIVE + setId)；XC_Replacement.returnConstant → intercept { chain -> v }；before setResult(null) → intercept 短路；XSharedPreferences 三级副本全部废弃 → getRemotePreferences 每次 intercept 现读（MarketTamer 配置机制整体删除 ~500 行）；Proxy 回调/主线程投递原样保留（安装器 sy1/jz1 链）；禁 Class.forName initialize=true（boot-loop 史），用 loader.loadClass + runCatching。

**scope.list 扩展**（module.prop 已 staticScope=true，全量声明即可）：追加 com.hihonor.appmarket / com.android.packageinstaller（已有）/ com.android.systemui / com.hihonor.android.launcher / com.hihonor.desktop.systemui；health 不加（传感器闸门默认关）。**加 systemui 后 LSPosed 需重启生效**（写入验收判据）。

### 3.3 主题系统完整版（ThemeModeStore 抄 LSPFRIDA）

现状简化版仅暗色三态。升级为完整模型（prefs keys 全部进 HookSettings 或 ThemeModeStore 自有 prefs）：

| 项 | 键 | 值域 | 说明 |
|---|---|---|---|
| 暗色三态 | color_mode | 跟随系统/浅色/深色 | 现有能力保留（isSystemInDarkTheme + 手动覆盖） |
| 种子色 | key_color | String? hex | 12 色精选（demo 色板）；**仅 Monet 关闭时生效**（demo summary 实证） |
| Monet 开关 | monet_enabled | Bool | Android 12+ 门控（低版本 summary 提示）；开 = 主色交系统动态取色 |
| 取色风格 | palette_style | 九值：TonalSpot(默认)/Neutral/Vibrant/Expressive/Rainbow/FruitSalad/Monochrome/Fidelity/Content | 照 Rain ny1 枚举；v1 固定 TonalSpot 不作 UI 选择（无 demo 依据，AVOID 加项） |
| 纯黑模式 | pure_black | Bool | 仅暗色下生效（demo applyBlack 逻辑；--bg #000 / 卡片 #0C0C0E） |
| 悬浮底栏 | floating_bottom_bar | Bool | 联动导航壳双形态 |
| 隐藏桌面图标 | hide_icon | Bool | setComponentEnabledSetting(launcher alias, 2/1) |
| 日志级别 | debug_logging | Bool | 门控 hook 侧 DEBUG 日志（Rain enable_debug_logging 模式） |

装配：MainActivity 已按 color_mode remember 重建（现实现），补 keyColor/monet 依赖 + ThemeController 透传即可；主色无固定值、由 Monet/系统决定（照 Rain，不引入固定色板逻辑）。

### 3.4 工程化

- **git 分层 commit**：每 Task 独立 atomic commit（功能位 / UI 页 / hook 目标各归其 commit）；主题模块已实现代码入基线由并行 git 任务负责，本 spec 不 commit；hook 文件改动严格 pathspec，禁 add -A
- **版本适配脚本**（gap G3，照 fanqie adapt_new_version.sh + apkver.py 瘦身）：`tools/adapt_honor.sh`——adb 拉目标 apk → 二进制解析版本号 → 提取 hook 清单（代码常量表）→ dex 类/方法存在性比对 → 输出「N 个会失效」报告；顺带校验 scope.list 与 TargetRegistry 一致性（防 Rain mipay 式空注册）；每次适配在 hook 文件头部注释记录目标版本（fanqie MainHook 模式）
- **release 签名**（gap S9，发布期）：debug/release 双签名（keystore 用户级，不外泄）；release 开 minify + shrinkResources，proguard-rules.pro 已备（入口 keep + libxposed keep + adaptresourcefilecontents）；打包后 unzip 核对 java_init.list 内容改写生效
- **module.prop 补两行**：exceptionMode=protective + autoHotReload=true（gap S3，全局双保险 + 调试期免手动重启）
- **文档同步**：README 与实现一致（staticScope=true 已改；docs 计数更新）；三插件移植的 README 不符项（降级安装、版本号漂移）不入本模块文档，只留在整合方案文档

---

## 4. 任务分解（10 Task，供 writing-plans 展开）

> 顺序逻辑：T1 真机基线打通主题闭环 → T2 最小移植（F6）验证迁移流程 → T3-T6 UI 按 demo 逐页落 → T7/T8 依 F6 模式放大 → T9 工程化收口 → T10 全量验收。

**T1 真机基线校验（主题模块 v1 验收）**
- 目标：装机 → LSPosed 激活 → scope 勾选 thememanager → LSPosed 日志核对：挂载汇总、按钮态、下载链、挽留弹窗、重启不回退
- 涉及文件：无新代码（发现缺陷另开 fix task）；新增 `docs/device-baseline.md`（真机版本快照：商店/安装器/市场/系统版本）
- 验收：目标商店版本下 installed N/M 全挂；详情页按钮组按 T1/T2/T4/T9 生效；下载链日志见 `productSendFinish success` 且进度推进；无挽留弹窗；重启/切账号后主题不回退。**不通过则不进 T2 之后的任何移植（hook 模式未获真机验证）**

**T2 F6 安装器净化移植**
- 目标：填 HonorInstallerHook.kt 占位（ly1.h 检测总入口替换 / qf2.g 指纹跳过 / Socket.connect 禁联网 / r12 安全模式卡 / AbstractAdBusinessPresenter 推荐条 / PackageInstallerActivity 横幅 / z62 清理提醒 / g82 断网死等，8 组 ~90% 直移；Proxy 回调 + 主线程 post 保留）+ TargetRegistry 解除注释 + f6 功能位 + scope.list 一行 + UI 副标题安全提示
- 涉及文件：`xposed/target/HonorInstallerHook.kt`、`TargetRegistry.kt`、`data/HookSettings.kt`、`scope.list`
- 验收：真机点开即装（无检测转圈）；联网上报被断；f6 默认关；LSPosed 日志 installed 8/8

**T3 UI 导航壳重构**
- 目标：三 tab 重构（模块/应用/设置）+ 悬浮胶囊底栏双形态组件（液态玻璃 style）+ Tab3 设置 5 入口行 + 二级页通用壳（返回键 + 大标题）；删除 NavHost about 路由改二级页栈
- 涉及文件：`MainActivity.kt`、`ui/AppRoot.kt`、`ui/screen/SettingsScreen.kt`、新增 `ui/component/FloatingNavBar.kt`
- 验收：三 tab 可切且 pager 联动；底栏双形态与「悬浮底栏」开关实时联动；二级页返回正常；与 demo Tab3 结构逐行对照一致

**T4 Tab1 模块页**
- 目标：状态卡（三态 + 彩蛋）+ 统计两卡（功能数/已启用，实时计数）+ 设备信息 5 行（真实值）；挂载汇总数据接入（hook 侧统计 → 状态卡消费）
- 涉及文件：`ui/screen/HomeScreen.kt`、`xposed/HookEntry.kt`（汇总 StateFlow/回调）、`data/HookSettings.kt`
- 验收：数字与 LSPosed 日志 installed N/M 一致；未激活框架显示引导态；3 击彩蛋 toast；与 demo Tab1 对照一致

**T5 Tab2 应用页 + 功能位体系接线**
- 目标：Hook 总开关卡 + 4 app 行 + 二级功能开关页（8 功能行）+ f5/f6/f7/f7_keyguard 四键接线（默认全关）+ 计数联动（demo fcount）
- 涉及文件：`ui/screen/HookSettingsScreen.kt`（重构为新应用页）、`data/HookSettings.kt`、`xposed/target/TargetRegistry.kt`
- 验收：开关双写生效且 hook 侧 intercept 现读（改即生效）；默认态 4/8 与 demo 一致；总开关关 = 全部放行

**T6 主题设置二级页 + ThemeModeStore 完整版**
- 目标：Rain 三项（悬浮底栏/跟随系统取色/隐藏桌面图标）+ 外观组（暗色三态循环 ∨ / 12 色板弹层 / 纯黑模式）+ ThemeModeStore 补 keyColor/monet/pure_black/palette_style + MainActivity 装配
- 涉及文件：`ui/screen/ThemeSettingsScreen.kt`、`data/ThemeModeStore.kt`、`MainActivity.kt`
- 验收：12 色板弹层选色即时改主色（Monet 关时）；暗色三态切换即时重建；纯黑仅暗色生效；Monet 开关 Android 12+ 门控；隐藏图标后可从 LSPosed 模块设置重新打开；与 demo 主题设置页对照一致

**T7 F7 液态玻璃面板移植**
- 目标：新建 GlassRestoreHook.kt——TransparentModeManager 材质位（改字段 mBlurType=0x20000）/ isLiquid 兜底 / BlurParametersConfig 面板参数 / ShotBlurView 遮罩 / EdgeLightParamEx 构造器 / MachineConfig 机型强制 HIGH / StyleType.getMaterialType / UiModeManagerEx / ConfigurationEx，面板 10 组大方法直移；同 object 注册 3 键（systemui + launcher×2，内部按 lpparam.packageName 过滤）；f7 键 + f7_glass_keyguard 子位占位（锁屏 ~600 行二期，kdiag 观测先行）；setprop 参数通道保留
- 涉及文件：`xposed/target/GlassRestoreHook.kt`（新建）、`TargetRegistry.kt`、`data/HookSettings.kt`、`scope.list`
- 验收：控制中心/通知面板恢复 LIQUID 渲染；setprop 16 参数 2s TTL 生效；**无 boot-loop**（禁 Class.forName init / 禁硬编码资源 id / 未验证钩子默认关纪律）；锁屏子位默认关且不挂载锁屏链

**T8 F5 市场净化移植**
- 目标：新建 MarketHook.kt 分两批——批 1 广告/推送/唤醒/工具页（SplashAdBlocker/DialogBlocker/PushBlocker/WakeBlocker/ToolPageEntryBlocker）+ 批 2 页面/推荐流（TabStripper/ActivityBlocker 37 组表/MineSectionBlocker/RollWordBlocker/RecommendFeedBlocker/SearchAdBlocker）；多版本钩子并存存档保留；f5 键
- 涉及文件：`xposed/target/MarketHook.kt`（新建）、`TargetRegistry.kt`、`data/HookSettings.kt`、`scope.list`
- 验收：真机开屏广告无；运营弹窗/推送/自启唤醒屏蔽生效；推荐流空、搜索广告位过滤；「搜索应用」与「应用更新」永远可用；配置机制（XSP/3 副本/Provider/ConfigRelay）零残留；f5 默认关

**T9 工程化收口**
- 目标：`tools/adapt_honor.sh` 版本适配脚本（含 apkver 式二进制版本解析 + hook 清单存在性比对 + scope/registry 一致性校验）；release 签名 + minify 启用；module.prop 两行（exceptionMode/autoHotReload）；README 与实现同步
- 涉及文件：`tools/adapt_honor.sh`（新建）、`app/build.gradle.kts`、`proguard-rules.pro`、resources/META-INF/xposed/module.prop、README.md
- 验收：脚本对 thememanager/packageinstaller/systemui 三目标输出失效报告（空报为通过）；release APK 可安装可激活且 java_init.list 打包完整（unzip 核对）；README 无与实现冲突陈述

**T10 真机全量验收**
- 目标：F1-F7 逐项真机过 §5 判据 + UI 与 demo 截图对照 + 挂载基线复核 + 收尾文档 `docs/device-acceptance.md`
- 涉及文件：验收记录文档（新增）
- 验收：§5 全部判据通过；失败项如实记录并拆 next-version 任务，不掩盖

---

## 5. 验收标准

**真机判据（LSPosed 日志 + 肉眼）**：
1. 挂载：每目标 installed N/M 全挂（主题 ~46/46；安装器 8/8；玻璃面板 10 组；市场两批）——日志 TAG 各 target 过滤可见
2. 下载路由触发：付费主题点下载 → 日志 `DownloadItemIfTrial: productSendFinish success ,start To download` + onLoading 推进、进度条走完；无 「httpURLConnection is null」（前置：设备已登录荣耀账户）
3. 挽留弹窗拦截：过期/登出场景无「VIP已过期」弹窗出现（无 OverallRetentionDialogActivity「dialogParams =」日志）；「试用已结束」/到期横幅不出现
4. 不回退：应用付费主题 → 重启 / 切账号 / 等待到期 → 主题保持，无「已恢复默认」回退、试用态不被回收（getThemeApplyState 恒 PERMANENT 生效）
5. F6：第三方 apk 安装秒过（无联网安全检测转圈/指纹验证）；F7：控制中心液态玻璃恢复（肉眼 + `dumpsys` 材质位日志）；F5：市场开屏广告/弹窗/推送无

**F1-F7 逐项**：见 §1 功能位表，每项按对应目标日志 + 真机行为双证。

**UI 一致**：demo 逐页对照——三 tab 结构、8 功能行与默认态（4/8）、设置 5 入口、主题设置（Rain 三项 + 外观组）、底栏双形态、About 三段；与实际渲染截图逐项比对。

**工程**：release APK 可安装可激活；适配脚本可在目标更新后输出失效清单；范围纪律——无 demo 外页面、无第四插件外 hook。

---

## 6. 风险与开放问题（如实标注）

1. **版本错位待根因**：分析基线为商店 20.1.63.305 / 引擎 11.0.1.122；真机实际版本未核（demo 显示 MagicOS 10.0.1）。旧 hook（isBuyBtnShow 系）真机无效教训：任何 hook 点在新版本上须重新实证（T1 门禁）。DexKit 3 API 也未真机验证。
2. **framework 资源待拉**：ROM ThemeProvider 服务侧是否独立校验主题授权未证实——商店 hook 放行后「应用」可能被 ROM 侧拒绝（回退路径的另一源头）；设备 /sdcard 系统资源待 USB 拉取后静态分析。
3. **服务端校验边界**：sendProduct 试用授权有风控可能（H1/H2 通道可能被拒，日志 403/401 判据已备）；付费资源文件 isSpecialEncrypt 解密链路未深挖；「免费次数」计数在服务端任务模型、本地无可 hook 计数器；试用授权前置 = 荣耀账户已登录。
4. **第四插件待确认**：当前整合范围 = 3 插件（F5/F6/F7）；用户侧若另有第四插件候选（如 Rain/LSPFRIDA 式）未在本次范围，需确认后扩 §4。
5. **F7 boot-loop 事故史**：禁 Class.forName initialize=true、禁硬编码资源 id、未验证钩子默认关——三条纪律必须写进移植 task 的红线；全量 AOT 重编译可绕过 hook（`cmd package compile -m interpret-only` 属已知限制，不解决）。
6. **混淆漂移**：安装器 MagicOS10/11 混淆完全不同、市场 16.1.6~16.1.8 多版本——迁移前基线校验流程兜底，迁移后依赖 DexKit 双轨 + 版本适配脚本；类名漂移=静默失效的插件原设计，HonorHook 用显式解析失败 + 懒加载重试升级。
7. **Monet 效果**：miuix 0.9.3 + Monet 蓝主色覆盖范围（Switch/底栏/色板）需真机目检，可能需微调 primaryContainer 透明度。
8. **F6 安全敏感**：跳过安全检测削弱恶意应用拦截——默认关 + UI 副标题明示；用户开责任自负，文档留免责说明。