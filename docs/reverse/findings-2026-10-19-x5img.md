# findings：小红书 9.50.0 X5 图片保存链路（2026-10-19，五 dex 定向产物）

> 产物：`.buildtmp/xhs950-partial/sources/`（classes2/6/7/9/20）。resources/ 为空（jadx --no-res）。
> 结论先行：**真机图片长按不走 ji9.b**。ji9.b 只属于「笔记级」feedback 面板（feedback_actions 节点域）；
> 长按**图片**由 `AsyncImageGalleryController.G2` 构建批量面板，保存走 `a59.v.b2` / `ek9.a0` 逐图调
> `nd8.i.e/g/i`，与 ji9.b 零交集——ji9.b 零触发是必然，非 hook 失效。
> 待补产物清单见文末 §7（classes6 重 jadx / classes8/10 / arsc）。

## 1. ji9.b 真实消费方（笔记级 feedback 面板链）
- `ji9/b.java:42` invoke = DownloadController 点击体；锚串 :55 `"onDownloadClick, noteId=" + cVar.Ta().u0()`
- 实例化仅 ji9/c.java 两处：:236 `consume$default(..., new ji9.b(this), 1, ...)` 订阅 **ox8.b = LongPressDialogItemClickEvent**；:201 Lazy 供 Ra()（actionSource `"long_press"`/`"share"`，b.java:39）
- 发布方 `com/xingin/longpress/dialog/item/*Presenter.java`（ItemTopButtonPresenter.java:341/349 等）
- `ji9.c` = 笔记级长按面板 controller（:194-201 构造 DI：NoteFeed/qb9.g/h5e.d/LCBBottomSheetDialog :200；`ji9.c.i` = 面板 BottomSheetDialog 本体）；作用域 `yae.c("feedback_actions")`（:199）
- `ji9.g` :70 锚 `"DownloadLinkerProvider", "enable: type="`；`ji9.e` = IMAGE_DOWNLOAD FunctionSwitch 门（disable 置 enable=false，X1c 同族）
- 同构面板族 fi9/li9/hi9/si9/gi9 各有 feedback_actions scope（非图片保存链）

## 2. ji9.b 点击后（feedback 面板路径）
- b.java:62 `Ta().b1().b()`（disableSaveMedia）→ :63 "download blocked" toast
- b.java:67-87 组 `oc9.i`（fileid=ImageBean.K()、url=qb9.g.o()…）推 `h5e.d` subject；订阅方（→ek9.j.b 桥）不在五 dex
- 同构第二入口：`xb9/r.java:166-178`（onNext oc9.i）/ :196-199 直接 `ISaveImageProxy.a.a(...)`；`pc9/y.java:57-76` onDownloadClick()V

## 3. 批量保存流本体（真机实证路径）
图片长按宿主 = `com/xingin/matrix/notedetail/imagecontent/imagegallery/AsyncImageGalleryController.java`（未混淆）：
- :2321 `e3(y)` 锚行 `"onImageLongClick, currentIndex=" + u0 + ", noteId=" + D2().u0()` → xj9/u0 → xj9/v0 case 1（:55-85）
  → `G2(y,f)`（:1834 jadx 反编译失败）构建 `wqb.j` 勾选面板；失败回落 `zp9.f0.d("long_press_panel")`
- 勾选回传 `xj9/r0.java:44-58`：每图 (checked,url) 写 `controller.W` 与 `NoteFeed.T1()`（key=fileid）；事件 `A(11487, r0)`（v0:36）
- 执行两形态：
  - `a59/v.java:399 b2(...)`：遍历全图 `iVar.e(activity, K(), y0(), Uri.parse(imageBean.n0())...)`（:419-421）；
    **首图回调即弹「已保存」完成卡**（:437 o5.b → wqb.j，:440 setSuccessMainTitle(2131829578)）——后续图成败不回写该卡
  - `ek9/a0.java`（NoteImageSaveHelper，按品牌分实现默认 ek9.j）：:57 锚 `"imageListSave start, count="`；
    `ek9/j.java:122-150` 每图 `nd8.i.a.g(...)`；进度 "N/M" = `ek9/i.java:44-48` `((oVar.a+1)+"/"+list.size())`
