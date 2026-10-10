# LSPosed 项目 4 个 skill 文件技术错误修正记录

修正执行时间：2026-10-09
依据：静态审计 15 条 + 用户 jadx 失败补充 + AI-lsposed-temlate 分析 6 条（全部修正点在落盘前均经本机实证：读实码/查文件/跑命令）。

## 文件 1：`.claude/skills/reverse-decompile/SKILL.md`

### 1. jadx 主命令重写（高）
- **位置**：工具链 → jadx 一节（主命令 + `-Xmx4g` 说明 + 坑条目）
- **原文**：`"$JAVA21" -Xmx4g -jar tools/jadx/lib/jadx-1.5.6-all.jar --show-bad-code -d ...`；坑写"可能是 all jar 主类指向 GUI"
- **修正后**：`export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-21.0.6.7-hotspot"`（Windows 风格路径，/c/... POSIX 路径会被 bat 的 if exist 判失败）+ `tools/jadx/bin/jadx.bat --show-bad-code -d system-files/<app>-jadx <apk>`。坑改为确定性表述：all jar 的 MANIFEST Main-Class 是 `jadx.gui.JadxGUI`（**已用 `unzip -p tools/jadx/lib/jadx-1.5.6-all.jar META-INF/MANIFEST.MF` 实证**），`java -jar` 100% 进 GUI 绝不产出；备选写法 `"$JAVA21" -Xmx4g -cp ... jadx.cli.JadxCLI ...`。堆内存说明：jadx.bat 自带 DEFAULT_JVM_OPTS（MaxRAMPercentage=70，**grep jadx.bat 实证**），`-Xmx4g` 不能写命令行，须经 `JAVA_OPTS`/`JADX_OPTS` 环境变量（bat 源码 `%DEFAULT_JVM_OPTS% %JAVA_OPTS% %JADX_OPTS%` 实证支持）。
- **依据**：本机 `tools/jadx/bin/jadx.bat` 存在（含 -cp 调 `jadx.cli.JadxCLI`）；jar MANIFEST 实证。

### 2. "hook 19 个 app" → "hook 20 个 app"
- **位置**：实践案例 Rain 条目
- **修正**：19→20。审计原标"两处"（实践案例 + 方法论第 4 点），**实证**方法论第 4 点无该表述，仅有实践案例一处，已修。
- **依据**：`system-files/rain-apktool/unknown/META-INF/xposed/scope.list` 实计 20 个包名。

### 3. §反混淆 3 资源 id 段重写（中）
- **位置**：反混淆技术 → 3
- **原文**："按资源名查找，不硬编码 id 数值（fanqie-purify v1.9.21 起版本无关实现的核心）" + "实测 6.7.1.16→6.7.1.32 id 整体平移"
- **修正后**：id 数值与名字都可能逐版变（部分版本间平移、部分不变；MainHook 实证 6.7.1.32→6.7.2.32 的 14 个资源 id 数值没变，但名字重排且同名可不同义——e4q 两版指向完全不同控件）；**版本无关正解是文案+结构定位**（指向 lsposed-hook-expert §方法论 1）；`getIdentifier` 按名查只是辅助；PRE_HIDE_RES_IDS 硬编码数值仍双轨保留（只省一帧，换版本需重新提取）。
- **依据**：MainHook.java:93-95 注释"资源 id 全部未变……id 数值逐一相同，PRE_HIDE_RES_IDS 与 getIdentifier 规则原样可用"；PRE_HIDE_RES_IDS 实码（MainHook.java:189-202）仍在硬编码数值。

### 4. aapt2 段补不可用标注（中）
- **位置**：工具链 → aapt2
- **修正**：明示"本机 tools/ 无 aapt2（ANDROID_HOME 指向不存在的目录），需自行放入 tools/（来自 build-tools）"；首选替代改为 apktool 产物 `res/values/public.xml` 的 id 映射 + `unzip -p <apk> <file>` 快速读。
- **依据**：`ls tools/` 仅有 apktool.jar/jadx/platform-tools，无 aapt2 或 build-tools 目录。

### 5. "3106 个混淆类"口径修正（低）
- **位置**：反混淆 §1 现象 + 实践案例 Rain 条目（同口径两处）
- **修正后**："smali/ 顶层 3100 个混淆类 + 6 个保留包目录（android/androidx/io/love/np/org，共 3106 条目），全 dex 共约 3300 个类"。
- **依据**：`ls system-files/rain-apktool/smali/` 实计 3106 条目 = 3100 个 .smali 文件 + 6 个目录（android/androidx/io/love/np/org）。

