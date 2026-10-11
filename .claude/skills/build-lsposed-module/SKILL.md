---
name: build-lsposed-module
description: 从零构建 LSPosed 插件源码工程的方法论——Android Gradle 工程脚手架、libxposed API 依赖、XposedModule 入口与注册、miuix UI 脚手架（拼装 HC hook 入口 + LSPFRIDA UI 体系 + Rain UI 结构）、构建 APK。当需要新建 LSPosed/Xposed 模块工程、搭建项目脚手架时使用。
metadata:
  author: 项目沉淀
  last-updated: '2026-10-10'
  keywords:
  - 构建
  - 脚手架
  - LSPosed
  - Gradle
  - miuix
  - 工程
  - APK
---

> 项目级实战判据（选点 / 死点 / 验证铁律）：[`docs/hook-craft.md`](../../../docs/hook-craft.md)。

## 适用场景

从零搭建 LSPosed 插件 Android 源码工程，产出可安装 APK。基于 reference 三插件 + Rain + fanqie-purify 实践。

## 1. Gradle 工程脚手架

版本基准（reference 速查，选其一跟随）：
- AGP 9.2.1（HyperChanger）或 9.3.1（LSPFRIDA）
- Kotlin 2.3.21 / 2.4.10
- compileSdk 37 / targetSdk 37 / minSdk 按目标（26~35）
- JVM target 17 / Java 17
- Gradle 9.5.0+（wrapper）
- Gradle 仓库可用全阿里云镜像（AI-temlate settings.gradle.kts:1-21：gradle-plugin/google/central/public 四仓 + `RepositoriesMode.FAIL_ON_PROJECT_REPOS`）+ `mavenLocal` 限定 `includeGroup("io.github.libxposed")` 离线兜底——国内网络环境推荐

`build.gradle.kts` 关键：
```kotlin
plugins {
    id("com.android.application") version "9.2.1"
    id("org.jetbrains.kotlin.android") version "2.3.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
}
android {
    compileSdk = 37
    defaultConfig {
        applicationId = "<你的包名>"
        minSdk = 26
        targetSdk = 37
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources { excludes += "**"; merges += "META-INF/xposed/*" } }  // 先排除全部资源再显式 merge xposed 元数据（AI-temlate 硬手法），保证混淆/压缩后 java_init.list 存活，比仅靠 proguard keep 更可靠
}

**工程目录约定**（来源 AI-temlate .cursor 规则，可选采纳）：`apksource/<包名>/` 放目标 app 反编译代码，只读参考、不参与编译，与 `system-files/` 逆向工作流契合；hook 时用 `param.getClassLoader().loadClass(...)` 按字符串加载目标类，禁止把 apksource 里的类编入模块 APK。
```

## 2. libxposed API 依赖

```kotlin
dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")       // 编译期，运行时由 LSPosed 框架提供
    implementation("io.github.libxposed:service:102.0.0") // 宿主侧通信
    // miuix UI（Maven Central）
    implementation("top.yukonga.miuix.kmp:miuix-ui-android:0.9.3")
    implementation("top.yukonga.miuix.kmp:miuix-preference-android:0.9.3")
    // Compose（AndroidX BOM）
    implementation(platform("androidx.compose:compose-bom:2026.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.activity:activity-compose")
}
```

## 2.5 混淆（proguard）必配规则

启用混淆（`isMinifyEnabled=true`，尤其配合 `-repackageclasses`）时必须加 `-adaptresourcefilecontents META-INF/xposed/java_init.list`——R8 自动改写 init 文件里的类名以匹配混淆后 FQCN，否则框架找不到入口类（来源 AI-temlate `module/app/proguard-rules.pro`）：

```proguard
-adaptresourcefilecontents META-INF/xposed/java_init.list
# 入口类必须具名 keep，禁止通配 + allowobfuscation——R8 改行为时构建零报错但模块被静默跳过
-keep public class <你的包名>.HookEntry {
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public void onSystemServerStarting(...);
}
```

