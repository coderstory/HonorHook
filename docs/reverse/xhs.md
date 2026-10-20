# 小红书（com.xingin.xhs）逆向与 hook 设计

> 2026-10-13 · 新目标 U-X1~U-X5。移植源：com.chekayo.xhssavefix 1.2.3（`reference/xhs/savefix-base.apk`，
> jadx 产物 `system-files/xhssavefix-jadx/`）+ JoeanAmier/XHS-Downloader 图片/视频直链重建逻辑
> （`reference/xhs/XHS-Downloader/`，application/image.py、video.py）+ 作者 9.33.4 期分析文档
> `reference/xhs/reverse-notes.md`。
> 目标宿主 **9.50.0**（dumpsys 实证 2026-10-19；9.49.1 versionCode 9491803 为上一基线）。反编译：
> `system-files/xhs-jadx/`（9.49.1 全量，历史参考）+ `system-files/xhs-partial/`（9.49.1 classes2/6/7/9/20
> 定向）+ `.buildtmp/xhs950-partial/`（9.50.0 同五 dex 定向）。
> **禁止全量反编译 xhs**：183MB APK 全量 jadx 跑 40+ 分钟卡 65%（9.50.0 一轮 67% 崩溃实证）；
> 只做定向——五 dex 单独 jadx 5 分钟出 40262 个 java，覆盖全部挂载链路（两版本实证）。命令：
> `unzip -o .buildtmp/xhs-950.apk 'classes2.dex' 'classes6.dex' 'classes7.dex' 'classes9.dex' 'classes20.dex' -d .buildtmp/xhs950-dex/`
> 然后 `jadx --no-res -j 8 -d .buildtmp/xhs950-partial .buildtmp/xhs950-dex/`（JDK 21）。
> **每点附「重定位」**：新宿主版本适配时按备注锚点重找（混淆名必漂，锚点不改）。

## 一、需求

1. 解除「作者关闭下载/保存」限制（服务端 FunctionSwitch/MediaSaveConfig 下发，客户端 hook 唯一入口）
2. 保存走原版无压缩图（XHS-Downloader 实证：app 默认存的是显示用压缩变体，token 重建直链为原图）
3. 保存无水印（作者水印 + AI「生成」标签）
4. 视频保存：`/api/sns/v10/note/video/save` 响应改写（**仅 `disable` 解锁有效**）+ 外挂 probeSize 选最大档直下（`origin_video_key` 在 app 网络层**不存在**，见 §三 X4 实证）
5. 评论图片（用户上传的图片评论）同走高清无水印
6. 批量下载开关：多图帖子一键全存

边界：付费内容（`PAID_IMAGE_DOWNLOAD`）不绕过；live photo（vivo/oppo zip 双文件分支）不覆盖。

## 二、保存链路（9.49.1 实证）

```
长按图/视频 → sb9/y（onDownloadClick lambda，classes7）
  ├─ 图片: ISaveImageProxy（SPI，未混淆接口）
  │    └─ com.xingin.image.SaveImageProxyImpl.save(activity, fileid, 头像url, 图url, 滤镜url, z, okCb, errCb)
  │         └─ qc8.i.e → h → g → i   （qc8 = SaveImagesHelper 族，9.33.4 名 jm7.i/j）
  │              └─ i.i: Uri.parse(str3=图url) → Fresco 解码（磁盘缓存按 url 键）
  │                   → qc8.j.a(原图, 作者水印, 滤镜, AI标签, disableWaterMark, disableAi)  合成
  │                   → qc8.e 写相册
  ├─ 评论图: iu4/l（MediaBrowserNoteCommentSaveItemActionHelper，classes6）
  │    └─ qc8.i.h 直调（绕过 SPI proxy）——与笔记图汇于 qc8.i.i 的 str3 解码源 ★单一收口
  ├─ 视频: d49/q → NoteDetailService.saveVideo(note_id)（retrofit，classes9）
  │    └─ GET /api/sns/v10/note/video/save → 响应 disable:true + 无 download_url = 服务端拒
  └─ 门: mh9/b（DownloadController.onDownloadClick，classes2）
       └─ note.Qa().h1().b()（MediaSaveConfig.disable_save）true → toast「download blocked」拦截
```

清晰度差距根源：`qc8.i.i` 拿显示 URL（`sns-webpic-*` 带 `!` 裁剪后缀）进 Fresco，存的就是压缩位图。
XHS-Downloader 的重建：URL 第 6 段起取 token、剥离 `!` 后缀 → `https://sns-img-bd.xhscdn.com/{token}?imageView2/2/format/jpg/q/100` 原图直链（**必须带 q/100 参数**：只剥参数不加回会拿到同尺寸高压缩重编码，见 §六 X5 实证）。

## 三、挂载点总表

| # | 开关 | 点 | 层 | 9.33.4 对照 |
|---|---|---|---|---|
| X1a | save_unlock | `MediaSaveConfig.b()` → false | 未混淆直连 | 同名同号 |
| X1b | save_unlock | `FunctionSwitch.a()` → true（type∈image/video_download） | 未混淆直连 | 同名同号 |
| X1c | save_unlock | `ShareInfoDetail$Operate.a()` → false（同 type） | 未混淆直连 | 同名同号 |
| X2a | no_watermark | `MediaSaveConfig.c()` → true | 未混淆直连 | 同名同号 |
| X2b | no_watermark | `qc8.j.b(Context, boolean)` → null | 混淆·串锚 | jm7.j.b |
| X2c | no_watermark | ImageBean disable_ai getter → true | 未混淆类·运行时注解定位 | jm7 期 ImageBean.u()，9.49.1=v()，**方法号漂移实证** |
| X3 | hd_save | `qc8.i.i(...)` args[3] 图 url → token 重建直链 | 混淆·串锚 DexKit | jm7.i 系 |
| X4 | video_save | `okhttp3.OkHttpClient$Builder.build()` 注入 Interceptor 代理 | 公开 API | 同 |
| X5 | batch_save | `mh9.b` 保存点击 → **模块全接管（不 proceed）+ 外挂直下**（imagefeed 采集 + HttpURLConnection + MediaStore）：1 张静默直下、≥2 张弹确认卡、采不到图集回落宿主原生保存 | 混淆·双日志串锚 DexKit | — |

### 每点重定位备注

**X1a/X2a `com.xingin.entities.MediaSaveConfig`**（classes5）
- 类名未混淆。`b()` = `@kd.c("disable_save")` 字段 getter；`c()` = `@kd.c("disable_watermark")` 字段 getter。
- 9.33.4→9.49.1 方法号零漂移（jadx 双版本对照）。
- 重定位：类名直查；方法号变了就按 JSON 注解找字段 → 该 boolean 字段的无参 getter。DexKit 兜底：dexStrings `disable_save`/`disable_watermark` + 类名串。
- 失败分级：FAILED（重试无意义——类不存在说明结构大改）。

