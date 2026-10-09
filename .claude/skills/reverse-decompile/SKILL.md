---
name: reverse-decompile
description: 安卓 apk 逆向反编译、还原与反混淆。当需要反编译 apk、阅读混淆/加固代码、还原类名/控制流/资源 id、为 LSPosed hook 开发定位目标时使用。涵盖 apktool/jadx 工具链、反混淆技术（阿拉伯文类名、控制流混淆、资源 id 平移）、DexKit 辅助查找、自研 dex 分析脚本方法。
metadata:
  author: 项目沉淀
  last-updated: '2026-10-09'
  keywords:
  - 逆向
  - 反编译
  - apktool
  - jadx
  - 反混淆
  - DexKit
  - LSPosed
---

## 适用场景

需要反编译安卓 apk（尤其混淆/加固的）、还原代码、为 LSPosed hook 开发定位目标类/方法/资源时使用。本 skill 沉淀自项目实践：Rain_1.1.5.apk（高混淆 LSPosed 模块）+ fanqie-purify（自研 dex 分析工具与逆向报告）。

## 环境约束（本项目）

- **Java**：本机 Zulu 8（默认 JAVA_HOME，1.8）和 Adoptium 21（`C:\Program Files\Eclipse Adoptium\jdk-21.0.6.7-hotspot\bin\java.exe`）。**apktool/jadx 需 Java 11+，必须用 Adoptium 21**（Zulu 8 不够）。
- **工具位置**：`tools/`（`apktool.jar`、`jadx/`）。
- **下载 github 二进制用 PowerShell `Invoke-WebRequest`**（走系统代理），curl 在公司网被拦（http_code=000）。
- **反编译产物放** `system-files/<app>-apktool/` 和 `system-files/<app>-jadx/`，源 apk 保留。
- **无 Windows 管理员权限**：工具用户级便携，不依赖系统安装。

## 工具链

### apktool（反编译：AndroidManifest 明文 + res 资源 + smali）

```bash
JAVA21="/c/Program Files/Eclipse Adoptium/jdk-21.0.6.7-hotspot/bin/java.exe"
"$JAVA21" -jar tools/apktool.jar d -f <apk> -o system-files/<app>-apktool
```

产物：`AndroidManifest.xml`（明文）、`res/`（资源）、`smali/`（dex 反汇编）、`apktool.yml`、`lib/`、`assets/`。apktool 对混淆 dex 能正常 baksmali（它只做 dex→smali 转译，不解逻辑）。

### jadx（反编译：Java/Kotlin 伪代码）

```bash
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.6.7-hotspot"   # Windows 风格路径——/c/... POSIX 路径会被 bat 的 if exist 判失败
tools/jadx/bin/jadx.bat --show-bad-code -d system-files/<app>-jadx <apk>
```

- `--show-bad-code`：混淆代码反编译不完美时也输出（带 goto），对混淆 apk **必加**。
- 堆内存：jadx.bat 自带 `DEFAULT_JVM_OPTS`（`MaxRAMPercentage=70`），`-Xmx4g` 不能写在命令行，须通过 `JAVA_OPTS` 或 `JADX_OPTS` 环境变量传入（如 `JAVA_OPTS="-Xmx4g"`）；大/混淆 dex 堆不够会 OOM 中途退出、产物不生成。
- `--no-res`：只要源码时跳过资源（更快）。
- 产物：`sources/<package>/...java`。

**坑**：`jadx-1.5.6-all.jar` 的 MANIFEST Main-Class 是 `jadx.gui.JadxGUI`（已用 unzip -p 实证），`java -jar` 100% 进 GUI 绝不产出（不是"可能"）。正解是 `tools/jadx/bin/jadx.bat`（内部以 `-cp ... jadx.cli.JadxCLI` 调 CLI 主类，命令前 `export JAVA_HOME` 指向 Adoptium 21）；备选写法 `"$JAVA21" -Xmx4g -cp tools/jadx/lib/jadx-1.5.6-all.jar jadx.cli.JadxCLI --show-bad-code -d system-files/<app>-jadx <apk>`。

