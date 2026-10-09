---
name: reverse-and-hook
description: 逆向分析安卓 app（dex/so/资源）并 hook 实现内存级功能修改（UI 与业务）的端到端方法论。当需要对目标 app 逆向、定位 hook 点、实现 UI 隐藏/替换/弹窗拦截或业务功能改动时使用。涵盖 dex/so/资源逆向、hook 点定位、UI 级与业务级 hook 手法、版本适配、真机验证。
metadata:
  author: 项目沉淀
  last-updated: '2026-10-09'
  keywords:
  - 逆向
  - hook
  - 功能修改
  - UI
  - 业务
  - dex
  - so
  - 资源
  - 内存级
---

## 适用场景

对目标安卓 app 逆向分析，定位 hook 点，用 LSPosed hook 实现内存级功能修改（UI 隐藏/替换 + 业务短路/改返回值）。端到端流程，细节用 reverse-decompile + static-analysis + lsposed-hook-expert skill。

## 流程

### 1. 获取目标 app
- `adb pull` 或已有 apk
- 加固壳需先脱壳（frida-dexdump / BlackDex / 内存 dump）

### 2. 逆向分析

#### dex（Java/Kotlin 层）— 主要
- `apktool d` → smali + AndroidManifest + res（reverse-decompile skill）
- `jadx.bat --show-bad-code` → 伪代码（**用 jadx.bat 非 `java -jar all jar`**，后者是 GUI 入口，见 reverse-decompile skill 坑）
- 自研 dex 脚本按特征查找（fanqie-purify adscan/dexsig 思路）

#### so（native 层）— 补充
- IDA / Ghidra 反汇编 `lib/*.so`
- 找 native 函数符号、JNI 注册（`RegisterNatives`）、交叉引用
- native 逻辑 hook 需 frida（非 Xposed Java 层）

#### 资源
- `aapt2 dump resources` → 资源 id / 字符串
- apktool `res/values/strings.xml`（功能线索）、`public.xml`（id 映射）

### 3. 定位 hook 点（static-analysis skill）
- 按字符串 / 资源 id 反查引用类
- 按方法签名定位重载（只 hook void 的，不碰 boolean/String 策略查询）
- 按继承链找收口点（`android.app.Dialog.show()` 覆盖所有原生弹窗，版本无关）
- 真机取证补充（`uiautomator dump` View 树 / `dumpsys window` 窗口 / LSPosed 日志）
- DexKit 按特征查找混淆方法（Rain 用法：参数/返回值/字符串引用/调用关系）

### 4. hook 实现功能修改（内存级，不改 apk）

hook 是运行时内存修改——注入目标进程改方法行为，app 重启后重新 hook，**不改 apk 文件**。

#### UI 改动
| 目标 | hook 手法 | 实践来源 |
|------|----------|---------|
| 隐藏广告/入口 View | hook `setVisibility` 改 GONE；hook 构造 / `onAttachedToWindow` 后 GONE | fanqie-purify MusicPatchAdContainer |
| 隐藏整块区域 | 收掉子 View + `collapseEmptyAncestors` 折叠空父容器 | fanqie-purify「我的」页 |
| 拦截弹窗 | hook `android.app.Dialog.show()` + 内容判定（文案规则扫 content-desc + text） | fanqie-purify 收口点 |
| 文案过滤 | hook `TextView.setText` 过滤广告文案 | fanqie-purify 文本规则 |
| 替换布局 | hook `setContentView` / `LayoutInflater.inflate` | — |
| 悬浮窗拦截 | hook `WindowManagerGlobal.addView`（只处理 type=2/panel/overlay） | fanqie-purify 兜底 |
| 多点重扫 | Dialog.show 后 0/300/800/1600/2800ms 扫（Lynx 异步渲染） | fanqie-purify |

#### 业务功能改动
| 目标 | hook 手法 | 实践来源 |
|------|----------|---------|
| 禁用广告请求 | hook `checkAdAvailable` 返回 false（请求前总闸，无闪烁） | fanqie-purify 广告位总闸 |
| 禁用功能 | hook 方法短路 `return void`（**只 void 重载**，不碰 boolean/String 策略） | fanqie-purify AudioHelper |
| 改本地状态 | hook 字段写 `isVip=true`（服务端校验绕不过） | fanqie-purify VIP patch |
| 阻止实例化 | hook 构造函数 | — |
| 改参数/返回值 | hook `before` 改 args / `after` 改 result（`XposedHelpers`） | — |

### 5. 版本适配
- **文案 + 结构定位 > 硬编码混淆类名/资源 id**（版本无关，fanqie-purify v1.9.21）
- 资源 id 用 `getIdentifier(name, type, pkg)` 按名查（不硬编码数值，逐版平移）
- 收口点（`Dialog.show`）版本无关
- `adapt_new_version.sh` 一键比对（资源 id + hook 目标在新 dex 是否还在）

### 6. 真机验证（只能真机直连，无模拟器）
- LSPosed 日志：hook 挂载 + 拦截确认（`✅ 已挂载` / `🔇 已拦截`）
- `adb shell uiautomator dump`：UI 效果
- `adb shell dumpsys window`：窗口层级

## 实践案例
- **Rain**（`reference/Rain_1.1.5.apk`）：DexKit 查找混淆 hook 点 + 控制流混淆 hook 入口（`HookEntry extends XposedModule`，hook 逻辑封装到混淆类 `C0110.m488`）
- **fanqie-purify**（`reference/fanqie-purify（AI开发插件实践）/`）：广告位总闸 + 收口点 + 版本无关 + 走过的弯路（详见 lsposed-hook-expert skill 方法论 6 条）

## 限制
- **服务端校验绕不过**：VIP 权益等服务端校验，hook 只能改本地状态，实际权益由服务端决定
- **加固壳需脱壳**：hook 目标若加壳，需先脱壳拿真实 dex
- **native 逻辑需 frida/IDA**：so 内逻辑非 Xposed Java 层 hook
- **内存级不持久**：app 重启重新 hook，不改 apk 文件（持久改 apk 需重打包，非本方法论范围）

## 版本错位根因排查

真机 UI 行为与逆向分析结论对不上时，**第一步验证版本**，不要直接怀疑 hook 逻辑：

- 对版本：`adb shell dumpsys package <pkg> | grep -i versionName` vs 逆向基线 apk 的版本（`aapt2 dump badging <apk>`）。版本不同 → 基线分析整篇作废重做，不是调 hook 能救的。
- 为什么悄无声息：libxposed 挂载容错"目标类/方法找不到只打 WARN（`installed N/M`）"——类名漂移/版本错位被静默吞掉，hook 不生效也不崩；**installed N/M 里 N 明显小于 M 是版本错位实锤**。
- 实证：MagicOS 10（v3.2）与 MagicOS 11（v4.0）安装器混淆完全不同，装错版本不崩、静默失效；参考插件 README 版本与 manifest 版本对不上的文档漂移也出现过。
- 排查顺序：先对版本 → 再看 installed N/M 命中率 → 最后才动 hook 实现。
