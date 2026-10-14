# 小红书（com.xingin.xhs）版本兼容性与维护成本

> 维护导向文档：回答「宿主 OTA / 服务端改动后哪些点会碎、怎么重定位、要花多少工」。
> 事实源：代码 `app/app/src/main/kotlin/top/honorhook/xposed/target/xhs/*.kt` + `docs/reverse/xhs.md` +
> `docs/reverse/xhs-hd-fix-plan.md` + `docs/hook-craft.md` + `CLAUDE.md`「跨版本兼容（hook 选点原则）」。
> 基线宿主 **9.49.1**（versionCode 9491803，2026-10-13 真机拉取；`FeatureCatalog.kt:223`）。
> **软信息标注**：文中 `（推断）`= 按锚类型推理非实测；`（估计）`= 人时/概率量级；`（未记录）`= 无据可查。

## 一、结论摘要

1. **稳的**：X1/X2a（未混淆实体 getter）、X4（okhttp3 公开 API）跨宿主 OTA 大概率不动——只要 `com.xingin.entities.*` 类名与 `@kd.c` 注解串不改、okhttp3 不 shade。
2. **碎的**：四个混淆点（X3 `qc8.i.i`、X2b `qc8.j.b`、X5 `mh9.b`/`d49.q`）的方法号与类名随宿主 R8 重映射走，**已实证漂移过**（X2c `u()`→`v()`）；X5 `d49.q` 无 DexKit 锚，锚最弱。
3. **真失效第一步**：看 `tools/dev.sh modlog-f XhsHook` 的 `XhsHook·Xn: installed N/M` 与失败清单；先对 `dumpsys package com.xingin.xhs` 版本 vs 基线 9.49.1，**别先改 hook 实现**（`hook-craft.md` §六·1）。

## 二、依赖分层

四层依赖，按「宿主 OTA 变更频率 / 失效概率 / 我们的缓冲」排。**大概率**、**中/低** 为推断（量级判断，非实测）。

| 层 | 本项目依赖的具体对象 | 变更频率 | 失效概率 | 我们的缓冲 |
|---|---|---|---|---|
| **1 宿主未混淆对象** | okhttp3 公开 API（`OkHttpClient$Builder`、`Interceptor`、`ResponseBody`）；`com.xingin.entities.{MediaSaveConfig, FunctionSwitch, ShareInfoDetail$Operate, ImageBean}`；宿主 SPI `ISaveImageProxy`/`ServiceLoaderKtKt`；`kotlin.Lazy` | 低 | 低 | 类名/注解串不漂；okhttp3 未 shade（dex 串池实证，`XhsVideoSave.kt:17`） |
| **2 宿主混淆对象** | `qc8.i.i`（X3）、`qc8.j.b`（X2b）、`mh9.b`（X5 图）、`d49.q`（X5 视频） | 每宿主大版本 | 高 | DexKit **内容锚**（字符串常量/参数序列）不随混淆变；但 `d49.q` 目前无 DexHint（`XhsBatchSave.kt:98-107`） |
| **3 协议层** | `/api/sns/*` 路径；`/note/video/save`；`/note/imagefeed`、`/note/detailfeed`（触发端点）；JSON 字段 `video_info_v2`/`master_url`/`backup_urls`/`note_id`/`download_url`/`disable`/`status`/`images_list`/`original`/`url`/`share_info.link`/`xsec_token` | 服务端随时（与宿主版本无关） | 中 | 正则是宽匹配、字段缺失即静默降级；但换字段名要改正则 |
| **4 内容层** | CDN 形态 `sns-img-bd.xhscdn.com/{token}`、`ci.xiaohongshu.com/{token}`；压缩参数 `?imageView2/2/w/576/…`、`!` 后缀；`sns-video-bd.xhscdn.com/{originVideoKey}`；移动版网页 `__INITIAL_STATE__`（camelCase `originVideoKey`） | 服务端随时 | 中 | 换 host 已实证无损（同 token 四 host 字节相同，`xhs.md` §三 X5）；网页结构变化无缓冲 |

