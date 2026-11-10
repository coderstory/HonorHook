# findings 2026-11-10 · 9.53.3 保存拦截零命中根因 + NoteFeed.u0 漂移

## 现象

模块 v1.2.8（9.53.3 套）`installed X5 5/5`，但真机点保存（00:07:39 日志）零「已断」判据：宿主 720p
照常落盘 `Camera_xhs_*.mp4`、模块事后 deleteLowQuality 删。两轮修（拦截门改「noteId 合法即拦」）
均无效 ⇒ 问题不在拦截逻辑，在 noteId 提取。

## 根因（实证）

**`NoteFeed.u0()` 混淆名漂移**：9.53.3 里 `u0()` 返回 `GroupShare`，noteId getter 换成 `J0()`。
- 证据：`.buildtmp/xhs9533-c5/sources/com/xingin/entities/notedetail/NoteFeed.java:1143`
  `public final String J0()`；:2296 `public final GroupShare u0()`（classes5.dex）。
- 消费点 `XhsBatchSave.mountNoteSaveEntry` 旧代码 `feed.javaClass.getMethod("u0").invoke(feed) as? String`
  ⇒ invoke 得 GroupShare，`as? String` = null ⇒ noteId=null ⇒ **静默 proceed**（无任何 WARN，
  仪表盲区）。挂载与签名（vj9.q1#b 9 参）本身无漂移——`.buildtmp/xhs9533-partial/sources/vj9/q1.java`
  核对，b(9参) 真身存在且方法体结构同 9.50（`noteFeed.J0()` 取 noteId）。

## 修复

- 新增 `XhsSavePivot.noteIdOf(feed)`：扫「无参 + 返回 String + 产物过 24hex 门」的 declaredMethods
  （与 findFeedAndNoteId isNoteFeed 分支同口径，版本无关）。
- `XhsBatchSave` 两处 `getMethod("u0")`（mountNoteSaveEntry / videoSaveClick feed 轨）改走 noteIdOf。
- 纪律沉淀：**混淆 getter 禁按名反射**，凡取值点一律带内容校验门（24hex）扫描；xhs-anchors.md
  9.53.3 行已补 ⚠ 注。

## 复测判据（用户点一次保存应见）

1. `XhsHook·X5 笔记保存总入口已断 … noteId=<24hex>`（拦截命中）
2. 无宿主 `Camera_xhs_*.mp4` 落盘（MediaStore 无新 Camera_xhs 文件）
3. 分享链接解析成功 → HD 卡 → 原片直下 `Movies/小红书/xhs_<noteId>_hd.mp4`

## 方法论备注

- 本机 jadx 跑法（tools/jadx/bin 垫片 noexec 不可执行）：jadx-cli 主类 `jadx.cli.JadxCLI`，
  `java -Djava.io.tmpdir=<可写目录> -Duser.home=<可写目录> -cp tools/jadx/lib/* jadx.cli.JadxCLI --no-res -d <out> <dex>`
  ——不设 tmpdir/user.home 会 TempFilesGetter 崩。
- 定位类定义 dex：dexdump 是 x86_64 跑不了；python 解析 dex header（class_defs_size@0x60/off@0x64，
  不是 0x68/0x6c——那是 data 段）扫 type_ids + class_defs。
