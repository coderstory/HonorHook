package top.honorhook.xposed.target.xhs

import android.app.Activity
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * X5 批量保存（引擎 XhsHook·X5）：图片笔记长按「保存」→ **模块全接管**，自己下全部原图（含当前张）。
 *
 * 架构（2026-10-14 四版定案，外挂解析）：不碰 app 内部结构——图集数据从 [XhsVideoSave] 的
 * okhttp 旁路采集（笔记 JSON images[].url_default，XHS_Downloader_Android 同源），下载由模块自己
 * HttpURLConnection + MediaStore 完成。零混淆依赖、零宿主反射（URL 重写走共享 [XhsImageUrl]，权威源 NoteOutput.imageUrls）。
 *
 * 前三版死因存档（勿复活）：
 * 1. 画廊三击——400ms 窗口苛刻 + 双击缩放冲突（用户否决）
 * 2. 拦截-重放本张——dispatch token 一次性，事后重放被下游身份过滤静默丢弃
 * 3. app 内存找图集（controller 图搜 / adapter.setItems 快照）——sheet 控制器图里图集列表
 *    实证为空（三轮 census），MultiTypeAdapter.setItems 真机不存在（串池类名≠运行时类）
 *
 * 挂载点 `mh9.b`（图片长按面板「保存」点击 lambda）：**模块全接管（不 proceed ⇒ 宿主原保存逻辑不执行）**，
 * 自己下全部原图——1 张静默直下、≥2 张弹确认卡；图集采不到才 `proceed()` 回落宿主原生保存
 * （2026-10-15 定案，取代旧「先 proceed 本张原生保存再追加」：旧法多图必重复一张、单图 `urls.size < 2`
 * 早退导致模块零介入，只剩宿主重编码图）。
 */
object XhsBatchSave {

    private const val JUDGE_PREFIX = "XhsHook·X5"

    private val engine = MountEngine("XhsHook·X5", "com.xingin.xhs")

    /** 图片长按面板「保存」点击 lambda（逻辑体 invoke(Object)，jadx mh9/b.java:41 实证）。 */
    private const val CLS_SAVE_CLICK_LAMBDA = "mh9.b"
    private const val METHOD_INVOKE = "invoke"

    /** 直下参数：UA（xhscdn 对无 UA 请求可能 403）、单图体积上限 50MB、超时。 */
    private const val UA = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    private const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
    private const val CONNECT_TIMEOUT = 10_000
    private const val READ_TIMEOUT = 30_000

