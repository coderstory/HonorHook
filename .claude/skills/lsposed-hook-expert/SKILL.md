---
name: lsposed-hook-expert
description: LSPosed/Xposed 模块开发专家——hook 策略、API 使用与方法论。当需要开发 LSPosed 插件、编写 hook 代码、选择 hook 点与策略、使用 libxposed API 或旧版 Xposed API、适配混淆目标 app 时使用。沉淀自 reference 三插件（新版 API）+ fanqie-purify MainHook（旧版 API + 方法论）+ Rain（DexKit）实践。
metadata:
  author: 项目沉淀
  last-updated: '2026-10-10'
  keywords:
  - LSPosed
  - Xposed
  - hook
  - libxposed
  - DexKit
  - 模块开发
---

> 项目级实战判据（选点 / 死点 / 验证铁律）：[`docs/hook-craft.md`](../../../docs/hook-craft.md)。

## 适用场景

开发 LSPosed/Xposed 模块：编写 hook 代码、选 hook 点与策略、适配混淆目标 app、配 scope、构建验证。**前置**：reverse-decompile + static-analysis skill 定位 hook 点。

## API 选型

### 新版 libxposed API 102（推荐，reference 三插件 + Rain 用）

```kotlin
compileOnly("io.github.libxposed:api:102.0.0")
implementation("io.github.libxposed:service:102.0.0")
```
- 入口基类：`io.github.libxposed.api.XposedModule`（**非**旧版 `IXposedHookLoadPackage`）
- 回调：`onModuleLoaded` / `onPackageLoaded`（hook 用户 app）/ `onSystemServerStarting`（hook 系统服务）/ `onPackageReady`
- 入口注册：`src/main/resources/META-INF/xposed/java_init.list`（入口类全限定名，每行一个）+ `module.prop`：`targetApiVersion=102`（对齐当前 API，四参考一致）；`minApiVersion`=模块支持的最低框架 API（各异：LSPFRIDA=82 / HyperChanger=101 / notification-guard=102 / Rain=102，低值兼容更老框架）；另有 `staticScope`、`autoHotReload`、`exceptionMode`、`description` 等实用键；HyperChanger 另有 `native_init.list`（native hook 入口）
- scope：`staticScope=true` + `META-INF/xposed/scope.list`（包名每行一个）；`staticScope=false` 由用户在 Manager 勾选

真实 hook 调用示例（API 102 链式写法，摘自 reference 项目实码）：

```kotlin
// HyperChanger AppBottomNavHooks.kt:289-291
module.hook(setClipBottom)
    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
    .intercept { chain -> chain.proceed(arrayOf<Any>(0f)) }
// notification-guard GuardModule.java:14-15（另演示 setId）
handles.add(hook(target).setId(id)
    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
    .intercept(hooker));
```

### 旧版 de.robv.android.xposed（fanqie-purify 用，方法论通用）

```java
import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
```
- 入口：`IXposedHookLoadPackage.handleLoadPackage(XC_LoadPackage.LoadPackageParam)`
- 注册：`assets/xposed_init`（入口类全限定名）
- AndroidManifest 另有 meta-data 四件套：`xposedmodule=true` / `xposeddescription` / `xposedminversion=82` / `xposedscope=目标包名`（fanqie-purify module/AndroidManifest.xml 实证）——旧版的 minVersion 与 scope 声明走 manifest meta-data，与新版 META-INF/xposed 机制不同。
- minApiVersion=82

两版底层同为 ArtMethod 替换（LSPlant 引擎），**但 API 完全不同**：旧版 `XposedBridge.hookMethod` / `XposedHelpers.findAndHookMethod`；新版是链式调用 `hook(method).intercept { ... }`（无 XposedHelpers——框架不提供，如需按类/方法名查目标用 libxposed/helper 或裸反射）。在 102 模块里 import XposedHelpers 是典型错误。新项目用新版 API 102。

## hook 方法论（fanqie-purify MainHook 实战精华）

参考 `reference/fanqie-purify（AI开发插件实践）/module/src/com/eta/fanqie/enhance/MainHook.java`（注释详尽记录每版适配/修复/弯路）。

### 1. 版本无关实现 > 硬编码混淆类名/资源 id
- 混淆类名/资源 id 逐版变（fanqie 实测 `f22.q`→`f32.q`、`a13.d0`→`i23.d0`），硬编码每版都要改。
- **文案 + 结构定位**：拿住稳定特征（"个人主页"爬到头像行→取父容器→收掉非头像子块）；文案判定（"我的资产"）；`getIdentifier(name, type, pkg)` 按名查 id（不硬编码数值）。
- 仍需每版复核的：`PRE_HIDE_RES_IDS`（只省一帧闪现）、阅读页广告行工厂类名、VIP 促销弹层类名——用 `python3 tools/adapt_new_version.py` 自动比对提醒。

