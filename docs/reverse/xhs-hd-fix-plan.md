# XHS 原图 / 原视频下载修复 · 变更清单（2026-10-14）

> 事实源：`docs/reverse/xhs.md`（逆向记录）、`/tmp/xhslog.txt`（真机 modlog 全量）、CDN 实测。
> 主会话只决策派发；编码在子任务。改前只改本清单内条目。

## 零、本轮实证（可复核）

| # | 结论 | 证据 |
|---|---|---|
| E1 | **X4 的 `download_url` 换链对客户端无效** | 01:17 日志 `视频响应已换链 → ..._258.mp4`；用户实测客户端仍落 ~2.9MB（= 服务器原 `_4610`）⇒ 客户端不吃 `download_url` |
| E2 | **`_258` / `_4610` 不是同片画质档** | curl 落盘 + MP4 box 解析：两者均 `720x960 avc1`，时长 **8.700s / 11.184s** 不同 |
| E3 | **app 自报原始尺寸更高** | 日志 `video对象探测`：`"height":1920`，`"bbox":{"2":[{"h":1920,"w":1080}]}` |
| E4 | **裸 origin key 直链不存在** | `https://sns-video-bd.xhscdn.com/{32hex}` → 404（两枚 file id 均试）；`/stream/1/110/{258,4610}/{id}_{t}.mp4` → 200 |
| E5 | **seg×type 矩阵无更高档** | `seg ∈ {1,10..300}` × `type ∈ {19,20,258,259,260,4610,4611}` 全 404（仅 `seg=110` + `type=258/4610` 通） |
| E6 | **`origin_video_key` 不在 app 网络层** | 全 dex grep 仅 1 处，在 `classes20.dex` `NoteDetailArgumentsExtensionsKt.java:176-180`，是 **Bundle extra**；app API 实体无此字段 |
| E7 | **伪 originKey 采集在产垃圾** | `P_VIDEO_OBJ_ID`（`"id"` + 3000 字符窗口）实测命中两类：24 位 hex（如 `6a0331340000000035038a88`，`sns-video-bd/<hex>` → **404**）与 `http://..._258.mp4`（video 窗口内其他对象的 id） |
| E8 | **web 页取原始档死路** | `GET /explore/{noteId}` → 302 → `/404/sec_...?source=xhs_sec_server`（23,152B 壳），`originVideoKey` 0 命中；真机日志 `web页无 originVideoKey` 每次必现 |
| E9 | **客户端落盘目录是 `Pictures/小红书`** | 真机 `ls`：`/sdcard/Pictures/小红书` 由 com.xingin.xhs 创建；`deleteLowQuality` 的 `DISPLAY_NAME LIKE '%noteId%'` 在其文件名上恒 0 命中 ⇒ 静默 no-op |
| E10 | **`Movies/小红书` 空** | 日志 5 次 `HD直下完成 … 4148KB → Movies/小红书/xhs_…_hd.mp4`，但目录 `total 0` ⇒ 交付管线有缺陷（无 `IS_PENDING` 生命周期） |
| E11 | **询问卡重复弹** | 同一 noteId 11 分钟内弹 5 次（01:44/01:49/01:50/01:53/01:55），每次点下载都重下同一文件 |
| E12 | **`VideoInfoV2.b()` 全量收流** | `media.stream.{h264,h265,h266}` 三组全进 `VariableVideo` 列表，`d()==1`（default_stream）置 index 0；`videoInfoJson` 存原始 media JSON |
| E13 | **客户端视频落 `DCIM`，文件名不含 noteId（源码级）** | 落盘收口 `idc/w.java:730-760` `h0(file, dir, displayName, move, mime)`：API29+ 仅 `dir==DCIM/MOVIES` 才作 relative_path，否则强制 `DCIM`；mime 以 `video` 开头 → `MediaStore.Video`；写入 `m0(uri, FileInputStream)`，**宿主自身不置 `IS_PENDING`**。视频保存三处调用点 `r01/p.java:27` / `w01/r.java:26` / `f65/n.java:39` 均 `h0(file, DIRECTORY_DCIM, "Camera/"+file.getName(), …, "video/mp4")` ⇒ `DISPLAY_NAME LIKE '%noteId%'` **源码级铁定 0 命中** |

**【已决，2026-10-14 真机 V-8 取证】**：**有**。同一 note 响应体采出候选 8 个，最大档 `_533` = **6122KB**，落盘后 MP4 box 解析 = **1440x1920 hvc1(HEVC) 8.685s**，与 app 自报 `height:1920` 吻合 ⇒ **真原始档在网络响应体内，无需 WebView 会话**。旧打分法只到 `_258`/`_4610` 是漏档 bug，非服务端限制。回答完毕，见 `docs/reverse/xhs.md` §三 X4。