### 6. 补 META-INF/xposed 位置与快速路径（低）
- **位置**：方法论 §1
- **修正**：apktool 把 META-INF 归入 `unknown/`——Rain 的 xposed 三件在 `system-files/rain-apktool/unknown/META-INF/xposed/`；免反编译快速读法 `unzip -p <apk> META-INF/xposed/module.prop`（rain-extract/ 即手工解压所得）。
- **依据**：`ls system-files/rain-apktool/unknown/META-INF/xposed/` 实计 java_init.list/module.prop/scope.list。

### 7.（用户补充）jadx 失败类必须回 smali 读
- **位置**：反混淆 §2 控制流混淆对策新增一条
- **修正**：jadx 反编译常有 N 个类失败（Rain 76 个 error，exit code 1 但产物已写出），失败类只有空壳/stub，必须回 smali 读——**smali 是根本能力，jadx 伪代码是加速器**。
- **依据**：用户反馈 jadx 失败只能看 smali。

### 8. dexfind.py 口径（高）
- **位置**：方法论 §4（reverse-decompile 正文唯一一处）+ static-analysis §hook 定位 1（审计所称"第二处"实在此文件）+ 自研脚本表下补注
- **修正后**：两处思路引用均改"（fanqie-purify REVERSE_ENGINEERING 文档提及的 dexfind 思路，脚本未随仓库提供）"；自研脚本表格下补："`adscan.py`/`dexsig.py` 的 `from dexfind import Dex` 依赖的共享模块缺失，**当前不可运行**，仅可借鉴算法思路"。
- **依据**：`ls reference/fanqie-purify（AI开发插件实践）/tools/` 仅有 adscan.py/dexsig.py/apkver.py/adapt_new_version.sh，无 dexfind.py/dexdump.py；adscan.py:17 与 dexsig.py:15 均 `from dexfind import Dex` 实证。

## 文件 2：`.claude/skills/static-analysis/SKILL.md`

### 9. 工具表修正（高）
- **位置**：工具表"自研 dex 脚本"行
- **修正后**：行内标注"adscan/dexsig 按特征分析（`from dexfind import Dex` 依赖缺失，当前不可运行）；dexfind/dexdump 仅 REVERSE_ENGINEERING 文档提及，未随仓库提供"。
- **依据**：同修正 8（tools/ 目录实证）。

### 10. "19 个目标包名"改 20
- **位置**：§hook 点定位方法第 4 点
- **修正**：19→20。
- **依据**：rain scope.list 实计 20 个包名。

### 11. libxposed compileOnly 坑一节重写（中）
- **位置**：jadx 伪代码阅读 → 坑
- **原文**："jadx 无法解析继承关系（大量 WARN Can't compare types...）"——与实际产物矛盾
- **修正后**："jadx 能打出 `extends XposedModule`（名字来自 dex 引用，rain-jadx 的 HookEntry.java:36 实证 `public final class HookEntry extends XposedModule`），但 libxposed API 是 compileOnly、apk 内无类定义，基类无方法体可分析；叠加控制流混淆打散结构后伪代码不可读——**以 smali 为准**"。
- **依据**：rain-jadx 产物 HookEntry.java:36 实际输出（读产物实证）。

### 12.（用户补充）新增小节"jadx 失败场景与 smali 兜底"
- **位置**：jadx 伪代码阅读节内，坑一节之前
- **内容**：jadx "finished with errors, count: N"（Rain 76 个）失败类只有空壳/stub 必须回 smali；控制流混淆严重时 goto 蛮荒不可读；R8 full mode、加固壳、花指令 jadx 处理不了；结论：**smali 阅读是根本能力（兜底必经），jadx 伪代码是加速器（能读就快）**，不是"伪代码为主、smali 辅助"。

## 文件 3：`.claude/skills/lsposed-hook-expert/SKILL.md`

### 13. "两版本质相同"技术错误修正（高）
- **位置**：API 选型末尾
- **原文**："两版 hook 方法调用本质相同（XposedBridge.hookMethod / XposedHelpers.findAndHookMethod），方法论通用"
- **修正后**："两版底层同为 ArtMethod 替换（LSPlant 引擎），**但 API 完全不同**：旧版 XposedBridge.hookMethod / XposedHelpers.findAndHookMethod；新版是链式调用 `hook(method).intercept { ... }`（无 XposedHelpers，框架不提供，用 libxposed/helper 或裸反射）。在 102 模块里 import XposedHelpers 是典型错误"。
- **依据**：reference 三插件实码均为链式 API；旧版 API 无 `hook(...)` 方法。

