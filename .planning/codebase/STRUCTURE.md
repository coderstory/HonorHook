---
last_mapped_commit: fe2e820eb97798af089b982b570191734c867fcb
last_mapped_at: 2026-10-11
---
# Codebase Structure

**Analysis Date:** 2026-10-11

## Directory Layout

```
lsposed-plugin/                                   # HonorHook —— 荣耀系统应用净化 / 指定应用会员态模块
├── CLAUDE.md                                     # 协作纪律 + 设备铁律 + 构建 gotchas（中文）
├── README.md                                     # 功能台账 + 安装步骤 + 验证清单
├── .gitmodules                                   # 10 个第三方仓库 submodule（reference/ 只读）
├── .github/
│   └── workflows/ci.yml                           # dev → debug+test+artifact；master → release+GitHub Release
├── app/                                          # Gradle 根工程（./app/gradlew -p app）
│   ├── settings.gradle.kts                        # 阿里云镜像 + mavenLocal(限 io.github.libxposed)
│   ├── build.gradle.kts                          # 仅插件别名声明 apply false
│   ├── gradle/libs.versions.toml                  # 版本目录（AGP 9.2.1 / Kotlin 2.3.21 / libxposed 102 / miuix 0.9.4）
│   ├── gradlew · gradlew.bat · gradle/wrapper/    # wrapper
│   └── app/                                       # 唯一的 Android module（applicationId top.honorhook）
│       ├── build.gradle.kts                       # compileSdk 37 / minSdk 26 / targetSdk 34 / abiFilters arm64-v8a
│       ├── proguard-rules.pro                     # java_init.list 改写 + HookEntry/FeatureCatalog/RemoteConfig keep
│       └── src/
│           ├── main/
│           │   ├── AndroidManifest.xml            # application/Activity/QUERY_ALL_PACKAGES/meta-data
│           │   ├── kotlin/top/honorhook/          # ★ 全部源码（不是 java/，见下方 Special Directories）
│           │   │   ├── FeatureCatalog.kt          #   单一事实源：REMOTE_GROUP + 13 个功能 key
│           │   │   ├── xposed/                    #   ── hook 侧 ──
│           │   │   │   ├── HookEntry.kt           #     libxposed 入口
│           │   │   │   ├── core/                   #     挂载基建（4 文件）
│           │   │   │   │   ├── MountEngine.kt
│           │   │   │   │   ├── MethodResolver.kt
│           │   │   │   │   ├── DexKitHelper.kt
│           │   │   │   │   └── MountScheduler.kt
│           │   │   │   ├── data/                   #     决策表 + 远端开关读取（5 文件）
│           │   │   │   │   ├── RemoteConfig.kt
│           │   │   │   │   ├── CloudSwitchTable.kt
│           │   │   │   │   ├── AdSlotFilter.kt
│           │   │   │   │   ├── BottomTabFilter.kt
│           │   │   │   │   └── InstallerConfigTable.kt
│           │   │   │   └── target/                 #     6 个目标适配器
│           │   │   │       ├── TargetHook.kt       #       适配器接口 + logDisabled
│           │   │   │       ├── TargetRegistry.kt   #       包名→目标 静态注册表
│           │   │   │       ├── InstallerHook.kt    #       com.android.packageinstaller（11 点）
│           │   │   │       ├── SettingsHook.kt     #       com.android.settings（1 点）
│           │   │   │       ├── SystemUiHook.kt     #       com.android.systemui（1 点）
│           │   │   │       ├── LauncherCardTitleHook.kt  # com.hihonor.android.launcher（1 点）
│           │   │   │       ├── exam8/Exam8Hook.kt          # com.exam8.chengrenGK（10 点）
│           │   │   │       └── themestore/                # com.hihonor.android.thememanager（43 点）
│           │   │   │           ├── ThemeStoreHook.kt      #   install + 9 种 MountKind 分派
│           │   │   │           ├── ThemeStoreParts.kt     #   类名常量 / MountTask / 构建器 / 节流日志
│           │   │   │           ├── CollectorsRes.kt       #   F1/F2/F3/F4 挂载表
│           │   │   │           └── CollectorsSource.kt   #   下载链 / 云控 / 槽位 / 底 tab + 运行时
│           │   │   └── ui/                      #   ── UI 侧 ──
│           │   │       ├── HonorHookApp.kt       #     Application + XposedServiceHelper 监听
│           │   │       ├── FrameworkState.kt     #     XposedService Binder 持有 + 激活态
│           │   │       ├── FeatureStore.kt       #     功能开关：本地镜像 + 远端下发
│           │   │       ├── AppSettings.kt        #     外观设置（颜色/Monet/玻璃/悬浮底栏）
│           │   │       ├── MainActivity.kt       #     单 Activity 三 Tab + 玻璃底栏
│           │   │       ├── component/            #     UI 组件（部分 vendored）
│           │   │       │   ├── Theme.kt
│           │   │       │   ├── FloatingBottomBar.kt     ← vendored（KernelSU）
│           │   │       │   ├── InteractiveHighlight.kt  ← vendored
│           │   │       │   ├── DampedDragAnimation.kt   ← vendored
│           │   │       │   ├── DragGestureInspector.kt  ← vendored
│           │   │       │   └── liquid/                    ← vendored（AndroidLiquidGlass）
│           │   │       │       ├── CombinedBackdrop.kt · InnerShadow.kt · Lens.kt · Vibrancy.kt
│           │   │       └── screen/             #     4 个页面
│           │   │           ├── HomeScreen.kt · FeaturesScreen.kt · SettingsScreen.kt
│           │   │           ├── ThemeSettingsScreen.kt · MiuixBackButton.kt
│           │   ├── resources/META-INF/xposed/  #   ★ 模块元数据（不是 assets/）
│           │   │   ├── java_init.list          #     top.honorhook.xposed.HookEntry
│           │   │   ├── module.prop             #     minApiVersion/targetApiVersion 102, staticScope, protective
│           │   │   └── scope.list              #     6 个作用域包名
│           │   └── res/                        #   仅 drawable/mipmap/values —— 无 layout/（全 Compose）
│           └── test/kotlin/top/honorhook/xposed/data/
│               └── DecisionTablesTest.kt        # 4 张决策表的 JVM 回归
├── docs/                                         # 只写"代码读不到的东西"（中文）
│   ├── README.md                                #   docs 索引
│   ├── hook-craft.md                            #   怎么写好 hook（选点原则 / 死点 / 失败分级）
│   ├── reverse-craft.md                         #   怎么逆向（证据标准 / 流程 / 坑）
│   ├── hook-philosophy-manifesto.md              #   架构七条原则（哲学版 + 工程条款版）
│   ├── hook-inventory-audit.md                  #   挂载点台账口径 / 已决删除 / 勿删观察项
│   ├── theme-store.md                           #   主题商店设计（需求 / 策略 / 判据 / 未解决）
│   ├── ui-miuix-overview.md                     #   UI 语义与取舍 / 压缩边界
│   └── reverse/                                 #   每个目标一份逆向记录
│       ├── README.md                            #   6 目标总表 + 能力边界 + 已放弃目标
│       └── theme-store.md · installer.md · settings.md
│           · launcher.md · systemui.md · exam8.md
├── tools/                                       # ★ 唯一获准的构建/安装/设备入口
│   ├── dev.sh                                   #   ~40 子命令（env/build/build-release/test/install/…）
│   ├── README.md
│   ├── adapt_new_version.py · rain-decrypt.py   #   适配脚本
│   └── bin/apktool · bin/jadx                   #   工具垫片（jdk-21/ android-sdk/ platform-tools/ 被 gitignore）
├── reference/                                   # ← 10 个第三方仓库 git submodule，**只读**，不探索
│   └── (LSPosed 框架源码 · HyperChanger · LSPFRIDA · KernelSU 系 · fanqie-purify · apk · skills)
├── .claude/skills/                              # 项目 skill（7 个，含 vendored 的 apk-reverse）
├── system-files/                                # 本地目标 APK / framework.jar（gitignored）
├── skills-lock.json                             # skill 锁文件（gitignored）
├── .buildtmp/                                   # 临时产物（gitignored）
└── .planning/codebase/                          # 本次 GSD 映射输出
    ├── ARCHITECTURE.md
    └── STRUCTURE.md
```