> 注：任务清单里列的 **`size`** 字段在 xhs 代码中**未见消费点**（`（未记录）`）——现有代码用的是 `Content-Length`/`Content-Range` 实测字节（`XhsVideoSave.kt:860-888`）。

## 三、逐点清单（X1–X5）

口径：代码共 **10 个挂载点**（`grep -c SimpleTask(` = 9 静态 + X2c 动态 1）；`adapt_new_version.py` 提取到 **9**（X2c 无静态挂载点被跳过，见 `xhs.md` §六）。
判据汇总行：X1 `installed N/3` · X2 `installed N/3` · X3 `N/1` · X4 `N/1` · X5 **`N/2`**（`XhsBatchSave.kt` 现有 2 点；`xhs.md` §六 表仍记 `N/1` — **文档未同步**）。
「OTA 失效概率」为**推断**（按锚稳定性排序，非实测）。

### 3.1 点表（id / 签名 / 锚 / 概率）

| 点 id | 目标类.方法（签名） | 锚类型 | OTA 失效概率（理由） |
|---|---|---|---|
| `xhs:saveUnlock.mediaSaveConfig.b` (X1a) | `com.xingin.entities.MediaSaveConfig.b()` → boolean，0 参 | 未混淆类名直连（**无 DexKit 兜底**，`XhsHook.kt:92-99`） | 低——类名未混淆；方法号 9.33.4→9.49.1 零漂移（`xhs.md` §三） |
| `xhs:saveUnlock.functionSwitch.a` (X1b) | `com.xingin.entities.notedetail.FunctionSwitch.a()` → boolean，0 参 | 未混淆类名直连（无 DexKit） | 低——同上；`type` 判定走字段读 + 方法号回落，双漂才失效（安全侧放行） |
| `xhs:saveUnlock.shareInfoOperate.a` (X1c) | `com.xingin.entities.ShareInfoDetail$Operate.a()` → boolean，0 参 | 未混淆类名直连（无 DexKit） | 低——同上 |
| `xhs:noWatermark.mediaSaveConfig.c` (X2a) | `MediaSaveConfig.c()` → boolean，0 参 | 未混淆类名直连（无 DexKit） | 低 |
| `xhs:noWatermark.qc8j.b` (X2b) | `qc8.j.b(Context, boolean)` → `android.graphics.Bitmap`，2 参 | **混淆·DexKit classStrings** `["disableAiWaterMark is ", "SaveImagesHelper"]`（`XhsWatermark.kt:61-69`） | 中——类名/方法号必漂；内容串锚 + 2 参特征存活即可 |
| `xhs:noWatermark.imageBean.disableAi` (X2c) | `com.xingin.entities.ImageBean` 的 disable_ai getter（方法号动态发现） | **运行时注解反射**（`@kd.c("disable_ai")` 字段 + 差分探针，`XhsWatermark.kt:115-156`） | 低——注解串不漂、字段名/方法号全免疫；**但 adapt 脚本核不到（少计 1）** |
| `xhs:hdSave.qc8i.i` (X3) | `qc8.i.i(Activity, String×4, boolean, Function1×2, boolean×3)` → void，11 参，改 args[3] | **混淆·DexKit classStrings** 四串 `["saveImageBitmapNull","getBitmapFromFEPlatform failed","fetch image failed","SaveImagesHelper"]`（`XhsHdSave.kt:49-56`） | 中——四串 + 11 参特征；串常量不改就还能重定位 |
| `xhs:videoSave.okhttpBuilder.build` (X4) | `okhttp3.OkHttpClient$Builder.build()` → `okhttp3.OkHttpClient`，0 参 | **公开库 API**（反射读 `interceptors` 字段，`XhsVideoSave.kt:194`） | 低——okhttp 未 shade、`interceptors` 是公开源码字段；okhttp 被 shade 即失效（FAILED） |
| `xhs:batchSave.saveClick.invoke` (X5 图) | `mh9.b.invoke(Object)` → `java.lang.Object`，1 参 | **混淆·DexKit strings** `["download blocked, disableSaveMedia=true","onDownloadClick, noteId="]`（`XhsBatchSave.kt:91-97`） | 中——双日志串锚；点击链改版即 `NOT_RESOLVED`（不影响其余功能） |
| `xhs:batchSave.videoSaveClick.invoke` (X5 视频) | `d49.q.invoke(Object)` → `java.lang.Object`，1 参 | **无 DexKit 锚**（仅类名 `d49.q` 直连；注释称"靠 versionTables"但**未落地**，`XhsBatchSave.kt:98-108`） | **高**——锚最弱，类名一漂即失；且无内容锚兜底（`（未记录）` 为何不配锚） |