### aapt2（资源 id 分析）

```bash
aapt2 dump resources <apk> | grep <resource_name>
```

按资源名查 id 数值，比对版本间 id 平移。**注意：本机 tools/ 无 aapt2（ANDROID_HOME 指向不存在的目录），需自行放入 tools/（来自 build-tools）**。首选替代：apktool 解码产物 `res/values/public.xml` 的 id 映射 + `unzip -p <apk> <file>` 快速读。

## 反混淆技术

### 1. 类名/方法名混淆（含阿拉伯文字符）

- **现象**：类名如 `۟ۢۦۦ۠`、`a.smali`、`a0.smali`（Rain smali/ 顶层 3100 个混淆类 + 6 个保留包目录 android/androidx/io/love/np/org，共 3106 条目；全 dex 共约 3300 个类）。
- **对策**：
  - 建立重命名映射表（按功能推断语义，记录到 `system-files/<app>-rename-map.md`）。
  - 按字符串常量/调用关系推断类功能（如含 "开始播放语音播报" 的类是语音播报入口）。
  - **入口类通常不混淆**：xposed `java_init.list` / AndroidManifest 的入口类（如 `love.nairain.hook.xposed.HookEntry`）是明文，作为起点顺藤摸瓜。

### 2. 控制流混淆（ApkControlFlowConfusion 等）

- **现象**：smali/jadx 伪代码满是 goto、switch 跳转，结构被打散（Rain 用了 `ApkControlFlowConfusion_8.0`，见 `assets/protected_by_np/`）。
- **对策**：
  - jadx `--show-bad-code` 输出不完美伪代码，人工识别 goto 模式还原 if/loop 结构。
  - **jadx 失败类必须回 smali 读**：jadx 反编译常有 N 个类失败（Rain 76 个 error，exit code 1 但产物已写出），失败类只有空壳/stub——**smali 是根本能力，jadx 伪代码只是加速器**。
  - 关注方法签名 + 字符串 + 调用关系，而非逐行读懂控制流。
  - 必要时回 smali 层看指令序列（`invoke-virtual`/`move-result`/`if-*`）。

### 3. 资源 id 混淆 + 逐版变化

- **现象**：id 数值与名字都可能逐版变（即使 versionCode 不变也可能平移）——部分版本间平移、部分不变（MainHook 实证：6.7.1.32→6.7.2.32 的 14 个资源 id 数值没变，但名字重排且同名可不同义——e4q 两版指向完全不同控件）。
- **对策**：
  - **版本无关正解是文案+结构定位（见 lsposed-hook-expert skill §方法论 1）**，`getIdentifier(name, type, pkg)` 按名查只是辅助。
  - fanqie-purify 的 PRE_HIDE_RES_IDS 硬编码数值仍双轨保留（只省一帧闪现，换版本需重新提取）。
  - aapt2 dump resources 比对新旧包 id。

### 4. 字符串解密

- 混淆 apk 常把字符串加密，运行时解密。
- 对策：定位解密方法（通常集中调用某个 decrypt），hook 解密方法或静态还原；jadx 常能识别简单解密。

### 5. DexKit 辅助（混淆 app 的 hook 点查找）

- DexKit 是 LSPosed 生态的 dex 分析库（`libdexkit.so`），按特征查找混淆方法：参数类型/返回值/字符串引用/调用关系/注解。
- **适用**：目标 app 高混淆，类名/方法名不可读，但知道方法特征（如"接收 String 返回 boolean 且引用了某字符串"）。
- Rain 用 DexKit 查找 hook 点。详见 lsposed-hook-expert skill。

## 方法论（从 fanqie-purify REVERSE_ENGINEERING 沉淀）

参考：`reference/fanqie-purify（AI开发插件实践）/REVERSE_ENGINEERING_v1.9.23.md`

