# HonorDuo 拆解（2026-10-24，v1.0.2/10002，当日发布）

样本：system-files/honorduo.apk（3.76MB，libxposed API 93，scope 仅 systemui）。
功能面（1.0.2 更新日志）：状态栏天气+时钟双行、通知面板四开关、图标替换（WiFi5/6/7 assets 内置）、圆环双卡堆叠、自定义字体、充电闪电动画、胶囊避让修复、深浅色实时变色、熄屏停动画（功耗）。

## 实现路线（与 06 方案 A 对照）
- **完全不用** setIconVisibility/onTuningChanged/icon_blacklist/pipeline VM（dex 0 命中）
- 它的路子=**View 层直控**：按 id（battery/clock/wifi/mobile_signal/system_icon_area）抓 View 改 visibility + remember/restore；enforceSuppression 持续压制防系统重排闪回
- 只 hook 9 个未混淆类：HwStatusbarContents/HwSystemIcons/SignalClusterView/SignalUnitNormalView/HwStatusBarClock/HwStatusBarBatteryView/**HnPhoneStatusBarAnimController（只反射读 mIsColoredCapsuleShown/mCapsuleRight/mIsCapsuleShown 做避让，不 hook 方法）**/HnControlCenterHeader/NotifCenterHeader*
- 视觉=纯自绘（GlyphView+TrioRenderer+TrioZones 分区引擎+WeatherIconDrawer）；数据面全靠广播/ConnectivityManager 自采（无 VM）
- 暗色走 onDarkChanged；防烧屏 burnInTask；熄屏 ScreenStateTracker 停动画
- **弱口**：无 VM 细粒度（漫游/activityInOut/双卡详情）、**无锁屏 KeyguardStatusBar 适配**

## 结论（对 v1.4 的定稿输入）
1. HonorDuo 已验证「真融合可行 + 两大坑」（胶囊避让/容器溢出）就是主战场——与我们 06 报告预判一致
2. 我方差异化=它没有的：VM 数据源（漫游/双卡细粒度）+ 锁屏联动 + 胶囊生态联动（14/16）
3. 必抄三件：①AnimController 只读字段不 hook 方法的轻侵入避让；②ScreenStateTracker 熄屏停动画；③模块 APK assets 图标工厂（ZipFile+缓存）
4. HyperDuo（yixing233，1.7.4 仍更新）与 HonorDuo（odomu）不同作者零代码引用=创意同源重写；竞品仍在快速迭代，v1.4 上线前需再核对它的 changelog
