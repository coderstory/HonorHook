---
last_mapped_commit: fe2e820eb97798af089b982b570191734c867fcb
last_mapped_at: 2026-10-11
---
# External Integrations

**Analysis Date:** 2026-10-11

本模块**没有**任何后端服务、HTTP 客户端、推送/WebSocket、数据库或第三方登录。
唯一的"外部"是三类：**LSPosed 框架**（模块 API + Binder + 远端 prefs）、
**被注入的宿主应用进程**（Java 层反射 + 方法拦截）、**开发期设备工具链**（adb / jadx / apktool / LSPosed 日志）。

## APIs & External Services

### 1. LSPosed 框架（libxposed API 102）

| 面 | 用到的东西 | 文件 |
|---|---|---|
| 模块入口 | `io.github.libxposed.api.XposedModule` 子类 `HookEntry`，覆写 `onPackageLoaded(PackageLoadedParam)` | `app/app/src/main/kotlin/top/honorhook/xposed/HookEntry.kt` |
| 注册 | `META-INF/xposed/java_init.list`（一行全限定名）+ `module.prop`（`minApiVersion=102` / `staticScope=true` / `exceptionMode=protective`）+ `scope.list`（6 包静态作用域） | `app/app/src/main/resources/META-INF/xposed/{java_init.list,module.prop,scope.list}` |
| 拦截 API | `module.hook(method)` → `.setExceptionMode(PROTECTIVE)` → `.setId(task.id)` → `.intercept { chain -> ... }`（`XposedInterface.Hooker`）；统一封装在 | `app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt`（`mount()`） |
| 日志 API | `module.log(Log.INFO/WARN/ERROR, "HonorHook", ...)` —— **走框架日志而非 logcat**，唯一判据来源 | `MountEngine.logSummary/judge`、`TargetHook.kt` 的 `logDisabled` |
| 远端 prefs | `XposedModule.getRemotePreferences(FeatureCatalog.REMOTE_GROUP)`（protected，仅模块子类可调） | hook 侧：`HookEntry.kt:21`；读取器 `app/app/src/main/kotlin/top/honorhook/xposed/data/RemoteConfig.kt` |
| Service 绑定 | `XposedServiceHelper.registerListener(this)` + `XposedService`（`apiVersion` / `frameworkName` / `frameworkVersion`），**收到 Binder 才算激活** | `app/app/src/main/kotlin/top/honorhook/ui/HonorHookApp.kt`；状态汇总 `app/app/src/main/kotlin/top/honorhook/ui/FrameworkState.kt` |
| 版本探测 | `XposedService.API_102` 常量（`publish()` 异常兜底分支用） | `FrameworkState.kt:63` |
| Manager 元数据 | LSPosed Manager 实际读 `<application>` 的 `label` / `description`（不是 `module.prop` 的 `description=`） | `app/app/src/main/AndroidManifest.xml`（注释含框架源码 file:line 实证） |

**libxposed api 是 `compileOnly`**（`app/app/build.gradle.kts`）—— 运行期由框架注入类，打进 APK 会类冲突。

### 2. 被注入的 6 个宿主进程

代码侧枚举点共四处，**必须保持一致**：

| 位置 | 形态 |
|---|---|
| `app/app/src/main/resources/META-INF/xposed/scope.list` | LSPosed 静态作用域（不一致 → 框架根本不给 `onPackageLoaded` 回调） |
| `app/app/src/main/kotlin/top/honorhook/xposed/target/TargetRegistry.kt` | 运行期注册表（`init` 里 6 个 `register(...)`；`find()` 未命中即静默跳过） |
| `app/app/src/main/kotlin/top/honorhook/FeatureCatalog.kt` | UI 功能目录（`targets` 列表含包名 + 功能 key） |
| 各 `*Hook.kt` 的 `override val packageName` | 目标自述 |

| 包名 | 应用 | 逆向基线 | 挂载点 | 指令/文档 |
|---|---|---|---|---|
| `com.hihonor.android.thememanager` | 主题商店 | 20.1.63.305 | 43 | `docs/reverse/theme-store.md`、`docs/theme-store.md` |
| `com.android.packageinstaller` | 包管理器 | 14.0.37.301 | 11 | `docs/reverse/installer.md` |
| `com.android.settings` | 系统设置 | 14.0.0.210 | 1 | `docs/reverse/settings.md` |
| `com.exam8.chengrenGK` | 成人高考题库 | 5.9.0.0-release | 10 | `docs/reverse/exam8.md` |
| `com.hihonor.android.launcher` | 系统桌面 | 18.0.0.234 | 1 | `docs/reverse/launcher.md` |
| `com.android.systemui` | SystemUI | 17.0.0.1 | 1 | `docs/reverse/systemui.md` |