## Directory Purposes

| 目录 | 用途 | 备注 |
|---|---|---|
| `app/app/src/main/kotlin/top/honorhook/xposed/` | hook 侧全部代码：入口、挂载基建、决策表、6 个目标适配器 | 与 UI 侧同进程编译，但运行在不同进程 |
| `app/app/src/main/kotlin/top/honorhook/ui/` | UI 侧全部代码：状态层 + 组件层 + 页面层 | 依赖 Miuix 0.9.4 + Compose MP 1.11.0 |
| `app/app/src/main/kotlin/top/honorhook/FeatureCatalog.kt` | 唯一的跨进程契约（group 名 + 13 个 key） | 纯 Kotlin，双端共用，禁止任一侧手写 key 字符串 |
| `app/app/src/main/resources/META-INF/xposed/` | libxposed 模块元数据三件套 | **不是 `assets/`**；混淆存活由 `packaging.merges` + proguard 保证 |
| `app/app/src/main/res/` | 仅 launcher 图标 + 主题 + `strings.xml` | 无 layout；全部 UI 是 Compose |
| `app/app/src/test/` | JVM 单测（仅决策表） | `tools/dev.sh test` / `./app/gradlew -p app test` |
| `app/gradle/libs.versions.toml` | 版本目录 | 版本号只写这一处 |
| `docs/` | 只记录代码里读不到的：逆向实证 / 为什么 / 外部约束 / 判据 | 中文；类清单、挂载点表一律不进 docs |
| `docs/reverse/` | 每个 hook 目标一份逆向记录 `<app>.md` | 目录规则：新增目标 = 新增一份同名文件 |
| `tools/` | `dev.sh` 是唯一获准的构建 / 安装 / 设备操作入口 | 工具链自解析（项目内优先 → brew/PATH） |
| `.github/workflows/ci.yml` | dev 出 debug + 跑单测；master 出 release 并发 GitHub Release | 单测是真门禁（无 `\|\| true`） |
| `.claude/skills/` | 项目 skill：`honorhook-dev` / `lsposed-hook-expert` / `reverse-*` / `apk-reverse`(vendored) | 方法论详证在这里，不在 docs |
| `reference/` | 第三方仓库 submodule（LSPosed 源码、HyperChanger、LSPFRIDA 等） | **只读**，不探索不修改；代码注释里的 "照 X" 出处 |
| `system-files/` | 目标 APK 与 framework 落盘件（拉包用） | `.gitignore` 排除，大体积可再生 |
| `.buildtmp/` | `dev.sh` 的日志/截图等临时产物 | `.gitignore` 排除 |

