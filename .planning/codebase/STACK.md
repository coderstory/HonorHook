---
last_mapped_commit: fe2e820eb97798af089b982b570191734c867fcb
last_mapped_at: 2026-10-11
---
# Technology Stack

**Analysis Date:** 2026-10-11

一个 APK 两个运行面的 **LSPosed / Xposed 模块**（libxposed API 102，包名 `top.honorhook`）：
**hook 侧**注入 6 个目标进程（功能默认全开，只改内存态），
**UI 侧**是 Miuix Compose 配置面板（单 Activity 三 Tab，经远端 prefs 下发开关）。
无服务端、无网络栈、无数据库、无 native 代码。

## Languages

| 语言 | 位置 | 说明 |
|---|---|---|
| **Kotlin** | `app/app/src/main/kotlin/top/honorhook/**`（41 个 `.kt`）+ `app/app/src/test/kotlin/top/honorhook/**`（1 个 `.kt`） | 100% Kotlin，**无任何 `.java` 源文件** |
| XML | `app/app/src/main/AndroidManifest.xml`、`app/app/src/main/res/**`、`app/app/src/main/resources/META-INF/xposed/*` | Android 资源与模块元数据 |
| Groovy/Shell | `tools/dev.sh`（bash）、`tools/bin/apktool`、`tools/bin/jadx`（垫片脚本） | 工具链入口，git 跟踪 |
| Python | `tools/adapt_new_version.py`、`tools/rain-decrypt.py` | 逆向期脚本，非构建期 |

代码规模：main 侧 41 个 `.kt` / 6585 行；行数分布见 `docs/ui-miuix-overview.md` §压缩边界
（代码 5130 / 注释 906 / 空行 588；hook 侧 2805 行、UI 侧 3735 行）。

**Gradle / Kotlin DSL 文件（全部构建逻辑在此，禁止加 Groovy 脚本）**

- `app/settings.gradle.kts` — 仓库声明 + `rootProject.name = "HonorHook"` + `include(":app")`
- `app/build.gradle.kts` — 顶层插件声明（`android.application` / `kotlin.compose` 均 `apply false`）
- `app/app/build.gradle.kts` — 唯一模块的全部 `android {}` 与 `dependencies {}`
- `app/gradle/libs.versions.toml` — **版本目录，唯一版本来源**；任何新依赖必须先在此加条目
- `app/gradle.properties` — Gradle JVM 参数与 AndroidX 开关
- `app/local.properties` — 机器本地 SDK 路径（`sdk.dir`），**gitignore，不入库、不外传**

> AGP 9.x 自带 Kotlin 支持：`app/build.gradle.kts` 不声明 `kotlin-android` 插件，
> Kotlin 侧只加载 `org.jetbrains.kotlin.plugin.compose`（与 Kotlin 版本同号）。

## Runtime

| 项 | 值 | 来源 |
|---|---|---|
| Gradle | **9.6.1** | `app/gradle/wrapper/gradle-wrapper.properties`（`mirrors.cloud.tencent.com` 镜像，`validateDistributionUrl=true`） |
| AGP | **9.2.1** | `app/gradle/libs.versions.toml` `[versions] agp` |
| Kotlin | **2.3.21** | 同上 `[versions] kotlin` |
| JDK（构建用） | **21**，且**必须含 `bin/jlink`** | `tools/dev.sh` 顶部解析；release 打包需要 `jlink`，debug 不需要 |
| Java 源码/字节码级别 | **17** | `app/app/build.gradle.kts` `compileOptions` + `kotlin { compilerOptions { jvmTarget } }` |
| compileSdk | **37** | `app/app/build.gradle.kts` |
| targetSdk | **34** | 同上 |
| minSdk | **26** | 同上 |
| versionCode / versionName | `1` / `0.1.0` | 同上；CI 的 Release tag 直接 grep `versionName` |
| applicationId / namespace | `top.honorhook` | 同上 |

