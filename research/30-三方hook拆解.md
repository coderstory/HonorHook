# 三方 hook 预备：小米健康 + WPS 拆解（2026-10-24）

样本：system-files/mi-health.apk、wps.apk。

## 小米运动健康 com.mi.health（v3.59.1，143MB/13 dex）
**无加固、业务类零混淆**（com.xiaomi.fitness.*/com.fitness.banner.* 全保留；仅 onetrack SDK 内部混淆）→ 不需要版本表不需要 DexHint。
- 开屏链：login/SplashActivity → SplashViewModel → login/ad/{SplashAdActivity,SplashAdImpl,SplashAdPreference,SplashAdRequest,SplashAdService} → export/SplashAdManager → banner.export.v2.SplashAdItem
- 首页运营位：com.fitness.banner.export.{IBanner,**BannerImpl**,BannerItem,BusinessKey,GroupKey}（独立运营位 SDK，BannerImpl 唯一实现收口极佳）
- 埋点：com.xiaomi.onetrack.*（244 类，track 门面未混淆可短路）
- **无三方广告 SDK**（穿山甲/GDT 零命中，自研服务端运营位——比荣耀更稳无 OTA 漂移）
- 选型：开屏 P2 hook SplashAdManager 取 item→null（兜底 P4 finish）；运营位 P2 BannerImpl→空表；埋点 P2 track 短路
- **工作量 1.5-2 人日**；HonorAdsHook/HonorOpsAdHook 骨架 100% 可抄只换类名表

## WPS cn.wps.moffice_eng（v26.10.1，167MB/22 dex）
**无加固**，但 **RePlugin 动态插件**（广告 UI 部分随 ad_plugin 运行时下载，base 只内置 vasPaperProject.jar）。cn.wps.moffice.*/com.pixiu.* 未混淆。
- 广告 SDK=金山自研聚合（com.pixiu.* MoPub 风格：nativeads/mobileads/ecpm/bridge）；穿山甲/GDT 不在 base（随插件下发）
- 开屏：main/push/splash/SplashAdView、nativemobile/MoPubPhoneSplashAdImpl、extlibs/ISplashAd(+Listener)
- 信息流/弹窗：common/infoflow/{InfoFlowDialog,ListView,Command,Adapter}、main/ad/{InterstitialAdManager,NativeAdPreCacheManager,**FakeClickInjector**(防误点+假点击上报),AdShowRecorder,AdActionInvoker+15 Jump*}
- 会员促销：membership/MemberShipWebView（H5 云控——只拦自动弹出不拦手动入口防白屏）
- **首选 P2：hook AdPluginManager/AdPluginCheckUtil 使 ad_plugin 永不加载**（插件不装形态全消失；注意吞插件框架重试异常）
- 兜底：ISplashAdListener 短路+InfoFlowDialog 构造短路+PreCache 空；FakeClickInjector 短路=隐私加分
- **工程缺口：RePlugin 插件 ClassLoader 通道**——插件类不在 base loader，需挂 RePlugin loader 创建钩子；我方 MountEngine 现假设 defaultClassLoader **需小扩展**
- 工作量 3-4 人日（插件拦截 1d+兜底 1d+loader 通道扩展 1-2d）

## 优先级
mi.health 先行（最便宜最稳）；WPS 次之（AdPluginManager 单点收益最大）。基线 3.59.1 / 26.10.1。
