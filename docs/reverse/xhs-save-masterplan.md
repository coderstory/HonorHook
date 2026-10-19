# 小红书保存功能 · 完整方案（终态 v2，2026-10-19 22:50 真机全绿）

> 目标态一句话：**宿主的保存响应被换链（视频）或拦截（图片），唯一产文件者 = 模块管线
> （分享链接 → vendored 下载器解析 → 原片/原图直下，带进度/取消/失败回落）**。
> v2 变更：C 方案换链取代假响应短路（21:22 实证假成功令宿主 fallback 自下载 = 双下载）。

## 一、入口 × 行为矩阵（已验路径打 ✅，2026-10-19）

| 入口 | 宿主原行为 | 方案后行为 | 判据 |
|---|---|---|---|
| 图片：笔记级长按面板「保存」（ji9.b） | 单张压缩图 | ✅ 模块全接管，直下全部原图（实况只下静帧） | `外挂直下完成 8/8`（22:39） |
| 视频：正常可下载笔记（作者未关） | save API → 720p 落 DCIM | save 响应 `download_url` 换链为原片 → 宿主自下原片一次，零弹卡 | `save 响应已换链为原片`（**待真机验**，见 §六） |
| 视频：作者关闭下载（服务端 disable） | toast「作者已关闭下载功能」 | ✅ 响应保留 disable 拒绝语义、只清 data.msg（toast 消失）+ 弹 HD 卡 → 模块直下原片 | `换链放弃…清 msg+弹 HD 卡` + `HD直下完成 16877KB`（22:50） |
| 视频弹卡 | 无 | HD 询问卡（仅换链失败/拒绝路出现） | `HD询问 候选 7 个` |
| 解析失败（断网/token 失效） | — | 失败卡 + parsedEmptyNotes 拉黑放行宿主保底 | （设计路径，未专项验） |

## 二、挂载点终态（11 个）

| 点 | 职责 | 稳定性 |
|---|---|---|
| X1a/b/c 解锁（3） | 保存按钮可见性 | 注解/未混淆，稳 |
| X2a/b/c 去水印（3） | AI 水印与标签 | b 混淆（串锚），a/c 稳 |
| X4 okhttp Interceptor（1） | save 响应换链 / 拒绝清 msg / share_info 采集 | okhttp 公开 API，全版本 |
| X5 图 ji9.b（1） | 图片接管判定（链接可得→不 proceed） | 双串锚 |
| w.h0/e0 落盘闸（2） | 四判据：DCIM && Camera/ && video/mp4 && **≤8MB**（>8MB = 换链后原片拷贝，放行） | 未混淆工具，最稳层 |

a59.q 点击轨：留档不依赖（三天零触发实证）。X4/落盘闸同款判据见 §五。

## 三、数据流

```
点击保存 ─┬─ 图片: ji9.b → 链接可得?(内存) → 否:proceed宿主 / 是:接管 → 直下原图+静帧 → Pictures/小红书
          └─ 视频: X4 interceptVideoSave（okhttp 线程内同步，resolveCached 60s TTL）
                    ├─ availableShareLink（拉黑→放行宿主）
                    ├─ 解析 → 候选选档（无水印优先 → 原片 → 最大分辨率）
                    ├─ proceed → 响应有 download_url → 换链为原片 URL → 宿主自下原片（>8MB 落盘放行）
                    └─ 响应无 download_url（作者关闭/风控）→ 清 data.msg（toast 消失）
                         + 弹 HD 卡 → 模块直下原片 → Movies/小红书（同名覆盖）
```

链接获取单链（唯一）：`shareLinkOf(noteShares 长链) → 宿主 ShareService.getShortUrl
（反射 i3c.d，锚行 "getShortUrlObservableIfNeed success" u2c/y.java:70，3s 超时）
→ 短链 → 302 展开（xsec_token 自带）→ vendored resolve`。失败即拉黑回落。

## 四、失败矩阵

| 失败 | 宿主 | 用户感知 |
|---|---|---|
| getShortUrl 失败/超时 | 放行原生保存 | 失败卡 → 再点走宿主 |
| 解析 0 项 | 同上 | 同上（拉黑） |
| 响应无 download_url（作者关闭） | **保留 disable 语义**，只清 msg：零下载零拷贝零 toast | HD 卡 → 原片 |
| rebuild 失败 | 原响应原样 | 宿主行为照旧（toast/720p），无损 |
| 下载中断/取消 | — | 进度卡取消，半成品回滚 |
| 下载成功（换链主路） | 宿主自己下原片 | 一次下载，落 DCIM（>8MB 豁免） |

**反例档案**：①21:22 假响应短路——宿主拿假成功后 fallback 自下载 720p = 双下载；②22:47 假成功体整替拒绝响应——同款双下载。教训：**宿主 save 响应的 success/disable 语义不许伪造**，只许换 download_url 指针或清展示文案。

## 五、判据速查（modlog-grep）

- `X5: installed 4/4` / `X4: installed 1/1` / `X1: installed 3/3` / `X2: installed 3/3`
- 图片：`X5 接管图片保存` → `分享链接解析成功 图N` → `外挂直下完成 N/N`
- 视频换链：`save 响应已换链为原片（宿主自下载，一次下载零弹卡）`
- 视频拒绝路：`换链放弃：save 响应无 download_url（宿主拒绝，清 msg+弹 HD 卡）` → `HD直下完成 NNNNKB`
- 落盘闸：`save_to_album 落盘已断（w.h0/e0 video 过滤）`（仅 ≤8MB 的 720p）

## 六、终验清单

1. ✅ 图片：长按面板保存（22:39 图8，外挂直下完成 8/8）
2. ✅ 作者关闭下载视频：清 msg + HD 卡 + 原片 16877KB（22:50）
3. ⬜ **正常可下载视频**：判据 `save 响应已换链为原片` + 宿主进度走原片体积 + 落盘 >8MB（豁免生效）
4. ⬜ 断网保存 → 失败卡 → 恢复再点 → 放行宿主
5. ⬜ 实况图集静帧（代码已落：livePhotos.image.originalUrl 合并，未专项复验）

## 七、已知边界（明示不覆盖）

- 大图浏览器/分享 sheet/宿主「保存全部」弹框的**图片**入口：宿主自身静默失败且无挂载点
- parsedEmptyNotes 无 TTL：拉黑至进程死（与失败卡语义一致）
- 宿主同单连发两次 save：第二次靠 resolveCached 缓存 + ASK_DEDUPE 10s 幂等闸吸收

## 八、文档收口（执行项）

- [x] masterplan v2（本文件，2026-10-19 22:50）
- [x] xhs.md §九业务图谱重画为本方案数据流
- [x] xhs-compat.md / xhs-downloader-parity.md 销账（头部状态块，正文留作方法论）
- [x] xhs-anchors.md 补 w.h0/e0、getShortUrl 行
- [x] strings.xml batch_save desc 对齐现行交互
