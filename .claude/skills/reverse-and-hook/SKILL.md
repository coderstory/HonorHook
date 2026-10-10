---
name: reverse-and-hook
description: 逆向分析安卓 app（dex/so/资源）并 hook 实现内存级功能修改（UI 与业务）的端到端方法论。当需要对目标 app 逆向、定位 hook 点、实现 UI 隐藏/替换/弹窗拦截或业务功能改动时使用。涵盖 dex/so/资源逆向、hook 点定位、UI 级与业务级 hook 手法、版本适配、真机验证。
metadata:
  author: 项目沉淀
  last-updated: '2026-10-10'
  keywords:
  - 逆向
  - hook
  - 功能修改
  - UI
  - 业务
  - dex
  - so
  - 资源
  - 内存级
---

> 项目级实战判据（选点 / 死点 / 验证铁律）：[`docs/hook-craft.md`](../../../docs/hook-craft.md)。

## 适用场景

端到端跑通"逆向 → 定位 → hook 实现内存级功能修改"时的**总览与手法对照**。
**细节各自归位**：反编译/反混淆 → `reverse-decompile`；读 smali/伪代码/定位手段 → `static-analysis`；API 与选点原则 → `lsposed-hook-expert`；本项目判据与命令 → `honorhook-dev`；项目级坑与铁律 → `docs/hook-craft.md`、`docs/reverse-craft.md`。

## 流程（六步，每步看对应 skill）

1. **拿包**：`adb pull` / 已有 apk；加固壳先脱壳（frida-dexdump / BlackDex / 内存 dump）；DEX 虚拟化双壳（classes.dex 整体加密、运行时纯内存解密无落盘）⇒ 静态+落盘双死路，只能运行时 dump。
2. **反编译**：`tools/dev.sh decompile <apk>` / `jadx <apk>`（见 `reverse-decompile`）。
3. **定位 hook 点**：字符串/资源名反查、签名定位重载、继承链找收口点、服务端载荷出口、DexKit 特征、真机取证（见 `static-analysis`）。
4. **实现**：见下表手法；选点原则与死点见 `docs/hook-craft.md`。
5. **版本适配**：特征检测优于版本号；`python3 tools/adapt_new_version.py` 回归；基线版本错位 ⇒ 分析作废重做（判据见 `honorhook-dev`）。
6. **真机验证**：`dev.sh log-hook` / `modlog` 看 `installed N/M`，`dev.sh ui` 看界面。

## 手法对照（本项目与参考项目实证）

hook = 运行时内存修改，**不改 apk**；app 重启后重新 hook。

**UI 改动**

| 目标 | 手法 | 实践来源 |
|---|---|---|
| 隐藏广告/入口 View | hook `setVisibility` 改 GONE；或构造 / `onAttachedToWindow` 后 GONE | fanqie-purify |
| 隐藏整块区域 | 收掉子 View + 折叠空父容器 | fanqie-purify「我的」页 |
| 拦截弹窗 | hook `android.app.Dialog.show()` + 内容判定（扫 content-desc + text，不只 TextView） | fanqie-purify 收口点 |
| 文案过滤 | hook `TextView.setText` 按文案规则过滤 | fanqie-purify |
| 悬浮窗拦截 | hook `WindowManagerGlobal.addView`（按窗口类型过滤；真正护栏是文案特异性，别只靠 type） | fanqie-purify 兜底 |
| 异步渲染的弹层 | 多点重扫（0/300/800/1600/2800ms） | fanqie-purify（Lynx 异步） |
| 替换布局 | hook `setContentView` / `LayoutInflater.inflate` | — |

**业务改动**

| 目标 | 手法 | 实践来源 |
|---|---|---|
| 关广告请求 | hook 请求前置条件门返回 false（无闪烁，优于逐 View GONE） | fanqie-purify 广告位总闸 |
| 关功能 | 只短路 **void** 重载（播放/执行类），**不碰 boolean/String 策略查询**（会改状态机，反而每次重请求） | fanqie-purify AudioHelper |
| 改本地状态 | 改判定数据源返回值 / 字段（**服务端校验绕不过**） | 本项目主题商店 / exam8 |
| 服务端下发内容 | 在"响应 → 域模型"的唯一出口裁剪（不在消费方逐个隐藏） | 本项目槽位/底 tab 裁剪 |

## 限制

- **服务端校验绕不过**：会员权益等由服务端判定，hook 只改本地状态（本项目 exam8 实证：UI 门可过、正文取不到）。
- **加固壳需脱壳**；**native 逻辑需 frida/IDA**（非 Xposed Java 层）。
- **内存级不持久**：app 重启重新 hook；改包固化是另一条路（见 `.claude/skills/apk-reverse/SKILL.md`）。
- **静态点 ≠ 运行时生效点**：挂之前数消费点数、确认可达（详见 `static-analysis` 与 `docs/hook-craft.md`）。
