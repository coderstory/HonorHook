# XHS_Downloader_Android（基准）业务逻辑全景 × 插件 xhs 系列对照

> 2026-10-15。基准 = `reference/xhs/XHS_Downloader_Android`（Kotlin 安卓版小红书下载器，**唯一抄写基准**）。
> 插件 = 本模块 `app/app/src/main/kotlin/top/honorhook/xposed/target/xhs/`（X1–X5）。
> 路径缩写：`B/` = 基准 `app/src/main/java/com/neoruaa/xhsdn/`；`P/` = 插件 xhs 目录。
> 证据分级：`[源码实证]` = 直接读到的代码行为；`[真机实证]` = xhs.md / 引擎注释记载的真机测量；
> `[合理推断]` = 有依据的推断（依据随条目给出）；`[未取证]` = 无实测、需验证。

---

## 一、基准业务逻辑全景

### 1.1 取数链 [源码实证]

基准有**两条独立取数路径**，产出同一 `ResolvedNote` 模型：

**路径 A：URL → OkHttp 抓 HTML → 解析 `__INITIAL_STATE__`**
- 入口 `DownloadQueue.enqueue`（B/domain/download/DownloadQueue.kt:47-63）：`XhsUrlParser.extractLinks` 抽链（host 白名单 xiaohongshu.com / rednote.com / xhslink.com|cn，B/data/xhs/XhsUrlParser.kt:6-10），分享文案首行可作 shareTitle（XhsUrlParser.kt:26-33）。
- 短链（xhslink）先 GET 跟随重定向换真链（B/data/xhs/XhsContentRepository.kt:37-47, 79-86）。
- 抓页 UA = `Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 Chrome/141.0 Mobile Safari/537.36 xiaohongshu`（XhsContentRepository.kt:111-114）——**结尾 `xiaohongshu` 后缀 + Chrome Mobile 是拿到完整 state 的前提**（与插件 X4 原片解析的 UA 结论同源）。
- **无任何签名/加密 API**：纯 HTML GET，`window.__INITIAL_STATE__` 提取为 JSON（XhsNoteParser.kt:211-227 取 script 段 + 229-260 花括号配对 + 262-295 归一化 `undefined`→`null`、`new Map([])`→`[]`）。
- **cookie 会话**（可关）：`XhsNetwork.client` 对非媒体请求注入 Cookie 拦截器（B/data/network/XhsNetwork.kt:68-81）——手动会话（`SessionCredentials`，AES/GCM 存 noBackupFilesDir，XhsNetwork.kt:22-56）优先，否则 `useWebSession=true`（默认，B/data/settings/DownloadOptions.kt:53）时取 WebView CookieManager 的 xiaohongshu/rednote cookie。仅 https。
- 抓不到页 / 解析不出 noteId → `DownloadFailure.RequiresWebView`（XhsContentRepository.kt:54, 62）＝ 走路径 B。

**路径 B：内置 WebView「抓取」**
- WebViewActivity 用 **桌面 UA**（`XhsWebSession.desktopUserAgent` 把设备括号段换成 X11; Linux x86_64，B/data/account/WebAccountRepository.kt:61-63；WebViewActivity.kt:170 注入）。
- 视频嗅探：`onLoadResource` 收 `sns-video`+xhscdn / `.mp4` / `masterUrl` 命中的资源 URL（WebViewActivity.kt:365-380）。**注意：`sniffedVideoUrls` 传入了 `extractImages` 但函数体内未消费——基准内是死数据，历史遗留** [源码实证]。
- 「抓取」按钮 = evaluate 注入 asset `xhs_extractor.js`（WebViewActivity.kt:446-470）：从 `__INITIAL_STATE__`/`__NEXT_DATA__`/`__NUXT__` BFS 找 `noteDetailMap[当前页 noteId]`（noteId 由路径正则 `/explore|discovery\/item|user\/profile/.../24hex` 得出），返回 `{note, comments, url}`；`MainViewModel.onWebCrawlResult` 用 `XhsNoteParser.parseDetail` 落成 `ResolvedNote` → `queue.acceptResolved`（B/viewmodels/MainViewModel.kt:225-236），acceptResolved 校验「浏览器笔记 == 任务笔记」（noteId 比对，DownloadQueue.kt:157-158）。
- WebView 每 1.5s 轮询注入 `xhs_account.js` 读**登录者本人**（非笔记作者）的 userId/nickname/avatar 存档展示（WebViewActivity.kt:201-225；WebAccountRepository.kt:28-51，userId 须 24hex、avatar 须 https）。鉴权始终留在 WebView 私有 cookie store，不导出 [源码实证]。

