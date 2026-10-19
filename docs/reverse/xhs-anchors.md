# 小红书挂载点锚点登记表（唯一事实源）

> **用途**：宿主版本适配时**不从零逆向**——拿本表锚行去新版本反编译产物 grep，命中即定位，
> 混淆名必漂、锚行不改（锚行规范见 memory `hook-anchor-line-convention`）。
> **维护纪律**：新选点/换点必须同步本表 + `xhs.md` §八对照表；adapt 脚本校验以本表为准。
> 表内 `锚行` = 方法体内识别性最高的原文（jadx java 形态，jadx 版本差异可能改排版，grep 用引号内子串）。

## 数据表（2026-10-19 基线 9.50.0）

| 挂载点 | 类#方法 | 锚行（grep 子串） | 产物位置 | 验证版本 |
|---|---|---|---|---|
| X1b 解锁 | `kd.c` getter（`@kd.c("enable")` 注解族） | `"enable"` 注解 + FunctionSwitch 语义（注解锚非串锚） | classes2 | 9.49/9.50 零漂移 |
| X2b 水印 | `nd8.j#b(Context,boolean)` | `"andr_image_save_white_bg_optimized"`（DexHint 同串）；b() 特征 = inflate layout→view→Bitmap，alpha 0.56 | classes6 | 9.50（旧 qc8.j） |
| X3 原图 | `nd8.i#i`（11 参，args[3]=图 url） | `"saveImageBitmapNull"` / `"getBitmapFromFEPlatform failed"` / `"fetch image failed"` / tag `"SaveImagesHelper"` 四串锚；`Uri.parse(str3)`（nd8/i.java:245） | classes6 | 9.50（旧 qc8.i） |
| X4 直链 | `okhttp3.OkHttpClient$Builder#build`（未混淆） | 不需要锚（公开 API）；判据 = `X4: installed 1/1` + 拦截日志 | — | 全版本 |
| X5 图片保存（**仅笔记级 feedback 面板路径**，findings-2026-10-19-x5img：图片长按不走此点） | `ji9.b#invoke(Object)` | `"onDownloadClick, noteId="`（ji9/b.java:55）；`"download blocked, disableSaveMedia=true"`（:63） | classes7 | 9.50（旧 mh9.b） |
| X5 视频保存 | `a59.q#invoke(Object)` | 方法体无串常量 ⇒ `saveVideo(noteFeed.u0())` 调用形（a59/q.java:53）+ 字段 `(int, com.xingin.entities.notedetail.NoteFeed)`；`u0()`=noteId（`"onDownloadClick, noteId=" + cVar.Ta().u0()` 旁证，ji9/b.java:55） | classes9 | 9.50（旧 d49.q 误绑教训） |
| X5 面板收口 | `ji9.c#i`（面板 dismiss） | ji9/c.java:28,200（面板构造） | classes7 | 9.50 |
| 图片长按入口（未挂，X5 触发候选④） | `AsyncImageGalleryController#e3`（未混淆） | `"onImageLongClick, currentIndex="`（AsyncImageGalleryController.java:2321） | classes20 | 9.50 |
| 图片批量编排总闸（未挂，候选③） | `ek9.j#a`（NoteImageSaveHelper 接口实现，未混淆） | `"imageListSave start, count="`（ek9/a0.java:57） | classes9 | 9.50 |
| 图片落盘成功回调（未挂，候选②） | `nd8.e`（写相册收口） | `"save file to Album success: "`（nd8/e.java:39） | classes6 | 9.50 |
| 图片保存权限门（未挂，候选⑤） | `nd8.i#g`（:232，jadx 失败体） | `ib2.q.a.a()` 条件 + `ib2.m.f(...)`（权限申请 helper，hx8/m.java:360 同签名实证） | classes6 | 9.50 |
| 笔记级面板门（X1c 同族） | `ji9.e` / `ji9.g` | `"DownloadLinkerProvider", "enable: type="`（ji9/g.java:70） | classes7 | 9.50 |
| 视频落盘收口（未挂，V-5 清理锚） | `idc.w`（:730-760 区域） | 宿主视频 MediaStore insert 收口，三处调用 `r01/p.java:27` 等 | classes2 | 9.49.1 定位 |

## 已知非挂载但关键的宿主结构（业务图谱见 xhs.md §九）

| 结构 | 锚 | 用途 |
|---|---|---|
| note 详情 gson 模型 | `gsa/i.java`：`@kd.c("video_info_v2")`（:164）、`@kd.c("images_list")`（:88）、`@kd.c("function_switch")` | 载荷字段名漂移探测 |
| note 详情模型（另一套） | `jn9/e.java`：`function_switch`（:95）、`media_save_config`（:107） | 同上 |
| arch kotlinx 媒体模型 | `com/xingin/arch/model/note/media/video/MediaParser.java`：`getFirstStreamUrl`（`stream.h264[0].master_url`） | 新一代容器（AIGC 笔记疑走此路） |
| 视频保存 API | `NoteDetailService.java:209` `@f("/api/sns/v10/note/video/save")` | 端点漂移探测 |
| 视频流端点 | `VideoFeedNetService.java:25` `@f("/api/sns/v4/note/videofeed")`（宿主 rec.xiaohongshu.com） | 同上 |

## 适配流程（浓缩版，全流程见 skill `xhs-recon`）

1. 读本表 → 2. 按 xhs.md §八头部定向反编译命令出产物 → 3. 逐锚 grep →
   4. 命中=核对签名零漂；miss=按锚行语义找新包名 → 5. 更新本表两处（表+验证版本列）
   6. `adapt_new_version.py` 只做辅助（xhs 点会被误归 thememanager 包，报告仅供人读，见 2026-10-19 实证）
