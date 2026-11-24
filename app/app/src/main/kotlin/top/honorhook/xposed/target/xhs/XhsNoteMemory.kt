package top.honorhook.xposed.target.xhs

import android.app.Activity
import com.neoruaa.xhsdn.core.model.ResolvedMedia
import com.neoruaa.xhsdn.core.model.ResolvedNote
import java.lang.reflect.Field

/**
 * 详情页内存取图（2026-11-24，用户 m00461 选 A）。
 *
 * 为什么需要第三条数据源：分享页解析依赖「分享链凭据」（`share_info.link` / `xsec_token`）。
 * 宿主命中本地缓存时详情页**不发 imagefeed**，凭据登记不上；列表卡片反射 token 也偶发读不出
 * ⇒ [XhsSavePivot.resolveByShareLink] 开头直接早退（驱动日志「链=无凭据」），连网页兜底都走不到。
 * 但这一刻详情页已经把这组图渲染在屏幕上了：宿主的 note 模型就在 Activity/ViewModel 对象图里，
 * 图集直链（`imageList[].urlDefault` / `infoList[].url`）与凭据无关，是现成的。
 *
 * 字段名来源（逆向实证，勿凭直觉改）：上游 `XhsNoteParser`（reference/xhs 1.4.1）对分享页 JSON
 * 用的就是这套——`imageList`/`images`、每项 `urlDefault` > `url` > `infoList[].url`（`imageScene`
 * 优先 `WB_DFT`），另有 `video`/`type=="video"`/`user.nickname`/`user.userId`。宿主内存里的模型
 * 字段名与接口 JSON 同源（R8 只改类名不改成员名，模型字段带 `@SerializedName` 或 kotlin 属性名）。
 *
 * 判据与边界：
 * - **只认同一篇**：对象里必须有 24 位 id 字段 == noteId；匹配不上就返回 null，绝不按类名猜
 *   （按类名猜是 [XhsBatchDriver.modelProbe] 的活，那是只读诊断）。
 * - **视频笔记整篇跳过**（`type=="video"` 或 bean 级 `video` 非空）：内存里往往只剩封面，
 *   存下来就是错内容——宁可诚实失败。
 * - 全程 try/catch + 限深限量（主线程 3s 预算内跑完）；失败返回 null，调用方照旧判失败。
 *
 * **2026-11-24 审计收紧的三处**（都用单测钉住，见 XhsNoteMemoryTest）：
 * 1. 图集只认**第一个**抽得出直链的集合字段，且跳过 user/author/comment/avatar/video 这类字段——
 *    原来把所有集合字段的 URL 合在一起，评论配图、作者头像会混进图集（存错内容比失败更糟）。
 * 2. 直链必须落在 `.xhscdn.com` / `.xiaohongshu.com`：下游 `XhsImageUrl.toBare` 不适用的 URL
 *    会原样下载，混进一张外域链接就是一张垃圾图。
 * 3. 视频判定容忍空容器（`video: {}` / 空 List 的图文模型很常见），并认 `noteType` / `isVideo`。
 * 另外：反射字段按 Class 缓存（挂主线程也不再反复 declaredFields），落空时把原因写进 [lastDiag]
 * 供驱动日志取证——真机一次失败就能看出是「没找到对象」还是「找到但字段口径不符」。
 */
internal object XhsNoteMemory {

    private val HEX24 = Regex("^[0-9a-fA-F]{24}$")

    /** 最近一次 [resolve] 的失败原因（只有失败时非空；驱动侧在 vlog 里带出去）。 */
    @Volatile
    internal var lastDiag: String? = null

    /** 抽图时整段跳过的字段名（前缀匹配，忽略大小写）：这些字段里的 URL 不是这篇的图集。 */
    private val SKIP_FIELD_PREFIXES = arrayOf(
        "user", "author", "comment", "avatar", "profile", "video", "livephoto", "owner", "ad",
    )

