# 京东反 hook / 反篡改检测面（16.2.0 静态实证）

> 2026-11-25。方法：自写 dex 反汇编器 `.buildtmp/jd162/dxtool.py`（字符串定位 → 调用关系 → 逐方法反汇编），
> 不看 jadx、不跑 HTX；全部结论附类名/方法名/常量串，未取证的地方明确标注。
> 背景：用户反馈「京东单独出现用户验证」，怀疑本模块 hook 被抓。

## 一句话结论

京东的反 hook 检测是**独立的 `JDMob.Security` 反篡改栈 + JMA 风控上报 + FireEye SDK + native `libAntiCheat.so`**，
且整条 Xposed 检测由**服务端 mobile config 驱动**（`lsposedCheck` / `hookkeys` / `hooknum` / `hiddenApiOpen`）。
16.2.0 已把 `JMA.needXposedDialog()` 编译成 `const/4 v0,0; return`（恒 false）——**本地弹窗被关掉，改由服务端风控处置**，
这与「不再弹 Xposed 提示、但出现验证」的观察一致。

本机实测没有证据表明我们的 hook 被抓：JD 进程 `/proc/<pid>/maps` 零命中 `lspd|lspatch|xposed|riru`，
无 `/sbin/.magisk`、无 `/data/misc/riru`，logcat 零条检测日志。京东多数探针针对的是 **Riru / Magisk 时代**的痕迹。

## 一、检测地图（dex8 为主）

| 类 | 角色 | 手法（实证常量串/调用） |
|---|---|---|
| `com.jd.stat.common.e0` | Xposed 检测主类（tag `JDMob.Security.XposedHookHelper`） | `d()` 先读 `f.Q().o0()`(lsposedCheck) 与 `g0()`(hiddenApiOpen) 两个开关；再 `hookdetect.e.a(BaseDexClassLoader)` 枚举类加载器 + `hookdetect.b.a()`；最后 `loadClass("de.robv.android.xposed.XposedHelpers")` 读 `methodCache`/`constructorCache`/`fieldCache`（经典 Xposed 缓存检测）。`a(Member)` 把命中项拼成 `类名小写#方法名`；`b()` 按 `hookkeys` 白名单过滤、`hooknum` 截断；`c()` 缓存结果 |
| `com.jd.stat.security.jma.feature.hookdetect.b` | `JDMob.Security.HookCheckUtil`，30+ 探针 | native 库名表 `libsubstrate-dvm.so`/`libriruloader.so`/`libsandhook`/`libmemtrack`/`frida`/`arthook_native`/`riru`/`libva`；`/system/bin/app_process.orig`；`DexPathList$Element` 反射（`pathList`/`dexElements`/`dexFile`）；`/proc/<pid>/maps` + `(deleted)`；magisk 挂载（`/proc/self/mounts`、`mountinfo`、`/sbin/.magisk/`、`/sbin/.core/**`）；栈检测 `handleHookedMethod`/`ZygoteInit`/`com.saurik.substrate.MS$2`；`Seccomp:`/`Seccomp_filters:`；`/system/lib/libart.so`+`xposed`；Xposed 模块枚举 `xposedminversion`/`xposedmodule`/`de.robv.android.xposed.install` |
| `hookdetect.d` | `JDMob.Security.RootCheckUtils` | `ro.debuggable`/`ro.secure`、`/su` 全路径表 + `which su` + `PATH` 分段、`magisk`/`libriruloader.so`/`/data/misc/riru`/`/twrp`、`/proc/self/attr/prev`==`zygote`、SELinux `getFileContext` + `u:object_r:magisk_file:s0` |
| `hookdetect.a` | root 管理 App 名单 | `universalandroot`/`roothelper`/`supersu`/`superuser`/`qihoo.permmgr`/`wmroot`… |
| `com.jd.stat.common.feature.alter.d` | `JDMob.Security.LSPatchUtil` | `<clinit>` 用 `com.jd.stat.common.utils.n.a(hex,"315")` 解密类名/路径常量；`a()` 比 classloader 差异 + `checkLSPatchClass`/`checkLSPatchRepackage`；`b()` 查 `getPackageCodePath()` 是否含 `/lspatch/`、扫 ZipFile entries |
| `com.jdjr.acr.HookJavaMethodUtils` | 金融侧**定向**方法级检测 | `IsNativeOrXposed(cls, method)` → native `com.wangyin.platform.ACMUtil.getAccessFlags` 读 ART 方法访问标志，判 native / `has been hooked by xposed` / `is backe of a methed that has been hooked by xposed` / `kAccIgnoreAotCode`；**只被 `AntiHookManager.getCameraMethodHookedList`/`getHookArrayList`/`getLocationHookArrayList` 调用** ⇒ 专保相机/定位（反相机注入、反模拟位置），不扫全量方法 |
| `com.jd.stat.security.jma.JMA` | 风控/埋点主类 | `needXposedDialog()` = `const/4 v0,0`；`reportFireEyeEvent`/`getSoftFingerprint`/`updateJDMobileConfig`… |
| `com.jd.stat.security.f` | mobile config 读取器（单例 `f.Q()`） | `o0()`=optInt(`lsposedCheck`,0)、`M()`=optString(`hookkeys`,"")、`N()`=optInt(`hooknum`,…)、`g0()`=optInt(`hiddenApiOpen`,0) |
| FireEye（`com.jd.fireeye.security.*`，未混淆） | 京东安全 SDK | `FireEye.hasActived/hasInit/hasReport/reportFireEye(Event)`；开屏链里 `rv.f.n().k()` 即它的投放入口 |
| native | `lib/arm64-v8a/libAntiCheat.so`(166K)、`libarthook.so`(6K)、`libDecryptorJni.so` 等 | 未见 Java 调用点（未取证），按存在性列为潜在上报方 |

