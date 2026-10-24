# LSPosed Manager UI 规格书（Compose 1:1 翻译底稿）

> 提取自 `reference/LSPosed（框架源码）/app/src/main/`（传统 View + XML）。HonorHook UI 重做的照抄基准。
> 翻译目标：Compose + miuix 组件体系，布局结构/信息架构/条目样式/视觉 token 1:1 对齐。

## 1. 整体骨架与 tab 系统

**架构**：Navigation + BottomNavigationView（activity_main.xml:20-54）。

```
CoordinatorLayout (edgeToEdge)
├─ ConstraintLayout "container" (match×match)
│  ├─ 内容区 "nav_host_fragment" (match×0dp, bottom_toTopOf=nav, bg=?android:colorBackground)
│  └─ BottomNavigationView "nav" (match×wrap, fitsSystemWindowsInsets=bottom, menu=navigation_menu)
```

**Tab 顺序（LSPosed 5 tab，主页居中）**：

| 位置 | 图标（选中实心/未选中描边） | 标题 |
|---|---|---|
| 1 | repo | "Repository" |
| 2 | extension | "Modules" |
| 3 | home | "Overview" |
| 4 | assignment | "Logs" |
| 5 | settings | "Settings" |

- **角标**：Modules tab = 已启用模块数（bg=colorPrimary，文字=colorOnPrimary）；Overview = 有更新时显示。
- HonorHook 映射：**4 tab（无 repo）**——模块｜主页｜日志｜设置；模块 tab 角标 = 已启用功能数。

## 2. Tab 主页（HomeFragment，startDestination）

**外层**：折叠工具栏（大尺寸样式，scrollFlags=scroll|exitUntilCollapsed，titleCollapseMode=scale）内含 [click_view + MaterialToolbar(高=?actionBarSize, elevation=0, collapseMode=pin, logo=app 图标, titleMarginStart=16dp)]；内容区 BorderNestedScrollView。

**内容（竖向 LinearLayout，paddingHorizontal=16dp, paddingVertical=24dp）卡片序列**：

```
├─ status 卡（MaterialCardView Elevated，bg=?colorPrimary，elevation=6dp，阴影 tint 主色）
│  └─ RelativeLayout (padding=24dp)
│     ├─ status_icon (24×24dp，tint=?colorOnPrimary)
│     ├─ status_title (marginStart=24dp，sans-serif-medium，16sp，color=?colorOnPrimary)
│     └─ status_summary (bodyMedium，color=?colorOnPrimary)
├─ warning 卡（Outlined，marginTop=16dp，bg=?colorErrorContainer；标题/正文 onErrorContainer，padding=24dp）
├─ update 卡（Outlined，marginTop=16dp，bg=?colorSecondaryContainer，行尾 MaterialButton）
└─ info 卡（Elevated，marginTop=16dp，padding=24dp）
   └─ 竖向：8 组 标签/值 行（标签 Body1+textColorSecondary；值 Body2+paddingBottom=24dp）
      └─ 末行(gravity=end) borderless MaterialButton "copy_info" (Copy)
```

**状态卡四态**：未安装(error 图标)/已激活(check_circle，副标题=`%s (%d) - %s` 版本名/码/API)/部分激活(warning)/需更新(update)。
**交互**：toolbar 与 click_view 点击 → About 对话框（App 名+版本+链接）；溢出菜单：反馈/关于。
**copy_info** → 剪贴板 + Snackbar "已复制"。

HonorHook 映射：状态卡=模块激活态（XposedService 绑定+框架版本：已激活=check_circle+"LSPosed 版本名 (码) - API"；未连=error+引导）；info 卡=设备信息（LSPosed 版本/API/系统/Android/设备型号/构建时间 + 运行中目标数）+ copy_info；溢出菜单：关于。

## 3. Tab 模块列表（ModulesFragment）

**外层（fragment_pager.xml）**：折叠工具栏（标题+副标题"N module(s) enabled"）+ TabLayout(48dp，单用户时 GONE) + ViewPager2 + FAB（多用户才显示，HonorHook 不需要）。
**页内容**：SwipeRefreshLayout > EmptyStateRecyclerView（空态：`¯\_(ツ)_/¯\nNothing here`，16sp，textColorSecondary，垂直居中）。
**菜单**：SearchView（按 appName/包名/描述过滤；HonorHook 4 条目可省略）。

