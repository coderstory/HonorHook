# 最近任务「清后台保留当前 app」— 逆向分析与方案（荣耀 ClearBox 链路，已重构实现）

> 需求：垃圾桶清后台时，保留清后台前的前台 app（热回原任务，不杀不冷启）。
> 拍板（2026-10-12）：**热保留** + **仅垃圾桶按钮生效**。
> 状态：**已实现**。v1（1013c45）挂 quickstep 三点 K1/K2/K3——**真机实测全落空，已删**
> （§一·1）；2026-10-14 重构为荣耀 ClearBox 链路 N1/N2 两点 + A/B 互斥开关（本 commit，§四）。

## 一、真机链路实证（launcher 18.0.0.234，AAK-AN00 MagicOS）

### 1. quickstep 链路作废声明

v1 方案假设的链路（`RecentsView.dismissAllTasks` → `removeAllRecentTasks` → `startHome`）
在荣耀上**永不触发**：真机 dump `.buildtmp/ui_rc.xml` 中垃圾桶按钮
`resource-id=com.hihonor.android.launcher:id/clear_all_recents_image_button`（ClearBox 布局），
listener 绑定在 `HnRecentsClearBox`；quickstep 侧 `overview_clear_all_button_launcher3` 计数 **0**
（不在视图树，AOSP 原生垃圾桶入口被荣耀替换）。故 K1（`RecentsView.dismissAllTasks`）、
K2（`ActivityManagerWrapperEx.removeAllRecentTasks`）、K3（`RecentsView.startHome`）三点
hook 全部删除。

### 2. 荣耀真实链路（反编译直证，行号均在 jadx 产物内核对）

UI 实证：`.buildtmp/ui_rc.xml`，`clear_all_recents_image_button` 在视图树（ClearBox 布局）。

```
HnRecentsClearBox.onClick(View)                          HnRecentsClearBox.java:406（public @Override，
  → startRemoveTask()                                    :841   onFinishInflate :366-374 绑定 = 唯一 listener）
    → 内部类 RemoveTaskThread.run()
      → removeTaskProcess()
        → ActivityManagerWrapperEx.removeHwAllTask()      ActivityManagerWrapperEx.java:124（shlib，public 无参 void）
          → HwRecentsTaskUtils.sendRemoveAllTask(context) 发广播给 com.hihonor.systemmanager
        → HnRecentsClearBox.sCountDownLatch.await(20000)  latch public static :158，初始 count=1；
                                                           唯一 countDown 在管家「清后台回执」广播 receiver :174
```

shlib 辅证（`.buildtmp/shlib-jadx`，HnSystemUISharedLib）：

1. **单卡划走通道**：`ActivityManagerWrapperEx.removeTask(int)`（:87）→ 基类 :172 → ATMS binder。
2. **回 app 通道**：`ActivityManagerWrapperEx.startActivityFromRecents(int, ActivityOptions)`（:79）
   → 基类 :142 → ATMS binder，返回 boolean——recents 点卡片回 app 同一条路。
3. **单任务广播**：`HwRecentsTaskUtils.sendRemoveTaskToSystemManager(Context, Task)`（:178，
   public static；Task = `com.hihonor.android.systemuisharedlib.systemui.shared.recents.model.Task`，
   从 `TaskView.getTask()` 拿，可能 null）——荣耀原生单卡清卡就是它。
4. **回桌面原生自动**：`HnLauncherRecentsView.onAllTasksRemoved`（:152）→
   `goToState(LauncherState.NORMAL)`，无需 hook。
5. **关键 public 面**：`HnRecentsClearBox.mRecentsView`（:139，public 字段，运行时类型
   `HnLauncherRecentsView`，继承 quickstep `RecentsView`：`getRunningTaskView()` :2779、
   `getTaskViewCount()` :2233、`requireTaskViewAt(int)` :3896 全 public）；
   `TaskView.getTaskIds()`（TaskView.java:575，可能含 -1 要过滤）与 `getTask()`（可能 null）。

## 二、方案（N1/N2 两点 + A/B 互斥开关，全直连轨，零 DexKit）

| # | 挂点 | 位置 | 行为 |
|---|---|---|---|
| N1 | `HnRecentsClearBox#onClick(View)` | launcher base | 垃圾桶捕获：keep = `getRunningTaskView().getTaskIds()` 过滤 -1（quickstep 原生维护"进 overview 前的前台任务"）；kill = 其余卡片 `getTaskIds()` 过滤 -1 且不在 keep 集 + 同卡 `getTask()` 非 null 收 shlib Task 对象（B 路径广播入参）。两开关全关时不捕获（零反射）。 |
| N2 | `ActivityManagerWrapperEx#removeHwAllTask()` | HnSystemUISharedLib | 全杀收口替换：flag 有效时整条替换、不 proceed——A = 逐个 `removeTask(id)`（ATMS，单卡划走同 API）/ B = 逐个 `sendRemoveTaskToSystemManager(Context, Task)` 广播管家；随后反射 countDown `sCountDownLatch`；无效透传原生。 |

**A/B 开关语义**：`launcher.recents_keep_current_atms`（直杀）与
`launcher.recents_keep_current_manager`（管家广播）互斥，**同开时 A 生效**（运行时 WARN 留痕）。
feature 字段留空 = 载体点，两开关全关则零干预。

