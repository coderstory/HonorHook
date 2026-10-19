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
 *   - url 含 `/api/sns/` → peekBody(512KB) 采集：origin_video_key（VideoInfo @kd.c 实证，
 *     9.49.1 实体含此 JSON 字段）+ video_info_v2 区段逐流候选（每条流的 backup_urls 各元素与
 *     master_url 各成一候选，共享流元数据，见 [harvestStreams]）。noteId = 区段前最近
 *     `"(?:id|note_id)"` 十六进制串；map 上限 500 清空。
 *   - url 含 `/note/video/save` 且 body 含 `"disable"` → disable:false + status:2 +
 *     注 download_url（origin key 重建 `https://sns-video-bd.xhscdn.com/{key}`——
 *     XHS_Downloader_Android data/xhs/XhsNoteParser.kt 实证原始 key 最高画质）。ResponseBody
 *     三轨重建（Kotlin Companion → create(MediaType,String) → create(String,MediaType)）。
 *   - 选档语义 **2026-10-15 基准切换**（用户拍板以 reference/xhs/XHS_Downloader_Android 为唯一
 *     抄写基准，逐语义移植 watermarked 升序 → original 降序 → w×h 降序取首个，见
 *     [pickBestAndDownload]）；savefix `wh8.o1.a` 分支兜底**不搬**（9.49.1 名已漂，
 *     download_url 注入后客户端分支自愈）。
 *
 * 防重：同一 Builder 实例不重复注入（interceptors.contains 判代理身份）。
 * 失败分级：FAILED（okhttp 缺失说明目标结构大改，重试无意义）。
 */
object XhsVideoSave {

    private const val JUDGE_PREFIX = "XhsHook·X4"

    private val engine = MountEngine("XhsHook·X4", "com.xingin.xhs")

    private const val CLS_OKHTTP_BUILDER = "okhttp3.OkHttpClient\$Builder"
    private const val METHOD_BUILD = "build"

    /** savefix P_NOTE_ID 同款。 */
    private val P_NOTE_ID: Pattern = Pattern.compile("\"(?:id|note_id)\"\\s*:\\s*\"([0-9a-f]{16,32})\"")

    /** origin_video_key（VideoInfo @kd.c("origin_video_key") 实证字段）。 */
    private val P_ORIGIN_KEY: Pattern = Pattern.compile("\"origin_video_key\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * X5 图集采集（外挂解析，2026-10-14 定案）：笔记 JSON 的 images[].url_default 即
     * XHS_Downloader_Android 的同源数据（imageList urlDefault），比在 app 内存里翻 adapter/实体图稳得多——混淆零依赖。
     * 逐 `"images":[` 区段取 noteId 与 url_default 列表。
     */
    private val P_NOTE_ID_PLAIN: Pattern = Pattern.compile("\"note_id\"\\s*:\\s*\"([0-9a-f]{16,32})\"")

    /** noteId → 图集 url 列表（外挂缓存，供 X5 批量直下）。 */
    private val noteImages = ConcurrentHashMap<String, MutableList<String>>()

    /** noteId → 载荷声明图集张数（成功采集时 max 累积；X5 防「漏采剩 1 张被当单图直存」用，2026-10-19）。 */
    private val declaredImageCounts = ConcurrentHashMap<String, Int>()

    /** 最近一篇带图的笔记（用户正在看的），X5 批量取这里。 */
    @Volatile private var lastImageNoteId: String? = null

    /** 当前浏览笔记（harvestNoteImages 响应 body/query 解析出的 noteId；与图集是否成功入库无关）。
     *  2026-10-15 审计修复：护栏丢弃（>30 张判污染整包 return）不更新 [lastImageNoteId]，缓存停留
     *  上一篇 ⇒ [lastNoteImages] 会把上一篇图集交给 X5 全接管，替用户下载错笔记的图且原生保存消失。
     *  本字段只认 body/query 可归属的 noteId，「沿用最近值」的响应不可归属、不更新。 */
    @Volatile private var currentNoteId: String? = null

    /** X5 取当前笔记图集（空 = 未采到，或缓存已过期非当前浏览笔记——见 [currentNoteId]；
     *  2026-10-19 起 save-hostile 笔记采集经张数一致性校验失败不入库，也走这里空回落）。 */
    fun lastNoteImages(): List<String> {
        val cur = currentNoteId ?: return emptyList()
        if (cur != lastImageNoteId) return emptyList()
        return noteImages[cur]?.toList() ?: emptyList()
    }

    /** X5 用：当前笔记载荷声明的图集张数（与 [lastNoteImages] 同 freshness 门；null = 载荷未声明）。
     *  防「漏采剩 1 张被当单图直存」的判定输入（findings-x5b.md §2.3，2026-10-19）。 */
    fun lastNoteDeclaredImageCount(): Int? {
        val cur = currentNoteId ?: return null
        if (cur != lastImageNoteId) return null
        return declaredImageCounts[cur]
    }

    /** 日志回调（install 期注入，模块 logger；采集探测仪表用）。 */
    @Volatile var logHook: ((Int, String, String) -> Unit)? = null

    /**
     * video_info_v2 区间内全部 master_url（覆盖 MediaStream{h264[],h265[],h266[]} 三编解码多档位，
     * entities/video/MediaStream.java 实证）。2026-10-15 起仅作定位锚：每个命中经花括号回溯圈定
     * 所属 VideoStream 对象逐流配对采集（见 [harvestStreams]），不再直接当候选 URL 用。
     * mp4 限定保留：master_url 实测恒 mp4，过滤嵌套子对象里同名 URL 的噪声。
     */
    private val P_MASTER_ANY: Pattern = Pattern.compile("\"master_url\"\\s*:\\s*\"(http[^\"]+?\\.mp4[^\"]*?)\"")

    /** backup_urls 数组开括号（取证实证 backup 紧随 master_url 之后；元素逐个提取见 [harvestStreams]）。 */
    private val P_BACKUP_ARRAY: Pattern = Pattern.compile("\"backup_urls\"\\s*:\\s*\\[")

    /** 带引号字符串（backup_urls 数组元素提取用）。 */
    private val P_QUOTED: Pattern = Pattern.compile("\"([^\"]+)\"")

    /** 流对象字段（VideoStream.java 实证；均取对象起点到 master_url 之间的**最后一个**命中）。 */
    private val P_HEIGHT: Pattern = Pattern.compile("\"height\"\\s*:\\s*(\\d+)")
    private val P_WIDTH: Pattern = Pattern.compile("\"width\"\\s*:\\s*(\\d+)")
    private val P_BITRATE: Pattern = Pattern.compile("\"video_bitrate\"\\s*:\\s*(\\d+)")
    private val P_SIZE: Pattern = Pattern.compile("\"size\"\\s*:\\s*(\\d+)")
    private val P_STREAM_TYPE: Pattern = Pattern.compile("\"stream_type\"\\s*:\\s*(\\d+)")
    private val P_STREAM_DESC: Pattern = Pattern.compile("\"stream_desc\"\\s*:\\s*\"([^\"]*)\"")

    /** 所属编解码数组键（h264/h265/h266；codec 不参与本模块排序，采集失败无害）。 */
    private val P_CODEC_KEY: Pattern = Pattern.compile("\"(h26[456])\"")

    private const val REGION_NOTE_ID_BACK = 8000
    private const val MAX_BODY = 524288L
    private const val URL_MAP_LIMIT = 500
    private const val VIDEO_CDN = "sns-video-bd.xhscdn.com"

    /**
     * 单条视频候选 URL（逐语义移植 XHS_Downloader_Android core/model/ResolvedNote.kt MediaCandidate +
     * data/xhs/XhsNoteParser.kt:172-204）：每条流（video_info_v2.media.stream.{h264,h265,h266}[] 元素）
     * 的 backup_urls 各元素与 master_url **各成一候选**，共享所属流的 width/height/bitrate/size/codec。
     * 原片候选（originVideoKey 重建）：width/height/bitrate/size = 0、watermarked = false、original = true
     * （id 最高优先，排序见 [pickBestAndDownload]）。
     */
    private class VideoCandidate(
        val url: String,
        val width: Int,
        val height: Int,
        val bitrate: Long,
        val size: Long,
        val codec: String?,
        val watermarked: Boolean,
        val original: Boolean = false,
    )

    /**
     * noteId → 视频源：originKey（origin_video_key 原始档；V-1 后仅此一来源，且实测恒 null——
     * E6/E7：该字段不在 app 网络层）+ candidates（video_info_v2 区段逐流逐 URL 采集，2026-10-15
     * 基准切换，见 [harvestStreams]）。
     */
    private class VideoSource(val originKey: String?, val candidates: List<VideoCandidate>) {
        /**
         * 换链专用（2026-10-14 止损 → 降级为死分支）：**只信 origin_video_key**——bestStream 的
         * height/bitrate lookback 曾全场 miss 导致首流（最低档 _258）胜出，换链反而降质。
         * V-1 后唯一来源 P_ORIGIN_KEY 实测恒 null ⇒ 本方法恒 null ⇒ 换链分支自然死掉；
         * 保留待「进程内 web 会话」路线复活（本轮不做，见 patchVideoSaveResponse 注释 E1/E7）。
         */
        fun swapUrl(): String? = originKey?.let { if (it.startsWith("http")) it else "https://$VIDEO_CDN/${it.trimStart('/')}" }
    }

    private val videoSources = ConcurrentHashMap<String, VideoSource>()

    /**
     * noteId → 分享页线索（share_info.link 或同级 xsec_token）。真原始档不在 app API 梯子里（E6/E7），
     * 只在**移动版网页** `www.xiaohongshu.com/discovery/item/{id}` 的 `__INITIAL_STATE__` 里（camelCase
     * `originVideoKey`）；取该页须带 fresh xsec_token——桌面 UA 抓同一页没有，且 `xsec_source=pc_feed`
     * 会 302 到登录页（须 `app_share`）。link 是 app 侧已拼好的整条 URL（含 token），优先原样用。
     */
    private class NoteShare(val link: String?, val token: String?)

    private val noteShares = ConcurrentHashMap<String, NoteShare>()

    /**
     * share_info.link（例：`https://www.xiaohongshu.com/discovery/item/<id>?…&xsec_source=app_share&xsec_token=…`）。
     * app JSON 标准转义（非 web 的 `\/`），但仍走 [unescapeJson] 兜底。
     */
    private val P_SHARE_LINK: Pattern = Pattern.compile("\"link\"\\s*:\\s*\"([^\"]+)\"")

    /** 从 share link 路径取 noteId（`.../discovery/item/<16-32hex>`）。 */
    private val P_SHARE_LINK_ID: Pattern = Pattern.compile("discovery/item/([0-9a-f]{16,32})")

    /** 同级 xsec_token（与 `"id"` 同级；noteId 就近取，见 harvestNoteShare）。 */
    private val P_XSEC_TOKEN: Pattern = Pattern.compile("\"xsec_token\"\\s*:\\s*\"([^\"]+)\"")

    /**
     * 移动版网页 `__INITIAL_STATE__` 里的原始档 key（camelCase；XHS_Downloader_Android
     * data/xhs/XhsNoteParser.kt:175 `video.consumer.originVideoKey` / `video.originVideoKey` 同源）。
     * 值形如 `spectrum/1040g0jg…`，
     * `/` 在 web JSON 里被转义 ⇒ 必须 [unescapeJson] 还原再拼 URL，否则 404。
     */
    private val P_ORIGIN_VIDEO_KEY_CAMEL: Pattern = Pattern.compile("\"originVideoKey\"\\s*:\\s*\"([^\"]+)\"")

    /** 解析原片用的移动 UA——**结尾 `xiaohongshu` 后缀 + Chrome Mobile 缺一不可**（桌面 UA 无 originVideoKey）。 */
    private const val XHS_WEB_UA =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36 xiaohongshu"

    /** 网页读取上限 1MB（`__INITIAL_STATE__` 远小于此；护栏防异常页拉爆内存）。 */
    private const val MAX_PAGE_BYTES = 1 shl 20

    /** 解析网页连接/读取超时（网页比 CDN 慢，给宽一点）。 */
    private const val RESOLVE_TIMEOUT_MS = 15_000

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
        ensureActivityTracker()
        // A2：进程被杀留下的 .pending- 半成品（IS_PENDING 态）后台清一次（每进程一次；年龄门槛见函数注释）
        cleanupPendingOrphans(module)
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
            val patched = patchVideoSaveResponse(url, body, response, module)
            // 外挂直下（2026-10-14 用户定案）：客户端落盘的是播放缓存低码流、不消费响应 url——
            // 模块自己下高清（origin_video_key 优先，回落响应内 download_url），完成后删客户端低画质文件
            scheduleHdVideoDownload(url, body, module)
            return patched
        }
        harvestVideoSources(body)
        harvestNoteShare(body)
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