### 1.2 解析链（XhsNoteParser）[源码实证]

- note 对象定位：优先 `note.noteDetailMap` / `noteData.data` / `feed.items`，miss 则全树 BFS（≤50,000 节点、≤50 note，XhsNoteParser.kt:300-357），`isLikelyNoteObject` 过滤（有 noteId+title/desc 或 imageList/video 特征，359-369）；按 expectedNoteId 选，无 id 取唯一。
- **图片**（imageList/images 逐项，XhsNoteParser.kt:84-105）：原图 URL 优先级 `urlDefault` → `url` → `infoList[0].url` → `traceId` 重建 `https://sns-img-qc.xhscdn.com/{traceId}`（86-89）；非 http 丢弃。
- **实况照片**：`item.livePhoto` 与 `item` 两处 stream 候选合并去重（91-92），非空 ⇒ 该项是 LivePhoto（图+视频成对）；视频笔记无 stream 的首图标 `cover`（id `cover:N`，93-97）。
- **视频**（note.video，106-111）：候选 = `originVideoKey`（`video.consumer.originVideoKey` 或 `video.originVideoKey`，非 http 时拼 `https://sns-video-bd.xhscdn.com/{key}`，172-179）+ `media.stream` + `stream` 各编解码数组逐元素（185-206：`backupUrls[]` 每元素 + `masterUrl` + `url` 各成一候选，共享流元数据 width/height/videoBitrate/size/codec）；**watermarked = `streamType ∈ {259,309}` 或 `streamDesc == "WEB_LIVEPHOTO_19"`（忽略大小写）**（201-202）；候选按 watermarked 升序排（92, 182）。
- **评论图**（136-170）：comments.list + subComments BFS；每 picture 原图优先级 `urlDefault` → `infoList` 中 `imageScene=="WB_DFT"` 变体 → `url` → `infoList[0].url` → `urlPre`（157-162）；id `noteId:comment:{commentId}:{n}`；hasMore/subCommentHasMore 累计为 `commentsHasMore`。
- 元数据（114-131）：作者 nickname/nickName/name/userName + userId/user_id/id/redId；title/desc；tagList；`time`（epoch）为 publishTime/publishedAt；interactInfo 四计数。`commentsLoaded` = comments list 存在且 `firstRequestFinish != false`。

### 1.3 媒体选档 [源码实证]

- **可下载判定** `NoteOutput.eligible`（B/domain/download/NoteOutput.kt:15-25）：评论图 ⇐ `commentImageDownload`（默认 **false**）；视频封面 ⇐ 仅选择模式或 `videoCoverDownload`（默认 **false**）；普通图 ⇐ `imageDownload`（**true**）；视频 ⇐ `videoDownload`（**true**）；LivePhoto ⇐ `imageDownload || (videoDownload && livePhotoMode != STILL)`。
- **图片直链** `NoteOutput.imageUrls`（NoteOutput.kt:34-45）：token = `URI.path`（**不含 query**）trim 后切段、`segments[1]` 为 32hex 时丢前 2 段、join 后截首个 `!`；host 须 `endsWith(".xhscdn.com")/.xiaohongshu.com"`；候选列表 = `[preferred, sourceUrl, raw]` 去重——AUTO 时 preferred = `https://sns-img-bd.xhscdn.com/{token}`（**裸链，无任何 query**），指定格式（JPEG/PNG/WEBP/HEIC/AVIF）时 = `https://ci.xiaohongshu.com/{token}?imageView2/format/{fmt}`。
- **视频选档** `NoteOutput.videoUrls`（NoteOutput.kt:47-56）：候选按 `watermarked 升序 → (COMPATIBILITY 偏好时 h264/avc/avc1 优先，否则 original 优先) → RESOLUTION/COMPATIBILITY 按 w×h 降序 / BITRATE 按 bitrate / SIZE 按 size` 排序，取列表首；尾补 `video.sourceUrl` 兜底。默认偏好 RESOLUTION（DownloadOptions.kt:37）。
- 选择模式：`requireSelection` 时任务停 `WAITING_FOR_USER`，UI 列 `eligible(forSelection=true)`（含封面），选定 ids 写回 session 后过滤（DownloadQueue.kt:196-204）。

### 1.4 下载传输链 [源码实证]