### 3.2 失效症状 / 重定位 / 成本

| 点 | 失效症状（真机判据） | 重定位步骤 | 成本估计 |
|---|---|---|---|
| X1a/b/c | `XhsHook·X1: installed N/3`（N<3）+ WARN `部分 hook 最终未挂载`；保存受限帖仍 toast「download blocked」 | 类名直查；方法号变了按 `@kd.c` JSON 注解找字段 → 该 boolean 无参 getter（`xhs.md` §三 X1a） | 估计 0.5–2 h/套 |
| X2a | `XhsHook·X2: installed N/3`；作者水印复活 | 同 X1a（`disable_watermark` 字段） | 估计 0.5–1 h |
| X2b | `X2 通路 全灭 0/3`（或 N<3）；AIGC 帖仍画 AI 标签（可接受降级） | DexKit `["disableAiWaterMark is ","SaveImagesHelper"]` → 同类中 1 个 `(Context, boolean)→Bitmap`（`xhs.md` §三 X2b） | 估计 1–3 h |
| X2c | 同上；`X2` 少 1 点（脚本口径） | 无需改码——注解反射自动适配；只有注解串 `disable_ai` 被服务端改名才静默降级 | 估计 0 h（结构性降级） |
| X3 | `XhsHook·X3: installed N/1`（0/1）；保存回压缩图 | DexKit 四串 + 11 参特征重找 `qc8.i.i`；args[3] 下标复核（`xhs.md` §三 X3） | 估计 2–6 h |
| X4 | `X4 0/1` + `okhttp3.Interceptor 不可用，视频直链未注入`；保存响应不改写、无 HD 询问卡 | okhttp3 类名公开，通常无需重定位；okhttp 被 shade 才要换锚 | 估计 0–4 h |
| X5 图 | `X5` N<2 + 无「保存全部」弹窗；本张原生保存仍生效 | DexKit 双日志串重找 `mh9.b.invoke`（`xhs.md` §三 X5） | 估计 2–6 h |
| X5 视频 | `X5` N<2；视频无反馈卡 | 无锚→须反编译找「保存点击链实例化、双分支 case2=saveVideo」的 lambda（`XhsBatchSave.kt:105`） | 估计 3–8 h（推断，无锚最贵） |

## 四、随服务端变化（与宿主版本无关）的点

这些点**宿主没升级也会碎**。