台账权威 = 真机 `installed N/M` 的 M（`docs/hook-inventory-audit.md`，合计 67）；
基线值以各 Hook 的 `HOST_VERSION_BASELINE` 常量为代码侧准绳，`MountEngine.checkHostVersionOnce` 每进程比对一次。

**放弃的边界（勿重新评估）**：system_server 守卫（libxposed api 102 无 `onSystemServerLoaded` 回调类型）；
exam8 试卷正文由服务端按订单态下发（`api.wantiku.com/api/Sprint/Papers`，hook 只能过 UI 门、取不到正文）—— 见 `docs/reverse/README.md` §二、§三。

### 3. 宿主 App 的 Android 框架面

- `PackageManager.getPackageInfo(pkg, 0).versionName` —— `MountEngine.checkHostVersionOnce`（经 `Class.forName("android.app.ActivityThread").currentApplication()` 反射取 Context，`PackageLoadedParam` 时机 Application 尚未创建）。
- `ActivityThread.currentApplication()` 反射调用 —— 同上（这是唯一一处主动反射 Android 私有 API）。
- `QUERY_ALL_PACKAGES` 权限 + `getApplicationInfo()` —— UI 侧读 6 个宿主 app 的 label/icon 渲染功能列表（`app/app/src/main/AndroidManifest.xml` 注释）。

### 4. DexKit（混淆目标兜底解析）

| 项 | 内容 |
|---|---|
| 坐标 | `org.luckypray:dexkit:2.2.0`（`app/app/build.gradle.kts` `implementation(libs.dexkit)`） |
| 使用点 | `app/app/src/main/kotlin/top/honorhook/xposed/core/DexKitHelper.kt`（`System.loadLibrary("dexkit")` 懒加载、`DexKitBridge.create(apkPath)` 进程级缓存、四维锚点匹配器） |
| 调用方 | `app/app/src/main/kotlin/top/honorhook/xposed/core/MethodResolver.kt`（**双轨解析**：第一轨直连反射 → 失败才走 DexKit） |
| 四维锚点 | `usingStrings`（方法体字符串常量，**最稳**）、`paramTypes`（参数类型序列）、`declaredClassStrings`（所在类字符串）、`declaredClassFieldTypes`（所在类字段类型） |
| **内容锚点是硬要求** | 默认包类（`className` 不含 `.`，如混淆名 `u15` / `o52`）**无包名可限定搜索范围**，若四维锚点全空则**诚实跳过并记 WARN**（`MethodResolver.kt:93-99`），**不猜类名硬兜** |
| 缓存 | 按 ClassLoader 分桶的弱键命中/未命中缓存（`MethodResolver.hitBuckets` / `missBuckets`），避免版本漂移时重复秒级磁盘扫描 |
| 挂载点侧声明 | `MountEngine.DexHint`（`app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt`），各 Hook 在 `addConst` / `addConditional` / `addBlock` 时传 `dex = DexHint(...)`；现存 19 处（installer 10 / themestore 7 / settings 1 / systemui 1） |

### 5. 失败分级（`MountEngine.Result`，与 DexKit 兜底强耦合）

| 级别 | 语义 | 处置 |
|---|---|---|
| `OK` | 解析并挂载成功 | 计入 `installed N/M` |
| `NOT_RESOLVED` | 类 / 方法 / `resolveValue` 依赖值未就绪 | **随类加载重试**（`MountScheduler.postDelayed`，最多 2 轮：500ms → 2000ms） |
| `FAILED` | hook 挂载抛异常 | **重试无意义**，记 WARN 不阻断同批其他任务 |

依据：`app/app/src/main/kotlin/top/honorhook/xposed/core/MountEngine.kt`、`docs/hook-craft.md` §四。

## Data Storage

只有 `SharedPreferences`，共三处，**无 Room / SQLite / DataStore / 文件存储**。

