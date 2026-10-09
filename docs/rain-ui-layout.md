# Rain 1.1.5 精确 UI 结构图纸（逆向实证）

> 目的：HonorHook UI 对齐 Rain。本文全部结论来自 `system-files/rain-jadx/sources/` 反编译源码 + `rain-apktool/res/values/strings.xml` 实证；源码路径简写为 `defpackage/xxx.java:行号`。Rain 是 Compose + 混淆代码，组件名不可恢复，语义从调用模式+strings 推断处已标注「推断」。

## 1. 导航结构（总图）

```
App 根 (sv.i, sv.java:525)
└─ kv0 导航栈 (mv0.a = List<kv0>，z5.java:174-205)，当前渲染栈顶：
   ├─ gv0(MainTab)  ── 主壳 sv.h（sv.java:288）：3 tab 主界面
   │   └─ np1.c 页面壳：标题槽(o1 case9) + HorizontalPager(g90.b) + 底部导航栏(h70)
   │      ├─ Page0 = ck0.g Module 页（模块状态卡×3）
   │      ├─ Page1 = y92.c Apps 页（推荐说明卡 + app 列表）
   │      └─ Page2 = y92.f Settings 页（6 行设置）
   ├─ bv0(About)         ── o6.b About 页
   ├─ jv0(UsageGuide)    ── y92.k 使用指南页
   ├─ cv0(AdaptedApps)   ── bk0.c 适配应用页
   ├─ hv0(ThemeSettings) ── ck0.j 主题设置页
   └─ iv0(ThirdParty)    ── gw0.H 三方库页（bv0 内入口）
   返回 = mv0.a() 出栈（closeTop）；二级页标题栏带返回键（r0.java:28-86，
   testTag: about_back / adapted_apps_back / theme_settings_back / third_party_back / usage_guide_back）
```

- 三 tab 枚举 qv0 = MODULE(0)/APPS(1)/SETTINGS(2)（qv0.java:12-14）
- tab 标签动态取 string `"tab_"+枚举名小写`（h70.java:152-189）→ tab_module="Module" / tab_apps="Apps" / tab_settings="Settings"（strings.xml:118-120）
- tab 图标 = drawable：ic_home(0) / ic_contacts(1) / ic_settings(2)（sv.J，sv.java:144-163）
- 每页顶部大标题：tab0 = app_name "Rain"，tab1 = "Apps"，tab2 = "Settings"（o1.java:990-1022）——**首页大标题是应用名**
- tab ↔ pager 联动：点击 tab → PagerState.animateToPage（h70.java:249 lf0 反射 "animateToPage(I)V"）；滑动 pager → tab 选中跟随，横滑切 tab
- 底栏两种形态（h70.java:100-104）：`floating_bottom_bar` 关 = 标准 MiuixNavigationBar；开 = FloatingMiuixNavigationBar（底部悬浮 +12dp，h70.java:229）
- 另一个隐藏 pref `enable_blur`（up1.java:66）控制页面内容 blur 过渡（sv.h:333-353 的 z4=tp1.g → en0Var → o6.C 包裹），UI 无可见开关行

## 2. Module 页（tab0，ck0.g → LazyColumn 12dp 间距，ck0.java:1198-1213）

| 卡片 | testTag | 内容 | 证据 |
|---|---|---|---|
| 模块状态卡 | module_status_card | 图标 + 状态标题 + summary + 动作文本，三态：<br>· 未连接：Not connected / Waiting for the framework service / 动作 No framework detected<br>· 读取失败：Read failed / Unable to read framework information / 动作 Check LSPosed settings<br>· 工作中：Working / Framework service connected · %1$s(LSPosed 版本) / 动作 Framework is active | ck0.java:896-909 |
| 适配计数卡 | module_adapted_count | title "Adapted" + 数值 = 已装 ∩ scope.list 的应用数 | ck0.java:1345，1286-1289 |
| 启用计数卡 | module_enabled_count | title "Enabled" + 数值 = LSPosed 已勾选的应用数；读取失败显示 "Status unavailable" | ck0.java:795-813 |

