# framework 荣耀隐藏 API：灵动胶囊直喂可行性（2026-10-24）

样本：/system/framework/framework-magic.jar（已拷 system-files/framework-extract/，含 HnSystemUISharedLib.jar）。

## 核心结论
**三方 app 不能合法喂胶囊**：权限 `com.hihonor.systemserver.interaction.permission.ACCESS_HONOR_INTERACTION_SERVICE` 为 **signature|knownSigner（无 knownCerts）= 等效 signature 级**，仅荣耀平台签名可授。喂内容必须 hook——建议点：HnInteractionFwk 服务端 checkPermission（/system/priv-app/HnSystemServer/HnInteractionFwk.apk）或 NotificationListener 侧 CapsuleData 注入（与荣耀音乐同型）。

## 服务架构
framework-magic.jar（客户端 API）→ HnSystemServer.apk 拉起 HnInteractionFwk.apk（服务端 InteractionDisplayService，含 dispatcher/eem/card/js 子包 + Interaction.db）→ SystemUI dynamicserver 渲染。dumpsys 入口 `interaction_display`（root/shell 可 dump）。

## InteractionDisplayManager 方法面（static）
registerEntrance/unregisterEntrance、startDisplay(InteractionMessage,DisplayCallback)、stopDisplay、updateDisplayState/updateDisplayData(AppClipData)、**sendCustomData(String,SystemEntrance,Shape,Bundle)**、getInteractionMessage/getAll/Started、监听面（CapsuleChanged/AppBackHome/DisplayObserver/ServiceState）、SystemUI 回调面（notifyCardState/CapsuleRect/PackageChanged…）。
结果码与 11 种 RemoveReason；服务类型 TYPE_INTERACTION_{DEFAULT,IMMEDIATELY_FEEDBACK,MOMENT_FEEDBACK,SCENE_CONTROL,SYSTEM_DYNAMIC,BUSINESS_DYNAMIC}_SERVICE。

## CapsuleData 数据模型
字段：mMainText/mSubText/mMainContent[]/mSubContent[]/mIcon/mFirstIcon/mBrandColor/mCustomBrandColor/mStatusBarTemplate/mMainContentRemoteViews/mSubContentRemoteViews/mSessionId/mUid/mExtraData。
**模板常量（12 种）**：TEMPLATE_{IMAGE,TEXT,IMAGE_TEXT,TEXT_IMAGE,TEXT_TEXT,IMAGE_TIMER,IMAGE_TEXT_TIMER,IMAGE_TEXT_TEXT,TEXT_TEXT_IMAGE,IMAGE_TEXT_TEXT_IMAGE,IMAGE_TEXT_IMAGE_TEXT,WAVE_TIMER}。
ExtraData{StatusBarData（capsuleType/degreeLevel/displayDuration/startTime/chronometer/iconAnim/DynamicCardShowFlag/cardCustomColors）, AodData}。
**Bundle 喂入键**：`capsuleData` / `smart_island_capsule_data` / `capsule_anim` / `capsuleColor` / `capsuleType`。
SystemEntrance 枚举（GLOBAL/LOCK_SCREEN/NOTIFICATION_CENTER/CONTROL_CENTER/AOD/SUBSCREEN…）；CardSize 9 种；CardData{RemoteViews, **JsCardData**, CardSize, CardViewType}。

## JsCard 通道（官方自定义内容）
JsCardData{showUrl(H5 包)/showPackageName/versionCode/minPlatformVersion/data(JSON)/code/isPreLoad} → SystemUI JsCardManager 下载渲染，capsuleTemplate 模板名配套。**= 官方 H5 卡片协议**，hook 时伪造 JsCardData 即可喂富内容。

## framework 荣耀扩展面点名（250 包中值得挖）
magicwin/hnmagicsystem（智慧窗注入）、vui（语音）、haptic/lighteffect/swing、aod、cover（折叠）、**liveclip（实况窗姐妹通道，完整 Manager+Host IPC）**、hishow/magicvoice/nb.searchmanager（YOYO）、systemmanager.notificationmanager（胶囊-通知联动）、**permissionmanager Provider（content://com.hihonor.permissionmanager.provider… 权限数据库只读入口）**、security.behaviorauth/riskdetect（hook 反检测面需注意）。

## 对开发计划的影响
v1.4 胶囊功能路线定型：**hook 注入**（sendCustomData 伪造调用方 或 NotificationListener 型 CapsuleData 注入），模板用官方 12 种 + JsCardData 富内容；无合法直喂捷径。
