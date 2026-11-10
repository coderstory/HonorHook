# 规格：AI 图像工具重构（图优先 / 变体 B）

> 来源：2026-11-11 会话 grilling + HTML 原型验证（`prototype/ai-image-ui.html`，
> 分支 `feature/ai-image-refactor`，UI 变体 B 用户拍板）。原型是 primary source，
> 实现票完成前不要删。

## 目标

推倒重写 `ui/photos/AiImageScreen.kt`（功能 / UI / 代码三面），能力面
`PhotosAiClient`（visionkit 注入通道，见 `docs/reverse/photos.md`）不动，
不加新 AI 能力。

## 已拍板决策

1. **图片来源三轨**：小红书目录（默认，`Pictures/小红书`）＋ 任选目录（顶部来源
   切换器，记住上次选择，存 `AppSettings` prefs）＋ 系统相册 picker
   （`PickVisualMedia`，零权限；选中的图复制进 app cache 再进管线）。
2. **多选批量**：队列串行逐张提交（引擎单次一张），参数整批共用；逐项报进度，
   可取消。
3. **UI = 原型变体 B（网格/队列分屏）**：上 ~60% 缩略图网格（3 列），
   下 ~40% 实时处理队列；网格**点按 = 预览大图，角标点击 = 勾选**；
   参数 chips + 执行按钮常驻队列区下方。
4. **结果列表**：会话内（离开页面即清，不落盘历史）；每项显示 状态/输出路径，
   失败可一键重试。
5. **输出落点**：目录来源 = 原图同目录 `<名>-ai.<ext>`（重名顺延 `-ai2`）；
   picker 来源 = 输出统一落 `Pictures/HonorHookAI/`。
6. **缩略图**：手写 `BitmapFactory`（`inJustDecodeBounds` + `inSampleSize`）+
   `LruCache`，IO 线程解码；**不加图片库依赖**（repo 无 coil/glide，纪律）。

## 非目标

- 不加超清/扩图之外的 visionkit 能力（单独立票）
- 不持久化处理历史
- `PhotosAiClient`、`PhotosAiHook`、图库注入通道零改动

## 验收

- `tools/dev.sh build` 通过；`outpaintScales` 既有 JVM 测试仍绿
- 三轨来源均可进队列；批量 N 张串行跑完，结果逐项可见
- 失败项可见原因并可重试；处理中可取消