- 计数卡样式：图标(16dp) + title + 大数字（无副标题时数字居中，ck0.i/t0 模式）
- 状态卡点击 = unlockHiddenApps 彩蛋动作（o1.java:192 → ka0Var 传入 ck0.d onclick）——**点模块状态卡解锁隐藏应用**，toast "喵喵喵？"（z5.java:150-169，pref `hidden_apps_unlocked`）

## 3. Apps 页（tab1，y92.c "apps_list" LazyColumn，y92.java:1098）

1. **推荐说明卡**（列表顶部）：title "Recommended apps" + summary "These apps are listed in the LSPosed recommended scope. You can still adjust the scope in LSPosed."（no.java:106-114）；加载中显示 "Reading installed apps…"（no.java:132）
2. **app 行**（y92.b，y92.java:854-1044），只列**已安装** app（按 si0.c 过滤，y92.java:1090），行结构：
   - App 图标（bk0.k，bk0.java:1127，加载 launcher 图标）
   - 标题 = 应用名（资源 app_<包名> 标签，回退包名，y92.m:1908-1928）
   - 副标题状态文本（y92.java:944-991）：
     - Not installed（未安装，理论上不出现在列表）
     - Not adapted · %1$s（已装未适配，%s=版本）
     - Installed · no launcher activity（无启动 Activity）
     - Installed · %1$s（已装已适配，%s=版本）
     - scope 更新中换显示 "Updating LSPosed scope…"
   - **LSPosed scope 开关**：label "Enable in LSPosed"（scope_switch_title），testTag `scope_switch_<包名>`，开=已勾选（y92.java:1019-1035）；异常显示 "LSPosed service is not connected" / "Unable to read the LSPosed scope"
   - **点击行 → 启动该 app**，失败 Toast：The app is not installed / This app cannot be launched directly / Unable to launch the app（o1.java:718-763）
3. 列表无二级页、无长按操作

## 4. Settings 页（tab2，y92.f "settings_list" LazyColumn，y92.java:1228）

| # | 行 | 标题 / summary（string） | 控件 | prefs key | 证据 |
|---|---|---|---|---|---|
| 1 | Language | Language / Choose the interface language | 下拉 [Follow system, 简体中文, English]，切换即 recreate() | language_mode (Int) | pp1:36-46，o1:685-703 |
| 2 | Adapted apps 入口 | Adapted apps / Supported apps, features and versions | 箭头行 testTag adapted_apps_entry → 二级页 | — | qp1:41 |
| 3 | Logging | Logging / Choose log verbosity written to LSPosed. | 下拉 [Concise, Debug] | enable_debug_logging (Bool) | pp1:56-66 |
| 4 | Usage guide 入口 | Usage guide / Module activation and usage steps | 箭头行 usage_guide_entry → 二级页 | — | qp1:81 |
| 5 | Theme settings 入口 | Theme settings / Customize interface options | 箭头行 theme_settings_entry → 二级页 | — | qp1:31 |
| 6 | About 入口 | About / Version v%1$s · %2$s（版本 · Designed by Nairain） | 箭头行 about_entry → 二级页 | — | sp1:41 |

> 观察：**Rain 的设置 tab 只有「入口行 + 语言/日志两个下拉」，具体开关全部收进二级页**——与 HonorHook 内联全部设置相反。

## 5. 二级页

### Theme settings（ck0.j，标题 "Theme settings"，列表 testTag theme_settings_list）
1. **Color Mode 下拉**：Color Mode / Choose the color scheme for the interface；选项 [Follow system, Light, Dark]（ck0.java:1526）| key `color_mode`
2. **Floating navigation bar 开关**：Floating navigation bar / Detach the bottom bar and float it above the content. | key `floating_bottom_bar`
3. **Hide desktop icon 开关**：Hide desktop icon / You can still open this page from LSPosed module settings after hiding it. | 实现 = setComponentEnabledSetting(launcher ComponentName, 2/1)（o1.java:707-717），非删除图标