### 14.（高，新增）新版 API hook 调用最小示例
- **位置**：API 选型新版小节末尾
- **内容**：摘录两段真实实码并注明来源——HyperChanger `AppBottomNavHooks.kt:289-291`：`module.hook(setClipBottom).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept { chain -> chain.proceed(arrayOf<Any>(0f)) }`；notification-guard `GuardModule.java:14-15`：`handles.add(hook(target).setId(id).setExceptionMode(...).intercept(hooker));`（覆盖 `intercept { chain -> chain.proceed(...) }`、`.setId(...)`、`.setExceptionMode(...)`）。
- **依据**：两处参考实码直读（注意：AppBottomNavHooks.kt 实际路径在 `btm/m/liquidglass/hook/` 而非审计给的 `btm/m/os4/systemuihook/`，289-291 行内容与审计描述一致）。

### 15. minApiVersion 表述修正（中）
- **位置**：新版小节入口注册行
- **修正后**：`targetApiVersion=102`（对齐当前 API，四参考一致）；`minApiVersion`=模块支持的最低框架 API（各异：LSPFRIDA=82 / HyperChanger=101 / notification-guard=102 / Rain=102，低值兼容更老框架）；补 module.prop 其他实用键：`autoHotReload`、`exceptionMode`、`description`；补 HyperChanger 存在 `native_init.list`（native hook 入口）。
- **依据**：四个 `module.prop` 逐文件读取实证（HC minApiVersion=101、NG=102、LSPFRIDA=82 + exceptionMode=protective + autoHotReload=false、Rain=102）；`find HyperChanger -name "native_init*"` 命中 `src/main/resources/META-INF/xposed/native_init.list`。

### 16. 广告位名单补全（低）
- **位置**：方法论 §2 广告位总闸
- **原文**：只列 4 项（splash_ad/page_front_ad/reader_banner/audio_patch_ad）
- **修正后**：黑名单实际 9 项：splash_ad、page_front_ad、page_middle_ad、reader_banner、audio_patch_ad、audio_patch_pre_ad、audio_info_flow、mini_game_config、free_ad_enter（book_mall_feed_ad 因误伤已移除——以 MainHook BLOCKED_AD_POSITIONS 为准）。
- **依据**：MainHook.java:230-242 BLOCKED_AD_POSITIONS 实码读取（含 book_mall_feed_ad 已移除注释）。

### 17. 报错文本逐字修正（低）
- **位置**：方法论 §6 弯路 abstract 条目
- **修正**："is abstract: no body" → "is abstract: it has no body to hook"（MainHook.java:34 原文）。
- **依据**：MainHook.java:34 实读。

### 18. DexKit 补依赖坐标（低）
- **位置**：DexKit 一节末尾
- **修正**：`implementation("org.luckypray:dexkit:2.2.0")`（HyperChanger build.gradle.kts:84 实读）；Rain 通过 DexKitBridge 使用（apk 内 libdexkit.so 四架构）；澄清 HookEntry 构造器里的 dexkit 包引用实为混淆器塞进的字符串解密 helper，真正的查找调用在分发类里。
- **依据**：HC build.gradle.kts:84 实读；Rain apktool 产物分析。

### 19. type=1/type=2 存疑标注（低）
- **位置**：方法论 §3 收口点 hook 的 WindowManagerGlobal 条目
- **修正后**："过滤四类窗口类型（TYPE_APPLICATION=type=2 等）"；注：AOSP 语义中 type=2 即 normal application window（activity 自身也常是 type=2），代码层未强制排除 activity decor——真正的误伤护栏是 PROMO_POPUP_PATTERNS 文案特异性（原作者注释声称 activity 不会走到此路径，待真机验证）。
- **依据**：MainHook.java:3584-3590 实码（floating 判定含 `ty == TYPE_APPLICATION`）；MainHook.java:3636 注释"用户主动打开的会员开通页是 Activity 而不是 Dialog，本来就不会走到这里"。

### 20. 旧版注册补 meta-data 四件套（低）
- **位置**：旧版小节注册行
- **修正**：旧版除 assets/xposed_init 外，AndroidManifest 还有 meta-data：`xposedmodule=true` / `xposeddescription` / `xposedminversion=82` / `xposedscope=目标包名`（旧版 minVersion 与 scope 声明走 manifest meta-data，与新版 META-INF/xposed 机制不同）。
- **依据**：fanqie-purify `module/AndroidManifest.xml` 实读（4 个 meta-data 逐条存在）。