**运行期平台**：Android 8.0(API 26) ~ Android 15+。
液态玻璃（`RuntimeShader`）路径由 `MainActivity` 的 `Build.VERSION.SDK_INT >= 33` 守卫，
低版本退回实色底栏 —— 这是 minSdk 26 能跑的唯一原因。

## Frameworks

| 框架 | 版本 | 用途 |
|---|---|---|
| **libxposed API** | **102.0.0**（`io.github.libxposed:api`） | **compileOnly**：hook 侧 `XposedModule` / `XposedModuleInterface` / `XposedInterface.Hooker`，运行期由 LSPosed 框架提供 |
| **libxposed service** | **102.0.0**（`io.github.libxposed:service`） | **implementation**：模块 App 侧 `XposedServiceHelper` / `XposedService`，负责激活态判定与远端 prefs 写入 |
| **LSPosed 模块打包** | `minApiVersion=102` / `targetApiVersion=102` / `staticScope=true` / `exceptionMode=protective` | `app/app/src/main/resources/META-INF/xposed/module.prop` |
| **DexKit** | **2.2.0**（`org.luckypray:dexkit`） | 混淆目标方法特征查找（直连反射轨的兜底线），带 `libdexkit.so`（arm64） |
| **Compose Multiplatform / JetBrains** | `1.11.0`（`org.jetbrains.compose.*`） | UI 底座：ui / foundation / animation / material3(`1.11.0-alpha07`) / material-icons(`1.7.3`) |
| **Miuix** | **0.9.4**（`top.yukonga.miuix.kmp:*`） | 宿主 UI 组件体系（KernelSU Manager 同源），模块名/简介语义取自 `docs/ui-miuix-overview.md` |
| **MaterialKolor** | **5.0.1** | 种子色 → M3 色板（动态取色 / 关键色 / 配色风格 / 色规范） |
| **Android Liquid Glass（backdrop）** | `2.0.0`（`io.github.kyant0:backdrop`） | 真·背景模糊（vibrancy + blur），对齐 HyperChanger |
| **ProGuard / R8** | 随 AGP 9.2.1 | release = 混淆 + 资源收缩 + debug 签名 |
| **JUnit 4** | **4.13.2** | JVM 单元测试（表驱动决策回归，跑 `:app:testDebugUnitTest`，**不需要 Robolectric**） |

## Key Dependencies

全部来自 `app/app/build.gradle.kts` 的 `dependencies {}`，版本一律经 `libs.` 别名取自 `app/gradle/libs.versions.toml`。

### compileOnly

| 别名 | 坐标 | 版本 | 为什么重要 |
|---|---|---|---|
| `libs.libxposed.api` | `io.github.libxposed:api` | 102.0.0 | 只在编译期存在；运行期由 LSPosed 提供。**绝不可打成 `implementation`**，否则类重复。proguard 已配 `-dontwarn io.github.libxposed.annotation.**` |

### implementation

