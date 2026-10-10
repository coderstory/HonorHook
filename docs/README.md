# docs 文档索引

> 更新：2026-10-10 · 活跃文档 9 份（不含本索引与 archive/）。模块已收敛为**纯 hook 模块（无 UI）**，
> 仅保留**两个目标**：主题商店（`com.hihonor.android.thememanager`）+ 包管理器（`com.android.packageinstaller`）。
> 早期参考项目分析（Rain / HyperChanger / 模板审计 / gap 分析）已移入 `archive/`，需要借模式时再翻。

## 实现设计（1）

| 文件 | 用途 | 何时读 |
|------|------|--------|
| `theme-store-hook-design.md` | **ThemeStoreHook 设计说明**：F1–F4 目标、功能位归组、全部挂载点清单与理由、VIP 落地值、按钮显隐决策点、挂载健壮性与判读方式、不挂的点、哲学审计记录 | 改 `ThemeStoreHook.kt` 前必读；源码内只留指针，理由以此为准 |

## 逆向分析类（7）

| 文件 | 用途 | 何时读 | 关键结论一句话 |
|------|------|--------|----------------|
| `cloud-switches.md` | **主题商店云控开关全表**（AgreeRepo 约 30 项 + 散读 key，含 key/默认/作用/读点；§四 = 本项目已接管清单） | 想"关某功能"前先查有没有云控开关；**加/改强制开关只动 `CloudSwitchTable.kt`** | 云控走 AMS systemParam（前缀 10001000），**唯一读点收口 = `u15.getBoolean`（SharepreferenceUtils）**；本项目挂读点 + 表驱动 |
| `installer-cloud-config.md` | **包管理器云控摸底 + InstallerHook 落地实现**（§二 机制 / §六 落地 7 挂载点） | 要处理/调整安装页广告、安全模式、拦截名单、AB 请求时 | 安装器**无布尔开关表**：云控＝`CommonConfig`（`configVO` key→JSON）+ AB 实验；广告是内容下发型（`AdRespV4.adAppList` 渲染），无 `isShowAd` 总开关 ⇒ 源头拦截点 = 广告请求条件门 `lt1.b` + 响应出口 `getAdAppList` |
| `honor-theme-payment-analysis.md` | 荣耀主题付费机制逆向（引擎+商店两 apk 的 hook 点清单） | 主题模块相关 hook 开发前必读 | 5 组 30 个 hook 点全部 file:line 实证（含 B3 过期时间链），是主题模块 v1 的实现依据 |
| `theme-download-chain.md` | 付费主题下载卡 0% 的下载链路反查与卡点定位 | 主题下载/免费化相关 hook 开发时 | 卡点 = 下载 URL 获取：付费主题服务端不在详情 JSON 下发可用直链，真实 URL 只在 sendProduct 授权接口返回 |
| `theme-ui-real-chain.md` | 主题商店 UI 真实渲染链反查（详情页按钮/VIP tab/新用户专享） | hook 详情页按钮真机无效时必读 | isBuyBtnShow 是"门"非"开关"，真实链 = 门 + `IncentiveData.getThemeDetailButtonType()` → updateButtons switch；fetchShouldDialogBy* 消费链是休眠代码 |
| `launcher-card-title-hide.md` | **系统桌面卡片标题（YOYO建议）隐藏分析**（节点定位 / 渲染链 / 总闸 `z.d()` / 官方「隐藏应用名称」开关 / 4 条 hook 路线 + 插件独立 ClassLoader 约束） | 要做桌面定制、或想隐掉卡片/图标文字时 | 那行文本 = `card_indicator_title`（`HnIndicator2X4StackView`，在 `YOYOCardStackPlugin.apk` 独立 CL）；总闸 = `ThemeUtilImpl.isHiddenTitle()` ← SP `launcher_icon_title_hidden`；推荐 hook 点 `ThemeUtilImpl.isHiddenTitle()`（类名不混淆、只被卡片消费） |
| `rain-analysis.md` | Rain_1.1.5.apk 逆向留档（基本信息/META-INF 三配置/strings 核对） | 需要 libxposed 102 参考基线与配置写法时 | Rain 是单入口 + java_init.list/module.prop/scope.list 三配置文件的标准 libxposed 102 模块样张 |
| `rain-hook-architecture.md` | Rain hook 架构分析（入口分发/DexKit 双轨/字符串解密） | 写 hook 分发或借鉴 DexKit 双轨解析时 | 单入口 HookEntry + 接口化 per-app 适配器 + 静态注册表 + 直连反射优先/DexKit 兜底双轨解析 |

## 参考工程类（1）

| 文件 | 用途 | 何时读 | 关键结论一句话 |
|------|------|--------|----------------|
| `hc-hook-structure.md` | HyperChanger 工程组织分析（入口/分发/UI↔hook 远程 prefs 通信） | 拼装入口/分发结构时（直接模板） | 单类 HyperSystemUiModule 承载全部 target：包名 if 前置过滤 + when 分发 + xxxInstalled 防重装 |

## 需求与方法类（2）

| 文件 | 用途 | 何时读 | 关键结论一句话 |
|------|------|--------|----------------|
| `honorhook-theme-requirements.md` | HonorHook 主题模块 v1 功能需求 | 主题模块任何开发/审计/验收前 | F1 开 VIP / F2 全免费 / F3 试用无倒计时 / F4 任何场景不恢复默认（2026-10-09 用户确认） |
| `hook-philosophy-manifesto.md` | 先验框架宣言（哲学表述版） | 需要架构判断力内化时 | 先验时空/因果锚点/先验律令/现象界适配——与 CLAUDE.md §九 工程条款一一对应 |

## 审计类（4）

| 文件 | 用途 | 何时读 | 关键结论一句话 |
|------|------|--------|----------------|
| `hook-inventory-audit.md` | **全量 hook 台账与冗余审计**（47 个挂载点的存在性/可达性/消费点 + P0-P4 分级 + 清理实证） | **改任何 hook 前必读**；怀疑"某个 hook 是不是多余的"时查此表 | P0 死挂载 1（`TryOut.isSupportTrial` 零调用者）／P1 重复 2（`o52.addDownloadItem` 被 H2b 覆盖、`ItemInfo.isVipResouce` 应改挂 `getVipOnly`）／P2 残留 4（两个调试探针 + 无用 key + 同构 findMethod）／P3 两套挂载基建重复 |
| `gap-analysis.md` | HonorHook vs 参考项目的 Gap 分析 | 评审/优先级排期/范围核对前 | 逐项差距 + 修复建议与工作量（注册/scope/manifest/queries/DexKit 双轨/版本漂移防护等），方法为源码抽查实证 |
| `skill-audit-fixes.md` | 4 个 project skill 技术错误修正记录 | 改 skill 文件前先读 | 每条修正落盘前均本机实证（读实码/查文件/跑命令），含 jadx 主命令/版本无关/收口点/DexKit 教训，防止反向修改 |
| `ai-lsposed-temlate-report.md` | AI-lsposed-temlate 模板项目分析报告 | 吸收工程模板/.cursor AI 规则时 | libxposed 101 脚手架（非实际插件），两份 AI 规则（API 速查 + Classic API 禁用清单）值得吸收 |

## 已清理（2026-10-09）

模块收敛为纯 hook 后删除（`git log --diff-filter=D` 可找回）：

- `lsposed-ui-spec.md` / `rain-ui-layout.md` / `ui-template-research.md` —— UI 现已整体移除；
- `honor-plugins-integration.md` / `honor-system-preliminary.md` —— 市场/安装器/玻璃三目标已删除。
