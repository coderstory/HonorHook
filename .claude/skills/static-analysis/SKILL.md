---
name: static-analysis
description: 安卓静态代码分析——阅读 smali 与 jadx 伪代码，定位 LSPosed hook 目标点。当需要从反编译产物中找 hook 点、理解目标 app 行为、按字符串/资源 id/调用关系定位混淆类与方法时使用。涵盖 smali 语法、jadx 伪代码阅读、自研 dex 分析脚本方法、真机取证补充。
metadata:
  author: 项目沉淀
  last-updated: '2026-10-09'
  keywords:
  - 静态分析
  - smali
  - jadx
  - 伪代码
  - hook 点定位
  - dex
  - uiautomator
---

## 适用场景

从反编译产物（apktool smali / jadx 伪代码）中阅读代码、定位 hook 点、分析目标 app 行为。**前置**：先用 reverse-decompile skill 反编译拿到产物。

## smali 语法基础

smali 是 dex 的反汇编格式。

### 寄存器
- `v0`-`vN`：局部寄存器
- `p0`-`pN`：参数寄存器（p0 = this（实例方法）/ 第一个参数（静态方法））

### 常见指令
| 指令 | 含义 |
|------|------|
| `invoke-virtual {p0, v1}, Lcom/pkg/Class;->method(Ljava/lang/String;)V` | 调用实例方法 |
| `invoke-direct` | 调用 private/构造方法 |
| `invoke-static` | 调用静态方法 |
| `invoke-super` | 调用父类方法 |
| `move-result-object` / `move-result` | 取上一个 invoke 的返回值 |
| `const-string v0, "xxx"` | 字符串常量 |
| `iget`/`iput` | 实例字段读/写 |
| `sget`/`sput` | 静态字段读/写 |
| `if-eqz`/`if-nez`/`if-eq`/`if-ne` | 条件跳转 |
| `return-void`/`return-object` | 返回 |

### 方法签名
`Lcom/pkg/Class;->methodName(Lparam/types;)retType`
- `L` 开头是对象，`;` 结尾
- 基本类型：V(void)/Z(boolean)/I(int)/J(long)/D(double)/F(float)/B(byte)/S(short)/C(char)
- 数组：`[` 前缀，如 `[Ljava/lang/String;`

### 阅读策略
- 不逐行读，先看方法签名 + 字符串常量（`const-string`）+ invoke 调用链。
- 混淆代码关注 `const-string`（线索）+ `invoke`（调用关系）。
- 控制流混淆时识别 goto 模式，结合 jadx 伪代码对照。

## jadx 伪代码阅读

jadx 把 smali 反编译成 Java/Kotlin，更可读。但混淆代码会有：
- 大量 `goto`（控制流未还原）
- 不可读类名（a, b, c, ۟ۢۦۦ۠）
- 反编译失败的 `// jadx: ...` 注释

### jadx 失败场景与 smali 兜底

- jadx 报 "finished with errors, count: N"（Rain 76 个）——失败类只有空壳/stub，必须回 smali 读。
- 控制流混淆严重时伪代码满是 goto 蛮荒不可读。
- R8 full mode、加固壳、花指令 jadx 处理不了。
- 结论：**smali 阅读是根本能力（兜底必经），jadx 伪代码是加速器（能读就快）**——不是"伪代码为主、smali 辅助"。

### 坑：libxposed compileOnly 类型缺失
对 libxposed API 模块（如 Rain），API 是 `compileOnly`（运行时由框架提供，apk 内无类定义）。jadx 能打出 `extends XposedModule`（类名来自 dex 引用，rain-jadx 的 HookEntry.java:36 实证 `public final class HookEntry extends XposedModule`），但基类无方法体可分析；叠加控制流混淆打散结构后伪代码不可读——**以 smali 为准**（apktool smali 有完整指令序列）。

## hook 点定位方法（核心）