**X1b `com.xingin.entities.notedetail.FunctionSwitch`**（classes5）
- 类名未混淆。`a()` = `@kd.c("enable")` getter；`c()` = `@kd.c("type")` getter。
- type 判定用字符串 `image_download`/`video_download`（服务端下发原文；客户端枚举 `notedetail/p.java` 未混淆但**不依赖它**，直比字符串）。
- 重定位：同 X1a。**必须 hook 后动态取 `c()` 比对再改**，不能见 `a()` 就 true——其它 type（如 `paid_image_download`）会被误放。
- 失败分级：FAILED。

**X1c `com.xingin.entities.ShareInfoDetail$Operate`**
- 类名未混淆（内部类 `$` 分隔）。`a()` = disable 位 getter；type 取 getter 读 `@kd.c("type")` 字段（9.49.1 方法号 g() 系 jm7 期同号，仍按注解定位）。
- 重定位：同上；encoder 注意 `$`。

**X2b `qc8.j.b(Context, boolean)` → null**（classes6）
- 混淆名。锚：同类含日志串 `"disableAiWaterMark is "`（j.a 方法体）；b() 特征 = LayoutInflater inflate layout → view → Bitmap（`com.xingin.xhs.thread_monitor_lib` 的 BitmapFactoryProxy 代理调用）。
- 重定位：DexKit dexStrings `["disableAiWaterMark is ", "SaveImagesHelper"]` → 同类中 1 个 (Context, boolean) → Bitmap 方法。上游 jm7.j.b（9.33.4）。
- 失败分级：NOT_RESOLVED（随类加载重试；拿不到 = AIGC 帖仍画 AI 标签，可接受降级——作者水印走 X2a/X2c 不受影响）。

**X2c ImageBean disable_ai getter → true**
- `com.xingin.entities.ImageBean` 类名未混淆；**方法号已实证漂移**（9.33.4 `u()` → 9.49.1 `v()`；savefix 1.2.3 原样装 9.49.1 会挂到 `u0()`（original getter，返回 String），setResult(Boolean) 类型不匹配）。
- 实现：**运行时注解反射**——install 期扫 `ImageBean.declaredFields` 找 `kd.c` 注解值 == `"disable_ai"` 的字段 f，再扫 `declaredMethods` 找无参、返回 boolean、方法体读 f 的 getter（默认双参兜底 `getX()` 命名不可靠），挂 after → true。
- 重定位：注解串 `"disable_ai"` 不漂；字段名/方法号全漂移免疫。**adapt_new_version.py 盲区**：运行时解析无静态挂载点，该 target 的脚本核对点数会比源码少 1，属预期（脚本口径记录）。
- 失败分级：NOT_RESOLVED（注解找不到 = 服务端改字段名，静默降级为 AI 标签可能保留）。

**X3 `qc8.i.i` args[3] 图 url 替换**（classes6）
- 混淆名。方法签名（11 参）：`(Activity, String×4, boolean, Function1×2, boolean×3)`；`args[3]` = 解码源图 url（`Uri.parse(args[3])` 进 Fresco 的那个）。
- 锚（DexKit dexClassStrings，同类内）：`"saveImageBitmapNull"`、`"getBitmapFromFEPlatform failed"`、`"fetch image failed"`、tag 串 `"SaveImagesHelper"`——四个串全在 classes6 dex 字符串池（2026-10-13 grep 实证）。
- 替换规则（XHS-Downloader image.py 实证移植）：
  1. url 非 http/https 或 host 不含 `xhscdn.com`/`xiaohongshu.com` → 原样
  2. host 已是 `sns-img-bd.xhscdn.com` 且 path 无 `!` → 原样（防重入）
  3. token = path 全段重组（等价 `split("/")[5:].join("/")`）后截到第一个 `!` → `https://sns-img-bd.xhscdn.com/{token}?imageView2/2/format/jpg/q/100`（**q/100 不可省**：2026-10-14 实测同尺寸下裸链 1,727,058 B vs q/100 11,114,503 B；`toBare()` 为不带参数的回落用裸链）
  4. 每 note 运行期 LRU 记忆已替换 url，省重复串操作
- 覆盖面：笔记图（SPI 路径）+ 评论图（iu4/l 直调 qc8.i.h，h 是 i 的桥）——**一处 hook 双覆盖**。
- 边界：live photo（vivo/oppo 双文件 zip 分支，iu4/l 内）不走 i.i 的单图解码，不覆盖；Fresco 缓存 miss 会走网络拉原图（正常路径）。
- 重定位：四串锚 + 11 参特征。上游 jm7.i 系（9.33.4）。
- 失败分级：NOT_RESOLVED。

