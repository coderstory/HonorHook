# 京东 `com.jingdong.app.mall` — 屏蔽开屏广告逆向

> 基线 **16.0.70（versionCode 102834）**（2026-10-12 真机 `dumpsys`/`pm path` 实证，设备 AAK-AN00）。
> 反编译产物：`system-files/JD-16.0.70-jadx/`（jadx `-j 4 -r --no-res --show-bad-code`，35276 类，exit 1 + 77 errors 属正常）。
> 状态：**调研期**——只有逆向实证，无任何 hook 代码。

## 一、结论（TL;DR）

- JD 开屏 = 自研广告栈（**无穿山甲/优量汇/百度 SDK**，dex 字符串实证），非第三方通杀可覆盖。
- 开屏页 = `SplashFragment`（**类名未混淆**），由 `MainFrameActivity`（launcher）onCreate 经 `JDHomeManager.a` → `l.b` 添加。
- **全源码 `new SplashFragment` 唯一**（`l.java:66`）；`JDHomeManager.a` 唯一调用方 `MainFrameActivity.java:995`（grep 实证）。
- 挂载点主选：hook `JDHomeManager.a(BaseActivity, int)` 跳过 = SplashFragment 永不 add，全局状态等同「服务端没下发广告」的原生路径。

## 二、链路实证（jadx，file:line）

```
launchable-activity: com.jingdong.app.mall.MainFrameActivity   (aapt2 badging)
  └ MainFrameActivity.java:995   JDHomeManager.a(this, p80.e.f87307p0)   // 埋点锚 "addSplashFragment"
      └ JDHomeManager.java:50    public static void a(BaseActivity, int)
          └ FireEye.hasActived() → home/common/utils/l.java:63  b(BaseActivity, int)
              ├ com.jingdong.app.mall.home.x.j() == true → return        // 另一关闭闸（语义未查）
              └ l.java:65  rv.f.n().k() != null                            // ★ 广告数据对象 = 唯一闸门
                  └ l.java:66  add(containerId, new SplashFragment(), XView2Constants.SPLASHFRAGMENT)
```

`SplashFragment`（162 行，全可读）内部：

- `onCreateViews`（:112）→ `V0()`（:27）**二次判同一数据源** `rv.f.n().k()`：null 或 `g.f89712n`（adImageObject）空 ⇒ 走 `Y0(false)` 完整原生无广告路径（fragment 自移除，返回 null 视图）。
- 有广告 ⇒ `BaseSplashView.C(thisActivity, adObj, 0)`（:42）创建渲染视图（ImageSplashView/VideoSplashView/ThreeDSplashView），`f0()` 启动，`onFinish` 回调（:49）→ `Y0` → `b1()` 自移除。

## 三、挂载点候选（按稳序）

| 点 | 锚点性质 | 消费点 | 副作用 / 风险 |
|---|---|---|---|
| **主选** `JDHomeManager` 内 `static void a(BaseActivity, int)` hookBefore 直接 return | 类名未混淆；**方法名 `a` 混淆** ⇒ 运行时遍历 declaredMethods 按「static + void + (BaseActivity, int)」唯一签名匹配，不依赖名字 | 1（MainFrameActivity:995，launcher 每次冷启动必达） | 状态 = 原生「无广告」路径（少一次 `rv.b.c("0")` 埋点，无功能面差异）；失败安全：签名不匹配 → NOT_RESOLVED → 开屏照常 |
| 备选 `BaseSplashView.C(BaseActivity, g, int)`（:230 static factory）返 null | 类名未混淆；方法名混淆 + 第 2 参为混淆类型 `rv.g` ⇒ 按返回类型/参数个数匹配；**须限定第 3 参 == 0**（ADActivity.java:46 同一 factory 传 1，是独立广告面，勿误伤） | 2（SplashFragment:42 / ADActivity:46） | 返 null 后 SplashFragment 走 `V0()==false` **完整原生无广告路径**（Y0(false) 全套副作用），状态最干净；混淆类型匹配跨版本脆 |
| 拒绝：`rv.f.n().k()` 返 null | 数据源收口，但 `rv.f/rv.g` 全混淆，消费点未数清 | 未数 | 波及面不可控 |
| 拒绝：渲染层 `BaseSplashView.onAttachedToWindow` 清 view（Honor adsdk 通杀式） | 类未混淆 | — | 倒计时/finInish 回调在 view 内部，清视图可能 onFinish 永不触发 ⇒ splash 卡死，比广告更糟 |

## 四、边界（诚实结论）

- **广告数据请求照发**：链路只砍「SplashFragment 渲染」，`rv.f` 拉取/上报不动 —— 隐私与流量无改善，只省展示与等待。
- 「启动图联动逛」入口随 splash 一起消失（挂在 SplashFragment 生命周期内）。
- `ADActivity`（`com.jingdong.app.mall.ad.ADActivity`，`rv/j.java:233` 与 SplashFragment 并列的另一广告面）**非开屏，本期不动**。
- 未混淆类名（MainFrameActivity/SplashFragment/JDHomeManager/BaseSplashView/BaseActivity）跨版本存活概率高；方法签名匹配失效时优雅退化为「广告照常」。

## 五、待验证（落地后）

- 真机冷启动 JD：无 splash 直达首页；`installed N/M` 判据含新目标。
- 热启动/从后台回来不误伤（hook 点只在 onCreate 链，理论无热路径）。
- 回归：正常浏览/下单路径无异常（hook 只跳过 add fragment，不碰任何业务方法）。

## 六、落地接线（新目标接入，依 `CLAUDE.md` §3.2）

`TargetRegistry` 新建 target（抄最像的现有实现）→ `scope.list` 加 `com.jingdong.app.mall` → `FeatureCatalog` 加开关条目 → 远端 prefs 默认开。挂载点实现放 target 内，混淆方法用「遍历 declaredMethods 按签名匹配」模式（项目已有 DexKit 兜底纪律：签名匹配 = 弱锚点，失败必须 NOT_RESOLVED 显式记日志，不许静默）。