    /** images_list 数组开括号（图集区段锚，2026-10-19 采集收窄用，findings-x5b.md）。 */
    private val P_IMAGES_LIST: Pattern = Pattern.compile("\"images_list\"\\s*:\\s*\\[")

    /** note 级声明张数（detail 型载荷；字段名属探测项，miss 退元素数口径，见 harvestNoteImages）。 */
    private val P_IMAGE_COUNT: Pattern = Pattern.compile("\"image_count\"\\s*:\\s*(\\d+)")

    private fun harvestNoteImages(body: String, url: String) {
        // 触发端点收窄（2026-10-14 实测 62 张污染修正）：只认 imagefeed（逐图 feed，进图浏览必发）
        // 与 detailfeed（笔记详情/预载）。宽前缀 /note/ 会把 user/posted（他人主页笔记列表）等
        // 无关响应收进来；homefeed 直接整条信息流的图灌进单笔记缓存。
        if (!url.contains("/note/imagefeed") && !url.contains("/note/detailfeed")) return
        // noteId 三轨：body 内 "note_id"（comment/detail 型）→ URL query note_id（imagefeed 型，
        // 2026-10-14 诊断实证 body 无 note_id 而 query 有）→ 沿用最近值
        val fromBody = P_NOTE_ID_PLAIN.matcher(body).takeIf { it.find() }?.group(1)
        val fromQuery = if (fromBody == null) queryParam(url, "note_id") else null
        val noteId = fromBody ?: fromQuery ?: lastImageNoteId
        val noteIdFrom = if (fromBody != null) "body" else if (fromQuery != null) "query" else "沿用"
        // 归属更新必须在采集失败早退之前：图集采没采到不影响「用户在浏览本篇」这个事实
        if (fromBody != null || fromQuery != null) currentNoteId = noteId
        // 仪表①（findings-x5b.md §1：失败静默是排查最大成本，采集必须有痕）：端点 + noteId 来源
        logHook?.invoke(Log.INFO, MountEngine.TAG,
            "XhsHook·X4 图集采集进入 ${url.take(80)} noteId=$noteId 来源=$noteIdFrom")
        // 采集收窄到 images_list 区段（2026-10-19，findings-x5b.md §1·A）：save-hostile 笔记
        // （服务端 FunctionSwitch(image_download)=false，真机实证）载荷缺本图集直链，旧「全 body
        // 兜底扫 P_NOTE_IMG_URL」会把 preload 封面/关联笔记的杂图扫进来当本图集（落错 1 张）。
        // 区段须归属本次 noteId（前最近 "note_id"；lookback miss 归本 noteId——imagefeed 型 body 可无 note_id）。
        var elements = 0
        val found = LinkedHashSet<String>()
        val r = P_IMAGES_LIST.matcher(body)
        while (r.find()) {
            val regionNoteId = nearestNoteIdBefore(body, r.start()) ?: noteId
            if (noteId != null && regionNoteId != noteId) continue
            val arrEnd = arrayEnd(body, r.end())
            if (arrEnd < 0) continue
            elements += harvestImagesListRegion(body, r.end(), arrEnd, found)
        }
        // 失败有痕：无可归属元素（视频笔记正常无图 / hostile 剥离）或 noteId 缺失 → 不入库，
        // X5 走既有空回落 proceed（宿主原生保存；hostile 笔记宿主也存不了，属诚实失败）
        if (noteId == null || elements == 0) {
            logHook?.invoke(Log.WARN, MountEngine.TAG,
                "XhsHook·X4 图集采集失败 noteId=$noteId：无可归属 images_list 元素（视频笔记正常无图 / save-hostile 剥离）")
            return
        }
        // 张数一致性校验（2026-10-19 定案，findings-x5b.md §2.2）：元素数即载荷声明张数，采到的
        // URL 条数不符 ⇒ 服务端剥离了部分/全部直链（残缺图集）——判采集失败不入库（返回空语义 =
        // 调用方回落），宁回落宿主原生保存也别把残缺/杂图当图集下错图。
        if (found.size != elements) {
            logHook?.invoke(Log.WARN, MountEngine.TAG,
                "XhsHook·X4 图集采集失败 noteId=$noteId：一致性不符 采到 ${found.size} / 声明 $elements（直链被剥离），不入库")
            return
        }
        if (noteImages.size > URL_MAP_LIMIT) { noteImages.clear(); declaredImageCounts.clear() }
        val merged = (noteImages[noteId] ?: mutableListOf()) + found
        // 按 token 去重（2026-10-15 真机：单图笔记 original 直链与带参 url 并存 = 两串一图，整串
        // distinct 去不掉 ⇒ 弹「2 张」下 2 张一模一样）。toBare 不适用（null）回退整串做键；
        // LinkedHashSet 保序，首现优先（original 直链序不变）。
        val distinct = merged.distinctBy { XhsImageUrl.toBare(it) ?: it }
        // 单笔记张数护栏：多图笔记上限 ~20 张（9.49.1 图集极限），超 30 判定污染整包丢弃
        if (distinct.size > 30) {
            logHook?.invoke(Log.WARN, MountEngine.TAG, "XhsHook·X4 图集护栏触发 noteId=$noteId ${distinct.size} 张疑似污染，丢弃")
            return
        }
        noteImages[noteId] = distinct.toMutableList()
        lastImageNoteId = noteId
        // 声明张数入库（note 级 image_count 优先、miss 用元素数；max 累积防分页小载荷拉低）：
        // X5 单图分支防「漏采剩 1 张被当单图直存」的判定输入（findings-x5b.md §2.3）。
        val declared = maxOf(P_IMAGE_COUNT.matcher(body).takeIf { it.find() }?.group(1)?.toIntOrNull() ?: 0, elements)
        if (declared > 0) declaredImageCounts[noteId] = maxOf(declaredImageCounts[noteId] ?: 0, declared)
        // 仪表②③：采到条数 + 首末 URL 截断 + 一致性校验结果
        logHook?.invoke(Log.INFO, MountEngine.TAG,
            "XhsHook·X4 图集采集成功 noteId=$noteId 采到 ${found.size} / 声明 $elements " +
                "首=${found.first().take(60)} 末=${found.last().take(60)}")
    }