1. **先看明文配置**：`AndroidManifest.xml`（组件/权限/包名）+ `META-INF/xposed/`（`module.prop`/`java_init.list`/`scope.list`，入口类 + hook 目标）+ `res/values/strings.xml`（功能线索）。**注**：apktool 把 META-INF 归入 `unknown/`——Rain 的 xposed 三件在 `system-files/rain-apktool/unknown/META-INF/xposed/`；免反编译快速读法：`unzip -p <apk> META-INF/xposed/module.prop`（`system-files/rain-extract/` 即手工解压所得）。
2. **规模评估**：统计类/方法/指令数（jadx 加载日志给，如 Rain 3300 类/15251 方法/88万指令），判断混淆程度。
3. **入口顺藤**：从 xposed 入口类（不混淆）出发，看 hook 哪些目标、调用什么工具。
4. **按字符串/资源 id 反查**：用关键词枚举相关类（fanqie-purify `adscan.py` 思路），按字符串/id 反查引用类（fanqie-purify REVERSE_ENGINEERING 文档提及的 dexfind 思路，脚本未随仓库提供），打印方法签名（`dexsig.py`）。
5. **真机取证补充**：`uiautomator dump` View 树、`dumpsys window` 看窗口、LSPosed 日志——静态枚举不到的服务端下发内容靠运行时抓。
6. **核心结论：逐一按类名封堵不可持续**（一版一变），优先找收口点（如 `android.app.Dialog.show()` 覆盖所有原生弹窗）+ 文案/结构兜底。

## 自研 dex 分析脚本（fanqie-purify tools/）

参考 `reference/fanqie-purify（AI开发插件实践）/tools/`：

| 脚本 | 作用 |
|------|------|
| `adscan.py` | 按关键词枚举 dex 里真实定义的类（广告/弹窗/会员/hybrid bridge）+ 字符串池搜索 |
| `dexsig.py` | 打印类的方法签名清单（决定 hook 哪个重载） |
| `apkver.py` | apk 版本信息 |
| `adapt_new_version.sh` | 一键适配新版本：拉包 + 资源 id/hook 目标比对（不动代码） |

这些脚本用 Python 直接解析 dex 结构（不依赖 smali 工具），可复用思路。**注意**：`adscan.py`/`dexsig.py` 的 `from dexfind import Dex` 依赖的共享模块缺失，**当前不可运行**，仅可借鉴算法思路（dexfind 只存在于 REVERSE_ENGINEERING 文档描述中，脚本未随仓库提供）。

## 实践案例（本项目）

- **Rain_1.1.5.apk**（`reference/Rain_1.1.5.apk`）：LSPosed 模块，高混淆（3100 混淆类 + 6 个保留包目录（3106 条目）+ 阿拉伯文类名 + ApkControlFlowConfusion）。apktool 反编译产物在 `system-files/rain-apktool/`。入口 `love.nairain.hook.xposed.HookEntry`，hook 20 个 app（番茄/贴吧/高德/QQ邮箱/12306/酷安/百度网盘/菜鸟/多邻国/小米钱包/华为健康等）。
- **fanqie-purify**（`reference/fanqie-purify（AI开发插件实践）/`）：自研 dex 分析工具 + `REVERSE_ENGINEERING_v1.9.23.md` 逆向报告（目标番茄畅听 111MB/15 dex/5181 广告类），是方法论范本。

## 限制

- **加固壳**（360/腾讯乐固/娜迦等）：apktool/jadx 直接反编译得到的是壳代码，需先脱壳（frida-dexdump / BlackDex / 内存 dump）。
- **native so**（`lib/*.so`）：需 IDA/Ghidra，本 skill 不覆盖。
- **服务端下发内容**（Lynx/H5/自绘）：APK 内无宿主类，静态分析枚举不到，只能运行时按文案/结构兜底（见 lsposed-hook-expert skill）。
