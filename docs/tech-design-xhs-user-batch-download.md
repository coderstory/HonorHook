# 技术方案 — 小红书用户作品批量下载

> 依据：[docs/prd-xhs-user-batch-download.md](prd-xhs-user-batch-download.md)
> 定位：**纯 UI 侧功能，零 hook 依赖**，不涉挂载层，不进 `xposed/target/`。

## 1. 总体结构

```
ToolboxScreen(HubView.XHS_BATCH)
  └─ XhsBatchScreen(ui/screen/)          Compose 页：输入 + 进度面板 + 三按钮
       └─ XhsBatchController(ui/xhsbatch/)  状态机 + 串行编排（纯 Kotlin，可 JVM 单测）
            ├─ XhsUserFeedClient(ui/xhsbatch/)   离屏 WebView 翻页 → 笔记 ID 列表
            ├─ DefaultXhsContentRepository        复用 :xhs-dn-core 白名单件（单篇解析）
            └─ XhsSerialDownloader(ui/xhsbatch/)  OkHttp 串行落盘（自写，不用上游 DownloadQueue）
```

**不新增上游白名单文件**。上游可用件只有 7 个（`libs/xhs-dn-core/build.gradle.kts`），其中本方案用到 4 个：
`XhsUrlParser`（`user/profile/<id>` 分支已存在，`extractPostId` seg[3]）、`XhsNoteParser`（`__INITIAL_STATE__` 提取 + 媒体解析）、`DefaultXhsContentRepository` / `OkHttpXhsPageSource`（单篇链接 → `ResolvedNote`）、`ResolvedNote`（`orderedMedia: List<ResolvedMedia>` 即媒体清单）。上游的 `RepositoryDownloadCoordinator` / `DownloadQueue` **不在白名单**，且其面向单篇交互式任务，不合适——下载层自写（约 100 行）。

## 2. 数据面

### 2.1 笔记列表：离屏 WebView 翻页（`XhsUserFeedClient`）

- 主线程创建 `WebView`，`visibility=GONE`，尺寸 1080×1920，UA 用默认浏览器 UA。
- 加载 `https://www.xiaohongshu.com/user/profile/<userId>`。
- `WebViewClient.shouldInterceptRequest`：URL 含 `/api/sns/web/v1/user_posted` 时，把响应体整体读出（构造替换 body 返回，页面不感知），交给 JSON 解析 → `notes[]`（noteId / xsec_token / cover）+ `cursor` + `has_more`。
- 翻页：`evaluateJavascript` 注入 `window.scrollTo(0, document.body.scrollHeight)`，节流由**注入频率**控制（每次注入间隔 2-4s 随机），直到 `has_more=false` 或达上限（v1 上限 200 篇，防失控）。
- **兜底**：超时（首屏 15s 无拦截命中）或 JSON 解析失败 → 改抓 HTML 解析 `window.__INITIAL_STATE__`（首屏约 20 篇）。复用 `XhsNoteParser` 已有 JS 字面量提取逻辑的思路（其 `parseInitialStateRootFromHtml` 是 private，照抄该算法到本项目文件，不引上游新文件）。
- 登录态（增强项，v1 可缺省）：启动前读 `CookieManager.getInstance().getCookie("https://www.xiaohongshu.com")` 自动带上（系统 WebView 与宿主小红书 app 的 cookie 不共享，匿名会话对公开内容够用）；后续如需手工注入 session3，沿用上游 `SessionCredentials` 的 AES-GCM 加密存储思路，v1 不做 UI。
- 退出：任务结束/取消时 `webView.destroy()`，`stopLoading()` 先行。

> **xsec_token 必须保留**：详情页链接现在是 `/explore/<noteId>?xsec_token=…&xsec_source=pc_user`，无 token 的裸链接会被 403/跳登录。列表 JSON 里每个 note 自带 token，拼详情 URL 时透传。

### 2.2 单篇解析与下载（串行主管线）

`XhsBatchController.run()` 单协程：

```
for note in notes:                       // notes = FeedClient 产出（Flow<XhsFeedItem>）
  delay(jitter(2000..4000))
  resolved = repository.resolve("https://www.xiaohongshu.com/explore/$id?xsec_token=$t&xsec_source=pc_user")
  if 失败 → notesFailed++, continue      // 失败隔离（PRD F10）
  for media in resolved.orderedMedia:    // Image / Video / LivePhoto
    if paused → suspend 等恢复（当前文件边界停，PRD F7）
    delay(jitter(500..1500))
    ok = downloader.download(media.sourceUrl, fileName(note, media))
    imagesDone++ / videosDone++ / totalBytes += fileSize
```