**X4 `okhttp3.OkHttpClient$Builder.build()` + Interceptor 动态代理**
- 公开库 API（okhttp3 未 shade——dex 串池实证有 `okhttp3.Interceptor`/`okhttp3.Request$Builder`）。
- before build() 反射读 `interceptors` 字段加代理（savefix 同款；字段名 `interceptors` 为 okhttp 公开源码字段，跨版稳定）。
- 代理逻辑（savefix 移植 + 升级）：
  - url 含 `/api/sns/` → `peekBody(512KB)`：
    - 扫 `"origin_video_key"\s*:\s*"([^"]+)"`（P_ORIGIN_KEY）→ 优先。**实证作废（2026-10-14）**：
      该字段**不在 app 网络层**。全 dex 串池 grep 仅 1 处命中，在 `classes20.dex`
      `com/xingin/arch/adapter/model/NoteDetailArgumentsExtensionsKt.java:176-180`，形态是
      **Bundle extra**（`h.c(yVar, "origin_video_key", strA)`，`strA = VideoInfo.A()` = `VideoInfo.id`）；
      app API 实体（`entities/video/Consumer.java`）无此字段，`originVideoKey` 全 dex 0 命中
      ⇒ 本分支**恒 miss**（app 网络层无此字段）；原始档已改由「移动版网页 `__INITIAL_STATE__`」路线取得（见本节下方），此处仅存分支占位，无调用价值
    - **候选全量采集（2026-10-14 真机定稿，取代旧 bestStreamOf 打分）**：`video_info_v2` 区段内收集**全部** `master_url`（覆盖 `MediaStream{h264[],h265[],h266[]}` 多编解码多档位）+ 同流首个 `backup_urls[]`，去重成候选表，交给下载前 `probeSize`（HEAD / `Range: bytes=0-0` 读 `Content-Length`）**按实测字节选最大**。旧法（height 主序 + avg_bitrate 的 1200 字节窗口打分）真机漏档：只拿到 `_258`/`_4610`，漏 `_533`/`_526`
    - `video_info_v2` 区段 h264 `"master_url"`（savefix 原版 P_MASTER_MP4 门限）已并入上面的候选全量采集，不再单独占一档
    - noteId = 同区段最近 `"(?:id|note_id)"\s*:\s*"([0-9a-f]{16,32})"`（savefix 同款）；map 上限 500 清空
  - url 含 `/note/video/save` 且 body 含 `"disable"`：
    - `"disable":true` → `"disable":false`；有 `"status"` 则统一 `"status":2`，无则在 disable 后插 `"status":2`
    - 注 `"download_url"`：map[noteId] 命中时——**origin_video_key 优先**（`https://sns-video-bd.xhscdn.com/{key}`，XHS-Downloader video.py 语义），回落全流最高档，再回落 master_url（savefix 只到这层）。**注：此改写对客户端无效，见下「客户端不吃 download_url」实证**
    - `ResponseBody.create` 三轨重建（Kotlin Companion → create(MediaType,String) → create(String,MediaType)，savefix 同款容错）
  - XHS-Downloader 的多 stream 按 bitrate/resistance 排序**不搬**：选档交给 `probeSize` 实测字节数（旧 height/bitrate 窗口打分法定档实错，见上条）。
  - **客户端不吃 `download_url`（2026-10-14 真机实证，覆盖此前全部推断）**：01:17 日志 `视频响应已换链 … → ..._258.mp4`，用户实测客户端仍落 ~2.9MB（= 服务器原 `_4610`）⇒ 响应改写对客户端保存档位**无影响**。X4 响应改写的有效语义**只剩 `disable` 解锁**，换链为死路径（`swapUrl()` 依赖的 originKey 恒 null）。
  - **`_533` 只是最高「转码」档，不是原始档（2026-10-14 用户实证修正）**：本 note 响应体采出候选 **8 个** = `_258`(4148KB) / **`_533`(6122KB)** / `_331`(1607KB) / `_526`(4455KB) / `_4610`(2859KB) + 各自 `sns-bak-v6` 镜像，取最大落盘 = 1440x1920 hvc1(HEVC) 8.685s。**但第三方独立下载器对同一笔记拿到 117MB**（≈109 Mbps，8.6s）⇒ 真原始档存在且远大于梯子最大值，**不在 app API 的 `media.stream` 梯子里**。上一轮据此写下的「原始档就在响应体内 / V-9 作废」**作废，V-9 类路线重新回到候选**。
  - **原始档路线已解决并真机验收（2026-10-14，本 note 真机落盘 116,714,755 B）**：原始档 = 作者上传原片，**只在移动版网页** `__INITIAL_STATE__` 的 `originVideoKey`（camelCase），URL = `https://sns-video-bd.xhscdn.com/{key}`。模块落地链：
    1. **token 采集** `harvestNoteShare`：从 app 响应里按 noteId 收 `share_info.link`（已含 token 的完整 URL，优先原样用）/ 同级 `xsec_token`。
    2. **取页** `resolveOriginVideoUrl`：GET `discovery/item/{noteId}?xsec_source=app_share&xsec_token=…`，UA 必须**手机版**且结尾带 `xiaohongshu` 后缀（桌面 UA 的 state **无 `consumer`/无 `originVideoKey`**）；`xsec_source=pc_feed` 会 302 到登录页。**不需要登录 Cookie**。
    3. 正则取 `"originVideoKey":"…"` → **还原 `\uXXXX` 转义**（HTML 里是 `spectrum/1040g…`）→ 拼 URL。实测 key 前缀有 `spectrum/` 与 `pre_post/` 两种，**不得假设前缀**。
    4. 原片 URL 并入候选表，仍由 `probeSize` 按实测字节选（不写死优先）。
    - 真机判据：`原片解析成功 … https://sns-video-bd.xhscdn.com/spectrum/1040g0jg…` + `选档 … 原片=yes 选中=113979KB 候选=15个` + `HD直下完成 … 113979KB → Movies/小红书/xhs_…_origin.mov`；落盘文件 `tkhd 3336x4448` / `mvhd 8.685s`，与 app 自报 `video.width/height = 3336x4448` **完全一致** ⇒ 是真原片非转码（客户端自存那份仅 1,646,277 B）。
  - **梯子最大档是 4K，不是原片（修正上一轮结论）**：`candidatesOf` 旧实现用 `REGION_MASTER_WINDOW = 6000` 固定窗口，把 `video_info_v2` 区段**截断**，7 档只采到 5 档、漏掉 4K。完整梯子（本 note）：`532`=2160x2880 UHD 10,165,101B / `533`=1440x1920 QHD / `526`=1080x1440 FHD / `258`=720x960 HD h264 / `332` / `331` / `334`=vvc。已改为**以「下一个 `video_info_v2`」为区段右界**（常量删除）。无论梯子多高都不是原片——`video.width/height`(3336x4448) 永远大于梯子最大分辨率。
  - 反面证据（别再走）：camelCase `originVideoKey` / snake_case `origin_video_key` 在 **app 响应体**里**全 case 零命中**（落盘 3 份完整 body 验证）；宿主 dex 里**没有** `sns-video-bd` 这个 host（只 `sns-video-chapter`）；裸 fileid 直链恒 404（`{fileid}` 每档不同，且都不是 origin key）。
  - **进程被杀会留孤儿**：`IS_PENDING` 态的 `.pending-<id>-<name>` 文件在宿主进程被系统回收时不会走 `rollbackHd()`（实测残留 102,759,493 B 半成品）。根治需在模块启动时扫 `.pending-*`——**未做，待拍板**。
  - **交付链修复（V-4/V-5，2026-10-14）**：`downloadVideoHd` 改 `IS_PENDING=1` 占位 → 流式写 → 字节数 == `Content-Length` 校验 → `IS_PENDING=0`；失败 `rollbackHd()` 删占位。此前无 IS_PENDING 生命周期 ⇒ 产物在 `Movies/小红书` 消失。Content-Type **黑名单**（只拒 `text/*`/`application/xml`/`application/json`；`application/octet-stream` 合法放行——CDN 对原始档常回此类型）。`deleteLowQuality` 改时间窗（`DATE_ADDED >= t0-10s` + 非本 uri + `SIZE < ourSize`，走 `Video.EXTERNAL_CONTENT_URI` 覆盖 DCIM）。真机实证：`HD直下完成 … 6122KB → Movies/小红书/xhs_…_hd.mp4`（落盘 6,269,525B 存活）+ `已删低画质文件 Camera_xhs_1791916085784.mp4 (1607KB < 6122KB)`。
  - **伪 originKey 采集是 bug**：扫 `"video":{` 后 3000 字符窗口内首个 `"id"` 作 origin key —— 实测命中两类垃圾：24 位 hex（= 别的 note id，`sns-video-bd/<hex>` → 404）与 `http://..._258.mp4`（窗口内其他对象的 id）。**已删（V-1，2026-10-14）**。
