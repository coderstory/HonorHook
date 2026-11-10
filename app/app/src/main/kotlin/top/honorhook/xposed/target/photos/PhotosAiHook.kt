package top.honorhook.xposed.target.photos

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 荣耀图库（com.hihonor.photos）AI 能力执行器（2026-11-10 需求：后台直调 AI 超清/AI 扩图，不走 UI 模拟点击）。
 *
 * ## 调用链（jadx 实证，图库 153.0.0.582，见 docs/reverse/photos.md）
 * 图库编辑器走荣耀视觉 SDK（visionkit，引擎承载方 com.hihonor.visionengine 进程）：
 * - AI 超清 = `com.hihonor.mcs.intelligence.visionkit.api.ImageEnhancer.enhanceImage(param, extras)`
 * - AI 扩图 = `ImageExtender.process(VisionImage, param, extras)`
 * 关键约束：`VisionBinderClient.fetchServiceBinder` 显式 bind
 * `ComponentName("com.hihonor.visionengine", "com.hihonor.vision.service.VisionPluginService")`
 * ——只有图库进程内调用才免权限问题（外部进程 bind 需要 visionengine 侧放行）。
 * SDK 公开类未混淆 ⇒ 直连反射按名取，不需要 DexKit；**必须后台线程**（SDK 主线程调用直接抛
 * IllegalThreadStateException）。
 *
 * ## 业务参数照抄图库调用方（com.hihonor.gallery.editor.ui.aienhance.AiEnhanceClient / ui.imgExtend.ImgExtendClientManager）
 * - 超清：businessName="HwGallery_ai_enhance"、keepAlive=60、feature=GENERAL_IMAGE_ENHANCE
 *   （PHOTO_IMAGE_ENHANCE = 人像长边模式）、extras {IS_AUTHORIZATION=true,
 *   visionengine_call_chain_info="com.hihonor.photos$imageEnhance"}、imageFormat jpeg=0/png=1
 * - 扩图：businessName="HwGallery_Extend"、call_chain="com.hihonor.photos$imageExtender"、
 *   四边 scale（浮点比例）+ rotateAngle
 *
 * ## 通道（无业务 UI，先提供原子能力）
 * 图库进程内注册动态导出广播 `top.honorhook.photos.AI_TASK`：
 * `am broadcast -a top.honorhook.photos.AI_TASK --es path <图片路径> --es mode enhance|outpaint
 *   [--es out <输出路径>] [--ez photoMode false] [--ef top 0.25 --ef bottom 0.25 --ef left 0 --ef right 0]`
 * 结果回发 `top.honorhook.photos.AI_RESULT`（code / out / msg）并落模块日志。
 * 进程未存活时广播无人接收——调用方需先拉起图库（ am start 一次即可，hook 在注入时已注册）。
 *
 * 纪律：全部 runCatching 包裹，异常只记日志，绝不炸图库进程。
 */
object PhotosAiHook : TargetHook {

    override val packageName = "com.hihonor.photos"

    private const val JUDGE_PREFIX = "photos-ai-hook"
    private const val ACTION_TASK = "top.honorhook.photos.AI_TASK"
    private const val ACTION_RESULT = "top.honorhook.photos.AI_RESULT"

    /** SDK 类名（visionkit 公开 API，未混淆，跨图库版本稳定）。 */
    private const val CLS_IMAGE_ENHANCER = "com.hihonor.mcs.intelligence.visionkit.api.ImageEnhancer"
    private const val CLS_IMAGE_EXTENDER = "com.hihonor.mcs.intelligence.visionkit.api.ImageExtender"
    private const val CLS_VISION_IMAGE = "com.hihonor.mcs.intelligence.visionkit.data.VisionImage"
    private const val CLS_ENHANCE_BUILDER = "com.hihonor.mcs.intelligence.visionkit.data.imageenhance.ImageEnhanceParameter\$Builder"
    private const val CLS_EXTEND_BUILDER = "com.hihonor.mcs.intelligence.visionkit.data.imageextend.ImageExtendParameter\$Builder"
    private const val CLS_ENHANCE_MODE = "com.hihonor.mcs.intelligence.visionkit.data.imageenhance.ImageEnhanceMode"

    /** 图库业务名（照抄 AiEnhanceClient/ImgExtendClientManager 常量；服务端可能按此放行调用链）。 */
    private const val BUSINESS_ENHANCE = "HwGallery_ai_enhance"
    private const val BUSINESS_EXTEND = "HwGallery_Extend"
    private const val CALL_CHAIN_ENHANCE = "com.hihonor.photos\$imageEnhance"
    private const val CALL_CHAIN_EXTEND = "com.hihonor.photos\$imageExtender"