- **并发**：每任务 `Semaphore(4)` worker（DownloadQueue.kt:266）；共享 OkHttp `maxRequests 12 / perHost 8`，connect 20s / read 45s / write 30s（B/FileDownloader.kt:48-63）。任务级超时 = `timeoutSeconds`（默认 **45**，读 5–180 收敛、连接 min(超时,20)≥5，XhsNetwork.kt:59-62）；可选 http/socks5 代理（63-67，媒体请求须 `proxyDownload` 才走代理）。
- **传输** `ResumableTransfer`（B/data/network/ResumableTransfer.kt）：逐候选 URL × `maxRetries`（默认 **3**，收敛 0–10）重试，退避 `500ms << attempt` 封顶 15s（42-58）；`.part` 文件 + `.json` 元数据（identity=url SHA-256、etag、modified、total、complete）；断点续传 = `Range: bytes=offset-` + `If-Range`（强 etag 或 Last-Modified，79-87），416 且长度/校验器吻合 = 已完整（100-109）；`Access` 失败码 = 401/403/404/410（111-113）；**Content-Type 含 text/json/xml = NON_MEDIA 拒收**（114-118）；206 续传须 range 起点 == offset 且校验器未变，否则重置（121-134）；落盘前空间预检（剩余 < 需求 + 1MB = NO_SPACE，137-139）；写完**魔数探测** `MediaFileType.detect`（jpg/png/gif/webp/avif/heic/mov/mp4/webm 签名，B/data/network/MediaFileType.kt:8-28），认不出 = NON_MEDIA 删件（171-174）；字节数不符 = INCOMPLETE（167）。UA 同抓页 UA + `Accept-Encoding: identity` + Referer xiaohongshu.com（193）。
- **跳过已存在**：`runResource` 先查本任务 resources 表，再按 `recordKey`（noteId+mediaId+存储 URI+命名模板+格式/开关快照+文件夹的指纹，NoteOutput.kt:81-90）查历史完成记录并**逐一重开校验** refs 仍存在（DownloadQueue.kt:239-265, 415-418）；`skipExisting` 默认 **false**。
- 进度/速度：250ms ticker 采样 bytes/s（B/domain/download/DownloadSpeedTracker.kt），≥1s 落库一次。
- 旧管线（仍被分享入口/选择下载用）：`XHSDownloader` + `XhsDownloadCoordinator`——每候选 3 次 × 350ms×n 退避，候选链 = 变换 URL → 原 URL → original → original 变换（B/domain/download/XhsDownloadCoordinator.kt:29-32, 103-114）；`FileDownloader` 拒 text/html 等非媒体 Content-Type（297-306）、扩展名先 Content-Type 后 URL 推断（359-391）、文件名加 `xhs_` 前缀、桌面 Chrome/91 UA + Referer（290-295）。

### 1.5 落盘 / 命名 [源码实证]

- **文件名** `NoteOutput.fileName`（NoteOutput.kt:69-79）：模板 = 自定义命名开时用 `customNamingTemplate`，否则 **`{postId}`**；token 集合 username/userId/title/postId/publishTime/index/index_padded/downloadTimestamp；publishTime = publishedAt 格式化 `yyyy-MM-dd_HH-mm-ss`；结果过 `safePart`（控制字符与 `\/:*?"<>|` 换 `_`、去尾点、**UTF-8 字节上限 160**，58-67）后拼 `_NN.ext`（index+1 两位补零）。自定义模板默认串 = `{title}({username})_{publishTime}`（B/NamingFormat.kt:24-26）；标题 token 有 50 字符预算的旧管线变体（XHSDownloader.kt:449-469）。
- **文件夹** `NoteOutput.folders`（92-95）：作者归档开 ⇒ `{备注||作者名}_{作者Id}`；笔记归档开 ⇒ `{title}_{noteId}`。均默认 **false**。
- **存储目标**：默认 MediaStore——`Pictures/xhsdn/`、`Movies/xhsdn/`、`Downloads/xhsdn/`（按 MIME，B/data/storage/AndroidStorageSink.kt:688-699）+ 文件夹段；**实况合成对落 `DCIM/xhsdn/`**（97）；IS_PENDING 生命周期 + 重名规避（89-133）。<Q 走公共目录 File + media scan（135-180）。自定义 SAF 目录：COEXIST（provider 给 ` (1)` 名）或 REPLACE（staged 写 + 备份 + 改名，`checkExistingFilesBeforeSave` 默认 **true** ⇒ REPLACE，B/data/storage/StorageDestination.kt:24-38）。
- **写拍摄时间**：`writePublishTime`（默认 false）开且 publishedAt 有值 ⇒ DATE_MODIFIED/DATE_TAKEN 或 setLastModified，失败记 warning（AndroidStorageSink.kt:242-252；DownloadQueue.kt:325）。
- **笔记文案**：`noteFormat`（默认 **NONE**）TXT/MARKDOWN/BOTH ⇒ 生成标题+正文+作者[Id]+时间+tags+canonicalUrl 的 txt/md（NoteOutput.kt:97-104），随任务落盘。

