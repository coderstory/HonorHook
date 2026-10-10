---
name: honorhook-dev
description: HonorHook 项目的开发/设备操作统一入口——tools/dev.sh 用法（何时用哪个子命令 / 判据与日志坑 / 工具链路径自检；子命令全量见 dev.sh help）。当需要编译 APK、安装到真机、抓 hook 判据日志、操作设备屏幕、反编译目标 apk、或想知道 java/adb/apktool/jadx/aapt2 在哪时使用。禁止手拼 gradle/adb 命令。
metadata:
  author: 项目沉淀
  last-updated: '2026-10-10'
  keywords:
  - dev.sh
  - 构建
  - 安装
  - 真机
  - 日志
  - 反编译
  - 工具链
---

## 铁律

1. **一律走 `tools/dev.sh <子命令>`**，禁手拼 `gradle` / `adb` / `java -jar apktool` / `jadx`。
2. **子命令清单不在这里** —— 跑 `tools/dev.sh help`（唯一权威，新增子命令会同步更新它）。
3. **不问路径** —— 跑 `tools/dev.sh env`，输出 java / adb / aapt2 / apktool / jadx / python3 / gradlew 的实际路径（项目内便携版优先 → brew/PATH 兜底，缺失显示 MISSING）。
4. **新操作先加子命令**（`dev.sh` case + help 表）再执行。
5. 设备侧禁令（禁 reboot / 禁破坏性操作 / 破坏性效果不由 AI 实测）见根 `CLAUDE.md`。

## 何时用哪个（按意图）

| 我想 | 用什么 |
|---|---|
| 编译 / 装到真机 / 一条龙 | `build` `build-release` `install` `install-release` `verify`；`deploy-release` = release 全流程（含重装后自动重新启用模块） |
| 看 hook 有没有挂上（判据） | `log-hook`（TAG=HonorHook 实时）· `modlog`（LSPosed 框架日志，**判据真源**）· `wait-hook`（轮询到 installed） |
| 模块完全没日志 | `lspd-log` / `diag-module` / `log-grep`（先确认模块被框架加载了没） |
| 界面行为不对 | `ui`（View 树 + text/content-desc）· `shot` · `viewdump`；再 `tap` / `swipe` / `text` / `key` |
| 反编译目标 app | `decompile <apk> [名]`（smali+明文 manifest+res）· `jadx <apk> [名]`（伪代码）· `aapt2 <参数>` · `apkinfo <apk>` |
| 校验作用域配错没 | `scopes` vs `targets`（不一致 ⇒ 框架静默不回调该包） |
| 体积 / dex 构成 | `size` · `dexstat` |

## 判据与日志坑

- **判据 = 真机 `installed N/M`**；N<M ⇒ 选点失效或宿主版本错位（**先 `dumpsys package` 对版本，别先改 hook 实现**）。
- hook 侧日志落 **LSPosed 框架日志** `/data/adb/lspd/log/modules_*.log`（**不在 logcat**）。
- 宿主 logger 的 **INFO 级常不落 logcat**（只有 WARN/ERROR）⇒ 载荷/链路类证据要靠模块侧探针打。
- `modlog` 的 pattern 别用 `\|`（多层引号转义后匹配不到），分开调用。
- `ui` 的 dump 偶尔抓到过渡态（底部 tab 全 `selected=false`）⇒ sleep 后再 dump，判定用"关键节点计数"而非"有无文本"。
- `jadx` 大包加 `JADX_OPTS="-j 4 --no-res"`；**exit 1 + "finished with errors, count: N" 属正常**；`--no-res` 拿不到 `strings.xml` 里的 URL 模板，需另跑 `decompile` 取资源。

## 典型闭环

```bash
tools/dev.sh build && tools/dev.sh install && tools/dev.sh launch   # 或直接 verify
tools/dev.sh log-hook        # 看 installed N/M 是否全命中
tools/dev.sh ui              # 界面没生效时看 View 树
```

## 相关

- 判据/选点/死点见 `docs/hook-craft.md`；逆向流程见 `docs/reverse-craft.md`
- 逆向方法论：`reverse-decompile` / `static-analysis`；写 hook：`lsposed-hook-expert` / `reverse-and-hook`；工程搭建：`build-lsposed-module`
