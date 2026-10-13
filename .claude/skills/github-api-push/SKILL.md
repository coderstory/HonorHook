---
name: github-api-push
description: push 通道不可用时，用 GitHub REST API 纯接口把本地提交同步上远端（token 三轨获取：gh CLI / Windows 凭据库 / 用户提供；blob→tree→commit→PATCH ref 快进重放，可全程不碰 git push 甚至不碰 git.exe）。触发：push 失败/push 不了/网络封 git/用 API 提交/同步 GitHub/不要用 git
---

# GitHub API 推送（push 通道失效时的运输层）

本地 commit → REST Git Data API 逐对象重放，不依赖 `git push`。**运输层替身，不是流程豁免**：
推哪个分支、什么时机，纪律照旧（master 相关流程归 release skill；远端写必须逐次向用户复述获准）。
**禁 force**：PATCH ref 一律 `force=false`，非快进必失败（防覆盖保险丝）。

## 前置检查（全过才继续）

1. **代理**：问用户当前代理端口（会过期——仓库 git 配置 2026-10-13 实证写死过 `socks5://7897` 而实际在用 10808），探通才继续：
   `curl -s -o /dev/null -w '%{http_code}' --proxy socks5h://127.0.0.1:<端口> https://api.github.com` → 200
2. **实时基准**：GET `/repos/<owner>/<repo>/git/ref/heads/<分支>`，与本地 `refs/remotes/origin/<分支>` 比对；
   不一致 = fetch 之后又有人推过 → 用实时值重新取基准（`git fetch` 刷新过的本地引用只作参考）
3. **确认门**：向用户复述「准备把 N 个 commit 重放到 origin/<分支>（基准 <sha>），快进不改写历史」，获准后动

## Token 三轨（按序，全失败才向用户要 PAT）

| 轨 | 取法 | 坑 |
|---|---|---|
| gh CLI | `gh auth token` | 本机可能根本没装 gh（2026-10-13 实测 PATH/scoop/choco/winget/配置目录全无，用户记忆的「已登录」其实是 GCM） |
| Windows 凭据库 | `scripts/extract-gh-cred.ps1`（CredRead）读两条：`GitHub - https://api.github.com/<账号>`（gho_ OAuth）与 `git:https://github.com`（PAT），UTF-8/UTF-16 双解码逐候选 GET /user 验证 | gho_ 可能过期（401 就换下一个候选）；**解析下标**：输出 `CRED\|target\|USER\|user\|UTF8\|tok\|UTF16\|tok` → token 在第 5/7 段（首战取 4/6 当成 token 的全是 "UTF8" 字面量） |
| 用户提供 PAT | 用户粘贴 | 会话留痕，安全性由用户权衡 |

token 只在脚本进程内存流转：不 echo、不落盘、不进命令行参数（pswsh 输出经管道捕获）。

## 重放（scripts/replay_commits.py，纯 stdlib）

4. 差异范围 = `origin/<分支>..<分支>`；两种模式：
   - `--mode auto`（git.exe 本地可用，仅远端通道死）：**增量重放**——每提交用 `git diff-tree`
     取改动集，`base_tree`+delta 建树，只上传改动 blob（blob sha 与本地全等）；支持多提交/删除/权限位；
     合并提交被线性化（内容保真、双亲折叠，如实告知用户）；子模块 gitlink 直传（160000 透传，GitHub
     在子模块仓库内解析 sha）；首战教训：**别做全量树重放**——大仓库一个提交几百个文件，串行上传会被
     用户当成"卡住"（2026-10-13 实证），delta 后同一场景 11 个 blob 十几秒
   - `--mode files`（连 git.exe 都不给）：显式文件清单 + 工作区内容**单提交**重放（消息/作者从参数或 reflog 取）；
     清单每行一个路径，`- <路径>` 表示删除；**不支持**中间提交重放与旧版本内容
5. 保真：作者/邮箱/日期/message 原样传给 API；auto 模式 blob 全等，files 模式抽查 `git rev-parse <sha>:<path>` 对比（无 git 时跳过）
6. 链式提交（第一个 parent=基准，之后串前一个新 sha），**最后一次性** PATCH ref 到末位新 sha（force=false）
7. 交付 commit URL；API commit 等价 push event，CI 正常触发

## 已知坑（实证）

- **GBK 控制台**：中文 commit message 严禁走 heredoc/命令行参数（必乱码）——从 UTF-8 文件读
  （`--message-file`），JSON `ensure_ascii=True` 全 ASCII 出网
- **sha 分叉后遗症**：API 重放的新 commit sha ≠ 本地 sha（内容全等、tree 全等）。善后（有 git 的机器）：
  `git fetch && git reset --hard origin/<分支>`（零内容丢失）；**别 pull --rebase**（空 rebase 或造重复提交）
- 临时 payload 文件放运行目录，用完即清；凭据提取脚本本身不含 token，可入库

## 善后

8. 报告：commit URL + 基准 sha + 模式 + 分叉善后命令一行
9. 清理一次性 payload 文件

## 首战存档（2026-10-13）

push 通道被封 → 10808 代理只认 API → 凭据库 gho_ token（第 5 段下标修复后）→ files 模式重放
8 文件单提交 `43bc083` → 远端 `d152d877`（blob sha 全等、作者/日期保真、纯快进）。
工具当时落在 `system-files/tmp-gh/`，已泛化入库本 skill。