- 不搬 savefix `wh8.o1.a` 分支兜底（9.49.1 名已漂）：download_url 注入后客户端下载分支自愈（改数据源优于改参数；少一个漂移点）。
- 重定位：okhttp3 类名公开；`interceptors` 字段名公开源码。工程意义上无需重定位。
- 失败分级：FAILED（okhttp 缺失说明目标结构大改）。

**X5 `mh9.b`（图片长按面板「保存」点击 lambda）→ 追加弹窗 → 外挂直下（2026-10-14 定稿）**
- 挂点与锚同前表。交互：**先 proceed 后弹窗**——本张原生保存（fresh token）→ 追加卡片（自绘：圆角卡 + XHS 红主按钮 + 深色自适应）「已保存本张（原图）/ 检测到本篇还有 N 张」[保存全部/不用了]；单图笔记零打扰。
- **外挂数据链（四版演进终局，前四版死因存档勿复活）**：
  1. 画廊三击——400ms 窗口 + 双击缩放冲突（用户否决）
  2. 拦截-重放本张——dispatch token 一次性，事后重放被下游身份过滤静默丢弃
  3. app 内存找图集（controller 图搜/adapter.setItems 快照）——sheet 图里图集实证为空；MultiTypeAdapter.setItems 真机不存在
  4. **外挂解析（定稿）**：X4 interceptor 旁路 `/api/sns/v1/note/imagefeed`（逐图 feed，进图浏览必发）+ `/note/detailfeed`，收 `images_list[]` 的 `original` 直链（优先）/`url`；noteId 三轨 = body `"note_id"` → URL query `note_id` → 沿用最近值；触发端点**精确匹配**（宽前缀 /note/ 会收进 user/posted 污染；homefeed 会灌整条信息流——实测 62 张污染）；单笔记 >30 张护栏丢弃。
  5. 直下：模块自己 HttpURLConnection（UA+Referer+302 跟随）→ MediaStore `Pictures/小红书`；URL 原图化 = 剥 `?`/`!` 参数，**换 host 已实证安全**（见下）。
- **换 host + 剥 query 实证（2026-10-14，三条真机 URL）**：token 取 3 例——根级 `1040g0083269625nml6105pou5a8jchgaphip3tg`、`notes_pre_post/1040g3k0325ubij8hk64g5nlvfu908d75654m610`、`spectrum/1040g34o32617jo6fk6105qh81qbgfs01n02n15g`。同一 token 对 `sns-na-i6` / `sns-img-bd` / `ci.xiaohongshu.com` / `sns-img-qc` 四 host **返回字节完全相同**（644,430B / 1,956,636B / 159,039B）⇒ 换 host 无损，原「未实证」作废。剥 query 即原图：`notes_pre_post/…` 无参 1,956,636B vs 签名预览 644,430B。**但只剥 query 不够——必须补回 `?imageView2/2/format/jpg/q/100`（2026-10-14 订正，真机 curl 实测）**：只保 token 时 CDN 回**同尺寸但高压缩重编码**（画质损失大），同一张 4284x5712 图：token A 裸链 1,727,058B / 加该 query（`w/4284` 同结果）= 11,114,503B；token B 裸链 4,917,390B / 加该 query = 12,014,146B；客户端自存同张 10,186,338B ⇒ `XhsImageUrl` AUTO 分支恒在 token 后追加该 query（`toOriginal`），裸链另存 `toBare` 供下载失败回落。**注意 Content-Type**：原图可能回 `application/octet-stream`（非 `image/*`）⇒ 下载端**禁按 Content-Type 白名单拒收**（改用黑名单拒 `text/*` 错误页）。落地实现 `XhsImageUrl.kt`（host 固定 `sns-img-bd.xhscdn.com`）。
- **X5 真机实录（2026-10-14，4 图笔记）**：长按图片 →「保存」命中 `mh9.b` → 原生保存本张（客户端落 `/sdcard/DCIM/Camera_XHS_<ts><token路径>.jpg` 1,228,638B，**1728x2304**，X3 原图直链生效）→ 追加卡「检测到本篇还有 4 张」（采集自 `imagefeed`，首张 host 实测 `sns-na-i1.xhscdn.com`，改写后走 `sns-img-bd`）→「保存全部」→ 模块直下 **4/4** 落 `Pictures/小红书`（4.77/2.87/2.83/3.08 MB；首张 1728x2304 = 与客户端同分辨率，但为 CDN **原始字节**，远大于客户端重压的 1.23MB）。
- 弹窗 Activity 来源：controller 图搜（Lazy 主动 getValue 解包 + Dialog 轨——LCBBottomSheetDialog.context）。
- 边界：当前张会与原生保存重复一张（无现图 fileid 判定链路，接受重存）；视频笔记不弹（X4 已直链）。
- 重定位：双日志串 + invoke(Object) 签名；采集端点 `note/imagefeed` 路径字面量。
- 失败分级：NOT_RESOLVED（零命中 = 点击链改版，X5 失活不影响其余）。
- **2026-10-15 订正（路线 A）**：交互改为**模块全接管（不 proceed）+ 外挂直下**——上面「先 proceed 后弹窗」的交互描述与「当前张会与原生保存重复一张」的边界**双双作废**，新语义与取证见 §六末节。

## 四、注册与开关

- 新 TargetHook：`XhsHook`（`com.xingin.xhs`）注册 TargetRegistry；scope.list 加 `com.xingin.xhs`
- FeatureCatalog（2026-10-19 22:56 用户拍板收敛，仅两开关）：Target(baselineVersion = "9.50.0") + 2 Feature：
  `xhs.save_unlock`（去掉下载限制）/ `xhs.takeover_save`（接管下载 = X2 去水印 + X4 换链 + X5 图片接管 + 落盘闸）
- 判据：modlog-grep XhsHook —— `installed N/M` 汇总 + 各生效行（见 `xhs-save-masterplan.md` §五）

## 五、与 savefix 1.2.3 的差异清单

