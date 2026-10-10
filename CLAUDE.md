# CLAUDE.md

> 只写代码里读不到的。类名/方法/挂载点/结构读代码或查 `docs/`。**写得简短**。

## 定位

荣耀 LSPosed 模块（libxposed 102，`top.honorhook`）。hook 侧注入 6 个目标进程（默认全开，只改内存态）；UI 侧 Miuix Compose 面板经远端 prefs 下发开关（写 false 才关，**下次注入才生效**）。

## 先看这里

- 构建 / 安装 / 设备操作 / 反编译 → `tools/dev.sh`（**禁手拼 gradle/adb**）；路径跑 `dev.sh env`；命令索引 skill `honorhook-dev`
- 动手前读 `docs/hook-craft.md`（怎么写 hook）、`docs/reverse-craft.md`（怎么逆向）
- 索引 `docs/README.md`；每 app 逆向记录 `docs/reverse/`
- 判据 = 真机 `installed N/M`（LSPosed 框架日志 `/data/adb/lspd/log/modules_*.log`，不在 logcat）

## 纪律

- 改前出变更清单，只改清单内；不顺手改 bug / 重构 / 删死代码
- 别为压缩行数重构：注释是唯一审计痕迹、`vendored` 件要对齐上游，二者都不压（实测可压空间 2~4%，见 `docs/ui-miuix-overview.md` §压缩边界）
- 注释与文档只写代码读不到的（逆向实证 / 为什么 / 外部约束 / 判据）；长篇进 `docs/`，源码留指针
- 新操作先加 `dev.sh` 子命令再做；子任务只跑定向编译
- 路径全英文

## 设备铁律

- 🚫 **禁 reboot（任何形态）**——需要重启才生效的事，停下告诉用户，等用户自己重启
- 🚫 禁破坏性/难回退操作（恢复出厂、`pm uninstall` 系统应用、擦数据）——先问
- 🚫 不在用户主力机实测未验证 hook 的破坏性效果。AI 交付 = 代码 + 构建通过 + **用户自执行**的验证清单
- 只真机直连；`reference/` 只读

## 环境坑

- 工具链由 `dev.sh` 自解析（项目内优先 → brew/PATH）；**JDK 21 需含 jlink**
- 脚本一律 LF（CRLF 在 bash 3.2 直接报错）
- bash 3.2：`$VAR` 后紧跟中文必须写 `${VAR}`，否则 unbound variable 退出
- 代理已配：`http.https://github.com.proxy = socks5://127.0.0.1:7897`

## 构建 gotchas

- `packaging { excludes += "**" }` + 显式 merge `META-INF/xposed/*` = 混淆后 `java_init.list` 存活的保证；动 proguard/packaging 必须回归 release 包元数据
- release = 混淆 + 收缩 + debug 签名；`abiFilters` 只 arm64
- 模块名/简介只从 `<application>` 的 label 与 description（必须 `@string`）读
- DexKit 兜底必须有内容锚点，无锚点诚实跳过（`NOT_RESOLVED`），不猜类名
- 失败分级：`FAILED` = 重试无意义；`NOT_RESOLVED` = 随类加载重试

## 哲学

**在精确的边界内，找最稳的锚点，最小侵入完成重定向，随时准备优雅失败。** 七条原则与审计应用见 `docs/hook-philosophy-manifesto.md`。

## 未解决

- 主题商店搜索页「免费领」是本地硬编码 tab，云控与槽位裁剪都管不到 → `docs/theme-store.md` §七·2
- ~~挂载点口径漂移~~ **已排查为假问题（2026-10-11）**：`docs/hook-inventory-audit.md` 的复核 grep 正则漏了 6 个命名空间，
  误报 theme 37 / installer 8；用全命名空间命令复查后代码计数（theme 43 / installer 11）与 README 一致。