| 存储 | group / 文件名 | 写入方 | 读取方 | 用途 |
|---|---|---|---|---|
| 本地功能镜像 | `honorhook_features` | `app/app/src/main/kotlin/top/honorhook/ui/FeatureStore.kt`（`states: MutableStateMapOf` Compose 可观察镜像） | 同进程（`isEnabled`） | UI 即时显示；**框架未连接也能读** |
| 本地外观设置 | `honorhook_settings` | `app/app/src/main/kotlin/top/honorhook/ui/AppSettings.kt`（颜色模式 / Monet / 关键色 / 配色风格 / 色规范 / 玻璃开关 / 悬浮底栏） | 同进程 `MainActivity` | 主题重建 |
| **远端 prefs（跨进程）** | `FeatureCatalog.REMOTE_GROUP = "honorhook_config"` | UI 侧经 `XposedService.getRemotePreferences(group).edit().putBoolean(key, enabled).apply()`（`FeatureStore.setEnabled`） | hook 侧经 `RemoteConfig.isEnabled(key)`，只在 `install()` 阶段查表 | **hook 侧唯一配置输入** |

`FeatureCatalog` 被 proguard `-keep class ... { *; }` 保住 —— 宿主写与 hook 侧读共用同一批 key 字面量，
R8 重命名任一侧的常量/成员就会让双端漂移。

### Remote preferences / IPC 语义（跨进程边界，必须理解）

通道 = LSPosed 框架托管的 remote SharedPreferences，**写方 = 模块 App 进程，读方 = 每个被注入的宿主进程**。

1. `RemoteConfig.attach()` 在 `HookEntry.onPackageLoaded` 里**只做一次 Binder 读取并缓存**（`HookEntry.kt:21`），
   失败回 `null`。之后各目标在 `install()` 里走 `RemoteConfig.isEnabled(key)` 热路径查表 —— **无重复 Binder 调用**。
2. **默认全开**：`prefs?.getBoolean(key, true) ?: true`，外层再 `runCatching{}.getOrDefault(true)`。
   读不到（框架未连接 / 未下发 / 抛异常）一律视为开 —— 与历史"功能恒开"行为一致。
3. **写 `false` 才关，下次注入才生效**。关某功能 = 宿主显式写 false，
   **不是立刻生效** —— 目标进程必须被强停并重开，`onPackageLoaded` 才会重读一次。
   UI 二级页顶部提示卡写死了这句："开关在目标进程下次启动时读取"。
4. **写失败必须可见**：`FeatureStore.setEnabled` 返回远端下发是否成功；`false` 时
   UI 顶部显示" LSPosed 未激活 —— 开关只会保存到本地，hook 侧仍按默认全开运行"。
   原因：只写本地时 UI 开关照样翻转，用户会误以为已生效。
5. 功能被远端关掉的目标在 `install()` 开头直接 return，靠 `logDisabled()`（`app/app/src/main/kotlin/top/honorhook/xposed/target/TargetHook.kt`）
   打一行留痕日志 —— 否则真机上分不清"用户关掉了功能"与"模块根本没注入"。

**IPC 形态**：Binder（`XposedService`）→ 框架侧 SharedPreferences 文件。模块自身**不开** `Service`、**不导出** `ContentProvider`、
`AndroidManifest.xml` 里唯一的跨进程组件就是 LSPosed 的 service 绑定（隐式）。

## Authentication & Identity

**不适用** —— 模块自身没有账号体系。

- 无登录、无 token、无 OAuth、无设备指纹。
- 唯一的"身份"概念是**模块是否被 LSPosed 激活**：`FrameworkState.active`，判据 = **收到 LSPosed 的 `XposedService` Binder**
  （`app/app/src/main/kotlin/top/honorhook/ui/{HonorHookApp,FrameworkState}.kt`）。不做包名探测、不做文件探测。
- 唯一的"权限"概念是 `android.permission.QUERY_ALL_PACKAGES`（Android 11 包可见性，读宿主 app 的 label/icon）。
- 会员态伪造（exam8 F1）是对**宿主进程内存**的改写，不是本模块的身份体系。

## Monitoring & Observability

### 判据日志（唯一真源：LSPosed 框架日志）

> **`/data/adb/lspd/log/modules_*.log`，不在 logcat。** 这是本项目最容易被搞错的一点 ——
> 模块日志由 `module.log()` 写进框架自己的日志文件，`adb logcat` 抓不到。

