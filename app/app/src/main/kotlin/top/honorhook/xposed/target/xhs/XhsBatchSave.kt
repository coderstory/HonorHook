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
import java.net.HttpURLConnection
import java.net.URL
import java.util.IdentityHashMap

/**
 * X5 批量保存（引擎 XhsHook·X5）：图片笔记长按「保存」→ 本张原生保存 → 追加弹窗 → **模块直下剩余全部**。
 *
 * 架构（2026-10-14 四版定案，外挂解析）：不碰 app 内部结构——图集数据从 [XhsVideoSave] 的
 * okhttp 旁路采集（笔记 JSON images[].url_default，XHS-Downloader 同源），下载由模块自己
 * HttpURLConnection + MediaStore 完成。零混淆依赖、零宿主反射（URL 重写复用 X3 的 token 规则）。
 *
 * 前三版死因存档（勿复活）：
 * 1. 画廊三击——400ms 窗口苛刻 + 双击缩放冲突（用户否决）
 * 2. 拦截-重放本张——dispatch token 一次性，事后重放被下游身份过滤静默丢弃
 * 3. app 内存找图集（controller 图搜 / adapter.setItems 快照）——sheet 控制器图里图集列表
 *    实证为空（三轮 census），MultiTypeAdapter.setItems 真机不存在（串池类名≠运行时类）
 *
 * 挂载点 `mh9.b`（图片长按面板「保存」点击 lambda）：先 proceed（本张原生保存，fresh token，
 * 已带 X1 解锁 + X2 无水印 + X3 原图直链），后弹追加询问。
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

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 宿主 ClassLoader（弹窗 Activity 图搜用；直下链路已零宿主反射）。 */
    @Volatile private var hostLoader: ClassLoader? = null

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        hostLoader = param.defaultClassLoader
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf("com.xingin.xhs"))

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_SAVE_CLICK_LAMBDA,
                methodName = METHOD_INVOKE,
                paramCount = 1,
                returnType = "java.lang.Object",
                id = "xhs:batchSave.saveClick.invoke",
                description = "X5 批量保存·图片长按面板「保存」点击 → 本张原生保存后追加弹窗（外挂直下版）",
                dex = DexHint(
                    strings = listOf(
                        "download blocked, disableSaveMedia=true",
                        "onDownloadClick, noteId=",
                    ),
                ),
            ),
        )
        engine.attemptMount(pending, module, hostLoader!!) { task -> mountOne(task, module) }
    }

    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule): MountEngine.Result =
        engine.mount(task, module, hostLoader!!) { chain ->
            // 先 proceed：本张原生保存必须用当次 dispatch 的 fresh token 跑（v2 重放版死因）
            val result = chain.proceed()

            val controller = extractController(chain.thisObject)
            val activity = controller?.let { findActivity(it) }
            if (activity == null) {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 弹窗未出（controller=${controller != null} activity=false）")
                return@mount result
            }
            engine.judge(module, task, JUDGE_PREFIX, "保存完成，弹追加询问")
            mainHandler.post { maybeShowBatchDialog(activity, module) }
            result
        }

    /** 点击 lambda 的 controller 字段（字段名 d 混淆，按「首个非 null 实例字段值」取）。 */
    private fun extractController(lambda: Any?): Any? {
        if (lambda == null) return null
        return runCatching {
            lambda.javaClass.declaredFields.firstNotNullOfOrNull { f ->
                runCatching { f.isAccessible = true; f.get(lambda) }.getOrNull()
            }
        }.getOrNull()
    }

    /**
     * 类型图搜 Activity（仅弹窗载体用；BFS ≤3 层 + Lazy 主动 getValue 解包 + Dialog 轨）。
     * mh9.c 不持 Activity 直引：lazy 里 LCBBottomSheetDialog（点击时必在显示）→ Dialog.context。
     */
    private fun findActivity(root: Any): Activity? {
        fun derefLazy(v: Any): Any = runCatching {
            val lazyCls = Class.forName("kotlin.Lazy", true, hostLoader ?: return v)
            var cur: Any = v
            var n = 0
            while (lazyCls.isInstance(cur) && n < 4) {
                cur = cur.javaClass.methods.firstOrNull { it.name == "getValue" }?.invoke(cur) ?: break
                n++
            }
            cur
        }.getOrDefault(v)

        val visited = IdentityHashMap<Any, Boolean>()
        var frontier = listOf(root)
        repeat(3) {
            val next = mutableListOf<Any>()
            for (node in frontier) {
                if (visited.put(node, true) != null) continue
                if (node is Activity) return node
                if (node is android.app.Dialog) {
                    runCatching { node.context.unwrapActivity() }.getOrNull()?.let { return it }
                }
                val fields = runCatching { node.javaClass.declaredFields }.getOrNull() ?: continue
                for (f in fields.take(64)) {
                    val v = derefLazy(runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue)
                    if (v is android.content.Context) {
                        v.unwrapActivity()?.let { return it }
                    } else if (!v.javaClass.name.startsWith("java.") &&
                        v.javaClass.name != "kotlin.UNINITIALIZED_VALUE"
                    ) {
                        next += v
                    }
                }
            }
            frontier = next
        }
        return null
    }

    private fun android.content.Context.unwrapActivity(): Activity? {
        var c: android.content.Context = this
        var depth = 0
        while (c is android.content.ContextWrapper && depth < 8) {
            if (c is Activity) return c
            c = c.baseContext
            depth++
        }
        return null
    }

    /** 追加询问：图集来自 [XhsVideoSave.lastNoteImages]（≥2 才弹）；确认后外挂直下。 */
    private fun maybeShowBatchDialog(activity: Activity, module: XposedModule) {
        val urls = XhsVideoSave.lastNoteImages()
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 追加询问判定：已采图集 ${urls.size} 张${if (urls.isEmpty()) "" else "（首张 " + urls.first().take(60) + "…）"}")
        if (urls.size < 2) return
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
        card.addView(tv("已保存本张（原图）", 17, titleColor, bold = true))
        card.addView(tv("检测到本篇还有 $count 张，要一起保存吗？", 14, subColor).apply {
            setPadding(0, d(8), 0, 0)
        })

        fun pillBtn(label: String, filled: Boolean, onClick: () -> Unit) =
            android.widget.TextView(activity).apply {
                text = label
                textSize = 15f
                gravity = android.view.Gravity.CENTER
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
        row.addView(pillBtn("保存全部", filled = true) { dialog.dismiss(); onBatch() }.apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        card.addView(row)
        dialog.setContentView(card)
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
            urls.forEachIndexed { i, raw ->
                val url = toOriginalUrl(raw)
                val saved = runCatching { downloadOne(activity, url, "xhs_${System.currentTimeMillis()}_$i.${extOf(url)}") }
                    .getOrDefault(false)
                if (saved) ok++
                else module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 第 ${i + 1} 张下载失败: ${url.take(80)}")
            }
            mainHandler.post {
                runCatching {
                    android.widget.Toast.makeText(activity, "已保存 $ok/${urls.size}", android.widget.Toast.LENGTH_SHORT).show()
                }
            }
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 外挂直下完成 $ok/${urls.size}")
        }, "HonorHook-xhs-batch").start()
    }

    /** 原图化：剥 `?`/`!` 压缩参数（imageView2 等），保持原 host——sns-na-i6 无参路径即原图；
     *  不换 host（fileid 形态带目录前缀 spectrum/…，换 host 拼接规则未实证，风险大于收益）。 */
    private fun toOriginalUrl(url: String): String =
        if (url.startsWith("http")) url.substringBefore('?').substringBefore('!') else url

    private fun extOf(url: String): String =
        url.substringBefore('?').substringAfterLast('/').substringAfterLast('.')
            .takeIf { it.length in 2..5 && it.all(Char::isLetterOrDigit) } ?: "jpg"

    private fun downloadOne(activity: Activity, url: String, fileName: String): Boolean {
        var u = url
        var redirects = 0
        while (true) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", UA)
            conn.setRequestProperty("Referer", "https://www.xiaohongshu.com/")
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        if (++redirects > 5) return false
                        u = conn.getHeaderField("Location") ?: return false
                        continue
                    }
                    200 -> {
                        val values = ContentValues().apply {
                            put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                            put(MediaStore.Images.Media.MIME_TYPE, "image/${if (extOf(url) == "png") "png" else "jpeg"}")
                            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/小红书")
                        }
                        val outUri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                            ?: return false
                        activity.contentResolver.openOutputStream(outUri)?.use { out ->
                            conn.inputStream.use { input ->
                                val buf = ByteArray(64 * 1024)
                                var total = 0L
                                while (true) {
                                    val n = input.read(buf)
                                    if (n < 0) break
                                    total += n
                                    if (total > MAX_IMAGE_BYTES) return false
                                    out.write(buf, 0, n)
                                }
                            }
                        } ?: return false
                        return true
                    }
                    else -> return false
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}
