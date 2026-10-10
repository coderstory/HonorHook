<!-- 归档：docs/archive/ai-lsposed-temlate-report.md → skill references（2026-10-10 md 重构） -->
<!-- 口径提示：正文写于 Windows 期/早期调研阶段，Windows 路径（C:\ / .bat / tools/jdk-21）与已删目录（.buildtmp/、system-files/*-apktool 等）可能失效；结论与方法论仍有效。 -->

# AI-lsposed-temlate 参考项目分析报告

> 本文档整理自 AI-lsposed-temlate 参考项目分析结论（已验证），供 lsposed-plugin 工程与 build-lsposed-module / lsposed-hook-expert skill 演进参考。

## 1. 项目定位

AI 辅助开发的 LSPosed 模块脚手架模板（**非实际插件**）。GitHub 仓库 bujianxingyun/AI-lsposed-temlate，作者 mobohua（5 个 commit 均为 2026-06-10 一天完成），Apache-2.0 协议。

README 自述："基于 libxposed 的 LSPosed Xposed 模块模板，预置 Cursor 规则与 Skill，便于 AI 辅助下快速开发 Hook 模块"。

**基础参数**：libxposed API 101、Java 21 + Kotlin、minSdk 26。

**核心价值三件套**：

1. 官方 libxposed example 移植（入口 / 生命周期 / hook demo）
2. `.cursor/rules/project-context.mdc` + `.cursor/skills/libxposed-api/SKILL.md`（AI 开发规则）
3. `apksource/` 反编译代码目录约定 + 定制模块 6 步清单

**README 84-88 三条纪律**：

1. libxposed 必须 compileOnly
2. hook 只写 `onPackageReady`，不写 `onPackageLoaded`
3. 多包进程必须校验 `getPackageName()`

## 2. 工程结构

Gradle 工程整体放在 `module/` 子目录（单模块 `:app`，`rootProject.name="mylspmodule"`）。仓库根是模板说明层（README / LICENSE / CI / .cursor）。

**app/src/main/ 构成**：

- AndroidManifest
- `java/com/moren/demo/`：App.kt、MainActivity.kt、ModuleMain.java、ModuleMainKt.kt 共 4 个源文件
- `res/layout/activity_main.xml`
- `resources/META-INF/xposed/`

**关键 Gradle 配置**：

| 位置 | 内容 | 作用 |
|---|---|---|
| module/settings.gradle.kts:1-21 | 全阿里云镜像（gradle-plugin/google/central/public）+ mavenLocal 限定 `includeGroup("io.github.libxposed")` 离线兜底 + `FAIL_ON_PROJECT_REPOS` | 依赖源与离线兜底 |
| module/app/build.gradle.kts:41-46 | `packaging{resources{merges += "META-INF/xposed/*"; excludes += "**"}}` 先排除全部再显式 merge xposed 元数据 | 保证混淆后 java_init.list 存活，比 proguard keep 更硬 |
| build.gradle.kts:21-25 | release 开 minify + shrinkResources + debug 签名 | 发布配置 |
| CI | JDK 21 temurin，任务 assembleDebug assembleRelease | 构建验证 |

## 3. 技术栈

- AGP 8.2.2（偏旧）/ Kotlin 2.2.10 / Gradle 8.4.0
- compileSdk 36（AGP 8.2.2 官方仅支持到 34，用 `suppressUnsupportedCompileSdk=36` 压警告）/ buildTools 36.0.0
- minSdk 26 / targetSdk 34
- JVM target 17
- libxposed `api:101.0.0` compileOnly + `service:101.0.0`（API 101，落后我们基线 102）
- **无 miuix、无 Compose**（ViewBinding + XML）、**无 DexKit**
- 依赖总数仅 2 个（api + service），零 AndroidX

## 4. Hook 入口

**新版 API**：ModuleMain.java:13 `extends XposedModule`（无 de.robv 引用）。

**注册链路**：

- `META-INF/xposed/java_init.list` → `com.moren.demo.ModuleMain`
- `module.prop`：minApiVersion=101 / targetApiVersion=101 / staticScope=true
- `scope.list`：默认 scope `com.android.settings`

**双入口冗余**：ModuleMainKt.kt:16 是同功能 Kotlin 版入口（含 `onSystemServerStarting`），java_init.list 只注册 Java 版（Kotlin 版作为休眠备选示范）。

**注意点**：

- ModuleMainKt.kt:46 有 `if(!param.isFirstPackage) return`
- 但两个入口都**没有**按 `getPackageName()` 过滤目标包名（与自身 README:88 纪律相悖，实际靠 ClassNotFound 自然失败）

## 5. UI

极简：MainActivity.kt:14 普通 Activity + ViewBinding（activity_main.xml 7 TextView + 3 Button）。内容是 XposedService 官方演示：

- App.kt:48-51：Application 注册 `XposedServiceHelper.registerListener`
- MainActivity.kt:88-104：演示 `requestScope()` 动态申请作用域、`getRemotePreferences()` 读写共享配置、`openRemoteFile()` 写远程文件
- 系统主题 `Theme.DeviceDefault`，无自定义

## 6. Hook 示例

**无真实第三方 app hook**。ModuleMain.java:37-85 是官方 libxposed example 拦截链示例（目标为虚构类 `com.moren.demo.Example`）。但 API 手法演示很全：

- `hook(method).intercept(chain -> ...)` 拦截链（before/after 由 `chain.proceed()` 前后表达）
- `proceed(newArgs)` 改参
- `proceedWith(newThis)` 换 this
- `getInvoker().setType(Invoker.Type.ORIGIN/Chain)` 调原方法
- 构造器 hook + `setPriority(PRIORITY_HIGHEST)` + `setExceptionMode`

无 DexKit、无字符串查找、无混淆目标适配。

## 7. 与现有参考对比及价值评估

**技术栈对比表**：

| | AI-temlate | HyperChanger | notification-guard | LSPFRIDA |
|---|---|---|---|---|
| AGP/Kotlin/Gradle | 8.2.2/2.2.10/8.4 | 9.2.1/2.3.21/9.6.1 | 8.13.2/2.3.20 | 9.3.1/2.4.10/9.5.0 |
| libxposed api | 101 | 102 | 102 | 102 |
| SDK | c36/m26/t34 | c37/m35 | c36/m31/t35 | c37/m26/t34 |
| miuix+Compose | 无(XML) | 0.9.3+有 | 0.8.8+有 | 0.9.4-rc01+JB Compose |
| DexKit | 无 | 2.2.0 | 无 | 无 |

**结论：不能直接替代现有拼装方案**（无 miuix/Compose UI、无 DexKit、无真实 hook 实战、栈偏旧、API 101 落后）。但作为"最小骨架 + AI 方法论"参考，四点值得吸收：

1. **packaging.merges 保 `META-INF/xposed/*`**（app/build.gradle.kts:41-46）——比仅靠 proguard keep 更可靠的元数据打包保障，应补入 build-lsposed-module skill
2. **`.cursor/skills/libxposed-api/SKILL.md` 是完整的 libxposed 现代 API 速查（162 行）**：Legacy→Modern 映射表（:18-31）、module.prop 三字段语义（:51-57）、`deoptimize()` 对抗内联（:118-122）、Remote Preferences/Files 与 `/data/adb/lspd/modules/` 路径（:130-131）、`param.isFirstPackage` 多包进程陷阱（:72-74）——与 lsposed-hook-expert skill 高度互补，个别条目（"框架不提供 XposedHelpers，用 libxposed/helper 或裸反射" :103）更精确
3. **"禁止 Classic API"负面清单**（project-context.mdc:19-34 禁用/替换对照表）——防 AI 混用两代 API 的护栏，值得补入 skill
4. **`apksource/` 目录约定**：目标 app 反编译代码放 `apksource/<包名>/` 只读参考、不参与编译、hook 时 `param.getClassLoader().loadClass(...)` 加载（project-context.mdc:54-57）——与 rain-extract 逆向工作流契合，可采纳为工程目录约定

**对 build-lsposed-module skill 的修正点**：

- 该 skill 现写 manifest 需注册 `io.github.libxposed.service.XposedProvider`，但四个参考项目（含本模板）manifest 均无此 provider，统一只用 Application + `XposedServiceHelper.registerListener()`（AI-temlate App.kt:50、HC HookApplication.kt:14、LSPFRIDA LSPFRIFAApplication.kt:29）——**该行应删除或修正**
- proguard-rules.pro 的 `-adaptresourcefilecontents META-INF/xposed/java_init.list`（R8 自动改写 init 文件里的类名匹配混淆后 FQCN）配合 `-repackageclasses` 混淆时必需，值得补入 skill

## 总评

定位"AI 开发方法论 + 最小可跑骨架"参考，价值在 `.cursor` 两份文档与打包/混淆细节，不在工程栈本身；**继续用 HC + LSPFRIDA 拼装做工程模板**，把上述 4+2 个点合入 skill。