### 2. 广告位总闸 > 逐个 View GONE
- 在广告**请求/渲染之前**拦截：`AdConfigManager.checkAdAvailable(广告位, *)` 返回 false，一次 hook 覆盖全部被动广告位，无"渲染一帧才隐藏"的闪烁。
- 只拦被动展示位，黑名单实际 9 项：splash_ad、page_front_ad、page_middle_ad、reader_banner、audio_patch_ad、audio_patch_pre_ad、audio_info_flow、mini_game_config、free_ad_enter（book_mall_feed_ad 因误伤已移除——以 MainHook BLOCKED_AD_POSITIONS 为准），用户主动触发的激励视频/金币流程放行。
- 打日志记录所有广告位，便于换版本重新收集黑名单。

### 3. 收口点 hook（覆盖一类弹窗）
- 继承链：所有原生弹窗都走 `android.app.Dialog.show()`——hook 它 + 内容判定（`isDialogBad()`/`isPromoPopupText()`），一个 hook 覆盖所有 Dialog 子类。
- 内容级兜底：扫弹窗子树所有 View 的 `content-desc` + `text`（不只 TextView，Lynx 弹层文案只在 content-desc）。
- 多点重扫（0/300/800/1600/2800ms）：Lynx 异步渲染，文案延迟出现。
- `WindowManagerGlobal.addView` 悬浮窗兜底（过滤四类窗口类型：TYPE_APPLICATION=type=2 / TYPE_APPLICATION_PANEL / TYPE_APPLICATION_ATTACHED_DIALOG / TYPE_APPLICATION_OVERLAY）。**注**：AOSP 语义中 type=2 即 normal application window（activity 自身也常是 type=2），代码层未强制排除 activity decor——真正的误伤护栏是 PROMO_POPUP_PATTERNS 文案特异性（原作者注释声称 activity 不会走到此路径，待真机验证）。

### 4. 只短路 void 重载，不碰策略查询
- `AudioHelper.s(String)/r(long)/j(String)` 混杂 void（播放）和 boolean/String（策略查询）。
- 只 hook 返回 void 的重载（短路播放），**不碰 boolean/String 策略查询**（避免改变 app 状态机导致"每次切歌重新请求广告"等副作用）。
- 反汇编验证：曾尝试让 `isAdViewOk()` 返回 false，反汇编证实会导致"正在展示"状态不置位→每次切歌重新请求广告，故弃用。

### 5. 刻意不封堵的（避免误伤用户利益）
- 奖励领取弹窗（`rewardpopup.g3/j`）、每日抽奖、分享福利——是用户自己的钱，封了等于扣钱。
- 但它们仍走 `Dialog.show()`，只要内容命中 `isDialogBad()` 文案规则仍被收（规则刻意避开"金币/奖励/签到"等用户想看的词）。

### 6. 走过的弯路（务必不重犯，MainHook 注释明记）
- **粗粒度文案白名单误伤**：`t.contains("登录")` 白名单把"登录领取"广告一起放过（既是头部账号入口又是首页红包广告）。→ 必须用结构判定（在不在顶栏），不能用文案判定。
- **abstract 方法 hook 失败**：hook 接口/抽象类 `subwindow.manager.c.e()` 无方法体 → `hookAllMethods` 抛 "is abstract: it has no body to hook"（MainHook.java:34 原文），拦截器静默失效数版本。→ 改 hook 具体实现类 `f` 与 `z70.a`。
- **removeView 破坏 ViewStub**：容器由 ViewStub 一次性 inflate，removeView 会抛 IllegalStateException。→ 用 GONE 不 removeView。
- **类名照搬同类项目**：NsAdImpl/NsVipImpl 在畅听里不存在（基线版本不同），不能照搬对方类名。

## DexKit（混淆 app 的 hook 点查找）

Rain 用 DexKit（`libdexkit.so`）查找 hook 点。DexKit 按 dex 特征匹配混淆方法：
- 参数类型/返回值（如接收 String 返回 boolean）
- 字符串引用（方法内引用了某字符串）
- 调用关系（调用/被调用）
- 注解、使用数（被 N 处调用）

适用：目标 app 高混淆，类名不可读，但知道方法特征。DexKit 在运行时（hook 进程内）分析目标 dex，返回匹配的方法。详见 Rain 入口 `love.nairain.hook.xposed.HookEntry`。

完整用法（Bridge 两种创建方式 / 查询构造→结果→过滤流程 / 双轨策略 / Rain 的挂载助手 ck0·qa·sa 与 `XposedHelpers` 替代品 i92）见 [`references/rain-dexkit-and-mount-patterns.md`](references/rain-dexkit-and-mount-patterns.md)。**DexKit 的定位手段（何时该用特征查找）见 `static-analysis` skill。**

依赖坐标：`implementation("org.luckypray:dexkit:2.2.0")`（HyperChanger build.gradle.kts:84）；Rain 通过 DexKitBridge 使用（apk 内 libdexkit.so 四架构）。注意：HookEntry 构造器里的 dexkit 包引用实为混淆器塞进的字符串解密 helper，真正的 DexKit 查找调用在分发类里。

## 开发流程