- `resolve` 走 `OkHttpXhsPageSource(fetchHtml)` + `DefaultXhsContentRepository`，OkHttp client = `FileDownloader.getSharedHttpClient().newBuilder()` 追加 Cookie/UA 拦截器（单实例全任务复用）。
- 签名 URL 有时效 ⇒ **解析后立即下载该篇**，不先攒全清单（也天然是串行）。
- 视频取 `ResolvedMedia.Video.sourceUrl`（上游已处理正片）；LivePhoto v1 按其视频成分处理，不生成动态图。

### 2.3 落盘

- 目录：`<下载根>/xhs/<userId>/`（平铺）。下载根沿用现有外挂直下管线的输出目录常量（实现时与 `XhsBatchSave` 对齐取同一个值，不新造第二份配置）。
- 文件名：`<noteId>-<原序号或原文件名去参>`，冲突时追加 `-2`、`-3`。
- 下载用 OkHttp 同步 `Call.execute()`（已在 IO 协程），流式写临时名 `.part`，完成后 rename——半截文件不进目录、不进计数。
- MB 统计 = rename 后 `length()` 累加。

## 3. 控制面（`XhsBatchController`）

### 3.0 持久化与任务列表（新增需求）

- 入口页改造为**任务列表**：Hub tile 进入后先看到历史任务（倒序），右上 [+] 新建任务 → 输入用户 ID 启动；点任一任务进入其进度面板（进行中实时刷新，已结束显示汇总）。
- 存储：`XhsBatchTaskStore`——`filesDir/xhs_batch/tasks.json` 单文件，`kotlinx.serialization` 存 `List<XhsBatchTaskRecord>`。**不上 Room**（单列表低频写，Room 依赖不值当；项目 app 模块现状也无 Room 依赖链）。

```kotlin
@Serializable
data class XhsBatchTaskRecord(
    val taskId: String,          // UUID
    val userId: String,
    val createdAt: Long,
    val updatedAt: Long,
    val state: String,           // XhsBatchState.name
    val notesFound: Int, val notesProcessed: Int, val notesFailed: Int,
    val imagesDone: Int, val videosDone: Int, val totalBytes: Long,
    val dirPath: String,         // 供列表页「打开目录 / 撤回」定位
)
```

- 写时机：状态每次跃迁 + 每完成一个文件节流合并写（`MutableStateFlow` + 500ms debounce，杀进程最多丢最后 500ms 计数）。
- 运行中恢复：**v1 不做断点续传**（PRD 非目标）。App 被杀后任务记录停在最后快照，状态标记为 `Interrupted`；用户点「继续」= 重新整跑同一 userId，下载层按「目标文件已存在且非 `.part`」跳过（天然去重，等同续传效果）。
- 任务条目操作：进行中 → 暂停/取消/撤回；已结束 → 撤回、删除记录（只删记录不删文件）；上限保留最近 50 条，超出淘汰最旧（文件不动）。
- 同一 userId 重复新建：允许，但列表里同 userId 已有进行中任务时提示先取消旧任务（防止两个任务写同一目录）。

```kotlin
sealed interface XhsBatchState { Idle; Fetching; Running; Paused; Done; Cancelled; Withdrawn; Failed(reason) }

class XhsBatchController(
    private val feed: XhsUserFeedClient,
    private val repo: XhsContentRepository,
    private val downloader: XhsSerialDownloader,
) {
    val progress: StateFlow<BatchProgress>   // PRD §4.4 的 BatchProgress
    fun start(userId: String)                // 校验纯数字 → 创建目录 → 启动 Job
    fun pause() / resume()                   // pauseFlag: paused 时在文件边界 suspend
    fun cancel()                             // job.cancel()，文件保留
    suspend fun withdraw(): WithdrawResult   // cancel() + 递归删除 <userId>/ 目录（返回删除文件数/失败数）
}
```