### 21.（AI-temlate 吸收）开发流程补两条陷阱
- **位置**：开发流程新增第 7、8 条
- **内容**：① 多包进程陷阱——hook 回调按包名分发前须校验 `param.packageName`（且 `param.isFirstPackage` 判定首个进程实例）；② AI 生成 hook 代码防混用两代 API 的负面清单可参考 `reference/AI-lsposed-temlate/.cursor/rules/project-context.mdc`（Classic API 禁用/替换对照表）。
- **依据**：project-context.mdc 实读（禁用表 10 行 + "不要两套 API 并存"）；libxposed API 定义 `isFirstPackage` 字段。

## 文件 4：`.claude/skills/build-lsposed-module/SKILL.md`

### 22. 删 XposedProvider 注册（高）
- **位置**：§4 AndroidManifest 示例
- **修正**：删除 `<provider android:authorities="<包名>.XposedService" ... android:name="io.github.libxposed.service.XposedProvider"/>`；在 manifest 后补注：四个参考项目 manifest 均无此 provider，统一做法是 `Application` 里 `XposedServiceHelper.registerListener(...)`（参考 AI-temlate App.kt:50、HC HookApplication.kt:14、LSPFRIDA LSPFRIFAApplication.kt:29）。
- **依据**：三处 Application 源码实读（registerListener 调用行号逐一命中）；grep reference 下源码无 XposedProvider 声明（仅 LSPFRIDA docs 提及 service 内部类）。

### 23. 补 packaging merges 硬手法（高）
- **位置**：§1 build.gradle.kts 示例
- **修正后**：`packaging { resources { excludes += "**"; merges += "META-INF/xposed/*" } }`——先排除全部资源再显式 merge xposed 元数据，保证混淆/压缩后 java_init.list 存活（比仅靠 proguard keep 更可靠）。
- **依据**：AI-temlate `module/app/build.gradle.kts:41-46` 实读（excludes 与 merges 并存）。

### 24. 补 proguard 规则（中）
- **位置**：新增 §2.5 混淆（proguard）必配规则
- **内容**：`-adaptresourcefilecontents META-INF/xposed/java_init.list`（R8 自动改写 init 文件里的类名以匹配混淆后 FQCN，否则框架找不到入口类）+ `-keep,allowobfuscation,allowoptimization public class * extends io.github.libxposed.api.XposedModule {...}`（含四回调方法）。
- **依据**：AI-temlate `module/app/proguard-rules.pro` 实读。

### 25. 补工程目录约定（低）
- **位置**：§1 末尾
- **内容**：`apksource/<包名>/`——目标 app 反编译代码放此目录只读参考、不参与编译，hook 时用 `param.getClassLoader().loadClass(...)` 按字符串加载（来源 AI-temlate .cursor 规则，与 system-files 逆向工作流契合，可选采纳）。
- **依据**：project-context.mdc"apksource"节实读（"禁止将 apksource 中的类编入模块 APK"）。

### 26. 补镜像配置参考（低）
- **位置**：§1 版本基准列表
- **内容**：Gradle 仓库可用全阿里云镜像（AI-temlate settings.gradle.kts:1-21：gradle-plugin/google/central/public 四仓 + `RepositoriesMode.FAIL_ON_PROJECT_REPOS`）+ `mavenLocal` 限定 `includeGroup("io.github.libxposed")` 离线兜底——国内网络环境推荐。
- **依据**：AI-temlate `module/settings.gradle.kts:1-21` 实读。

## 执行说明

- 修正工具：`safe_update`（局部原子替换），frontmatter（name/description/metadata）未动，章节结构保持。
- 全部 27 个修正点（26 条修正 + 1 份记录文档）已落盘；修正前每条均经本机实证（读实码/`ls`/`unzip -p`/`grep`）。
- 偏差说明：审计清单个别位置标注与实际文件不完全一致——①"hook 19 个 app"两处，实证仅实践案例一处（方法论第 4 点无此文字）；②"dexfind.py 思路"两处，reverse-decompile 正文一处、另一处在 static-analysis（已一并修正）；③AppBottomNavHooks.kt 引用路径为 `btm/m/liquidglass/hook/`（审计写 `btm/m/os4/systemuihook/`），内容行 289-291 与审计描述一致。