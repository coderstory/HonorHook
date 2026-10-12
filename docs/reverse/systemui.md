# SystemUI `com.android.systemui` — 逆向分析与实现

> 本文件 = 该 app 的逆向分析记录（信号图标渲染链 + `SystemUiHook` 落地）。索引见 [`README.md`](README.md)。

> 2026-10-10 · 目标 `com.android.systemui` 基线 **17.0.0.1**（versionCode 1700000001，AAK-AN00，Android 17，双卡 DSDS 双联通在网）。
> 2026-10-11 · 新增 U2 隐藏 5G 标 / U3 双卡信号合并（见 §七）；U1 副卡隐藏实现不变。
> 2026-10-12 · 新增 U4 系统通知隐藏 ×3（§八）/ U5 手电筒低电量放行（§九）。

## 一、需求

双卡时状态栏右侧显示两组信号图标（SIM1 大 + SIM2 副卡小，各含 5G 类型标 + 信号格）。隐藏 SIM2 那一组；SIM2 单独在卡时仍正常显示。
2026-10-11 追加：不显示 5G/4G 类型标；双卡时把两张卡的信号格合并成「一个图标两排」（iOS 样式）。

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

- `SystemUiHook: installed N/N`（N = 开启的功能数；无失败清单）
- 状态栏右侧只剩 SIM1 一组信号图标；插拔副卡后仍正确（拔 SIM2 → SIM1 独显；拔 SIM1 → SIM2 图标恢复显示）
- `dumpsys activity service com.android.systemui` 中 `signal_cluster` 的 `mobile_combo` 只剩 1 个子视图

各功能的单独判据见 §七（U2/U3 的判据在 §七·5 与 §七·6）。

## 六、生效范围说明

同一单例也被锁屏 / 通知头 cluster 消费 ⇒ 副卡信号图标在这些位置一并隐藏（行为一致，预期内）；
左侧运营商名称卡（`msim_status_bar_operators_card_two_id`）与本点无关，如需另做。

## 七、信号格与视图树（2026-10-11 真机实证）

> 本节全部为**真机取证**结论，静态 jadx 分析得不到或会得出错误结论的项已标注。

### 7.1 视图树（布局实证）

```
mobile_combo (LinearLayout, horizontal, wrap_content)
  └ mobile_combo_card (SignalUnitNormalView)      ← addSignalView 的返回值，每卡一个
      └ FrameLayout
          ├ mobile_inout (TintImageView)   数据活动箭头
          ├ mobile_type  (TintImageView)   5G/4G 类型标   ← U2 隐藏它
          ├ mobile_signal(TintImageView)   信号格 ★ 换合并图标的地方
          └ mobile_roam  (TintImageView)   漫游标
```

四种子视图**各自独立** ⇒ 只动 `mobile_signal` 不会波及漫游标/数据箭头/5G 标。
`mobile_type` / `mobile_signal` 的 view id **运行时 `getIdentifier` 取**，不硬编码 `R.id` 数值（资源 id 逐版漂移）。

### 7.2 信号格是 6 档不是 5 档

资源名形如 `stat_sys_signal_five_0` … `stat_sys_signal_five_5`（含 `_small` 小尺寸变体，状态栏用 small；
另有 `_fully` 满格变体）。**档位数字 0~5 共 6 档**。探针实测命中 `stat_sys_signal_five_5_small`。

单格尺寸 **17dp 高 × 22.67dp 宽**（vector 尺寸 `svg_regular_size_17dp = 17dp`）。
两排上下叠需 34dp > 状态栏高度 ⇒ **自绘时每排必须压扁**（这是「iOS 双排」在 Android 状态栏的物理约束）。

### 7.3 信号刷新的唯一落点

```
SignalIconBinder.bind(...) → RepeatWhenAttached {
    collect(viewModel.commonImpl.getMobileStrengthId()) { id ->      ← SignalIconBinder.java:469
        imageView.setVisibility(VISIBLE)
        HnSignalUtils.setIconForView(imageView, id)                  ← :431 ★ 信号格每次变化必调
    }
}
```

