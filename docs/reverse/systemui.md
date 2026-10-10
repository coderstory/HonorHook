# SystemUI `com.android.systemui` — 逆向分析与实现

> 本文件 = 该 app 的逆向分析记录（信号图标渲染链 + `SystemUiHook` 落地）。索引见 [`README.md`](README.md)。

> 2026-10-10 · 目标 `com.android.systemui` 基线 **17.0.0.1**（versionCode 1700000001，AAK-AN00，Android 17，双卡 DSDS 双联通在网）。
> 实现 = `SystemUiHook`（1 挂载点），单点 `MobileDataControllerImpl.updateSubs(List)` 入参剔除副卡。

## 一、需求

双卡时状态栏右侧显示两组信号图标（SIM1 大 + SIM2 副卡小，各含 5G 类型标 + 信号格）。隐藏 SIM2 那一组；SIM2 单独在卡时仍正常显示。

## 二、渲染链（jadx 实证，`system-files/systemui-jadx`）

```
HnMobileIconsViewModel.filteredSubscriptions  (Flow<List<SubscriptionModel>>，订阅变化重发)
  └ HnMobileUiAdapter.start() 收集（HnMobileUiAdapter.java:69 唯一调用 updateSubs 的地方）
      └ MobileDataControllerImpl.updateSubs(List<SubscriptionModel>)   ★ 单点
          ├ mSubscriptionInfoList.clear() + add(逐元素)  ← 该字段唯一写入点
          └ 逐卡: mobileCallBack.addSignalView(slotId) → SignalUnitNormalView
                 + SignalIconBinder.bind(slotId, view, viewModel, ...)
```

- 所有 `HwSignalClusterView`（状态栏 / 通知头 `notification_header_status_bar` / 锁屏 cluster）在
  `onAttachedToWindow` 经 `addCallback` 注册到同一个 `MobileDataControllerImpl` 单例，
  回调 `SignalClusterView$2.addSignalView(slotId)` 把每卡视图加进 `mobile_combo`。
- 每卡的 5G 类型标 / 信号格 / 漫游 / 吞吐箭头全部在该 `SignalUnitNormalView` 内
  （SignalIconBinder 绑定 `mobile_type` / `mobile_signal` / `mobile_roam` 等子 view）。
- 因此 `updateSubs(List)` 是状态栏每卡图标的**唯一重建出口**；入参剔除副卡 ⇒ 视图根本不创建。

## 三、选点理由（对照 §九哲学）

- **根源/最少点**：改订阅数据源出口（1 点），不 hook 逐视图的 bind/visibility（表现层多点）。
- **上游一致性**：过滤后的列表被存进 `mSubscriptionInfoList`，`addCallback(cb)` 的私有
  `updateSubs(MobileCallBack)` 重建路径同读该字段 ⇒ 单点覆盖两条路径。
- **传过滤副本而非原地改**：无 VSIM 时 HnMobileUiAdapter 直接把上游 Flow 发射的列表对象透传
  （HnMobileUiAdapter.java:49-69），原地 remove 会污染上游发射状态。
- **≥2 卡才剔**：单独用 SIM2（size==1）不剔，图标照常。
- **自愈**：副卡插拔/启停会使 filteredSubscriptions 重发，重新过本点，无需额外失效逻辑。
- **QS 网络瓷贴**走 pipeline ViewModel（MobileIconsViewModel 直连），不经此出口 ⇒ 双卡数据切换功能不受影响。

## 四、实现要点与坑

| 坑 | 处理 |
|----|------|
| `updateSubs` 有 `(List)` / `(MobileCallBack)` 两个 1 参 void 重载 | `MethodResolver` 直连轨只按参数个数/返回值匹配（firstOrNull 顺序不定），**本目标绕过它按参数类型 `java.util.List` 消歧解析**（SystemUiHook.mountOne 内） |
| slotId 字段版本漂移 | 反射 `getField("slotId")` 取值失败时按"未命中"放行原逻辑（PROTECTIVE + runCatching，优雅降级） |
| 副卡判定 | `SubscriptionModel.slotId == 1`（0-based 卡槽；真机 `SimData{slotId=1,subId=2}` 实证），**不是 subscriptionId**（subId 随插拔漂移） |

## 五、验证判据（用户自执行）

```bash
tools/dev.sh deploy-release        # 编译+装包+自动重新启用+强停作用域进程（含 systemui）
tools/dev.sh modlog SystemUiHook   # LSPosed 日志判据
```

- `SystemUiHook: installed 1/1`（无失败清单）
- 状态栏右侧只剩 SIM1 一组信号图标；插拔副卡后仍正确（拔 SIM2 → SIM1 独显；拔 SIM1 → SIM2 图标恢复显示）
- `dumpsys activity service com.android.systemui` 中 `signal_cluster` 的 `mobile_combo` 只剩 1 个子视图

## 六、生效范围说明

同一单例也被锁屏 / 通知头 cluster 消费 ⇒ 副卡信号图标在这些位置一并隐藏（行为一致，预期内）；
左侧运营商名称卡（`msim_status_bar_operators_card_two_id`）与本点无关，如需另做。