| 项 | 代码位置 | 失效症状（日志哪行缺失） | 最快验证手段（本机 curl，token 占位） |
|---|---|---|---|
| API 前缀 `/api/sns/` | `XhsVideoSave.kt:241` | 无 `XhsHook·X4 视频响应已改写`、无 `原片解析成功`——采集链整体不入 `rewriteVideoSave` | 真机 modlog 看是否还有 `视频响应已改写`；本机无法复现（需 app token） |
| `/note/video/save` 路径 | `XhsVideoSave.kt:243` | 无 `视频响应已改写`；`选档` 行也没有 | 同上（响应体需 app 会话） |
| `/note/imagefeed`、`/note/detailfeed` 触发端点 | `XhsVideoSave.kt:283` | 无 `追加询问判定：已采图集 N 张`，X5 批量失活 | 同上 |
| `video_info_v2` 区段字段 | `XhsVideoSave.kt:324` | 无 `HD询问 noteId=… 候选 N 个`（候选为空直接 return，`XhsVideoSave.kt:739`） | 用真机 modlog 的 `选档 … 候选=N个` 反推 |
| `master_url` / `backup_urls` | `XhsVideoSave.kt:77-80` | 同上；候选表只剩响应 `download_url` 一条 | 同上 |
| `originVideoKey`（camelCase，移动版网页） | `XhsVideoSave.kt:138` | `原片解析失败 noteId=… 网页无 originVideoKey` | `curl -s -A 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36 xiaohongshu' 'https://www.xiaohongshu.com/discovery/item/<noteId>?xsec_source=app_share&xsec_token=<token>' \| grep -o '"originVideoKey":"[^"]*"'` |
| `share_info.link` / `xsec_token` 配对 | `XhsVideoSave.kt:125,131,391-418` | `原片解析跳过 noteId=…：无 xsec_token/share link` | 真机 modlog 看该行；本机不可验（token 来自 app 响应） |
| `images_list[]` 元素 `original` / `url` | `XhsVideoSave.kt:276-277` | 无 `追加询问判定：已采图集 N 张`（空列表） | 真机 modlog |
| 图片压缩参数 `?imageView2/2/w/576/…` / `!` 后缀 | `XhsImageUrl.kt:10,15,44` | X3/X5 落盘仍是压缩图（改写后 token 相同但未剥参） | `curl -sI 'https://sns-img-bd.xhscdn.com/<token>'`；剥 `?`/`!` 后应更大 |
| CDN host `sns-img-bd.xhscdn.com` / `ci.xiaohongshu.com` | `XhsImageUrl.kt:27,42,47` | 改写返回 null → 走原 URL | 同 token 换 host 验证等价：`sns-na-i6.xhscdn.com` / `sns-img-bd` / `ci.xiaohongshu.com` / `sns-img-qc.xhscdn.com`（应字节相同，`xhs.md` §三 X5） |
| CDN host `sns-video-bd.xhscdn.com/{originVideoKey}` | `XhsVideoSave.kt:85,444` | `原片解析成功` 有但下载 404 | `curl -sI 'https://sns-video-bd.xhscdn.com/<key>'`（key 形如 `spectrum/1040g…` 或 `pre_post/…`） |
| `disable`/`status` 响应字段 | `XhsVideoSave.kt:536-541` | 无 `视频响应已改写 noteId=…（解锁）`；保存受限 | 真机 modlog |
| 移动版网页 `__INITIAL_STATE__` 结构 | `XhsVideoSave.kt:437-442` | 同 `网页无 originVideoKey` | 见上 curl（**桌面 UA 拿不到**，必须手机 UA + `xiaohongshu` 后缀；`xsec_source=pc_feed` 会 302 登录页） |

## 五、版本适配 SOP（拿到新宿主 APK 后）

| 步 | 命令 | 判据 |
|---|---|---|
| 1 拉包 | `tools/dev.sh pull-apk com.xingin.xhs` → `system-files/com.xingin.xhs/`（含 split） | `apkinfo` 摘要显示新 versionName |
| 2 反编译 | `tools/dev.sh jadx <apk> xhs-jadx-<ver>`（或 `tools/dev.sh decompile <apk>`） | 产物 `system-files/xhs-jadx-<ver>/sources/` |
| 3 自动核验 | `python tools/adapt_new_version.py <apk> --expect-pkg com.xingin.xhs --report system-files/adapt-report-xhs.md` | 报告「存活 9/M」；X2c 少计 1 为**预期**（`xhs.md` §六） |
| 4 逐点核对 | 对报告里 `CLASS_MISSING`/`METHOD_MISSING`/`PARAM_CHANGED` 的点，按 §三 的锚重找（混淆点看 DexHint 串是否还在 dex） | 每个漂移点定位出新的类名/方法号/参数数 |
| 5 改码 | 未混淆点改 `CLS_*`/`methodName` 常量；混淆点优先调 DexKit 锚；确认要按版本分发时**手写新表注册 `versionTables`**（机制见 `core/HostVersion.kt:53`、`themestore/ThemeStoreHook.kt:23`、`LauncherHook.kt:39`） | 定向编译通过 |
| 6 定向编译 | `bash tools/dev.sh build` | 编译通过 |
| 7 装机 + 启用 | `tools/dev.sh install` → `tools/dev.sh enable-module` | 装后版本正确；重装即停用 → 必须重新启用（`hook-craft.md` §六·4） |
| 8 重注入 + 判据 | `tools/dev.sh relaunch com.xingin.xhs` → `tools/dev.sh modlog-f XhsHook` | X1 `3/3`·X2 `3/3`·X3 `1/1`·X4 `1/1`·X5 `2/2`（对齐 `xhs-hd-fix-plan.md` §五 真机实证口径） |

