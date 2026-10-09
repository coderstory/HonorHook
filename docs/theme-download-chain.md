# 付费主题下载卡 0% — 下载链路反查与卡点定位

> 真机现象：付费主题被 hook 免费化后，详情页"下载"按钮可点，进度永远 0%。
> 分析对象：`system-files/theme-store-jadx/sources`（主题商店 20.1.63.305）。
> 行号均为该目录相对路径；hook 现状见 `app/.../ThemeStoreHook.kt`（37 项，F2 全开）。
> 结论先行：**卡点 = 候选 2（下载 URL 获取失败）**——F2 免费 hook 把付费主题推入"免费直下"分支，
> 而该分支直接用详情接口 JSON 里的 `fileUrl` 当下载地址；付费主题服务端不在 JSON 里下发可用直链
> （真实 URL 只在购买/试用授权接口 `sendProduct` 返回）。空/占位 URL 在下载器里**静默短路、不报错**，
> UI 按钮停留在"已开始、进度 0%"状态，永不更新。下文给全链路 + 证据 + 可行的 hook 绕法。

## 1. 下载链路全图（每环 file:line，均在 jadx 源码实证）

```
详情页"下载"按钮点击
  └─ OnlinePreviewBaseActivity$DownloadStartState.start()         OnlinePreviewBaseActivity.java:1051-1078
       （1055 行用 isNeedPayItem 只做埋点；1059 行 startItemDownload(1) → downloadType=NORMAL）
  └─ OnlineThemePreviewActivity.downloadItem(1)                   OnlineThemePreviewActivity.java:1158-1163
  └─ common.d.startDownloadTheme(ctx, itemInfo, type=1, src=1)    common/d.java:225-234
       ├─ new DownloadInfo(itemInfo) → setDownloadInfo():         common/d.java:169-223
       │    172 行 downloadInfo.uri = itemInfo.downloadUrl        ←── 核心：URL 来源是 ItemInfo.downloadUrl
       │    180-182 行 downloadInfo.price 由 itemInfo 价格字段推导（→ isPayedItem 判定源头）
       │    233 行 setNeedLogin(!themeInfo.isNoNeedLogin())
       └─ o52.addDownloadItem(builder)                            defpackage/o52.java:563-634
             ├─ 604 行 if (!isPayedItem()) → 免费分支（hook 后付费主题走这里）:
             │    609-611 isNeedLogin → h71 登录门（仅弹登录，不改 URL）  defpackage/h71.java:57-68
             │    612 行 z61Var.downloadItem()
             │        └─ z61.downloadItemInfo()                    defpackage/z61.java:41-61
             │              └─ o52.resumeDownload(info, false)      defpackage/o52.java:771-833
             │                    └─ 814 行 z71.download(downloadInfo.uri, filePath, …)  defpackage/z71.java:18-36
             │                          └─ DownloadHandler 裸 GET   downloader/http/DownloadHandler.java
             │                                (104-129 getHttpUrlConnection：只设 Method+连接超时15s，无请求头无读超时)
             │                                (218-267 sendRequest；320 行置 STARTED；onLoading 354-358 才报进度)
             └─ 615-633 行 else → 付费分支（真实购买路径，hook 后不再走）：
                   按 downloadType 选策略：2→e71(试用) 3→f71(VIP) 4→a71(审计) 5→b71(金币) 6→d71(限时) 默认 c71(普通购买)
                   所有策略的第一步都是调 c.getInstance().getDownloadUrl() = OrderManagerImpl
                   （c71.java:58、e71.java:30、f71.java:30、a71.java:28 均调 sendProduct 拿真实 URL 后才下载）
```

## 2. 两条 URL 来源路线（关键分叉）