    /** SDK 侧权益校验 key（BundleKey.IS_AUTHORIZATION，图库恒传 true）。 */
    private const val KEY_IS_AUTH = "isAuthorization"
    private const val KEY_CALL_CHAIN = "visionengine_call_chain_info"

    /** 像素上限（图库 getInvalidType 同口径 40MP，超限引擎拒绝）。 */
    private const val TOTAL_PIXELS_MAX = 40_000_000L

    /** 单线程后台执行器：SDK 禁主线程，且引擎调用串行化防并发打架。 */
    private val worker by lazy {
        HandlerThread("hh-photos-ai").also { it.start() }.let { Handler(it.looper) }
    }
    private val receiverRegistered = AtomicBoolean(false)

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_PHOTOS_AI)) {
            logDisabled(module, FeatureCatalog.KEY_PHOTOS_AI, "PhotosAiHook")
            return
        }
        // 无挂载点（这不是 hook 点而是能力注入）⇒ 不走 MountEngine；判据日志直接落一条，证明注册路径活着。
        module.log(Log.INFO, "HonorHook", "$JUDGE_PREFIX: install（能力注入型，无挂载点；等宿主 Application 起来后注册任务通道）")
        waitApplicationAndRegister(module)
    }

    /**
     * 轮询拿 Application 后注册导出广播（照 XhsBatchSave.ensureActivityTracker 范式：
     * install 期 Application 常未建好，必须轮询不能只试一次）。
     */
    private fun waitApplicationAndRegister(module: XposedModule) {
        if (!receiverRegistered.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).post(object : Runnable {
            private var attempt = 0
            override fun run() {
                val app = runCatching {
                    Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null) as? Application
                }.getOrNull()
                if (app != null) {
                    runCatching { registerTaskReceiver(app, module) }
                        .onSuccess { module.log(Log.INFO, "HonorHook", "$JUDGE_PREFIX: 任务通道已注册（${app.packageName}）") }
                        .onFailure { module.log(Log.ERROR, "HonorHook", "$JUDGE_PREFIX: 注册失败", it) }
                    return
                }
                if (attempt++ < 60) Handler(Looper.getMainLooper()).postDelayed(this, 500)
            }
        })
    }

    private fun registerTaskReceiver(ctx: Context, module: XposedModule) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_TASK) return
                // 图库有多进程（:ml/:BGS/:crop…），广播会扇出到每一个——并发三路 SDK 调用会被
                // 引擎侧权益/上传链路互踩（实测三进程并发全部 NETWORK_UPLOAD_OBS_ERROR=1017）。
                // 只让主进程处理，其余进程忽略。
                val isMain = runCatching {
                    Class.forName("android.app.ActivityThread").getMethod("currentProcessName")
                        .invoke(null) as? String
                }.getOrNull() == appPkgOrSelf(context)
                if (!isMain) return
                module.log(Log.INFO, "HonorHook", "$JUDGE_PREFIX: 收到任务 mode=${intent.getStringExtra("mode")}")
                // 广播回调在主线程，转后台线程再碰 SDK（主线程直抛）
                worker.post { runCatching { handleTask(context, intent, module) }
                    .onFailure { module.log(Log.ERROR, "HonorHook", "$JUDGE_PREFIX: 任务异常", it) } }
            }

            private fun appPkgOrSelf(ctx: Context): String = ctx.packageName
        }
        val filter = IntentFilter(ACTION_TASK)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            ctx.registerReceiver(receiver, filter)
        }
    }

    // ---- 任务处理 ----

    private fun handleTask(ctx: Context, intent: Intent, module: XposedModule) {
        val path = intent.getStringExtra("path") ?: return reply(ctx, module, -1, null, "path 缺失")
        val mode = intent.getStringExtra("mode") ?: "enhance"
        val out = intent.getStringExtra("out") ?: defaultOutPath(path, mode)
        val bitmap = decodeCapped(path)
            ?: return reply(ctx, module, -2, null, "解码失败（路径不存在或非图片）: $path")

        val (code, msg) = when (mode) {
            "enhance" -> runEnhance(ctx, bitmap, intent, out)
            "outpaint" -> runOutpaint(ctx, bitmap, intent, out)
            else -> -3 to "未知 mode: $mode"
        }
        reply(ctx, module, code, if (code == 0) out else null, msg)
    }

    /** AI 超清：返回 (resultCode, 消息)。photoMode=true 走人像长边增强（PHOTO_IMAGE_ENHANCE）。 */
    private fun runEnhance(ctx: Context, bitmap: Bitmap, intent: Intent, out: String): Pair<Int, String> {
        val loader = ctx.classLoader
        val photoMode = intent.getBooleanExtra("photoMode", false)
        val enhancerCls = Class.forName(CLS_IMAGE_ENHANCER, false, loader)
        val features = listOf(if (photoMode) "photo_img_enhance_feature" else "general_img_enhance_feature")
        val clientOpts = hashMapOf<Any, Any>(KEY_IS_AUTH to true)
        val client = enhancerCls.getMethod(
            "getClient", Context::class.java, Int::class.javaPrimitiveType, List::class.java,
            String::class.java, Map::class.java,
        ).invoke(null, ctx, 60, features, BUSINESS_ENHANCE, clientOpts) ?: return -4 to "getClient 返回 null"

        // open() != 0 = 引擎未就绪（visionengine 进程没起来等）
        val openCode = client.javaClass.getMethod("open").invoke(client) as Int
        if (openCode != 0) return openCode to "open()=$openCode（引擎未就绪，稍后重试）"

        val visionImage = Class.forName(CLS_VISION_IMAGE, false, loader)
            .getMethod("fromBitmap", Bitmap::class.java).invoke(null, bitmap)
            ?: return -5 to "VisionImage.fromBitmap null"

        // ImageEnhanceMode 枚举 ordinal：PHOTO=1 / GENERAL=2（jadx data/imageenhance/ImageEnhanceMode.java）
        val modeEnum = Class.forName(CLS_ENHANCE_MODE, false, loader)
            .getMethod("values").invoke(null) as Array<*>
        val enhanceOrdinal = (if (photoMode) modeEnum[0] else modeEnum[1]) as Enum<*>

        val sessionId = client.javaClass.getMethod("getTaskId").invoke(client) as String
        val builder = Class.forName(CLS_ENHANCE_BUILDER, false, loader).getDeclaredConstructor().newInstance()
        withBuilder(builder, "setEnhanceMode", Int::class.javaPrimitiveType, enhanceOrdinal.ordinal)
        withBuilder(builder, "setSessionId", String::class.java, sessionId)
        withBuilder(builder, "setUri", String::class.java, "file://$out")
        withBuilder(builder, "setVisionImage", Class.forName(CLS_VISION_IMAGE, false, loader), visionImage)
        withBuilder(builder, "setOriginalWidth", Int::class.javaPrimitiveType, bitmap.width)
        withBuilder(builder, "setOriginalHeight", Int::class.javaPrimitiveType, bitmap.height)
        // imageFormat：jpeg=0 / png=1（AiEnhanceClient.getEnhanceMimeType）
        withBuilder(builder, "setImageFormat", Int::class.javaPrimitiveType, if (out.endsWith(".png")) 1 else 0)
        val param = builder.javaClass.getMethod("build").invoke(builder)

        val extras = hashMapOf<String, Any>(KEY_CALL_CHAIN to CALL_CHAIN_ENHANCE)
        if (photoMode) extras["extra_enhance_priority"] = 1
        val result = client.javaClass.getMethod(
            "enhanceImage",
            param.javaClass.superclass?.takeIf { it.name.endsWith("ImageEnhanceParameter") } ?: param.javaClass,
            HashMap::class.java,
        ).invoke(client, param, extras)
        return readEnhanceResult(loader, result, out)
    }

    /** AI 扩图：四边 scale（0~1 比例），至少一边 >0。 */
    private fun runOutpaint(ctx: Context, bitmap: Bitmap, intent: Intent, out: String): Pair<Int, String> {
        val loader = ctx.classLoader
        val top = intent.getFloatExtra("top", 0f)
        val bottom = intent.getFloatExtra("bottom", 0f)
        val left = intent.getFloatExtra("left", 0f)
        val right = intent.getFloatExtra("right", 0f)
        if (top <= 0 && bottom <= 0 && left <= 0 && right <= 0) return -6 to "扩图四边 scale 全为 0"

        val extenderCls = Class.forName(CLS_IMAGE_EXTENDER, false, loader)
        val clientOpts = hashMapOf<Any, Any>(KEY_IS_AUTH to true)
        val client = extenderCls.getMethod(
            "getClient", Context::class.java, Int::class.javaPrimitiveType,
            String::class.java, Map::class.java,
        ).invoke(null, ctx, 60, BUSINESS_EXTEND, clientOpts) ?: return -4 to "getClient 返回 null"

        val openCode = client.javaClass.getMethod("open").invoke(client) as Int
        if (openCode != 0) return openCode to "open()=$openCode（引擎未就绪，稍后重试）"

        val visionImage = Class.forName(CLS_VISION_IMAGE, false, loader)
            .getMethod("fromBitmap", Bitmap::class.java).invoke(null, bitmap)
            ?: return -5 to "VisionImage.fromBitmap null"

        val builder = Class.forName(CLS_EXTEND_BUILDER, false, loader).getDeclaredConstructor().newInstance()
        withBuilder(builder, "setTop", Float::class.javaPrimitiveType, top)
        withBuilder(builder, "setBottom", Float::class.javaPrimitiveType, bottom)
        withBuilder(builder, "setLeft", Float::class.javaPrimitiveType, left)
        withBuilder(builder, "setRight", Float::class.javaPrimitiveType, right)
        withBuilder(builder, "setRotateAngle", Int::class.javaPrimitiveType, 0)
        val param = builder.javaClass.getMethod("build").invoke(builder)

        val extras = hashMapOf<String, Any>(KEY_CALL_CHAIN to CALL_CHAIN_EXTEND)
        val process = extenderCls.getMethod(
            "process",
            Class.forName(CLS_VISION_IMAGE, false, loader),
            param.javaClass.superclass?.takeIf { it.name.endsWith("ImageExtendParameter") } ?: param.javaClass,
            Map::class.java,
        ).invoke(client, visionImage, param, extras)
        return readExtendResult(loader, process, out)
    }

    /** 小工具：反射逐个调 Builder setter。 */
    private fun withBuilder(builder: Any, method: String, ptype: Class<*>?, value: Any?) {
        builder.javaClass.getMethod(method, ptype).invoke(builder, value)
    }

    // ---- 结果读取与落盘 ----

    private fun readEnhanceResult(loader: ClassLoader, result: Any?, out: String): Pair<Int, String> {
        if (result == null) return -7 to "enhanceImage 返回 null"
        val code = result.javaClass.getMethod("getResultCode").invoke(result) as Int
        if (code != 0) return code to "enhanceImage code=$code"
        val enhanceResult = result.javaClass.getMethod("getResult").invoke(result)
            ?: return -8 to "resultCode=0 但 result=null"
        val outImage = enhanceResult.javaClass.getMethod("getOutImage").invoke(enhanceResult)
            ?: return -9 to "outImage=null"
        val bitmap = outImage.javaClass.getMethod("getBitmap").invoke(outImage) as? Bitmap
            ?: return -10 to "outBitmap=null"
        return if (saveBitmap(bitmap, out)) 0 to "OK ${bitmap.width}x${bitmap.height}"
        else -11 to "保存失败: $out"
    }

    private fun readExtendResult(loader: ClassLoader, result: Any?, out: String): Pair<Int, String> {
        if (result == null) return -7 to "process 返回 null"
        val code = result.javaClass.getMethod("getResultCode").invoke(result) as Int
        if (code != 0) return code to "process code=$code"
        val extendResult = result.javaClass.getMethod("getResult").invoke(result)
            ?: return -8 to "resultCode=0 但 result=null"
        val extendImage = extendResult.javaClass.getMethod("getExtendImage").invoke(extendResult)
            ?: return -9 to "extendImage=null"
        val bitmap = extendImage.javaClass.getMethod("getBitmap").invoke(extendImage) as? Bitmap
            ?: return -10 to "extendBitmap=null"
        return if (saveBitmap(bitmap, out)) 0 to "OK ${bitmap.width}x${bitmap.height}"
        else -11 to "保存失败: $out"
    }

    private fun saveBitmap(bitmap: Bitmap, out: String): Boolean = runCatching {
        val f = File(out)
        f.parentFile?.mkdirs()
        f.outputStream().use { os ->
            if (out.endsWith(".png")) bitmap.compress(Bitmap.CompressFormat.PNG, 100, os)
            else bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
        }
        true
    }.getOrDefault(false)

    /** 解码 + 40MP 像素帽（超限按 inSampleSize 抽样，同图库 getInvalidType 口径）。 */
    private fun decodeCapped(path: String): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        var px = bounds.outWidth.toLong() * bounds.outHeight
        while (px > TOTAL_PIXELS_MAX) { sample *= 2; px /= 4 }
        BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }.getOrNull()

    private fun defaultOutPath(path: String, mode: String): String {
        val f = File(path)
        val dir = f.parentFile ?: return "/sdcard/Pictures/hh-ai-${UUID.randomUUID()}.png"
        val stem = f.nameWithoutExtension
        return File(dir, "$stem-hh-${mode}-${System.currentTimeMillis()}.png").absolutePath
    }

    private fun reply(ctx: Context, module: XposedModule, code: Int, out: String?, msg: String) {
        module.log(if (code == 0) Log.INFO else Log.WARN, "HonorHook", "$JUDGE_PREFIX: code=$code out=$out msg=$msg")
        runCatching {
            ctx.sendBroadcast(Intent(ACTION_RESULT)
                .putExtra("code", code)
                .putExtra("out", out)
                .putExtra("msg", msg)
                .setPackage("top.honorhook"))
        }
    }
}
