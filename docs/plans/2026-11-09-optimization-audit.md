# 2026-11-09 · 全维度体检与优化（审计汇总 + 首轮修复）

> 触发：用户 2026-11-08 晚要求「派并发子任务做全维度优化调研，严格按 devflow 流程」，随后授权自助执行
> （原话：「我要睡了，太困了。自助执行，」）。池台账：`docs/pool/pool.db` need `#1`。
> 七面只读审计（文档 / 依赖与注释 / 性能 / 设计架构 / 历史包袱 / 业务流程 / 冗余）已全部返货，
> 本文件是**去重归并后的唯一结论**；每条都标了「已修」或「待办」，行号以 2026-11-09 00:20 工作区为准。
> 审计原文留在子代理会话里，本文件只留判据与结论（细节需复现时按文件:行回读代码）。

## 一、已修（本轮，提交见 `git log`）

| 类别 | 修复 | 依据 |
|---|---|---|
| 性能 | `Fireflies.kt` 绘制循环不再每帧每只 `new Path`/`Rect`/两个 `floatArrayOf`：共用一条 `Path` + `shapePathInto(...rewind())`，光晕系数提到文件级常量 | 性能审计 P0-②（≈120 次分配/帧） |
| 性能 | `SystemUiHook.findByEntryName` 的 `getIdentifier` 只解析一次，递归改用 id 比较（新增 `findByViewId`） | 性能审计 P0-④（每节点 0.5–2ms） |
| 性能 | `StatusBarIconFeature` 新增 `resId → entry 名` 缓存（上限 512），`getResourceEntryName` 不再每次刷新都查资源表 | 性能审计 P0-⑤ |
| 性能 | `HonorSplash` 用 `derivedStateOf` 包住 `enter.value > 0.85f`，不再每次动画帧重组整页 | 性能审计中优 |
| 可靠性 | 断连时拨开关记入 `FeatureStore.pendingKeys`，`FrameworkState.onServiceBind` 后 `flushPending()` 补下发；`pushAllToRemote` 异常分支返回 `connected=false`（原「已同步 0/N」假成功） | 业务流程 #1/#12、架构 #2/#3、历史包袱 #15 |
| 安全 | MFA「备份」由一键导出改为**先确认**，弹窗明示「明文 JSON 会落到公共 Download，任何应用可读」 | 业务流程 #9 |
| 安全 | 工具箱确认框正文按动作类型生成：只有冻结才承诺「自动备份、随时可恢复」，开关批次改为「写入系统设置、模块内不提供一键恢复」 | 业务流程 #11、#4（文案侧） |
| 依赖 | 删 `hiddenApiBypass`、`backdrop` 两条零引用目录项与 MaterialKolor 残留注释；`settings.gradle.kts` 去掉与 `mavenCentral()` 同 URL 的重复 `maven{}` | 依赖审计、冗余审计 #17/#19 |
| 历史包袱 | 删 `fireflyLight` 全套死开关（key/state/getter/setter/加载）；`ApkInstaller` 去掉 minSdk=26 下恒真的 `>= O` 条件；`GardenBackdrop` 删 `amoled` 死参数与 `dim` 缩放链 | 历史包袱 #4/#5/#6 |
| 注释纠偏 | `Theme.kt`「支持 Monet」、`Card.kt`「Monet 关闭+关键色 0」、`ThemeStoreHook`「46 项」、`InstallerHook` 版本清单副本、`HostVersion`「不碰 hidden 成员」、`Card.kt`「淡绿包边/40 多处」、`ArtworkBackdrop` 2dp/16dp、`MainActivity` AMOLED 残留、`GenshinIcons` 消息号 | 依赖/注释审计、历史包袱 #7、架构 #24 |
| 文档 | `docs/README.md` 文件计数 117→115（51 参考 + 58 脚本 + 3 根文件）、目标总表 13→15 行、索引补 7 篇；`docs/reverse/README.md` 补应用市场/运动健康两行、去掉已失效的「§八」引用；`sogou-ime.md` 6 点→12 点；`lsposed-ui-spec.md` Monet→固定色板；`plans/2026-11-08-ui-overhaul.md` 加「已删条目」快照注记；`.planning/codebase/*` 7 篇加生成物告警；`AGENTS.md` 去掉对 README 已删句子的引用 | 文档审计（部分项已在早前轮次修掉，此处只补剩余） |

## 二、按优先级排的待办（下一轮）

### P0 — 用户可见的「假反馈」，但改动面大，需真机回归
1. **挂载判据没有 App 内出口**（业务流程 #2、架构根因）：`installed N/M` 只进 LSPosed 日志。建议 hook 侧把每包汇总写回（本地文件 / 远端 prefs 的一个 JSON key），首页区分「已连接」与「已挂载」，目标卡显示 N/M。**这是所有「看起来开着其实没挂上」的总根因。**
2. **作用域对账缺失**（#5）：UI 不读 `scope.list`，被移出作用域的目标仍显示「生效」。取不到 scope 就退化为常驻提示，不要用绿 chip 断言。
3. **重装后本地/远端无对账**（#15、架构 #1）：本轮只补了「断连脏键补发」；重装场景仍是本地覆盖远端。建议 `onServiceBind` 时对「本地没写过的 key」采用远端值。
4. **临时 Root 一键执行内核提权**（#3）：执行前无确认、无能力探测、无中止。至少加风险确认弹窗。
5. **冻结快照没有恢复入口**（#4）：`SnapshotStore.latest/all` 全仓无调用。要么加恢复页，要么把「随时可恢复」改成「快照仅供 root 手动恢复」。

