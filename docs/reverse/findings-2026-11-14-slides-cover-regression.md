# findings 2026-11-14 · slides 混合笔记只下视频回归（cover 口径二次踩坑）

## 现象

v1.3.1 真机保存一篇「带图+视频」笔记（noteId=6af6d9a8000000002a038147，图21 视频1），只落
`Movies/小红书/xhs_*.hd.mp4`，无「混合笔记图片并入」日志 ⇒ imageUrls 空。2026-11-04 修过
「视频+图片只检测到视频」（d3c89c5c），当时口径 `filterNot { it.cover || it.commentId != null }`——
**第二次报障同类**，说明口径本身有洞，不是本次改动弄丢。

## 根因（实证）

**slides_simulate_post 快闪视频**：type=video 的笔记由 N 张图合成视频，上游
`reference/xhs/XHS_Downloader_Android/.../data/xhs/XhsNoteParser.kt`（~:116-118）
`val isCover = isVideoNote && streams.isEmpty()`——该笔记 streams 非空只产出视频流，
**全部 21 张 slide 被标 cover=true**。旧口径把 21 张全滤 ⇒ 只剩视频。解析侧 originals
必非空（parser `if (!isHttp(original)) continue`），排除空 URL 说；挂载层全绿
（X2 3/3、X4 1/1、X5 5/5、X1 3/3），纯行为问题。

## 修复

共享口径函数 **`xhsContentImageUrls(note: ResolvedNote?, noteId: String): List<String>`**
（XhsBatchSave.kt 文件顶层，internal；不在 object 内——object 构造带 Handler/Activity 追踪，
JVM 单测不可初始化）：

- cover 只在「存在非 cover 图，或全列表唯一图（纯视频笔记封面帧）」时滤：
  `coverAreSlides = imgs.size > 1 && imgs.all { it.cover }`，滤式
  `filterNot { (it.cover && !coverAreSlides) || it.commentId != null }`
- 两路调用点收口：XhsBatchSave 图片分支（~:636）+ XhsVideoSave.offerHdDownloadFromNote（~:996）
- livePhotos 静帧原图 + XhsCommentHarvest.urlsOf 并入（原两处各自重复的口径合一）
- 契约钉：`app/app/src/test/kotlin/top/honorhook/xposed/target/xhs/XhsContentImageUrlsTest.kt`
  （slides 全保留 / 单 cover 纯视频滤 / 混合滤 / livePhoto 展开 / 去重保序 / null note）

## 纪律沉淀

- **cover 语义按上游 parser 定**：type=video + 全 cover 多图 ≠ 封面帧，是正文 slide。
  判「全列表唯一图才当 cover 滤」而非逐张滤——凡 filter{cover} 都要想 slides_simulate。
- 口径两处重复实现是这次回归的温床：收口单函数后，第三次改口径只动一处。

## 复测判据（用户 re-save 那条 slides 笔记）

1. 日志现「混合笔记图片并入 21 张 noteId=…」
2. `Pictures/小红书/` 落 21 图 + `Movies/小红书/` 落 hd.mp4
3. 图片分支（长按图片页保存）也应 21 张，不再「分享链接解析无可用图片」