    /** 提案 1 重试阶梯（基准 ResumableTransfer.kt:42-58 语义）：同一 URL 最多 3 次尝试，仅瞬时失败重试。 */
    private const val FETCH_MAX_ATTEMPTS = 3
    private const val FETCH_BACKOFF_BASE_MS = 500L
    private const val FETCH_BACKOFF_CAP_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 最近 resumed 的宿主 Activity（弹窗载体；[ensureActivityTracker] 追踪）。 */
    @Volatile private var lastActivity: Activity? = null
    private val activityTracked = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * install 期注册追踪：`mainHandler.post` 轮询 `ActivityThread.currentApplication` 拿 Application，
     * 注册 `registerActivityLifecycleCallbacks`（onResume 记 / onPause 清）。
     *
     * **必须轮询、不能只试一次**（同 XhsVideoSave.ensureActivityTracker，2026-10-14 真机实证）：install
     * 时 Application 往往还没建好，一次性注册拿不到 ⇒ 当前页永远追踪不到 ⇒ 弹窗取不到 Activity。
     */
    private fun ensureActivityTracker() {
        if (!activityTracked.compareAndSet(false, true)) return
        mainHandler.post(object : Runnable {
            private var attempt = 0
            override fun run() {
                val app = runCatching {
                    Class.forName("android.app.ActivityThread")
                        .getMethod("currentApplication").invoke(null) as? android.app.Application
                }.getOrNull()
                if (app != null) {
                    runCatching {
                        app.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
                            override fun onActivityResumed(a: Activity) { lastActivity = a }
                            override fun onActivityPaused(a: Activity) { if (lastActivity === a) lastActivity = null }
                            override fun onActivityCreated(a: Activity, b: android.os.Bundle?) {}
                            override fun onActivityStarted(a: Activity) {}
                            override fun onActivityStopped(a: Activity) {}
                            override fun onActivitySaveInstanceState(a: Activity, b: android.os.Bundle) {}
                            override fun onActivityDestroyed(a: Activity) {}
                        })
                    }
                    return
                }
                if (attempt++ < 60) mainHandler.postDelayed(this, 500)
            }
        })
    }

    /** 宿主 ClassLoader（弹窗 Activity 图搜用；直下链路已零宿主反射）。 */
    @Volatile private var hostLoader: ClassLoader? = null

    /**
     * 宿主长按面板 Dialog 类（`mh9.c.i` 那个 Lazy 的值类型，jadx 实证），挂载期用宿主 loader 缓存一次：
     * [dismissHostPanel] 按**类型**认面板用（不赌混淆字段名）。
     */
    private const val CLS_PANEL_DIALOG = "com.xingin.foundation.core.v2.dialog.LCBBottomSheetDialog"
    @Volatile private var panelDialogClass: Class<*>? = null

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        hostLoader = param.defaultClassLoader
        // 面板 Dialog 类挂载期缓存一次（关面板用；取不到只是面板关不掉，不阻断下载，见 dismissHostPanel）
        panelDialogClass = runCatching { Class.forName(CLS_PANEL_DIALOG, false, hostLoader) }.getOrNull()
        ensureActivityTracker()
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf("com.xingin.xhs"))

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_SAVE_CLICK_LAMBDA,
                methodName = METHOD_INVOKE,
                paramCount = 1,
                returnType = "java.lang.Object",
                id = "xhs:batchSave.saveClick.invoke",
                description = "X5 批量保存·图片长按面板「保存」点击 → 模块全接管（不 proceed）直下全部原图（图集采不到才回落宿主原生保存）",
                dex = DexHint(
                    strings = listOf(
                        "download blocked, disableSaveMedia=true",
                        "onDownloadClick, noteId=",
                    ),
                ),
            ),
            MountEngine.SimpleTask(
                className = "d49.q",
                methodName = "invoke",
                paramCount = 1,
                returnType = "java.lang.Object",
                id = "xhs:batchSave.videoSaveClick.invoke",
                description = "X5·视频保存发起（d49.q invoke default=saveVideo(noteFeed.F0())，d49/v.java:922 实证）→ 保存后弹反馈卡",
                // d49.q = Function1 双参 lambda（构造 (NoteFeed,int)，case 2 分支=saveVideo 组装），
                // d49/v.java:922 实证由保存点击链实例化；9.49.1 直连，锚弱跨版本靠 versionTables
            ),
        )
        engine.attemptMount(pending, module, hostLoader!!) { task -> mountOne(task, module) }
    }

    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule): MountEngine.Result =
        when (task.id) {
            "xhs:batchSave.videoSaveClick.invoke" -> engine.mount(task, module, hostLoader!!) { chain ->
                // d49.q 双分支：case1=分享引导（arg=Triple）default=saveVideo（arg=String）——只对
                // String 轨弹反馈卡，分享路径静默放行
                val isSave = runCatching { chain.getArg(0) is String }.getOrDefault(false)
                // 本轮起点（proceed 前）：X4 若在本轮保存里弹了询问卡，其 lastAskAtMs >= t0（见 askShownAfter）
                val t0 = System.currentTimeMillis()
                val result = chain.proceed()
                if (!isSave) return@mount result
                mainHandler.post {
                    // 判重（2026-10-15，缺陷④）：X4 本轮已弹「下载高清原版视频」就不再弹「已保存本视频」
                    // （真机实证两卡并存）。重复保存落在 X4 的 5 分钟去重窗口内时 X4 不弹卡 ⇒ lastAskAtMs
                    // 仍是旧值 < t0 ⇒ 反馈卡照常可见。
                    // ★ 这里只是**快路径**：X4 写 lastAskAtMs 是在宿主 save API 的**网络响应**到达时
                    //   （retrofit 异步，100~500ms 后），远晚于本判定点 ⇒ 通常不命中。最终保障是 X4 弹卡前
                    //   主动调 [dismissVideoFeedbackCard]（后弹的关先弹的，确定性握手，不依赖时序）。
                    if (XhsVideoSave.askShownAfter(t0)) {
                        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX X4 本轮已弹询问卡，跳过视频反馈卡")
                        return@post
                    }
                    val activity = lastActivity ?: return@post
                    engine.judge(module, task, JUDGE_PREFIX, "视频保存完成，弹反馈卡")
                    runCatching { showVideoSavedDialog(activity, module) }
                        .onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 视频反馈卡失败: ${it.message}") }
                }
                result
            }
            else -> mountClick(task, module)
        }

    /**
     * 图片保存点击：**模块全接管**——图集采不到才 `proceed()` 回落宿主原生保存，否则不 proceed
     * （宿主原保存逻辑整段不执行），自己下全部原图：1 张静默直下、≥2 张弹确认卡。
     */
    private fun mountClick(task: MountEngine.SimpleTask, module: XposedModule): MountEngine.Result =
        engine.mount(task, module, hostLoader!!) { chain ->
            val urls = XhsVideoSave.lastNoteImages()
            if (urls.isEmpty()) {
                // 护栏（2026-10-15）：采不到图集（含缓存过期非本篇，见 XhsVideoSave.currentNoteId 新鲜度门）必须回落宿主——
                // 否则用户点「保存」什么都不发生，或下载到上一篇的图
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 图集未采到，回落宿主原生保存")
                return@mount chain.proceed()
            }
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 接管图片保存，图集 ${urls.size} 张（不 proceed）")
            // 宿主原实现末尾的 `cVar.i.getValue().dismiss()` 随 proceed 一起被跳过 ⇒ 面板得模块自己关
            dismissHostPanel(chain.thisObject, module)
            if (urls.size == 1) {
                // 单图：静默直下原图（不弹卡；旧版 `urls.size < 2` 早退让单图只剩宿主重编码图）
                showOnTrackedActivity(module, "单图静默直下") { downloadAll(it, urls, module) }
            } else {
                showOnTrackedActivity(module, "追加询问") { maybeShowBatchDialog(it, urls, module) }
            }
            engine.judge(module, task, JUDGE_PREFIX, "已接管图片保存，图集 ${urls.size} 张")
            // 不 proceed ⇒ 返回值得自己给：宿主 invoke 返的是 kotlin.Unit（点击 lambda）。用**宿主 loader**
            // 取 Unit.INSTANCE（模块 loader 的同名类不保证同一 class 身份），取不到才回 null。
            runCatching {
                Class.forName("kotlin.Unit", true, hostLoader).getDeclaredField("INSTANCE").get(null)
            }.getOrNull()
        }

    /**
     * 弹窗载体封装（BadToken 修复，2026-10-15）：真机实证用抓到的历史 Activity 建 Dialog 会抛
     * `Unable to add window -- token … is not valid; is your activity running?`。统一改走
     * [lastActivity] 追踪值 + 取不到时 300ms × 16 重试（同 XhsVideoSave.showDownloadedDialog），
     * 绝不再用 controller 图搜出来的 Activity 建窗。主线程执行；异常只 WARN，不影响下载。
     */
    private fun showOnTrackedActivity(module: XposedModule, what: String, attempt: Int = 0, block: (Activity) -> Unit) {
        mainHandler.post {
            val activity = lastActivity
            if (activity == null) {
                if (attempt < 16) {
                    mainHandler.postDelayed({ showOnTrackedActivity(module, what, attempt + 1, block) }, 300)
                } else {
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX $what 未出：无 resumed Activity")
                }
                return@post
            }
            runCatching { block(activity) }
                .onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX $what 失败: ${it.message}") }
        }
    }

    /**
     * 关宿主长按面板（2026-10-15）：宿主 `mh9.b#invoke` 原实现末尾会 `cVar.i.getValue().dismiss()`
     * 收面板——图片分支不 proceed 后这段被一起跳过，面板会留在屏上。
     * 从 lambda（[chain.thisObject]）**按类型**找，不按字段名（混淆名会漂）：字段值是 `kotlin.Lazy`
     * 就先 `getValue()`，结果落在 Dialog 子类（或挂载期缓存的 [panelDialogClass]）就 dismiss；
     * 值不是 Lazy 就下钻一层（lambda 捕获的 controller → 它的 Lazy 面板字段）。
     * 只在主线程调；任何失败仅 WARN，绝不影响下载。
     */
    private fun dismissHostPanel(lambda: Any?, module: XposedModule) {
        mainHandler.post {
            runCatching {
                val loader = hostLoader ?: return@post
                val lazyCls = runCatching { Class.forName("kotlin.Lazy", false, loader) }.getOrNull() ?: return@post
                var node: Any? = lambda
                var depth = 0
                while (node != null && depth < 3) {
                    val fields = runCatching { node.javaClass.declaredFields }.getOrNull()
                    var next: Any? = null
                    var scanned = 0
                    if (fields != null) for (f in fields) {
                        if (++scanned > 64) break
                        val v = runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue
                        val unwrapped = if (lazyCls.isInstance(v)) {
                            // Lazy 未初始化时 getValue() 会就地建实例（不显示）——dismiss 对未显示的
                            // Dialog 是 no-op，最坏只是白建一个实例；建不出来由外层 runCatching 兜住
                            runCatching { v.javaClass.methods.firstOrNull { it.name == "getValue" }?.invoke(v) }.getOrNull()
                        } else v
                        if (unwrapped != null && dismissIfPanel(unwrapped)) {
                            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 已关宿主长按面板（${unwrapped.javaClass.name}）")
                            return@post
                        }
                        if (next == null && unwrapped != null && !unwrapped.javaClass.name.startsWith("java.")) next = unwrapped
                    }
                    node = next
                    depth++
                }
            }.onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 面板关闭失败: ${it.message}") }
        }
    }

    /** Dialog 子类（或缓存的 [panelDialogClass]）→ dismiss；直接调不到就反射找无参 `dismiss`。返回是否已关。 */
    private fun dismissIfPanel(v: Any): Boolean {
        if (v is android.app.Dialog) return runCatching { v.dismiss() }.isSuccess
        if (panelDialogClass?.isInstance(v) != true) return false
        val m = runCatching { v.javaClass.getMethod("dismiss") }.getOrNull() ?: return false
        return runCatching { m.isAccessible = true; m.invoke(v) }.isSuccess
    }

    /** 当前「已保存本视频」反馈卡（X4 弹询问卡前由 [dismissVideoFeedbackCard] 主动关掉）。 */
    @Volatile private var videoFeedbackDialog: android.app.Dialog? = null

    /**
     * 关掉 X5 的「已保存本视频」反馈卡——供 X4 在弹「下载高清原版视频」询问卡前调用（2026-10-15，缺陷④）。
     *
     * 确定性握手（后弹的关先弹的）：视频保存点击 → X5 反馈卡先弹 → 宿主 save API 响应到达 → X4 询问卡
     * 后弹 ⇒ 由 X4 主动关掉先弹的 X5 卡，不依赖两者时序（X4 写 `lastAskAtMs` 在响应到达时，retrofit
     * 异步 ⇒ X5 侧 [XhsVideoSave.askShownAfter] 快路径基本命中不了）。
     * 任何失败静默：绝不能波及 X4 链路。
     */
    fun dismissVideoFeedbackCard() {
        mainHandler.post {
            runCatching { videoFeedbackDialog?.dismiss() }
            videoFeedbackDialog = null
        }
    }

    /** 视频保存反馈卡（复用图片卡样式，单按钮）。 */
    private fun showVideoSavedDialog(activity: Activity, module: XposedModule) {
        val night = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF1E1E22.toInt() else 0xFFFFFFFF.toInt()
        val titleColor = if (night) 0xFFF5F5F7.toInt() else 0xFF17171A.toInt()
        val accent = 0xFFFF2E4D.toInt()
        val dp = activity.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        val card = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(24), d(22), d(24), d(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(22).toFloat(); setColor(bg)
            }
        }
        val title = android.widget.TextView(activity).apply {
            text = "已保存本视频"
            textSize = 17f
            setTextColor(titleColor)
            paint.isFakeBoldText = true
        }
        card.addView(title)
        val ok = android.widget.TextView(activity).apply {
            text = "好"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(d(18), d(11), d(18), d(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(18).toFloat(); setColor(accent)
            }
            setTextColor(0xFFFFFFFF.toInt())
        }
        val dialog = android.app.Dialog(activity)
        ok.setOnClickListener { dialog.dismiss() }
        card.addView(ok.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = d(18) }
        })
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.setCancelable(true)
        // 单例引用（X4 弹询问卡前据此关掉本卡）；用户自己点「好」/返回键关掉时自动清引用
        dialog.setOnDismissListener { if (videoFeedbackDialog === dialog) videoFeedbackDialog = null }
        videoFeedbackDialog = dialog
        dialog.show()
    }

    /** 追加询问（≥2 张）：图集来自 [XhsVideoSave.lastNoteImages]；确认后外挂直下。分流见 [mountClick]。 */
    private fun maybeShowBatchDialog(activity: Activity, urls: List<String>, module: XposedModule) {
        // 全量 URL 不截断（2026-10-15 原图链路诊断）：PC 端 curl 比对需完整 token/query
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 追加询问：已采图集 ${urls.size} 张（首张 " + urls.first() + "）")
        runCatching {
            showStyledDialog(activity, urls.size) { downloadAll(activity, urls, module) }
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 追加弹窗失败: ${it.message}")
        }
    }

    /**
     * 程序化自绘弹窗（宿主进程内无模块资源，全代码绘制）：圆角卡片 + 小红书红实心主按钮 +
     * 灰描边次按钮 + 深色模式自适应。
     */
    private fun showStyledDialog(activity: Activity, count: Int, onBatch: () -> Unit) {
        val night = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF1E1E22.toInt() else 0xFFFFFFFF.toInt()
        val titleColor = if (night) 0xFFF5F5F7.toInt() else 0xFF17171A.toInt()
        val subColor = if (night) 0xFF98989D.toInt() else 0xFF8A8A8E.toInt()
        val accent = 0xFFFF2E4D.toInt()
        val dp = activity.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()

        val card = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(24), d(22), d(24), d(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(22).toFloat()
                setColor(bg)
            }
        }
        fun tv(text: String, size: Int, color: Int, bold: Boolean = false) = android.widget.TextView(activity).apply {
            this.text = text
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) paint.isFakeBoldText = true
        }
        card.addView(tv("下载本篇全部原图", 17, titleColor, bold = true))
        card.addView(tv("本篇 $count 张，一起下载原图吗？", 14, subColor).apply {
            setPadding(0, d(8), 0, 0)
        })

        fun pillBtn(label: String, filled: Boolean, onClick: () -> Unit) =
            android.widget.TextView(activity).apply {
                text = label
                textSize = 15f
                gravity = android.view.Gravity.CENTER
                maxLines = 1
                setPadding(d(18), d(11), d(18), d(11))
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = d(18).toFloat()
                    if (filled) setColor(accent) else {
                        setColor(0x00000000); setStroke(d(1), subColor)
                    }
                }
                setTextColor(if (filled) 0xFFFFFFFF.toInt() else subColor)
                setOnClickListener { onClick() }
            }
        val dialog = android.app.Dialog(activity)
        val row = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            setPadding(0, d(18), 0, 0)
        }
        row.addView(pillBtn("不用了", filled = false) { dialog.dismiss() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = d(10) }
        })
        row.addView(pillBtn("全部下载", filled = true) { dialog.dismiss(); onBatch() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(row)
        dialog.setContentView(card)
        // 真机实测默认 wrap 宽度挤「全部下载」换行（2026-10-15）：定宽 90% 屏 + 按钮单行
        dialog.window?.setLayout(
            (activity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
            android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.setCancelable(true)
        dialog.show()
    }

    /** 外挂直下：后台线程逐张 HttpURLConnection + MediaStore 落「Pictures/小红书」。 */
    private fun downloadAll(activity: Activity, urls: List<String>, module: XposedModule) {
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 外挂直下开始 ${urls.size} 张")
        mainHandler.post {
            runCatching {
                android.widget.Toast.makeText(activity, "开始下载 ${urls.size} 张…", android.widget.Toast.LENGTH_SHORT).show()
            }
        }
        Thread({
            var ok = 0
            var bytes = 0L
            urls.forEachIndexed { i, raw ->
                // 主链 = 裸链（token，无 query）；2026-10-15 基准切换逐语义移植 XHS_Downloader_Android
                // domain/download/NoteOutput.kt:34-45（AUTO：preferred = sns-img-bd 裸链 → 回落 raw 原样，
                // fetch() 按列表顺序逐个试）。toBare 不适用（null）时退回 raw 本身；裸链失败（非 200 /
                // 错误页 Content-Type）由 downloadOne 用 raw 原样回落一次（见 downloadOne）。
                val url = XhsImageUrl.toBare(raw) ?: raw
                // 文件名扩展名仅占位：落盘 MIME/扩展名由 fetchOne 首块魔数定（提案 3，认内容不认 URL）
                val saved = runCatching { downloadOne(activity, module, url, "xhs_${System.currentTimeMillis()}_$i.${extOf(url)}", raw) }
                    .getOrDefault(0L)
                if (saved > 0) { ok++; bytes += saved }
                else module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 第 ${i + 1} 张下载失败: ${url.take(80)}")
            }
            // 完成态提示（替换旧 Toast「已保存 N/N」，用户定案 2026-10-14）；走追踪 Activity（修 BadToken）
            showOnTrackedActivity(module, "完成卡") { showBatchSavedDialog(it, ok, urls.size, bytes) }
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 外挂直下完成 $ok/${urls.size}")
        }, "HonorHook-xhs-batch").start()
    }

    /** 体积显示（单点实现）：1024 进制一位小数 MB（截断）。 */
    private fun fmtSize(bytes: Long): String {
        val mi = bytes / 1048576L
        val frac = (bytes % 1048576L) * 10L / 1048576L
        return "$mi.$frac MB"
    }

    /**
     * 批量下载完成卡（用户定案 2026-10-14）：询问卡用动作态、完成卡才说「已下载」。
     * 标题 = 成功/总数，正文 = 合计落盘体积（有失败张数时追加「，N 张失败」），单按钮关闭。
     * 复用本文件自绘风格（同 [showVideoSavedDialog]）；必须主线程调用。
     */
    private fun showBatchSavedDialog(activity: Activity, ok: Int, total: Int, bytes: Long) {
        val failed = total - ok
        val night = (activity.resources.configuration.uiMode and
            android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = if (night) 0xFF1E1E22.toInt() else 0xFFFFFFFF.toInt()
        val titleColor = if (night) 0xFFF5F5F7.toInt() else 0xFF17171A.toInt()
        val subColor = if (night) 0xFF98989D.toInt() else 0xFF8A8A8E.toInt()
        val accent = 0xFFFF2E4D.toInt()
        val dp = activity.resources.displayMetrics.density
        fun d(v: Int) = (v * dp).toInt()
        val card = android.widget.LinearLayout(activity).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(d(24), d(22), d(24), d(18))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(22).toFloat(); setColor(bg)
            }
        }
        card.addView(android.widget.TextView(activity).apply {
            text = "已下载 $ok/$total"
            textSize = 17f
            setTextColor(titleColor)
            paint.isFakeBoldText = true
        })
        card.addView(android.widget.TextView(activity).apply {
            text = if (failed > 0) "${fmtSize(bytes)}，$failed 张失败" else fmtSize(bytes)
            textSize = 14f
            setTextColor(subColor)
            setPadding(0, d(8), 0, 0)
        })
        val dialog = android.app.Dialog(activity)
        card.addView(android.widget.TextView(activity).apply {
            text = "好"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
            setPadding(d(18), d(11), d(18), d(11))
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = d(18).toFloat(); setColor(accent)
            }
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { dialog.dismiss() }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = d(18) }
        })
        dialog.setContentView(card)
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
        dialog.setCancelable(true)
        dialog.show()
    }

    private fun extOf(url: String): String =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.')
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: "jpg"

    /** 提案 1 退避：500ms << (attempt-1) 封顶 15s（基准 ResumableTransfer.kt:54 `500ms shl attempt` 封顶 15s，本插件 1 基换算）。 */
    private fun backoffMs(attempt: Int): Long =
        (FETCH_BACKOFF_BASE_MS shl (attempt - 1).coerceAtMost(5)).coerceAtMost(FETCH_BACKOFF_CAP_MS)

    /**
     * 直下单张：先抓 [url]；若命中「HTTP 非 200」或「错误页 Content-Type」两类失败，则改用
     * [rawFallback]（采集原串，含 query）重下一次——**仅一次，不递归**。其余失败（超限/写盘异常）不重试。
     * （2026-10-15 基准切换：回落目标由裸链改 raw 原样，NoteOutput.kt:34-45 列表序语义；旧的
     * markQualityFailed 粘性降级随 q/100 主链一并删除。单 URL 内的瞬时失败重试阶梯在 [fetchOne]
     * 内部——基准 ResumableTransfer.kt:42-58 = 逐候选 × maxRetries 双层，候选间各走一轮阶梯。）
     * 返回值 = 落盘字节数（>0 = 成功；0 = 失败），供完成卡合计体积。
     */
    private fun downloadOne(activity: Activity, module: XposedModule, url: String, fileName: String, rawFallback: String? = null): Long {
        val bytes = LongArray(1)
        when (fetchOne(activity, module, url, fileName, bytes)) {
            Fetch.OK -> return bytes[0]
            Fetch.RETRY_FALLBACK -> if (rawFallback != null && rawFallback != url) {
                val retry = LongArray(1)
                if (fetchOne(activity, module, rawFallback, fileName, retry) == Fetch.OK) return retry[0]
            }
            Fetch.FAIL -> Unit
        }
        return 0L
    }

    /** 单地址抓取结果：OK = 已落盘；RETRY_FALLBACK = 非 200（瞬时码耗尽）/ 错误页 / 魔数非图片，可回落 raw 原样重试一次；FAIL = 终局失败。 */
    private enum class Fetch { OK, RETRY_FALLBACK, FAIL }

    /**
     * 单地址抓取（302 跟随）+ MediaStore 落盘到 Pictures/小红书。
     * 错误页识别走 Content-Type **黑名单**：只拒 JSON / XML / text 系说明页；
     * `application/octet-stream` 是合法原图（XHS 原始档实测），**绝不拒收**——其内容真实性
     * 由提案 3 魔数探测兜底。
     *
     * 提案 1 重试阶梯（基准 ResumableTransfer.kt:42-58 语义）：同一 URL 最多 [FETCH_MAX_ATTEMPTS]
     * 次尝试，仅「瞬时失败」（IOException / HTTP 5xx / 408 / 429）重试，退避见 [backoffMs]；重试只
     * 发生在「连接 + 收响应头」阶段——Content-Type 校验通过即进入写盘阶段，此后任何失败维持原
     * 语义（上抛 / 返回值，交外层 bare→raw 候选链），**不在此层重试**（MediaStore insert 不可重复）。
     * 401/403/404/410 等 4xx = 基准 Access 失败码同类（ResumableTransfer.kt:111-113），非瞬时，不重试。
     * 提案 3 魔数探测（基准 ResumableTransfer.kt:171-174 + MediaFileType.kt:8-28）：200 后先读首块
     * （64B）判内容，仅图片类继续；null / 视频类 = 可能错误页/串流 → RETRY_FALLBACK（尚未 insert，
     * 不留半成品）。落盘 MIME/扩展名以 magic 为准。
     * 提案 4 空间预检（基准 ResumableTransfer.kt:137-139 NO_SPACE）：Content-Length 已知且可用
     * 空间 < 需求 + 1MB ⇒ 判 FAIL；长度未知跳过检查（不发明阈值）。
     * 成功时经 [bytesOut] 回传落盘字节数（供完成卡合计体积）。
     */
    private fun fetchOne(activity: Activity, module: XposedModule, url: String, fileName: String, bytesOut: LongArray): Fetch {
        var u = url
        var redirects = 0
        var attempt = 1   // 提案 1：同一 URL 第几次尝试（1 基，上限 FETCH_MAX_ATTEMPTS）
        while (true) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://www.xiaohongshu.com/")
            // 响应码阶段（连接 + 收响应头）单独 try：只有这里的 IOException 算「瞬时失败」可重试
            val code = try {
                conn.responseCode
            } catch (io: IOException) {
                conn.disconnect()
                if (attempt >= FETCH_MAX_ATTEMPTS) throw io   // 全耗尽 = 原失败语义（上抛，downloadAll 记失败）
                val delay = backoffMs(attempt)
                module.log(Log.WARN, MountEngine.TAG,
                    "$JUDGE_PREFIX 直下 瞬时失败（${io.javaClass.simpleName}: ${io.message}），${delay}ms 后第 $attempt 次重试: ${u.take(80)}")
                Thread.sleep(delay)
                attempt++
                continue
            }
            try {
                when (code) {
                    in 300..399 -> {
                        if (++redirects > 5) return Fetch.FAIL
                        u = conn.getHeaderField("Location") ?: return Fetch.FAIL
                        continue
                    }
                    200 -> {
                        // 错误页类型（CDN 直链失效时常回说明页，如「图片不存在」JSON/HTML）；
                        // 非瞬时，不重试，交给回落。octet-stream 是合法原图，不在黑名单内。
                        val ctype = conn.getHeaderField("Content-Type")?.substringBefore(';')?.trim()?.lowercase()
                        if (ctype == "application/json" || ctype == "application/xml" ||
                            ctype?.startsWith("text/") == true
                        ) {
                            return Fetch.RETRY_FALLBACK
                        }
                        // 提案 4 空间预检（基准 ResumableTransfer.kt:137-139 NO_SPACE）：长度已知才查
                        val len = conn.contentLengthLong
                        if (len > 0) {
                            val dir = java.io.File(
                                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "小红书")
                            val usable = dir.usableSpace
                            if (usable < len + 1024L * 1024) {
                                module.log(Log.WARN, MountEngine.TAG,
                                    "$JUDGE_PREFIX 直下 空间不足（需 ${len / 1048576}MB，可用 ${usable / 1048576}MB），放弃")
                                return Fetch.FAIL
                            }
                        }
                        // 提案 3：insert 之前先读首块（64B，读满或流尽为止）跑魔数——null / 视频类 =
                        // 可能错误页/串流，交 raw 回落；此时尚未 insert，MediaStore 不留半成品
                        val head = ByteArray(64)
                        var headFill = 0
                        val input = conn.inputStream
                        while (headFill < head.size) {
                            val n = input.read(head, headFill, head.size - headFill)
                            if (n < 0) break
                            headFill += n
                        }
                        val kind = XhsMediaFileType.detect(if (headFill == head.size) head else head.copyOf(headFill))
                        if (kind == null || kind !in XhsMediaFileType.IMAGE_KINDS) {
                            return Fetch.RETRY_FALLBACK
                        }
                        // 落盘 MIME/扩展名以 magic 为准（基准认内容不认 URL/Content-Type；extOf 初值仅占位）
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName.substringBeforeLast('.') + "." + kind)
                            put(MediaStore.Images.Media.MIME_TYPE, XhsMediaFileType.mimeTypeOf(kind))
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/小红书")
                        }
                        val outUri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: return Fetch.FAIL
                        activity.contentResolver.openOutputStream(outUri)?.use { out ->
                            // 首块先写入（不能丢），再续读剩余
                            out.write(head, 0, headFill)
                            input.use { rest ->
                                val buf = ByteArray(64 * 1024)
                                var total = headFill.toLong()
                                while (true) {
                                    val n = rest.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_IMAGE_BYTES) return Fetch.FAIL
                                    out.write(buf, 0, n)
                                }
                                bytesOut[0] = total
                            }
                        } ?: return Fetch.FAIL
                        return Fetch.OK
                    }
                    // 其余状态码（提案 1）：仅 5xx / 408 / 429 算瞬时重试；其余 4xx（含基准 Access
                    // 失败码 401/403/404/410，ResumableTransfer.kt:111-113）非瞬时直接回落。
                    // 全耗尽返回原失败语义（RETRY_FALLBACK，仍可走 raw 回落）
                    else -> {
                        if ((code >= 500 || code == 408 || code == 429) && attempt < FETCH_MAX_ATTEMPTS) {
                            val delay = backoffMs(attempt)
                            module.log(Log.WARN, MountEngine.TAG,
                                "$JUDGE_PREFIX 直下 瞬时失败（HTTP $code），${delay}ms 后第 $attempt 次重试: ${u.take(80)}")
                            Thread.sleep(delay)
                            attempt++
                            continue
                        }
                        return Fetch.RETRY_FALLBACK
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
