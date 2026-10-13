package top.honorhook.xposed.target.xhs

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

/**
 * X4 视频直链（引擎 XhsHook·X4）：`/api/sns/v10/note/video/save` 响应改写 + 原生
 * origin_video_key 优先直链。
 *
 * 挂载点 `okhttp3.OkHttpClient$Builder.build()`（公开库 API，okhttp3 未 shade——dex 串池实证）。
 * before 反射读 `interceptors` 字段（okhttp 公开源码字段名，跨版稳定）注入动态代理 Interceptor。
 * okhttp3 不在编译期 classpath（模块只依赖 android.jar），请求/响应全走反射调用。
 *
 * 逻辑移植自 savefix XhsSaveFix.java（system-files/xhssavefix-jadx）+ 升级（doc §五）：
 *   - url 含 `/api/sns/` → peekBody(512KB) 采集三级源：origin_video_key（VideoInfo @kd.c 实证，
 *     9.49.1 实体含此 JSON 字段）优先 > 全流最高档（h264/h265/h266 全部 master_url 按
 *     height+avg_bitrate 取最大——XHS-Downloader get_video_link 同语义，h264[0] 只是默认
 *     播放档）> h264[0] master_url（savefix 原版）。noteId = 区段前最近 `"(?:id|note_id)"`
 *     十六进制串；map 上限 500 清空。
 *   - url 含 `/note/video/save` 且 body 含 `"disable"` → disable:false + status:2 +
 *     注 download_url（origin key 重建 `https://sns-video-bd.xhscdn.com/{key}`——XHS-Downloader
 *     video.py 实证原始 key 最高画质；回落 master_url）。ResponseBody 三轨重建（Kotlin
 *     Companion → create(MediaType,String) → create(String,MediaType)）。
 *   - XHS-Downloader 多 stream 按 bitrate 排序**不搬**（origin key 已是最高档，解析成本零收益）；
 *     savefix `wh8.o1.a` 分支兜底**不搬**（9.49.1 名已漂，download_url 注入后客户端分支自愈）。
 *
 * 防重：同一 Builder 实例不重复注入（interceptors.contains 判代理身份）。
 * 失败分级：FAILED（okhttp 缺失说明目标结构大改，重试无意义）。
 */
object XhsVideoSave {

    private const val JUDGE_PREFIX = "XhsHook·X4"

    private val engine = MountEngine("XhsHook·X4", "com.xingin.xhs")

    private const val CLS_OKHTTP_BUILDER = "okhttp3.OkHttpClient\$Builder"
    private const val METHOD_BUILD = "build"

    /** savefix P_MASTER_MP4 同款（h264 流数组内的 master_url，限定 4000 字符窗口防跨流误配）。 */
    private val P_MASTER_MP4: Pattern =
        Pattern.compile("\"h264\"\\s*:\\s*\\[.{0,4000}?\"master_url\"\\s*:\\s*\"(http[^\"]+?\\.mp4[^\"]*?)\"")

    /** savefix P_NOTE_ID 同款。 */
    private val P_NOTE_ID: Pattern = Pattern.compile("\"(?:id|note_id)\"\\s*:\\s*\"([0-9a-f]{16,32})\"")

