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