| 项 | savefix | 本模块 | 理由 |
|---|---|---|---|
| 图片 URL | 不碰（存压缩图） | X3 token 重建原图 | XHS-Downloader 实证清晰度差距 |
| 视频 download_url | master_url 单源 | 无效（客户端不读） | 真机实证：换成 `_258` 后客户端仍落 `_4610`（2.9MB） |
| wh8.o1.a 分支兜底 | 有 | 不搬 | download_url 注入后自愈；点已漂移 |
| 评论图 | 不覆盖 | X3 同点覆盖 | qc8.i.i 收口发现 |
| harvest/ndjson/autoExpand | 有 | 丢 | 作者采集基建，历史包袱 |
| UpdatePrompt/设置 pill | 有 | 丢 | 注入宿主 UI 的自更新，包袱 |
| OFF/PROBE/FAST 文件标记配置 | 有 | 丢 | 改用模块开关体系（远端 prefs） |
| ImageBean.u() | 裸挂 | 运行时注解反射 | 9.49.1 漂移实证，裸挂会类型炸 |
| 混淆点 jm7.j.b / kr9.c | 裸挂 | 串锚 DexKit + 丢弃(kr9.c 随 harvest) | 跨版本原则 |
| 批量下载 | 无 | X5 | 新需求 |

## 六、实现记录（2026-10-13，代码落地）

实现 = `app/src/main/kotlin/top/honorhook/xposed/target/xhs/` 五文件 + FeatureCatalog/strings/scope.list/TargetRegistry 注册。全部按 §三总表实现，以下为实读后与 doc 表述的**偏差与定案**：

### 各引擎挂点与汇总名

| 引擎 | 功能 | 挂点 | 汇总行 |
|---|---|---|---|
| XhsHook·X1 | save_unlock | MediaSaveConfig.b / FunctionSwitch.a / ShareInfoDetail$Operate.a（三直连） | `XhsHook·X1: installed N/3` |
| XhsHook·X2 | no_watermark | MediaSaveConfig.c / qc8.j.b（DexHint classStrings=["disableAiWaterMark is ","SaveImagesHelper"]）/ ImageBean disable_ai getter（运行时注解反射） | `XhsHook·X2: installed N/3` |
| XhsHook·X3 | hd_save | qc8.i.i（11 参，DexHint 四串锚）before 改 args[3] | `XhsHook·X3: installed N/1` |
| XhsHook·X4 | video_save | okhttp3.OkHttpClient$Builder.build() before 注 Interceptor 代理 | `XhsHook·X4: installed N/1` |
| XhsHook·X5 | batch_save | `mh9.b#invoke`（图片长按面板「保存」点击 lambda）+ `d49.q#invoke`（视频保存发起） | `XhsHook·X5: installed N/2` |

### 实读偏差（与 §三 表述不一致处，以 jadx 实读为准）

1. **X1c type getter 不是 g()**：9.49.1 实读 `ShareInfoDetail$Operate`：`g()` 返回 `tag` 字段、`h()` 才返回 `type`（savefix 按 g() 判 type 是其适配版本的偏差）。实现不按方法号：优先直读**字段 `type`**（实体字段名 R8 未改名），回落方法号 c()（FunctionSwitch）/ h()（Operate）；两路都漂 = 不命中 = 放行原判定（安全侧，付费边界不受影响）。
2. **X5 挂点定案**：AsyncImageGalleryController **没有** onAttachedToWindow 式 view 回调；view 绑在 presenter `aj9.h2 extends pe7.g<ImageGalleryView>` 的继承公共字段上（type V 擦除为 android.view.View），且 presenter.didLoad 在 controller.onAttach 内经 dispatchLoad 读 view（否则宿主自身 NPE）⇒ **onAttach after = view 必已绑定**的稳定直连点，选它而非 obfuscated 的 aj9.h2.didLoad（后者需 DexHint 兜底，能不引混淆锚就不引）。
3. **X5 数据取法定案**：doc 建议的 controller.R（DetailNoteFeedHolder）→ NoteFeed.N0() 链路的 b()/N0() 是单字母混淆方法，防漂改走类型锚定：presenter 字段扫 `com.drakeet.multitype.MultiTypeAdapter`（库未 shade）→ `getItems()`（库 API）→ 条目内扫 `com.xingin.entities.ImageBean` 类型字段（y wrapper 的 b）。ImageBean 取值仍按 doc：K()=fileid、A0()=显示 url（实读链路：controller:5613 `gVarP2.q0(Uri.parse(imageBean.A0()))`，即 sb9/y 的 g.o()=filePath 来源）、R()?.o()=滤镜 url。**K()/A0()/R() 单字母号漂移 = X5 整体降级**（doc 失败分级 FAILED，不阻断其余功能；适配时按「ImageBean 无参 boolean/String getter + 注解」重找）。
4. **X2c 探针定案**：无参构造 ImageBean → `@kd.c("disable_ai")` 字段置 true → 扫无参非静态返 boolean 且 invoke 后为 true 的 getter（只有读该字段的 getter 在探针下为 true）。注解串不漂、字段名/方法号全免疫。失败 = NOT_RESOLVED（重试 2 轮后进汇总失败清单，AI 标签可能保留 = doc 可接受降级）。
5. **X4 语义**：照 savefix patchVideoSaveResponse/rebuildResponse/createResponseBody/queryParam/P_MASTER_MP4/P_NOTE_ID 搬；**`origin_video_key` 优先直链已于 2026-10-14 实证作废**（该字段不在 app 网络层，P_ORIGIN_KEY 恒 miss，`sns-video-bd/{32hex}` 实测 404）。okhttp3 不进编译期 classpath，全程反射（peekBody/proceed/newBuilder/ResponseBody 三轨重建）。改写命中打 `XhsHook·X4 视频响应已改写 noteId=...`（每次命中，不走 judge 节流）。有效语义只剩 `disable` 解锁。
6. **X5 保存调用**：取 SPI 走 `com.xingin.spi.service.ServiceLoaderKtKt.service$default(Class,null,null,3,null)`（sb9/y 同链），回落 java.util.ServiceLoader；调 `save(8参)` 直连（a.a 默认方法 shim 不经）。boolean 参传 true（=disableWaterMark，与 X2a 同语义）；avatar 传 ""（sb9/y 对 null 的兜底同款）。success/error 回调用 Function1 动态代理计数，Toast「已保存 N/M」。触摸监听恒返回 false，不消费宿主手势；同 presenter 进行中忽略、同 note（fileid 指纹）会话内一次。

### adapt_new_version.py 口径

X2c 无静态挂载点（运行时注解反射解析）⇒ 本 target 脚本核对点数比源码少 1（源码 X2 引擎 3 点，脚本 2 点），**预期差，不修**。其余 8 点脚本口径正常。

### 状态

- ✅ 编译通过（`bash tools/dev.sh build`，2026-10-13）
- ✅ **真机验证通过（2026-10-14）**：判据 = modlog `XhsHook·X1: installed 3/3 · X2 3/3 · X3 1/1 · X4 1/1 · X5 2/2` 全绿（APK 1.1.0 / 宿主 9.49.1）；各功能端到端实证见 §三 与 `xhs-hd-fix-plan.md` §五/§六。原「未真机验证 + 验证清单（含画廊三击）」条目作废——画廊三击是已被否决的 v1 设计。