## Key File Locations

### Entry Points

| 用途 | 路径 |
|---|---|
| libxposed 模块入口类 | `app/app/src/main/kotlin/top/honorhook/xposed/HookEntry.kt:17` |
| 入口声明（框架读取） | `app/app/src/main/resources/META-INF/xposed/java_init.list:1` |
| 模块描述（API 版本 / 作用域模式 / 异常模式） | `app/app/src/main/resources/META-INF/xposed/module.prop:1-4` |
| 作用域清单（6 个包名） | `app/app/src/main/resources/META-INF/xposed/scope.list:1-6` |
| 目标分发表 | `app/app/src/main/kotlin/top/honorhook/xposed/target/TargetRegistry.kt:16-25` |
| UI 进程入口（Application） | `app/app/src/main/kotlin/top/honorhook/ui/HonorHookApp.kt:11` |
| UI 界面入口（单 Activity 三 Tab） | `app/app/src/main/kotlin/top/honorhook/ui/MainActivity.kt:61` |
| 开发/设备操作唯一入口 | `tools/dev.sh`（`tools/dev.sh help` / `tools/dev.sh env`） |

### Configuration

| 用途 | 路径 |
|---|---|
| 功能 key 与远端 prefs group 单一事实源 | `app/app/src/main/kotlin/top/honorhook/FeatureCatalog.kt` |
| hook 侧远端开关读取（默认全开） | `app/app/src/main/kotlin/top/honorhook/xposed/data/RemoteConfig.kt:31` |
| UI 侧开关写入（本地 + 远端双写） | `app/app/src/main/kotlin/top/honorhook/ui/FeatureStore.kt:52` |
| 外观设置持久化（SP `honorhook_settings`） | `app/app/src/main/kotlin/top/honorhook/ui/AppSettings.kt:53-62` |
| 依赖/版本目录 | `app/gradle/libs.versions.toml` |
| module 构建设置（打包 excludes/merges、abiFilters） | `app/app/build.gradle.kts:11-59` |
| 混淆存活规则 | `app/app/proguard-rules.pro` |
| 声明式清单（权限 / Activity / meta-data） | `app/app/src/main/AndroidManifest.xml` |

### Core Logic