### P1 — 结构性收敛（成本中，收益大）
6. **版本集三源**（架构 #5）：`FeatureCatalog.extraVersions`、hook 内 `Table(setOf(...))` 字面量、表类名各编码一遍。UI 绿标与 hook 实际分发口径不一致，OTA 后容易误判。
7. **HookPoint→Task 适配器字段丢失**（架构 #4）：Ouc/Sysmgr/AppMarket 丢掉 `dexStrings`/`value`/`forceArgIndex`，表里配了锚点但不生效 → 混淆点永远 `NOT_RESOLVED`。建议收敛到 core 的统一 `toTask()`，或给每个适配器加字段覆盖单测。
8. **注册链路无护栏**（架构 #14/#20）：新增 TargetHook 有 8–10 处落点，只有 strings 缺失会编译报错。建议加反射测试：每个 `TargetHook` 实现类必须出现在 `TargetRegistry` 且包名在 `scope.list`。
9. **首页性能**：`layerBackdrop` 录制层 + 每帧变值的萤火虫 = 每 vsync 全屏离屏重录。方案：萤火虫移出 backdrop 录制范围，或降帧（`withFrameNanos` 限 30fps），或萤火虫关掉时跳过录制。**需真机电量/掉帧对比。**
10. **FileOrganizer 组合期 `stat`**（性能 P0-③）：逐行 `stat` 移出组合（`produceState`/`derivedStateOf`），首屏 8–40ms 可省。

### P2 — 文档 / 结构整理
11. 死代码删除（冗余审计已逐条取证，约 250 行）：`HonorQinxuanHook.mountOne/mountCardProbe`、`ImeHook.rewriteFileAdds`、`FileOrganizer` 三个被内联副本取代的 private fun 与 `listRuns/readManifest/skippedConflict`、`XhsVideoSave.takeoverActive`、`OtaSentinel.shouldWarn`、`ToolQueue.historyTail`、`GhostLockRunner.managers`、`XhsImageUrl.toOriginal`、`tools/wp-set.sh`（88 行零引用）、根 `app/build.gradle.kts` 无 `src/` 却配 kotlin.jvm（**动根构建脚本前先跑一次全构建**）。
12. XHS 弹窗 UI 复制 8 份（约 150–200 行）→ 抽 `XhsDialogUi`；XHS 挂载探针两处逐字重复 → 抽 `XhsMountProbe(prefix)`。**改动需真机 `installed N/M` 回归，勿与其它改动同批。**
13. 版本套清理（需发布渠道版本分布佐证）：installer `14.0.22.326`（129 行、从无真机判据）、theme `20.1.65.302`、`67.302`（只活 4 天，删代价 = 1 个 VIP 点）。
14. `MiHealthHook.kt`（332 行）+ `KEY_MIH_FACE_FREE`：有意留档的死代码，需 owner 决定保留或删。
15. `FeatureCatalog` hidden 壳的 3 条不可见文案、`values-night/themes.xml` 与 values 完全同构（删前需真机看冷启动闪屏）。

## 三、验证清单（用户自执行）

1. 点开 App：首页正常、卡片进场、萤火虫仍在动（颜色随插画开关切换）。
2. 拉下通知栏看一眼是否有掉帧；对比「关掉插画背景/萤火虫」后的流畅度差异。
3. 功能页拨一个开关 → 杀进程重开 App → 开关保持；若当时 LSPosed 未激活，重连后应自动补下发（Logcat tag `HonorHook` 里有一行「补发未下发的开关 N 项」）。
4. 工具箱 → 任意批量操作：确认框文案应只描述该批次的真实后果（冻结才提备份）。
5. MFA 页点备份图标：应弹明文风险确认，取消后不产生文件。
6. 主题设置页：四个卡（明暗 / 玻璃与底栏 / 萤火虫 / 背景）进场正常，无卡中卡。
7. `bash tools/dev.sh test` 全绿；`tools/dev.sh hook-status` 与昨日相比无新增失败。

## 四、流程记录（devflow）

- 定级：P1（跨面优化，无线上故障）。
- 七面审计为**用户明确要求的并发调研波次**（pool note 已记），属并发上限豁免的一次性调研；实现阶段默认串行。
- 需求池：`docs/pool/pool.db` need `#1`「HonorHook 全维度体检与优化」。
- 子任务简报未按 devflow 三段头（已证事实/预算/验收）书写——**这是本轮已知偏差**，下一轮派单必须补齐。
