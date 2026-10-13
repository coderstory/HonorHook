# 网易邮箱大师 `com.netease.mail` — 本地会员 + 去广告 可行性调研

> 基线 **7.27.1（versionCode 466）**（apktool.yml 实证，APK 为用户提供的安装包，真机版本未核对）。
> 状态：**T1 开发中（2026-10-13 用户拍板）**——路线 = §五路径 B（免脱壳盲试）+ 范围 = 只做去广告（会员缓议）。
> T1 交付 = 框架 onCreate 门控跳开屏 + TopOn 断水（开屏/信息流）+ 运行时侦察探针（见 §五·2）。
> 产物：`system-files/mailmaster-jadx/`（主 dex，仅 33 壳类）、`system-files/mailmaster-apktool/`（资源+manifest）、
> `system-files/pangle-asset-jadx/`（穿山甲动态分包 dex，2768 java，**完全可读**）。

## 一、结论（TL;DR）

- **能做，方向可行**：app 自带「会员免此广告」原生路径（资源文案 `mail_ad_excluded` / `launch_ad_cloud_privilege_ads_free`）——
  让客户端认为用户是会员 ⇒ 广告自动消失，状态最干净（同 JD 案例哲学：走 app 原生无广告路径，不硬砍渲染层）。
- **拦路石：网易易盾加固 7.6.3_904**（DEX 加密壳）。业务 dex 46.9MB 但 class_def 被抽空（jadx 仅 33 个壳类），
  字符串/类型池**明文保留**（32 万行 strings）⇒ 类名全知道、方法体读不到。精确挂点（尤其 VIP 判定方法名/签名）需脱壳。
- **去广告**把握大：三家广告 SDK（TopOn 聚合 + 穿山甲 + 优量汇）的 API 类名明文，SDK 侧穿山甲分包完全可读，
  hook 广告加载失败路径 = 原生无广告。**本地会员**：客户端 gate 的权益（去广告/图标/皮肤/备注/聚合规则/星标数/日期定位等）
  可骗；**服务端算的权益**（AI 接口、云容量）骗不过——具体哪些权益是服务端 gate，脱壳读码后才能逐一划分。

## 二、壳结构（静态分析边界实证）

| 项 | 值 | 证据 |
|---|---|---|
| 壳 | 网易易盾 NIS wrapper，VER `7.6.3_904` | `MyApplication.java:12`（mailmaster-jadx） |
| 加载流程 | attachBaseContext → `MyJni.load(this, pkg)` → libnesec.so 解密 → `loadClass(strAppName)` 实例化真实 Application → `MyJni.ra/run/ip` 替换生命周期 | `MyApplication.java:324-384, 630-649` |
| 主 dex | 46.9MB，**仅 33 类**（`com.netease.nis.wrapper.*` 壳 + `R.java` + 字符串解码器 `a.auu.a`） | `find mailmaster-jadx -type f \| wc -l` = 33 |
| 加密数据位置 | dex header file_size = 实际文件大小（无附加区）⇒ class_def/code 在 dex 结构内部加密 | python struct 解析 header |
| 字符串池 | **明文**：32 万行 strings，含全部业务类名/方法名/埋点 id/preference key | `/tmp/mm-strings.txt`（dex strings） |
| 动态分包 | `assets/1579934425` = zip：明文 dex 5.9MB（穿山甲 SDK）+ mini manifest + resources.arsc，运行时插件式加载 | 解包实证 + pangle-asset-jadx 2768 java |
| 热修 | tinker（`TINKER_ID=tinker_id_466`），业务 dex 可能被 patch | manifest meta |
| native | `libnesec.so` / `libnesec-x86.so`（解密引擎，2026-09-24 与 dex 同时间戳）；`libnesdk/libnedat` 网易网络库 | unzip -l |

**hook 前提不受壳影响**：运行时类已解密加载，`loadClass` 直接可用；壳只挡「离线读码」，不挡运行时 hook。
但易盾企业壳可能含反调试/反 hook 检测，真机验证时留意（荣耀机 LSPosed 环境已常驻，风险待实测）。

## 三、广告架构（字符串池 + manifest + 资源实证）