### 1.6 特殊媒体处理 [源码实证]

- **实况照片合成** `LivePhotoCreator` + `MotionPhotoContainer`：
  - 格式 AUTO 按厂商解析（B/data/settings/DownloadOptions.kt:18-29）：oppo/oneplus/realme/oplus→OPLUS、samsung→SAMSUNG、vivo/iqoo→VIVO、**huawei/honor→HUAWEI**、xiaomi/redmi/poco→XIAOMI、其余 STANDARD。
  - 流程（B/LivePhotoCreator.kt:31-76）：静帧解码 → **EXIF 方向归一化转 JPEG（质量 95，196-226, 212）** → 写厂商 EXIF（OPLUS `UserComment=Oplus_8388608`、VIVO 一段 X300 capture-state 串，167-189）→ `MotionPhotoContainer.write` 按格式拼容器（XMP GCamera:MotionPhoto + 各厂商命名空间；SAMSUNG SEFH/SEFT trailer；HUAWEI 60 字节尾含视频帧数；XIAOMI 保留 legacy MiCamera profile；OPLUS 附 MPF——B/MotionPhotoContainer.kt:20-61, 125-217, 236-283）→ 回读校验「JPEG EOI 在视频偏移前 + ftyp 在声明偏移 + 嵌入轨道与源轨道一致」（58-59）。
  - VIVO_LEGACY 双文件对：图尾 + 视频 uuid box，共享 pairId（`UUID(taskId:mediaId)` 前 28 hex，DownloadQueue.kt:346-362；LivePhotoCreator.kt:79-121）。
  - 合成失败 → 回落分别存静帧（`main`）与视频（`motion`）+ warning（DownloadQueue.kt:363-367）。
- **图片方向**：`ImageOrientationUtils`（方向→宽高交换/旋转角）用于缩略图与尺寸读取（B/utils/MediaUtils.kt:113-139）；合成静帧时用于归一化。
- **封面**：视频笔记无实况流的首图 `cover=true`（XhsNoteParser.kt:93-97），默认不下载、仅选择模式可选（NoteOutput.kt:19）。
- **评论图**：默认关；下载时命名后缀 `_comment`（DownloadQueue.kt:303）；评论未加载/有更多时打 warning（NoteOutput.kt:27-32；DownloadQueue.kt:226）。

### 1.7 失败与回落 [源码实证]

- 失败分类（B/domain/download/DownloadContracts.kt:21-30）：InvalidInput / NoMedia / RequiresWebView / Network / Parsing / Storage / Exhausted；映射用户文案（DownloadQueue.kt:420-436）。
- 单资源失败只计数不打断整任务：终态 rollup `failed>0 且有成功 ⇒ PARTIAL；全失败 ⇒ FAILED；全跳过 ⇒ SKIPPED；否则 COMPLETED`（DownloadQueue.kt:386-388）。
- 持久化队列：Room 任务 + 逐资源状态（DOWNLOADING/COMPLETED/SKIPPED/FAILED + bytes）；暂停/恢复/取消/重试（refresh=重新解析）；中断恢复（sessions.recoverInterrupted）；前台服务承载，Android 15 dataSync 超时先落检查点（B/domain/download/DownloadService.kt:48-52）；任务快照（设置+解析结果）随任务持久化，UI 生命周期不拥有网络调用。
- 回落链：图片 `[裸链, sourceUrl, raw]`、视频 `[排序候选…, sourceUrl]`；失败重试在传输层完成，**任务层无额外重试**。

### 1.8 水印相关行为 [源码实证]

**基准无水印去除/修改像素逻辑。** 全仓 grep「watermark/水印」仅命中：
1. `MediaCandidate.watermarked` 标记（streamType 259/309、WEB_LIVEPHOTO_19，XhsNoteParser.kt:201-202）用于**选档排序避开带水印流**（92, 182；NoteOutput.kt:48）；
2. 应用描述文案「origin resolution & no watermark」（res/values/strings.xml:18）——语义 = 下载原图/非水印变体，非去除。
基准与插件 X2（宿主合成链去水印）是**互补关系，无对应物**。

---

## 二、插件现状对照

