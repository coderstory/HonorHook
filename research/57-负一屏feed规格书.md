# 自研极简负一屏实现规格书（2026-10-24，源码级）

## 关键修正（推翻 42 号假设）
getType()=5 **不是安全档**：isTowApkInstalled(5) 落 GoogleFeedClient try 分支（无 GMS catch→Dummy），correctNegativeScreenType(5) 国内降级 3。⇒ 唯一路=**hook `NegativeScreenStrategy.getNegativeScreenClient(ZZI)` after 替换返回自研实例**；getType 返 5 只为过 VALID 校验；同步 hook createNewProxyTarget 写回 mProxyTarget 双保险。

## 接口契约（NegativeScreenClient 94 行实读，21 方法）
- 手势三元组（主线程，来自 HiBoardClientManager.onTouchEvent）：startScroll(down 事件，一次手势一次；默认实现含 hideIconFromSwipeHandler 保留)→onScroll(每个 MOVE；Google 公式 progress=clamp(|rawX-downX|/dragLayer.width,0,1))→endScroll(UP/CANCEL；按 0.5 阈值落位/回弹)。完整序列=setMovingOutEnable→startScroll→onScroll×N→endScroll
- overlay 二元组：openOverlay(z,Bundle)=**容器完全 client 自理**（向 launcher.getDragLayer() addView 自建 View）；closeOverlay 对称
- invoke 命令集（-555 卡片样式/-777 bundle/-888 拖拽/-333/-444 生命周期/-111 系最近任务/-1000 断连）自研全空实现
- 必须项：isLoadingAnimFeatureEnabled()=false（否则 Proxy 走 LoadingAnimInputConsumer 劫持手势）、getHiBoardType()=-1、reconnect no-op（勿学 Dummy recreate）
- Proxy 重建时机多（onCreate/onPreferenceChanged/装包）→hook 工厂比 setProxyTarget 稳；Proxy 对未知子类透传 isConnected=true 天然兼容

## 实现（最省事变体）
class DiyClient : **NegativeScreenClientDummy()**（13 行零依赖）覆写手势 5 法，在 InMemoryDexClassLoader(宿主 parent) 生成；模块不引用 hiboard 类。
卡片（纯系统 View 勿 Compose）：时钟=TextClock；步数=TYPE_STEP_COUNTER（launcher 常驻注册，免权限，拒则降级隐藏）；日程=CalendarProvider（SecurityException 降级日期+农历占位）；深色 onUiModeChanged 重读。

## 判据+验收+风险
判据=logcat NegativeScreenClientProxy "proxy target: DiyClient"。验收五条：左滑出现/progress 跟手对拍/0.5 落位回弹/home 返回不残留/SP 切 4 时尊重开关放行。
最险=手势割裂双动画：先只 log 抓序列对拍 Google 公式；isInterceptScroll 反射置位抑制换页；OverScroller 400ms 对齐 Workspace snap。
工期 S~M（hook+Dummy 1d/手势对拍 2d/三卡 2d/打磨 1d）。