    /** [pos) 前最近的 `"note_id"`（images_list 区段归属判别用；无则 null）。 */
    private fun nearestNoteIdBefore(body: String, pos: Int): String? {
        val m = P_NOTE_ID_PLAIN.matcher(body).region(maxOf(0, pos - REGION_NOTE_ID_BACK), pos)
        var id: String? = null
        while (m.find()) id = m.group(1)
        return id
    }

    /** 数组开括号后一位 [from) 起配对找闭括号下标（失配 -1）。字符串内裸括号未剥转义——
     *  URL/字段值实测不含（与 harvestStreams 花括号回溯同前提）。 */
    private fun arrayEnd(body: String, from: Int): Int {
        var depth = 1
        for (j in from until body.length) {
            when (body[j]) {
                '[' -> depth++
                ']' -> if (--depth == 0) return j
            }
        }
        return -1
    }

    /** images_list 区段 [from,to) 内逐元素（花括号配对圈对象；元素扁平、嵌套子对象配对同样正确），
     *  返回元素数。每元素恰收一条 URL：original 直链优先、miss 收 url；两者皆无计元素不产 URL
     *  （交调用方一致性校验判残缺）。 */
    private fun harvestImagesListRegion(body: String, from: Int, to: Int, found: LinkedHashSet<String>): Int {
        var count = 0
        var i = from
        while (i < to) {
            if (body[i] != '{') { i++; continue }
            var depth = 0
            var end = -1
            for (j in i until to) {
                when (body[j]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) { end = j; break }
                }
            }
            if (end < 0) break
            count++
            val element = body.substring(i, end + 1)
            val picked = P_ORIGINAL_URL.matcher(element).takeIf { it.find() }?.group(1)
                ?: P_NOTE_IMG_URL.matcher(element).takeIf { it.find() }?.group(1)
            if (picked != null) found += picked
            i = end + 1
        }
        return count
    }

    private fun peekBodyString(response: Any): String? = runCatching {
        val peek = response.callMethod("peekBody", MAX_BODY) ?: return null
        peek.callMethod("string") as? String
    }.getOrNull()?.takeIf { it.isNotEmpty() }

    /**
     * 采集：逐 `video_info_v2` 区段——origin_video_key（V-1 后唯一 originKey 来源，实测恒 null，E6）
     * + candidates（[harvestStreams]：区段内逐流花括号回溯，每条 backup_urls 元素与 master_url 各一候选）。
     * noteId 取区段前最近 `"(?:id|note_id)"` 十六进制串；map 上限 500 清空；首访不覆盖（!containsKey）。
     * V-1 撤伪 originKey 采集（旧三版 `video:{` 窗口扫 id 产垃圾：24hex 裸键直链 404 / 窗口内其他对象
     * 的 url，E7），也撤 web 页取原始档路线（E8，服务端 sec 墙）。
     *
     * 2026-10-15 基准切换（用户拍板以 XHS_Downloader_Android 为唯一抄写基准）：候选集/元数据/选档
     * 全部逐语义移植 XhsNoteParser.kt:172-204 + NoteOutput.kt:47-56（见 [harvestStreams] /
     * [pickBestAndDownload]）；旧「probeSize 实测字节选最大」作废——跨编码字节不可比（真机实证
     * H264 _258 37808KB 胜 H265 高分档 ~10MB），且原片直链被字节比较挤掉。旧 E2/E5「窗口打分不可靠」
     * 的教训指**固定字符窗口**，现用花括号配对 + 精确区段（下一 video_info_v2 为界），非窗口法复活。
     */
    private fun harvestVideoSources(body: String) {
        var idx = body.indexOf("\"video_info_v2\"")
        while (idx >= 0) {
            // 区段上界 = 同 body 内下一个 "video_info_v2" 起点（无则 body.length）。旧固定窗口
            // REGION_MASTER_WINDOW=6000 会把本 note 的 video_info_v2 区段截断——真机实证 4K `_532`
            // 档位落在 6000 字符之外，旧候选采集只采到 5 档、漏掉 4K。video_info_v2 每 note 恰一个，
            // 以下一个为界即精确圈住本 note 的媒体块，且不会串到下一个 note（换窗为界同时也解除
            // 窗口大小这一隐式耦合，新增档位/元数据不再挤掉尾部流）。
            val nextIdx = body.indexOf("\"video_info_v2\"", idx + 1)
            val regionEnd = if (nextIdx < 0) body.length else nextIdx
            val originKey = P_ORIGIN_KEY.matcher(body).region(idx, regionEnd).let { if (it.find()) it.group(1) else null }
            val candidates = harvestStreams(body, idx, regionEnd)
            if (originKey != null || candidates.isNotEmpty()) {
                val idMatcher = P_NOTE_ID.matcher(body).region(maxOf(0, idx - REGION_NOTE_ID_BACK), idx)
                var noteId: String? = null
                while (idMatcher.find()) noteId = idMatcher.group(1)
                if (noteId != null && !videoSources.containsKey(noteId)) {
                    if (videoSources.size > URL_MAP_LIMIT) videoSources.clear()
                    videoSources[noteId] = VideoSource(originKey, candidates)
                }
            }
            idx = body.indexOf("\"video_info_v2\"", idx + "\"video_info_v2\"".length)
        }
        // origin_video_key 全响应扫（V-1 后唯一 originKey 来源；实测恒 null——E6，保留待 web 会话路线复活）
        var oidx = body.indexOf("\"origin_video_key\"")
        while (oidx >= 0) {
            val om = P_ORIGIN_KEY.matcher(body).region(oidx, minOf(body.length, oidx + 400))
            if (om.find()) {
                val key = om.group(1)
                val idMatcher = P_NOTE_ID.matcher(body).region(maxOf(0, oidx - REGION_NOTE_ID_BACK), oidx)
                var nid: String? = null
                while (idMatcher.find()) nid = idMatcher.group(1)
                if (nid != null) {
                    val old = videoSources[nid]
                    if (old == null || old.originKey == null) {
                        videoSources[nid] = VideoSource(key, old?.candidates ?: emptyList())
                    }
                }
            }
            oidx = body.indexOf("\"origin_video_key\"", oidx + 18)
        }
    }

    /** 带水印流标记（XhsNoteParser.kt 语义）：stream_type ∈ 此集或 stream_desc = WEB_LIVEPHOTO_19（忽略大小写）。 */
    private val WATERMARK_STREAM_TYPES = setOf(259, 309)
    private const val WEB_LIVEPHOTO_DESC = "WEB_LIVEPHOTO_19"

    /**
     * video_info_v2 区间内逐流采集（2026-10-15 基准切换，花括号回溯法；逐语义移植
     * XHS_Downloader_Android data/xhs/XhsNoteParser.kt:172-204）：每个 `"master_url"`（[P_MASTER_ANY]
     * 定位）向左花括号回溯圈定所属 VideoStream JSON 对象——`}` 深度 +1、`{` 深度 -1，深度变 -1 的
     * `{` 即对象起点（VideoStream 对象扁平，opaque1/vqa 子对象在其后，不影响向左回溯）。
     * 流元数据 = 对象起点到 master_url 之间各字段**最后一个**命中（取证实证 width/height 等在对象
     * 前半段）；取不到 height 的流整条丢弃（宁缺勿错配）。watermarked = stream_type ∈ {259,309} ||
     * stream_desc == "WEB_LIVEPHOTO_19"（忽略大小写）。codec = regionStart→对象起点向前最近的
     * "h264"/"h265"/"h266" 数组键；取不到仅置 null——codec 不参与本模块排序，丢失不影响结果。
     * 每条流展开为**独立候选**：backup_urls 数组全部元素（master_url 之后、对象 `}` 之内第一个数组，
     * 紧随 master_url 取证实证）逐个 + master_url 收尾；同流候选共享元数据，列表序即下载器序
     * （选中流取 backup[0] = 其首个候选）。
     */
    private fun harvestStreams(body: String, regionStart: Int, regionEnd: Int): List<VideoCandidate> {
        val out = ArrayList<VideoCandidate>()
        val m = P_MASTER_ANY.matcher(body).region(regionStart, regionEnd)
        while (m.find()) {
            val masterUrl = m.group(1) ?: continue
            // 向左花括号回溯：深度变 -1 的 `{` = 所属对象起点
            var depth = 0
            var objStart = -1
            var i = m.start() - 1
            while (i >= regionStart) {
                when (body[i]) {
                    '}' -> depth++
                    '{' -> {
                        depth--
                        if (depth < 0) { objStart = i; break }
                    }
                }
                i--
            }
            if (objStart < 0) continue
            // 元数据：对象起点到 master_url 之间各字段取最后一个命中
            fun lastNum(p: Pattern): Int {
                val mm = p.matcher(body).region(objStart, m.start())
                var v = -1
                while (mm.find()) v = mm.group(1)?.toIntOrNull() ?: -1
                return v
            }
            val height = lastNum(P_HEIGHT)
            if (height <= 0) continue
            val width = lastNum(P_WIDTH).coerceAtLeast(0)
            val bitrate = lastNum(P_BITRATE).toLong().coerceAtLeast(0)
            val size = lastNum(P_SIZE).toLong().coerceAtLeast(0)
            val streamType = lastNum(P_STREAM_TYPE)
            val dm = P_STREAM_DESC.matcher(body).region(objStart, m.start())
            var streamDesc = ""
            while (dm.find()) streamDesc = dm.group(1) ?: ""
            val watermarked = streamType in WATERMARK_STREAM_TYPES ||
                streamDesc.equals(WEB_LIVEPHOTO_DESC, ignoreCase = true)
            // codec：regionStart→对象起点向前最近的编解码数组键（最后一次命中即最近）
            var codec: String? = null
            val cm = P_CODEC_KEY.matcher(body).region(regionStart, objStart)
            while (cm.find()) codec = cm.group(1)
            // 对象 `}` 位置：master_url 起向前数括号（起点 depth=1，子对象 opaque1/vqa 嵌套不影响计数）
            var d2 = 1
            var j = m.end()
            while (j < regionEnd) {
                when (body[j]) {
                    '{' -> d2++
                    '}' -> { d2--; if (d2 == 0) break }
                }
                j++
            }
            // backup_urls 数组全部元素（master_url 之后、对象 } 之内的第一个数组）；取不到 = 空
            val urls = ArrayList<String>()
            val bam = P_BACKUP_ARRAY.matcher(body).region(m.end(), minOf(j, regionEnd))
            if (bam.find()) {
                val arrStart = bam.end()
                val hardEnd = minOf(j, regionEnd)
                val arrEnd = body.indexOf(']', arrStart).let { if (it < 0) hardEnd else minOf(it, hardEnd) }
                if (arrEnd > arrStart) {
                    val em = P_QUOTED.matcher(body.substring(arrStart, arrEnd))
                    while (em.find()) em.group(1)?.let { urls += it }
                }
            }
            urls += masterUrl
            for (u in urls) out += VideoCandidate(u, width, height, bitrate, size, codec, watermarked)
        }
        return out
    }

    /**
     * 采集分享页线索（与 [harvestVideoSources] 同源同响应）：`share_info.link` 优先——app 已把
     * noteId + fresh xsec_token 拼成整条 URL（`xsec_source=app_share`），原样用最省事；link miss
     * 时收同级 `xsec_token`（noteId 就近 lookback 同 harvestVideoSources）。二者皆无则不入表。
     */
    private fun harvestNoteShare(body: String) {
        // share_info.link 优先（值须含 xsec_token 才算分享链——普通 "link" 字段多，先过滤）
        var lm = P_SHARE_LINK.matcher(body)
        while (lm.find()) {
            val value = unescapeJson(lm.group(1) ?: continue)
            if (!value.contains("xiaohongshu.com") || !value.contains("xsec_token=")) continue
            val idm = P_SHARE_LINK_ID.matcher(value)
            if (!idm.find()) continue
            val nid = idm.group(1) ?: continue
            if (noteShares.size > URL_MAP_LIMIT) noteShares.clear()
            val old = noteShares[nid]
            noteShares[nid] = NoteShare(value, old?.token)
        }
        // 同级 xsec_token 兜底（link 缺失的响应形态）
        var tm = P_XSEC_TOKEN.matcher(body)
        while (tm.find()) {
            val tok = unescapeJson(tm.group(1) ?: continue)
            val idMatcher = P_NOTE_ID.matcher(body).region(maxOf(0, tm.start() - REGION_NOTE_ID_BACK), tm.start())
            var nid: String? = null
            while (idMatcher.find()) nid = idMatcher.group(1)
            if (nid == null) continue
            val old = noteShares[nid]
            if (old == null || (old.link.isNullOrEmpty() && old.token == null)) {
                if (noteShares.size > URL_MAP_LIMIT) noteShares.clear()
                noteShares[nid] = NoteShare(null, tok)
            }
        }
    }

    /**
     * 解析真原始档 URL（任务1.2）：GET 移动版网页 → 正则取 `originVideoKey` → 拼 CDN。
     * **任何失败（无 token / 非 200 / 取不到 key / 异常）恒返回 null 并打一行日志**，绝不影响既有梯子链。
     * 只在后台线程调用（pickBestAndDownload 的下载线程），不阻塞 okhttp 拦截线程。
     */
    private fun resolveOriginVideoUrl(noteId: String, share: NoteShare, module: XposedModule): String? {
        val pageUrl = (share.link?.takeIf { it.contains("xsec_token=") })
            ?: share.token?.let { "https://www.xiaohongshu.com/discovery/item/$noteId?xsec_source=app_share&xsec_token=$it" }
            ?: run {
                module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 原片解析跳过 noteId=$noteId：无 xsec_token/share link")
                return null
            }
        val html = runCatching { fetchNotePageHtml(pageUrl) }.getOrNull()
        if (html == null) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 原片解析失败 noteId=$noteId：网页不可取（$pageUrl）")
            return null
        }
        val m = P_ORIGIN_VIDEO_KEY_CAMEL.matcher(html)
        if (!m.find()) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 原片解析失败 noteId=$noteId：网页无 originVideoKey（token 过期/非视频页）")
            return null
        }
        val key = unescapeJson(m.group(1) ?: "").trim()
        if (key.isEmpty()) return null
        val url = if (key.startsWith("http")) key else "https://$VIDEO_CDN/${key.trimStart('/')}"
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 原片解析成功 noteId=$noteId ${url.take(120)}")
        return url
    }

    /**
     * GET 移动版网页（手机 UA + Accept），手动跟随 302（≤5 跳）；非 200 返 null，读取上限
     * [MAX_PAGE_BYTES]（[readCapped]）。UA / `xsec_source=app_share` 是取到 originVideoKey 的前提（实证）。
     */
    private fun fetchNotePageHtml(pageUrl: String): String? {
        var u = pageUrl
        var redirects = 0
        while (true) {
            val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = RESOLVE_TIMEOUT_MS
            conn.readTimeout = RESOLVE_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", XHS_WEB_UA)
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        if (++redirects > 5) return null
                        u = conn.getHeaderField("Location") ?: return null
                    }
                    200 -> return conn.inputStream.use { readCapped(it, MAX_PAGE_BYTES) }
                    else -> return null
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 读流上限 cap 字节为 UTF-8 字符串（防异常页拉爆内存）。 */
    private fun readCapped(input: java.io.InputStream, cap: Int): String {
        val buf = ByteArray(64 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (total < cap) {
            val n = input.read(buf)
            if (n < 0) break
            val w = minOf(n, cap - total)
            out.write(buf, 0, w)
            total += w
        }
        return out.toString("UTF-8")
    }

    /** 还原 JSON 字符串转义：`\/`→`/`、`\\`、`\"`、`\uXXXX`（web `__INITIAL_STATE__` 的 key/链接必有 `\/`）。 */
    private fun unescapeJson(s: String): String {
        if (s.indexOf('\\') < 0) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    '/' -> { sb.append('/'); i += 2 }
                    '\\' -> { sb.append('\\'); i += 2 }
                    '"' -> { sb.append('"'); i += 2 }
                    'u', 'U' -> if (i + 6 <= s.length) {
                        val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                        if (code != null) { sb.append(code.toChar()); i += 6 } else { sb.append(c); i++ }
                    } else { sb.append(c); i++ }
                    else -> { sb.append(c); i++ }
                }
            } else {
                sb.append(c); i++
            }
        }
        return sb.toString()
    }

    /**
     * 改写 /note/video/save 响应：disable:true→false、status 统一 2（无则插入）。命中返回新 Response，
     * 未命中/异常返回 null（原响应不动）。
     *
     * **换链（download_url 替换）自 2026-10-14 起为死分支**（V-10）：实测 E1——换链日志出现（换上
     * `_258`）但客户端仍落服务器原档 sizeof，即客户端根本不消费响应内 download_url；且唯一可信源
     * origin_video_key 经 E6/E7 实证不在 app 网络层（伪采集仅产垃圾，裸 key 直链 404 = E4）⇒
     * swapUrl() 恒 null。分支与 VideoSource.originKey 保留待「app 进程内 WebView 会话」路线复活（本轮不做）。
     * 高清交付已改由外挂直下（scheduleHdVideoDownload）承担，别再指望「换链能救」。
     */
    private fun patchVideoSaveResponse(url: String, body: String, response: Any, module: XposedModule): Any? {
        val hasDisableBlock = body.contains("\"disable\"")
        if (!hasDisableBlock && !body.contains("\"download_url\"")) return null
        val noteId = queryParam(url, "note_id")
        // swapUrl() 恒 null（见函数注释 E1/E6/E7）——此分支保留但当前不生效
        val better = noteId?.let { videoSources[it]?.swapUrl() }
        var patched = body
        if (hasDisableBlock) {
            patched = patched.replace(Regex("\"disable\"\\s*:\\s*true"), "\"disable\":false")
            patched = if (patched.contains("\"status\"")) {
                patched.replace(Regex("\"status\"\\s*:\\s*\\d+"), "\"status\":2")
            } else {
                patched.replace("\"disable\":false", "\"disable\":false,\"status\":2")
            }
        }
        if (better != null && better != body) {
            // 换链：服务器 download_url（转码档）→ 采集链最优源；带 ?sign 的原串整段替换
            patched = patched.replaceFirst(
                Regex("\"download_url\"\\s*:\\s*\"[^\"]*\""),
                "\"download_url\":\"$better\"",
            )
        }
        if (patched == body) return null
        val newResponse = rebuildResponse(response, patched) ?: return null
        module.log(
            Log.INFO, MountEngine.TAG,
            "$JUDGE_PREFIX 视频响应已改写 noteId=$noteId（${if (hasDisableBlock) "解锁" else "改写"}" +
                "${if (better != null) " 换链→${better.take(90)}" else ""}）",
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

    // ---- 外挂高清直下（X4 内置，零新挂点；save 响应每次必经本拦截）----

    /**
     * HD 直下去重（noteId → 上次发起时刻 ms；同 note 5 分钟内不重复发起——save API 每次保存重试 3-4 次）。
     * A1 起叠加**文件持久化**：宿主进程被系统回收/重注入后进程内表丢失 ⇒ 同一笔记重复弹询问卡
     * （真机实证 E11：11 分钟内弹 5 次）。表落宿主 Application filesDir，落盘走 [ioExecutor] 异步，
     * okhttp 拦截线程绝不碰磁盘。
     */
    private val hdDedupe = ConcurrentHashMap<String, Long>()
    private const val HD_DEDUPE_MS = 5 * 60_000L
    private const val MAX_VIDEO_BYTES = 500L * 1024 * 1024

    /**
     * 本轮真正弹 HD 询问卡的时刻（ms；0 = 从未弹过）。供 X5 视频保存反馈卡判重（[askShownAfter]）：
     * 同一次保存里 X4 已弹「下载高清原版视频」，X5 就不再弹「已保存本视频」（双卡并存，2026-10-15 真机实证）。
     * **仅快路径**（响应到达晚于 X5 判定点 ⇒ 基本命中不了）；最终保障 = X4 弹卡前主动
     * [XhsBatchSave.dismissVideoFeedbackCard] 关掉 X5 卡。
     */
    @Volatile private var lastAskAtMs = 0L

    /**
     * X5 反馈卡**快路径**判据（非唯一保障）：时刻 [t] 之后（含）弹过询问卡 ⇒ 本轮已有 X4 卡在屏上。
     * 只在 X4 的询问卡早于 X5 判定点弹出时才命中——X4 写本值是在宿主 save API 的**网络响应**到达时
     * （retrofit 异步，100~500ms 后），通常晚于 X5 在 proceed 返回处的判定 ⇒ 基本命中不了。
     * 最终保障 = X4 弹卡前调 [XhsBatchSave.dismissVideoFeedbackCard] 主动关掉 X5 卡（确定性握手，2026-10-15）。
     */
    fun askShownAfter(t: Long): Boolean = lastAskAtMs >= t

    /** A3 **运行内**续传（非同次运行不算）：读取阶段 IOException 最多重试 3 次，退避 1s/2s/4s。 */
    private const val HD_RESUME_MAX_RETRY = 3
    private const val HD_RESUME_BACKOFF_BASE_MS = 1_000L

    /** A1 持久化文件（宿主私有目录；每行 `noteId<TAB>epochMillis`）；上限 200 条，超窗口/超限的行写入时自然裁掉。 */
    private const val HD_DEDUPE_FILE = "xhs_hd_dedupe.tsv"
    private const val HD_DEDUPE_MAX = 200

    /** X4 后台 IO 单线程（A1 去重表落盘 + A2 孤儿清理）：daemon，绝不占用 okhttp 拦截线程。 */
    private val ioExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "HonorHook-xhs-io").apply { isDaemon = true }
        }
    }

    /**
     * 延时收尾线程（2026-10-15，缺陷③）：低画质兜底二删排在 5 秒后。**独立于 [ioExecutor]**——那里
     * 还排队着去重表落盘，用一个 sleep 占住单线程 5 秒会连带把它压住。daemon，绝不阻塞下载收尾。
     */
    private val delayedExecutor by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "HonorHook-xhs-delayed").apply { isDaemon = true }
        }
    }

    /** A1 惰性加载守卫（首次用到时读一次盘并合并进内存表 [hdDedupe]）。 */
    private val hdDedupeLoaded = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 宿主 Application（filesDir / ContentResolver 载体）。ActivityThread.currentApplication 是唯一横切入口。 */
    private fun currentApplication(): android.app.Application? = runCatching {
        val loader = hostLoader
        val cls = if (loader != null) Class.forName("android.app.ActivityThread", false, loader)
        else Class.forName("android.app.ActivityThread")
        cls.getMethod("currentApplication").invoke(null) as? android.app.Application
    }.getOrNull()

    /**
     * A1：首次用到去重表时惰性读盘一次，把持久化条目合并进内存表（进程内已有的值更新，不被盘上旧值覆盖）。
     * 只在 okhttp 拦截线程调用 ⇒ 这是本链路唯一的同步 IO，代价是一次 ≤200 行的小文件读；任何失败
     * 只打一行 WARN 并退回纯内存行为，绝不抛、绝不阻塞。
     */
    private fun ensureHdDedupeLoaded(module: XposedModule) {
        if (!hdDedupeLoaded.compareAndSet(false, true)) return
        val now = System.currentTimeMillis()
        val text = runCatching {
            val dir = currentApplication()?.filesDir ?: return
            val f = java.io.File(dir, HD_DEDUPE_FILE)
            if (!f.isFile) return
            f.readText()
        }.getOrElse {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 去重表读取失败（退回纯内存）: ${it.message}")
            return
        }
        var loaded = 0
        for (line in text.lineSequence()) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val id = line.substring(0, tab).trim()
            val ts = line.substring(tab + 1).trim().toLongOrNull() ?: continue
            if (id.isEmpty() || now - ts >= HD_DEDUPE_MS) continue   // 超窗口行丢弃，下次写入时从盘上清掉
            if ((hdDedupe[id] ?: 0L) < ts) hdDedupe[id] = ts
            loaded++
        }
        if (loaded > 0) {
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 去重表已加载 $loaded 条（跨进程去重生效）")
        }
    }

    /**
     * A1：内存表变更后异步落盘（全量重写 ≤200 行的小文件，代价可忽略）。
     * 快照在调用线程算（表小），写盘在 [ioExecutor] —— 失败只 WARN，绝不抛、绝不阻塞拦截线程。
     */
    private fun persistHdDedupe(module: XposedModule) {
        val now = System.currentTimeMillis()
        val rows = hdDedupe.entries
            .filter { now - it.value < HD_DEDUPE_MS }
            .sortedByDescending { it.value }
            .take(HD_DEDUPE_MAX)
        runCatching {
            ioExecutor.execute {
                runCatching {
                    val dir = currentApplication()?.filesDir ?: return@runCatching
                    val sb = StringBuilder(rows.size * 24)
                    for (e in rows) sb.append(e.key).append('\t').append(e.value).append('\n')
                    java.io.File(dir, HD_DEDUPE_FILE).writeText(sb.toString())
                }.onFailure {
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 去重表写入失败（仅内存生效）: ${it.message}")
                }
            }
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 去重表落盘调度失败: ${it.message}")
        }
    }

    /** 主线程 Handler（进度弹窗必须主线程）。 */
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** 当前进度弹窗（单实例：新下载顶掉旧的）。 */
    @Volatile private var progressDialog: android.app.Dialog? = null
    @Volatile private var progressText: android.widget.TextView? = null
    @Volatile private var progressBar: android.widget.ProgressBar? = null

    /** 末次进度百分比：建卡回填（[showProgressDialog]）+ 满格当场收卡（[updateProgress]）。 */
    @Volatile private var lastPct = 0

    /** 下载进行中标记（弹窗重试循环据此中止；下载极快时防「完成后才弹出、永久停留」）。 */
    @Volatile private var hdActive = false

    /**
     * 展示进度卡。Activity 取不到时每 300ms 重试（≤5s）——首次调用时追踪器可能刚注册、
     * 当前 Activity 的 onResume 已错过（2026-10-14 真机「没弹窗」实证根因）。
     */
    private fun showProgressDialog(attempt: Int = 0) {
        mainHandler.post {
            if (!hdActive) return@post
            val activity = trackedActivity
            if (activity == null) {
                if (attempt < 16) mainHandler.postDelayed({ showProgressDialog(attempt + 1) }, 300)
                return@post
            }
            runCatching {
                progressDialog?.dismiss()
                val night = (activity.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                    android.content.res.Configuration.UI_MODE_NIGHT_YES
                val bg = if (night) 0xFF1E1E22.toInt() else 0xFFFFFFFF.toInt()
                val titleColor = if (night) 0xFFF5F5F7.toInt() else 0xFF17171A.toInt()
                val dp = activity.resources.displayMetrics.density
                fun d(v: Int) = (v * dp).toInt()

                val card = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    setPadding(d(24), d(22), d(24), d(22))
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = d(22).toFloat(); setColor(bg)
                    }
                }
                card.addView(android.widget.TextView(activity).apply {
                    text = "正在下载原版视频"
                    textSize = 16f
                    setTextColor(titleColor)
                    paint.isFakeBoldText = true
                })
                progressText = android.widget.TextView(activity).apply {
                    // 回填末次百分比（2026-10-15）：本函数可能晚于下载事件建卡（Activity 重试链），
                    // 只写当前引用会让卡停在 0% 直到下一次进度回调。
                    text = "$lastPct%"
                    textSize = 13f
                    setTextColor(if (night) 0xFF98989D.toInt() else 0xFF8A8A8E.toInt())
                    setPadding(0, d(6), 0, d(12))
                }
                card.addView(progressText)
                progressBar = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                    max = 100
                    progress = lastPct
                }
                card.addView(progressBar, android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT, d(6)))

                progressDialog = android.app.Dialog(activity).apply {
                    setContentView(card)
                    window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(0x00000000))
                    setCancelable(false)
                    show()
                }
            }
        }
    }

    /** 宿主当前 Activity（ActivityThread.currentApplication + lifecycle 追踪）。 */
    @Volatile private var trackedActivity: android.app.Activity? = null
    private val activityTracked = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * install 期注册追踪（轮询到 Application 就注册——必须赶在首个 Activity onResume 之前，
     * 否则当前页永远追踪不到；懒注册版 2026-10-14 真机弹窗实证失效）。
     */
    fun ensureActivityTracker() {
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
                            override fun onActivityResumed(a: android.app.Activity) { trackedActivity = a }
                            override fun onActivityPaused(a: android.app.Activity) { if (trackedActivity === a) trackedActivity = null }
                            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
                            override fun onActivityStarted(a: android.app.Activity) {}
                            override fun onActivityStopped(a: android.app.Activity) {}
                            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
                            override fun onActivityDestroyed(a: android.app.Activity) {}
                        })
                    }
                    return
                }
                if (attempt++ < 60) mainHandler.postDelayed(this, 500)
            }
        })
    }

    /** A2 孤儿清理守卫（每进程只跑一次）。 */
    private val pendingCleanupRan = java.util.concurrent.atomic.AtomicBoolean(false)

    /** A2 `.pending-` 半成品视为孤儿的年龄阈值：10 分钟（在写的半成品 lastModified 就是当前时刻）。 */
    private const val PENDING_ORPHAN_AGE_MS = 10 * 60_000L

    /**
     * A2 孤儿清理：宿主进程被杀时 [rollbackHd] 不执行，MediaStore 的 `IS_PENDING` 态半成品
     * （落盘名 `.pending-<id>-<name>`，真机实测残留 102,759,493 B）会永久留在 `Movies/小红书`。
     * 启动时后台扫一次，删「文件名以 `.pending-` 开头 **且** lastModified 早于 now-10min」的条目。
     * **年龄门槛绝不能省**：正在下载的半成品 mtime 就是刚刚，删了等于毁掉正在跑的下载。
     * 目录不存在 / listFiles 返回 null = 用户从没下过原片，属正常，静默返回（不打 WARN）。
     */
    private fun cleanupPendingOrphans(module: XposedModule) {
        if (!pendingCleanupRan.compareAndSet(false, true)) return
        runCatching {
            ioExecutor.execute {
                runCatching {
                    val dir = java.io.File(
                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
                        "小红书",
                    )
                    val files = dir.listFiles() ?: return@runCatching   // 目录不存在/无权限：正常，静默
                    val cutoff = System.currentTimeMillis() - PENDING_ORPHAN_AGE_MS
                    var deleted = 0
                    var freed = 0L
                    for (f in files) {
                        if (!f.name.startsWith(".pending-")) continue
                        if (f.lastModified() >= cutoff) continue   // 新近修改 = 正在下载，绝不能删
                        val size = f.length()
                        if (f.delete()) {
                            deleted++
                            freed += size
                            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 清理孤儿半成品 ${f.name} (${size / 1024}KB)")
                        }
                    }
                    // 汇总行删 0 个也打（判据：能区分「扫过没孤儿」与「没扫」）
                    module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 孤儿清理完成 删除=${deleted}个 释放=${freed / 1024}KB")
                }.onFailure {
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 孤儿清理异常: ${it.message}")
                }
            }
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 孤儿清理调度失败: ${it.message}")
        }
    }

    private fun updateProgress(pct: Int) {
        lastPct = pct
        // 满格当场收卡（2026-10-15，缺陷③）：收尾原先把第二次低画质清理压在 5s sleep 之后，dismiss 被
        // 一起拖后 5~6 秒 ⇒ 进度卡（setCancelable(false)，关不掉）与完成卡并存。这里读到 100% 就立刻收。
        // hdActive 先落：showProgressDialog 的 300ms×16 重试链据此中止，否则收卡后又被建回来；
        // 此刻读流循环已结束（本函数 100% 只由循环末次推进或 total<=0 分支触发），而续传重试只发生在
        // 读流 IOException 期间 ⇒ 置 false 不打断续传。
        if (pct >= 100) {
            hdActive = false
            dismissProgress()
        }
        mainHandler.post {
            runCatching {
                progressBar?.progress = pct
                progressText?.text = "$pct%"
            }
        }
    }

    private fun dismissProgress() {
        mainHandler.post {
            runCatching { progressDialog?.dismiss() }
            progressDialog = null
            progressText = null
            progressBar = null
        }
    }

    /** 体积显示（单点实现）：<1 MiB 走整数 KB，其余 1024 进制一位小数 MiB（截断）。 */
    private fun formatSize(bytes: Long): String {
        if (bytes < 1024L * 1024L) return "${(bytes + 512L) / 1024L} KB"
        val mi = bytes / 1048576L
        val frac = (bytes % 1048576L) * 10L / 1048576L
        return "$mi.$frac MiB"
    }

    /**
     * 下载完成卡（用户定案 2026-10-14）：询问卡用动作态、完成卡才说「已下载」——本卡即直下成功后的完成态。
     * 标题体积用实际落盘字节 [bytes]，正文落盘文件名 [name]，单按钮关闭。
     * 主线程 + Activity 取不到时重试（同 [showProgressDialog] 那套），异常静默（绝不波及下载链路）。
     */
    private fun showDownloadedDialog(bytes: Long, name: String, attempt: Int = 0) {
        mainHandler.post {
            val activity = trackedActivity
            if (activity == null) {
                if (attempt < 16) mainHandler.postDelayed({ showDownloadedDialog(bytes, name, attempt + 1) }, 300)
                return@post
            }
            runCatching {
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
                    text = "已下载 ${formatSize(bytes)}"
                    textSize = 17f; setTextColor(titleColor); paint.isFakeBoldText = true
                })
                card.addView(android.widget.TextView(activity).apply {
                    text = name
                    textSize = 14f; setTextColor(subColor); setPadding(0, d(8), 0, 0)
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
        }
    }

    private fun scheduleHdVideoDownload(url: String, body: String, module: XposedModule) {
        val noteId = queryParam(url, "note_id") ?: return
        // t0 = 本轮 save 拦截时刻，透传给 V-5 低画质清理的时间窗锚点
        val t0 = System.currentTimeMillis()
        ensureHdDedupeLoaded(module)   // A1：首次用到时读盘合并（跨进程去重；读失败退回纯内存）
        val last = hdDedupe[noteId] ?: 0L
        if (t0 - last < HD_DEDUPE_MS) return   // V-6：命中即连询问卡都不弹
        hdDedupe[noteId] = t0
        persistHdDedupe(module)        // A1：变更后异步落盘，绝不阻塞本拦截线程

        // 候选集（2026-10-15 基准切换）：采集链全部逐 URL 候选，选档在 pickBestAndDownload
        // （逐语义移植 XHS_Downloader_Android NoteOutput.kt:47-56——响应 download_url 不参与选档）
        val streams = videoSources[noteId]?.candidates ?: emptyList()
        if (streams.isEmpty()) return

        // 下载上下文：宿主 Application（MediaStore/ContentResolver 用）
        val app = runCatching {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.content.Context
        }.getOrNull() ?: run {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX HD直下: 宿主 Application 不可取，放弃")
            return
        }
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX HD询问 noteId=$noteId 候选 ${streams.size} 个")
        // 确定性让位：X4 卡后弹 ⇒ 主动关掉 X5 的「已保存本视频」反馈卡，不依赖时序（2026-10-15）
        XhsBatchSave.dismissVideoFeedbackCard()
        // 记本轮弹卡时刻（真正要弹才写：上面各 early-return 分支都不算）——X5 反馈卡快路径判据，见 askShownAfter
        lastAskAtMs = System.currentTimeMillis()
        showAskDialog(noteId, streams, app, t0, module)
    }

    /** 询问卡：客户端已保存低画质，问是否下载高清版（用户定案 2026-10-14）。 */
    private fun showAskDialog(noteId: String, streams: List<VideoCandidate>, app: android.content.Context, t0: Long, module: XposedModule, attempt: Int = 0) {
        mainHandler.post {
            val activity = trackedActivity
            if (activity == null) {
                if (attempt < 16) mainHandler.postDelayed({ showAskDialog(noteId, streams, app, t0, module, attempt + 1) }, 300)
                return@post
            }
            runCatching {
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
                    text = "下载高清原版视频"
                    textSize = 17f; setTextColor(titleColor); paint.isFakeBoldText = true
                })
                card.addView(android.widget.TextView(activity).apply {
                    text = "客户端已保存低画质版，是否改为下载原片？"
                    textSize = 14f; setTextColor(subColor); setPadding(0, d(8), 0, 0)
                })
                @Suppress("UNCHECKED_CAST")
                val dialog = android.app.Dialog(activity)
                fun pillBtn(label: String, filled: Boolean, onClick: () -> Unit) =
                    android.widget.TextView(activity).apply {
                        text = label; textSize = 15f
                        gravity = android.view.Gravity.CENTER
                        maxLines = 1
                        setPadding(d(18), d(11), d(18), d(11))
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = d(18).toFloat()
                            if (filled) setColor(accent) else { setColor(0x00000000); setStroke(d(1), subColor) }
                        }
                        setTextColor(if (filled) 0xFFFFFFFF.toInt() else subColor)
                        setOnClickListener { onClick() }
                    }
                val row = android.widget.LinearLayout(activity).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    setPadding(0, d(18), 0, 0)
                }
                row.addView(pillBtn("取消", filled = false) { dialog.dismiss() }.apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = d(10) }
                })
                row.addView(pillBtn("下载原版", filled = true) {
                    dialog.dismiss()
                    hdActive = true
                    showProgressDialog()
                    Thread({
                        runCatching { pickBestAndDownload(app, noteId, streams, t0, module) }
                            // V-6：HD 下载成功后再写一次去重锚，避免同 note 短时间反复弹卡（E11）
                            .onSuccess {
                                hdDedupe[noteId] = System.currentTimeMillis()
                                persistHdDedupe(module)   // A1：成功锚同样落盘
                            }
                            .onFailure { module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX HD直下失败 noteId=$noteId: ${it.message}") }
                        hdActive = false
                        dismissProgress()
                    }, "HonorHook-xhs-video-hd").start()
                }.apply {
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
        }
    }

    /**
     * 选档（2026-10-15 基准切换，用户拍板逐语义移植 XHS_Downloader_Android）：
     * 候选集 = 原片（移动版网页解析 originVideoKey 成功时置顶：width/height=0、watermarked=false、
     * original=true，等价下载器 `original=true` id 最高优先）+ 采集链全部逐 URL 候选
     * （data/xhs/XhsNoteParser.kt:172-204 语义）。排序照 domain/download/NoteOutput.kt:47-56
     * 默认 RESOLUTION 偏好：watermarked 升序 → original 降序 → w×h 降序；`sortedWith` 稳定排序 +
     * `first()` = 平手取排序后列表第一个（逐字对齐）。origin 解析成功即走置顶语义（等价直接用原片）；
     * 候选集为空 WARN 一行后 return。选中流 URL = 其首个候选（backup_urls[0] 列表序，无 backup 则
     * master_url——采集展开序已保证）。
     * 旧「probeSize 实测字节选最大」已删：选择不再依赖实测字节——跨编码字节不可比（真机实证
     * H264 _258 37808KB 胜 H265 高分档 ~10MB），且原片直链被字节比较挤掉。
     */
    private fun pickBestAndDownload(app: android.content.Context, noteId: String, streams: List<VideoCandidate>, t0: Long, module: XposedModule) {
        if (streams.isEmpty()) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 选档 noteId=$noteId 无候选，放弃")
            return
        }
        // 真原始档不在 app API 梯子里（E6/E7），只在移动版网页 __INITIAL_STATE__ 的 camelCase
        // originVideoKey。解析失败恒 null ⇒ 回落梯子候选选档，行为与原状一致。
        val originUrl = noteShares[noteId]?.let { resolveOriginVideoUrl(noteId, it, module) }
        val candidates = if (originUrl != null) {
            listOf(VideoCandidate(originUrl, 0, 0, 0, 0, null, watermarked = false, original = true)) + streams
        } else {
            streams
        }
        val best = candidates.sortedWith(
            compareBy<VideoCandidate> { it.watermarked }
                .thenByDescending { it.original }
                .thenByDescending { it.width.toLong() * it.height },
        ).first()
        module.log(Log.INFO, MountEngine.TAG,
            "$JUDGE_PREFIX 选档 noteId=$noteId 原片=${if (best.original) "yes" else "no"} " +
                "选中=${best.width}x${best.height} ${best.url.take(120)}")
        downloadVideoHd(app, noteId, best.url, t0, module)
    }

    /**
     * 下载 HD → MediaStore「Movies/小红书」→ 删客户端低画质文件。
     *
     * V-4 交付管线修复（E10 实证旧版落 `Movies/小红书` 0 字节）：
     *  insert 置 IS_PENDING=1 → `inputStream` 流式写 `openOutputStream`（不再全量驻留 ByteArray）
     *  → Content-Type 拒错误页类型（黑名单：text 类、xml、json；octet-stream 合法放行）→ 写入字节数 == Content-Length（>0 时）
     *  → IS_PENDING=0 + DATE_ADDED/DATE_TAKEN；任何失败/异常回滚删 uri。
     * 注：IS_PENDING 只是写入正确性卫生——宿主自身落盘并不置它（idc/w.java:730-760），
     * 「文件消失」的实质修复是流式写 + 两份校验 + 失败回滚；目录/文件名维持原样。
     *
     * A3：读取阶段 IOException 走**运行内**续传（重发带 `Range: bytes=<已写>-` 的请求，206 追加 /
     * 200 回落从头），最多 3 次；**不是跨进程续传**——宿主进程被杀仍从 0 重下（残片由 [cleanupPendingOrphans] 清）。
     *
     * 提案 3/4（2026-10-15 基准传输链对齐，ResumableTransfer.kt:137-139/171-174 语义）：首写路径缓存
     * 首块 64B，终检补魔数校验（仅 mov/mp4/webm 放行；206 续传无首块跳过）；首次拿到 total 后、
     * insert 前空间预检，不足放弃（不 insert，不删宿主文件）。
     */
    private fun downloadVideoHd(ctx: android.content.Context, noteId: String, startUrl: String, t0: Long, module: XposedModule) {
        var u = startUrl
        var redirects = 0
        var resumeAttempt = 0   // A3 运行内续传已用次数（上限 HD_RESUME_MAX_RETRY）
        var total = 0L
        var written = 0L
        var contentType: String? = null
        // 提案 3：首写路径缓存的首块（64B）——终检魔数校验用；206 续传路径无首块 ⇒ 终检跳过魔数
        var firstBlock: ByteArray? = null
        // 文件名/MediaStore uri 在拿到响应 Content-Type 后才定（insert 因此移入 200 分支）：
        // 原片 = video/quicktime（MOV，实测 116.7MB/quicktime）→ `_origin.mov`；其余梯子档
        // （video/mp4 / octet-stream）→ `_hd.mp4`。
        var ourUri: android.net.Uri? = null
        var name = "xhs_${noteId}_hd.mp4"
        try {
            while (true) {
                val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 15_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = false
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36")
                conn.setRequestProperty("Referer", "https://www.xiaohongshu.com/")
                // A3 运行内续传：已写字节 > 0 才带 Range（首次请求不带）；服务端忽略它则回 200（见下分支）
                if (written > 0) conn.setRequestProperty("Range", "bytes=$written-")
                try {
                    when (val code = conn.responseCode) {
                        in 300..399 -> {
                            if (++redirects > 5) { rollbackHd(ctx, ourUri); return }
                            u = conn.getHeaderField("Location") ?: run { rollbackHd(ctx, ourUri); return }
                            continue
                        }
                        // A3：206 = 带 Range 请求被接受（续传），按追加写续上剩余字节
                        200, 206 -> {
                            if (code == 200 && written > 0) {
                                // 服务端不支持 Range（忽略它回 200 全量）：视为从头开始，written 归零重写
                                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX HD直下 续传被拒（回 200），从头重下")
                                written = 0
                            }
                            // 首写才定 Content-Type/文件名并 insert（续传沿用同一 uri，不能再插一条）
                            if (ourUri == null) {
                                total = conn.contentLengthLong
                                // 提案 4 空间预检（基准 ResumableTransfer.kt:137-139 NO_SPACE）：首次拿到
                                // total 后、insert 前；可用空间 < 需求 + 1MB ⇒ 放弃（不 insert；ourUri 仍为
                                // null，无需回滚、不删宿主文件）。206 续传路径 ourUri 非空，不重查。
                                if (total > 0) {
                                    val dir = java.io.File(
                                        android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_MOVIES),
                                        "小红书",
                                    )
                                    val usable = dir.usableSpace
                                    if (usable < total + 1024L * 1024) {
                                        module.log(Log.WARN, MountEngine.TAG,
                                            "$JUDGE_PREFIX HD直下 空间不足（需 ${total / 1048576}MB，可用 ${usable / 1048576}MB），放弃")
                                        return
                                    }
                                }
                                contentType = conn.contentType
                                val ctLower = contentType?.lowercase()?.substringBefore(';')?.trim()
                                val isMov = ctLower == "video/quicktime"
                                name = if (isMov) "xhs_${noteId}_origin.mov" else "xhs_${noteId}_hd.mp4"
                                val values = android.content.ContentValues().apply {
                                    put(android.provider.MediaStore.Video.Media.DISPLAY_NAME, name)
                                    put(android.provider.MediaStore.Video.Media.MIME_TYPE, if (isMov) "video/quicktime" else "video/mp4")
                                    put(android.provider.MediaStore.Video.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_MOVIES + "/小红书")
                                    put(android.provider.MediaStore.Video.Media.IS_PENDING, 1)
                                }
                                ourUri = ctx.contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: return
                            }
                            val uri = ourUri   // 上面 insert 分支已保证非空
                            // 流式落盘（V-4）：边读边写，不再全量驻留内存；Content-Length 已知时按字节算进度。
                            // A3 模式：已写字节 > 0 用追加（"wa"），否则清空重写（"rwt"）——首写与「服务端不支持
                            // Range」两条路径都落在 "rwt" 上，续传（206）落在 "wa" 上。
                            ctx.contentResolver.openOutputStream(uri, if (written > 0) "wa" else "rwt")?.use { out ->
                                conn.inputStream.use { input ->
                                    val buf = ByteArray(128 * 1024)
                                    var done = written   // A3：进度按累加字节续，不退回 0
                                    var lastPct = -1
                                    // 提案 3：首写路径（written==0，含「服务端拒 Range 回 200 从头重下」）先读
                                    // 首块 64B 缓存供终检魔数（基准 ResumableTransfer.kt:171-174 写完探测；插件
                                    // 改为缓存首块免重读落盘文件）。首块照常写入不丢；206 续传路径不读。
                                    if (written == 0L) {
                                        val head = ByteArray(64)
                                        var fill = 0
                                        while (fill < head.size) {
                                            val n = input.read(head, fill, head.size - fill)
                                            if (n < 0) break
                                            fill += n
                                        }
                                        firstBlock = head.copyOf(fill)
                                        out.write(head, 0, fill)
                                        done += fill
                                        written = done
                                    }
                                    while (true) {
                                        val n = input.read(buf)
                                        if (n < 0) break
                                        out.write(buf, 0, n)
                                        done += n
                                        // A3：逐块推进 written——读中断时 Range 起点/追加偏移必须等于已落盘字节
                                        written = done
                                        if (total > 0) {
                                            val pct = ((done * 100) / total).toInt()
                                            if (pct != lastPct) { lastPct = pct; updateProgress(pct) }
                                        }
                                        if (done > MAX_VIDEO_BYTES) { rollbackHd(ctx, uri); return }
                                    }
                                }
                            }
                            if (total <= 0) updateProgress(100)
                            break
                        }
                        else -> {
                            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX HD直下 HTTP $code 放弃")
                            rollbackHd(ctx, ourUri)
                            return
                        }
                    }
                } catch (io: java.io.IOException) {
                    // A3 **运行内**续传：只救本进程内的读中断（SocketTimeout / 连接被掐）；重试 = 外层循环重发
                    // 一次带 Range 的请求。进程被杀不在此列（从 0 重下，残片交 A2 清）。
                    if (resumeAttempt >= HD_RESUME_MAX_RETRY) {
                        module.log(Log.WARN, MountEngine.TAG,
                            "$JUDGE_PREFIX HD直下 续传重试耗尽（已写 ${written / 1024}KB / 共 ${total / 1024}KB）: ${io.message}")
                        throw io   // 交外层 catch：rollbackHd + 抛（保持既有失败语义）
                    }
                    resumeAttempt++
                    val delay = HD_RESUME_BACKOFF_BASE_MS shl (resumeAttempt - 1)   // 1s → 2s → 4s
                    module.log(Log.WARN, MountEngine.TAG,
                        "$JUDGE_PREFIX HD直下 读取中断，${delay}ms 后第 $resumeAttempt 次续传（Range: bytes=$written-）: ${io.message}")
                    Thread.sleep(delay)
                    continue
                } finally {
                    conn.disconnect()
                }
            }
            // 校验 1（黑名单，2026-10-14 修正）：CDN「原始档」常回 application/octet-stream（合法），
            // 白名单 `video/` 前缀会误杀 ⇒ 只拒明确是错误页/错误体的类型（text/*、xml、json）；
            // 其余（video/mp4、application/octet-stream、缺失 Content-Type）放行，交字节数校验兜底。
            val ct = contentType?.lowercase()?.substringBefore(';')?.trim()
            if (ct != null && (ct.startsWith("text/") || ct == "application/xml" || ct == "application/json")) {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX HD直下 Content-Type 疑错误页（$contentType），回滚")
                rollbackHd(ctx, ourUri)
                return
            }
            // 校验 2：写入字节数与 Content-Length 比对（length>0 时）
            if (total > 0 && written != total) {
                module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX HD直下 字节数不符（写 $written / 期望 $total），回滚")
                rollbackHd(ctx, ourUri)
                return
            }
            // 校验 3（提案 3 魔数，基准 ResumableTransfer.kt:171-174 写完探测认不出 = NON_MEDIA 删件）：
            // 用首写路径缓存的首块判内容，仅 mov/mp4/webm 放行；206 续传路径无首块则跳过（沿用校验 1/2）。
            val fb = firstBlock
            if (fb != null) {
                val kind = XhsMediaFileType.detect(fb)
                if (kind == null || kind !in XhsMediaFileType.VIDEO_KINDS) {
                    module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX HD直下 魔数非视频（${kind ?: "未识别"}），回滚")
                    rollbackHd(ctx, ourUri)
                    return
                }
            }
        } catch (t: Throwable) {
            rollbackHd(ctx, ourUri)
            throw t
        }
        val finalUri = ourUri
        // 成功路径放在 try 外：try 内任何异常都已在 catch 里 rollbackHd 后 rethrow，收尾留在 try 外即
        // 「收尾失败绝不回滚已写好的文件」。（原注释讲的是「避免下面的 5s sleep 被打断时误删」——
        // 2026-10-15 起第二次低画质清理改独立线程、sleep 已删，理由换成本句，位置不动。）
        // 关 IS_PENDING + 写时间戳（DATE_ADDED 秒 / DATE_TAKEN 毫秒）
        ctx.contentResolver.update(finalUri, android.content.ContentValues().apply {
            put(android.provider.MediaStore.Video.Media.IS_PENDING, 0)
            put(android.provider.MediaStore.Video.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(android.provider.MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
        }, null, null)
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX HD直下完成 noteId=$noteId ${written / 1024}KB → Movies/小红书/$name")
        // 收尾顺序（2026-10-15，缺陷③）：先收进度卡再弹完成卡。原先 Thread.sleep(5_000) 卡在这一段之前，
        // 把 dismissProgress 一起拖后 5~6 秒 = 进度卡与完成卡并存。
        hdActive = false      // 先落标记：中止 showProgressDialog 的重试链，防收卡后又被建回来
        dismissProgress()     // 幂等：兜住 total<=0（无进度更新）与失败回滚两条路径
        showDownloadedDialog(written, name)
        deleteLowQuality(ctx, t0, finalUri, written, module)
        // 客户端低码文件可能晚于我们落盘：5s 后兜底再删一轮——专用延时线程，不再 sleep 阻塞收尾
        runCatching {
            delayedExecutor.schedule(
                { deleteLowQuality(ctx, t0, finalUri, written, module) },
                5, java.util.concurrent.TimeUnit.SECONDS,
            )
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 延时低画质清理调度失败: ${it.message}")
        }
    }

    /** V-4 失败回滚：删掉半成品 uri（IS_PENDING 残留会占坑）；uri 为 null（insert 前失败）时 no-op。 */
    private fun rollbackHd(ctx: android.content.Context, uri: android.net.Uri?) {
        if (uri == null) return
        runCatching { ctx.contentResolver.delete(uri, null, null) }
    }

    /**
     * 删客户端低画质视频（V-5 改造）。
     *
     * E9 + 源码实证（宿主落盘收口 `idc/w.java:730-760`，视频保存三处调用点 `r01/p.java:27` 等）：
     * 客户端落 `DCIM/`、display_name = `Camera/<临时文件名>`（**不含 noteId**）⇒ 旧版
     * `DISPLAY_NAME LIKE '%noteId%'` 恒 0 命中（静默 no-op）。改为以本轮 save 拦截时刻 `t0` 为锚的
     * **时间窗**：`DATE_ADDED >= t0-10s` + 非本文件 + `SIZE < ourSize`。查询走
     * Video.EXTERNAL_CONTENT_URI（覆盖 DCIM/Pictures/Movies 全域）。不设 OWNER_PACKAGE_NAME 硬条件
     * （宿主经 ContentResolver 插入，owner 未必是 com.xingin.xhs）。
     */
    private fun deleteLowQuality(ctx: android.content.Context, t0: Long, ourUri: android.net.Uri, ourSize: Long, module: XposedModule) {
        runCatching {
            val proj = arrayOf(
                android.provider.MediaStore.Video.Media._ID,
                android.provider.MediaStore.Video.Media.DISPLAY_NAME,
                android.provider.MediaStore.Video.Media.SIZE,
            )
            val since = (t0 / 1000) - 10   // DATE_ADDED 单位为秒
            ctx.contentResolver.query(
                android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                "${android.provider.MediaStore.Video.Media.DATE_ADDED} >= ?", arrayOf(since.toString()),
                null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media._ID)
                val nameCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.DISPLAY_NAME)
                val sizeCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Video.Media.SIZE)
                var deleted = 0
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val size = c.getLong(sizeCol)
                    val uri = android.content.ContentUris.withAppendedId(
                        android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                    if (uri == ourUri) continue
                    if (size >= ourSize) continue   // 只删比我们小的（低画质判定）
                    val r = ctx.contentResolver.delete(uri, null, null)
                    if (r > 0) {
                        deleted++
                        module.log(Log.INFO, MountEngine.TAG,
                            "$JUDGE_PREFIX 已删低画质文件 ${c.getString(nameCol)} (${size / 1024}KB < ${ourSize / 1024}KB)")
                    }
                }
                if (deleted == 0) {
                    module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 低画质清理：无可删（客户端文件未落盘或体积不小于 HD）")
                }
            }
        }.onFailure {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 低画质清理失败: ${it.message}")
        }
    }

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