## 3. XposedModule 入口与注册

入口类：
```kotlin
class HookEntry : XposedModule() {
    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 按 param.packageName 分发，对 scope 内目标 app hook
    }
    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) { /* ... */ }
}
```

注册（`src/main/resources/META-INF/xposed/`）：
- `java_init.list`：`<你的包名>.HookEntry`（每行一个入口类）
- `module.prop`：`minApiVersion=102` / `targetApiVersion=102` / `staticScope=true`
- `scope.list`：目标 app 包名每行一个

## 4. AndroidManifest

```xml
<application android:name=".ModuleApplication" android:label="@string/app_name">
    <activity android:name=".MainActivity" android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN"/>
            <category android:name="de.robv.android.xposed.category.MODULE_SETTINGS"/>
        </intent-filter>
    </activity>
    <activity-alias android:name=".LauncherActivity" android:targetActivity=".MainActivity" android:exported="true">
        <intent-filter>
            <action android:name="android.intent.action.MAIN"/>
            <category android:name="android.intent.category.LAUNCHER"/>
        </intent-filter>
    </activity-alias>
    <meta-data android:name="xposedmodule" android:value="true"/>
</application>
<queries>
    <package android:name="com.xxx.target"/>  <!-- 每个 hook 目标 app -->
</queries>

> **注**：四个参考项目 manifest 均无 XposedProvider——需要 XposedService 能力时在 `ModuleApplication.onCreate()` 里调用 `XposedServiceHelper.registerListener(this)`（Application 实现 `XposedServiceHelper.OnServiceListener`），参考 AI-temlate `module/app/src/main/java/com/moren/demo/App.kt:50`、HC `HookApplication.kt:14`、LSPFRIDA `LSPFRIFAApplication.kt:29`。
```

## 5. UI 脚手架（拼装策略）

参考 Rain UI 结构（About 页 + Adapted apps 列表页 + 设置页 + 悬浮底栏）+ LSPFRIDA UI 体系：
- `ModuleApplication` + `MainActivity`（`setContent`）
- `MiuixTheme` + `ThemeModeStore`（三态 System/Light/Dark + Monet，LSPFRIDA 简化版）
- `NavHost`（二级页）+ `HorizontalPager`（底部 Tab）+ `NavigationBar`（初版原生 / 进阶 FloatingMiuixNavigationBar）
- 页面：首页（模块状态）/ 适配应用列表 / 设置（Color Mode / Floating bar / Hide icon）/ 关于
- 设计 token：搬 LSPFRIDA `UiTokens` + `MiuixPageBackground()`

UI 写法详见 `reference/LSPFRIDA（同类插件）`、`reference/HyperChanger（同类插件）` 源码。

## 6. 构建产出

```bash
tools/dev.sh build
```
产出 `app/build/outputs/apk/debug/app-debug.apk`。安装后 LSPosed Manager 启用模块 + 勾选作用域。

## 参考

- `reference/LSPFRIDA（同类插件）`、`reference/HyperChanger（同类插件）`、`reference/notification-guard（同类插件）`：新版 API + miuix 实战
- `reference/Rain_1.1.5.apk`：UI 结构参考（About + Adapted apps + 设置 + 悬浮底栏，作者夜雨|Nairain）
- `reference/fanqie-purify（AI开发插件实践）/module/`：旧版 API module 结构（`build.sh` / `AndroidManifest` / `xposed_init`）
- 环境约束：macOS 本机（JDK 21 / SDK 在 `tools/android-sdk/`，`tools/dev.sh` 顶部已固化解析）、仅真机直连无模拟器
- 构建入口：**走 `tools/dev.sh build`**，不在会话里手拼 gradle 命令

## references/

- [`references/hc-remote-prefs-channel.md`](references/hc-remote-prefs-channel.md) — HyperChanger UI↔hook 远程 prefs 双通道全链路实证（LSPosed 源码 API 102 链路 / 现读 + 变化监听两机制 / 二进制资产通道 / 反向 RPC），搭通信层时必读