> 无线 adb：`tools/dev.sh connect <ip[:port]>`。反编译用工具链自解析（`tools/dev.sh env` 先跑）。
> 注意：**xhs 目标尚未纳入 `versionTables`**（现存版本表仅 theme/launcher，`CLAUDE.md`「跨版本兼容」段），本 target 走源码直挂式——步骤 5 的「按版本分发」对 xhs 是**未落地选项**。

## 六、成本模型

**以下人时区间为估计值，非实测**；点数与步骤可从代码/文档计数。

| 场景 | 需改点数 | 每点步骤 | 必须真机？ | 需重新反编译？ | 人时区间（估计） |
|---|---|---|---|---|---|
| **一次宿主大版本 OTA** | 0–4（未混淆点通常 0；混淆点 4 个） | 拉包→jadx→adapt 核验→逐点重定位→改码→编译→装机→modlog 判据 | 是（`installed N/M` 只在真机 LSPosed 框架日志） | 是 | 4–16 h（0 改码时仍要约 1–2 h 核验/装机） |
| **一次服务端字段/接口改动** | 1–3（通常 1 条正则或 1 条路径） | 定位消费点→改正则/路径→编译→装机→看对应日志行 | 视情况（curl 可先验 CDN/网页，API 需真机） | 否 | 1–6 h |

**已被工程化降低成本的部分**：

- **数据表唯一事实源 / `HookPoint` 表**：主项目 theme 43 点已入表（改表不改逻辑）；**xhs 尚未入表**（源码直挂）→ 对 xhs 此项收益**未兑现**。
- **DexKit 内容锚**：混淆点靠字符串常量/参数序列重定位，锚不改则免改码（X2b/X3/X5 图有效；**X5 视频 `d49.q` 无锚，未受益**）。
- **运行时注解反射**：X2c 完全免疫方法号/字段号漂移（零改码）。
- **`installed N/M` 汇总判据 + 失败清单**：一次 `modlog-f` 即知哪些点碎（`core/MountEngine.kt:196-220`）。
- **`adapt_new_version.py` 自动核验**：一步跑出全部静态点的存活/漂移（`tools/adapt_new_version.py:135-380`）；**盲区** = X2c 动态点（少计 1）。
- **宿主版本自检**：非基线时自动打 WARN 提示（`core/MountEngine.kt:279-299`）。

## 七、已知残留风险与未覆盖路径

