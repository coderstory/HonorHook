# 2026-11-08 · 界面深度美化 v1（实验分支 `experiment/ui-overhaul`）

> **快照注记（2026-11-08 21:45 之后）**：本计划里「主题预览卡」「AMOLED / 纯黑档」「Monet 跟随壁纸取色」等条目**已随 `8fc723b` 整体删除**；主题设置页现仅 4 张卡（明暗模式 / 玻璃与底栏 / 萤火虫 / 背景）。文内相关描述保留为决策历史，**不要当待办继续做**。

用户要求（m00025）：界面布局 + 展示特效（卡片默认不显示、从屏幕外飞进来、渐进式展现）+ 装饰细节，
要"突破固有思维"，但整体必须协调。分支 `experiment/ui-overhaul`（基于 `dev`），不并入 `dev` 之前先给用户看效果。

## 概念：花园晨启（Garden Dawn）

`GardenBackdrop` 已经是"静态晨光花园"。进场编排沿用同一个隐喻：**卡片从画面各边被托进/吹进花园**，
轻微倾斜、分层错峰，像花园里依次亮起的灯；落位后依次"呼吸"出内容。默认状态即"未入场"——
用户看到的第一个画面只有背景与顶栏，内容自己生长出来。

**方向不是随机的，按版式角色取边**（v1 全部自下起飞，实机是"一列电梯"，用户 2026-11-08 反馈后改）：

| 版式角色 | 入场边 | 例子 |
|---|---|---|
| 页头 / 第一张卡 | 上（落下 150dp，倾角 1.4°） | 首页状态卡、功能页开关卡、主题预览卡 |
| 成对并排（左列 / 右列） | 左 / 右（横向 210dp + 上抬 18dp，倾角 5.5°） | 功能页网格、工具箱 6 磁贴 |
| 纵向列表卡 | 下（托起 190dp，奇偶扇开 ±40dp） | InfoCard、设置三张卡、详情/安全类逐条 |

三条硬性约束（来自盘点，避免破坏既有观感）：

1. 卡片底色已被 `Theme.kt:104-108` 压到 0.62/0.72 alpha，入场 alpha 只能做短促淡入（0→1 在前 45% 行程内完成），
   主位移交给 `translationY` / `scale` / `rotationZ`，不做长时间半透明悬浮。
2. 底栏与内容层是**兄弟**（`MainActivity.kt:170-201`，同父会让 backdrop 递归采样 SIGSEGV）——进场只作用于内容层内侧。
3. `Fireflies` 挂在 `HomeScreen` 顶层（盖在卡上），不参与卡片进场；`GardenBackdrop` 保持静态（全屏 Canvas 逐帧重绘太贵）。

## 变更清单（只改这些）

| # | 文件 | 改动 |
|---|---|---|
| 1 | `ui/component/motion/Entrance.kt`（新） | `EntranceState`（一次性播放登记）+ `LocalEntranceHost` / `LocalEntranceGate` + `Modifier.cardEntrance(order, key, edge)`（`EntranceEdge.Top/Bottom/Left/Right`）+ `Modifier.revealFade(order, delayMs)` + `Modifier.dockRise()` |
| 2 | `ui/component/Card.kt` | 包边之外加两件装饰：**卡片投影**（三圈逐级放大的实心超椭圆 1.5/3/5.5dp，越外越淡；不用 `Modifier.shadow` 的 RenderEffect 模糊——SDK 31+ 且每卡一层离屏缓冲，滚动掉帧）与**顶部玻璃高光**（裁剪到同一张 squircle 路径的竖直渐变，0 → 45% 高度归零）；画序 = 投影 → 内容 → 高光 → 包边 |
| 3 | `ui/MainActivity.kt` | ① `splashVisible` 拆成"在屏"与"已退场"（`Gate`），splash 开始淡出即开闸；② `when(selectedTab)` 换成 `AnimatedContent`（淡入 280ms + 上浮 300ms(tween) / 淡出 140ms）；③ 给每个 tab 的内容提供独立 `EntranceState`（切 tab 重播进场） |
| 4 | `ui/splash/HonorSplash.kt` | 新增 `onLeaving: () -> Unit = {}`，`leaving = true` 时回调一次（开闸时机 = 淡出起点） |
| 5 | `ui/screen/HomeScreen.kt` | 两张卡挂 `cardEntrance`（order 0/1）；`InfoRow` 挂 `revealFade`（逐行浮现） |
| 6 | `ui/screen/FeaturesScreen.kt` | 头部两张卡 + 网格按累计序号挂 `cardEntrance`（key 用包名，两组序号不撞）；`FeatureCardRow` / `FeatureGridCard` 加 `modifier` / `startIndex`；总览胶囊行挂 `revealFade(240ms)`；目标详情页与安全类页的头部卡、逐条 Row 也挂序号 |
| 7 | `ui/screen/ToolboxScreen.kt` | 6 个磁贴手写 0..5（3 个硬编码 Row 无循环）；`AdsGroupCard` 序号 6；`HubTiles` 加 `startIndex` |
| 8 | `ui/screen/SettingsScreen.kt` | 3 张固定卡序号 0/1/2 |
| 9 | `ui/screen/ThemeSettingsScreen.kt` | 6 张固定卡 + 预览卡序号（0..6） |
| 9b | `ui/component/motion/Entrance.kt`（动效性格） | 新增 `EntranceMotion`（`Glide` 默认 / `Snap` 吸附）：同族不同味，只动位移倍率、起始缩放、弹簧阻尼、错峰四个旋钮，不引入新运动类型。工具箱磁贴改 `Snap`（上浮 28dp、0.69→1、阻尼 0.46 过冲、错峰 34ms），与功能页「左右 210dp 合拢」区分开——用户 2026-11-08 反馈两页动效一模一样 |
| 10 | `ui/component/Card.kt`（触感） | 可点击卡片点击/长按给一档轻震：`SegmentTick`（"嗒"，不是短震动）/ `LongPress`；自挂 `Modifier.clickable` 的磁贴改走新扩展 `Modifier.cardClick()`，两条路径手感一致。**按下不震**（列表滚动会一路嗡鸣），只在手势判定为点击后震 |
| 11 | `ui/MainActivity.kt`（底栏） | `HonorHookBottomBar` 挂 `Modifier.dockRise()`：整条从下方 26dp 升起 + 淡入（520ms，比卡片晚 120ms） |
| 12 | `docs/plans/2026-11-08-ui-overhaul.md`（本文件） | 变更清单 / 观感参数 / 验证清单 |