| 项 | 值 |
|---|---|
| 聚合层 | **TopOn/AnyThink**（`com.anythink.*` 引用 3000+，splashad/banner/nativead/rewardvideo 全套） |
| 联盟 | 穿山甲 Pangle（`com.bytedance.sdk.openadsdk`，动态分包加载）+ 优量汇 GDT（`com.qq.e.ads.*`） |
| 广告模块 | `com.netease.mail.ad.*`（网易内部分模块化，`MODULE_APPLICATION` meta；**无独立 :ad 进程**，组件在主进程） |
| 开屏链 | launcher alias `DefaultSplashActivity` → `com.netease.mail.biz.main.SplashActivity`（判定）→ `com.netease.mail.ad.launch.ui.SplashAdActivity`（展示，manifest 声明） |
| 业务侧 SDK API 引用 | `ATSplashAdListener` / `com.qq.e.ads.splash.SplashAD(Listener)` / `CSJSplashAd$SplashAdListener`（字符串池，证明业务链引用三家） |
| 开屏本地配置 key | `store_key_launch_ad_click_area_enable` / `launch_ad_stock` / `launch_ad_prefetch` / `launch_ad_trigger` / `launch_ads_record` |
| 信息流 | `com.netease.mail.ad.infoflow.*`（含 `FMailManager`）、`PopupMaillistAdsExtendBinding`、埋点 `list_infoflow_hide_ad_click` |
| 云控 | `store_key_mmad_conf_*`（MM AD 云控前缀）、`store_key_mine_cloud_ads_free`（会员免广告开关）、`store_key_remove_webmail_ad_tail` |
| 会员闸门文案 | `launch_ad_cloud_privilege_ads_free`「会员免此广告」/ `mail_ad_excluded` / `mail_ad_open_excluded`（strings.xml） |
| 澄清 | `VipSplashActivity`/`VipOrderSplashActivity`/`VipLuck2026SplashActivity` 是 **launcher 图标换装 alias**（enabled=false，同指 SplashActivity），非广告页 |

## 四、VIP 架构（字符串池实证）

| 项 | 值 |
|---|---|
| 门面接口 | `com.netease.mail.biz.core.api.dashi.DashiVipService`（Kotlin，ARouter 服务组 `dashivip`） |
| 实现 | `com.netease.mobimail.api.DashiVipServiceImpl` —— 内部类暴露方法名：`showNoteVipGuide`/`showAppIconVipGuide`/`showCustomSkinVipGuide`/`showMailDateLocateVipGuide`/`showAttachLocateVipGuide`/`showSenderLocateVipGuide`/`showSearchDataFilterVipGuide`/`showSenderAggregationVipGuide`/`showPDFShareVipGuide`/`showTodoAttachVipGuide`/`showRewardVipGuide`/`showReadAloudGuide` + `getDashiVipPayUrl(...)` |
| 判定日志 | `crossBorderGuide: onMailLoaded isDashiVip=`、`checkDashiVipSkin: remove vip theme`（运行时判定散布在多个消费点） |
| 服务端 | `/dashi/vipInfo`、`/dashivip/api`；登录链 `login: query vip phone num`（**VIP 状态服务端下发**，大师账号体系） |
| 权益面 | 去广告、会员图标、自定义皮肤、聚合规则解锁、无限备注、星标联系人 500、按日期定位、待办附件、超大容量、AI 权益、PDF 分享、发件人定位、朗读、企业版 QiyeVipActivity 等（strings.xml 文案） |
| 关联类 | `VipTrackStatisticsHelper`、`biz/dashi/vip/a`（混淆辅助）、`MasterVipActivity`（会员页） |

若所有消费点都经 `DashiVipService` 门面 ⇒ 单点 hook 实现类 = 全局生效；若散点直读缓存 ⇒ 需读码数清消费方（同 B 类消费方普查纪律）。

## 五、推进路径（2026-10-13 已拍板：B + 范围收窄）

| 路径 | 内容 | 代价 | 风险 |
|---|---|---|---|
| A. 真机脱壳 | 设备跑一次邮箱大师 + dump 解密后 dex → 完整静态分析 | 动主力机 | 易盾反调试检测；dump 只读无破坏 |
| **B. 免脱壳盲试（已选）** | 运行时 hook 明文已知类 + 反射枚举 + 日志试错 | 零额外设备操作 | 方法语义靠侦察日志确认 |

去广告 T1 已落码（2026-10-13）；**VIP 后来重启（用户同日拍板「侦察+彩票一起上」）**，见 §五·3。

### 五·2、T1 挂载点设计（MailMasterHook，4 task + 侦察）

| task | 点 | 层 | 失败安全 |
|---|---|---|---|
| A `mm:splash.finish` | `android.app.Activity.onCreate`（framework 收口，MfaAuthHook 同款）门控 `ad.launch.ui.SplashAdActivity` → finish | 兜底：无论广告数据来自联盟还是运营，广告页闪退即过 | 类名不命中原样 proceed |
| B `mm:splash.toponOff` | `com.anythink.splashad.api.ATSplashAd.loadAd`（TopOn 公开 API）hookBefore 短路 | 联盟开屏断水（请求不发，隐私+流量收益） | 方法名从字符串池+TopOn 公开 API 推断，解析不到 = NOT_RESOLVED 静默 |
| C `mm:infoflow.toponOff` | `com.anythink.nativead.api.ATNative.loadAd` 短路 | 信息流联盟断水（`MasterNativeAdapter` 的存在证明信息流走 TopOn native） | 同上 |
| D `mm:infoflow.makeRequest` | 同 ATNative，方法名备胎（TopOn 加载入口 loadAd/makeRequest 二选一） | 同上 | 同上 |