**latch 放行（必做）**：A 移除与 B 广播都不产生管家那条 REPLY_TRIM_ALL 回执
（原生唯一 countDown 在广播 receiver，:174）⇒ 不手动 countDown 宿主线程卡 `await(20000ms)`。
无论 removed 成败都放行；拿不到 latch 只 WARN（宿主 20s 自行超时，可接受降级）。

**600ms 热回**：dismiss 动画 300ms + `resetAfterDismiss` 收尾（清卡片视图/退出 overview），
太早 `startActivityFromRecents` 会被收尾顶掉。主线程 `Handler.postDelayed(600ms)` 后遍历
保留 id 逐个热回，第一个成功即停；**热回失败不补任何动作**——原生
`onAllTasksRemoved → goToState(NORMAL)` 的回桌面已在跑，回落 = 停在桌面，可接受。

**语义代价**：管家负责的「已清理缓存/释放内存」toast 不再发生（A 完全绕过管家；B 走单任务
通道，管家不聚合播报）。清后台的卡片清理本身不受影响。

**消费模型**：N2 是唯一消费方、单点同步执行（RemoveTaskThread），消费即全清（先接局部变量
再置空字段，热回 600ms 后不读字段）；10s 超时兜底残留脏标记。v1 的「K2/K3 拆分消费」
缺陷修复随 quickstep 链路一并作废。

## 三、风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 热回时序（600ms 不够/过早） | 中 | 首个成功即停 + 失败静默回落桌面；真机验证观感，必要时调 [HOT_RETURN_DELAY_MS] |
| B 广播无回执（管家不回 REPLY_TRIM_ALL） | 中 | N2 主动 countDown latch，不依赖回执；latch 反射失败也只 WARN |
| `onClick` @Override 必保名 | 低 | 覆写 `View.OnClickListener.onClick`，接口方法 R8/宿主不可混淆改名；shlib 类（HnRecentsClearBox 依赖的 ActivityManagerWrapperEx/HwRecentsTaskUtils）全未混淆 |
| 运行卡 Task 被误广播（B） | 低 | N1 只收「贡献了移除 id」的卡的 Task，运行卡（保留集）贡献 0 个移除 id ⇒ Task 不进移除对象集 |
| `removeHwAllTask` 出现其他调用方 | 低 | flag 只在 N1（onClick）置位 + 10s 超时；收口语义上垃圾桶 flow 独占 |
| 锁定任务被误杀 | 中 | 逐个移除绕过原生全杀的锁定豁免（若有）；验证清单覆盖 |
| 分屏合并卡 B 路径杀不全（已知缺口） | 低 | 合并卡一卡双 id，B 只广播 `task.key.id` 一个（A 路径按 ids 全杀不受影响）；分屏场景建议用 A，后续需要时 N1 改为按 Task 逐 id 构造广播 |
| shlib/launcher 类跨 MagicOS 大版本漂移 | 中 | 现基线 18.0.0.234 全 public 直连零混淆面；失效走 `installed N/M` + NOT_RESOLVED 常态兜底 |

## 四、实现归属

- 本 commit（2026-10-14 重构）：删 K1/K2/K3 三点与 `REMOVE_ALL_KEEP_CURRENT`/
  `START_TASK_INSTEAD_HOME` 两 kind；增 `HnRecentsClearBox.onClick`（ARM_KEEP_CURRENT）与
  `ActivityManagerWrapperEx.removeHwAllTask`（REMOVE_HW_ALL_KEEP_CURRENT）两点；
  `LauncherHook` 增 `removeTasksViaManager`/`countDownLatchForRemoveTask`/`postHotReturn`
  三函数与 `killTaskObjs` 字段。
- FeatureCatalog：`launcher.recents_keep_current` 一 key 拆二
  （`launcher.recents_keep_current_atms` / `launcher.recents_keep_current_manager`），
  strings.xml 对应四条文案。
- 历史：v1 三点 1013c45；2026-10-13 K2/K3 拆分消费修复（随链路作废）；本重构依据
  真机 dump + launcher/shlib 双 jadx 产物。

## 五、验证清单（用户自执行，AI 不代测）

1. 判据：modlog `installed 5/5`（launcher 目标五点：L1 + R1/R2 + N1/N2 全绿）。
2. 开 A（直杀）开关：QQ 前台 → 上滑进最近任务 → 点垃圾桶：其他卡消失、**QQ 不退**，
   ~0.6s 后自动热回 QQ 原界面（热，非冷启）；原「已清理」类 toast 不再出现。
3. 关 A 开 B（管家广播）：同场景同预期（逐卡消失，当前 app 保留并热回）。
4. 两开关全关：垃圾桶行为与原生完全一致（全杀 + 回桌面 + 管家 toast 照常）。
5. A+B 同开：行为走 A（直杀），日志有 `RECENTS-KEEP 两开关同开，按直杀(A)执行` WARN。
6. 仅一个任务时点垃圾桶：不杀、正常回该任务（移除集为空 = 仅热回）。
7. 回归：文件夹 R1/R2（从文件夹开应用返回桌面自动重开）与卡片标题 L1 不受影响。
8. 逐卡划走、点 overview 空白回桌面：行为不变（不在这两个挂点的干预范围）。