**踩坑（重要）**：初判信号变化走 `TelephonyCallback.onSignalStrengthChanged`，
但真机布 **9 个挂点全零触发**（framework 嵌套类 3 个 + DexKit 捞到 6 个，其中 2 个是卫星通信 SatelliteController 无关）。
走数据开关（`svc data disable/enable`）与飞行模式均不触发。⇒ **TelephonyCallback 不是 SystemUI 侧的刷新路径，
StateFlow → `setIconForView` 才是**。该方法（`com.android.systemui.utils.HnSignalUtils`，具名静态）是最稳的驱动点。

它同时被用于 `mobile_roam` / `activityInOut` / `mobile_type`（同文件 614/695/774/925/937 行），
故 hook 后**必须按资源名过滤**只处理 `stat_sys_signal*`。

### 7.4 libxposed 102 的返回值规则（两次崩溃换来的）

| 被 hook 方法 | 返回类型 | hook 回调必须返回 |
|---|---|---|
| `void` 方法（`updateSubs` / `bind` / `setIconForView`） | void | **`null`** |
| `addSignalView(int)` | **ViewGroup** | **宿主视图原样返回**，返回 null 会让宿主 `bind(view=null)` → NPE |

- 写死 `; null` 于 void 方法 ⇒ `ClassCastException: Return value's type from hook callback does not match` → SystemUI 崩溃循环。
- 写死 `; null` 于 `addSignalView` ⇒ 宿主拿到 null → `SignalIconBinder.bind` 第 5 行 `view.getClass()` NPE → 崩溃循环。
- 另：匿名类 `SignalClusterView$2` 的**外层实例不能靠 superclass 链找**（匿名类非内部类，无外部类引用），
  要走合成字段 `this$0`。U3 最终设计绕开了这两点，只挂一个 void 方法。

### 7.5 U2 隐藏 5G 标

挂 `SignalIconBinder.bind`（6 参、具名、静态、无重载歧义），第 2 参是每卡的 `SignalUnitNormalView`，
在宿主把 `mobile_type` 交给协程绑定**之前**置 `GONE`。`bind` 只在每卡视图创建时调一次（随 `updateSubs` 重建），
故置 GONE 即持久。找不到 `mobile_type` 时**留痕告警**（布局漂移信号，不刷屏）。

### 7.6 U3 双卡信号合并

**设计**：只把主卡（卡序 0）的 `mobile_signal` 换成自绘 Drawable（上下两排，每排 4 根递增柱），
同时把副卡（卡序 1）整张 `GONE` + 宽度压 0。省一整张卡的宽度，且漫游标/数据箭头不受影响。

**数据**：`setIconForView` 入参资源 id → 正则 `^stat_sys_signal(?:_five)?_(\d)` 取档位；
按 `mobile_combo_card` 在 `mobile_combo` 的 index 区分卡序。合并在 `group.post{}` 里做（等宿主建完所有卡）。

**取色**：读 `mobile_signal` 自己的 `imageTintList`（宿主 DarkIconDispatcher 体系算好的），不自算 ⇒ 跟随深色模式。

**未验证**：本节 U3 部分代码已实现且编译通过，但**尚未在真机验证过任何视觉效果**（设备不可用）。
验证判据 = 日志 `HonorHookMerged: 已合并：主卡=X 副卡=Y`。验证前须在模块 UI **关闭 U1「隐藏副卡图标」**
（U1 开启时副卡在数据层已被剔除，无第二张卡可合并）。

### 7.7 自绘 Drawable 会被宿主擦掉（必须包宿主外壳）

`HnSignalUtils.setIconForView` 内部有快速返回 + 强制覆盖：

```java
Drawable d = imageView.getDrawable();
if (d instanceof ResScalingDrawableWrapper && ((ResScalingDrawableWrapper) d).iconId == i) return;  // 快速返回
…
imageView.setImageDrawable(new ResScalingDrawableWrapper(drawable2, i, getScaleFactor(ctx)));      // 覆盖
```