    /** 对象图遍历上限：详情页对象图可到上万节点，老探针的 60×40 口径太窄，但也不能无限走。
     *  字段上限放宽到 300：宿主 NoteBean 字段数 60+，`imageList` 可能排在后面，卡 60 会永远取不到图。 */
    private const val MAX_DEPTH = 7
    private const val MAX_VISITED = 1200
    private const val MAX_FIELDS = 300
    /** 遍历时只看前 120 个字段：千级对象 × 全字段反射会顶满主线程 3s 预算，取值仓不在后段。 */
    private const val TRAVERSE_FIELDS = 120
    private const val MAX_COLLECTION = 40

    /** `urlDefault` > `url` > `urlPre`；`infoList` 里优先这几个场景（与上游选档口径一致）。 */
    private val URL_FIELDS = arrayOf("urlDefault", "url", "urlPre", "url_default")
    private val SCENE_PREFERRED = arrayOf("WB_DFT", "WB_DFT_WEBP", "CRD_WM_WEBP")

    fun resolve(activity: Activity?, noteId: String): ResolvedNote? = extractFrom(activity, noteId)

    /**
     * 同 [resolve]，但根对象可以是任意对象——JVM 单测用它钉住字段抽取口径（Activity 造不出来）。
     */
    internal fun extractFrom(root: Any?, noteId: String): ResolvedNote? = try {
        lastDiag = null
        if (root == null || !HEX24.matches(noteId)) {
            lastDiag = if (root == null) "没有可用的 Activity" else "noteId 不是 24 位 id"
            null
        } else {
            val samples = ArrayList<String>(3)
            val bean = findBean(root, noteId, samples)
            when {
                bean == null -> {
                    // 真机（2026-11-24 22:42）`链=无凭据` 就落在这里：把图里见到的其它 id 一并打出来，
                    // 「一个 id 都对不上」（多半是驱动侧 noteId 认错）与「对上了但抽不出图」修法完全不同。
                    val hint = if (samples.isEmpty()) "" else "（疑似 id：${samples.joinToString("，")}）"
                    lastDiag = "对象图里没有 id=$noteId 的对象$hint"
                    null
                }
                looksVideo(bean) -> {
                    lastDiag = "命中 ${bean.javaClass.simpleName}，但这是视频笔记（只取到封面）"
                    null
                }
                else -> {
                    val urls = imagesOf(bean, 0)
                    if (urls.isEmpty()) {
                        lastDiag = "命中 ${bean.javaClass.simpleName}，但没抽出图集直链（字段口径不符）"
                        null
                    } else {
                        val user = objectField(bean, "user", "userInfo", "author")
                        ResolvedNote(
                            canonicalUrl = "",
                            title = stringField(bean, "title"),
                            description = stringField(bean, "desc", "description"),
                            authorName = user?.let { stringField(it, "nickname", "nickName", "name") },
                            // 只认 userId：用户对象里的裸 `id` 可能是别的含义，认错会被作者闸门判成别人的笔记。
                            authorId = user?.let { stringField(it, "userId", "user_id") }
                                ?.takeIf { HEX24.matches(it) },
                            publishTime = null,
                            images = urls.map { ResolvedMedia.Image(sourceUrl = it, originalUrl = it) },
                            videos = emptyList(),
                            noteId = noteId,
                            type = "normal",
                        )
                    }
                }
            }
        }
    } catch (_: Throwable) {
        lastDiag = "抽取异常"
        null
    }

    // ---- 找 bean ----

    /**
     * 广度优先：先近后远；先按 24 位 id **精确**匹配，落空再松一档按「String 字段里包含该 id」匹配
     * （有的模型把 id 拼进分享链/url 字段）。命中也要真抽得出图才算数。
     * [samples] 顺手收集对象图里见到的其它 24 位 id（限 3 条），落空时写进 [lastDiag] 供取证。
     */
    private fun findBean(root: Any, noteId: String, samples: MutableList<String>): Any? =
        bfs(root, noteId, exact = true, samples = samples)
            ?: bfs(root, noteId, exact = false, samples = null)

