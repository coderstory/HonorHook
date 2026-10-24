# 43 号验证剧本执行结果汇总（2026-10-24 深夜一轮）

执行环境：HONOR AAK-AN00 / MagicOS 11.0.0.109 / KSU 临时 root 在场。
流程：43 号剧本 §1 单 root 会话「改→回读→取证→回滚」；全程 resetprop -n（普通模式 `__system_property_set failed: -1`，msc.* 疑 local.prop 只读区）。
**终态回归：19 个 prop 全部回基线（含 --delete 清空值项），零 FC，零设备异常。**

## 结果分布

| 分类 | 数量 | 说明 |
|---|---|---|
| verified | 0 | — |
| no_effect | 14 | 全部 readback 通过但 UI 无变化 |
| rewritten | 0 | — |
| fc | 0 | 相机组 7 门全过无崩溃 |
| untestable | 3 | gesture_snapshot / dp_pc(缺硬件) / bt_a2dp(缺设备) |

## 两个结构性发现

1. **B 层 msc.\* resetprop 门在本机全数 no_effect**（`-n` 直写成功回读 ≠ 生效，读取面被
   CustomConfigurationUtil 缓存或另有门）。这**实证支持**了 28 号规格书把 resetprop 会话层
   全数排除出 ToolSwitchTable 的决定；未来若要开 B 层能力，路线是 hook
   `getSystemProperty`（24 号 C 层判定），不是 resetprop。
2. **5G 的唯一控制面是 UI 开关**：开发者选项「显示 5G 设置开关」关 + 双 prop=true →
   移动数据页「启用 5G」消失（A/B 定案，截图证据含个人信息留设备侧）。该开关即
   44 号 HwSystemManager/NV 通道结论，与 33 号 A 层 show_5g_switch 对应。

## 产物

- `verdict43.jsonl` 17 行（P1×3 / P2×3 / P3×4 / P4×7）
- `evidence/` 11 张无个人信息截图；含 SIM 号截图只留设备侧
  `/storage/emulated/0/project/research/verification/`（HANDOFF §六）
- P4 二轮候选：进相机设置页核对 livephoto/video_movie_mode/gesture 的设置内子项（判据面收窄假设）
- 未执行：P5 opta/optb 总门（高危，单独会话）与协同档位组（同）；dual.video_capbility_switch / 4k_video_limit_duration（高危相机）