若直接 `setImageDrawable(自绘 Drawable)`：`instanceof` 不成立 ⇒ 快速返回失效 ⇒
**每次信号强度变化宿主都会覆盖掉自绘图**，功能等于没生效（且表现为「偶尔闪一下原生图标」）。

**正解（已废弃，见 §7.8）**：把自绘图包进宿主自己的 `ResScalingDrawableWrapper` ……
该方案能防覆盖，但仍解决不了「卡序判定」与「宿主重建后状态残留」，最终被 §7.8 的架构取代。

### 7.8 双排合并的最终架构（移植 HyperCeiler，2026-10-11）

参考：[HyperCeiler `DualRowSignalHookV.kt`](https://github.com/ReChronoRain/HyperCeiler)（小米 HyperOS 同功能实现）。
它证明「iOS 式双排」在 SystemUI 上可行，且**五个关注点必须分开挂**：

| 层 | 荣耀 SystemUI 17.0.0.1（实证） | HyperCeiler（小米） |
|---|---|---|
| 视图创建 | `ModernStatusBarMobileView.constructAndBind(...)` | 同名同签名（AOSP 共同祖先） |
| 信号数据 | `HnSignalUtils.setIconForView` 第 2 参（资源 id） | `MobileSignalController.notifyListeners` |
| 插拔卡 | `MobileDataControllerImpl.updateSubs(List)` | `NetworkController.setCurrentSubscriptionsLocked` |
| 反色 | 待真机验 | `MiuiMobileIconBinder.bind` 的 `tintLightColorFlow` |
| 卡数判定 | `states` 里已建根视图数 | `mMobileSignalControllers.size()` |

**为什么放弃「只挂 setIconForView 单点」**（初版方案，静态审查查出三个必然失效的问题）：
1. **卡序画反**：靠 child index 定主副，但宿主建卡顺序不保证按卡槽（VSIM 会被挪到最前）；
2. **重建后不再生效**：`secondaryHidden` 挂在容器上，宿主 `removeAllViews` 重建后标记残留；
3. **宿主覆盖自绘图**：直接改 `mobile_signal` 的 drawable 会被宿主每次刷新擦掉。

**新架构如何绕开**：
- `constructAndBind` 的返回值 `ModernStatusBarMobileView` 自带 **`public int subId`** ⇒ subId 直接可读，
  不靠 index 反推 ⇒ 解决①；
- 双排容器是我们自己 `addView` 的子树，宿主 `removeAllViews` 清掉后**下一轮 constructAndBind 重建**，
  状态挂 rootView（WeakHashMap）随视图一起报废 ⇒ 无残留，解决②；
- 合并图放在**自建容器**里，与宿主 `mobile_signal` 的 `setImageDrawable` 完全隔离 ⇒ 不被覆盖，解决③。

**注意（移植时易错）**：`constructAndBind` **非 void**（返回 View），hook 回调必须**原样返回宿主视图**；
返回 null 会让宿主后续 `bind` 收到 null 视图而 NPE（2026-10-11 崩溃实证）。

**形态**：每卡视图里 `mobile_combo` 装各子图标。双排时把原生 `mobile_signal` 隐藏，
在 `mobile_combo` 里插入一个 FrameLayout，内含两个 ImageView（上=数据卡 / 下=非数据卡），
并只保留卡槽 0 那组视图（slot>0 整组 GONE）⇒ 视觉上「一个图标两排」，宽度省掉一整组。

**未验证**：U3 仍**零真机验证**。新架构只做了编译与单测（档位解析 10 例）。
验证判据 = 日志 `HonorHookMerged` 前缀。验证前须在模块 UI **关闭 U1「隐藏副卡图标」**。

### 7.9 U3 双卡合并：结案废弃（2026-10-11）

真机五轮迭代后**彻底删除代码**（`MergedSignalHook.kt`、`SignalLevelParseTest.kt`、
`FeatureCatalog.KEY_SYSTEMUI_MERGE_SIGNAL`、`SystemUiHook` 的 U3 分派）。本节留档防重踩。

**最终根因链**（全部真机实证，按踩坑顺序）：

1. **`mobile_combo` 不是 `ModernStatusBarMobileView`**：这版宿主（17.0.0.1）状态栏
   `signal_cluster_container` 里的卡根是 **LinearLayout**，读不到 `public int subId` 字段
   ⇒ 一切按 subId 排序/主副判定的设计全部落空。
2. **`setIconForView` 拦截器必须 `chain.proceed()`**：不 proceed ⇒ 宿主原方法不执行
   ⇒ `TintImageView` 上的信号图标永远画不出来（现象：SIM 框架在、信号全空）。
   这是最隐蔽的一条：hook「生效」着，只是把宿主的绘制吞了。
3. **卡锚点没有 `mobile_combo_card`**：inner 布局 `<merge>` 直挂，`mobile_signal`
   的祖先链是 `mobile_combo < FrameLayout < signal_cluster_container`。
4. **`constructAndBind` 在显示路径上零触发**：双排只能由 `setIconForView` 驱动。
5. **宿主视图重建 churn**：2 张卡在状态栏注册出 4~5 个根（旧根 detach 后靠 GC 回收），
   主卡选择必须按 cluster 分组 + `isAttachedToWindow` 过滤 + 容器内 child index 排序，
   任何基于全局列表/subId 的排序都会随机选主（WeakHashMap 迭代序）。
6. **档位采集拿不到真实值**：`setIconForView` 的前若干次调用都是
   `stat_sys_signal_null` / `icon=0` / `mobile_inout` 的 `single_stat_sys_signal_connected`，
   真实档位图标被 6 条日志上限挤掉，无法确认采集口径。

**教训**：LSPosed 拦截器的默认姿势是「先 `proceed()` 放行宿主，再做旁路采集」；
拿不到可靠锚点（subId/卡序/档位）时，双排这种**强视图操作**不可做——
应改用 §7.3 的数据源轨（`getPrimaryLevel()` StateFlow）重做，而不是继续在渲染点硬刚。

## 八、U4 系统通知隐藏（充电 / 热点共享 / USB 调试，2026-10-12）

### 8.1 选点：通知管线收口

单挂载点 `com.android.systemui.statusbar.notification.HwNotificationFilterImpl#shouldFilterOut(NotificationEntry)Z`（未混淆、无重载；`HwDependencyFactory` DI 唯一绑定，全工程无第二子类）。

传播链（smali 复核，jadx 烂流会假报 return false）：

```
HnCoordinatorImpl.attach():325-327  addPreGroupFilter(mHnPreGroupNotifFilter) + addFinalizeFilter(mHnFilter)
  → HnCoordinatorImpl$4.smali:46-52  classId=1 分支调 mNotificationFilterEx.shouldFilterOut(entry)
      返回 true ⇒ :cond_4 ⇒ return true
  → ShadeListBuilder.applyFilters:285-301  命中即设 excludingFilter ⇒ entry 出渲染列表
      ⇒ 下拉 / 锁屏 / 横幅 / 图标全无（entry 无 row 可绑定）
HwNotificationListnerImpl.wakeScreenOn:444  同判 ⇒ 隐藏的不再点亮屏幕/AOD
```

- pre-group 那路（classId=0）只调 `shouldFilterOutPreGroup`，**不经过本点**——finalize 一路已够。
- 宿主方法本体是纯查询（keyguard / 媒体中心白名单），hook 恒 `proceed()` 后 OR 上匹配结果，
  宿主原有过滤行为不受影响；返回类型 Z，回调必须返回 Boolean。

### 8.2 身份匹配（dumpsys 实证 2026-10-12，设备在网）

| 功能 | 规则 | 证据 |
|---|---|---|
| 充电 | `pkg==com.hihonor.systemmanager && id==200001` | 插充电器后真机活跃条目：title「极速充电」text「加速充电中，设备温度会升高」action「本次关闭」，ONGOING |
| 热点 | `pkg==android && group=="TetheredNotification"` | 热点开时真机活跃条目：title「1 台设备」text「已共享 275 MB」，group 为 AOSP framework 硬编码串 |
| USB 调试 | `pkg==android && channelId 含 "adb"`（宽匹配） | 静态推断：system_server `AdbDebuggingManager` 发、pkg=android；wifi tcpip 模式不发此通知，未抓到活跃条目 |

- NMS 对渠道 id 强制打码（`dumpsys --noredact` / `cmd notification get` 均为 `***`，隐私补丁）⇒ 充电/热点不用渠道做锚点；**渠道 id 唯一证据源 = 模块内建探针**（`probeNotifIdentity`，每 key 一条、封顶 40 条，打 pkg/id/group/channel/title）。
- 充电发帖方是**系统管家**（非 powergenie，初判错误）；普通充电态可能同 id 换标题，探针运行期核对。
- `id=2000 pkg=android` 是「LSPosed 已加载」框架通知，与上述规则均不符，不受影响。

### 8.3 排查备忘

- 静态读不到发帖方时走 `cmd notification list` + `dumpsys notification`（活跃条目对比插拔前后差集）；
  `HnCoordinatorImpl` 还注册了 `HnNotificationEntryDumper` 进 DumpManager，可作宿主侧 dump 通道。
- sbn 全量反射读取（`mSbn` public 字段 + 无参 getter）：本地 android.jar（android-37.0）缺
  `android.app.StatusBarNotification`，走可解析 stub 强转会撞运行期类身份。

## 九、U5 手电筒低电量放行（2026-10-12）

### 9.1 门清单（全部 5% 阈值，jadx 实证）

| 入口 | 门 | 位置 |
|---|---|---|
| QS 瓷贴（下拉菜单） | `HwPhoneStatusBar.canOpenFlashLight()` | `HwPhoneStatusBar.java:625-628`：`mBatteryManager == null \|\| mLevel > 5`；基类 `StatusBar.java:1648` 是恒 true 的 stub |
| 桌面小部件 | 同上 | `FlashWidgetIntentService.java:49` |
| 锁屏工具箱 ×2 | `BatteryStateInfo.isExhaustBatteryLevel()` | `BatteryStateInfo.java:400`：`getChargeLevel() <= 5`；`FlashlightToolLayout.java:585` / `ToolBoxView.java:731` |
| 长按亮度面板 | 内联 `mBatteryLevel > 5` | `FlashlightActivity.java:243`（另 disable 按钮） |

挂 `canOpenFlashLight` 一点盖 QS + 小部件（2 消费方，纯查询门短路返回 true 不 proceed）。
锁屏门未实现（用户未要求）；亮度面板内联判断无单点可挂，放弃。

### 9.2 风险与验证

- 5% 是厂商电池保护阈值，绕过后可开到深度放电自动关机——UI 描述已注明，默认关。
- 真机验证（用户自执行，无需真放电）：`dumpsys battery unplug` + `set level 4` 模拟，
  下拉点手电筒应开灯无 toast「电量过低，手电筒无法开启」；验完 `dumpsys battery reset`。

## 十、U4/U5 验证判据（用户自执行）

```bash
tools/dev.sh deploy-release        # 编译+装包+自动重新启用+强停作用域进程（含 systemui）
tools/dev.sh modlog SystemUiHook   # LSPosed 日志判据
```

- `SystemUiHook: installed N/N`（U1/U2/U4/U5 全开时 N=5）
- 通知探针行：插充电器 / 开热点 / 插 USB 线各触发一次，`systemui-hook 通知探针:` 行给出全量身份
  （收紧 USB 调试渠道 id 与充电普通态 id 的依据）
- 判据行：`隐藏充电通知(...)` / `隐藏热点通知(...)` / `隐藏USB调试通知(...)` / `电量 N% 放行手电筒`
- 未验证（2026-10-12）：横幅/状态栏图标是否随列表剔除同步消失（理论同源，待真机观测）；
  充电普通态 id 与 USB 调试渠道 id 未实证。