| 路线 | 触发条件 | URL 来源 | 结果 |
|---|---|---|---|
| A 免费直下 | `DownloadInfo.isPayedItem()==false`（F2 价格 hook 后为 false，DownloadInfo.java:939-947 与 ItemInfo 同款 price 逻辑） | `itemInfo.downloadUrl` ← 服务端资源 JSON 的 `fileUrl` 字段（u02.java:190 `setDownloadUrl(themeBean.getFileUrl())`；deep-link 路径 common/g.java:596 同） | 裸 GET，无任何鉴权头 |
| B 付费/试用授权 | `isPayedItem()==true` | `OrderManagerImpl.getDownloadUrl()` → `sendProduct` 接口（OrderManagerImpl.java:292-352）：请求字段只有 hitopId/resId/resType/trialFlag（302-306），**不含订单号**；服务端按账户的购买/授权记录校验，返回 `SendProductInfo.downUrl`（334）+ 内容密钥（335 saveResContentKey） | 真实 URL 形如 `…download.do?k=<key>`（DownloadInfo.java:927-933 isFixUrl 的判定串），K 全部服务端下发，客户端无任何本地拼接（全库检索 `?k=` 仅此一处） |

## 3. 卡点结论与证据链

**结论：候选 2 —— 下载 URL 获取失败（本地 hook 把 URL 获取环节跳过了）。**

证据链（逐级实证）：

1. **hook 后的实际走向**：ThemeStoreHook.kt 挂的是 ItemInfo/BaseResItemBean 的价格与判定（:135-168），
   未挂 `DownloadInfo` 系。`setDownloadInfo` 用**价格字段**推导 `DownloadInfo.price`→0 → `isPayedItem()`=false
   → o52.addDownloadItem 走免费分支（o52.java:604-613），**跳过了 sendProduct URL 获取**（那是付费分支 615-633 才有）。
2. **免费分支下载的就是 itemInfo.downloadUrl**（common/d.java:172），该字段来自服务端 JSON `fileUrl`（u02.java:190）。
   付费主题的 `fileUrl` 服务端不下发可用直链（证据：付费四大策略无一用 item JSON 的 URL，全部重新调 sendProduct；
   且 isFixUrl 要求付费 URL 必须含服务端签发的 `download.do?k=`）。
3. **为什么是静默 0% 而不是报错**（与现象完全吻合）：
   - uri 为空/占位 → `z71.download` 对非 null 空串放行（z71.java:25-27 只挡 null），
     DownloadHandler.getHttpUrlConnection 见 `TextUtils.isEmpty(r)` 直接返 null（DownloadHandler.java:107-109），
     sendRequest 记一条 log 后 **return null，不抛异常**（:226-229）→ 线程状态停在 STARTED（:320），
     无 FAILURE、无 onLoading、无任何回调；
   - uri 为 null → z71.java:25-27 返 null handler → o52.resumeDownload :819-822 提前 return，同样无状态推进；
   - uri 存在但被服务端挂起（DownloadHandler 只有 connect 超时 15s、**无 read 超时**，:118）→ 读响应会永久阻塞，
     进度同样永远 0%。
   - 期间 UI 侧按钮早在 DownloadStartState.start() 被置为"开始+进度 0"（OnlinePreviewBaseActivity.java:1060-1067），
     之后没有更新事件 → "永远 0%"画面定格。
4. **排除候选 1（本地下载鉴权拦截）**：DownloadHandler 是裸 `HttpURLConnection` GET，无 token/签名/header
   （getHttpUrlConnection 仅 setRequestMethod + setConnectTimeout，:116-118）；链路上唯一的"门"是 h71 登录
   （只弹账密登录，h71.java:57-68），登录后放行，不产出/不校验 URL。
5. **候选 3（服务端流级拒绝）的判定**：若 fileUrl 存在而 CDN 拒流，`getResponseCode()>=300` 会抛 HttpException
   （DownloadHandler.java:135-139，HttpException 不重试 :246-247）→ FAILURE 状态 → UI 明确显示失败/重试，
   **不是**静默 0%。现状为静默 0%，故主因是 URL 为空/占位（候选 2）；流级拒绝只是同族第二子机制
   （连接挂起无读超时），可用真机日志区分（见 §5）。

## 4. 可行修复（hook 方案）