### V-9（**已作废，不启动**）—— 进程内 WebView 会话取真原始档
- **作废依据（2026-10-14 真机）**：`_533` 已是 1440x1920 HEVC 原始档，取自普通响应体（无 sec 墙、无登录态依赖）。下面路线不需启动，留档仅备将来「原始档不在响应体」的版本变化。
- 第三方取数链（`reference/xhs/XHS_Downloader_Android/.../XhsNoteParser.kt:172-182` 原文）：
  `key = video.consumer.originVideoKey ?? video.originVideoKey`；`isHttp(key) ? key : "https://sns-video-bd.xhscdn.com/${key.trimStart('/')}"`；
  同注释明写 **"stream/video IDs are not interchangeable with CDN keys"** —— 即我们试的 32 位 file id / 24 位 hex 都**不是** key（与 E4/E7 一致）。
- 数据源只能是 web 页 `window.__INITIAL_STATE__`（app API 无此字段，E6）。
- 进程外取页被 sec 墙挡（E8）⇒ 只能在 **XHS 进程内**用带会话的 WebView 加载
  `https://www.xiaohongshu.com/explore/{noteId}?xsec_token={tok}&xsec_source=pc_feed`，
  `evaluateJavascript` 读 `__INITIAL_STATE__`。`xsec_token` 可考虑从
  `NoteDetailArgumentsExtensionsKt` 同处（Bundle extra）取，或从 feed 响应配对（现配对逻辑失败，见 V-2 备注）。
- 成本/风险：进程内建 WebView（主线程、需 Activity 或 application context）、页面结构随版本变、登录态依赖。**启动前需用户拍板。**

## 一、V 组 —— 视频链 `app/app/src/main/kotlin/top/honorhook/xposed/target/xhs/XhsVideoSave.kt`

**V-1 删除伪 originKey 采集**（现 343-370 行整块）
- 删 `"video":{` 窗口扫描、`P_VIDEO_OBJ_ID`、`VIDEO_OBJ_WINDOW`、`originProbe`、`vidProbe2` 及日志行。
- `VideoSource.originKey` 仅保留 `P_ORIGIN_KEY`（`origin_video_key`）来源。该字段 app 网络层不存在（E6）⇒ 恒 null ⇒ `downloadUrl()`/`swapUrl()` 恒 null。字段与分支**保留**（注释记明：待 web 会话路线复活时启用），不再由 video 窗口喂值。

**V-2 删除 web 页取原始档**（`fetchOriginVideoUrl` + `pickBestAndDownload` 首行调用）
- 依据 E8。顺带删 `xsecTokens` / `P_XSEC` / `xsecProbe` —— **仅当** grep 确认无其他引用；有引用则保留并注明。

**V-3 候选采集改「全量 master_url + backup_urls[0]」**（`bestStreamOf` 重写）
- 现状用「master_url 前 1200 字符窗口找 height/avg_bitrate」打分；窗口法不可靠（E2/E5 下选出的并非目标档）。
- 改为在 `video_info_v2` 区间内**收集全部** `master_url` 与同流 `backup_urls[0]`，去重成候选列表，交给既有 `probeSize` 按**实测字节数**选最大。
- 删 `P_HEIGHT` / `P_AVG_BITRATE` / `STREAM_FIELD_WINDOW` 打分逻辑；保留 `P_MASTER_ANY` / `P_BACKUP_URLS`。
- `VideoSource` 三字段（originKey/bestStreamUrl/masterUrl）收敛为候选 `List<String>`；调用方（`patchVideoSaveResponse`）同步。

**V-4 交付管线修复**（`downloadVideoHd`）
1. `insert` 时置 `IS_PENDING=1`。
2. **流式写入**：`conn.inputStream` → `resolver.openOutputStream(uri)`，不落 `ByteArray`（现全量驻留内存）。
3. 校验 `Content-Type` 以 `video/` 开头，否则 `resolver.delete(uri)` + WARN（防 403 HTML 落盘）。
4. 写入字节数与 `contentLengthLong` 比对（length>0 时）；不等 → `delete(uri)` + WARN。
5. 成功后 `IS_PENDING=0` + `DATE_ADDED` / `DATE_TAKEN`。
6. 任何异常路径回滚（删该 uri）。
- 目录维持 `Movies/小红书`；文件名维持 `xhs_<noteId>_hd.mp4`。

**V-5 低画质清理改「时间窗 + 体积 + 非本文件」**（`deleteLowQuality`）
- 依据 E9。改为：以本轮 save 拦截时刻 `t0` 为锚，查 `MediaStore.Video` 中 `DATE_ADDED >= t0 - 10s`、`_ID != ourUri`、`SIZE < ourSize` 的行删除；保留写完 5s 后二扫。
- `t0` 由 `scheduleHdVideoDownload` 透传进 `downloadVideoHd` → `deleteLowQuality`。
- 可加 `OWNER_PACKAGE_NAME == "com.xingin.xhs"` 收窄（API 29+ 可用），取不到则忽略该条件。

**V-6 询问卡去重修正**（`scheduleHdVideoDownload` / `hdDedupe`）
- 依据 E11。`hdDedupe` 命中时**连询问卡都不弹**；HD 下载成功后再写一次 `noteId → now`。
- 先读现有实现确认当前去重位置，再改。

**V-7 撤联调仪表**：`imgProbe` / `saveProbe` / `noteProbeCount` 及对应日志行（`originProbe`/`vidProbe2` 随 V-1 撤，`xsecProbe` 随 V-2 撤）。