### 评审修复（2026-10-13，同轮 9 处）

- **X3**：before 改参由 `proceedWith(thisObject, args)` 改 `proceed(args)`（api 102 Chain：proceedWith = 跳过原方法以 result 充当返回值的结果改写轨，HonorAdsHook 短路语义；用于改参会令保存执行链整体不执行）；token 规则由 XHS-Downloader `split("/")[5:]` 改取「path 末段截 `!`」——原式依赖 Web 端深路径（token 前恒有 ≥2 目录段，结果即末段），客户端 ImageBean url 常为浅路径（host 后 0~1 段），drop(5) 取空 = 整功能静默 no-op；末段取法深路径与原式等价、浅路径才命中（§三 规则 3 已同步）。
- **X4**：createResponseBody 三轨 Class.forName 由模块 loader 改宿主 loader（`@Volatile hostLoader`，install 期存 `param.defaultClassLoader`；模块 classpath 无 okhttp3，模块 loader = 三轨全 ClassNotFoundException = 采集正常但 save 响应永不改写）；防重注入由 `interceptors.contains`（代理 equals 恒 false——Kotlin lambda InvocationHandler 内 this 指外层 object，判重必失效 → 重复注入 = 同请求双拦截 + download_url 重复插 JSON）改 `toString()` 标记判重（处理器恒返 `HonorHook-XhsVideoSave`）。
- **X2**：X2cTask.resolveValue 补 `also { resolvedGetter = it }` 写回——resolveValue 只是就绪门（MountEngine.resolveTask 丢弃返回值、仍按 methodName 解析），不写回则 methodName 恒 "" → 直连 0 命中 → 无锚 DexKit → NOT_RESOLVED 空转；探针改**差分**（置 true→true 且置 false→false 两态都符合才认定），防「默认值恰为 true 的其它布尔字段 getter」误挂（实例本地新建可丢弃，不复位）。
- **X5**：resolveSaveProxy 类加载由模块 loader 改宿主 loader（ISaveImageProxy/ServiceLoaderKtKt 是宿主类）；save 回调代理 loader 显式用 `activity.classLoader`（Function1 类身份必须宿主 kotlin-stdlib，模块侧同名类 ClassCastException）；三击监听不覆盖宿主——先取 `prev = view.onTouchListener`，尾部委托 `prev?.onTouch(v, event) ?: false`；collectImages 的 adapter 字段扫描补超类链（aj9.h2 系 presenter 字段可能落父类，同 findImageBeanValue 写法）。

### 2026-10-15 接管路线 A + 真机取证

**交互改版（路线 A = 模块全接管）**：图片点击不再 `chain.proceed()`——宿主原保存逻辑整段不执行，模块自己下全部原图（含当前张）。分流：图集空 → `proceed()` 回落宿主原生保存（否则用户点「保存」什么都不发生）；1 张 → 静默直下；≥2 张 → 确认卡后直下全部；卡上「不用了」= 什么都不保存（不加 Toast）。宿主那段被跳过时会顺带跳过面板关闭（`cVar.i.getValue().dismiss()`），模块改按**类型**从 lambda 找 `kotlin.Lazy` 字段自行 `dismiss()`（不按混淆字段名，字段名会漂）。

**真机取证（2026-10-15，宿主 9.49.1）**：

1. **宿主落盘命名实证**：`/sdcard/DCIM/Camera_XHS_<ts><token 的 `/` 换成 `_`>.jpg`——宿主链是「解码 → 合成 → 重编码」写盘，**不是原图**（对比模块外挂直下 `/sdcard/Pictures/小红书/xhs_<ts>_<i>.jpg` = CDN 原始字节）。
2. **多图重复一张**：旧「先 proceed 本张原生保存 → 再追加剩余」⇒ 宿主存当前张 + 模块又下全部 N 张（含当前张）= 重复。接管后宿主那份不再产生。
3. **单图零介入**：`maybeShowBatchDialog` 的 `urls.size < 2` 早退让单图笔记完全走宿主链，用户只拿到重编码图。改法 = 1 张静默直下原图（不弹卡）。
4. **视频进度卡时序缺陷（缺陷③）**：`downloadVideoHd` 成功路径原先把第二次 `deleteLowQuality` 压在 `Thread.sleep(5_000)` 之后，`dismissProgress()` 被一起拖后 5~6 秒 ⇒ 进度卡（`setCancelable(false)`，用户关不掉）与完成卡并存。改法：`updateProgress(pct)` 里 `pct >= 100` 当场 `hdActive = false` + `dismissProgress()`（先落标记以中止 `showProgressDialog` 的 300ms×16 重试链，防收卡后又建回来；此刻读流循环已结束，续传重试只发生在读流 IOException 期间 ⇒ 不打断续传）；收尾顺序 = `hdActive=false` → `dismissProgress()` → 完成卡 → 首次低画质清理（同步）→ 5 秒后第二次清理改**独立延时线程**（`delayedExecutor`，不再 sleep 阻塞收尾）。`showProgressDialog` 建卡回填 `lastPct`（修「重试链晚于下载事件建卡 ⇒ 停在 0%」）。
5. **完成卡 BadToken**：真机 `XhsHook·X5 完成卡失败: Unable to add window -- token android.os.BinderProxy@… is not valid; is your activity running?`——用 controller 图搜出的**历史 Activity** 建 Dialog。改法 = 弹窗一律走追踪的 resumed Activity + 取不到时 300ms × 16 重试（`showOnTrackedActivity`）；`findActivity`/`extractController` 保留作兜底。（2026-10-15 审计已删，作废——见判据节）
6. **视频双卡（缺陷④）**：X5「已保存本视频」反馈卡与 X4「下载高清原版视频」询问卡同时弹。**改确定性握手**：X4 在真正弹询问卡前调 `XhsBatchSave.dismissVideoFeedbackCard()` 主动关掉 X5 反馈卡（后弹的关先弹的），X5 侧 `askShownAfter(t0)` 退化为**快路径**（命中则连反馈卡都不弹）。**时序假设已证不成立**：初版按「X4 写 `lastAskAtMs` 落在本轮 `proceed()` 之内/之前」设计——实际 X4 是在宿主 save API 的**网络响应**到达时才写（retrofit 异步，100~500ms 后），远晚于 X5 在 `proceed()` 返回处的判定点 ⇒ 该判定几乎恒 false，双卡照旧；故改为握手（不依赖任何时序）。
7. **待办（2026-10-15 审计已修：currentNoteId 新鲜度门，缓存过期/护栏丢弃回落宿主兜底——见判据节过期回落条目）**：`图集护栏触发 31~247 张疑似污染，丢弃` 高频出现——护栏一触发就 `return`，**不更新** `noteImages[noteId]` 与 `lastImageNoteId` ⇒ `lastNoteImages()` 可能仍返回**上一篇笔记**的图集，接管后的弹窗/静默直下会下错图。修法方向：护栏触发时按 noteId 清空该条 + 复位 `lastImageNoteId`（或弹窗前校验 noteId 与当前笔记一致）。

