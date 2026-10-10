<!-- 归档：docs/archive/rain-hook-architecture.md → skill references（2026-10-10 md 重构） -->
<!-- 口径提示：正文写于 Windows 期/早期调研阶段，Windows 路径（C:\ / .bat / tools/jdk-21）与已删目录（.buildtmp/、system-files/*-apktool 等）可能失效；结论与方法论仍有效。 -->

# Rain LSPosed 模块 Hook 架构分析（DexKit + 混淆对抗）

> 分析对象：`reference/Rain_1.1.5.apk`（包名 `love.nairain.hook`，libxposed API 102）
> 材料：`system-files/rain-jadx/sources/`（jadx 伪代码）+ `system-files/rain-apktool/smali/`（smali 原文）
> 规则：smali 与 jadx 交叉验证，libxposed compileOnly 无方法体、以 smali 为准；混淆读不动处如实标注。
> 本文为 HonorHook（荣耀系统 hook）开发的前置参考，重点回答：入口怎么分发、DexKit 怎么用、hook 怎么挂、字符串加密怎么绕过。

---

## 0. 一句话结论

Rain 的架构 = **单入口类 HookEntry + 接口化 per-app 适配器（oa 接口）+ 静态注册表（va）+ 双轨目标解析（直连反射优先、DexKit 兜底）+ 统一 hook 挂载助手（ck0 + qa/sa 两个 Hooker）**。
-Rain 的字符串加密是**每个调用点独立 key 的单轮 XOR**，已在本机用 Python 离线还原出三个关键字符串，可批量破解。

---

## 1. 入口分发链路

### 1.1 调用链全貌（文字版）

```
LSPosed 注入目标进程
  └─ HookEntry.onPackageLoaded(PackageLoadedParam)          [smali: love/nairain/hook/xposed/HookEntry.smali:1108]
       ├─ 垃圾校验调用 C0110.m488(param)                    ← jadx 命名冲突产物，实为 androidx/versionedparcelable.ۦۢۦ.m488 = obj.getClass()，结果丢弃（HookEntry.java:515）
       ├─ m591(param) = param.isFirstPackage()              [HookEntry.java:277-306] 非首进程直接 return
       ├─ s90.a = m593(this)                                ← 读模块远程偏好：'rain_module' / 'enable_debug_logging'（字符串解密值，见 §4）
       ├─ c5.M = param.getApplicationInfo()                 [smali:579]
       ├─ C0164.m828() = va.a（触发注册表 clinit，实际无副作用）[defpackage/C0164.java:60]
       ├─ pkg = param.getPackageName()                      [smali:504-506]
       ├─ oa = C0050.m224(pkg) → va.a(pkg)                  ← 注册表线性查表（见 1.2）
       └─ 非 null → oa.a(this, param)                       [smali:435-437 invoke-interface Loa;->a(...)]

HookEntry.onPackageReady(PackageReadyParam)                 [smali:1234]
  └─ m596(param) = param.isFirstPackage() + pkg → va 查表 → oa.b(this, param)
       （oa 接口中 b() 为带默认体的虚方法，默认空实现，未覆盖者什么都不做）
```

### 1.2 注册表（va）——按包名分发到 20 个 app

- **`va.a(String)`**（`smali/va.smali:269`）：遍历 `va.a` 这个 `ArrayList<oa>`，逐项 `oa.getPackageName().equals(pkg)`，命中返回该实例，否则 null。**线性的，无 HashMap 优化**——20 项规模下无所谓。
- **`va.<clinit>`**（`smali/va.smali:16-262`）：硬编码 19 个单例对象（kotlin `listOf(...)` + `vl.h0` 合并 t30.e 空列表）：

| 类 | 实例数 | 包名（getPackageName 返回值，均已核对 smali） | 对应 app |
| --- | --- | --- | --- |
| x00 | 1 | com.dragon.read | 番茄小说 |
| rz1 | 1 | com.baidu.tieba | 百度贴吧 |
| me0 | 1 | com.phoenix.read | 飞读 |
| oe0 | 1 | com.kylin.read | 麒麟阅读 |
| tc | 5（字段 h/b/d/k/f） | 见下 | 见下 |
| yx0 | 1 | com.MobileTicket | 12306 |
| wk | 1 | app.jjyy.chuckle | — |
| or | 1 | com.coolapk.market | 酷安 |
| yd | 1 | com.baidu.netdisk | 百度网盘 |
| ah1 | 1 | com.kmxs.reader | 追书神器 |
| ri | 1 | com.cainiao.wireless | 菜鸟 |
| y4 | 1 | com.attempt.afusekt | — |
| jf0 | 1 | com.huawei.health | 华为健康 |
| v92 | 1 | com.yunyi.smartcamera | — |
| e22 | 1 | com.chan.cwallpaper | — |

- **tc = 5 个 app 共用一个类**（`smali/tc.smali`）：`tc.a:I` 整数判别，`getPackageName()` 按 id packed-switch（tc.smali:4975-5032）：
  - id 0 → com.autonavi.minimap（高德）
  - id 1 → com.clover.daysmatter（正点日历）
  - id 2 → com.duolingo（多邻国）
  - id 3 → com.handsgo.jiakao.android（驾考宝典）
  - id 4（默认）→ com.tencent.androidqqmail（QQ 邮箱）
- **注意**：scope.list 的 20 个包中 `com.mipay.wallet` **在全部 smali 中零引用**（`grep -rn "com.mipay.wallet"` 无命中）——声明了 scope 但没有适配器，属于无效注册（进程被注入但查表得 null 直接返回）。

### 1.3 适配器接口 oa（smali/oa.smali）

```smali
.class public interface abstract Loa;
.method public abstract a(HookEntry; PackageLoadedParam;)V   ← onPackageLoaded 入口（每 app 实现）
.method public b(HookEntry; PackageReadyParam;)V             ← 默认空实现（虚方法，可覆盖）
.method public abstract getPackageName()Ljava/lang/String;   ← 查表键
```

### 1.4 混淆特征与解读建议

- 入口 `onPackageLoaded`/`onPackageReady` 及其私有助手（m587-m596）全是 `while(true){ i^=0x661; sparse-switch }` 控制流混淆 + 垃圾调用（`Landroidx/startup/ۣۣۡۧ;->۟ۢۡۨۦ()I` 等）——**不要逐行啃**，用 smali 的 invoke 骨架 + const-string 跳读（上表证据全部来自此方法）。
- jadx 的 `C0110`/`C0050`/`C0150` 等名字是 jadx 对阿拉伯文类名的重命名，**与 HookEntry.java 里 import 的路径（androidx.versionedparcelable 等）只是混淆器把代码类物理塞进了这些"可信"包名**，类名冲突导致 jadx 张冠李戴（如 `C0110.m488` 实为 `obj.getClass()` 的垃圾方法）。**判断真实目标类必须回 smali 看 invoke 的目标 descriptor**。

---

## 2. DexKit 使用模式（目标是"按特征找被混淆的方法"，已完全还原）

### 2.1 桥是什么

`org.luckypray.dexkit.DexKitBridge`（`smali/org/luckypray/dexkit/DexKitBridge.smali`）——**就是原版 DexKit**：native 方法（`nativeInitDexKit(J)`、`nativeInitDexKitByClassLoader`、`nativeFindClass`、`nativeFindMethod`、`nativeBatchFindClassUsingStrings`…）由 Rain 模块自带的 `libdexkit.so`（apk lib/ 四架构）支撑。模块自身的 dex 里还混着 7 个 Rain 自己的混淆类被塞进了 `org/luckypray/dexkit/` 包（۟۠۠ۥۢ 等），分辨方法是看有没有字符串解密助手（`([SIII)Ljava/lang/String;`）。

### 2.2 Bridge 两种创建方式

1. **按目标 APK 路径（主路径）**：`System.loadLibrary("dexkit")` → `new DexKitBridge(targetApp.getApplicationInfo().sourceDir)`（jadx defpackage/v00.java:241-242；sourceDir 来源 x00.java:97）。即：**分析目标进程 APK 的 dex 直接从磁盘文件读**，不走内存。
2. **按 ClassLoader（进程内）**：`new DexKitBridge(classLoader, true)`（qc.smali:272、qv.smali:4276）。
   另有 qv.smali:1640-1720 用模块 context 的 `nativeLibraryDir + "/libdexkit.so"` 绝对路径 `System.load`（备选加载法）。用完 `bridge.close()` 释放（v00.java:258）。

### 2.3 查询构造 → 结果 → 过滤（完整流程，jadx v00.java 全可读）

构造查询（方法级，`v00.a`，v00.java:21-74）：

```java
g60 q = new g60();                      // 查询对象（find-method）
q.u = kc.v0(new String[]{"com.dragon.read"});   // searchPackages 白名单（硬编码，仅番茄用这套静态工具）
ww0 b = new ww0();                      // 方法查询条件构造器
ww0.P0(b, str, 6);                      // 方法名匹配（str = "isAnyVip"/"isVip"/"willShowLynxBanner"...）
ww0.W0(b, "boolean");                   // 返回类型匹配
b.Q0(0);                                // 参数个数匹配
// b.U0("com.dragon.read.rpc.model.VipCommonSubType")  // usingString 约束（v00.b，v00.java:84）
q.w = b;
vw0 hits = dexKitBridge.n(q);           // 执行查询（n = findMethod）
```

结果解析（v00.java:40-64）：每个命中 `uw0.e(classLoader)` 惰性解析出 `java.lang.reflect.Method`（失败包成 kj1/Result 变体，析出为 null 跳过）→ 反射过滤：`returnType==boolean && paramCount==0`（或 1 且 paramType==VipCommonSubType）`&& !interface && !abstract && !native && !static` → 按签名串去重（`v00.d(method)` = `declaringClass#name(p1,p2):ret`，v00.java:187-199）。

类级查询（`v00.c`，v00.java:125-185）：`e60` 查询对象 + `cl.P0(".VipInfoModel", 3, false)` 类名后缀匹配 → `bridge.m(e60)` → 过滤「非接口/非抽象、有构造器、≥3 个 boolean 字段」→ 取第一个。

### 2.4 双轨策略：直连反射优先，DexKit 兜底

番茄适配器 `x00.c`（jadx defpackage/x00.java:55-132）是最完整的案例：

1. **先直连已知类名**：`ck0.S(hookEntry, cl, "com.dragon.read.component.biz.impl.NsVipImpl", "isAnyVip", new String[0])`——findMethod 助手（smali ck0.smali:4920：loadClass + findMethod + 失败日志 "class not found: ..." tag "Hook-Helper"）。
2. **DexKit 兜底**：`v00.e(hookEntry, apkPath, cl, modelFallback, v00.b, v00.c, v00.d, t30.e)`（x00.java:99）——用同样的特征集（方法名/返回类型/参数个数/usingString）再跑一遍，应对"同一方法在不同版本被混淆改名/移位"。日志 `"resolving current APK targets with DexKit (modelFallback=" + z + ")"`。
3. **合并去重**：两批结果按签名串取并集。
4. **挂载**（详见 §3），最后打总数日志 `"installed N/M resolved hook(s)"`（x00.java:131）。

> 该双轨设计是 HonorHook 最值得抄的一点：**HookEntry 里不需要写死任何方法签名，只写"特征"，DexKit 查询结果本身是缓存无关的运行时反射方法**。

---

## 3. hook 挂载模式（libxposed 新版 API：hook → intercept）

### 3.1 统一挂载助手 ck0（smali/ck0.smali）

所有挂载都收敛到这几层包装，libxposed v102 调用形态在 smali 看得一清二楚：

```smali
invoke-virtual {p0, p1}, XposedInterfaceWrapper;->hook(Executable;)HookBuilder
invoke-interface {v0, v1}, HookBuilder;->intercept(Hooker;)HookHandle
```

- `ck0.R(api, classLoader, className)` → Class（loadClass + "class not found" 错误日志）
- `ck0.S(api, classLoader, className, methodName, String[] paramTypes)` → Executable（R + findMethod）
- `ck0.O(HookEntry, Executable, int, Boolean)` → boolean：装 **qa** Hooker（见下）
- `ck0.P(api, Executable, Object)` → boolean：装 **sa** Hooker（见下）
- `ck0.Q(HookEntry, Executable, va0 回调)` / `ck0.k(HookEntry, Class, va0 回调)`：带回调的挂载（k 用于"钩整个类"，如 VipInfoModel）
- 失败统一处理：`kj1` 包装 → `lj1.a()` 解包 → `log(5, "Hook-Helper", "failed to hook " + executable, throwable)`，**debug flag（s90.a）关掉时 throwable 置 null、不打日志也算弱化到 grep 不易见**。

### 3.2 qa——参数注入模式（ck0.O，smali/qa.smali）

`qa(模式索引 i, 固定值 v, AtomicBoolean 一次性, HookEntry, Executable)`：
- `0 <= i < args.length` → `args[i] = v` 后 `chain.proceed(args)`（改参放行）；
- 越界 → AtomicBoolean 只警告一次 `"arg N out of range on " + executable`（tag "Hook-Helper"）。

### 3.3 sa——固定返回 / 拦截 / 后置连锁（ck0.P，smali/sa.smali）

`sa(模式, 值)` 的 `intercept(Chain)` 按模式 packed-switch：

- **模式 0 = 无条件返回固定值**（`return-object p0`，即构造时传入的 Boolean.TRUE/FALSE）——**番茄的强 VIP 就是这么做的**：`ck0.P(hookEntry, isAnyVipMethod, Boolean.TRUE)` 让所有命中方法直接返回 true（x00.java:109-128）。
- **模式 1 = 放行后连锁反射调用**：`chain.proceed()` → `getThisObject()` → 按名字调用 `updateTabVisibility(false)`、`hideGuideBubble(AI_POWER_SEARCH)`（`i92.a(this, name, args)` 反射助手），每步失败独立兜底日志 `"[SearchPageCustomizeHook] updateTabVisibility(false) failed: "`——**贴吧的 AI 搜索页引导隐藏**。
- **模式 2+ = 首参匹配拦截**：`args[0].equals(值)` → 日志 `"[SearchPageCustomizeHook] search AI guide blocked"` → `return null`（拦下不 proceed）；否则放行（yn1.smali:1236/1362 两种 sa 实例化）。

### 3.4 i92——XposedHelpers 的 libxposed 替代品（smali/i92.smali）

`i92.a(obj, name, args[])`=按名反射调用（callMethod）；`b/c/d/e`=按名找类/字段/方法；`i/j/k/l`=分级日志。sa/qa 内部全靠它做动态行为。**这是"不 writeXposedHelpers 就没有 callMethod"时代的标准答案——自己维护一个 tiny reflect helper**。

### 3.5 每 app 挂载口径（以 x00 番茄为例，jadx x00.java:55-132 + smali x00.smali:279-411）

```
x00.a(HookEntry, param)                     [smali 279]
  ├─ 进程名白名单：Application.getProcessName() != "com.dragon.read"
  │    → log "skip non-main process <pid@name>" 后 return      [smali 283-334]
  └─ synchronized(this)（monitor-enter）× 一次性标志 x00.b       [smali 355-410]
       └─ x00.c(entry, param)  ← 真正装 hook 的地方
x00.c:
  ├─ 已知类直连：NsVipImpl.isAnyVip / PrivilegeManager.willShowLynxBanner /
  │    NsVipImpl.isVip(VipCommonSubType)                       [x00.java:65-89]
  ├─ VipInfoModel 已知类 + DexKit 兜底（modelFallback）         [x00.java:90-105]
  ├─ 合并去重（签名串）后按类型挂：
  │    ck0.P(method, TRUE)        ← checks（isAnyVip 族）固定 true
  │    ck0.P(method, true)        ← subtypes（isVip 族）固定 true
  │    ck0.P(method, FALSE)       ← banners（willShowLynxBanner 族）固定 false
  │    ck0.k(VipInfoModel, forceVipFields 回调) ← 类级：遍历 boolean 字段置 true
  └─ log "installed i/n resolved hook(s)"
```

---

## 4. 字符串解密体系（已在其机上离线还原验证）

### 4.1 真实算法：单轮 XOR，逐调用点独立 key

解密函数形态（在 **164 个类里重复复制**，遍布 android/androidx/support 及 org/luckypray/dexkit 包）：

```java
// androidx/profileinstaller/C0087.java:56-62（smali: Landroidx/profileinstaller/۟ۥ۠ۢ;->۟ۧۥ۟ۧ）
public static String m388(short[] sArr, int start, int len, int key) {
    char[] cArr = new char[len];
    for (int i = 0; i < len; i++) cArr[i] = (char) (sArr[start + i] ^ key);
    return new String(cArr);
}
```

调用形态（HookEntry 内 3 处，smali 126-230）：

```smali
sget-object v1, ...HookEntry;->f129short:[S   # 密文数组（40 个 short，HookEntry.java:40）
const/16 v2, 0x9          # start
const/16 v3, 0xb          # len
const/16 v4, 0x87d        # key（=2173）
invoke-static {v1,v2,v3,v4}, Landroidx/profileinstaller/۟ۥ۠ۢ;->۟ۧۥ۟ۧ([SIII)Ljava/lang/String;
```

密文数组由同类的 `m595()`（控制流混淆包裹，HookEntry.java:443-476）返回，也就是 `f129short`。

**已在本机用 Python 离线还原**（`(s ^ key) & 0xffff` 转 char）：

| 调用点（jadx 行号） | start/len/key | 明文 |
| --- | --- | --- |
| HookEntry.java:56（构造器反篡改抛出） | 0/9/2173 | `不要用低质小模块喵` |
| HookEntry.java:91（readDebugLoggingEnabled） | 9/11/862 | `rain_module`（模块远程偏好文件名） |
| HookEntry.java:91 | 20/20/1860 | `enable_debug_logging`（调试开关 key） |

> 三个串全部是有效明文字符串，且与代码语义自洽（偏好文件名 + 开关 key + 反篡改口号），**算法与 key 映射已证实**。

### 4.2 批量离线解密可行性：**可行**

全 dex 共 **201 处** `([SIII)Ljava/lang/String;` 解密调用点（`grep -rh` 计数）。解密只需三步：
1. 从 smali 正则提取每个调用点的 `sget-object`（数组引用）+ 3 个 const（start/len/key）；
2. 找到对应数组字面量（`.array-data 2`，如 HookEntry.f129short）；
3. XOR 还原。纯文本处理，不需要跑 ART。
本机验证脚本模式：`chr((s16[start+i] ^ key) & 0xffff)`，注意 jadx 打印的 short 是十进制 int 字面量、`short[]` 有符号语义（负数要 `ctypes.c_short` 重解释，`& 0xffff` 后 XOR 无影响）。

### 4.3 两个干扰项（别上当）

- **Scheme B 伪解密器**：`C0150.m764`（org/luckypray/dexkit/۟۠۠ۥۢ，jadx C0150.java:24-47）与 `C0050.m227` 形态是"hex 索引 + **Math.random() 异或**第二遍"——**非确定性的，必然输出乱码**，是混淆器埋的诱饵；调用处结果直接丢弃（`System.out.println(Long.decode(...))` 垃圾代码，HookEntry.java:73）。识别要点：解密器里出现 `Math.random()` 或随机数 = 诱饵。
- **junk 方法/垃圾调用**：`obj.getClass()`、`hashCode()`、整数常量异或后比较的壳方法遍地都是（C0110.m488 就是此类）；`C0060.m268() >= 0` 这类"恒真守卫"控制流混淆的语义就是恒真/恒假，按"真实代码在哪个分支执行"人工平铺一次即可。

### 4.4 附加发现：反篡改时间炸弹

`HookEntry` 构造器 `C0117.m521(new Date(), new Date(1816444807415L ^ 9463))`（HookEntry.java:43）日期校验失败即抛 `"不要用低质小模块喵"`——修改版/错版本秒崩，HonorHook 若想逆向魔改 Rain 系模块需先绕过此处（或直接重打包时删掉该校验）。

---

## 5. 给 HonorHook（荣耀系统 hook）的可借鉴清单

| # | 模式 | Rain 的做法 | HonorHook 建议 |
| --- | --- | --- | --- |
| 1 | **单入口 + 接口化适配器** | `oa` 接口（a/b/getPackageName）+ 静态注册表 va 按包名线性查 | 荣耀 hook 目标多为系统进程/系统应用，仍建议同结构：一个 registry 类 + 每目标一适配器实现接口，查表命中才执行 |
| 2 | **多 app 单类适配器** | tc 一个类 5 个实例（int 判别字段） | 同包族（如荣耀各 hy/ hwh 系）可合一个适配器类，减少类数量、方便共享公共 hook |
| 3 | **isFirstPackage 门控** | 非首进程（首进程=主进程的 hook 语境）直接 return | 系统进程还要叠加进程名校验：Rain 对"主进程"的定义是 `getProcessName() == 包名`，不等于即跳过并打日志，**避免多进程重复挂载** |
| 4 | **一次性挂载标志 + synchronized** | x00.a 内 monitor-enter + volatile boolean | 同一适配器可能被多次回调（进程重启后 LSPosed 重注入），必须防重入 |
| 5 | **先直连反射、再 DexKit 兜底** | 已知类名先 ck0.S/R；失败/模糊场景按特征（方法名+返回类型+参数个数+usingString）DexKit 查询 | 荣耀系统应用混淆少，但版本碎片多——特征查询兜底能扛"用户 ROM 版本换了方法位置" |
| 6 | **统一 hook 助手层** | ck0（R/S/O/P/Q/k）+ qa/sa 两种 Hooker（参数注入/固定返回/首参拦截/后置连锁）+ i92 反射助手 | 把"挂 hook 失败怎么办"收敛到一处：日志 tag、Result 包装、debug gate（s90.a）——**全模块统一错误处理**是 Rain 最工程化的点 |
| 7 | **固定返回值 = 模式 0 的 sa** | `hook(m).intercept(chain -> 固定值)` 实现强 VIP | 荣耀场景（如绕过横竖屏检查、状态栏图标过滤的 boolean 闸门）同上：hook 目标方法直接 `return 固定值` 最简单可控 |
| 8 | **后置连锁调用** | proceed 后按名调 `updateTabVisibility(false)` / `hideGuideBubble(...)`，每步独立 try-catch | 荣耀"隐藏引导气泡/拦截系统 Overlay 展示"类需求可直接照搬：主 hook 放行，副效果反射调用，失败只记日志不炸 |
| 9 | **签名串去重** | `declaringClass#name(params):ret` 作为并集 key | 直连结果 + DexKit 结果合并且以 clear count 打日志（installed N/M），可观测性好 |
| 10 | **字符串加密选型（反-反编译）** | short[] 单轮 XOR + 逐调用点 key + 解密器广撒 164 类 + Math.random 诱饵 | 若 HonorHook 要混淆，**别用公共 key、别用单个解密器类**；静态 trace 成本 ≈ 现成的 201 调用点批量脚本。若只是"防小白一眼看穿"，这个强度足够 |
| 11 | **scope 声明与实现一致性** | mipay.wallet 有 scope 无适配器（零引用） | 声明了 scope 就必须有适配器或显式忽略处理，否则进程白注入（浪费 + 用户困惑） |

---

## 附：主要证据索引

| 结论 | 证据 |
| --- | --- |
| 入口分发链 | smali/love/nairain/hook/xposed/HookEntry.smali:1108(onPackageLoaded),504-506(getPackageName),435-437(oa.a 调用),1234(onPackageReady)；jadx HookEntry.java:514-628,277-306(m591=isFirstPackage) |
| 注册表 va | smali/va.smali:16-262(clinit 19 单例),269-310(a(String) 线性查表)；smali/oa.smali(接口)；smali/C0164 对应 defpackage/C0164.java:60 |
| 适配器↔包名映射 | 各适配器 smali 的 getPackageName 常量（me0/oe0/yx0/wk/or/yd/ah1/ri/y4/jf0/v92/e22/rz1/x00/tc）；tc.smali:4975-5032 |
| DexKit 桥 | smali/org/luckypray/dexkit/DexKitBridge.smali:15-76(两构造器 + native 方法表)；qv.smali:1640-1720(libdexkit.so System.load)；qc.smali:272/655(两种创建)；jadx v00.java:241-242(loadLibrary+sourceDir) |
| DexKit 查询模式 | jadx defpackage/v00.java:21-123(a/b/c 查询与过滤),201-311(e 编排)；ww0.smali:27-262(P0/W0/Q0)；smali v00.smali:100-250(U0 等) |
| hook 挂载 API | smali/ck0.smali:4348-4380(O: hook→intercept),4530-4547(P),4805-4920(R 带 class not found 日志),4920+(S)；qa.smali:55-215；sa.smali:37-345(三模式) |
| 统一日志/错误处理 | ck0.smali 各方法尾部 log(5,"Hook-Helper","failed to hook "+...)；s90.a debug gate |
| 字符串解密 | jadx C0087.java:56-62(m388)，C0110 对应 smali androidx/versionedparcelable/ۦۢۦ；HookEntry.java:40(f129short),443(m595),56/91(调用点)；C0150.java:24-47(诱饵 m764)；解密验证：本机 Python 还原 3 串 |
| 反篡改 | HookEntry.java:42-86(构造器日期校验 m521) |
| scope 与实现不一致 | scope.list(rain-extract) 20 包 vs va 注册表 19 实例；grep com.mipay.wallet 零命中 |

> 未完成/存疑（如实标注）：a) `c5.o(...)` 事务包装（v00.e 外层）内部实现未深挖，仅确认其持"ka0 回调延迟执行 + 异常转 kj1 + debug 日志"语义；b) uc0/u00 组合容器字段（a=checks/b=subtypes/c=banners/d=VipInfoModel/e=extras）由 v00.e 构造确认，但其它适配器（qe0/p92 等）的 hoo k 细节未展开；c) `ck0.Q`/`va0` 回调式挂载语义只确认到"传 va0 回调 + 失败统一日志"层，未读全具体回调实现。以上不影响 §1-§4 结论，抽 1-2 个场景（如贴吧 yn1 的 AI 引导隐藏）已足够对齐模式。