总判：**解析/选档语义已逐语义对齐（2026-10-15 基准切换落码），取数与传输两端的「外壳」因架构不同不可对齐也无需对齐；未对齐且有移植价值的集中在传输鲁棒性与落盘可审计性。**

| 阶段 | 基准行为 | 插件现状 | 判定 |
|---|---|---|---|
| 取数 | OkHttp 抓 HTML / WebView `__INITIAL_STATE__` + cookie 会话 | X4 okhttp 拦截旁路采集**宿主自身**的 `/api/sns/` API 响应体（imagefeed/detailfeed/videofeed，P/XhsVideoSave.kt:318-353, 373-414）；无抓页、无 cookie 管理 | **插件架构不适用**（宿主进程内已有更权威的登录态 API 流；见 §三·明确不改 1） |
| 解析·图片 | urlDefault→url→infoList→traceId 优先级 | imagefeed `original` 直链优先 → `url` 兜底（P/XhsVideoSave.kt:315-336）；token 规则 = 基准 imageUrls 的 path 切段版（P/XhsImageUrl.kt:79-90，逐条对齐 B/NoteOutput.kt:37-40） | **已对齐**（数据源字段名不同但语义同源：urlDefault ⇔ images_list.original/url）[源码实证] |
| 解析·视频 | originVideoKey + media.stream 逐流候选，watermarked 259/309/WEB_LIVEPHOTO_19 | `video_info_v2` 区段花括号回溯逐流采集（master_url+backup_urls 各成一候选，元数据 last-hit；watermarked 同判，P/XhsVideoSave.kt:416-501）；origin_video_key 采集保留但 app 网络层实测恒 null | **已对齐**（候选结构/水印 flag 逐语义移植）[源码实证] |
| 解析·评论图 | BFS 全收 + WB_DFT 优先 | 批量不采集评论图；单张评论图高清走 X3（宿主 iu4/l → qc8.i.h 同收口，P/XhsHdSave.kt 注释） | **未对齐（有意）**——批量评论图见 §三·明确不改 6 |
| 解析·封面 | cover 标记 + 选择模式可下载 | 不适用（宿主视频保存不涉及封面下载） | **插件架构不适用** |
| 选档·图片 | AUTO：裸链 preferred → sourceUrl → raw | X5：裸链 preferred → raw 一次回落（P/XhsBatchSave.kt:436-441, 527-538）；中间少一层 sourceUrl（其值通常等于 raw，差异无实义） | **已对齐** [源码实证] |
| 选档·图片（X3） | AUTO 裸链 | X3 仍 `toOriginal` = token + `?imageView2/2/format/jpg/q/100`（P/XhsHdSave.kt:67；P/XhsImageUrl.kt:27-46）——**已知偏离**，依据 = 2026-10-14 真机 curl 字节实测（裸链 1.7MB vs q/100 11.1MB，同尺寸高压缩重编码） | **未对齐（有实测依据的偏离）** [真机实证]；收敛方案见 §四·拍板 1 |
| 选档·视频 | watermarked↑ → original↓ → w×h↓（RESOLUTION 默认） | P/XhsVideoSave.kt:1232-1236 同序 `sortedWith(...).first()`；origin 解析成功置顶（original=true, 0 尺寸） | **已对齐**；BITRATE/SIZE/COMPATIBILITY 偏好未实现（见 §三·明确不改 5） |
| 取数·原片 | 基准经 HTML/WebView 拿 `originVideoKey` | 插件 GET 移动版网页 `discovery/item/{id}?xsec_source=app_share&xsec_token=…`（手机 UA + `xiaohongshu` 后缀）取 camelCase `originVideoKey`（P/XhsVideoSave.kt:542-593）——**同一数据源（`__INITIAL_STATE__`）、不同运输方式**，token 来自宿主 share_info.link | **已对齐（异构实现）** [源码实证] |
| 传输·重试 | 逐候选 × 3 次 + 500ms 指数退避（封顶 15s） | X5：裸链失败仅 raw 回落**一次**，无重试退避（P/XhsBatchSave.kt:527-538）；X4 视频：读取 IOException 运行内续传 3 次（1s/2s/4s，P/XhsVideoSave.kt:1340-1353），但仅限已开写的流 | **未对齐**——见 §三·提案 1 |
| 传输·续传 | 跨调用 `.part`+元数据断点续传 | X4 仅运行内 Range 续传；X5 无续传 | **未对齐**（价值有限，见 §三·提案 1 附注） |
| 传输·校验 | Content-Type 黑名单 + **魔数探测** + 字节数比对 + 空间预检 | X5：Content-Type 黑名单 + 50MB 上限（P/XhsBatchSave.kt:569-574, 49）；X4：黑名单 + Content-Length 比对 + IS_PENDING + 回滚（P/XhsVideoSave.kt:1361-1372, 1299-1305）——**均无魔数探测、无空间预检** | **未对齐**——见 §三·提案 3/4 |
| 传输·并发 | 每任务 4 并发 | X5 逐张串行（P/XhsBatchSave.kt:435-445）；X4 单视频本身单流 | **未对齐**——见 §三·提案 2 |
| 落盘·目录 | Pictures\|Movies\|Downloads `/xhsdn/` + 作者/笔记归档文件夹 | X5 `Pictures/小红书`、X4 `Movies/小红书`（宿主既有目录沿用，P/XhsBatchSave.kt:578；P/XhsVideoSave.kt:1302）；无归档文件夹 | **未对齐（有意）**——目录与宿主一致是接管体验的一部分 |
| 落盘·命名 | `{postId}`（默认）/ 自定义模板 + `_NN.ext`，UTF-8 160B 收敛 | X5 `xhs_<时间戳>_<i>.jpg`（P/XhsBatchSave.kt:441）；X4 `xhs_<noteId>_hd.mp4 / _origin.mov`（P/XhsVideoSave.kt:1298） | **未对齐**——X5 文件名不含 noteId，不可审计；见 §三·提案 5 / §四·拍板 2 |
| 落盘·写时间戳 | writePublishTime 可选 | 无 | **未对齐（低价值）** |
| 落盘·笔记文案 | TXT/MD 可选 | 无 | **未对齐**——产品决策，§四·拍板 5 |
| 特殊·实况照片 | 7 厂商合成 + VIVO 双文件对 | 无（插件边界明确不覆盖宿主 live photo zip 分支） | **未对齐**——高成本立项，§四·拍板 4 |
| 特殊·图片方向 | EXIF 归一化（合成/缩略图用） | 不适用（无合成/缩略图场景） | **插件架构不适用** |
| 失败·回落 | 失败分类 + PARTIAL/FAILED/SKIPPED rollup + 持久化队列 | X5 图集采不到 ⇒ **回落宿主原生保存**（护栏+新鲜度门，P/XhsBatchSave.kt:177-183）；HD 去重（5min+落盘）+ `.pending-` 孤儿清理（P/XhsVideoSave.kt:719-745, 957-989）；无队列（保存即时完成） | **部分对齐 + 插件特有层**（宿主回落是基准没有的架构优势） |
| 水印 | 无去除逻辑，选档避水印流 | X2 = 宿主合成链去水印（X2a/b/c，P/XhsWatermark.kt）；X4 选档 watermarked flag 已对齐 | **基准无对应物**——插件能力超出基准 |

