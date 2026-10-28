# sogou-ime（搜狗输入法荣耀版）逆向档案

宿主：`com.sohu.inputmethod.sogou.honor` **10.32.44.202608251503**（versionCode 3001，aapt2 实证；
整包拉取到 `system-files/sogou-ime/base.apk`，129MB / 5 dex / 42592 类）。
MT MCP 工作区 `3npsn0lh`（2026-10-27，本机回环 127.0.0.1:8787；**本文档曾于 2026-10-27 晚被外部清除后重建**，
内容以本版为准）。已有 hook 面：ImeHook 6 点（验证码候选 5 点 + T5 开屏总闸，见 `ImeHook.kt` 头注）。

## 侦查工具口径

- MT MCP（`tools/dev.sh mt-mcp`）只回 **smali + 类轮廓 + xref**，无 Java 反编译工具（57 工具逐一核对过）；
  Java 阅读走本机 jadx **单类模式**：`tools/dev.sh exec /data/data/com.dsharnessmobile.shell/files/usr/opt/jadx/bin/jadx
  --single-class <fqn> -d <out> <apk>`（tools/bin 垫片在本机无执行位，用 env 解析出的绝对路径）。
- dex 命令行扫描坑：`grep -E` 下 `\|` 是字面量，多关键词交替必须写 `a|b|c`（2026-10-27 实测踩坑，首轮全空是它）。

## 一、开屏广告链（已落地，T5，2026-10-27 真机 `installed 6/6`）

**入口**：launchable-activity = `com.sohu.inputmethod.sogou.SogouIMELauncher` → 开屏宿主 =
`com.sohu.inputmethod.splashscreen.SogouLauncherActivity`（super BaseActivity）。

**决策点（唯一）**：`SogouLauncherActivity.onCreate()` 中

```smali
invoke-static {p0}, Lpx5;->f(Landroid/content/Context;)Lpx5;
invoke-virtual {v0, p0}, Lpx5;->b(Landroid/content/Context;)Z   # canShowAds
move-result-object v0
if-nez v0, :cond_41    # false ⇒ t.sendEmptyMessage(1) 直达主界面（不建 SplashScreen、不请求广告）
```

**canShowAds = `px5.b(Context)Z` 的 8 道闸**（smali 全读）：① `SettingManager.W7()Z`（联网同意门，
本 hook **不碰**）；② 折叠屏；③ `xf6.h()`；④ 荣耀未成年人模式 `isHonorMinorsMode()`；⑤ `bz3.h()`；
⑥ `MainImeServiceDel.isInSelf()`；⑦ `CommonUtil.k()`；⑧ 30s 频控（`SplashScreen.y`）。

**广告请求体**：`SplashScreen.n()`——AdSlot slotId `1900110810602143744` / app `109999783197`
→ `com.hihonor.adsdk.splash.SplashAdLoad$Builder` → `loadAd()`；2s 超时 Handler msg 2。
**全 app 对 `SplashAdLoad$Builder` 的构造仅此一处**（xref 实证）。

**挂载点**：`px5.b(Landroid/content/Context;)Z` 读数强制 false（CONST 短路，纯读判定无副作用）。

**挂载实录（首版 5/6 → 修复 6/6 的完整踩坑）**：
1. 首版 DexHint 带 `paramTypes=["Landroid/content/Context;"]` → **直连轨被毒哑**：
   `MethodResolver` 参数比对走 `canonicalTypeName`（只归一包装类型，**不剥 L…; 描述符**），
   描述符形式 ≠ 反射名 ⇒ b 被静默排除（0 命中**无任何日志**——直连轨只在异常时打点）→ 转 DexKit。
2. DexKit 宽松轨 `loose()` **不消费 paramTypes**（只收 usingStrings/classStrings/classFieldTypes）；
   L3 类级查询把 px5 的 `<clinit>` / `<init>(Context)` 一并带回，`hit.getMethodInstance` 对构造器抛
   `IllegalArgumentException("not a method")` ⇒ 候选集永不完整 ⇒ 重试 2 轮放弃（installed 5/6）。
3. 修法 = DexHint 去掉 paramTypes（当前版本直连「名 b + 单参 + 返 Z」在 px5 内唯一）→ 6/6 全绿
   （22:58 与 23:05 两轮真机一致）。
4. **遗留基建项**：`DexKitHelper.findMethods` 应对 `<init>`/`<clinit>` 命中做确定性排除（它们永远不是
   `java.lang.reflect.Method`，不该计入 unresolved）——否则任何用 classStrings 锚的挂载点在类名漂移后
   都会被 L3 构造器命中卡死。单独清单，勿顺手改。

**锚点口径**：b() 方法体零字符串，锚只能在类级——px5 的 beacon 面（e/g/h）独占
`press`/`network`/`confirm`/`cancel` 四串组合。类名漂移后的宽松轨适配**依赖上面的基建项先落地**。

 