| 别名 | 坐标 | 版本 | 为什么重要 |
|---|---|---|---|
| `libs.libxposed.service` | `io.github.libxposed:service` | 102.0.0 | 模块 App 与框架的唯一通道：`XposedServiceHelper.registerListener`（激活态）+ `getRemotePreferences(group)`（写开关） |
| `libs.dexkit` | `org.luckypray:dexkit` | 2.2.0 | 混淆目标兜底解析；带 `libdexkit.so`，是 `abiFilters` 只留 arm64 的直接原因之一（裁掉约 1.07MB 无用架构 so） |
| `libs.miuix.core` | `top.yukonga.miuix.kmp:miuix-core` | 0.9.4 | Miuix 基础层（主题/配色） |
| `libs.miuix.ui` | `top.yukonga.miuix.kmp:miuix-ui` | 0.9.4 | `Scaffold` / `TopAppBar` / `Card` / `NavigationBar` / `FloatingBottomBar` 的 basic 组件 |
| `libs.miuix.preference` | `top.yukonga.miuix.kmp:miuix-preference` | 0.9.4 | 设置页的 `SwitchPreference` / `ArrowPreference` / `OverlayDropdownPreference` |
| `libs.miuix.icons.android` | `top.yukonga.miuix.kmp:miuix-icons-android` | 0.9.4 | Miuix 图标 |
| `libs.miuix.blur` | `top.yukonga.miuix.kmp:miuix-blur` | 0.9.4 | `Backdrop` / `LayerBackdrop` / `blur` / `lens` / `vibrancy`；**声明 minSdk 33**，靠 manifest `tools:overrideLibrary` 覆盖到 26 |
| `libs.material.kolor` | `com.materialkolor:material-kolor` | 5.0.1 | 种子色 → M3 色板 |
| `libs.compose.ui` / `.foundation` / `.animation` | `org.jetbrains.compose.ui:ui` / `:foundation` / `:animation` | 1.11.0 | 原生 Compose 底座 |
| `libs.compose.material3` | `org.jetbrains.compose.material3:material3` | **1.11.0-alpha07** | JetBrains material3 只发 alpha 线（Central 无 1.11.0 稳定版） |
| `libs.compose.material.icons.core` / `.extended` | `org.jetbrains.compose.material:material-icons-core` / `-extended` | 1.7.3 | core 只有几十个基础图标；KernelSU 首页用的 Tag / Security / Smartphone / Fingerprint / DeveloperBoard / Block 全在 extended |
| `libs.backdrop` | `io.github.kyant0:backdrop` | 2.0.0 | 真·背景模糊（API 33+ 走 RuntimeShader，低版本降级磨砂） |
| `libs.activity.compose` | `androidx.activity:activity-compose` | 1.12.3 | `ComponentActivity` / `setContent` / `enableEdgeToEdge` / `BackHandler` |
| `libs.lifecycle.runtime.compose` | `androidx.lifecycle:lifecycle-runtime-compose` | 2.10.0 | Compose 生命周期绑定 |
| `libs.androidx.core.ktx` | `androidx.core:core-ktx` | 1.17.0 | AndroidX 基础扩展 |

### testImplementation

| 别名 | 坐标 | 版本 | 为什么重要 |
|---|---|---|---|
| `libs.junit` | `junit:junit` | 4.13.2 | 唯一测试依赖；`app/app/src/test/kotlin/top/honorhook/xposed/data/DecisionTablesTest.kt` 钉死四张表的契约（表内必命中 / 表外走安全默认），CI 的**真门禁** |

### vendored（源码级移植，无 jar）

**没有 `libs/` 目录、没有 vendored jar/aar**。需要「对齐上游」的移植件是**源码**，
约 1300 行，均带 `// vendored：` 首行标记，**禁止改写**（改写会与上游脱钩、升级无法逐行比对）：

| 文件 | 上游 |
|---|---|
| `app/app/src/main/kotlin/top/honorhook/ui/component/FloatingBottomBar.kt` | compose-miuix-ui example（IosLiquidGlassNavigationBar），Apache 2.0 |
| `app/app/src/main/kotlin/top/honorhook/ui/component/liquid/{Backdrop→CombinedBackdrop,InnerShadow,Lens,Vibrancy}.kt` | Kyant0/AndroidLiquidGlass，Apache 2.0 |
| `app/app/src/main/kotlin/top/honorhook/ui/component/{InteractiveHighlight,DampedDragAnimation,DragGestureInspector}.kt` | 同上体系 |

### 构建期外部二进制（gitignore，不入库）

| 路径 | 用途 |
|---|---|
| `tools/apktool.jar` | `tools/dev.sh decompile` 用（需 JDK 21 运行） |
| `tools/jadx/`（跨平台 bundle，入口 `bin/jadx`） | `tools/dev.sh jadx` |
| `tools/bin/apktool`、`tools/bin/jadx` | **垫片**：把上述产物暴露成 PATH 上的可执行名 |

## Configuration

### gradle.properties（`app/gradle.properties`）

