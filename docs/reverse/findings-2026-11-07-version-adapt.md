# 全量版本适配 findings（2026-11-07）

> 触发：用户「当前安装的软件都更新了，你检查下哪些软件的版本没适配，去适配一下，最后不管改没改代码，
> 都修改一下代码里的支持版本」。方法 = MT MCP 逐类读 dex 大纲（`tools/adapt_mt.py`，与 MountEngine
> 运行期判定同口径：类在 + 方法名 + 参数个数），不跑 jadx 全量反编译（本机 on-device，noexec + 内存吃紧）。
> 判据仍以真机 `installed N/M`（LSPosed 框架日志）为准，本文件只记静态核对结果。

## 一、设备实装版本 vs 代码登记（适配前）

Shizuku `dumpsys package` 实测（2026-11-07）：

| 宿主 | 实装 | 适配前代码 | 结论 |
|---|---|---|---|
| thememanager | **20.1.67.303** | 基线 20.1.63.305 + 65.302/67.302 精确表 | 表不匹配 ⇒ 回落基线，7 点失效 |
| mms | **19.0.10.310** | 19.0.9.306 | 宿主侧零漂移 |
| totemweather | **18.1.7.314** | 18.1.6.312 | 5/6 存活（1 点设计内） |
| appmarket | **16.1.10.302** | 16.1.9.302 | 7/7 存活 |
| sysmgr | **17.0.0.473** | 17.0.0.414 + 455/457 | 3/3 存活 |
| xhs | **9.53.3** | 9.50.0 + 9.49.1/9.51.0 | 6 点换包，需新覆盖套 |

其余 scope 包（installer / launcher / settings / systemui / ouc / ime / jd / wallpaper 两件）代码已登记当版，
本轮未动；calendar / browser / quickgame / cloudmusic 走 ads/tracking 通杀 SDK，无 baseline 版本号。

## 二、静态核对结果（`tools/adapt_mt.py` 修复后）

| 目标 | 结果 | 说明 |
|---|---|---|
| thememanager | 45/58 + PLATFORM 3 | 7 个 METHOD_MISSING 全由 4 个混淆类改名引起（见下） |
| appmarket | 7/7 | 类名实为 `com.hihonor.appmarket.operation.*`（旧抽取层被全局 `$PKG` 带偏） |
| sysmgr | 3/3 | 零漂移 |
| xhs | 5/5（静态点） | 坐标表内的 X1/X2b/X4 目标类全在 |
| totemweather | 5/6 | 唯一缺 = `HiAnalyticsInstanceEx$Builder`，**设计内**：`HonorTrackingHook` 已 `loadClass` 探测二选一，新版 SDK 删该类后自动回落 `HiAnalyticsManager.getInstanceEx` |
| mms | 2/5 | 缺的 3 点是插件 dex（`com.ted.sms.*` 不在 base.apk，经 honor linker 插件加载）——属既有设计，宿主侧 2 点存活 |

### 工具缺陷（本轮修的三处，都在 `tools/adapt_mt.py`）

1. **归属错乱**：`adapt_new_version.extract_mounts()` 用一个全局 `PKG`（= 最后一个定义者 thememanager）替换
   所有文件的 `$PKG`，AppMarketHook 的 `$PKG.operation.*` 被写成 thememanager 的类名 ⇒ 7/7 假 CLASS_MISSING。
   修法：`file_owners()` 按「文件自己的 `override val packageName` → `const val PKG/PKG_HN/PKG_HNOUC` →
   同目录兄弟 → TargetRegistry `register(...)` 参数 → 引用归属（数据表文件找引用它的 hook 文件）」重建归属，
   `mounts_for()` 再用文件自己的 `PKG` 重拼类名前缀。
2. **`${'$'}` 转义**：tracking hook 写 `HiAnalyticsInstance${'$'}Builder` 绕 Kotlin 模板，抽取层原样保留 ⇒
   照抄查类必 "Class not found"。归一成 `$`。
3. **校验方法**：旧实现 `apk.search('dex_names', method)`（limit 50）对 `b()/build/<init>` 这类高频短名必然
   搜不到目标类（okhttp3 build 都判假阴性）。改为 outline 为主（自动翻页）、smali `.method` 行兜底
   （outline 已知假阴性：`SingleCutProgressView#setLeftCropPosition`）。

## 三、thememanager 20.1.67.303：四个混淆类改名（已落表）

`ThemeStoreMap_20_1_67_303.kt`（新建）+ `ThemeStoreHook.versionTables` 注册：

| 源名 | 63.305 / 65.302 / 67.302 | **67.303** | 锚 |
|---|---|---|---|
| SharepreferenceUtils（云控读点） | u15 / t15 | **xh5** | "themename" 串 + 双 `getBoolean` 重载 |
| LiveWallpaperHelper | bz2 / ez2 | **b83** | `createApplyWallPaper(WallPaperInfo)V`、`getDecryWallpaperPath(String)` |
| DownloadItemIfVip | f71 / g71 / wb1 | **vb1** | "Start to download vip product"、"productSendFinish downloadUrl is Empty ? " |
| ThemeCheckTool | e26 | **b26** | `getDecryptTheme(String,String,String,Z)`、`getDecryptFile(String,File)`、`getDecryptWallpaperFile(String,File)` |

配套的运行时硬编码一并去版本化（否则新版本照样失效）：

- `ThemeStoreHook.mountOne` 的 `preProcessHandlers` 现在把 `task` 传进处理函数（第 4 参）——
  W2 要复用的宿主类名只能取当前命中表行的 `task.className`；