### 已知偏离项（如实登记）

1. **X3 q/100 vs 基准 AUTO 裸链**：上表已述。基准 AUTO 的 preferred 也是裸链（sns-img-bd），意味着**按基准抄写的 X5 在部分 token 上拿到的可能也是压缩重编码档**——插件 2026-10-14 实测与基准的「bare = 原图」假设矛盾 [真机实证 vs 基准源码实证]。这是本对照最重要的语义分叉，见 §四·拍板 1。
2. **X4 origin 经网页解析**：基准从 HTML/WebView 拿 originVideoKey；插件用宿主 share link + 移动版网页 GET。数据源同（`__INITIAL_STATE__`），实现异构，已在 2026-10-14 真机验收（116.7MB 原片落盘）[真机实证]。
3. **X4 无 sourceUrl 兜底**：基准视频候选尾补 `video.sourceUrl`；插件候选耗尽即 HD 下载失败，宿主自存的低画质文件仍在——失败语义可接受，不补。
4. **X4 下载 UA 无 `xiaohongshu` 后缀**（Chrome/120 Android 13，P/XhsVideoSave.kt:1274）vs 基准传输 UA 带 `xiaohongshu` 后缀（ResumableTransfer.kt:193）。CDN 对媒体请求不校验后缀（真机多次成功）[真机实证]；仅当未来 CDN 收紧才需对齐。
5. **插件独有**：去水印（X2）、保存解锁（X1）、宿主回落护栏、孤儿清理、双卡握手——基准无此层。

---

## 三、插件逻辑设计提案

只列有差距且值得做的项；已落码的选档/图片链语义不重复设计。

### 提案 1：X5/X4 传输重试阶梯 + 退避（基准 ResumableTransfer 语义）——建议 P1