### 1. 按字符串/资源 id 反查引用类
fanqie-purify REVERSE_ENGINEERING 文档提及的 dexfind 思路（脚本未随仓库提供）：给定字符串或资源 id，反查 dex 里引用它的类与方法。
- 字符串："开始播放语音播报" → 定位到 `com.bytedance.polaris.impl.voice.w.a(String,String)`
- 资源 id：`0x7f061268` → 反查哪个类 `const` 它

### 2. 按方法签名定位重载
fanqie-purify `dexsig.py` 思路：打印类的所有方法签名，决定 hook 哪个重载。
- 如 `AudioHelper` 有 `s(String)`/`r(long)`/`j(String)` 多个重载，按签名区分（只短路 void 的，不碰 boolean/String 策略查询）。

### 3. 按调用关系/继承链推断收口点
- 继承链：`i23.d0 extends com.dragon.read.widget.dialog.i extends android.app.Dialog` → 所有这类弹窗都走 `android.app.Dialog.show()`（稳定收口点，版本无关）。
- 调用关系：`bridge.modules.vip.a.showVipPromotionPopup` → `new i23.d0` → `dialog.i.show()`。

### 4. 按关键词枚举类
fanqie-purify `adscan.py` 思路：按关键词（广告/弹窗/会员/hybrid bridge）枚举 dex 里真实定义的类 + 字符串池搜索。Rain `scope.list` 的 20 个目标包名是第一层线索。

### 5. 真机取证补充（静态枚举不到的）
静态分析枚举不到服务端下发内容（Lynx/H5/自绘），需运行时抓：
- `adb shell uiautomator dump` → View 树 XML（看 content-desc/text/类名）
- `adb shell dumpsys window` → 窗口层级（看浮动窗口 type/gravity/surfaceInsets）
- LSPosed 日志 → hook 挂载/拦截确认
- fanqie-purify `adapt_new_version.sh --arm-dump` → 手机端 View 树转储，`--pull-tree` 拉到 artifacts/

## 工具

| 工具 | 用途 |
|------|------|
| apktool smali | dex→smali 反汇编，指令级阅读 |
| jadx 伪代码 | dex→Java/Kotlin，结构级阅读 |
| aapt2 dump resources | 资源 id/字符串 |
| 自研 dex 脚本（fanqie-purify tools/） | adscan/dexsig 按特征分析（`from dexfind import Dex` 依赖缺失，当前不可运行）；dexfind/dexdump 仅 REVERSE_ENGINEERING 文档提及，未随仓库提供 |
| uiautomator dump | 运行时 View 树 |
| dumpsys window | 窗口层级 |

## 实践案例

### Rain HookEntry（`system-files/rain-apktool/smali/love/nairain/hook/xposed/HookEntry.smali`）
26KB 入口 smali，用 libxposed API 102 + DexKit。读 smali 看 hook 挂载逻辑（`onPackageLoaded` 回调里对 scope 内 app 调 DexKit 查找 + hook）。jadx 因 libxposed 类型缺失产出不理想，以 smali 为准。

### fanqie-purify 定位 Polaris 语音子系统
参考 `reference/fanqie-purify（AI开发插件实践）/REVERSE_ENGINEERING_v1.9.23.md`：
- 字符串 "开始播放语音播报" → 反查到 `com.bytedance.polaris.impl.voice.w.a(String,String)`
- 方法签名清单（dexsig）→ 决定 hook `w.a`/`w.o` + `AudioHelper.s/r/j`
- 继承链 `i23.d0 → dialog.i → android.app.Dialog` → 收口点 `Dialog.show()`

## 输出：hook 点清单

静态分析后产出 hook 点清单，供 lsposed-hook-expert skill 使用：
- 类名（全限定）
- 方法名 + 签名（参数/返回值/重载选择）
- hook 策略（before/after/replace；短路返回 void / 改返回值）
- 依据（字符串/调用关系/继承链证据）
