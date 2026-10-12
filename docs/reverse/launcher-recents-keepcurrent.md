# 最近任务「清后台保留当前 app」— 逆向分析与方案（待实现）

> 需求：垃圾桶清后台时，保留清后台前的前台 app（热回原任务，不杀不冷启）。
> 拍板（2026-10-12）：**热保留** + **仅垃圾桶按钮生效**。
> 状态：**纯调研，未写任何 hook 代码，未真机验证**。

## 一、真机链路实证（launcher 18.0.0.234，AAK-AN00 MagicOS）

垃圾桶按钮 UI 实证：UI dump `.buildtmp/ui_20261012-223550.xml`，
`resource-id=com.hihonor.android.launcher:id/clear_all_recents_image_button`，
容器 `recents_bottom_area`（overview_panel2_container 内）。

点击链（jadx，`HnLauncher6.apk` classes.dex，类名方法名全部未混淆）：

```
ClearAllButton onClick
  → RecentsView.dismissAllTasks(View)                    com/android/quickstep/views/RecentsView.java:3717
    → createAllTasksDismissAnimation(300)                 // 卡片飞走动画（:3604）
      → 动画 onEnd
        → finishRecentsAnimation(true,false)              // :3621 lambda$createAllTasksDismissAnimation$29
        → ActivityManagerWrapperEx.removeAllRecentTasks() // ★ 全杀收口 :3634（UI_HELPER_EXECUTOR）
        → removeTasksViewsAndClearAllButton()
        → RecentsView.startHome()                         // ★ 回桌面 :3645
```

关键事实：

1. **kill 收口无参**：`ActivityManagerWrapper#removeAllRecentTasks()`
   （`com.android.systemui.shared.system.ActivityManagerWrapper`，在
   `/system/framework/HnSystemUISharedLib.jar`，已拉取 `.buildtmp/HnSystemUISharedLib.jar`），
   最终 `ActivityTaskManager.getService().removeAllVisibleRecentTasks()`（Honor 扩展 binder，无排除参数）。
   ⇒ 过滤只能在调用侧（launcher 进程）做。
2. **回 app 现成 API**：`ActivityManagerWrapper.startActivityFromRecents(taskId, options)`
   → ATMS.startActivityFromRecents —— recents 点卡片回 app 就是它，launcher 进程有权限，不需新造启动逻辑。
3. **保留对象**：`RecentsView.getRunningTaskView()`（:2779）/ `mRunningTaskViewId`
   —— quickstep 原生维护"进 overview 前的前台任务"，语义正好是需求里的"当前 app"。
4. **system_server 侧过滤死路**：api 102 fork 无 `onSystemServerLoaded`（docs/reverse/README.md §三 已判"放弃勿重评"）。
5. **单卡划走** 走 `removeTask(taskId)` → `ATMS.removeTask(taskId)`（ActivityManagerWrapperEx.java:87），
   与垃圾桶路径不同入口，不受本方案影响。

## 二、方案（3 hook 点，全直连轨，零 DexKit）

| # | 挂点 | 位置 | 行为 |
|---|---|---|---|
| 1 | `RecentsView#dismissAllTasks(View)` | launcher base | 进 hook 记 runningTaskId 到模块侧 flag（垃圾桶专属入口，全局仅 `$$ExternalSyntheticLambda20` 一个调用方） |
| 2 | `ActivityManagerWrapper#removeAllRecentTasks()` | HnSystemUISharedLib（framework jar，launcher 同 classloader） | flag 有效时替换：除保留 id 外逐个 `removeTask(id)`；flag 无效透传原逻辑 |
| 3 | `RecentsView#startHome()` | launcher base | flag 有效时替换为 `startActivityFromRecents(保留id, null)`，成功后清 flag；失败/无效回落原 startHome |

### 门控与边界（实现必读）

- **flag 生命周期**：#1 置位 → #2/#3 消费后清。`RecentsView.startHome()` 还有别的调用方
  （点 overview 空白处回桌面，RecentsView.java:1674/1676）——无 flag 一律透传，不影响原行为。
  异常路径（动画被中断、flag 残留）需要兜底清（如 #3 消费即清 + 超时清）。
- **`startHome()` 挂点选择**：`HnRecentsView`（abstract）无无参 `startHome()` override，
  concrete 只有 `RecentsView.startHome()` 一处（:2696），挂它即可。
- **保留 id 取法**：`getRunningTaskView().getTaskViewId()`/task id，避免反射字段
  `mRunningTaskViewId`（方法比字段稳）。具体 getter 由实现子任务从反编译产物再核对。
- **锁定任务**：`removeAllVisibleRecentTasks()` 原生语义未逆向（可能豁免 locked task）；
  逐个 `removeTask` 会绕过。实现时须过滤 `RecentTaskInfo` locked/不可移除项，真机验证。
- **回落路径**：保留 id 无效（task 已死/分身异常）→ 原逻辑全杀 + 回桌面，功能静默降级。

## 三、风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| shlib/quickstep 类名跨 MagicOS 大版本混淆 | 中 | 现基线 kept（keep 名单）；可配 DexKit 兜底锚点：`removeAllVisibleRecentTasks` 调用特征 + `clear_all_recents_image_button` 资源 id |
| 逐个 removeTask 与原生全杀语义差异（动画/窗口清理） | 中 | 与单卡划走同 API，行为可预期；真机验证观感 |
| 锁定任务被误杀 | 中 | 过滤 locked + 验证清单覆盖 |
| `removeAllRecentTasks` 还有其他调用方被误过滤 | 低 | flag 只在垃圾桶 flow 置位；grep 全调用方见 §二 |
| 分身/多用户 task id | 低 | task id 全局唯一；验证清单覆盖 |

## 四、实现归属

- FeatureCatalog：launcher 目标下新 key（`launcher.recents_keep_current`，默认开），UI 文案两条（title/desc）。
- Hook 侧：`LauncherHook` 目标内新挂载任务（shlib 类属 framework classloader，launcher 进程内直挂）。
- 按项目纪律：编码派独立子任务，先出变更清单。

## 五、验证清单（用户自执行，AI 不代测）

1. QQ 前台 → 上滑进 recents → 垃圾桶：其他卡消失，**自动回到 QQ 原界面**（热，非冷启）。
2. 仅有 QQ 一个任务时点垃圾桶：不杀、回 QQ。
3. 点 overview 空白处回桌面：行为不变（startHome 透传）。
4. 单卡逐个划走（侧滑删卡）：行为不变。
5. 三指清后台（若设备支持）：行为不变（仅垃圾桶生效）。
6. 锁定任务（卡片锁图标）存在时点垃圾桶：锁定卡是否豁免。
7. 分身应用（微信/QQ 分身）任务存在时点垃圾桶：正常清、保留逻辑不串。
8. 判据：modlog `installed N/M` 新挂载点全绿 + 上述场景观察。