| 判据行 | 来源 |
|---|---|
| `ThemeStoreHook: installed 43/43` | `MountEngine.logSummary`，`hookName` 由各 `*Hook.kt` 构造 `MountEngine(name, packageName)` 传入 |
| `InstallerHook: installed 11/11` / `SettingsHook: installed 1/1` / `Exam8Hook: installed 10/10` / `LauncherCardTitleHook: installed 1/1` / `SystemUiHook: installed 1/1` | 同上 |
| `<prefix> 生效: <task.id> — <description>`（每任务最多 2 条） | `MountEngine.judge()`，`JUDGE_LOG_PER_TASK = 2`，高频读点防刷屏 |
| `<hookName> 功能已由远端开关关闭（<key>），本次注入跳过挂载` | `TargetHook.logDisabled` |
| `<hookName> 宿主版本 <x> 与 hook 基线 <y> 不一致——挂载点可能漂移…` | `MountEngine.checkHostVersionOnce` |
| `[diag]` 取证日志 | `dev.sh diag-capture`（同样在 `modules_*.log` 里 grep） |

汇总行与判据行**不受 `MountEngine.DEBUG_LOGGING`（默认 false）门控** —— 只有挂载细节日志受控。

### logcat（辅助，非判据）

`tools/dev.sh log` / `log-hook` / `log-tag` / `log-grep` / `grep-log` / `crash` / `logcrash` 走 `adb logcat`，
只用于宿主侧崩溃与 `[diag]` 辅助取证；**hook 是否装上以 `modules_*.log` 为准**。

### 崩溃观测

hook 挂载一律 `.setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)`（`module.prop` 也设了 `exceptionMode=protective`）
—— 单个 hook 抛异常不会带崩宿主进程。`HookEntry.onPackageLoaded` 的整个分发包在 `runCatching` 里，
"hook 挂载绝不能炸宿主进程"。

## CI/CD & Deployment

**存在** GitHub Actions：`.github/workflows/ci.yml`（workflow 名 `build`）。

| 触发 | 行为 |
|---|---|
| `dev` push | `actions/setup-java@v5` (temurin 21) → `./app/gradlew -p app assembleDebug` → **`./app/gradlew -p app test`**（真门禁，不加 `|| true`）→ `actions/upload-artifact@v7` 上传 `app-debug-${{ github.run_number }}` |
| `master` push | JDK 21 → `./app/gradlew -p app assembleRelease` → `softprops/action-gh-release@v2` 发 Release，tag = `v<versionName>.r<run_number>`，`make_latest: true` |
| `workflow_dispatch` | 手动触发 |

其他事实：

- `permissions: contents: write`（仅发 Release 需要）。
- **不注入 keystore**：CI 与本地一致跑 debug 签名（`signingConfig = signingConfigs.getByName("debug")`）。
- **不装 Android SDK**：`ubuntu-latest` 镜像已预装 `/usr/local/lib/android/sdk` 且许可证已预授权，
  缺失的 platform/build-tools 由 AGP 构建时自动补齐。
- 版本号从 `app/app/build.gradle.kts` grep `versionName` 提取，不硬编码在 CI 里。
- Release body 内嵌了 6 作用域、宿主基线版本与判据日志路径。
- GitHub 托管 runner 上**没有** `tools/jdk-21/` / `tools/android-sdk/`（项目内便携版），CI 只用 `gradlew`。

**部署到设备（本仓库开发流程，不在 CI）**：

```bash
tools/dev.sh deploy-release   # build-release + install-release + enable-module
```

`enable-module` 会在 `adb install -r` **覆盖安装导致 LSPosed 停用模块**后，自动深链到模块页并点击
LSPosed「模块未激活」系统通知的启用开关（通知栏与寄生管理器对 uiautomator 不可见，
用 screencap + PIL 像素定位），并强停作用域进程。

## Environment Configuration