### About（o6.b，标题 "About"）
- **parallax 收合头**（高度 220dp）：Rain logo + "Rain" + 版本徽章 "Version v%1$s (%2$d)"（testTag about_version，o6.java:488-506）
- **Developer 卡**（about_developer）：区块标题 "Developer"，头像 + "夜雨 | Nairain" + "GitHub · Developer"（nx0:79，ty1:54-56）
- **信息区** 6 行（oj.java:75-163）：System version / App version / Android version(API N) / LSPosed version(含 Not connected、Read failed 态) / Build time(yyyy-MM-dd HH:mm, 硬编码时间戳) / Device model(厂商+型号)
- **Links 卡**（约 about_section_links，t0.java:55）：Repository(GitHub 链接行, testTag link_repository) / Release Channel(Telegram 链接行, link_telegram) / Third-party libraries(入口, about_third_party_entry)；无浏览器时 Toast "No app is available to open this link"
- 第三方库二级页：标题 "Third-party libraries"，列表 third_party_list，行 = "名称 · 版本"（about_lib_summary "%1$s · %2$s"）

### Usage guide（y92.k，标题 "Usage guide"）
- 3 步列表（y92.java:1797，图标 y92.h：Settings.Regular/SelectAll.Light/Update.Regular）：
  1. Enable the module：「Enable Rain in LSPosed and select the target apps in the recommended scope.」
  2. Prepare the apps：「Force-stop and optimize the relevant apps.」
  3. Restart：「Restart the target apps so their hooks take effect.」
- 反馈行：Join the channel for feedback / Found an issue? Join the channel to report it.（Community 图标，no.java:333-353）
- 点赞行：Enjoying Rain? Give it a Star / Every Star is the best encouragement for this project.（Favorites 图标，no.java:479-500）

### Adapted apps（bk0.c，标题 "Adapted apps"）
- 每应用一行：功能文案（usage_guide_feature_*，按包名映射 bk0.java:532-592）+ 适配版本（"All versions supported" 或 "Adapted version: %1$s"，特例 tieba 22.12.1.0 / huawei_health 17.0.8.300 / baidu_netdisk 13.27.0 and later）
- **隐藏应用映射在此暴露**：com.phoenix.read→hongguo(鸿果短剧)、com.kylin.read→hongguo_manju(鸿果漫剧)、com.kmxs.reader→qi_mao(七猫小说)、com.chan.cwallpaper→tuling(图灵)——即解锁 hidden_apps_unlocked 后新增的应用（vv0.f:153-158 加载时传解锁标志）

## 6. 主题系统（Rain 的完整主题模型）

prefs（up1.java:53-69）：`color_mode`(Int) / `language_mode`(Int) / `monet_enabled`(Bool) / `key_color`(Int, Monet 种子色) / `color_style`(String, 默认 "TonalSpot") / `color_spec`(String, "Default" / "SPEC_2025") / `enable_blur`(Bool, 无 UI 行) / `floating_bottom_bar`(Bool) / `enable_debug_logging`(Bool) / `hidden_apps_unlocked`(Bool)

- 主题装配 fw0.a(fb(ColorMode, monetEnabled, keyColor, style·ny1, spec·jy1))（o1.java:156-160）
- **Monet 色板风格枚举 ny1 九值**（ny1.java:7-15）：TonalSpot(默认)/Neutral/Vibrant/Expressive/Rainbow/FruitSalad/Monochrome/Fidelity/Content
- Monet 仅 Android 12+（SDK≥31 检查，ck0.java:1527）；低版本 summary 显示 "Monet colors require Android 12 or later."
- 主色无固定色值——默认由系统/Monet 决定；非 Monet 时用 MiuixTheme 配色

## 7. 数据层（视图模型 vv0，vv0.java:15-104）

- 状态 StateFlow：框架状态(h) / 已装应用列表(j, LazyPagingItems 风格 qb) / 隐藏桌面图标(l) / 隐藏解锁(n) / scope 集合(p)
- 版本信息 ib=(versionName, versionCode)；系统信息 ew1=(api, display, release, manufacturer, model)
- **PACKAGE_ADDED/REMOVED/REPLACED 广播自动刷新应用列表**（vv0.java:84-101）
- 应用列表加载协程 bk0.P（bk0.java:410），按 hiddenAppsUnlocked 决定是否包含隐藏应用

## 8. HonorHook 当前 UI vs Rain 差异表

