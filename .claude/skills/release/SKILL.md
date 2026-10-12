---
name: release
description: HonorHook 发布管线（master 唯一入口）：dev 快进 + 版本 bump + 用户向 changelog，push 后 CI 双发布
---

# 发布

master 只读铁律：禁直接 push/commit/tag/force master。本 skill 是唯一写手，产出 = dev 上的 `chore(release)` 提交 + `git push origin dev:master` 快进。中途任何一步失败 → 停，报告用户，不自作主张修复或回滚。

## 前置检查（全过才继续）

1. 当前分支 = dev；`git status --porcelain` 为空（有脏改动 → 让用户先处理，不带脏发布）
2. `git pull --ff-only origin dev`
3. `git fetch origin master` —— 远端 master 不得包含 dev 发布点之外的提交（`git log origin/master --not origin/dev` 非空 = 有人绕过 skill 动了 master → 停）

## 版本与范围

4. 读 `app/app/version.properties`：versionCode / versionName / lastReleaseCommit
5. 新版本号默认 patch+1（1.0.1 → 1.0.2）；用户可指定进中段/主段。新 versionCode = (主段*100 + 中段*10 + 尾段) × 100，必须 > 旧值（覆盖安装要求单调）
6. changelog 范围 = lastReleaseCommit..HEAD；lastReleaseCommit 为空 = 首次 skill 发布 → 问用户起点
   `git log --oneline <起点>..HEAD`，剔除 `chore(release):` 行

## changelog 生成（写 RELEASE_NOTES.md，= GitHub release 正文，面向最终用户）

- 只写用户可感知变化：新功能、修复、行为变化；chore/docs/test/重构内部细节不写
- 禁内部术语：挂载点、类名/方法名、混淆名、hook 字样；写「修复了什么问题」，不写「改了哪个函数」
- 分组：新功能 / 修复 / 其他；末尾保留三行通用段（宿主基线 / 安装 / 判据，照现有 RELEASE_NOTES.md）

## 执行（第 7 步用户确认后才动）

7. 向用户展示草稿：新版本号 + changelog 全文，确认后继续
8. 写 `app/app/version.properties`：新 versionCode / versionName；lastReleaseCommit = 当前 HEAD（bump 前最后一个代码 commit）
9. 覆盖写 `RELEASE_NOTES.md`
10. `git add` 两文件 → commit：`chore(release): vX.Y.Z`
11. `git push origin dev`
12. `git push origin dev:master`（禁 --force；被拒 = master 有外力提交 → 停）

## 收尾

13. 报告：版本号、tag `vX.Y.Z`、CI 链接 https://github.com/coderstory/HonorHook/actions
14. 提醒用户（不代做）：CI 绿 = GitHub Release + Xposed-Modules-Repo 双发布完成；装机判据 = LSPosed 日志 `installed N/M`；老用户应用内「检查更新」应弹新版