| 项 | 载体 | 说明 |
|---|---|---|
| SDK 路径 | `app/local.properties`（`sdk.dir`） | **存在但机器本地**，`.gitignore` 排除，值不外传 |
| Gradle 镜像 | `app/settings.gradle.kts` + `app/gradle/wrapper/gradle-wrapper.properties` | 阿里云四仓 + Central 直连（JetBrains Compose Multiplatform 产物只在 Central）+ mavenLocal 限定 `io.github.libxposed`；wrapper 走 `mirrors.cloud.tencent.com` |
| JDK 21 | `tools/dev.sh` 自解析 | 项目内 `tools/jdk-21/` → brew `openjdk@21` libexec Home；**要求含 `bin/jlink`** |
| `JAVA_HOME` / `ANDROID_HOME` | `tools/dev.sh` 导出 | 并把 `tools/bin` → jadx bundle → 最高版本 build-tools → JDK → platform-tools 注入 PATH |
| 代理 | 开发机 `~/.gradle/gradle.properties` | `CLAUDE.md` 记：`http.https://github.com.proxy = socks5://127.0.0.1:7897` |
| `HONORHOOK_PYTHON` | 环境变量 | 覆盖 `enable-module` 用的解释器（Windows Git Bash 常见 `python` 而非 `python3`） |
| 宿主 APK / 反编译产物 | `system-files/`（gitignore） | `tools/dev.sh decompile` / `jadx` **现场生成**，不预置 |
| 密钥 / keystore | — | 仓库内**不存在**任何 `.jks` / `.keystore` / `*.pem` / `*.key`；release 用 debug 签名 |

## Webhooks & Callbacks

**不适用** —— 无出站 HTTP、无 Webhook 接收端、无推送、无第三方回调 URL。

代码内出现的 `https://` 全部是注释里的来源标注或服务端边界说明（`Kyant0/AndroidLiquidGlass` 出处、
`api.wantiku.com/api/Sprint/Papers` 属宿主服务端而非本模块调用），**不是本模块的网络调用**。

唯一"回调"语义的两处（均为进程内回调，非网络）：

1. **Xposed 框架回调** → `HookEntry.onPackageLoaded(param)`：框架在目标进程装载时回调，`param` 携带 `packageName` / `defaultClassLoader` / `applicationInfo.sourceDir`。
2. **协程/Handler 回调** → `MountScheduler` 的 `HandlerThread("HonorHook-mount")` 上 `postDelayed` 的重试回调（`app/app/src/main/kotlin/top/honorhook/xposed/core/MountScheduler.kt`）。

---

## 附：Device / Debug 工具面（`tools/dev.sh`）

**唯一入口**。`CLAUDE.md` 明令禁止手拼 gradle / adb 命令；新增操作先加 `dev.sh` 子命令再做。

| 类别 | 子命令 |
|---|---|
| 构建 | `build` / `build-release` / `test` / `clean` / `size` / `dexstat` |
| 安装部署 | `install` / `install-release` / `deploy-release` / `enable-module` / `launch` / `launch-theme` |
| 设备 | `devices` / `top` / `shot` / `ui` / `viewdump` / `tap` / `swipe` / `text` / `key` |
| 日志判据 | `log-hook` / `log-tag` / **`modlog [tag] [行数]`** / `lspd-log` / `wait-hook` / `hook-test` / `log-file` |
| logcat | `log` / `log-grep` / `grep-log` / `grep-logd` / `logcat` / `clear-log` / `crash` / `logcrash` |
| 诊断 | `diag-start` / `diag-module` / `diag-capture` |
| 逆向 | `decompile <apk>`（apktool）/ `jadx <apk>` / `aapt2 <参数>` / `apkinfo` |
| 信息 | `env`（打印全部解析后的工具路径）/ `help` / `scopes`（scope.list）/ `targets`（TargetRegistry）/ `exec <命令...>` |

**读日志的确切命令形态**（`tools/dev.sh` 内部实现，可直接照抄）：

- LSPosed 框架日志（需 root）：`adb shell su -c "grep -h 'HonorHook' /data/adb/lspd/log/modules_*.log"`
- 模块日志目录：`/data/adb/lspd/log/`；配置目录 `/data/adb/lspd/config`、模块目录 `/data/adb/lspd/modules`（`lspd-log` 子命令）
- hook 侧本地日志文件（备用）：`/data/user/0/top.honorhook/files/honorhook.log`（`log-file` 子命令）

**逆向期脚本**：

- `tools/adapt_new_version.py` —— 宿主新版本适配校验：从 Hook 源码**自动解析**挂载点清单（认 `addConst`/`addForceArg`/`addConditional`/`addBlock` 位置式与 `MountTask(...)` 具名式两种书写格式），
  在新版 jadx 产物里逐点验证「类存在 + 方法存在（名 + 参数数）」，输出存活 N/M + 丢失清单。**新增/删除挂载点无需同步任何清单。**
- `tools/rain-decrypt.py` —— Rain 参考模块字符串解密（short[] XOR 体系），逆向期工具，产物写 Markdown。

*Integrations analysis: 2026-10-11*