| 维度 | Rain（真实结构） | HonorHook AppRoot.kt / screens | 差异 |
|---|---|---|---|
| 导航 | 单 Activity + 导航栈（栈顶切页，无 NavHost） | NavHost(main / about 二级路由) | 结构等效；Rain 无路由库 |
| tab 数量/语义 | 3：Module / Apps / Settings | 3：首页 / Hook 设置 / 设置 | **Tab2 语义完全不同**：Rain=推荐+app 列表+scope 开关；HonorHook=Hook 配置开关 |
| Tab1 内容 | 状态卡 + Adapted/Enabled 计数卡 | 状态卡 + Hook 数占位 + 项目简介卡 | Rain 无简介卡，有 2 个计数卡 |
| Tab2 内容 | Recommended apps 说明卡 + 已装 app 行（图标/名/状态/LSPosed 开关/点击启动） | Hook 设置开关页 | Rain Tab2 是「应用管理」，HonorHook 是「功能开关」 |
| Tab3 内容 | 6 行：语言下拉/适配应用入口/日志下拉/使用指南入口/主题设置入口/关于入口 | 主题三态 + Hook 功能 + About 入口 | Rain 只放入口行，开关全进二级页 |
| 二级页 | 5 个（About/Usage guide/Adapted apps/Theme settings/Third-party） | 仅 About | 缺 4 个：使用指南/适配应用/主题设置/三方库 |
| 主题 | 二级页内：Color Mode 下拉 + Monet + 悬浮底栏 + 隐藏图标；9 种 Monet 风格 + spec + key_color | 设置页内联 Color Mode 三态 + 暗色跟随 | Rain 主题更强（Monet/风格/spec），且入口独立成页 |
| 底栏 | 可选悬浮（FloatingMiuixNavigationBar） | 固定 NavigationBar | 缺悬浮选项 |
| 大标题 | tab0 大标题 = "Rain" 应用名 | 首页 largeTitle = 应用名 | 一致 |
| 图标 | drawable ic_home/ic_contacts/ic_settings | MiuixIcons.Home/Layers/Settings | 视觉不同（Rain 是 MIUI 风格矢量图） |
| About | parallax 220dp 大头部 + 版本徽章 + 开发卡 + 信息区 6 行 + 链接卡 + 三方库二级页 | 简化 About（dev + 链接） | 缺 parallax 头部/版本徽章/信息区/三方库页 |
| 特殊功能 | 语言切换(recreate)、日志级别、隐藏桌面图标、scope 开关直管、应用点击启动、隐藏 app 彩蛋 | 均无 | 6 项缺失 |

## 9. HonorHook 对齐建议（按价值排序）

1. **Tab2 改为 Rain 式 Apps 页**（最大差异）：顶部说明卡 + 已装应用列表（图标+名+状态文本 "Installed · %s" 式 + LSPosed scope 开关 + 点击启动），需建 vv0 式 ViewModel + PACKAGE 广播刷新
2. **Tab3 收敛为入口行**：语言下拉、日志下拉、4 个箭头入口（主题设置/适配应用/使用指南/关于），具体设置进二级页（统一返回键+大标题壳）
3. **新增二级页**：Theme settings（Color Mode 下拉 + Monet 开关 + 悬浮底栏 + 隐藏图标）、Usage guide（3 步 + 反馈/点赞行）、Adapted apps（按包名功能/版本文案）
4. **主题升级**：Monet 开关（Android 12+ 门控）+ color_style 九风格 + color_spec，参考 Rain 的 fb/cm/ny1/jy1 结构
5. **悬浮底栏**：floating_bottom_bar 开关 → FloatingMiuixNavigationBar
6. **低成本项**：Module 页补 Adapted/Enabled 计数卡；顶部大标题用应用名；隐藏桌面图标（setComponentEnabledSetting）；语言切换 recreate
7. 彩蛋（unlockHiddenApps）与 enable_blur 可不做

## 10. 可信度标注

- 实证（strings + 代码双重证据）：1-7 节全部结构与文案
- 推断（混淆调用模式）：np1.a=下拉行、np1.f=开关行、m70.b/a=标准/悬浮底栏、g90.b=HorizontalPager、o6.C=blur 包裹、状态卡点击触发彩蛋（ka0Var 链路，逻辑在 z5:150-169 实证）
- 不可读：组件内部 Composer 细节（动画/间距/圆角的具体值）；"Module 页信息区"判断——oj 信息区挂在 About 页（t0.java:113），Module 页无信息区