| 用途 | 路径 |
|---|---|
| 挂载基建（重试 / 失败分级 / 汇总日志 / 版本自检） | `app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt` |
| 双轨方法解析（直连反射 → DexKit 兜底） | `app/app/src/main/kotlin/top/honorhook/xposed/core/MethodResolver.kt` |
| DexKit 四维特征查找与桥缓存 | `app/app/src/main/kotlin/top/honorhook/xposed/core/DexKitHelper.kt` |
| 挂载后台线程 | `app/app/src/main/kotlin/top/honorhook/xposed/core/MountScheduler.kt` |
| 主题商店装配与 kind 分派 | `app/app/src/main/kotlin/top/honorhook/xposed/target/themestore/ThemeStoreHook.kt` |
| 主题商店挂载表（资源/会员/试用） | `app/app/src/main/kotlin/top/honorhook/xposed/target/themestore/CollectorsRes.kt` |
| 主题商店挂载表（下载链/云控/裁剪） + 运行时 | `app/app/src/main/kotlin/top/honorhook/xposed/target/themestore/CollectorsSource.kt` |
| 主题商店共享部件与节流判据日志 | `app/app/src/main/kotlin/top/honorhook/xposed/target/themestore/ThemeStoreParts.kt` |
| 包管理器（含检测/指纹直通） | `app/app/src/main/kotlin/top/honorhook/xposed/target/InstallerHook.kt` |
| exam8 会员态与特权门 | `app/app/src/main/kotlin/top/honorhook/xposed/target/exam8/Exam8Hook.kt` |
| 设置 / SystemUI / 桌面 单点目标 | `SettingsHook.kt` / `SystemUiHook.kt` / `LauncherCardTitleHook.kt`（同目录） |
| 4 张决策表（改表 = 改线上行为） | `app/app/src/main/kotlin/top/honorhook/xposed/data/{CloudSwitchTable,AdSlotFilter,BottomTabFilter,InstallerConfigTable}.kt` |
| 功能页 UI（开关列表 + 两个提示卡） | `app/app/src/main/kotlin/top/honorhook/ui/screen/FeaturesScreen.kt` |

### Testing

| 用途 | 路径 |
|---|---|
| 决策表 JVM 回归（唯一测试） | `app/app/src/test/kotlin/top/honorhook/xposed/data/DecisionTablesTest.kt` |
| 运行方式 | `tools/dev.sh test`（= `./app/gradlew -p app test`） |
| CI 门禁 | `.github/workflows/ci.yml:41-42`（dev 分支，无 `\|\| true`） |
| 真机判据 | LSPosed 模块日志 `/data/adb/lspd/log/modules_*.log` 里的 `installed N/M`（`tools/dev.sh log-tag` / `modlog`） |
| 用户手动验证清单 | `README.md:68-75`（AI 不代跑破坏性实测） |
| 挂载点全命名空间复核命令 | `docs/hook-inventory-audit.md:7` |

## Naming Conventions

- **包名**：`top.honorhook`（`namespace` = `applicationId` = 包根）。hook 侧子包 `top.honorhook.xposed.*`，
  UI 侧子包 `top.honorhook.ui.*`，跨进程契约单独留在根包 `top.honorhook`。
- **文件名 = 唯一主类名**（Kotlin 单文件惯例）。例外：`ThemeStoreParts.kt` / `CollectorsRes.kt` /
  `CollectorsSource.kt` 是主题商店的**文件级私有命名**，因为一个文件承载多个 `collect*` 顶层函数。
- **类名后缀**：hook 入口 `HookEntry`；目标适配器 `*Hook`（`ThemeStoreHook` / `InstallerHook` /
  `SettingsHook` / `SystemUiHook` / `Exam8Hook` / `LauncherCardTitleHook`）；抽象基建设 `*Engine` /
  `*Resolver` / `*Helper` / `*Scheduler` / `*Store` / `*State` / `*Filter` / `*Table` / `*Catalog` /
  `*Registry` / `*Settings`；Composable 一律 `*Screen`（`HomeScreen` / `FeaturesScreen` / `SettingsScreen` /
  `ThemeSettingsScreen`）。
- **常量**：`SCREAMING_SNAKE_CASE`（`HOST_VERSION_BASELINE` / `KEY_THEME_VIP` / `REMOTE_GROUP` /
  `PRUNED_SLOT_TYPES` / `SAFE_MODE_ALL_OFF_JSON`）；包级目标类名常量在主题商店用 `CLS_*`（`ThemeStoreParts.kt:20-72`），
  其他目标用 `private const val CLS_*`。
- **功能 key 字面量**：`点分小写`（`theme.vip` / `installer.skip_check` / `exam8.burn_reading` /
  `launcher.hide_card_title` / `systemui.hide_sim2`）——**只允许出现在 `FeatureCatalog.kt`**。