- **目标行为**：抄 B/ResumableTransfer.kt:42-58——单 URL 失败（网络类）重试 `maxRetries` 次（可固定 2–3），退避 500ms<<n 封顶 15s；候选间也走阶梯（裸链 → raw 各自重试）。
- **插件落点**：`XhsBatchSave.downloadOne/fetchOne`（把「仅一次回落」扩成「候选 × 重试」双层）；`XhsVideoSave.downloadVideoHd` 已有续传重试，仅需对齐退避基数（现状 1s/2s/4s，无大碍，可不动）。
- **风险**：低——对死链多等几秒；加封顶即可。批量中途断网的「部分成功」语义不变。
- **收益**：批量 20 张里 1–2 张瞬时抖动不再静默丢张 [合理推断：当前串行单发无重试，抖动即丢]。

### 提案 2：批量并发 4 路（基准 Semaphore(4)）——建议 P2

- **目标行为**：抄 B/DownloadQueue.kt:266——图集批量下载 4 并发。
- **落点**：`XhsBatchSave.downloadAll`（Thread 内改固定 4 线程池 + CountDownLatch 汇总）。
- **风险**：低——基准同参数长期在用；CDN per-host 8 连接上限内。进度汇总逻辑要线程安全（ok/bytes 计数）。

### 提案 3：落盘前魔数探测（基准 MediaFileType.detect）——建议 P2

- **目标行为**：写盘前读前 64 字节跑签名表（jpg/png/gif/webp/avif/heic/mov/mp4/webm，B/data/network/MediaFileType.kt:8-28），认不出 = 按失败处理（X5 走回落，X4 走回滚）。
- **落点**：`XhsBatchSave.fetchOne`（200 分支先读首块判定再落 MediaStore）与 `XhsVideoSave.downloadVideoHd`（终检补一层）。注意 octet-stream 合法放行由魔数兜住——比黑名单更强且不误杀。
- **风险**：低。扩展名/MIME 仍按现状（魔数可顺带纠正 extOf 猜错的情况）。

### 提案 4：磁盘空间预检（基准 NO_SPACE）——建议 P3

- **目标行为**：下载前 `File(dir).usableSpace < 预期 + 1MB` 即放弃（B/ResumableTransfer.kt:137-139）。视频有 Content-Length 可预判；图片无长度则按 50MB 上限预检。
- **落点**：`XhsBatchSave.downloadAll` 开头一次性预检 + `downloadVideoHd` 拿到 total 后校验。
- **风险**：几乎为零。避免 MediaStore 写一半失败留下的清理路径。

### 提案 5：X5 文件名可审计化（基准 `{postId}` 命名）——建议 P2，需拍板（§四·拍板 2）

- **目标行为**：`xhs_<noteId>_<i>.jpg`（noteId 批量链路里现成，P/XhsVideoSave.lastNoteImages 的调用方可顺带取当前 noteId）；时间戳可保留做防撞后缀。基准的模板化命名（§一·1.5）不建议整搬——宿主内无设置界面承载。
- **落点**：`XhsBatchSave.downloadAll` 文件名参数 + `XhsVideoSave.lastNoteImages` 旁暴露当前 noteId（已有 `currentNoteId` 字段）。
- **风险**：低。文件管理器里同一笔记的批量产物可归组、可溯源——符合 Core Value「可审计」。

### 明确不改的项（及理由）

1. **基准 WebView/HTML 取数链 + cookie 会话（SessionCredentials/XhsNetwork 拦截器）**：插件活在宿主进程内，宿主自己就是登录态客户端，数据从宿主 API 响应旁路采集即可，且比独立 app 抓 HTML 更权威（字段结构化、有宿主已解好的 share token）。基准的 WebView 链是为「独立 app 无登录态」设计的，搬进插件无意义且引入反向依赖。**架构不适用。**
2. **短链解析（XhsUrlParser.resolveShortUrl）**：插件数据来自宿主 API 响应体与 share_info.link（已是完整 URL），无短链输入。不适用。
3. **代理/超时/重试次数等设置项（DownloadOptions 大半字段）**：宿主内无插件设置界面；远端 prefs 只承载功能开关。策略固定 = 基准默认值。不适用。
4. **持久化下载队列 / 任务历史 / 断点元数据（Room + ResumableTransfer 落盘 .part）**：插件保存动作即时完成（秒级—分钟级），孤儿清理 + HD 去重已覆盖主要鲁棒性诉求；引入队列 = 新增持久化面与兼容负担。跨进程续传对 ≤几 MB 的图无价值，对 100MB 原片进程被杀场景由 `.pending-` 孤儿清理兜底（重下）。**不做**（若拍板要做，见 §四·拍板 4 附带）。
5. **视频档位偏好 BITRATE/SIZE/COMPATIBILITY**：无配置面承载；固定 RESOLUTION = 基准默认。不做。
6. **批量评论图采集**：X3 已覆盖「用户手动保存评论图走高清」（单一收口 qc8.i.i）；批量采集需新增评论端点解析（字段形态与笔记图不同，WB_DFT 场景优先级链），收益低。不做。
7. **基准旧管线（XHSDownloader facade / XhsDownloadCoordinator / FileDownloader 直落）**：基准自身的新旧两套并存是其迁移包袱；插件只有一套链路。不适用。
8. **ImageOrientationUtils / MediaUtils 缩略图体系**：插件无合成/预览场景。不适用。
9. **X3 的 q/100**：**暂不改**——它与基准 AUTO 的分歧是「唯一基准原则」vs「真机字节实证」的冲突，等拍板（§四·拍板 1），不先动。