```
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

未开 `android.enableJetifier`、未开 `android.suppressUnsupportedCompileSdk`（compileSdk 37 在 AGP 9.2.1 支持范围内）。

### 仓库（`app/settings.gradle.kts`）

- `pluginManagement` 与 `dependencyResolutionManagement` **全部走阿里云四仓**（公司网络下 `services.gradle.org` / `plugins.gradle.org` 不可靠）。
- 额外保留 **Maven Central 直连** `https://repo1.maven.org/maven2`：JetBrains 的 `material3` / `material-icons` 只在 Central 有（阿里云未同步），国内不通时靠阿里云仓兜底。
- **mavenLocal 限定 group**：`includeGroup("io.github.libxposed")` —— 离线兜底，本地 `~/.m2` 有货时优先命中，且不会污染其他 group 的解析。
- `repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS`（禁止模块自建仓库）。

### 签名（`app/app/build.gradle.kts` `buildTypes.release`）

- `signingConfig = signingConfigs.getByName("debug")` —— **release 用 debug 签名直出**（本机无正式 keystore；仓库内**不存在**任何 `.jks` / `.keystore`）。
- 需要正式签名时改这一行即可，其余构建逻辑不依赖签名。
- CI（`.github/workflows/ci.yml`）同样不注入 keystore，跑的是 debug 签名。

### ABI（`defaultConfig.ndk.abiFilters`）

```kotlin
ndk { abiFilters += listOf("arm64-v8a") }
```

只保留真机 arm64：x86/x86_64（模拟器）与 armeabi-v7a（32 位）的 `libdexkit.so` 全部裁掉（约 1.07MB）。
项目只做真机直连验证（`CLAUDE.md` 设备铁律），无模拟器需求。**加 ABI 前先确认是否还有模拟器验证需求。**

### packaging（**元数据存活的关键**，`app/app/build.gradle.kts`）

```kotlin
packaging {
    resources {
        merges += "META-INF/xposed/*"
        merges += "META-INF/services/*"
        excludes += "**"
    }
}
```

- `excludes += "**"`：默认排除全部非代码资源，产物体积可控（体积分布用 `tools/dev.sh size` 看）。
- `merges += "META-INF/xposed/*"`：**显式重新放行 Xposed 模块元数据**。若不写，AGP 的资源合并/收缩会把 `java_init.list`（纯文本、不参与代码引用图）连同整个 `META-INF/xposed/` 一起裁掉 —— LSPosed 扫不到入口类，**静默不加载模块且构建零报错**。
- `merges += "META-INF/services/*"`：放行 ServiceLoader 配置（coroutines 的 `MainDispatcherFactory` 等），否则运行期 `Dispatchers.Main` 可能失效。
- **改动 `packaging` 或 proguard 必须回归 release 包的 `META-INF/xposed/` 元数据**（解包检查 `java_init.list` 与 `scope.list` 仍在）。

### ProGuard / R8（`app/app/proguard-rules.pro`，35 行）

| 规则 | 作用 |
|---|---|
| `-dontwarn io.github.libxposed.annotation.**` | 注解类只存在于 compileOnly jar，运行期无类 |
| `-adaptresourcefilecontents META-INF/xposed/java_init.list` | R8 同步改写 list 里的类名（兜底；下面两条保证它实际不触发） |
| `-keep,allowoptimization public class top.honorhook.xposed.HookEntry { public <init>(); }` | **具名 keep，禁止 `allowobfuscation`**。通配 + `allowobfuscation` 是锁步脆弱设计：R8 一旦把入口改名进默认包，全靠上面那条指令同步；AGP/R8 升级改变改名行为时 LSPosed 静默跳过模块且构建零报错。具名固定后 debug/release 日志过滤口径一致 |
| `-keepclassmembers class top.honorhook.xposed.HookEntry { onPackageLoaded(...); onPackageReady(...); }` | 两个覆写方法被 LSPosed 反射调用、R8 无静态调用方可见；`onPackageReady` 空实现已被 dead-code 消除 |
| `-keep class top.honorhook.ui.HonorHookApp / MainActivity { <init>(); }` | manifest 已自动 keep，此处为冗余防御 |
| `-keep class top.honorhook.FeatureCatalog { *; }` | 跨进程共用：宿主写、hook 侧读远端 key 字面量一致 |
| `-keep class top.honorhook.xposed.data.RemoteConfig { *; }` | 同上 |