- **挂载点 id（hook 侧日志/去重键）**：`<域>:<类>.<方法>`，域用连字符分组
  （`theme:` / `cloud:` / `adsource:` / `btn-hide:` / `ad-request:` / `ab-request:` / `installer:` /
  `settings:` / `launcher:` / `systemui:` / `exam8:`）。见 `docs/hook-inventory-audit.md:6-8`。
- **判据日志前缀**：每目标一个常量 `JUDGE_PREFIX`（`installer-hook` / `exam8-hook` / `settings-hook` /
  `systemui-hook` / `launcher-hook`），供真机按前缀过滤。
- **文档/注释语言**：中文散文；标识符、路径、命令、类名一律 English/ASCII（`CLAUDE.md:22`）。
- **测试命名**：反引号中文测试名（`` `pruned slot types all match` ``）。
- **行尾**：脚本一律 LF（CRLF 在 bash 3.2 直接报错，`CLAUDE.md:35`）。

## Where to Add New Code

### A. 新增一个目标 app 的 hook（照 `docs/reverse/README.md:57` 的口径，四处必改）

1. **写逆向记录**：`docs/reverse/<app>.md` —— 先有证据（file:line、消费点数、可达性、版本基线），
   再写代码。目录规则：一个目标一份。
2. **写适配器**：`app/app/src/main/kotlin/top/honorhook/xposed/target/<NewTarget>Hook.kt`
   （大目标如主题商店则建 `target/<app>/` 子包拆 `*Hook.kt` + `*Parts.kt` + `Collectors*.kt`）。
   照抄最像的现有实现，`object` 实现 `TargetHook`：
   - `packageName`、`private val engine = MountEngine("<Name>Hook", packageName)`
   - `private const val HOST_VERSION_BASELINE = "<实证版本>"`
   - 私有 `enum class MountKind` + 私有 `class MountTask : MountEngine.Task`（照
     `InstallerHook.kt:277-290` 或 `ThemeStoreParts.kt:75-98`）
   - `collect*()` 建表函数 + `install()` 里用 `RemoteConfig.isEnabled(KEY_*)` 门控
   - `mountOne()` 只做 kind 分派，解析/重试/分级交给 `engine`
3. **注册表加一行**：`app/app/src/main/kotlin/top/honorhook/xposed/target/TargetRegistry.kt:19-24`
   （`register(NewTargetHook)`；`HookEntry` **不需要**改）。
4. **作用域加一行**：`app/app/src/main/resources/META-INF/xposed/scope.list`
   —— 与第 3 步**必须一致**，否则 `onPackageLoaded` 收不到该包回调。
5. **功能目录加条目**：`app/app/src/main/kotlin/top/honorhook/FeatureCatalog.kt:15-32` 加 `KEY_*` 常量，
   在 `targets` 里加 `Target(pkg, 名称, listOf(Feature(...)))`。
   UI 页会自动渲染（`FeaturesScreen.kt` 完全由 `FeatureCatalog` 驱动，无需改 UI 代码）。
6. 若引入新的表驱动常量 → 在 `app/app/src/main/kotlin/top/honorhook/xposed/data/` 建 `*Table.kt`
   并在 `DecisionTablesTest.kt` 补契约测试。
7. README 台账 + `docs/hook-inventory-audit.md` 口径同步；CI release 正文里的宿主基线列表
   （`.github/workflows/ci.yml:66-67`）也含基线串。

> 逆向阶段的操作命令一律走 `tools/dev.sh decompile` / `tools/dev.sh jadx` / `tools/dev.sh apkinfo`。

### B. 新增一个 UI 开关（只影响 hook 行为，不动 UI 代码）

1. `FeatureCatalog.kt`：加 `KEY_<name>` 常量 + 把它加进对应 `Target(...)` 的 `features` 列表。
2. hook 侧：在对应 `*Hook.install()` 里用 `RemoteConfig.isEnabled(FeatureCatalog.KEY_<name>)` 包住
   新的 `collect*()` 调用（单点目标照 `SettingsHook.kt:25-28` 的 `logDisabled` 早返回模式）。
3. UI 侧 **零改动** —— `FeaturesScreen.kt:491-538` 按 `target.features` 动态生成 `SwitchPreference`。
4. 前提：远端写入必须成功。若 `FeatureStore.setEnabled` 返回 `false`，一级页已有
   "LSPosed 未激活" 提示卡（`FeaturesScreen.kt:552-582`）承接，无需新增文案。