1. **逆向定位 hook 点**（reverse-decompile + static-analysis skill）：产出 hook 点清单（类/方法签名/策略/依据）。
2. **写 hook 代码**：`XposedModule.onPackageLoaded` 里对目标包 hook（新版）或 `handleLoadPackage`（旧版）。
3. **配 scope**：`scope.list` 声明 hook 的目标包名（staticScope=true）。
4. **构建 APK**：`./gradlew :app:assembleDebug`（参考 reference 项目 Gradle 配置）。
5. **LSPosed 激活**：安装 APK，LSPosed Manager 启用模块 + 勾选作用域。
6. **真机验证**：看 LSPosed 日志确认 hook 挂载 + 拦截生效；uiautomator dump 看效果。**只能真机直连，无模拟器**（本项目约束）。
7. **多包进程陷阱**：多进程/多包场景下，hook 回调按包名分发前必须先校验 `param.packageName`（且 `param.isFirstPackage` 判定首个进程实例），否则同进程内多个包都会误触发同一 hook。
8. **AI 生成 hook 代码防混用两代 API**：负面清单参考 `reference/AI-lsposed-temlate/.cursor/rules/project-context.mdc`（Classic API 禁用/替换对照表：XposedHelpers.findAndHookMethod → hook(method).intercept、IXposedHookLoadPackage → XposedModule、assets/xposed_init → META-INF/xposed/java_init.list 等），禁止两套 API 并存。

## 实践案例

| 项目 | API | 价值 |
|------|-----|------|
| LSPFRIDA/HC/notification-guard（reference） | libxposed 102 新版 | 新版 API 入口/scope/UI 实战 |
| Rain（reference/Rain_1.1.5.apk） | libxposed 102 + DexKit | 混淆 app 的 DexKit hook 点查找 |
| fanqie-purify（reference） | 旧版 de.robv + 自研 dex 工具 | hook 方法论精华（版本无关/总闸/收口点/弯路） |

## references/

| 文件 | 内容 | 何时读 |
|------|------|--------|
| [`rain-dexkit-and-mount-patterns.md`](references/rain-dexkit-and-mount-patterns.md) | Rain（高混淆样本）入口分发链路 / 按包名注册表与适配器接口 / DexKit 查询全流程与双轨策略 / libxposed 挂载助手（ck0·qa·sa·i92）/ 字符串解密体系 | 写 DexKit 兜底、或"按特征找被混淆的方法"时 |
| [`rain-module-sample-analysis.md`](references/rain-module-sample-analysis.md) | Rain 基本信息 / META-INF 三配置（java_init.list + module.prop + scope.list）/ 混淆与防护特征 / UI 结构 | 要 libxposed 102 标准模块样张与配置写法时 |
| [`ai-lsposed-template-rules.md`](references/ai-lsposed-template-rules.md) | AI-lsposed-temlate 工程结构与两份 `.cursor` AI 规则（API 速查 + Classic API 禁用对照表） | 防 AI 生成代码混用新旧两代 API 时 |

## 限制

- **服务端校验绕不过**：VIP 权益等服务端校验，hook 只能改本地状态（isVip=true），实际权益由服务端决定（fanqie-purify 免登录说明）。
- **加固壳需脱壳**：hook 目标若加壳，需先脱壳拿到真实 dex。
- **native 逻辑**：so 内逻辑 hook 需 frida 等，非 Xposed Java 层 hook。

## 门类方法放行原则

按钮显隐类"门"方法（`isXxxShow`/`needShowXxx`）只是**入口判断**，不是最终仲裁者——门通过后还有真正的仲裁链决定显示什么。hook 门本身（尤其拦死返 false）会连后续更新逻辑一起掐掉，UI 停在初始态"看似没变"，且易误伤其他路径。

- 正确姿势：门方法放行，干预真正的仲裁者——服务端下发的按钮类型（`buttonType`/`getXxxType`）。
- 实证（主题商店 20.1.63.305，`docs/reverse/theme-store.md`）：`OnlinePreviewBaseActivity` 门通过后，`updateButtons` 按 `IncentiveData.getThemeDetailButtonType()` switch 决定显示哪组按钮；hook 门返 false 跳过 updateButtons，按钮停在 XML 初始 visible 态。
- 门后可能有**平行判定副本**：`ToolBarGroupLayout.needShowBuyButton()`、`WallpaperDetailView.needShowBuyButton()` 与 `isBuyBtnShow` 逻辑几乎逐行相同但是独立方法，需逐个覆盖才算完整。

## 移植前真机基线校验

迁移任何参考 hook 代码（同类插件/旧项目）前，先拿**真机实际安装的 apk** 反编译出基线，逐点验证类/方法存在性，再决定迁移哪些点：

- 无效点/历史兼容分支不迁移；README 宣传 ≠ 代码事实，以基线 apk 实际代码为准。
- 案例（荣耀三插件整合，归档文档已随模块收敛删除）：`HonorInstallerPurify` 8 组 hook 真机基线**全命中**可整体移植；其 README 声称的"降级安装"hook 在 v4.0 全文件不存在（残留宣传），不迁移。
- 版本先对齐：不同 MagicOS 版本类名混淆完全不同（MagicOS 10 v3.2 vs MagicOS 11 v4.0），装错版本不崩但静默失效——迁移前先 `adb dumpsys package` 对版本。
