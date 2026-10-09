# UI 模板调研报告

> 用途：lsposed-plugin（荣耀系统 hook 模块）app 脚手架搭建的 UI 设计参考留档
> 结论来源：reference 下 LSPosed manager + 三插件（LSPFRIDA / HyperChanger / notification-guard）+ Rain 的实际调研，结论均已实证（file:line 已给出），无需重新查证
> 技术底座：miuix + Compose

## 1. 总决策（已定）

- **UI 参考 LSPosed manager 的结构风格**（布局/导航/卡片/设置呈现），但剔除 manager 专属内容：
  - 激活状态
  - LSPosed 版本
  - 模块列表管理
  - scope 管理
  - 仓库 Repo
  - 全局日志
  - （我们是单一 hook 模块的配置界面，以上都不需要）
- **拼装策略**：
  - hook 入口 / scope / Gradle → 借鉴 HyperChanger
  - UI 体系 → 借鉴 LSPFRIDA（结构最清晰）
  - 荣耀系统 hook 逻辑 → 新写
- **用户确认**：使用 miuix 的话各插件 UI 都差不多，UI 不是选基底的关键差异。

## 2. LSPosed manager UI 结构（主参考）

> 技术栈：XML Views + Material3（rikkax 封装）+ ViewBinding + Navigation Component（fragment）——**非 Compose 非 miuix**。借鉴其结构风格，不照搬代码。

### 2.1 入口与导航

- 主 Activity：`org.lsposed.manager.ui.activity.MainActivity`（AndroidManifest LAUNCHER）
- 手机：BottomNavigationView，5 tab（Repo / Modules / Home / Logs / Settings，`navigation_menu.xml:20-44`，startDestination=Home）+ Navigation Component 二级页 push
- 平板（sw600dp）：NavigationRailView

### 2.2 页面骨架

CoordinatorLayout + AppBarLayout + SubtitleCollapsingToolbarLayout（LargeStyle 可折叠大标题）+ 内容区（ViewPager2 / NestedScrollView / SwipeRefresh+RecyclerView）+ 可选 FAB（`fragment_pager.xml:20-98`）

### 2.3 页面元素

- 模块列表项：`item_module.xml`（图标 + 名称 + 描述 + 版本 + 警告 hint + checkbox）
- 设置页：PreferenceFragmentCompat + PreferenceScreen XML，分 6 组（网络 / 语言 / 主题 / 框架 / 仓库 / 备份）
- 主题：Material3 + DynamicColors 动态取色（Android 12+）+ 19 色选 + 暗色三态（跟随系统 / 亮 / 暗）+ 纯黑模式

### 2.4 miuix 对应映射（manager XML → 本项目 Compose 写法）

| manager（XML） | miuix（Compose） |
| --- | --- |
| CoordinatorLayout + AppBarLayout | Scaffold |
| CollapsingToolbar | LargeTopAppBar |
| MaterialToolbar | SmallTopAppBar |
| BottomNavigationView | NavigationBar + NavigationBarItem |
| ViewPager2 + TabLayout | TabRow + HorizontalPager |
| SwipeRefresh + RecyclerView | LazyColumn |
| MaterialCardView | Card |
| MainSwitchBar | SuperSwitch / SwitchPreference |
| PreferenceFragment | miuix preference 体系（SwitchPreference / DropDownPreference） |

## 3. 三同类插件 UI 对比（miuix + Compose）

三者均为单 Activity + setContent。