**侦察探针**（T1 核心弹药）：task A intercept 内命中宿主 `biz.main.SplashActivity`（launcher 真身、冷启动必经、
壳此时必已解密）时，一次性枚举 10 个类（MasterAdModel/SplashAdActivity/LaunchAdInfo/FMailManager/
InfoFlowAdsConfigProvider/MasterSplashAdapter/MasterNativeAdapter/DisplayAds/ATNative/ATSplashAd）的
declaredMethods+Constructors 全签名打 `[mm-scout]` 日志 ⇒ **T2 据此把信息流挂载点上收精确化**
（运营位 `PopupMaillistAdsExtend`/`DisplayAds` 自渲染链不走 TopOn，B/C/D 杀不到，靠侦察后 T2 处理）。

**时序注**：B/C/D 是加密 dex 类，PackageLoaded 时机壳未解密 ⇒ 首轮 NOT_RESOLVED 靠 MountEngine 重试轮
（500ms/2s×2）；易盾解密通常 <1s，赶得上是常态、漏挂由 activity 兜底层补位，installed N/M 判读时属预期。

### 五·3、T-vip 挂载点设计（2026-10-13 用户拍板「侦察+彩票一起上」）

| task | 点 | 类型 | 依据 / 失败安全 |
|---|---|---|---|
| V1 `mm:vip.isDashiVip` | `DashiVipServiceImpl.isDashiVip()`(0 参 boolean)恒真 | 改返回值 | 方法名依据=业务日志串 `crossBorderGuide: onMailLoaded isDashiVip=`；挂不上 NOT_RESOLVED 静默 |
| V2 `mm:vip.isVip` | `DashiVipServiceImpl.isVip()` 恒真 | 同上 | isVip 为字符串池裸 token |
| V3 `mm:vip.isPlusVip` | `DashiVipServiceImpl.isPlusVip()` 恒真 | 同上 | isPlusVip/hasPlusVip 为字符串池裸 token |

- **独立开关** `mailmaster.vip`（与 `mailmaster.ad_off` 分离，UI 可单独关）；双开关结构：都关才整体跳过，
  taskA 恒挂、finish 行为受 adOn 运行时门控、侦察无条件执行。
- **侦察扩 16 类**：+DashiVipService（接口契约最重要）/ DashiVipServiceImpl / biz.dashi.vip.a /
  VipTrackStatisticsHelper / SearchDateFilterVipManager（日期定位消费方样例）/ TranslateVipHelper。
- **风险边界**（hook-craft §二·3 两类失败模式）：伪造会员只解锁客户端 gate 权益；服务端对账权益
  （AI 次数/云容量）无效，个别走服务端数据的路径可能异常（exam8 白屏先例）⇒ 远端开关独立可关兜底。
- 真机判读：`mm-vip 生效: mm:vip.isDashiVip` judge 行 + 会员引导弹窗消失/会员图标生效即彩票命中；
  全 NOT_RESOLVED 则按 [mm-scout] 的 DashiVipService(Impl) 方法面出 T-vip2 精确挂。

## 六、风险与边界（诚实结论）

- 本地会员只骗客户端内存态：**服务端对账的权益无效**（AI 服务接口按服务端账目计次、云容量是服务端统计的物理量）。
- 伪造 VIP 无支付行为 ⇒ 封号风险低（推断，无实证）；广告 SDK 只会观察到「加载失败/未展示」，无作弊上报面。
- 易盾可能做完整性校验/环境检测，真机验证时若 app 闪退或报「安全风险」即命中，届时换 dump 手段。
- 开屏广告请求链若在 hook 砍掉前已发出，隐私/流量无改善（同 JD 案例边界）。
- `:bytelive`（抖音直播拉活组件 ×16）、`:push`/`:pushservice` 等营销面不在本次需求内（「乱七八糟的广告」若含推送营销，另行确认范围）。

## 七、未定项

- 真机已装版本 vs 7.27.1 基线未核对（`dumpsys package com.netease.mail` 待跑）。
- VIP 判定收口唯一性（门面 vs 散点）待脱壳读码。
- 开屏/信息流各广告位的服务端开关字段名（云控 JSON schema）待读码。