**V-8 取证日志（临时，本轮必须留）**
- 采集时打一条：`XhsHook·X4 视频候选全量 noteId=<id> n=<k> <url>@<KB>KB,…`（含每候选 probeSize 实测体积）。
- 作用：回答"是否存在比 `_258`(4.05MB) 更大的档"。取证后撤或降为 DEBUG（在注释留 TODO）。

**V-10 X4 响应改写降级说明**：`swapUrl()` 恒 null（V-1 后），"换链"分支自然死掉 ⇒ 只保留 `disable` 解锁语义。在函数注释里记 E1/E7 实证，避免下次又被"换链能救"误导。

## 二、I 组 —— 图片链

**I-1 统一原图 URL 改写为唯一权威规则（源自第三方 `NoteOutput.imageUrls`）**
- **来源更正（2026-10-14 实证）**：规则出处 = `reference/xhs/XHS_Downloader_Android/app/src/main/java/com/neoruaa/xhsdn/domain/download/NoteOutput.kt:34-45`（第三方参考仓，**非** jadx 产物）。该项目全仓只有这一处 `NoteOutput`（`find . -name 'NoteOutput*'` 仅中此条）；jadx 产物 `system-files/xhs-jadx/` 无此类。落地实现 = 新建 `XhsImageUrl.kt`。
- 规则：去前导 `/`；按 `/` 切段；若 `segments.size > 2 && segments[1]` 匹配 `[a-fA-F0-9]{32}` 则丢前 2 段；`join("/")`；在首个 `!` 处截断；host 必须以 `.xhscdn.com` 或 `.xiaohongshu.com` 结尾；默认 `https://sns-img-bd.xhscdn.com/{token}`，显式格式 `https://ci.xiaohongshu.com/{token}?imageView2/format/{fmt}`。
- 三处各自实现收敛为一处（共享工具函数）：
  - `XhsBatchSave.toOriginalUrl()`（现仅取 host 后首段，缺 32-hex 2 段前缀剥离）
  - `XhsHdSave`（现取末段）
  - `XhsVideoSave` 的 `P_ORIGINAL_URL` 采集链（若适用同规则；不适用则不动）
- 视频 URL 旁路保留（`sns-video` / `/video/` / `.mp4` 不参与图片改写）。
- 不再用 `url.contains("xhscdn.com")` 作唯一判据（漏 `ci.xiaohongshu.com`）。

## 三、T 组 —— 工具链

**T-1 `tools/dev.sh install` 自动点安装弹窗**
- 把 `install-root` 已有的「uiautomator dump → 文本匹配 继续/继续安装/仍要安装/确定/安装/允许 → `input tap` 中心点」抽成公共函数，`install` 与 `install-root` 共用。
- 坐标从 dump 的 `bounds` 现算，禁写死（真机实测出现过 901,2519 / 919,2575 两种）。

## 四、纪律

- 只改本清单条目，不顺手重构/删死代码。
- 注释只写代码读不到的（实证 / 为什么 / 外部约束 / 判据），长篇留 `docs/` 指针。
- 用 `mcp__plugin_x-coding-tools__safe_write` / `safe_update` 代替内置 Write/Edit。
- 子任务只做**定向编译**，不 install、不 adb 操作设备。

## 五、真机验证结论（2026-10-14，APK 1.1.0 / 宿主 9.49.1）

前置：`tools/dev.sh install` 自动点掉两段安装弹窗（901,2519 / 919,2575）——T-1 真机生效；`enable-module` 后重注入，判据全绿 `X1 3/3 · X2 3/3 · X3 1/1 · X4 1/1 · X5 2/2`。

| 条 | 验证方式 | 结果 |
|---|---|---|
| V-3 + V-8 | 视频笔记「保存」→ 询问卡 → 下载高清 | 候选 **8 个**，最大 `_533` 6122KB；取证日志原文见 `docs/reverse/xhs.md` §三 |
| V-4 | 落盘后拉取文件 | `Movies/小红书/xhs_6acc41710000000002011d20_hd.mp4` **6,269,525B 存活**（此前无 IS_PENDING ⇒ 产物消失）；MP4 解析 1440x1920 hvc1 8.685s |
| V-5 | 清理日志 | `已删低画质文件 Camera_xhs_1791916085784.mp4 (1607KB < 6122KB)`；二次扫码 `无可删` |
| V-6 | 本轮只弹一次询问卡 | 未见重复弹；跨进程持久化仍未做（见残留风险） |
| V-7 | grep | 六个仪器标识符（imgProbe/saveProbe/originProbe/vidProbe2/xsecProbe/noteProbeCount）零残留 |
| I-1 | 4 图笔记「保存全部」 | **4/4** 落 `Pictures/小红书`（4.77/2.87/2.83/3.08 MB，1728x2304）；客户端自存同图 1.23MB |
| T-1 | `dev.sh install` | 两段弹窗自动点掉，装后版本 1.1.0 |

**残留风险（未解决，非本轮范围）**：V-6 跨进程去重仍是进程内 map，宿主进程重注入后会丢失 ⇒ 重启后同一 note 仍可能重复弹卡。
