# SystemUI 全域可 hook 面（2026-10-24，灵动胶囊/控制中心/锁屏/通知/截录屏）

APK：system-files/systemui-magicos11.apk（4 dex，41,349 类）。状态栏图标域见 06，此处不重复。

## 1. 灵动胶囊（重点）——双轨
A 轨=状态栏灵动胶囊（dynamicserver，MagicOS 本体）；B 轨=uikit 自由悬浮球 hnfloatingcapsule（WindowManager 独立窗口，桌面模式在用，可复用）。
**数据链路**：framework systemserver InteractionService → InteractionDisplayManager（framework 类）→ SystemUI `DynamicManager.addDynamicInfoListener` 回调 → `CapsuleManager.onDynamicCapsuleDataAdd`（16 参总口）→ CapsuleRecord → HnStatusCapsule/HnDynamicCapsuleLayout 渲染。通知也能喂（NotificationEntry→CapsuleData）。

锚点速查：
| 用途 | 锚点 |
|---|---|
| 喂内容总口 | CapsuleManager#onDynamicCapsuleDataAdd/Update/Remove（16参） |
| 数据注册表 | CapsuleManager.mCapsuleDataMap/mCapsuleRecordMap |
| 胶囊快照 | CapsuleRecord（musicSongInfo/pendingIntent/brandColor/capsuleView…） |
| 调度核心 | CapsuleClusterController.foregroundChanged |
| 驻留时长 | LivelyCapsuleStatusBarHelper.mDisplayTime/mExtremeDisplayTime（hook=无限驻留，S） |
| 计时胶囊 | CapsuleCountDownTimerManager + Chronometer |
| 展开/动画 | HnDynamicCapsuleLayout（20+ SpringInterpolator）/HnStatusCapsule |
| 悬浮球全套 | uikit HnFloatingCapsule + CapsuleState + CapsuleAnimatorManager（16 动画型） |
| 音乐胶囊 | HnMediaCapsuleSpectrumController/MusicIconRotateHelper |
| **官方 JS 卡片通道** | JsCardManager + capsuleTemplate 字段（自定义内容的官方机制!） |

可做：①喂自定义内容（音乐/充电/计时器/摘要）——深水区 L→M，需挖 framework CapsuleData Bundle 键；**捷径=直接用 HnFloatingCapsule 建自家悬浮胶囊（S–M，浅低风险）**；②驻留时长无限化 S；③动画曲线定制 S。风险注记：systemserver 端可能校验包名/ACCESS_HONOR_INTERACTION_SERVICE 权限。

## 2. 控制中心
- **自定义磁贴无需 hook**：AOSP CustomTileSpec/TileRequestActivity 通道完整，app 用 TileService 即可加磁贴
- 注入伪造磁贴：hook HwQSTileHostImpl 磁贴列表（M，spec 序列化存 Settings.Secure）
- 媒体卡自定义：HnMediaControlPanelEx#bindPlayer 后改 ViewHolder（M）
- 钱包卡：qs/pipeline/wallet/*（锁屏同源）
- 边界：控制中心 blur=com.android.magic.blur.*（**药师玻璃域，勿挖**）

## 3. 锁屏
- 时钟引擎：res/raw/keyguard_clock_style_{3,7,9}.json（schema 已抽取：widgetList/COMPOSITE_WIDGET_TYPE/DATE_WEEKDAY/SIMPLE_LUNAR/aodScaleRatio）+ ClockStyleController* 消费端 → **自定义时钟=自造 JSON+hook 样式选择（M）**
- 锁屏胶囊独立：KeyguardCapsuleDataHolder/KeyguardCapsuleController
- 快捷入口换绑：KeyguardAffordanceView/HwKeyguardAffordanceHelper
- 杂志锁屏：MagicLockscreenController（已冻结该 app，残留面）

## 4. 通知
- 过滤绕过/白名单：HwNotificationFilterImpl#shouldFilterOut 返 false（S，浅低险）
- 横幅=dynamicserver Heads-up DynamicCard（与胶囊共用管线）→ 横幅转胶囊样式定制（S–M）

## 5. 截屏/录屏
- 免按键长截屏/自动化：ScrollCaptureController/HwScreenshotController（S）
- 截屏编辑入口换绑：ScreenshotActionController（S）
- 录屏分辨率/帧率档位**不在 SystemUI**（framework/设置侧）——转 settings 域再查

## 6. 彩蛋
电池管理面（HwBatteryManager/NewStyleImageSourceManager）、省电动画、HnMultiUserSwitch、MagicLockscreenController#enableKeyguardInDemoVersion（锁屏 demo 模式）。

## 优先级
胶囊 A 轨喂内容（收益最大 L）> 悬浮球捷径（S–M 先落地）> 锁屏时钟 JSON（M）> 通知过滤（S）> 截屏自动化（S）。
