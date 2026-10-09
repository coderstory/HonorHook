# docs 文档索引

> 建档：2026-10-09 · 共 14 份（ls 实际清单），按类排序；新文档请在本表追加并更新计数。

## 逆向分析类（9）

| 文件 | 用途 | 何时读 | 关键结论一句话 |
|------|------|--------|----------------|
| `rain-analysis.md` | Rain_1.1.5.apk 逆向留档（基本信息/META-INF 三配置/strings 核对） | 需要 libxposed 102 参考基线与配置写法时 | Rain 是单入口 + java_init.list/module.prop/scope.list 三配置文件的标准 libxposed 102 模块样张 |
| `rain-hook-architecture.md` | Rain hook 架构分析（入口分发/DexKit 双轨/字符串解密） | 写 hook 分发或借鉴 DexKit 双轨解析时 | 单入口 HookEntry + 接口化 per-app 适配器 + 静态注册表 + 直连反射优先/DexKit 兜底双轨解析 |
| `rain-ui-layout.md` | Rain UI 精确结构图纸（导航结构/组件布局/strings） | HonorHook UI 对齐 Rain 时 | Rain 是 Compose + 混淆，组件名不可恢复，语义靠调用模式 + strings 推断（已标注） |
| `hc-hook-structure.md` | HyperChanger 工程组织分析（入口/分发/UI↔hook 远程 prefs 通信） | 拼装 HonorHook 入口/分发结构时（直接模板） | 单类 HyperSystemUiModule 承载全部 target：包名 if 前置过滤 + when 分发 + xxxInstalled 防重装 |
| `honor-theme-payment-analysis.md` | 荣耀主题付费机制逆向（引擎+商店两 apk 的 hook 点清单） | 主题模块相关 hook 开发前必读 | 5 组 30 个 hook 点全部 file:line 实证（含 B3 过期时间链），是主题模块 v1 的实现依据 |
| `theme-download-chain.md` | 付费主题下载卡 0% 的下载链路反查与卡点定位 | 主题下载/免费化相关 hook 开发时 | 卡点 = 下载 URL 获取：付费主题服务端不在详情 JSON 下发可用直链，真实 URL 只在 sendProduct 授权接口返回 |
| `theme-ui-real-chain.md` | 主题商店 UI 真实渲染链反查（详情页按钮/VIP tab/新用户专享） | hook 详情页按钮真机无效时必读 | isBuyBtnShow 是"门"非"开关"，真实链 = 门 + `IncentiveData.getThemeDetailButtonType()` → updateButtons switch；fetchShouldDialogBy* 消费链是休眠代码 |
| `honor-system-preliminary.md` | 荣耀 MagicOS 系统组件包名预研（桌面调研） | 规划新 hook 目标包/组件时 | 包名清单来自公开网页社区实证（多标注 AI 生成未人工审计），最终以真机为准 |
| `honor-plugins-integration.md` | 荣耀三插件（MarketTamer/InstallerPurify/LiquidGlassRestore）整合方案 | 整合/移植三插件功能时 | InstallerPurify 8 组 hook 全命中可整体迁移；MarketTamer 逻辑直移 + 配置机制大砍；README"降级安装"代码不存在不迁移 |

## 调研类（2）

| 文件 | 用途 | 何时读 | 关键结论一句话 |
|------|------|--------|----------------|
| `ai-lsposed-temlate-report.md` | AI-lsposed-temlate 模板项目分析报告 | 吸收工程模板/.cursor AI 规则时 | libxposed 101 脚手架（非实际插件），两份 AI 规则（API 速查 + Classic API 禁用清单）值得吸收 |
| `ui-template-research.md` | miuix UI 模板调研（LSPosed manager + 三插件 + Rain） | app UI 扩展/新页面设计时 | 结构与风格参考 LSPosed manager，技术底座 miuix + Compose，结论均已实证（file:line） |

## 需求与整合类（1）

| 文件 | 用途 | 何时读 | 关键结论一句话 |
|------|------|--------|----------------|
| `honorhook-theme-requirements.md` | HonorHook 主题模块 v1 功能需求 | 主题模块任何开发/审计/验收前 | F1 开 VIP / F2 全免费 / F3 试用无倒计时 / F4 任何场景不恢复默认（2026-10-09 用户确认） |

## 审计类（2）

| 文件 | 用途 | 何时读 | 关键结论一句话 |
|------|------|--------|----------------|
| `gap-analysis.md` | HonorHook vs 7 个参考项目的 Gap 分析 | 评审/优先级排期/范围核对前 | 逐项差距 + 修复建议与工作量（注册/scope/manifest/queries/DexKit 双轨/版本漂移防护等），方法为源码抽查实证 |
| `skill-audit-fixes.md` | 4 个 project skill 技术错误修正记录 | 改 skill 文件前先读 | 每条修正落盘前均本机实证（读实码/查文件/跑命令），含 jadx 主命令/版本无关/收口点/DexKit 教训，防止反向修改 |