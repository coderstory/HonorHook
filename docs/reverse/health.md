# 荣耀运动健康（com.hihonor.health）— 广告与营销信息分析

> 2026-10-24 首轮分析（MT MCP 静态路线，未全量反编译）。目的：评估 SDK 通杀钩子覆盖度 + 定位营销信息通道。
> 真机：`com.hihonor.health 17.24.0.302`（253MB，171k 类）；服务组件包 `com.hihonor.fitness.healthservice`
> 11.0.0.100（system/priv-app，纯数据服务无 UI，本轮不涉及）。

## 一、SDK 通杀钩子覆盖判定

| 通杀钩子 | 目标类 | health 包内 | 判定 |
|---|---|---|---|
| HonorAdsHook | `com.hihonor.adsdk.base.widget.base.BaseAdView`（49 方法） | ✅ 存在（adsdk 共 1000 类） | **广告视图已盖** |
| HonorTrackingHook | `com.hihonor.hianalytics.process.HiAnalytics{Instance,Manager}` | ✅ 存在（290 类） | **上报已盖** |
| HonorOpsAdHook | `com.hihonor.osoperationsdk.manager.RecommendCardClient` + `RecommendCallback` | ❌ **整包不存在**（0 类） | **运营推荐卡钩子无效——health 的营销走自有通道（亲选电商）** |

> 结论：注册 health 为目标后，adsdk 广告视图 + hianalytics 上报即被通杀覆盖；
> **营销信息的主体（亲选电商）不在通杀射程内**，需专点（见 §三）。

## 二、营销信息主体：内嵌「荣耀亲选」电商模块（cn.honor.qinxuan，496 类）

### 身份与形态

- 首页推荐流：`cn.honor.qinxuan.health.home.recommend.HealthRecommendFragment`
  （extends `honorchoice.home.recommend.RecommendFragment`）+ 独立 `HealthRecommendActivity`
- 首页 banner：`cn.honor.qinxuan.widget.HomeBannerView` —— **super = RelativeLayout，自建 View，
  不继承 adsdk BaseAdView ⇒ 通杀钩子不经过它**
- 网络层：`honorchoice.home.api.HomeApiService`（retrofit，20 个端点）via `HomeApiFactory`

### API 矩阵（营销面全貌，全部服务端下发）

| 端点 | 内容 |
|---|---|
| `getHomeRecommend` | 首页推荐商品流（`HomeRecommendResp.productList/hotWordList/ruleId`——ruleId 直证服务端推荐规则） |
| `getHomeSecKillInfoList` | 秒杀活动 |
| `queryHomeRegionInfo` / `queryMobileHomeInfo` | **首页运营位布局配置**（`HomeRegionResp→HomeRegionInfo[]`，每区块 = 字符串类型 + 内容 List；`MobileHomeInfoResp$AdsActivityInfo` = 广告活动位） |
| `queryOperateAdsInfo` | 运营广告信息（方法名直证） |
| `queryContentNewsList` / `queryActiveContent` | 内容资讯流 / 活动内容 |
| `queryMCP*` 系列、`querySmartRecommendProduct`、`querySearchRanking` | 商城商品/分类/热搜/智能推荐/搜索排行 |
| `queryUserGroup` / `querySystemConfigSync` | 用户分组（投放定向）/ 系统配置 |

### 客户端/服务端门判定

商品与区块内容 = 服务端下发（resp 实体 + ruleId），**客户端伪造无效**；
可做的是**响应出口裁剪**（同 theme-store `SlotData.getList` 模式）：
- `queryHomeRegionInfo` / `queryMobileHomeInfo` 出口按 region 类型剔营销区块
- `getHomeRecommend` / `queryOperateAdsInfo` 出口清列表
- 入口级：推荐 tab/卡片入口的本地开关判定（是否存在本地门待真机 UI 验证）

### 其它营销面（IAP 付费转化，独立于亲选）

