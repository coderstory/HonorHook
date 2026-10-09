# Rain_1.1.5.apk 逆向分析报告

> 本文档为 `reference/Rain_1.1.5.apk` 逆向分析的留档记录，供后续本插件开发参考。
> 相关逆向产物目录见 §6；文中结论均经 `META-INF/xposed/` 三配置文件与 `res/values/strings.xml` 核对。

## 1. 基本信息

| 项目 | 内容 |
| --- | --- |
| 文件 | `reference/Rain_1.1.5.apk`（3.6MB，单个 classes.dex 6.6MB） |
| 包名 | `love.nairain.hook` |
| 模块名 | Rain |
| 作者 | 夜雨 / Nairain（GitHub 开发者） |
| 类型 | LSPosed 模块（libxposed API 102：`minApiVersion=102`、`targetApiVersion=102`、`staticScope=true`） |
| 入口类 | `love.nairain.hook.xposed.HookEntry`（`META-INF/xposed/java_init.list` 声明） |

AndroidManifest 要点：

- `compileSdk 37`，Application 为 `love.nairain.hook.ModuleApplication`
- MainActivity：带 `de.robv.android.xposed.category.MODULE_SETTINGS` intent-filter（即 LSPosed Manager 里点「设置」打开的入口）
- LauncherActivity：activity-alias（LAUNCHER），指向 MainActivity
- `queries` 声明 20 个目标包（与 scope.list 完全一致，见 §2）

## 2. Hook 作用域（scope.list）

scope.list 声明以下 20 个目标应用（逐一核对自 `META-INF/xposed/scope.list`，应为 20 行；早期分析口径记为 19 个）：

| # | 包名 | 应用 |
| --- | --- | --- |
| 1 | com.dragon.read | 番茄小说 |
| 2 | com.baidu.tieba | 百度贴吧 |
| 3 | com.phoenix.read | 飞读（搜狗阅读） |
| 4 | com.kylin.read | 麒麟阅读 |
| 5 | com.handsgo.jiakao.android | 驾考宝典 |
| 6 | com.autonavi.minimap | 高德地图 |
| 7 | com.clover.daysmatter | 正点日历 |
| 8 | com.tencent.androidqqmail | QQ 邮箱 |
| 9 | com.MobileTicket | 铁路 12306 |
| 10 | app.jjyy.chuckle | — |
| 11 | com.coolapk.market | 酷安 |
| 12 | com.baidu.netdisk | 百度网盘 |
| 13 | com.kmxs.reader | 追书神器 |
| 14 | com.cainiao.wireless | 菜鸟 |
| 15 | com.attempt.afusekt | — |
| 16 | com.duolingo | 多邻国 |
| 17 | com.mipay.wallet | 小米钱包 |
| 18 | com.huawei.health | 华为健康 |
| 19 | com.yunyi.smartcamera | — |
| 20 | com.chan.cwallpaper | — |

strings.xml 还显示适配了更多 app（未纳入默认 scope）：鸿果短剧 / 漫剧（hongguo）、七猫小说（qi_mao）、图灵（tuling）、AfuseKt — 说明 hook 目标由代码按包名分派，scope 只是 LSPosed 侧的默认勾选清单。

## 3. 混淆与防护特征（高混淆样本）

- 顶层 3106 个混淆类（`a.smali` / `a0.smali` / `a00.smali` …）
- 类名使用阿拉伯文字符（`۟ۢۦۦ۠.smali`、`ۥۦۨۥ.smali` 等，jadx 重命名为 C0xxx 系列）
- 控制流混淆：ApkControlFlowConfusion 8.0（`assets/protected_by_np/ApkControlFlowConfusion_8.0_*.txt` 声明）
- 字符串加密：`f129short[]` 数组 + `m388()` / `m595()` 运行时解密
- 控制流打散证据：HookEntry 构造函数满是 `while(true){ i^=1633; switch(i){ case 14/49/204/239... } }` 状态机

## 4. HookEntry 结构（jadx 伪代码，629 行）

- `HookEntry extends XposedModule`（libxposed API 102 新版基类），import `io.github.libxposed.api.XposedModule` / `XposedModuleInterface`
- 生命周期回调：
  - `onPackageLoaded(XposedModuleInterface.PackageLoadedParam)`（HookEntry.java:514）
  - `onPackageReady(XposedModuleInterface.PackageReadyParam)`（HookEntry.java:574）
- 回调内部逻辑被封装进混淆类（如 `C0110.m488(packageLoadedParam)`），具体 hook 逻辑深埋在混淆代码中
- DexKit 辅助定位：import `org.luckypray.dexkit`（C0150/C0151），apk 内含 libdexkit.so 四架构（armeabi-v7a / arm64-v8a / x86 / x86_64）——按特征（方法签名 / 字符串 / 调用关系）查找混淆目标方法

## 5. UI 结构（Jetpack Compose + miuix）

- 纯 Jetpack Compose 实现（大量 androidx.compose.* 依赖，`res/` 下无 layout/ 目录）
- strings.xml 揭示的页面与设置项：
  - About 页：开发者 / 版本 / 第三方库 / 链接
  - Adapted apps（适配应用列表）页：逐项显示 `Installed · %s` / `Not installed` / `Not adapted` 状态
  - 设置项：
    - Color Mode：Dark / Light / Follow system
    - Monet（Material You 动态取色，Android 12+ 可用）
    - Floating bottom bar（悬浮底栏）
    - Hide desktop icon（隐藏桌面图标）
- 悬浮底栏 + 三态主题 + Monet 与 LSPFRIDA 的 FloatingMiuixNavigationBar + ThemeModeStore 体系同源（miuix 组件库）

## 6. 逆向产物与工具链

### 产物目录（本项目）

| 目录 | 内容 |
| --- | --- |
| `system-files/rain-apktool/` | apktool 3.0.3 反编译产物：AndroidManifest 明文 + smali/ + res/ + lib/ + assets/ |
| `system-files/rain-jadx/` | jadx 1.5.6 伪代码（`sources/love/nairain/hook/...`） |
| `system-files/rain-extract/` | `META-INF/xposed/` 三个配置文件（module.prop / java_init.list / scope.list） |

### 工具链踩坑（已实测验证）

- **jadx 入口坑**：`jadx-1.5.6-all.jar` 直接用 `java -jar` 启动的是 GUI 入口（只跑 loading、不产出代码）——必须用 `tools/jadx/bin/jadx.bat`（内部调 `jadx.cli.JadxCLI` 主类），且需 `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.6.7-hotspot"`
- jadx 反编译 1910 个类完成，但有 76 个 error（混淆代码所致，属正常现象；失败类回退读 smali）
- apktool 反编译命令：`"$JAVA21" -jar tools/apktool.jar d -f <apk> -o system-files/rain-apktool`（JAVA21 = Adoptium 21；Zulu 8 版本太老无法运行）

## 7. 结论

Rain 是本插件的最佳同类参考：LSPosed 模块（libxposed 102 + DexKit 按特征定位 + 高混淆防护）+ miuix Compose UI（悬浮底栏 / 三态主题 / About 页 / 适配应用列表页）。其 UI 结构即我们 app 可参考的「参考软件 UI」，逆向产物与工具链流程可直接复用（§6）。