---

## 四、待用户拍板清单

1. **X5 图片直链长期语义**（最重要）：
   - A. 维持现状——X5 裸链（对齐基准 AUTO），X3 q/100（对齐字节实测）；接受两引擎语义分叉。
   - B. X5 回改 q/100（违背「基准唯一」原则，但有 2026-10-14 真机字节实证支撑：裸链 = 同尺寸高压缩重编码）。
   - C. 双轨：裸链失败**或落盘字节 < 某阈值（如 < 2MB 且非小图）**时自动用 q/100 重试一次——以基准列表序为骨架、以实测为修正。
   - 附：可先做一次**对照取证**（同一批 token 三态字节对比：裸链 / q/100 / 客户端自存），把 [真机实证] 补成双盲数据再拍。
2. **X5 文件名**：维持 `xhs_<ts>_<i>` / 改 `xhs_<noteId>_<i>`（提案 5）/ 两者拼接。涉及用户相册里的呈现。
3. **同笔记重复保存去重**：基准 `skipExisting`（默认关）语义是否要——按 noteId+index 查 MediaStore 已有同名即跳过（默认关、可远端开）？现状重复保存 = 重复落盘。
4. **实况照片（Live Photo）是否立项**：插件侧补「imageList livePhoto stream 采集 + LivePhotoCreator 移植（7 厂商容器合成）」。高成本（容器字节级兼容矩阵 + 真机多机型验证），基准代码可直接抄但验证面大。明确不做则维持「live photo 双文件分支不覆盖」边界。
5. **笔记文案保存（txt/md）**：基准 noteFormat 能力是否要——X5 接管保存时可顺带落一仲 `.txt`（文案宿主 API 里有）。低成本，纯产品决策。
6. **批量并发 4 路**（提案 2）：无悬念的话默认做；除非担心低端机 4 连发发热。
7. **跨进程断点续传**（仅 X4 原片 >100MB 场景有价值）：是否值得把 ResumableTransfer 的 `.part`+元数据落盘机制搬进插件（孤儿清理已有，续传是增量收益）。

---

## 五、证据分级汇总

- 本文所有 `文件:行` 引用均出自直接通读：基准 44 个业务 kt（UI Compose 文件仅读业务函数）+ 插件 6 个 kt 全文 + xhs_extractor.js/xhs_account.js，标 `[源码实证]`。
- `[真机实证]` 条目引自 `docs/reverse/xhs.md` §三/§六 与引擎注释记载的 2026-10-13～15 真机测量（含 116.7MB 原片落盘、裸链 vs q/100 字节对比、0/4 与 installed N/M 判据），本分析未重复上机。
- `[合理推断]` 仅两处：提案 1 的「抖动即丢张」（由 X5 串行单发无重试的代码事实推出，未实测丢张率）；基准 AUTO 裸链「部分 token 非原始字节」的反向推论（正向证据是插件 2026-10-14 双 token 实测，基准自身未测字节）。
- `[未取证]`：拍板 1 的 C 方案阈值策略、提案 3 的魔数探测对 octet-stream 原图的实际命中率——落码前建议先跑 §四·拍板 1 附的对照取证。
- 与 `docs/reverse/xhs.md` 的冲突处理：xhs.md §三 X5「先 proceed 后弹窗」已被 2026-10-15 接管路线 A 取代（xhs.md:149 已自标注作废），本文一律以代码现状（P/XhsBatchSave.kt:175-199 全接管）为准。