    private fun bfs(root: Any, noteId: String, exact: Boolean, samples: MutableList<String>?): Any? {
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        var frontier = listOf(root)
        var depth = 0
        var visited = 0
        var fallback: Any? = null
        while (frontier.isNotEmpty() && depth <= MAX_DEPTH && visited < MAX_VISITED) {
            val next = ArrayList<Any>(frontier.size * 2)
            for (o in frontier) {
                if (visited >= MAX_VISITED) break
                if (o is String || o is Number || o is Boolean || o is Char) continue
                if (depth > 0 && !expandable(o.javaClass)) continue
                if (!seen.add(o)) continue
                visited++
                if (hasNoteId(o, noteId, exact)) {
                    if (imagesOf(o, 0).isNotEmpty()) return o
                    if (fallback == null) fallback = o
                }
                if (samples != null) sampleIds(o, samples)
                next.addAll(linksOf(o))
            }
            frontier = next
            depth++
        }
        return fallback
    }

    private fun hasNoteId(o: Any, noteId: String, exact: Boolean = true): Boolean {
        // 认全字段：宿主 note bean 字段数 100+，id 可能排在 120 之后（字段表已按 Class 缓存，扫全不贵）。
        for (f in fieldsOf(o.javaClass)) {
            if (f.type != String::class.java) continue
            val v = valueOf(f, o) as? String ?: continue
            if (v == noteId) return true
            if (!exact && v.length in 24..400 && v.contains(noteId)) return true
        }
        return false
    }

    /** 取证：记下对象图里出现的其它 24 位 id（同一个类只留一条，最多 3 条）。 */
    private fun sampleIds(o: Any, out: MutableList<String>) {
        if (out.size >= 3) return
        val simple = o.javaClass.simpleName
        if (out.any { it.startsWith("$simple.") }) return
        for (f in fieldsOf(o.javaClass, TRAVERSE_FIELDS)) {
            if (f.type != String::class.java) continue
            val v = valueOf(f, o) as? String ?: continue
            if (!HEX24.matches(v)) continue
            out.add("$simple.${f.name}=${v.take(8)}…")
            return
        }
    }

    /** 能继续往下走的对象引用：集合/数组元素 + 自定义类的字段值（框架类靠 [expandable] 挡掉）。
     *  模型类（Note/Feed/Detail/Bean）排在队头：同样的 visited 预算里先挖它们，少被外围对象吃掉。 */
    private fun linksOf(o: Any): List<Any> {
        val out = ArrayList<Any>()
        for (f in fieldsOf(o.javaClass, TRAVERSE_FIELDS)) {
            val v = valueOf(f, o) ?: continue
            if (v is String || v is Number || v is Boolean || v is Char) continue
            when {
                v is Collection<*> -> {
                    var n = 0
                    for (e in v) {
                        if (e == null || e is String) continue
                        out.add(e)
                        if (++n >= MAX_COLLECTION) break
                    }
                }
                v is Map<*, *> -> {
                    var n = 0
                    for (e in v.values) {
                        if (e == null || e is String) continue
                        out.add(e)
                        if (++n >= MAX_COLLECTION) break
                    }
                }
                v.javaClass.isArray && !v.javaClass.componentType!!.isPrimitive -> {
                    val arr = v as Array<*>
                    for (e in arr) {
                        if (e == null || e is String) continue
                        out.add(e)
                        if (out.size > MAX_VISITED) break
                    }
                }
                expandable(v.javaClass) -> out.add(v)
            }
        }
        if (out.size > 1) {
            val hot = ArrayList<Any>(out.size)
            val cold = ArrayList<Any>(out.size)
            for (e in out) if (modelish(e.javaClass)) hot.add(e) else cold.add(e)
            if (hot.isNotEmpty()) { hot.addAll(cold); return hot }
        }
        return out
    }

    /** 类名像业务模型（R8 后名字多半是 a1b，命中不了也无害，只是当个优先级的弱信号）。 */
    private fun modelish(c: Class<*>): Boolean {
        val n = c.simpleName
        return n.contains("Note") || n.contains("note") || n.contains("Feed") ||
            n.contains("Detail") || n.contains("Bean") || n.contains("Model")
    }

