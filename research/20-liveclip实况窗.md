# liveclip 实况窗通道（2026-10-24）

## 权限结论
framework 侧 liveclip API（com.hihonor.android.interaction.liveclip.LiveClipManager，static 可反射）**零权限检查**；无 liveclip 专属权限串；校验在服务端 HnInteractionFwk.apk（推断与胶囊共用 ACCESS_HONOR_INTERACTION_SERVICE signature|knownSigner）→ **三方大概率不能合法直喂**。Host 侧 LiveClipHostController.checkCallingPermission 只接受 system uid/平台签名。

## 架构
LiveClipManager(client) → ILiveClipManagerService(server) → ILiveClipHost(宿主渲染 LiveClipHostController) → **SurfacePackage 跨进程回投**。内容插件面：LiveClipProviderManager + ILiveClipFactory.createLiveClipInstance(String) + ILiveClipProxy（**原生 View 工厂，比 JsCard H5 强**：onCreate/onUpdate→AppClipData/onAcquireView(View,int,int)/isRealTime）。
API：bindLiveClip/createLiveClip(AppClipInfo,String,Bundle)/createLiveClipView/removeLiveClipView/destroyLiveClip；回调 onCreateSuccess(String,AppClipData,boolean)/onSurfacePackageCreated|Changed。

## 与胶囊关系
独立渲染通道但**共享数据模型 AppClipData{CapsuleData,JsCardData,RemoteViews,WatchData,Bundle}**，两条通道最终汇合同一模型；updateDisplayData(AppClipData) 即汇入点。SystemUI 无 liveclip 符号（0 命中）——宿主在 HnInteractionFwk 等荣耀进程内渲染。

## Hook 注入建议（若 signature 确认）
1. 最优：服务端 ILiveClipManagerService.Stub.createLiveClip 的 onTransact 前伪造 callingUid=1000/包名白名单（AppClipInfo 直连同模型+可带原生 View Provider）
2. 次优：伪造 ILiveClipProxy Provider 注册工厂，喂任意 View
3. 兜底：CapsuleData/NotificationListener 注入已覆盖（AppClipData 本含 CapsuleData）；liveclip 增量价值=SurfacePackage 原生 View 通道
## 服务端实证（主会话补证 2026-10-24）
已拷 system-files/hninteractionfwk.apk（365KB）：`com.hihonor.systemserver.interaction.liveclip.LiveClipManagerService`（含 ILiveClipManagerService$Stub/mLiveClipRecords/createLiveClip）+ checkPermission/checkCallingOrSelfPermission/checkCallingPackageName/getCallingUid + 权限串 `com.hihonor.systemserver.interaction.permission.ACCESS_HONOR_INTERACTION_SERVICE` 全部在列 → **签名校验坐实，三方直喂死刑；hook 点=LiveClipManagerService 权限校验处或 Stub.createLiveClip onTransact**。