- 串行保证：全任务一个 `Job`、一个调用链，无二级并发结构；OkHttp `Dispatcher` 限 `maxRequests=1`（双保险）。
- 风控退避（PRD F11）：连续 3 次解析失败或 HTTP 429/461 → 退避 `30s→60s→120s` 后重试当前篇；退避累计 3 轮仍失败 → 置 `Failed` 停整批（已下文件保留，可撤回）。
- 撤回删除在 IO 协程；二次确认弹窗在 UI 层（PRD F12），Controller 只收最终指令。
- `CancellationException` 一律 rethrow，不吞——取消要能立刻穿透 delay 与下载。

## 4. UI（`XhsBatchScreen`）

- 路由：`HubView` 枚举加 `XHS_BATCH("小红书批量下载")`，`HubTiles` 加 tile，`when` 分支出整页——照 `MFA` 的独立整页分支模式（ToolboxScreen.kt:191）。
- 页面结构照 PRD §5：任务列表（首页）→ 新建/详情页：输入行 → 状态徽标 → 四计数 → 当前动作行 → 失败行 → 按钮。进度从 `progress.collectAsState()` 渲染；列表页从 `XhsBatchTaskStore` 读记录 + 订阅进行中任务的 `StateFlow`。Controller 挂 `viewModel`。
- `onBack` / 离开页面：任务若在跑，弹「后台继续 / 取消任务」选择（v1 不做进程级后台服务，Activity 存活期间跑；被系统杀掉即中断，已下文件保留——面板文案如实说明）。

## 5. 开关与配置

- `FeatureCatalog` 加一个 key（`xhs_batch_download`，UI-only 组），默认开；关 = Hub tile 隐藏。hook 侧零改动。
- 节奏常量（间隔区间、上限篇数、退避表）集中在一个 `XhsBatchPacing` 常量对象，便于风控收紧时一刀调。

## 6. 测试

| 层 | 测什么 |
|---|---|
| JVM 单测 | `XhsBatchController`：串行顺序、pause 在文件边界、cancel 不删文件、withdraw 删目录、失败隔离、退避停批（feed/downloader/repo 全用 fake） |
| JVM 单测 | 文件名去冲突、`.part` 半截文件不计数的纯函数 |
| 真机手测 | PRD §6 验收清单（串行抓包、计数对账、撤回删净、断网退避） |
| 构建门禁 | `tools/dev.sh build` + `tools/dev.sh test` |

WebView 翻页无法 JVM 单测 → 用 fake `XhsUserFeedClient` 隔离；真机 spike 单列（见 PRD §7 第一条）。

## 7. 风险与对策

| 风险 | 对策 |
|---|---|
| 页面改版导致拦截选择器失效 | 兜底走 `__INITIAL_STATE__`；拦截按 URL 子串而非精确路径匹配 |
| 匿名会话可见篇数少/触发登录墙 | cookie 自动透传 + 面板提示「内容不全时可先在小红书网页登录」；登录态存储留作 v2 |
| 签名 URL 过期 | 解析即下载（§2.2 串行序已保证） |
| WebView 在主线程卡顿 | 仅用于列表翻页、任务启动一次；解析/下载全在 OkHttp/IO，互不相干 |
| 任务中 App 被杀 | 无进程级保活（v1 明示）；`.part` 机制保证不留脏文件 |
| 大账号下载量巨大 | 上限 200 篇 + 用户可随时暂停/取消；上限值进 `XhsBatchPacing` 可调 |

## 8. 交付物清单（变更范围）

| 文件 | 动作 |
|---|---|
| `app/src/main/kotlin/top/honorhook/ui/screen/XhsBatchScreen.kt` | 新增 |
| `app/src/main/kotlin/top/honorhook/ui/xhsbatch/XhsBatchController.kt` | 新增 |
| `app/src/main/kotlin/top/honorhook/ui/xhsbatch/XhsUserFeedClient.kt` | 新增 |
| `app/src/main/kotlin/top/honorhook/ui/xhsbatch/XhsSerialDownloader.kt` | 新增 |
| `app/src/main/kotlin/top/honorhook/ui/xhsbatch/XhsBatchPacing.kt` | 新增 |
| `app/src/main/kotlin/top/honorhook/ui/xhsbatch/XhsBatchTaskStore.kt` | 新增（持久化 + 任务记录） |
| `app/src/main/kotlin/top/honorhook/ui/screen/ToolboxScreen.kt` | 修改：HubView + tile + 分支 |
| `FeatureCatalog.kt` | 修改：加 1 个 key |
| `app/src/test/.../XhsBatchControllerTest.kt` 等 | 新增单测 |
| 上游 submodule / 白名单 | **不动** |