### 2026-10-15 X5 全接管判据（用户自执行，落码未验证）

- **X5 全接管路线 A 判据（用户自执行，2026-10-15 落码未验证）**：①多图笔记长按保存 → 宿主面板被关 + 模块询问卡出现 → 全部原图落 `Pictures/小红书`；②单图长按保存 → 静默直下无弹卡；③连续浏览两篇图集笔记 A→B 后在 B 长按保存 → 下载的是 B 的图（过期回落：B 采不到时回落宿主原生保存，绝不下载 A 的图）；④飞行模式/断网长按保存 → 回落宿主原生行为。
- **双卡握手判据**：视频保存后屏上只出现一张卡（X4 询问卡或 X5 反馈卡，不并存）。
- **待实证项**：mountClick 不 proceed 时自造 kotlin.Unit 返回值——宿主调用方是否消费该返回值未实证；回归窗口 = 长按保存后宿主不闪退、后续交互正常。
- **新鲜度门设计（审计 2026-10-15）**：护栏丢弃/换笔记时 `lastNoteImages` 返回空 ⇒ 回落宿主。

---

## 七、2026-10-15 基准切换与对照设计

> 用户拍板：下载策略**唯一基准** = `reference/xhs/XHS_Downloader_Android`（Kotlin 安卓版），禁止自创；Python 版 `reference/xhs/XHS-Downloader` 已删。
> 全量业务逻辑对照（基准阶段全景 × 插件现状 × 设计提案 × 待拍板清单，含证据分级）= `docs/reverse/xhs-downloader-parity.md`。
> 已落码对齐：视频选档（watermarked↑ → original↓ → w×h↓ 取首）、图片链（裸链优先 → raw 回落）、水印流标记（stream_type 259/309、WEB_LIVEPHOTO_19）。
> 已知分叉：X3 仍用 q/100（2026-10-14 真机字节实证 vs 基准 AUTO 裸链，冲突待拍板，见 parity §四·1）。

---

## 八、2026-10-19 适配 9.49.1 → 9.50.0（基线上移，原地换点不建 versionTables 新套）

宿主已升 9.50.0（dumpsys 实证）。9.49.1 真机基线判据 X1 3/3 · X2 3/3 · X3 1/1 · X4 1/1 · X5 2/2；
9.50.0 首测 X1 3/3、X4 1/1 活，X2 2/3、X3 0/1、X5 1/2。死因与重选如下（调研 findings
`.buildtmp/findings-{x2b,x3,x5}.md`，2026-10-19）。

### 死因定案

- **qc8.i / qc8.j 消失**：R8 全量重命名换包 `qc8→nd8`（同 classes6.dex 内）。旧 `Lqc8/i;`/`Lqc8/j;`
  在 9.50.0 全 24 个 dex 串池 0 命中（python zipfile grep + class def 全扫双实证）。
- **mh9.b「在 dex 却解析失败」**：class def 存在但 `class_data_off=0`（零方法零字段空壳 marker，
  还被 `Lmh9/a$b;#<init>` 当 Dagger 组件类型引用）⇒ `loadClass` 成功但 `declaredMethods` 空 ⇒
  直连 0 候选；串锚随逻辑体迁走 ⇒ DexKit 亦 miss ⇒ NOT_RESOLVED。逻辑本体迁 `ji9.b`。
- **d49.q「挂上」是假阳性**：`Ld49/q;` 全 22 dex 无 class def ⇒ CNFE 转 DexKit；task 无 DexHint，
  `MethodResolver.kt:88-93`「pkg 非空即绕过无锚点闸门」照样开搜，`pkg=d49 + invoke(1,Object)Object`
  的 `firstOrNull()` 即采纳——9.50.0 d49 包已换语义（分享引导控制器族），误绑 `d49/e`（或 f）。
  arg 非 String ⇒ isSave=false ⇒ 静默放行：**视频反馈卡实际同样失效**，judge 只数 task 不识错绑。

### 新挂载点对照（file:line 实证，产物 `.buildtmp/xhs950-partial/sources/`）

| 引擎点 | 9.49.1 | 9.50.0 | 证据 | 其余签名 |
|---|---|---|---|---|
| X2b | `qc8.j.b(Context,boolean)→Bitmap` | `nd8.j.b` 同签名 | dexdump c6.txt:901382 类头；b() inflate id 0x7f0c0f79→0x7f0c1b79 平移、alpha 0.56 同旧 | 方法名/参数/返回全不变 |
| X3 | `qc8.i.i`（11 参，args[3]=url） | `nd8.i.i` 同构 | nd8/i.java:240（签名）、:245（`Uri.parse(str3)`） | i/11 参/args[3] 不变；桥 h :164；SPI SaveImageProxyImpl 未换包 :38 `nd8.i.a.e` |
| X5 图片 | `mh9.b#invoke(Object)` | `ji9.b#invoke(Object)` | ji9/b.java:45（invoke）；:55 "onDownloadClick, noteId="、:63 "download blocked, disableSaveMedia=true" 逐字存活 | 两串锚保留；面板 Lazy=ji9.c.i（ji9/c.java:28,200） |
| X5 视频 | `d49.q#invoke(Object)` | `a59.q#invoke(Object)` | a59/q.java:16-53（default=saveVideo(noteFeed.u0())）；实例化 a59/v.java:471 `new q(noteFeed, 1)` | 字段 (int, NoteFeed)；方法体无串常量 ⇒ DexHint 用 classFieldTypes=["int","com.xingin.entities.notedetail.NoteFeed"] |
| X1 / X4 | — | **零漂移**（未动） | X1 3/3、X4 1/1 真机活 | — |

DexHint 更新：X2b classStrings 加 `"andr_image_save_white_bg_optimized"`（全 APK 唯一串，与
"disableAiWaterMark is " 双唯一串兜底）；X3 四串原样（同在 nd8.i 方法体内 :258/:277/:279/:190）；
X5 图片两串原样；X5 视频如上换字段类型锚。FeatureCatalog baselineVersion → 9.50.0。
评论图直调链 iu4/l.java:112 → cv4/l.java:94（X3 覆盖面不变）；NoteFeed getter F0()→u0()、
h1()→b1()、U1()→S1()；**短包名身份跨版本不可信，选点必须靠内容锚**（新旧混淆名总表见 findings-x5）。

### 遗留（不改码，留拍板）