**item_module.xml 条目（模块列表与 scope 详情共用，7 控件按需显隐）**：

```
FrameLayout (match×wrap, bg=?selectableItemBackground, minHeight=?listPreferredItemHeight,
             paddingVertical=16dp, paddingStart=16dp, paddingEnd=24dp)
└─ ConstraintLayout
   ├─ ImageView "app_icon" (48×48dp，垂直居中，start)
   ├─ TextView "app_name" (marginStart=20dp，16sp，ellipsize=marquee)
   ├─ TextView "app_Package_Name" (ListItemSecondary，maxLines=1，默认 gone)
   ├─ TextView "app_Version_Name" (ListItemSecondary，默认 gone)
   ├─ ExpandableTextView "description" (maxLines=5，ListItemSecondary，默认 gone)
   ├─ TextView "hint" (ListItemSecondary；警示=colorError+medium，推荐=colorPrimary+medium，默认 gone)
   ├─ TextView "version_name" (对齐顶部结束端，maxWidth=100dp，marquee，默认 gone)
   └─ MaterialCheckBox (end 垂直居中，默认 gone，clickable=false)
```

- **整行 alpha：启用 1.0 / 禁用 0.5**；点击 → 详情；长按 → 上下文菜单（可简化省略）。
- **详情页（fragment_app_list）**：折叠工具栏（title=应用名，subtitle=包名，返回键）；列表 = ConcatAdapter[主开关, 条目]。
- **主开关 item_master_switch**：MainSwitchBar "Enable module"（toggle 失败回弹）。
- **scope 条目**复用 item_module：包名行可见；checkbox 可见、点整行 toggle；hint="Recommended"(colorPrimary)/"On denylist"(colorError)；未启用或 denylist 时整行 alpha=0.5；勾选优先排序。

HonorHook 映射（Tab2）：列表 4 条目=目标应用（**48dp 图标用提取的真实 app 图标** honor_ic_theme/installer/systemui/market + 标题 + 一行描述 + 整行 alpha 按功能开关态）；点击进详情：MainSwitchBar 主开关 + 功能位条目（主题商店=F1/F2/F3/F4 四个 checkbox 条目；玻璃/安装器/市场=单主开关+说明）。

## 4. Tab 日志页（LogsFragment）

**外层**同 fragment_pager：折叠工具栏（标题 "Logs"）+ 2 tab（模块日志/详细日志——HonorHook 单 tab"模块日志"即可）；无 FAB；子页 SwipeRefreshLayout 下拉刷新。

**条目 item_log_textview**：

```
MaterialTextView (fontFamily=monospace, textSize=12sp, color=?colorOnSurface,
                  paddingHorizontal=16dp, hyphenationFrequency=fullFast)
```

- **颜色无分级**（所有级别同一 12sp monospace colorOnSurface）；强制 LTR 布局。
**顶部菜单**：Save（ifRoom 图标可见）；溢出：Scroll to top / Scroll to bottom / Clear log now / Word Wrap(checkable)。
**行为**：Save → 文档创建器（HonorHook 简化为系统分享文本）；Clear → service.clearLogs（HonorHook=清 KEY_LOG_RING）；scroll：距端 >500 条跳转否则平滑。

## 5. 全局视觉 token

| Token | 值 |
|---|---|
| 主题 | Material3 DynamicColors 动态取色（HonorHook=现有 Monet/色板体系不变） |
| status 卡 | 主色实底（bg=colorPrimary，文字/图标 onPrimary，elevation 6dp 阴影 tint 主色） |
| 卡片间距/内边距 | 卡间 marginTop=16dp；卡内 padding=24dp |
| 页面水平 padding | 16dp |
| 列表条目 padding | 上下 16dp、左 16dp、右 24dp |
| 图标尺寸 | 列表 48dp；状态卡 24dp |
| 字号 | 列表标题 16sp；日志 12sp monospace；空态 16sp |
| 字重 | 状态卡标题/警示行 sans-serif-medium |
| 状态色 | 警示卡 errorContainer；更新卡 secondaryContainer；列表警示 colorError、推荐 colorPrimary（配 medium） |
| 禁用态 | 整行 alpha 0.5 |
| 空态 | `¯\_(ツ)_/¯` + 提示文案居中 16sp textColorSecondary |