**不动**：`FloatingBottomBar.kt`、`component/liquid/*`、`InteractiveHighlight.kt`、`DampedDragAnimation.kt`、
`GardenBackdrop.kt`、`Fireflies.kt`、`FeatureCatalog` / hook 侧、任何倍率/版本文件。

## 观感参数（`Entrance.kt` 里的 EntranceTokens）

- 位移（四边）：下 `+190dp` / 上 `-150dp` / 左 `-210dp` / 右 `+210dp`（侧入额外上抬 18dp）
- 倾斜/缩放：下 `±2.8°` + `0.88f`；侧入 `±5.5°` + `0.92f`；上落 `∓1.4°` + `0.93f`；落位一律 1f（弹簧 `dampingRatio 0.76 / stiffness 210`，有轻微过冲）
- 横向扇开（仅底部入场）：奇偶卡 `±40dp`
- 错峰：`62ms/张`，单屏封顶 `400ms`（列表再长也不会等到"最后一张"）
- 内层浮现：`revealFade` 8dp 上移 + 360ms 淡入，`baseDelay 160ms` + `42ms/行`
- 底栏落座：`dockRise` 26dp 上浮 + 520ms 淡入，比卡片晚 120ms
- 触感：点击 `HapticFeedbackType.SegmentTick`、长按 `LongPress`；系统关掉触感反馈时静默
- 冷启动：首帧只画 splash，`AppRoot` 等 splash 上屏后再挂（`withFrameNanos` + 64ms），白屏窗口缩到最短；系统启动画面仍是纯白（`values/colors.xml` 的 `splash_bg`），因为 Android 12+ 只认纯色
- 整数序号是稳定 key（`order` 当 key），同一 `EntranceState` 内只播一次；LazyColumn 回收后不再重播

## 验证清单（用户自执行）

1. 冷启动：splash 淡出中首页状态卡**从上方落下**、InfoCard 自下托起，随后 InfoCard 五行逐行浮现；功能页第一张卡下落、网格每行两张**从左右合拢**；工具箱三行磁贴同理。
2. 切换四个 tab：每个 tab 的内容都重新"生长"一次（底栏不动）；冷启动时底栏比内容晚一拍升起，玻璃折射正常。
3. 二级页（功能页点卡片、工具箱子页、设置-关于/主题）：进入后卡片有进场，返回不闪。
4. 列表滚动：滚出屏幕再滚回来的卡片**不再重播**（不闪烁）。
5. 深浅色 / AMOLED 各看一遍：投影在浅色下是极淡的绿灰，在纯黑下不可见但无脏边；顶高光不压字。
6. 关闭「花园皮肤」「萤火虫」后进场仍正常（进场不依赖这两个开关）。
7. 触感：点功能网格卡 / 工具箱磁贴，指尖有一声很轻的"嗒"；**滚动列表时手压着卡片滚动不应震**；长按有更明显的按住震动。系统设置里关掉触感后应完全静音。