- `ThemeStoreRuntime.decryptPaidWallpaper(..., ownerClass)`：`getDecryWallpaperPath` 不再写死 `bz2`；
- `ThemeStoreHook.decryptToolClass`：`.303` 显式指向 `THEME_DECRYPT_TOOL_67_303`（= b26），其余版本仍回落 gl5——
  **2026-11-07 实证 `gl5` 已变成不相关的 Rx 类，解密方法整体迁进 `b26`**，写死 `gl5` 会让 W2 静默放行；
- `DECRYPT_PREPARE` 分支里两处 `loadClass("e26")` 改为 `loadClass(task.className)`。

## 四、xhs 9.53.3：6 个在位点整体换包（已落表）

`XhsPoints.kt` 新增 `"9.53.3"` 覆盖套；videoClick / albumCopy.h0 / albumCopy.e0 维持 null（9.51 起不挂/无家）：

| 点 | 9.52.1 | **9.53.3** | 锚串 |
|---|---|---|---|
| saveClick | xq9.b | **gr9.b** | `onDownloadClick, noteId=` |
| x2b | bm8.j | **jm8.j** | `andr_image_save_white_bg_optimized` + `disableAiWaterMark` |
| noteSaveEntry | nj9.o1 | **vj9.q1** | `save video failed:`（落内部类 `vj9.q1$a`，真身外层 `#b` 9 参） |
| aigcEditLaunch | cq4.b | **gq4.b** | `tag_video_edit_download_loading` |
| downloaderEntry | pg5.h | **tg5.h** | `addDownloadTask`（static `e` 13 参） |
| albumCopy.videoStore | d79.s | **l79.s** | `saveVideo start copy file.`（static `a` 5 参） |

签名/参数数与旧版逐点一致（invoke 1 参、noteSaveEntry 9 参、downloaderEntry 13 参、videoStore 5 参）。

## 五、支持版本登记（FeatureCatalog）

`extraVersions` 补齐（UI 版本徽章与 `baselineVersionOf` 都读这张表）：

- thememanager：`20.1.65.302, 20.1.67.302, 20.1.67.303`
- mms：`19.0.10.310`
- totemweather：`18.1.7.314`
- appmarket：`16.1.10.302`
- sysmgr：`17.0.0.455, 17.0.0.457, 17.0.0.473`
- xhs：`9.49.1, 9.51.0, 9.52.1, 9.53.3`

## 六、待真机判据（用户侧执行）

```bash
bash tools/dev.sh deploy-release      # 构建 + 安装 + 启用模块（装后需手动重启模块/force-stop 宿主）
bash tools/dev.sh modlog-grep 'ThemeStoreHook|Xhs'
bash tools/dev.sh wait-hook
```

期望：theme `installed 52/52`（新表 20.1.67.303，实测已达）、xhs X1 `3/3`、X2b `3/3`、X4 `1/1`、X5 `5/5`、appmarket `7/7`、
sysmgr `3/3`、mms `2/2`、totemweather `5/5`（Ex 点运行时二选一）。

## 七、运行时补修（2026-11-07 晚，静态校验抓不到的两处）

### 短信插件 10.0.44.309：T3 主轨 `b(4参)` → `c(5参)`

- **现象**：宿主升到 19.0.10.310（插件 10.0.44.309）后，短信卡片仍显示「打开应用」按钮；
  日志 `mms-hook 主轨未解析（com.ted.sms.feature.ui.action.SimpleActionLayout#b(4参)，插件版本差异？）`。
- **实证**（MT MCP，`mt://installed-app/com.hihonor.intelligentmessage`）：
  - `b()V` 仍在但已是零参方法；4 参版消失。
  - `c(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Lcom/ted/sms/model/TedCard;J)V`
    与旧 `b(4参)` **同构**：`iput-wide p5->u:J`（新增的时间戳）→ `p2.length()==0 &&
    (card==null || !"0EFF76FF".equals(code))` → `setVisibility(8); return-void`。参数序
    `msgId, json, smsNumber, TedCard, long`，第 2 参仍是 json。
  - 调用方 smaliq 命中 `com.ted.sms.plugin.TedMsgItemProcessor`（卡片渲染主链），非死方法。
- **处置**：`MmsHook` 改双轨解析——先 `b(4参)`（老插件 10.0.42.x 仍跑）再 `c(5参)`，命中项带自己的
  任务 id 打判据；`args[1] = ""` 两版语义一致。
- **真机判据**（2026-11-07 23:21）：`mms-hook 主轨已挂载: com.ted.sms.feature.ui.action.SimpleActionLayout.c(5参)`
  + `mms-hook 生效: mms:SimpleActionLayout.c …（json 已置空（card=TedCard））`，用户确认按钮消失。

### 小红书 9.53.3：服务定位器类再次改名

- **现象**：分享直下报 `XhsHook·X5 getShortUrl 异常 NoSuchFieldException: 服务定位器 Companion 未找到（试过 z3c.d / i3c.d）`。
- **实证**：`pbc/z` smali 为 `sget-object v0, Ldcc/d;->Companion:Ldcc/d$a;` → `e(Ljava/lang/Class;)Ljava/lang/Object;`
  → `ShareService.getShortUrl`；`dcc.d$a` 确有该 `e` 方法（**outline 会漏字段/方法，必须读 smali 复核**）。
- **处置**：`XhsSavePivot` 候选表前置 `dcc.d`，失败提示列出全部试过的候选。
- **真机判据**：22:38 装新包后 X1 `3/3`、X2 `3/3`、X4 `1/1`、X5 `5/5`，`getShortUrl` 不再报错。