release 另开 `isShrinkResources = true`，proguard 基线为 `getDefaultProguardFile("proguard-android-optimize.txt")`。

### 其他配置面

| 文件 | 作用 |
|---|---|
| `app/app/src/main/resources/META-INF/xposed/java_init.list` | 入口类全限定名，单行 `top.honorhook.xposed.HookEntry` |
| `app/app/src/main/resources/META-INF/xposed/scope.list` | 静态作用域 6 个包名；**与 `TargetRegistry` 不一致则 `HookEntry` 收不到该包回调** |
| `app/app/src/main/resources/META-INF/xposed/module.prop` | `minApiVersion` / `targetApiVersion` / `staticScope` / `exceptionMode` |
| `app/app/src/main/AndroidManifest.xml` | `QUERY_ALL_PACKAGES`（Android 11 包可见性，读宿主 app 的 label/icon）、`tools:overrideLibrary="top.yukonga.miuix.kmp.blur"`（minSdk 33 → 26）、`<application>` 的 `label` / `description`（**LSPosed Manager 实际读取的字段**，`description` 必须是 `@string` 字面量引用，否则 aapt2 拒绝） |
| `app/app/src/main/res/values/strings.xml` | 只承载 `module_description`（Manager 展示用），文件仅 9 行 |
| `.gitattributes` | `* text=auto eol=lf` —— 脚本一律 LF，CRLF 在 bash 3.2 直接报错 |
| `.gitignore` | 排除 `system-files/`、`tools/` 二进制目录、`app/local.properties`、`app/app/build/`、`.buildtmp/`、native 遗留 `app/app/src/main/cpp/` |
| `.gitmodules` | 11 个 `reference/` 只读第三方子模块（LSPosed 框架源码、HyperChanger、LSPFRIDA、fanqie-purify 等） |
| 代理 | `app/gradle.properties` 无代理；`CLAUDE.md` 记环境已配 `http.https://github.com.proxy = socks5://127.0.0.1:7897` |

## Platform Requirements

### 开发机（macOS）

| 组件 | 要求 | 自解析方式（`tools/dev.sh`） |
|---|---|---|
| **JDK 21** | **必须含 `bin/jlink`**（release 打包需要）→ 只认完整 JDK，不认 JRE | 项目内 `tools/jdk-21/` → brew `openjdk@21` 的 `libexec/openjdk.jdk/Contents/Home`（`/opt/homebrew` 或 `/usr/local`） |
| **Android SDK** | 含 build-tools（提供 `aapt2` / `apksigner` / `zipalign` / `dexdump`），许可证已接受 | `tools/android-sdk/`（`app/local.properties` 的 `sdk.dir` 指向此处）；`dev.sh aapt2` 自动取最高版本 |
| **adb** | USB 直连真机 | `tools/platform-tools/adb`（或 Windows `adb.exe`）→ PATH（brew `android-platform-tools`） |
| **apktool** | 需 JDK 21 运行 | `tools/apktool.jar` |
| **jadx** | 跨平台 bundle | `tools/jadx/`（入口 `bin/jadx`） |
| **python3 + Pillow** | 仅 `dev.sh enable-module` 需要（screencap 像素定位） | `HONORHOOK_PYTHON` > 工程内 venv > PATH；缺失时该子命令报错，其余不受影响 |

**所有构建/安装/设备/反编译操作走 `tools/dev.sh`**（子命令索引跑 `tools/dev.sh help`，路径跑 `tools/dev.sh env`）。
`CLAUDE.md` 明令**禁止手拼 gradle / adb 命令**。新增操作先加 `dev.sh` 子命令再做。