    /** origin_video_key（VideoInfo @kd.c("origin_video_key") 实证字段）。 */
    private val P_ORIGIN_KEY: Pattern = Pattern.compile("\"origin_video_key\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * X5 图集采集（外挂解析，2026-10-14 定案）：笔记 JSON 的 images[].url_default 即 XHS-Downloader
     * 的同源数据（imageList urlDefault），比在 app 内存里翻 adapter/实体图稳得多——混淆零依赖。
     * 逐 `"images":[` 区段取 noteId 与 url_default 列表。
     */
    private val P_NOTE_ID_PLAIN: Pattern = Pattern.compile("\"note_id\"\\s*:\\s*\"([0-9a-f]{16,32})\"")

    /** 图集采集命中计数（联调仪表，稳定后撤）。 */
    private val imgProbe = java.util.concurrent.atomic.AtomicInteger(0)

    /** noteId → 图集 url 列表（外挂缓存，供 X5 批量直下）。 */
    private val noteImages = ConcurrentHashMap<String, MutableList<String>>()

    /** 最近一篇带图的笔记（用户正在看的），X5 批量取这里。 */
    @Volatile private var lastImageNoteId: String? = null

    /** X5 取最近笔记图集（空列表 = 尚未采集到）。 */
    fun lastNoteImages(): List<String> =
        lastImageNoteId?.let { noteImages[it]?.toList() } ?: emptyList()

    /** 日志回调（install 期注入，模块 logger；采集探测仪表用）。 */
    @Volatile var logHook: ((Int, String, String) -> Unit)? = null

    /** note 响应 key 探测计数（联调期仪表，字段名定位后撤）。 */
    private val noteProbeCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 全流最高档扫描（XHS-Downloader get_video_link 语义）：MediaStream 实为 {h264[],h265[],h266[]}
     * 三编解码 × 多档（entities/video/MediaStream.java 实证），h264[0] 只是默认播放档——按
     * height+avg_bitrate 取全部 master_url 中的最大者才是流内最高档。
     */
    private val P_MASTER_ANY: Pattern = Pattern.compile("\"master_url\"\\s*:\\s*\"(http[^\"]+?\\.mp4[^\"]*?)\"")
    private val P_HEIGHT: Pattern = Pattern.compile("\"height\"\\s*:\\s*(\\d+)")
    private val P_AVG_BITRATE: Pattern = Pattern.compile("\"avg_bitrate\"\\s*:\\s*(\\d+)")

    /** backup_urls 数组首元素（video.py backupUrls[0] 优先语义；实体序在 master_url 前）。 */
    private val P_BACKUP_URLS: Pattern = Pattern.compile("\"backup_urls\"\\s*:\\s*\\[\\s*\"([^\"]+)\"")

    /** 流对象内字段与 master_url 的最大字符距（avg_bitrate 20 → master_url 66，jadx 实体序；窗口留余量）。 */
    private const val STREAM_FIELD_WINDOW = 1200

    private const val REGION_MASTER_WINDOW = 6000
    private const val REGION_NOTE_ID_BACK = 8000
    private const val MAX_BODY = 524288L
    private const val URL_MAP_LIMIT = 500
    private const val VIDEO_CDN = "sns-video-bd.xhscdn.com"

    /** noteId → 视频源三级链：origin key 重建直链 > 全流最高档 > h264[0] master_url。 */
    private class VideoSource(val originKey: String?, val bestStreamUrl: String?, val masterUrl: String?) {
        fun downloadUrl(): String? = originKey?.let { "https://$VIDEO_CDN/$it" } ?: bestStreamUrl ?: masterUrl
    }

    private val videoSources = ConcurrentHashMap<String, VideoSource>()

    /**
     * 宿主 ClassLoader（install 期写入）：okhttp3 只在宿主 classpath（本模块编译期也无 okhttp3），
     * ResponseBody 等类必须经宿主 loader 加载——用模块 loader 会全部 ClassNotFoundException，
     * 改写链整个失效（savefix appClassLoader 同语义）。
     */
    @Volatile private var hostLoader: ClassLoader? = null

    /** 防重标记（toString 唯一身份，见 buildInterceptorProxy 注释）。 */
    private const val INTERCEPTOR_TAG = "HonorHook-XhsVideoSave"

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        hostLoader = loader
        // 采集探测仪表回调（X5 联调期；稳定后撤）
        logHook = { prio, tag, msg -> module.log(prio, tag, msg) }
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf("com.xingin.xhs"))

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_OKHTTP_BUILDER,
                methodName = METHOD_BUILD,
                paramCount = 0,
                returnType = "okhttp3.OkHttpClient",
                id = "xhs:videoSave.okhttpBuilder.build",
                description = "X4 视频直链·OkHttpClient\$Builder.build() 注入 Interceptor 动态代理（采集 origin_video_key/master_url + 改写 /note/video/save 响应）",
            ),
        )
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result {
        val interceptor = runCatching { buildInterceptorProxy(loader, module) }.getOrNull()
            ?: run {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX okhttp3.Interceptor 不可用，视频直链未注入")
                return MountEngine.Result.FAILED
            }
        return engine.mount(task, module, loader) { chain ->
            // before：往 interceptors 列表塞代理后放行 build()。
            // ★ build() 非 void（返回 OkHttpClient）：intercept 返回值即方法最终返回值，
            //   必须回传 proceed 结果——写成「proceed(); null」会让 build() 返回 null，
            //   宿主全量 OkHttp 客户端 NPE 启动闪退（2026-10-14 真机实证，PROTECTIVE
            //   只挡异常不救 null 返回值）。void 方法才用「proceed(); null」惯例（U6 同款）。
            runCatching {
                val field = chain.thisObject.javaClass.getDeclaredField("interceptors")
                field.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                val interceptors = field.get(chain.thisObject) as? MutableList<Any>
                val injected = interceptors?.any {
                    runCatching { it.toString() }.getOrNull() == INTERCEPTOR_TAG
                } == true
                if (interceptors != null && !injected) {
                    interceptors.add(interceptor)
                }
            }.onFailure {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX interceptors 注入失败: ${it.message}")
            }
            chain.proceed()
        }
    }

    /** 动态代理 okhttp3.Interceptor；intercept(chain) 内 request/proceed 全反射（编译期无 okhttp3）。 */
    private fun buildInterceptorProxy(loader: ClassLoader, module: XposedModule): Any {
        val interceptorCls = loader.loadClass("okhttp3.Interceptor")
        return Proxy.newProxyInstance(loader, arrayOf(interceptorCls)) { _, method, args ->
            when (method.name) {
                "intercept" -> {
                    val chain = args?.firstOrNull()
                        ?: throw IllegalArgumentException("intercept chain null")
                    val request = chain.callMethod("request")
                    val response = chain.callMethod("proceed", request)
                    // 采集与改写任何异常都吞掉返回原响应（PROTECTIVE：绝不让视频直链炸宿主网络栈）
                    runCatching { rewriteVideoSave(request, response, module) }
                        .onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 处理异常: ${it.message}") }
                        .getOrNull() ?: response
                }
                "toString" -> INTERCEPTOR_TAG
                "hashCode" -> System.identityHashCode(this)
                "equals" -> this === args?.firstOrNull()
                else -> null
            }
        }
    }

    /**
     * 入口分流：/note/video/save 改写响应（命中返回新 Response，未命中/异常返回 null = 原响应原样）；
     * 其余 /api/sns/ 采集视频源（返回 null）。
     */
    private fun rewriteVideoSave(request: Any?, response: Any?, module: XposedModule): Any? {
        if (request == null || response == null) return null
        val url = request.callMethod("url")?.toString() ?: return null
        if (!url.contains("/api/sns/")) return null
        val body = peekBodyString(response) ?: return null
        if (url.contains("/note/video/save")) {
            return patchVideoSaveResponse(url, body, response, module)
        }
        harvestVideoSources(body)
        harvestNoteImages(body, url)
        return null
    }

    /**
     * X5 图集采集：逐 `"images":[` 区段，noteId 取区段前最近十六进制串（视频同款启发式），
     * 区段内收全部 url_default。**只认数组形 `"images":[`**——字符串形 `"images":"https…"`
     * 是评论区头像字段（2026-10-14 探测实证），跳过。
     * note 响应另配 key 探测（url 含 /note/ 时打 image 类 key 清单，定位图集真实字段名）。
     */
    /**
     * X5 图集采集（外挂解析，2026-10-14 定案）：笔记图集数据在 `/api/sns/v1/note/imagefeed`
     * 响应（探测实证 key=images_list；此前 "images" 命中的全是评论区头像字符串字段）。
     * 不赌字段名——直接收响应里全部 `sns-img`/`sns-webpic` host 的 URL（头像=sns-avatar 天然排除），
     * noteId 取响应内第一个 `"note_id"`（大图集分页多次响应累加去重）。
     */
    /**
     * X5 图集采集（外挂解析，2026-10-14 流量探测定案）：图集字段 = `images_list[]`，元素含
     * {fileid, height, width, url, original}，图床 host = sns-na-i6.xhscdn.com（url 带
     * imageView2 压缩参数；videofeed 段直接带 `original` 原图直链）。
     * 数据源端点：/note/detailfeed/preload（进笔记必发）+ /note/videofeed + homefeed。
     * 采集规则：original 直链优先；miss 收 url（[toOriginal] 落盘时剥参数）。
     * noteId 取响应内 `"note_id"`（homefeed 多笔记混包时沿用最近值——批量目标=当前浏览笔记）。
     */
    private val P_ORIGINAL_URL: Pattern = Pattern.compile("\"original\"\\s*:\\s*\"(https://sns-[^\"]+)\"")
    private val P_NOTE_IMG_URL: Pattern = Pattern.compile("\"url\"\\s*:\\s*\"(https://sns-(?:na|img|webpic)[^\"]+)\"")

    private fun harvestNoteImages(body: String, url: String) {
        // 触发端点收窄（2026-10-14 实测 62 张污染修正）：只认 imagefeed（逐图 feed，进图浏览必发）
        // 与 detailfeed（笔记详情/预载）。宽前缀 /note/ 会把 user/posted（他人主页笔记列表）等
        // 无关响应收进来；homefeed 直接整条信息流的图灌进单笔记缓存。
        if (!url.contains("/note/imagefeed") && !url.contains("/note/detailfeed")) return
        // noteId 三轨：body 内 "note_id"（comment/detail 型）→ URL query note_id（imagefeed 型，
        // 2026-10-14 诊断实证 body 无 note_id 而 query 有）→ 沿用最近值
        val noteId = P_NOTE_ID_PLAIN.matcher(body).takeIf { it.find() }?.group(1)
            ?: queryParam(url, "note_id")
            ?: lastImageNoteId
        val found = LinkedHashSet<String>()
        var m = P_ORIGINAL_URL.matcher(body)
        while (m.find()) found += m.group(1)
        if (found.isEmpty()) {
            m = P_NOTE_IMG_URL.matcher(body)
            while (m.find()) found += m.group(1)
        }
        if (noteId == null || found.isEmpty()) return
        if (noteImages.size > URL_MAP_LIMIT) noteImages.clear()
        val merged = (noteImages[noteId] ?: mutableListOf()) + found
        val distinct = merged.distinct()
        // 单笔记张数护栏：多图笔记上限 ~20 张（9.49.1 图集极限），超 30 判定污染整包丢弃
        if (distinct.size > 30) {
            if (logHook != null) {
                logHook!!(Log.WARN, MountEngine.TAG, "XhsHook·X4 图集护栏触发 noteId=$noteId ${distinct.size} 张疑似污染，丢弃")
            }
            return
        }
        noteImages[noteId] = distinct.toMutableList()
        lastImageNoteId = noteId
        if (logHook != null && imgProbe.incrementAndGet() <= 8) {
            logHook!!(Log.INFO, MountEngine.TAG, "XhsHook·X4 图集采集命中 noteId=$noteId 累计 ${noteImages[noteId]?.size} 张（来自 ${url.substringBefore('?').takeLast(40)}）")
        }
    }

    private fun peekBodyString(response: Any): String? = runCatching {
        val peek = response.callMethod("peekBody", MAX_BODY) ?: return null
        peek.callMethod("string") as? String
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * 采集（savefix fillVideoUrl 语义 + 两级升级）：逐 `video_info_v2` 区段——
     * origin_video_key 优先；全流最高档（bestStreamOf：区段内全部 master_url 按 height+avg_bitrate
     * 取最大，覆盖 h264/h265/h266 三编解码多档位）；h264[0]（P_MASTER_MP4，savefix 原版）殿后。
     * noteId 取区段前最近 `"(?:id|note_id)"` 十六进制串；map 上限 500 清空。
     */
    private fun harvestVideoSources(body: String) {
        var idx = body.indexOf("\"video_info_v2\"")
        while (idx >= 0) {
            val regionEnd = minOf(body.length, idx + REGION_MASTER_WINDOW)
            val originKey = P_ORIGIN_KEY.matcher(body).region(idx, regionEnd).let { if (it.find()) it.group(1) else null }
            val bestStream = bestStreamOf(body, idx, regionEnd)
            val masterUrl = P_MASTER_MP4.matcher(body).region(idx, regionEnd).let { if (it.find()) it.group(1) else null }
            if (originKey != null || bestStream != null || masterUrl != null) {
                val idMatcher = P_NOTE_ID.matcher(body).region(maxOf(0, idx - REGION_NOTE_ID_BACK), idx)
                var noteId: String? = null
                while (idMatcher.find()) noteId = idMatcher.group(1)
                if (noteId != null && !videoSources.containsKey(noteId)) {
                    if (videoSources.size > URL_MAP_LIMIT) videoSources.clear()
                    videoSources[noteId] = VideoSource(originKey, bestStream, masterUrl)
                }
            }
            idx = body.indexOf("\"video_info_v2\"", idx + "\"video_info_v2\"".length)
        }
    }

    /**
     * 区段内全部 master_url 逐个评分取最大：height 主序、avg_bitrate 次序（XHS-Downloader
     * get_video_link preference=resolution 同语义）。height/avg_bitrate 自 master_url 位置向前
     * [STREAM_FIELD_WINDOW] 窗口取最近值（实体字段序 avg_bitrate→height→master_url，jadx 实证），
     * 窗口 miss 按 0 计不弃候选（保底排序仍优于无档位信息）。
     * URL 选择照抄 video.py：`backupUrls[0] if backupUrls else masterUrl`——backup 是同流
     * 备用 CDN（同画质更稳），命中即替换。
     */
    private fun bestStreamOf(body: String, regionStart: Int, regionEnd: Int): String? {
        val m = P_MASTER_ANY.matcher(body).region(regionStart, regionEnd)
        var bestUrl: String? = null
        var bestScore = -1L
        while (m.find()) {
            val lookStart = maxOf(regionStart, m.start() - STREAM_FIELD_WINDOW)
            val height = P_HEIGHT.matcher(body).region(lookStart, m.start()).let { h ->
                var v = 0
                while (h.find()) v = h.group(1).toInt()
                v
            }
            val bitrate = P_AVG_BITRATE.matcher(body).region(lookStart, m.start()).let { h ->
                var v = 0
                while (h.find()) v = h.group(1).toInt()
                v
            }
            val score = height * 1_000_000L + bitrate
            if (score > bestScore) {
                bestScore = score
                // video.py 原文：backupUrls 非空取 [0]，否则 masterUrl（backup_urls 实体序在
                // master_url 前，同窗口向前找最近一个）
                val backup = P_BACKUP_URLS.matcher(body).region(lookStart, m.start()).let { b ->
                    var u: String? = null
                    while (b.find()) u = b.group(1)
                    u
                }
                bestUrl = backup ?: m.group(1)
            }
        }
        return bestUrl
    }

    /**
     * 改写 /note/video/save 响应（savefix patchVideoSaveResponse 语义照搬）：
     * disable:true→false、status 统一 2（无则插入）、注 download_url（origin key 优先）。
     * 命中改写 = 打判据行并返回新 Response；未命中返回 null（原响应不动）。
     */
    private fun patchVideoSaveResponse(url: String, body: String, response: Any, module: XposedModule): Any? {
        if (!body.contains("\"disable\"")) return null
        val noteId = queryParam(url, "note_id")
        val downloadUrl = noteId?.let { videoSources[it]?.downloadUrl() }
        var patched = body.replace(Regex("\"disable\"\\s*:\\s*true"), "\"disable\":false")
        patched = if (patched.contains("\"status\"")) {
            patched.replace(Regex("\"status\"\\s*:\\s*\\d+"), "\"status\":2")
        } else {
            patched.replace("\"disable\":false", "\"disable\":false,\"status\":2")
        }
        if (downloadUrl != null) {
            val m = Pattern.compile("\"note_id\"\\s*:\\s*\"([^\"]+)\"").matcher(patched)
            if (m.find()) {
                val ins = "\"note_id\":\"${m.group(1)}\",\"download_url\":\"$downloadUrl\""
                patched = patched.substring(0, m.start()) + ins + patched.substring(m.end())
            } else {
                patched = patched.replace("\"status\":2", "\"download_url\":\"$downloadUrl\",\"status\":2")
            }
        }
        val newResponse = rebuildResponse(response, patched) ?: return null
        module.log(
            Log.INFO, MountEngine.TAG,
            "$JUDGE_PREFIX 视频响应已改写 noteId=$noteId（disable:false+status:2${if (downloadUrl != null) "+download_url" else ""}）",
        )
        return newResponse
    }

    /** savefix rebuildResponse 同款：newBuilder().body(newBody).build()，失败返回 null 保原响应。 */
    private fun rebuildResponse(response: Any, text: String): Any? = runCatching {
        val oldBody = response.callMethod("body")
        val mediaType = oldBody?.callMethod("contentType")
        val newBody = createResponseBody(mediaType, text) ?: return null
        val builder = response.callMethod("newBuilder") ?: return null
        builder.callMethod("body", newBody)
        builder.callMethod("build")
    }.getOrNull()

    /** savefix createResponseBody 三轨容错：Kotlin Companion.create(String,MT) → create(MT,String) → create(String,MT)。类必须经宿主 loader 加载（模块 classpath 无 okhttp3）。 */
    private fun createResponseBody(mediaType: Any?, text: String): Any? {
        fun responseBodyClass(): Class<*>? =
            hostLoader?.let { runCatching { Class.forName("okhttp3.ResponseBody", true, it) }.getOrNull() }
        return runCatching {
            val companion = responseBodyClass()?.getDeclaredField("Companion")?.get(null)
            companion?.callMethod("create", text, mediaType)
        }.recoverCatching {
            responseBodyClass()?.callStaticMethod("create", mediaType, text)
        }.recoverCatching {
            responseBodyClass()?.callStaticMethod("create", text, mediaType)
        }.getOrNull()
    }

    /** savefix queryParam 同款。 */
    private fun queryParam(url: String, key: String): String? {
        var i = url.indexOf("?$key=")
        if (i < 0) i = url.indexOf("&$key=")
        if (i < 0) return null
        val s = i + key.length + 2
        val e = url.indexOf('&', s).let { if (it < 0) url.length else it }
        return url.substring(s, e)
    }

    // ---- 反射小工具（okhttp3 全程不进编译期 classpath）----

    private fun Any.callMethod(name: String, vararg args: Any?): Any? {
        val types = args.map { if (it is Long) Long::class.javaPrimitiveType else it?.javaClass ?: Any::class.java }
        // 精确声明类型优先（peekBody(long) 等基本类型签名），miss 退化为按名全扫
        val m = runCatching { javaClass.getMethod(name, *types.toTypedArray()) }.getOrNull()
            ?: javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
        m?.isAccessible = true
        return m?.invoke(this, *args)
    }

    private fun Class<*>.callStaticMethod(name: String, vararg args: Any?): Any? {
        val m = methods.firstOrNull { it.name == name && it.parameterCount == args.size } ?: return null
        m.isAccessible = true
        return m.invoke(null, *args)
    }
}