`MethodResolver` 无锚点兜底的误绑坑（pkg 非空即开搜 + `firstOrNull()` 即采纳，命中类名 ≠ 声明类名
不降级）：本案 d49.q 假阳性即实例。候选方案（基建改动，另行拍板）：混淆名 task 强制配锚点；或无锚点
采纳时「DexKit 命中类名 ≠ 声明类名」降级 WARN。

### 待用户真机验证

装新包 → 开 xhs → 看 modlog：`X1 3/3 · X2 3/3 · X3 1/1 · X4 1/1 · X5 2/2`；功能现象：图片长按保存
出「已接管图片保存，图集 N 张」judge 行 + 面板被关 + 询问/直下；视频保存出「视频保存完成，弹反馈卡」
judge 行（9.50.0 现版本两点都出不来）。

## 九、保存业务图谱（2026-10-19 22:50 终态，v2 换链架构）

> 完整方案（入口矩阵/数据流/失败矩阵/判据速查/终验清单）= **`xhs-save-masterplan.md`**（唯一事实源）。
> 本节只留代码读不出的运行时实证与历史结论。

### 9.1 用户入口矩阵（现行）

| # | 用户操作 | 宿主流 | 模块点 | 实证（2026-10-19） |
|---|---|---|---|---|
| V1 | 视频保存（任意入口） | save API（路①）或缓存直拷（②）/已存短路（③） | X4 Interceptor 换链（①）+ w.h0/e0 落盘闸兜 ②③ | ✅ 22:50 拒绝路全绿（16.8MB 原片）；换链主路待验 |
| I1 | 图集笔记长按面板「保存」 | 宿主单张压缩图 | `ji9.b` 全接管 | ✅ 22:39 图8 `外挂直下完成 8/8` |
| I2/I3 | 笔记视图长按 / 大图浏览器 | 宿主管道本机静默失败 | 无挂载点 | ❌ 明示边界（masterplan §七） |

### 9.2 视频保存三路（宿主侧 → 模块对策）

| 路 | 触发 | save 请求 | 对策 |
|---|---|---|---|
| ① 网络 API | 首存 | 有 v10 | X4 换链 download_url → 宿主自下原片（C 方案） |
| ② 缓存直拷 | 播放过 | 无 | 落盘闸断 720p（≤8MB 四判据）+ HD 卡（a59.q 轨留档） |
| ③ 已存短路 | 短时重复 | 无 | 同 ②；toast 照旧（无文件动作） |

作者关闭下载（服务端 disable）：拒绝体 `{"result":0,"success":true,"msg":"","data":{"msg":"…","disable":true}}`
——**保留 disable 只清 data.msg**（toast 消失）+ HD 卡直下原片。**禁伪造 success/disable 语义**
（21:22、22:47 两轮假成功体均致宿主 fallback 自下载 = 双下载，反例档案见 masterplan §四）。

### 9.3 数据流（现行，采集器已全删）

```
保存点击 ─→ noteId（X5 findFeedAndNoteId 多根+Lazy 解包）
        ─→ shareLinkOf(noteShares 长链) → ShareService.getShortUrl → 短链
        ─→ 302 展开（xsec_token 自带）→ vendored 下载器 parse → ResolvedNote
        ─→ 视频: interceptVideoSave 换链 / HD 卡直下（Movies/小红书，同名覆盖）
        ─→ 图片: downloadAll 原图+livePhotos 静帧（Pictures/小红书）
```

旧采集器（noteImages/videoSources/harvest*/imagefeed/videofeed 图谱）已随基准切换删除——
解析单一事实源 = 分享链接，不再依赖宿主 feed 端点形态。历史设计见 git log 4e01b33^。

## 十、2026-10-20 版本覆盖套从未生效（版本源恒 null）→ 修复

### 根因

`XhsPoints.cls(hostVersion, key)` 的 `hostVersion` 来自 `HostVersion.resolve(sourceDir)`，那条路走
`ActivityThread.currentPackageManager` —— 该成员在现代 Android/AOSP **根本不存在**（Android 14 源码全文
0 命中；真机 `getMethod`/`getDeclaredMethod` 均 `NoSuchMethodException`）⇒ **恒返回 null** ⇒
`overrides[null]` 永远 miss ⇒ **9.49.1 覆盖套（saveClick=mh9.b / videoClick=d49.q / x2b=qc8.j +
三个 null 点）从未生效过**，xhs 一直按基线（9.50.0 坐标）挂载。

ART 对「成员不存在」与「被 hidden-API 黑名单拒绝」抛**同一种异常类型** ⇒ 这条路自 2026-10-11 引入
（commit e842055）起就是死路，只因以前没有 target 消费版本号才潜伏（installer 选表是第一个真消费方）。

### 第二个缺陷：显式 null 被吞

旧 `cls()` 是 `over ?: baseline[key]`：覆盖表里显式 null（= 「该版本无此点」，见文件头 KDoc）与
「该版本无覆盖项」被混成同一种 null ⇒ 三个 null 点会错误回落基线坐标。
现改三态：命中覆盖且值为 null → null（跳过该点）；命中覆盖且有值 → 覆盖值；版本无覆盖项 / 版本为 null → 基线值。

### 修复

- 版本源换公开路径 `HostVersion.resolveHostVersion(sourceDir, "com.xingin.xhs")`
  （系统上下文 PM 直读宿主 APK 磁盘 → 宿主 Application PM 查包名；见 `docs/hook-craft.md`「宿主版本读取纪律」）。
- `cls()` 三态如上；纯逻辑已钉进 `XhsPointsTest`（6 例，含「显式 null 保持 null」——
  旧实现下该用例会红，确属咬住缺陷）。

### 影响面

- **9.50.x 宿主：逐键返回值与修复前完全一致**（覆盖表无 9.50.x 套 → 仍走基线）⇒ `installed N/M` 不变。
- **9.49.1 宿主**：三个点从「回落基线去挂一个该版本不存在的类」变为「跳过」（按表意）；saveClick/videoClick/x2b
  换回 9.49.1 真坐标 —— 这才是「旧宿主保持旧 hook」的设计意图首次真正成立。判据分母随之 -3
  （文件头 KDoc 明写「N 随之变化可判」）。

### 待验证

真机 XHS 判据复核（设备重新连上后跑）：9.50.x 上 `installed N/M` 应与修复前逐字一致。

**2026-10-20 16:48 真机复核（宿主 xhs 9.50.0，新包）**：`XhsHook·X1 3/3 · X2 3/3 · X4 1/1 · X5 7/7`
（主进程与 `:pushservice` 进程各一轮）—— 与修复前**逐字一致** ⇒ 换版本源对 9.50.x 无任何挂载点影响；
同轮 installer 复测 `installed 11/11` + 「宿主版本与 hook 基线一致: 14.0.38.301」。
