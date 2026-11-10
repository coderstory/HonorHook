# 荣耀图库（com.hihonor.photos）逆向记录

> 2026-11-10 首次分析。目的：AI 超清/AI 扩图后台直调能力（用户需求：不给 UI 模拟点击，要程序级调用）。
> 实装：`xposed/target/photos/PhotosAiHook.kt`（能力注入型，无挂载点）。

## 一、版本与结构

- 设备版本 **153.0.0.582**（versionCode 1530000582），APK `/product_h/region_comm/china/app/Gallery2/Gallery2.apk`（182MB，14 个 dex）
- AI 编辑入口：图库图片详情底部「AI 编辑」→ 面板（AI 消除/AI 超清/智能抠图/AI 扩图/AI 风格），UI 路径已实测走通
- **无公开直调入口**：导出组件只有 VIEW/PICK/GET_CONTENT/EDIT 选图入口，AI 功能是编辑器内部流程

## 二、AI 能力调用链（jadx 实证）

- 图库 AI 超清 = 荣耀视觉 SDK visionkit：`com.hihonor.mcs.intelligence.visionkit.api.ImageEnhancer.enhanceImage(param, extras)`
- AI 扩图 = `ImageExtender.process(VisionImage, param, extras)`（四边 scale + rotateAngle）
- SDK 类**未混淆**（公开 API），dex：classes9（图库编辑器调用方）/ classes10/11（SDK）
- 引擎承载方：`com.hihonor.visionengine`（系统特权应用，版本 11.0.100.12，plugin splits 按引擎分包）
- bind 目标：`VisionBinderClient.fetchServiceBinder` 显式 `ComponentName("com.hihonor.visionengine", "com.hihonor.vision.service.VisionPluginService")`
  ——外部进程 bind 受权限限制 ⇒ **只在图库进程内调用**（hook 注入正是如此）
- SDK 约束：主线程调用直接抛 `IllegalThreadStateException`；`queryEnhanceServiceRight` 表明有权益/配额检查（与 UI 手点共用）
- 端侧推断：`libXYAIBridgeMNNJni.so`（MNN 本地推理）等；`libImageErase_Android_Cloud.so` 暗示「消除」走云端（未验证）

## 三、业务参数（照抄图库调用方，勿自造）

- 超清调用方 `com.hihonor.gallery.editor.ui.aienhance.AiEnhanceClient`：
  - `getClient(ctx, 60, [GENERAL_IMAGE_ENHANCE|PHOTO_IMAGE_ENHANCE], "HwGallery_ai_enhance", {IS_AUTHORIZATION=true, businessName=...})`
  - Builder：`setEnhanceMode(GENERAL=2 / PHOTO=1 枚举 ordinal)` + `setSessionId(getTaskId())` + `setUri` + `setVisionImage(VisionImage.fromBitmap)` + `setOriginalWidth/Height` + `setImageFormat(jpeg=0/png=1)`
  - 先 `open()`（非 0 = 引擎未就绪），后 `enhanceImage(param, {visionengine_call_chain_info: "com.hihonor.photos$imageEnhance"})`
  - 结果：`VisionResult.getResultCode()==0` → `getResult().getOutImage().getBitmap()`
  - 像素约束（getInvalidType）：短边 ≥100、长边 ≤8192、总像素 <40MP
- 扩图调用方 `ui/imgExtend/ImgExtendClientManager`：
  - `getClient(ctx, 60, "HwGallery_Extend", {IS_AUTHORIZATION=true})`，call_chain=`"com.hihonor.photos$imageExtender"`
  - Builder：`setTop/setBottom/setLeft/setRight(float scale)` + `setRotateAngle(0)`
  - `process(visionImage, param, extras)` → `getResult().getExtendImage().getBitmap()`

## 四、实装设计（PhotosAiHook）

- 图库进程内注册动态导出广播 `top.honorhook.photos.AI_TASK`：
  `am broadcast -a top.honorhook.photos.AI_TASK --es path <图片> --es mode enhance|outpaint [--es out <输出>] [--ez photoMode] [--ef top/bottom/left/right <scale>]`
- 结果回发 `top.honorhook.photos.AI_RESULT`（code/out/msg）+ 模块日志（前缀 `photos-ai-hook`）
- 进程未存活时广播无人接收——先 `am start` 图库一次（Application 起来即完成通道注册，轮询 60×500ms）
- FeatureCatalog：`photos.ai_capability`（默认开）；scope.list 已加 `com.hihonor.photos`

## 五、验证结论（2026-11-10 真机已通过）

- **AI 超清 code=0**：1198×1800 输入 → 2570×4096 输出；**AI 扩图 code=0**：四边比例生效
- 全链路是**云端管线**（engine `image_enhance_engine` 由 cloudplugin split 承载，`ACCESS_SUCCESS` 上传后云端推理返回）
- 排障实录（都对调用成功至关重要）：
  1. `BundleKey.IS_AUTHORIZATION = "isAuthorization"`（**驼峰**，字符串写错 = prepare 2118 协议未授权；引擎侧 ImageEnhanceTaskManager.generalEnhanceAuth 直接读 prepare bundle 的该键）
  2. **广播只允许主进程处理**：图库多进程（:ml/:BGS/:crop）各注册了接收器，并发三路 SDK 调用互相干扰（已加 processName 过滤）
  3. `1017 = NETWORK_UPLOAD_OBS_ERROR` 桶，实际含义要看引擎日志：云端内容审查拒绝（`101114001026 the image content contains sensitive information`，屏幕截图会触发）也映射到 1017——**报错语义以引擎侧 Vision_ImageEnhanceTaskManager 日志为准**
  4. 测试图：屏幕截图会被云端敏感审查拦截；用普通照片验证

## 六、使用方式（装好后，图库进程存活时）

```bash
# AI 超清（photoMode=true 走人像长边增强）
am broadcast -a top.honorhook.photos.AI_TASK --es path /sdcard/test.jpg --es mode enhance [--ez photoMode true]
# AI 扩图（四边比例，float）
am broadcast -a top.honorhook.photos.AI_TASK --es path /sdcard/test.jpg \
  --es mode outpaint --ef top 0.2 --ef bottom 0.2 --es out /sdcard/out.png
```

结果回发 `top.honorhook.photos.AI_RESULT`（code/out/msg）+ 模块日志 `photos-ai-hook`。
图库进程未存活时先 `am start`/monkey 拉起一次（Application 起来即注册通道）。

## 七、验证清单（历史遗留，留档）

1. `tools/dev.sh deploy-release`（或 MT 管理器装 debug 包 + `enable-module`）
2. 打开一次图库（任意页面），看模块日志 `photos-ai-hook: 任务通道已注册`
3. 超清：`am broadcast -a top.honorhook.photos.AI_TASK --es path /sdcard/test.jpg --es mode enhance`
4. 扩图：`... --es mode outpaint --ef top 0.3 --ef bottom 0.3`
5. 看回发/日志 code=0 + 输出文件存在；非 0 对照 §三 open()/enhance code