    /** 只展开「可能是业务模型/容器」的类：框架类（View/Context/Bitmap…）一律不碰。 */
    private fun expandable(c: Class<*>): Boolean {
        if (c.isPrimitive || c.isArray) return false
        val n = c.name
        return when {
            n.startsWith("java.") || n.startsWith("kotlin.") || n.startsWith("kotlinx.") -> false
            n.startsWith("android.") || n.startsWith("dalvik.") || n.startsWith("libcore.") -> false
            // androidx 只允许容器类（模型可能被 ViewModel/LiveData/Fragment 装着），View 体系整片跳过
            n.startsWith("androidx.") -> n.startsWith("androidx.lifecycle.") ||
                n.startsWith("androidx.collection.") || n.startsWith("androidx.fragment.")
            else -> true
        }
    }

    /** Class → 字段表：主线程每篇要扫几百个对象，`declaredFields` 反复分配+反射不划算（2026-11-24 审计）。 */
    private val fieldCache = java.util.concurrent.ConcurrentHashMap<Class<*>, Array<Field>>()

    /** 本类 + 自定义父类的字段（到框架基类就停：android.* 的字段既多又没意义）。 */
    private fun fieldsOf(c: Class<*>, cap: Int = MAX_FIELDS): List<Field> {
        val all = fieldCache[c] ?: buildFields(c).also { arr ->
            // 宿主进程里 Class 只增不减（每篇详情页都可能冒出新型），给个上限免得无界长
            if (fieldCache.size >= 256) fieldCache.clear()
            fieldCache[c] = arr
        }
        return if (all.size <= cap) all.asList() else all.asList().subList(0, cap)
    }

    private fun buildFields(c: Class<*>): Array<Field> {
        val out = ArrayList<Field>(64)
        var k: Class<*>? = c
        var guard = 0
        while (k != null && guard++ < 8) {
            val n = k.name
            if (k != c && (n.startsWith("android.") || n.startsWith("java.") || n.startsWith("kotlin."))) break
            runCatching {
                for (f in k.declaredFields) {
                    // 访问位只设一次：逐次 setAccessible 是 JNI 往返，十几个字段 × 几百个对象就是几万次
                    runCatching { f.isAccessible = true }
                    out.add(f)
                }
            }
            if (out.size >= MAX_FIELDS * 2) break
            k = k.superclass
        }
        return out.toTypedArray()
    }

    // ---- 抽图 ----

    /**
     * 图集直链。**只认第一个抽得出直链的字段**（宿主只有一个 imageList/images 字段），并跳过
     * user/comment/video 这类字段——原来把所有集合字段的 URL 合并，评论配图/头像会混进图集
     * （2026-11-24 审计：存错内容比失败更糟）。
     */
    private fun imagesOf(o: Any, depth: Int): List<String> {
        val fields = fieldsOf(o.javaClass)
        for (f in fields) {
            if (skipField(f.name)) continue
            val v = valueOf(f, o) ?: continue
            val out = LinkedHashSet<String>()
            collectUrls(v, out)
            if (out.isNotEmpty()) return out.toList()
        }
        if (depth >= 2) return emptyList()
        // 一层包装（note / noteData / data / item 之类）里可能才挂着 imageList
        for (f in fields) {
            if (skipField(f.name)) continue
            val v = valueOf(f, o) ?: continue
            if (v is Collection<*> || v is Map<*, *> || v.javaClass.isArray) continue
            if (!expandable(v.javaClass)) continue
            val got = imagesOf(v, depth + 1)
            if (got.isNotEmpty()) return got
        }
        return emptyList()
    }

    private fun skipField(name: String): Boolean {
        val n = name.lowercase()
        return SKIP_FIELD_PREFIXES.any { n.startsWith(it) }
    }

    private fun collectUrls(v: Any, out: MutableSet<String>) {
        val items: List<Any> = when {
            v is Collection<*> -> v.filterNotNull().take(MAX_COLLECTION)
            v is Map<*, *> -> v.values.filterNotNull().take(MAX_COLLECTION)
            v.javaClass.isArray && !v.javaClass.componentType!!.isPrimitive ->
                (v as Array<*>).filterNotNull().take(MAX_COLLECTION)
            else -> return
        }
        for (item in items) {
            if (item is String) {
                item.takeIf(::isCdnImage)?.let(out::add)
                continue
            }
            pickUrl(item)?.let(out::add)
        }
    }