5. 若开关需要新参数（如阈值），在 `data/` 建表或在 `Feature` 上加字段（注意 UI 的 `SwitchPreference`
   只有 on/off）。

### C. 新增一个 UI 元素 / 页面

- 页面 → `ui/screen/<Name>Screen.kt`，在 `MainActivity.kt:133-137` 的 `when (selectedTab)` 或
  `SettingsScreen.kt:62-70` 的 `when (page)` 里挂载。
- 通用组件 → `ui/component/`；**如果是从 KernelSU / AndroidLiquidGlass 移植的**，原样 vendored 并在表头
  注明上游 + `vendored` 标记（照 `FloatingBottomBar.kt`、`component/liquid/*`），后续升级才能逐行比对。
- 新的持久化设置 → `ui/AppSettings.kt` 加 `KEY_*` + `setXxx()`（State + SP 双写）。
- 主题相关 → `ui/component/Theme.kt` + `ui/screen/ThemeSettingsScreen.kt`。

### D. 挂载点级新增（改现有目标）

- 在对应 `Collectors*.kt` 的 `collect*()` 里加一条 `addConst` / `addForceArg` / `pending += MountTask(...)`；
  必须填 `id`（`<域>:<类>.<方法>`）与 `description`（判据日志与失败清单用，含功能分组标注）。
- 混淆名目标必须给 `DexHint` 内容锚点（`strings` / `paramTypes` / `classStrings` / `classFieldTypes`）；
  **无锚点就诚实留空** → `NOT_RESOLVED`，不猜类名。
- 新 `MountKind` → 改 `ThemeStoreParts.kt:75`（或目标私有枚举）并在 `mountOne` 的 `when` 里补分支。

### E. 新增一个 dev.sh 子命令

按 `CLAUDE.md:21`：**先加 `tools/dev.sh` 子命令再做**，`tools/dev.sh` 的 `case` 是唯一的命令面；
新命令进 `tools/README.md` 与 skill `honorhook-dev`。脚本一律 LF，`$VAR` 后紧跟中文必须写 `${VAR}`。

## Special Directories

- **`app/app/src/main/kotlin/`（不是 `java/`）** —— 本工程全部 Kotlin 源码在此目录，Android 插件默认 srcSet 已包含
  `src/main/kotlin`，但路径名容易记错；同理模块元数据在 **`src/main/resources/META-INF/xposed/`**（不是 `assets/`）。
- **`app/app/src/main/res/` 无 `layout/`** —— UI 全 Compose；`values/strings.xml` 里的 `module_description`
  是唯一被框架读取的字符串资源（LSPosed Manager 读 `<application>` 的 label/description，`module.prop`
  的 `description=` 不被读取，见 `AndroidManifest.xml:19-25`）。
- **`reference/`** —— 10 个 git submodule（`.gitmodules`），**只读、不探索、不修改**。源码注释里的
  "照 KernelSU / 照 Rain / 照 HC / 照 LSPFRIDA"都指向这里，用于解释 vendored 件与模式出处。
- **`app/app/src/main/kotlin/top/honorhook/ui/component/` 的 vendored 子集** ——
  `FloatingBottomBar.kt` / `InteractiveHighlight.kt` / `DampedDragAnimation.kt` /
  `DragGestureInspector.kt` / `liquid/*` 约 1300 行为上游原样移植。**不重构、不压行**
  （`CLAUDE.md:19`、`docs/ui-miuix-overview.md:32`）。
- **`system-files/`** —— 本地落盘的目标 APK（主题 20.1.63.305、软件包安装程序 14.0.37.301、SystemUI.apk、
  framework.jar 等），`.gitignore` 排除；只用于逆向取证，不参与构建。
- **`.buildtmp/`、`app/build/`、`app/app/build/`、`app/.gradle/`、`local.properties`** —— 全部 gitignored
  的构建/本地配置产物；仓库内**不存在**任何 keystore/jks/pem/key 入库文件，签名走 debug keystore
  （`app/app/build.gradle.kts:32`）。
- **`.claude/skills/apk-reverse/`** —— 第三方 skill `apk-reverse` 的**整份 vendor 副本**（MIT，上游已废弃），
  保留 LSAosed 够不到的层（DEX 虚拟化 / Dart AOT / 服务端门）的深挖路线；原样未改，便于逐行对比上游。
- **`.planning/codebase/`** —— GSD 映射产物（`ARCHITECTURE.md` / `STRUCTURE.md`），非工程代码。

---

*Structure analysis: 2026-10-11*