- 逐图收口 `nd8/i.java:240 i(...)`：Uri.parse→Fresco 缓存→`nd8.d`→`nd8.j` 水印合成→`nd8.e` 写相册
  （nd8/e.java:39 锚 `"save file to Album success: "`）；缓存未中 :286 `"saveImageBitmapNull"` → r92.b 下载
- 权限门 `nd8/i.java:232 g()`（反编译失败）：`ib2.q.a.a()` false → `ib2.m.f(...)`（=权限申请 helper，
  同签名实证 hx8/m.java:360-373）→ nd8.b 授权后续 / **nd8.c 拒绝回调（静默）**
- 视频参照 `idc/w.java:730-760` 产物内未见（idc 不在五 dex，9.50 已漂）

## 4. 成败判定 / 假 4/4 病灶
- ek9 链：每图 okCb 才推进计数；errCb → DownloadError → ek9/g.java:37-42 关卡 + toast 2131839560（**有失败 toast**）
- 单图错误码：-1 无权限（"save image failed without permission"）、-2/-3 decode、-7 fetch
- **真机「4/4 假成功 + 零文件」最吻合 = a59.v.b2 形态**：完成卡首图回调即弹、与 MediaStore 写解耦；
  若 nd8.i.g 权限分支静默拒绝（nd8.c），或 wqb.j 计数自维护，即进度照走文件全无
- 确证需 wqb.j + nd8.d.subscribe + G2 三个失败体（§7）

## 5. X3 挂点 nd8.i.i 调用方（批量流同样经过）
- SPI `com/xingin/image/SaveImageProxyImpl.java:36`（@Service）→ `nd8.i.e`(:203) → `h`(:164) → `g`(:232 权限门) → nd8.b → **i**(:240)
- ek9 批量 `ek9/j.java:140-145` 直接 `nd8.i.a.g(...)`（g→b→i）；a59.v.b2 走 e()
- **args[3] = 逐图显示 url（`ImageBean.n0()` / d97.a.d()）**——X3 原地重建直链对批量流成立；
  待核：nd8.b 内部是否二次改写 str3

## 6. imagefeed 替代数据源（全内存对象，无 API 重请求）
- 图列表 `NoteFeed.z0()` → ArrayList<ImageBean>（AsyncImageGalleryController.java:1866/1887）
- 勾选 `NoteFeed.T1()` Map<fileid, Pair<Boolean,String>>；单图 `qb9.g.q()/o()`
- 字段：`n0()` 显示 url（裁剪后缀）、`K()` fileid、`L().o()` 滤镜、`d0()` live url、`X()` VideoInfoV2
- `MediaSaveConfig`：`b1().b()` disable_save、`b1().c()` 水印

## 7. 待补产物（优先级序）
1. classes6 重 jadx（dex 已在 .buildtmp/xhs950-dex/）：`--show-bad-code` 找回 `nd8/a.java`、`nd8/b.java`
2. classes8/10 unzip+jadx：`wqb`（完成卡/进度/setSuccessMainTitle/A(11487)）、`d97/a`（批量模型）、`zp9/m`、ek9.a0 调用方、oc9.i 订阅方
3. 四个失败体重出：G2(:1834)、Q1、nd8.d.subscribe、nd8.i.g
4. arsc：2131829578/2131828069/2131839560/2131830058/2131839556 文案 + 2131304937/2131499391 布局 id

## 8. 修复触发点候选（收口优先）
| # | 点 | 层 | 理由 |
|---|---|---|---|
| 1 | `nd8.i.i`（:240） | 收口·串锚 | 单图 SPI/subject、a59.v.b2、ek9 批量全汇入；X3 继续成立，一点覆盖全部入口 |
| 2 | `nd8.e` success 回调（:39 锚） | 收口·串锚 | 唯一成功事实点；X5 接管+外挂直下在此替换最稳 |
| 3 | `ek9.j.a`（:122） | 未混淆接口 | 批量总闸：拿 List 全图 url+fileid，整单接管（≥2 张确认卡、miss 回落） |
| 4 | `AsyncImageGalleryController.e3`（:2321） | 未混淆·入口 | 仅 X5 触发判定（不接管） |
| 5 | `nd8.i.g`（:232） | 反编译失败 | 接管需绕过/补权限分支，否则静默失败复现 |
| × | ji9.b / ji9.c:236 | 散点 | 不是图片长按 handler；12:05 工作路径=笔记级 feedback 面板（保留该覆盖），图片长按需新点 |