其余散点：`com.jd.stat.common.EncryptUtil.checkFridaStatus`、`com.jd.stat.common.NativeInfo.checkMagiskViaSocket`(+native 版)、
`x8.f.e`（检 `libxposed.so`）、classes4 的 `frida-server` 路径表与 `/system/framework/XposedBridge.jar`。

## 二、上报链

`hookdetect.*`（采集）→ `e0.a()` / `LSPatchUtil.a()`（拼 JSON / 位串）→ JMA feature 采集器
（`com.jd.stat.security.jma.feature.k3.call`、`zb.a`）→ riskinfo 上行 → 服务端风控 → 需要时下发验证（`com.jd.aips.verify`）。

要点：**采集与判定分离**。Java 层能做的只是让采集结果变干净，是否弹验证由服务端决定。

## 三、配置驱动（关键，决定对抗是否值得做）

服务端可远程开关：

- `lsposedCheck`（int，默认 0）：`e0.d()` 的总闸；0 = 整条 Xposed 检测不跑
- `hookkeys`（string，默认 ""）：上报白名单，`类名#方法名` 逗号分隔 —— 为空即**什么都不报**
- `hooknum`（int）：上报条数上限
- `hiddenApiOpen`（int）：hidden API 相关探测开关

⇒ 只要服务端不下发 `lsposedCheck=1` + 非空 `hookkeys`，`e0` 这条链就**不产生任何上报**。
这也解释了为什么本地看不到检测日志：可能根本没开。

## 四、本机暴露面实测（2026-11-25，JD 16.2.0 / 模块 v1.3.8）

| 京东的探针 | 本机实际 | 是否命中 |
|---|---|---|
| `cat /proc/<pid>/maps` 找 `xposed`/`riru`/`lspd`/`lspatch` | 零命中 | ❌ |
| `/sbin/.magisk/`、`/sbin/.core/**`、`/data/misc/riru`、`libriruloader.so` | 均不存在（设备是 `/data/adb` 型 root） | ❌ |
| `de.robv.android.xposed.{XposedBridge,XposedHelpers}` 缓存检测 | 本模块走 libxposed API 102（`java_init.list`），不写 `XposedHelpers.methodCache` | ❌（结构性不命中） |
| Xposed 模块枚举（`xposedminversion`/`xposedmodule`） | 需读已装包 meta；未取证 | 待定 |
| `/system/bin/su` `/su` 等路径、`which su` | 未取证 | 待定 |
| 定向方法级（相机/定位） | 我们只挂 `JDHomeManager.a`，不在其名单 | ❌ |

结论：**静态与实测都不支持「我们的 hook 被抓」**。用户看到的验证更可能来自其它风控信号（设备指纹/账号/网络），
或服务端按 `lsposedCheck` 打开后对环境的综合判定。

## 五、对抗方案分档（按性价比，L0 先行）

**L0 定性对照实验（零成本，先做）**
LSPosed 里把 `com.jingdong.app.mall` 从 scope 移除（或整模块停用）→ 强杀 JD → 冷启 → 观察是否还弹验证。
- 验证消失 ⇒ 确实是环境/模块被判定，才值得进入 L1
- 验证照旧 ⇒ 与 hook 无关，不投入

**L1 关采集（`f.o0()` 返 false）**
hook `com.jd.stat.security.f.o0()` 返回 false ⇒ `e0.d()` 早退，`e0` 链不产结果。
代价：hook 点固定在混淆类 `f`（16.2.0 名，随版本变，需 `adapt_new_version.py` 校验 + 版本表）；
且「开关被改」本身属于可被反查的行为（JD 另有 `HookJavaMethodUtils` 能力，但仅用于相机/定位名单）。

**L2 改结果（`e0.d()` 返空 JSON / `hookdetect.b.*` 返干净）**
点位更多、更脆，收益不比 L1 大（L1 已让整链不跑）。

**L3 抑制上报（JMA feature 收口 `k3.call`/`zb.a`）**
收口少，但 JMA 是核心埋点，改动面大、易崩，不推荐。

**L4 环境层隐藏（root/LSPosed 痕迹）**
不可行：需 reboot/系统级改造，违反设备铁律，且 `libAntiCheat.so` 的服务端信号无法消除。

**风险提示（必须先跟用户说清）**
1. JD 是支付/风控敏感 app，hook 其风控组件可能反而抬高风控分（账号限制），不建议在主力账号验证。
2. `libAntiCheat.so` 的 native 上报面未取证，Java 层干净 ≠ 整体干净。
3. 任何 hook 都增加注入面；本模块对 JD 目前只有开屏 1 点，收益/风险比很差。

## 六、待取证

- 服务端是否给本机下发 `lsposedCheck=1`（本机 `/data/user/0/com.jingdong.app.mall` 在 su 环境不可列，未取到 config 文件）
- 验证弹窗的实际发起方（`com.jd.aips.verify` 的调用链与触发条件）
- `libAntiCheat.so` 的 JNI 入口与上报字段
