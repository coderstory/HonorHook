# 荣耀浏览器 com.honor.browser 逆向记录

## 一、现状（2026-11-26，宿主 3.10.1.303）

- `HonorAdsHook("com.honor.browser")` 仍只挂 adsdk 渲染层 2 点
  （`com.hihonor.adsdk.base.widget.base.BaseAdView#notifyAllChildView` / `#onAttachedToWindow`）：
  真机 `installed 2/2`，但模块日志 `ads-hook` 判据行 **0 条** ⇒ 该版本广告不再经 BaseAdView 渲染。
- 用户报告广告位：**启动开屏 / 首页信息流广告卡 / 搜索结果页广告**。
- 全包 59 dex 共 133710 类；`com.hihonor.adsdk` 1985 类（唯一广告 SDK，无 GDT/穿山甲/快手类）；
  `com.honor.browser` 10854 类。

## 二、链路（3.10.1.303 静态实证）

浏览器自建 provider 层，再下探到 adsdk：

```
BrowserMainActivity 冷启动
 └ fv20（开屏调度，混淆）
    ├ l(cold)：把 splashView 垫进当前窗口（vv20.a.a(frameLayout)），不走 Activity
    ├ requestSplash（hot）
    └ m()/o()：current activity.startActivity(SplashAdvertisementActivity, NO_ANIMATION)
 └ rq0（AdManager，tag "AdManager"，源文件 com/honor/browser/advertisement/AdManager.kt:116）
    └ HnAds.get().init(application, HnAdConfig.Builder().setAppId(..).setAppKey(..).build())  ← 全包唯一 init 点
    └ h()：getAdManager().setAppActivateStrategy(..) / registerNotifyListener(..)
 └ ra1（源文件 com/honor/browser/advertisement/component/service/AdvertisementServiceImpl.kt，
        IAdvertisementService 唯一实现，混淆类）
    ├ initSdk(Application)：协程并发 r()/s() + rq0.c.g().i(application)
    ├ initAdEnvAndAppId(env, appId) → rq0.j(env, appId)
    ├ createSplashProvider(String) → qv0.h(scene)
    ├ createOperationSplashProvider(String) → BaseAdConfigData(adKey=slotId, adStatus=FALSE, ...) → qv0.h(n(slotId,cfg))
    └ qv0.h(scene) = scene.getConfigValues().getAdKey()=="ad_splash_operation" ? new p9t(scene) : new iv20(scene)
         ├ p9t：运营开屏（IOperationConfigService.loadOperationSplashRes → cards[0].bcImage + cardLink → bx20），**不走 adsdk**
         └ iv20：SplashAdProvider → com.hihonor.adsdk.splash.SplashAdLoad → base.AdLoadWrap.loadAd（SDK）
```

`SplashAdvertisementActivity`（应用自有类名未混淆，extends BaseBrowserActivity）：
onCreate 里 `setTheme(Translucent/SwipeBack)` → `FeedAdRootFrameLayout` 作 contentView →
未满足 `Z3()` 时垫 `ic_splash` 背景 → `ISplashAdProvider.obtainSplash$default(kx20.a, null, d(...))`；
拿到广告后展示，超时/失败走 `S3()` → `release()` + `finish()`。

`HnAds`（`com.hihonor.adsdk.base.HnAds`，@Keep）：`init(Context,HnAdConfig)` 写 `mContext/mAdConfig`；
`getAdManager()` 恒 `new com.hihonor.adsdk.base.init.a()`（不依赖 init）；`isInitialized()` = 两者非 null。

## 三、已证伪的收口点（**勿重走**，2026-11-26 真机实证）

1. **拦 `HnAds.init`（paramCount=2, void）→ 崩溃循环。**
   宿主 init 被跳过后照样去 `iv20.obtainSplashToCache` → `SplashAdLoad.loadAd` → `AdLoadWrap.loadAd`
   → `com.hihonor.adsdk.base.o.m.<init>` 直接 `HnAds.mContext.getApplicationContext()` ⇒
   `FATAL EXCEPTION: DefaultDispatcher-worker`，`java.lang.NullPointerException: Attempt to invoke virtual
   method 'android.content.Context android.content.Context.getApplicationContext()' on a null object reference`
   每 2~4 秒重启一次（pid 6018 → 6781 → 7365 → …）。**SDK 不 fail-safe，"isInitialized 恒 false" 只是把广告变成崩溃。**
2. **`SplashAdvertisementActivity.onCreate` 放行后立即 `finish()` → 冷启动被打断。**
   Activity 参与启动流程（finish 后浏览器退回桌面，进程仍活着但无前台 Activity、随后被 AppFastHibernation 冻结），
   不是可以随手关掉的纯广告覆盖层。

## 四、下一步候选（未验证）

- 浏览器侧广告 **View 容器**（`com.honor.browser.advertisement.widget.*`：FeedAdRootFrameLayout /
  DownLoadAdRootConstraintLayout / NovelAdRootConstraintLayout / VideoAdRootConstraintLayout、
  view/widget/AdPlayerRadiusContainer 等，类名均未混淆）做**渲染层**收口，而不是动 SDK 初始化或 Activity 生命周期。
  需要先用只读观察（UI dump / 观察钩子）确认哪类容器承载开屏/信息流/搜索广告，再决定"清子 View"还是"整卡 GONE"（后者可能留列表空位）。
- 注意 `FeedAdRootFrameLayout` 同时是开屏的 contentView，且开屏里它自己带 `ic_splash` 垫底子 View，
  直接 `removeAllViews()` 会把品牌底图一起去掉 ⇒ 若要清，需按子 View 类型过滤（adsdk view / `AdPlayerRadiusContainer`）。