    /**
     * 一个图集项 → 直链。认「像图片的 bean」（有 `urlDefault`/`urlPre`/`infoList`/`traceId`），
     * 避免把用户头像、作者封面这类带 `url` 的无关对象当图集（上游 `XhsNoteParser` 同款启发式）。
     */
    private fun pickUrl(item: Any): String? {
        val fields = fieldsOf(item.javaClass)
        fun has(name: String) = fields.any { it.name.equals(name, ignoreCase = true) }
        // 只有 `url` 的对象也认（有的宿主模型只给这一列），但要求它同时带尺寸信息，挡掉头像这类小图对象。
        val looksImage = has("urlDefault") || has("urlPre") || has("infoList") || has("traceId") ||
            (has("url") && (has("width") || has("height") || has("ratio")))
        if (!looksImage) return null
        for (name in URL_FIELDS) {
            stringField(item, name)?.let { if (isCdnImage(it)) return it }
        }
        val info = objectField(item, "infoList", "info_list") as? Collection<*> ?: return null
        val urls = info.filterNotNull().mapNotNull { variant ->
            val u = stringField(variant, "url")?.takeIf(::isCdnImage) ?: return@mapNotNull null
            val scene = stringField(variant, "imageScene", "scene") ?: ""
            scene to u
        }
        urls.minByOrNull { (scene, _) ->
            val i = SCENE_PREFERRED.indexOf(scene)
            if (i < 0) Int.MAX_VALUE else i
        }?.second?.let { return it }
        return urls.firstOrNull()?.second
    }

    /** 视频笔记：内存里通常只剩封面（`imageList` 首项 = cover），存下来就是错内容。 */
    private fun looksVideo(bean: Any): Boolean {
        if (stringField(bean, "type", "noteType")?.equals("video", ignoreCase = true) == true) return true
        if (booleanField(bean, "isVideo") == true) return true
        val v = objectField(bean, "video", "videoInfo", "noteVideo") ?: return false
        // 空容器不算视频：图文模型常带 `video: {}` / 空 List 占位，按老写法会把整篇误拒（2026-11-24 审计）
        return when (v) {
            is Map<*, *> -> v.isNotEmpty()
            is Collection<*> -> v.isNotEmpty()
            else -> true
        }
    }

    // ---- 反射小工具 ----

    private fun valueOf(f: Field, o: Any): Any? = try {
        // 字段缓存里已设过访问位；这里只是兜底（改过一次是 JNI 往返，不值得每次都走）
        if (!f.isAccessible) f.isAccessible = true
        f.get(o)
    } catch (_: Throwable) {
        null
    }

    private fun booleanField(o: Any, vararg names: String): Boolean? {
        val fields = fieldsOf(o.javaClass)
        for (n in names) {
            val f = fields.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: continue
            return valueOf(f, o) as? Boolean
        }
        return null
    }

    private fun stringField(o: Any, vararg names: String): String? {
        val fields = fieldsOf(o.javaClass)
        for (n in names) {
            val f = fields.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: continue
            val v = valueOf(f, o) as? String ?: continue
            if (v.isNotBlank()) return v
        }
        return null
    }

    private fun objectField(o: Any, vararg names: String): Any? {
        val fields = fieldsOf(o.javaClass)
        for (n in names) {
            val f = fields.firstOrNull { it.name.equals(n, ignoreCase = true) } ?: continue
            val v = valueOf(f, o) ?: continue
            if (v is String || v is Number || v is Boolean) continue
            return v
        }
        return null
    }

    /**
     * 只认小红书 CDN。下游 `XhsImageUrl.toBare` 对不适用的 URL 返回 null 后会**原样下载**，
     * 混进一张外域链接就是一张垃圾图（2026-11-24 审计）。视频域名（sns-video）在这里天然排除：
     * 图集直链不该是视频。
     */
    private fun isCdnImage(s: String): Boolean {
        if (!s.startsWith("http://") && !s.startsWith("https://")) return false
        val host = s.substringAfter("://")
            .substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            .lowercase()
        return host == "xhscdn.com" || host.endsWith(".xhscdn.com") ||
            host == "xiaohongshu.com" || host.endsWith(".xiaohongshu.com")
    }
}