- `com.hihonor.it.*`（IAP 收银台/商城 SDK）：`PcpPromotionTextView` / `BubblePromotionBarPopup` /
  `OrderPromotionTipDialog` / `PromotionRules`（contentType+promoLabel，Parcelable，服务端下发促销标签）
  **深挖修正（2026-10-24）**：这些组件属于 **IAP 收银台/订单详情内页**（`it.ips.cashier` 支付流程、
  `ShopCartAndOrderDetailAdapter` 购物车/订单 adapter）——只在用户**主动进入购买流程**时出现，
  不是首页主动营销打扰；且 `BubblePromotionBarPopup` 外部消费点 = 0（疑似未启用路径）。
  消费点数：PcpPromotionTextView 仅内部匿名类 1 处；PromotionRules 消费 1 处（订单 adapter）。
  **建议不挂**：支付链路上动刀风险高（回调复杂，炸了影响购买），收益低（用户主动进入的页面内
  促销标签 ≠ 营销打扰）。若真机观察到首页/详情页出现主动式促销气泡，再按运行时证据定位（当前
  静态分析未见首页消费点）

## 三、候选挂载点（若注册新目标；均为未混淆类，跨版本稳）

| 点 | 类.方法 | 语义 |
|---|---|---|
| 首页布局配置出口 | `HomeApiService` 实现（retrofit 代理）→ 响应 bean 出口 | 按 region 类型剔营销区块（需找到 retrofit 实现委托点，见未定项①） |
| 运营广告出口 | 同上，`queryOperateAdsInfo` | 清空/返空列表 |
| 营销 H5 桥 | `com.hihonor.health.feature.marketing.h5.MarketingWebJsMethod` | H5 营销页 JS 能力面（方法清单未枚举，待定） |

> 未混淆（`cn.honor.qinxuan` / `com.hihonor.health.feature.marketing` 全可读）⇒ 直连反射优先即可，
> 预期跨版本零漂移（同 X1/X4 经验）；retrofit 动态代理类本身不可挂，须挂接口默认实现/bean 出口。

## 四、边界与未定项

1. **retrofit 实现委托点未定位**：`HomeApiService` 是接口，挂载需找其实现/回调收口（`BaseModel`/
   `HomeApiFactory` 下游的 Observable 订阅消费点），或退一步挂 bean 解析出口（Gson TypeAdapter 层）。
2. **真机 UI 未观察**：首页实际出现哪些营销区块、推荐 tab 是否存在 = 服务端下发 + 用户分组决定，
   静态枚举不到（reverse-craft §三：只能运行时看）。下一步：真机开 App 首页 UI dump 定位实际卡片。
3. **用户分组投放**：`queryUserGroup` 暗示营销按人群下发——不同设备看到的卡不同，A/B 时注意。
4. **HealthService 包**（priv-app 数据服务）未分析——如营销推送由它触发需另查（本轮判定纯 UI 侧）。
5. 会员促销气泡（IAP）是否纳入：待用户拍板。

## 五、2026-10-24 下午轮次：运营位断根真机验证

探针取证结论：`cardEntityToModel` 链只承载**功能卡**（运动记录 1005/消耗 1008/步数 1007/
锻炼时长 1020/身体能量 1023/活动小时数 1021/温度 1013，type=-1），营销卡不在此链。
「天天赚现金」真身 = 服务端运营配置 → `BannerDataRepository.getBannerCards` →
`OperationsManager` 5 个 `update*` 写入口（banner 卡/商业信息/首页弹窗/运营条目/特殊卡）。

- 拦截：5 个 `update*` 全部不 proceed（写入断根，suspend 返回 null 按 Unit 语义）。
  真机判据：`HonorQinxuanHook installed 6/6` + 触发时五连 `qinxuan-hook 生效` 行（16:33 实测）。
- **撤销记录**：第一版曾挂 `getHomeModulesData → Observable.empty()` 当营销总入口，真机
  **过度拦截**——功能卡同管道 → 首页白屏。已撤销，撤销原因留档 HonorQinxuanHook 注释。
  教训：收口前必须确认管道只承载目标内容；有疑问先上只读探针。
- 遗留：开屏 `SplashAdvertisementActivity` 走自有渲染（非 BaseAdView），本轮已加
  onCreate-finish 秒退（见 HonorQinxuanHook splashAd 任务）；若 SDK 更换开屏形态需重对。

## 六、终局架构（2026-10-25 真机全绿：用户确认「没广告了」）

