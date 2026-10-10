# HonorHook —— 荣耀主题商店增强（纯 hook 模块）

基于 **LSPosed** 的荣耀（Honor / MagicOS）定制模块。**无 Activity、无图标、无资源、无设置界面**，
安装激活后功能默认全部开启，运行期只输出日志判据。

## 技术栈

| 维度 | 选型 |
|------|------|
| 形态 | 纯 hook 模块（无界面，LSPosed Manager 内无设置入口） |
| LSPosed API | `io.github.libxposed:api:102.0.0`（**compileOnly**，由框架运行时提供，不打进 APK） |
| 目标解析 | DexKit `org.luckypray:dexkit:2.2.0` —— 反射优先 / 特征兜底双轨解析（对抗混淆），仅 arm64 |
| 语言 | Kotlin 2.3.21（AGP 9.x 内置 Kotlin），JVM target 17 |
| SDK | compileSdk 37 / minSdk 26 / targetSdk 34 |

## 工程结构

- Gradle 工程根 = 本目录（`app/`），单一模块 `:app`，代码在 `app/app/src/main/kotlin/top/honorhook/xposed/`
- Hook 入口注册（`src/main/resources/META-INF/xposed/`）：
  - `java_init.list` → `top.honorhook.xposed.HookEntry`（继承 `XposedModule`）
  - `module.prop` → `minApiVersion=102 / targetApiVersion=102 / staticScope=true / exceptionMode=protective`
  - `scope.list` → `com.hihonor.android.thememanager`（主题商店，当前唯一目标）
- 源码（7 个文件，纯 Kotlin，零 Android 组件依赖）：
  - `HookEntry` — 模块入口，按包名查注册表分发
  - `target/TargetHook` + `target/TargetRegistry` — 目标接口与注册表（新增目标 = 加一行，入口零改动）
  - `target/ThemeStoreHook` — 主题商店 F1-F4（开 VIP / 全免费 / 试用无倒计时 / 永不恢复默认），45 个挂载点
  - `MethodResolver` + `DexKitHelper` — 双轨目标解析
  - `MountScheduler` — 挂载统一走后台线程（防主线程重试导致 ANR/boot-loop）
  - `HookKeys` — 挂载表的分组标注（无运行时开关，功能恒开）

## 构建

```bash
tools/dev.sh build           # debug APK
tools/dev.sh build-release   # release APK（混淆 + 资源收缩）
tools/dev.sh size            # 查看 APK 体积构成
tools/dev.sh dexstat         # 查看 dex 内部按包统计
tools/dev.sh install|install-release
tools/dev.sh hook-test       # 重启主题商店进程并抓挂载判据日志
```

- JDK 用项目内便携版 `tools/jdk-21`（脚本已固定 `JAVA_HOME`，**不能用 JBR**——缺 `jlink` 无法打包）
- `local.properties` 指向 `tools/android-sdk`
- 产物体积：release 约 **410KB** —— DexKit 的 arm64 so 占 381KB，代码 dex 仅 58KB（dex 内 67 个类，本包 4 个）

## 使用与验证

1. 安装 APK 后，在 LSPosed Manager 中**激活模块并勾选作用域**（主题商店）
2. 重启主题商店进程
3. 过滤日志 `TAG=HonorHook`，判据为 `ThemeStoreHook: installed 45/45`
   （另有 `theme-download-chain 路由触发`、`vip-state 漏斗改写`、`挽留弹窗已拦截`）

## 开发约定（简版）

- 路径全英文；`reference/` 只读；工具链一律装 `tools/` 下；只真机直连、禁模拟器
- **所有 gradle/adb 操作走 `tools/dev.sh`，禁止手工拼接命令**
- 完整协作指南见 `../CLAUDE.md`