| 项目 | 入口写法 | 主题 | 导航 | 特色 |
| --- | --- | --- | --- | --- |
| LSPFRIDA | `MainActivity.kt:101-143` 收集 4 个 StateFlow 构建 ThemeController，`MiuixTheme(controller) { AppRoot() }` | ThemeModeStore（StateFlow：mode 三态 + keyColor 种子色 + paletteStyle + wallpaperMonet），Object + SharedPreferences 持久化 | NavHost（7 路由）+ MainScaffold HorizontalPager 三页 + FloatingMiuixNavigationBar 悬浮底栏 | miuix 用得最深：MiuixPageBackground / UiTokens / GlassSurface 自定义封装 |
| HyperChanger | `MainActivity.kt:209` 极简 `setContent { Root(...) }`，Root 内建 ThemeController | 简化版：settings.themeMode String 三态 | AnimatedContent(tab) + 手动 pageStack（mutableStateListOf\<PageId\> push/pop）+ AnimatedVisibility 嵌套页 | Group / Entry / AppPage / AppList 自定义封装、Backdrop 玻璃拟态、kyant 玻璃下拉/弹窗 |
| notification-guard | `MainActivity.kt:52-57` 两行：`MiuixTheme(colors = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) { GuardApp() }` | 最简：跟随系统、无持久化 | when(tab) 状态切换 + NavigationBar 原生底栏 | 最轻量 |

## 4. 推荐 UI 模板（已定方案）

1. **主题**：LSPFRIDA ThemeModeStore 简化版——Object + MutableStateFlow\<ColorSchemeMode\>（System / Light / Dark 三态）+ SharedPreferences 持久化 + init(context) 在 Application 调用；后续可渐进加 keyColor 种子色。比 NG 的 isSystemInDarkTheme() 多用户选择权，比 HC 的 String 存储类型安全。
2. **导航**：LSPFRIDA 的 NavHost（二级页，`composable("route/{param}")`）+ HorizontalPager（底部三 Tab，保活状态）+ pager↔tab 同步（snapshotFlow）。
3. **底栏**：初版用 miuix 原生 NavigationBar + NavigationBarItem（NG 写法 `MainActivity.kt:103-107`，最简可靠）；进阶再搬 LSPFRIDA FloatingMiuixNavigationBar（`MiuixComponents.kt:105-159`，玻璃胶囊 + pill 滑动指示器）。
4. **页面骨架**：`Scaffold(containerColor = MiuixPageBackground(), contentWindowInsets = WindowInsets(0))` + TopAppBar(title, largeTitle)（大标题页）或 SmallTopAppBar（紧凑页）+ LazyColumn(horizontal=12dp, spacedBy=12dp, bottom=128dp 给底栏留空间)。
5. **设置页分组**：借鉴 HC 的 `Group(title) { SmallTitle + Card { SwitchPreference / ArrowPreference } }`（`MainActivity.kt:3450-3459`）。
6. **必搬组件**：
   - `MiuixPageBackground()`（`MiuixComponents.kt:82-89`，Scaffold 底色，解决深浅色底不一致）
   - UiTokens（`UiTokens.kt:23-54`，统一 PagePadding=12dp / CardSpacing / CardRadius=12dp / 排版字号）
7. **弹窗**：WindowDialog（`ProjectDetailScreenV093.kt:270-298` 确认弹窗写法）。
8. **玻璃拟态**：初版不做（依赖 API 33+ RuntimeShader）；后续需要时搬 LSPFRIDA glassSurface + GlassTopAppBar。
9. **页面规划**（参考 Rain UI + manager 风格）：
   - 首页（模块状态）
   - 适配应用列表（Rain 的 Adapted apps 模式）
   - 设置（Color Mode / 悬浮底栏 / 隐藏图标）
   - 关于
   - Compose 组件速查：
     - Card（cornerRadius = UiTokens.CardRadius）
     - SwitchPreference(checked, onCheckedChange, title, summary)
     - RadioButtonPreference(title, summary, selected, onClick)
     - ArrowPreference(title, summary, onClick)
     - BasicComponent(title, summary, startAction)
10. **edge-to-edge**：LSPFRIDA `MainActivity.kt:84-99` enableEdgeToEdge 透明系栏写法可选搬。

## 5. Rain UI 结构（同类参考）

（strings.xml 证实）

- About 页：开发者 / 版本 / 第三方库 / 链接
- Adapted apps 页：适配应用列表（Installed·%s / Not installed / Not adapted·%s）
- 设置：Color Mode 三态 + Monet、Floating bottom bar、Hide desktop icon
- 悬浮底栏

Rain 与 LSPFRIDA UI 体系同源（均为 miuix）。