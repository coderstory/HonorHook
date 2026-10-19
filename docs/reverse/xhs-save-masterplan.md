# 小红书保存功能 · 完整方案（终态 v3.1，2026-10-20 00:20 真机全绿）

> 目标态一句话：**保存入口（ab9.r1.b / ji9.b）直接断，宿主零提示零请求零下载，唯一产文件者
> = 模块管线（分享链接 → vendored 下载器解析 → 原片/原图直下，带进度/取消/失败回落）**。
> v3 变更（2026-10-20 00:20 真机全绿）：入口断取代四轮管道内手术（短路假响应/换链/8MB/60s 窗/
> spectrum 分支全部证伪，反例档案见 XhsBatchSave.mountAlbumSink KDoc）——图片 ji9.b 模式是唯一
> 全程零翻车模式，视频复制之；子任务静态审计（b75/m/n/l 全链 + 调用方）+ 逻辑审计四洞修复。

## 一、入口 × 行为矩阵（已验路径打 ✅，2026-10-20）

| 入口 | 宿主原行为 | 方案后行为 | 判据 |
|---|---|---|---|
| 图片：笔记级长按面板「保存」（ji9.b） | 单张压缩图 | ✅ 模块全接管，直下全部原图（实况只下静帧） | `外挂直下完成 8/8`（22:39） |
| 视频：任意笔记（普通/AIGC/作者关闭） | save API + 转码/下载 + 提示 → 720p 落 DCIM | ✅ ab9.r1.b 入口断（链接可得）：零提示零请求零下载 + 弹 HD 卡 → 模块直下原片 | `笔记保存总入口已断（ab9.r1.b）` + `HD直下完成`（00:20 AIGC/普通双验） |
| 视频：链接 miss/拉黑 | — | proceed 宿主原生保底 | `放行宿主原生保底` |
| 视频弹卡 | 无 | HD 询问卡（拦截后模块唯一出口） | `HD询问 候选 N 个` |
| 解析失败（断网/token 失效） | — | 失败卡 + parsedEmptyNotes 拉黑放行宿主保底 | （设计路径，未专项验） |

## 二、挂载点终态（11 个）

| 点 | 职责 | 稳定性 |
|---|---|---|
| X1a/b/c 解锁（3） | 保存按钮可见性 | 注解/未混淆，稳 |
| X2a/b/c 去水印（3） | AI 水印与标签 | b 混淆（串锚），a/c 稳 |
| X4 okhttp Interceptor（1） | 纯兜底：解析+弹卡（r1.b 拦截失败时）+ 拒绝清 msg + share_info 采集 | okhttp 公开 API，全版本 |
| X5 图 ji9.b（1） | 图片接管判定（链接可得→不 proceed） | 双串锚 |
| ab9.r1.b 笔记保存入口（1）+ b75.h.e 下载入口（1）+ mg4.b 编辑发起（1） | v3.1 主断点 + 两兜底（r1.b 断时后两者无输入） | r1.b 9 参签名+类串锚；e DexKit 串锚；mg4 fragment tag 串锚 |

a59.q 点击轨：留档不依赖（三天零触发实证）。X4/落盘闸同款判据见 §五。

## 三、数据流

```
点击保存 ─┬─ 图片: ji9.b → 链接可得?(内存) → 否:proceed宿主 / 是:接管 → 直下原图+静帧 → Pictures/小红书
          └─ 视频: X4 interceptVideoSave（okhttp 线程内同步，resolveCached 60s TTL）
                    ├─ availableShareLink（拉黑→放行宿主）
                    ├─ 解析 → 候选选档（无水印优先 → 原片 → 最大分辨率）
                    ├─ availableShareLink(noteId) 有链 → 不 proceed（零提示零请求零下载）
         │    → startHdAsk 后台解析 → 弹 HD 卡 → 模块直下原片 → Movies/小红书（同名覆盖）
         └─ miss/拉黑 → proceed 宿主原生保底
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
| r1.b 拦截（主路） | 零提示零请求零下载 | HD 卡 → 原片一次下载 |

**反例档案**：①21:22 假响应短路——宿主拿假成功后 fallback 自下载 720p = 双下载；②22:47 假成功体整替拒绝响应——同款双下载。教训：**宿主 save 响应的 success/disable 语义不许伪造**，只许换 download_url 指针或清展示文案。

## 五·A、版本迭代快速适配（三层防线，2026-10-19 v3 补）

| 层 | 覆盖点 | 漂移时行为 | 人工成本 |
|---|---|---|---|
| L1 未混淆 | w.h0/e0、okhttp Builder、X1 注解 getter、ji9 面板类 | 宿主 OTA 不动 | 零 |
| L2 DexHint 串锚 | b75.h#e（"addDownloadTask 强制下载"）、ji9.b 双串、a59.q 字段锚 | **DexKit 运行时自动重解析**，装上自愈 | 零（installed 判据确认） |
| L3 锚点登记表 | 串也变时 | `grep 串 → 新类名 → 改一行 SimpleTask.className` | 分钟级 |

流程入口：`tools/dev.sh adapt`（批量校验挂载点存活）+ skill `xhs-recon`；登记表 = `xhs-anchors.md`。
判据 = `installed N/M`（漂了少挂不崩宿主，`NOT_RESOLVED` 随类加载重试）。

## 五、判据速查（modlog-grep）

- `X5: installed 5/5` / `X4: installed 1/1` / `X1: installed 3/3` / `X2: installed 3/3`
- 图片：`X5 接管图片保存` → `分享链接解析成功 图N` → `外挂直下完成 N/N`
- 视频入口断（v3 主判据）：`保存下载入口已断（b75.h.e，宿主零下载零回调）noteId=…` → `HD询问` → `HD直下完成 NNNNKB`
- 视频拒绝路：`宿主拒绝保存 noteId=…（清 msg，弹 HD 卡）`
- 落盘兜底：`save_to_album 落盘已断（纯兜底，size=…）`；未接管时 `放行宿主原生保底`

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