**原理**：服务端不需要真实订单也能签发的授权 URL 通道 = **试用授权 `sendProduct(trialFlag=1)`**
（e71 试用策略正是为公开"试用"按钮走的链路：e71.java:30 传 1 → 服务端发试用 URL + 试用内容密钥 →
productSendFinish 成功回调里 `uri=str; super.downloadItem()`，e71.java:62-66）。F3/F4 已把试用态永久化，
把付费主题导入"试用下载"策略即可完成下载。

**推荐 hook 组合（两个点，条件均判 `uri 为空`，避免误伤真免费资源）**：

| # | 目标 | 签名 | hook 改法 | 效果 |
|---|---|---|---|---|
| H1 | `common.DownloadInfo.isPayedItem` (common/DownloadInfo.java:939) | `()Z` | uri 为空/占位时返回 `true`，否则放行原逻辑 | 空 URL 的项进入付费分支 |
| H2 | `o52.addDownloadItem(Context, DownloadInfo, int, boolean)` (defpackage/o52.java:882) | `(Landroid/content/Context;Lcom/hihonor/android/thememanager/common/DownloadInfo;IZ)Z` | 当 `downloadInfo.uri` 为空时把 int 参数强制为 `2`（TRIAL），否则放行 | 空 URL 的项走 e71 试用策略（付费分支 617-618 行选中 e71） |

- 组合后流程：点击下载 → isPayedItem()=true 且 type=2 → e71 → 异步 sendProduct(trialFlag=1) →
  服务端签发试用 URL+密钥 → 下载开始 → 应用时 F4 的 getThemeApplyState→PERMANENT 等 hook 让试用不回收
  （apply 侧 hook 已就位，ThemeStoreHook.kt:249-271）。
- **前置条件**：设备主题商店**已登录荣耀账户**（sendProduct 按账户授权；体检现链路上 h71 登录门同样需要）。
- **注意（勿踩）**：若只 hook H1 不 hook H2，type=1 会走 c71 普通购买策略 → 弹 IAP 支付页（c71.java:70-83），
  不是目标；H2 必须只对空 URL 项强制 2，真免费资源（有 fileUrl）保持 NORMAL 分支不受影响。
- **备选方案（更少侵入但更弱）**：hook `z71.download` 入口，uri 为空时用
  `OrderManagerImpl.getInstance().getDownloadUrl(downloadInfo, 1)` 现场补 URL——但该调用是阻塞网络
  （OrderManagerImpl.java:308 `.execute()`），而免费分支调用链在 UI 线程，有 ANR/NetworkOnMainThread 风险，不推荐。

## 5. 真机验证方法（判据）

LSPosed 日志（TAG `HonorHook` / 商店日志 `DownloadService` / `DownloadItemCommand` / `DownloadHandler`）：
- 出现 `DownloadHandler: httpURLConnection is null` 或 `DownloadUtils: download error url may not be null`
  → 确认 uri 为空（候选 2 主机制，修复方案 §4 适用）；
- 出现 `DownloadHandler: handleResponse statusCode 403/401` → 服务端流级拒绝（候选 3 边缘），
  需另想真实购买通道，§4 方案也可能被服务端风控拒（如实评估）；
- 出现 `DownloadItemIfTrial: productSendFinish success ,start To download` →
  §4 hook 生效、URL 已拿到，再看后续 onLoading 是否推进。
- 无日志时可用 `DownloadInfo` DB 行（表列 `url`）旁证 uri 实际值。

## 6. 结论一句话

付费主题的下载 URL 只由服务端购买/试用授权接口（sendProduct）签发，item JSON 的 `fileUrl` 对付费资源
不包含可用直链；F2 免费 hook 把流程推入"免直下"分支跳过了签发环节，空 URL 在无读超时、无错误回调的
DownloadHandler 里静默悬停，UI 定格 0%。修法：H1+H2 把空 URL 的项导入试用授权下载策略，配合已就位的
F3/F4 试用永久化 hook 闭环。