定位过程：运营卡断根后 banner 仍在 → 用户截图 + 「开会员免广告」提示 → 顺藤定位
`com.hihonor.library.advertisement.manager.AdsManager.isShowAds(Z)` = 全 App 广告展示
**总判定门**（会员免广告的权益实现点）。

### 最终挂载（HonorQinxuanHook，7 点）

| 点 | 语义 | 层级 |
|---|---|---|
| `AdsManager.isShowAds → false` | **广告总判定门恒假**（会员免广告等效实现，不伪造会员态） | 上游总闸 |
| `OperationsManager.updateBannerData` 不 proceed | 运营 banner 写入断根 | 防线1 |
| `updateHealthHomepagePopupData` 不 proceed | 首页营销弹窗断根 | 防线1 |
| `updateOperationsItem` p0 置空 proceed | 运营条目断根（**参数替换型**，suspend 零 null 风险） | 防线1 |
| ~~updateCommercialInfo/updateSpecialCard 不 proceed~~ | **已撤销**：suspend 返 null 炸启动协程 = 启动白屏（真机实证） | 教训 |
| `SplashAdvertisementActivity.onCreate` → finish | 开屏 Activity 空壳秒退（BaseAdView 清内容后白屏假死） | 兜底 |
| ~~getHomeModulesData → empty~~ | **已撤销**：过度拦截，功能卡同管道 → 首页白屏（真机实证） | 教训 |

### 关键认知

1. 「天天赚现金」大 banner 疑似真身 = **全局投放 DeliveryCampaign**（Room 库
   DeliveryCampaignEntity，`GlobalDeliveryDataHelper.getGlobalDeliveryData`）——isShowAds
   断后若残留，下一刀挂这里（未需要）。
2. 两层教训已入手册：**收口前确认管道纯度**（getHomeModulesData 白屏）；**suspend 钩子
   不 proceed 返 null 炸协程**（update* 白屏）——修正挂法 = 参数替换（置空列表）或仅拦 void。
3. 会员促销（IAP 收银台内页）维持不挂结论；isShowAds 门断后收银台权益判定不受影响
   （isShowAds 是广告展示门，非会员态本身——无越权副作用）。

## 七、钩子职责梳理（为什么不能只留 isShowAds 一个）

用户问「是不是只需要一个 hook」——答案：**不是**。三个正交面，互不覆盖（真机日志实证）：

| 面 | 数据链 | isShowAds 门是否覆盖 | 对应 hook |
|---|---|---|---|
| **广告**（开屏/横幅/插屏，adsdk） | isShowAds 判定 → adsdk 拉取渲染 | ✅ 总门即为此设 | `isShowAds → false` |
| **运营位**（banner 图卡「天天赚现金」/条目行「天天领红包」/弹窗「现金奖励」） | 服务端运营配置 → BannerDataRepository(DB 读 getBannerCards + OperationsManager update* 写 Flow)——**不走 isShowAds**（18:05 实证：isShowAds 已挂，getBannerCards 生效行照打） | ❌ | `getBannerCards 置空` + `updateBannerData/updateOperationsItem/updateSpecialCard.empty/updateHealthHomepagePopupData` 断根 |
| **开屏空壳假死** | SplashAdvertisementActivity 生命周期（BaseAdView 清内容后 Activity 不退出）——Activity 拉起与否不在 isShowAds 判定内 | ❌ | `onCreate → finish` |

### 各 hook 移除后果（都是实证过的）

- 移除 isShowAds → 广告全回（会员免广告失效）
- 移除 updateOperationsItem 置空 → 「天天领红包」入口行回归（17:59 截图已消 = 当前在拦）
- 移除 getBannerCards 置空 → 「天天赚现金」图卡回归（18:05 前的状态）
- 移除 splash finish → 开屏白屏假死回归（12:49 前的状态）
- 已撤销的 3 个（getHomeModulesData empty / update* suspend 不 proceed）→ 白屏，勿复活

### 可精简项

updateBannerData（Flow 写）与 getBannerCards（DB 读）是同源两链，当前双拦最稳；
若未来要减点，先删 updateBannerData（读侧断根后写侧收益存疑），**删后必须真机回归**。