**NDK**：工程**没有** C/C++ 源（`app/app/src/main/cpp/` 为空且被 gitignore，native hook 路线已中止），无需 NDK。

### 目标设备

| 项 | 要求 |
|---|---|
| 机型 | **真荣耀（Honor / MagicOS）手机**，USB 直连 adb，**不接模拟器**（`abiFilters` 只留 arm64） |
| 系统 | root + **LSPosed（Zygisk 变体）已安装**，模块需在 LSPosed 管理器中**启用** |
| 作用域 | 6 个宿主包全部勾选（见 `app/app/src/main/resources/META-INF/xposed/scope.list`） |
| 判据 | **LSPosed 框架日志 `/data/adb/lspd/log/modules_*.log`**（经 `su` 读取），**不在 logcat** —— 判据行 = `ThemeStoreHook: installed 43/43` 等 |
| 铁律 | **禁止 reboot**（任何形态）；禁破坏性/难回退操作；不在用户主力机实测未验证 hook 的破坏性效果 |

### 6 个被注入的目标进程

| # | 包名 | 应用 | hook 实现 | 基线版本 | 挂载点 |
|---|---|---|---|---|---|
| 1 | `com.hihonor.android.thememanager` | 主题商店 | `app/app/src/main/kotlin/top/honorhook/xposed/target/themestore/ThemeStoreHook.kt`（+ `CollectorsRes.kt` / `CollectorsSource.kt` / `ThemeStoreParts.kt`） | 20.1.63.305 | 43 |
| 2 | `com.android.packageinstaller` | 包管理器 | `app/app/src/main/kotlin/top/honorhook/xposed/target/InstallerHook.kt` | 14.0.37.301 | 11 |
| 3 | `com.android.settings` | 设置 | `app/app/src/main/kotlin/top/honorhook/xposed/target/SettingsHook.kt` | 14.0.0.210 | 1 |
| 4 | `com.exam8.chengrenGK` | 成人高考题库 | `app/app/src/main/kotlin/top/honorhook/xposed/target/exam8/Exam8Hook.kt` | 5.9.0.0-release | 10 |
| 5 | `com.hihonor.android.launcher` | 系统桌面 | `app/app/src/main/kotlin/top/honorhook/xposed/target/LauncherCardTitleHook.kt` | 18.0.0.234 | 1 |
| 6 | `com.android.systemui` | SystemUI | `app/app/src/main/kotlin/top/honorhook/xposed/target/SystemUiHook.kt` | 17.0.0.1 | 1 |

基线版本以各 Hook 文件的 `HOST_VERSION_BASELINE` 常量为准（`MountEngine` 每进程做一次版本自检，
不一致打 WARN，**判据仍以 `installed N/M` 为准**）。

### 新增依赖 / 新增 hook 的落点

- **新依赖** → `app/gradle/libs.versions.toml` 加条目 → `app/app/build.gradle.kts` 引用别名（版本号不得写两处）。
- **新目标 app** → 在 `app/app/src/main/kotlin/top/honorhook/xposed/target/` 新建 `XxxHook.kt` 实现 `TargetHook`
  → `TargetRegistry` 注册一行 → `scope.list` 同步包名 → `FeatureCatalog` 加 `Target`/`Feature` 条目。
  **`HookEntry` 零改动**（见 `app/app/src/main/kotlin/top/honorhook/xposed/HookEntry.kt` 类注释）。
- **新挂载点** → 优先改 `app/app/src/main/kotlin/top/honorhook/xposed/data/` 下的四张表
  （`CloudSwitchTable` / `AdSlotFilter` / `BottomTabFilter` / `InstallerConfigTable`），表即真相，且被 `DecisionTablesTest` 覆盖。
- **新 UI 页面** → `app/app/src/main/kotlin/top/honorhook/ui/screen/`，在 `MainActivity.kt` 的 `AppRoot` 里挂 Tab。

*Stack analysis: 2026-10-11*
