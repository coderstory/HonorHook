# HonorHook —— 荣耀系统增强 LSPosed 模块

基于 **LSPosed** 框架与 **miuix KMP** UI 框架开发的安卓 Xposed 模块，目标是针对荣耀（Honor / MagicOS）安卓系统进行 hook 定制。模块以标准 APK 形式产出，经 LSPosed Manager 激活后注入目标系统进程；作用域由模块写死（`module.prop` 的 `staticScope=true`，目标在 `scope.list` 中声明），Manager 无需也不可改勾选。

## 技术栈

| 维度 | 选型 |
|------|------|
| LSPosed API | `io.github.libxposed:api:102.0.0`（compileOnly，运行时由框架提供）+ `io.github.libxposed:service:102.0.0` |
| UI 框架 | Jetpack Compose + miuix KMP `top.yukonga.miuix.kmp:0.9.3`（ui / preference / icons） |
| 目标解析 | DexKit `org.luckypray:dexkit:2.2.0` —— **双轨解析**：反射优先，DexKit 特征查询兜底（对抗混淆） |
| 主语言 | Kotlin 2.3.21（AGP 9.x 内置 Kotlin），JVM target 17 |
| SDK | compileSdk 37 / minSdk 26 / targetSdk 34 |

## 工程结构

- **Gradle 工程根 = 本目录**（`app/`），单一模块 `:app`（代码在 `app/app/`），应用包名 `top.honorhook`
- **Hook 入口注册**（新版规范，`src/main/resources/META-INF/xposed/`）：
  - `java_init.list` → 入口类 `top.honorhook.xposed.HookEntry`（继承 `XposedModule`）
  - `module.prop` → `minApiVersion=102 / targetApiVersion=102 / staticScope=true`（静态作用域，目标由 `scope.list` 声明，无需 Manager 手动勾选）
  - `scope.list` → 6 个目标（4 组）：主题商店 `com.hihonor.android.thememanager` / 安装器 `com.android.packageinstaller` / 玻璃三包 `com.hihonor.desktop.systemui` + `com.android.systemui` + `com.hihonor.android.launcher` / 应用市场 `com.hihonor.appmarket`
- **代码分层**（`app/app/src/main/kotlin/top/honorhook/`）：
  - `xposed/` — Hook 入口与解析（`HookEntry` / `DexKitHelper`）
  - `xposed/target/` — 目标分发骨架（`TargetHook` 接口 + `TargetRegistry` 注册表 + 各目标实现，新增目标 = 新建实现 + 注册表追加一行，入口零改动）
  - `data/` — 配置与主题状态（`HookSettings` 远程 prefs 双写 / `ThemeModeStore`）
  - `ui/` — 单 Activity + Compose 界面（`MainActivity` / `AppRoot` / `screen` 分文件，MiuixTheme 包根）

## 构建

```bash
cd app
./gradlew :app:assembleDebug
```

- **JDK 17+**（本机验证用 Adoptium JDK 21，通过 `JAVA_HOME` 指向用户级便携安装）
- `local.properties` 须指向本机 Android SDK：`sdk.dir=D:/software/Android/SDK`（**必须正斜杠**——Java Properties 把反斜杠当转义吃掉会解析失败）
- 依赖镜像已内置于 `settings.gradle.kts`（阿里云镜像四仓 + `mavenLocal` 限定兜底 libxposed），公司网络可直接构建
- 产物：`app/build/outputs/apk/debug/app-debug.apk`

## 当前状态

- [x] 工程主体搭建完成，首次构建通过（app-debug.apk 11.2MB / 3m23s / 零编译错误）
- [x] UI↔hook **配置通信链路**（HookSettings 远程 prefs 双写 + hook 侧热监听 + intercept 现读，HC 模式）
- [x] **DexKit 双轨解析**基建（DexKitHelper 特征查询 + HookEntry 反射优先 / DexKit 兜底，Rain 模式；APK 含四架构 libdexkit.so）
- [x] **目标分发骨架**（TargetHook 接口 + TargetRegistry 注册表；新增目标 = 新建实现 + 注册表追加一行，入口零改动）
- [x] **主题商店 F1-F4 hook**（ThemeStoreHook 46 个挂载点覆盖 F1 开启 VIP / F2 全部资源免费 / F3 试用无倒计时 / F4 永不恢复默认；功能位独立开关 + 懒加载重试 + 挂载汇总，见 `../docs/honorhook-theme-requirements.md`）
- [x] **安装器净化 hook**（HonorInstallerHook 已注册、scope.list 已含 packageinstaller，10 个挂载点，见 `../docs/honor-system-preliminary.md`）
- [x] **玻璃恢复 hook**（GlassRestoreHook 注册 3 键，18 个挂载点 = 面板 10 组）
- [x] **应用市场 hook**（MarketHook 15 个挂载点，真机基线逐点命中）

## 上层目录说明

| 目录 | 内容 |
|------|------|
| `../reference/` | 参考项目 6 个（LSPFRIDA / HyperChanger / notification-guard 三个同类插件 + Rain 高混淆样本 + fanqie-purify 方法论范本 + AI-lsposed-temlate 工程模板），另有 LSPosed 框架源码与 android-skills 插件源（只读） |
| `../docs/` | 调研与逆向报告（rain-analysis、rain-hook-architecture、hc-hook-structure、honor-system-preliminary、ui-template-research、ai-lsposed-temlate-report、skill-audit-fixes、honor-theme-payment-analysis、honorhook-theme-requirements、gap-analysis 共 10 份） |
| `../system-files/` | 荣耀系统资源与逆向产物（Rain 逆向产物 rain-apktool / rain-extract / rain-jadx + rain-strings-decrypted.md + 荣耀主题引擎 APK；荣耀系统文件待 adb 拉取） |
| `../tools/` | 逆向工具链（adb platform-tools / apktool / jadx / rain-decrypt 解密脚本），全部用户级便携 |

## 开发约定（简版）

- 路径全英文（中文路径在 aapt2 / NDK / Windows GBK 终端下踩坑）
- `reference/` 只读；`system-files/` 是研究对象，处理后分析、不改系统
- 工具链用户级便携，不依赖 Windows 管理员权限；测试只真机直连，禁模拟器相关任务
- 完整协作指南见 `../CLAUDE.md`