| 项 | 影响 | 触发条件 | 现状态 |
|---|---|---|---|
| 跨进程去重（V-6） | 同一 noteId 反复弹 HD 询问卡、重复下载 | 宿主进程被杀/重注入——`hdDedupe` 是进程内 `ConcurrentHashMap`（`XhsVideoSave.kt:597`），内存丢失 | **未根治**（`xhs-hd-fix-plan.md` §五 残留风险） |
| `.pending-*` 孤儿 | `IS_PENDING=1` 半成品残留占存储（实测残留 102,759,493 B） | 宿主进程被系统回收时 `rollbackHd()` 不执行（`XhsVideoSave.kt:1008`） | **未做**（需模块启动扫 `.pending-*`，待拍板；`xhs.md` §三 X4 / fix-plan §六） |
| 大文件无断点续传 | 原片可达 116 MB（`xhs-hd-fix-plan.md` §六），一次中断即整包作废 | 网络抖动/进程被杀——`downloadVideoHd` 单次流式写，失败 rollback 全删（`XhsVideoSave.kt:900-1005`） | **未实现续传** |
| X3 改解码源无下载兜底 | 换原图直链后若 Fresco 缓存 miss，走网络拉原图（正常路径，但离线/慢网可能失败） | Fresco 缓存无该 token | 设计即如此（`xhs.md` §三 X3 边界），**未加兜底** |
| live photo / 动图未验证 | vivo/oppo 双文件 zip 分支不走 `qc8.i.i` 单图解码 | 实况照片笔记 | **未覆盖**（`xhs.md` §一 边界、§三 X3） |
| 宿主进程被杀中断下载 | 下载中途进程回收 → 文件不完整/无回滚 | 系统内存回收、用户强停 | 同 `.pending-*`；**未处理** |
| 触发端点收窄依赖固定路径 | `/note/imagefeed` 改名 → X5 批量静默失活 | 服务端改端点 | 已收窄（`XhsVideoSave.kt:283`）；**无兜底**（宽前缀曾 62 张污染，不可回退） |
| `d49.q` 视频点无 DexKit 锚 | 宿主 OTA 后该方法一漂即失，无自动兜底 | 混淆名变更 | **弱锚现状**（`XhsBatchSave.kt:98-108`） |
| X1/X2a 无 DexKit 兜底 | 若 `com.xingin.entities.*` 被混淆（当前未混淆）则直接 FAILED | 宿主改混淆策略 | 文档 §三 称有 DexKit 兜底，**代码未实现**（仅直连轨）——文档与代码口径不一致 |

## 八、判据与取证速查

**判据口径**：唯一权威 = 真机 LSPosed 框架日志 `/data/adb/lspd/log/modules_*.log` 里的 `installed N/M`（不在 logcat）。Module tag = `HonorHook`。

| 目的 | 命令 | 看什么 |
|---|---|---|
| 抓挂载判据 | `tools/dev.sh modlog-f XhsHook` | `XhsHook·X1..X5: installed N/M` + 通路行 + 失败清单 |
| 抓模块全量 | `tools/dev.sh modlog HonorHook 80` | 各引擎汇总 + 判据/异常行 |
| 装机 | `tools/dev.sh install`（debug）/ `tools/dev.sh install-release` | 自动点 ROM 安装弹窗 |
| 重装后启用 | `tools/dev.sh enable-module` | 重装即停用，必须重新启用 |
| 重注入 + 判据 | `tools/dev.sh relaunch com.xingin.xhs` | 强停冷启后自动抓 `installed` |
| 无线 adb | `tools/dev.sh connect <ip[:port]>` | 省端口默认 5555 |
| 查宿主版本 | `tools/dev.sh exec adb shell dumpsys package com.xingin.xhs \| grep versionName` | 对基线 9.49.1，不一致即看 WARN 自检行 |
| 版本自检行 | modlog | `XhsHook·Xn 宿主版本 X 与 hook 基线 9.49.1 不一致——挂载点可能漂移` |

**本机 curl 复现手法（token/key 用占位）**：

```bash
# 图片原图（剥 ? 参数 / ! 后缀 = 原图；换 host 应字节相同）
curl -sI 'https://sns-img-bd.xhscdn.com/<token>'
curl -sI 'https://sns-na-i6.xhscdn.com/<token>'   # 与 sns-img-bd / ci.xiaohongshu.com / sns-img-qc 等价

# 视频原片 CDN
curl -sI 'https://sns-video-bd.xhscdn.com/spectrum/<originVideoKey>'

# 移动版网页取 originVideoKey —— ★必须手机 UA 且结尾带 xiaohongshu 后缀；
# 桌面 UA 的 state 无 consumer/无 originVideoKey；xsec_source=pc_feed 会 302 到登录页
curl -s -A 'Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36 xiaohongshu' \
  'https://www.xiaohongshu.com/discovery/item/<noteId>?xsec_source=app_share&xsec_token=<token>' \
  | grep -o '"originVideoKey":"[^"]*"'
```

> 坑：网页 JSON 里 `/` 转义成 `\/`、key 前缀有 `spectrum/` 与 `pre_post/` 两种（不得假设前缀），模块侧须 `unescapeJson` 还原（`XhsVideoSave.kt:494-516`）。
