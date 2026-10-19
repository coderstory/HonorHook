package top.honorhook.xposed.target.xhs

import android.util.Log
import com.neoruaa.xhsdn.core.model.ResolvedNote
import com.neoruaa.xhsdn.data.xhs.DefaultXhsContentRepository
import io.github.libxposed.api.XposedModule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.honorhook.xposed.core.MountEngine
import java.util.concurrent.ConcurrentHashMap

/**
 * X5 保存管线适配层（2026-10-19 重构，X5 定案）：保存按钮 hook → 分享链接 → vendored 下载器管线。
 *
 * 自有代码只有本文件的适配：分享链接单链 + fetchHtml 注入 + ResolvedNote 交付。
 * 解析/选档/下载逻辑全部在 vendored `com.neoruaa.xhsdn.*`（XHS_Downloader_Android，整文件原样复制，
 * 见 app/app/src/main/kotlin/com/neoruaa/）——本文件不自创任何解析与选档语义。
 *
 * 分享链接**单链**（2026-10-19 拍板「地址就是分享链接，直接扔给下载器」；禁多轨叠补丁，前版
 * noteShares 直用/反射/剪贴板等多轨实现已删）：
 *  1. [XhsVideoSave.shareLinkOf]：noteShares 长链原文（okhttp 旁路采集 harvestNoteShare）
 *  2. [hostShortUrl]：反射调宿主 ShareService.getShortUrl(长链) → 短链（锚行
 *     "getShortUrlObservableIfNeed success"，u2c/y.java:70，9.50 实证）
 *  3. vendored [DefaultXhsContentRepository.resolve](短链)：UrlParser 规范化 + [expandShortUrl]
 *     302 展开（展开链自带 fresh xsec_token，2026-10-19 本地实证）+ vendored parser
 * 任一步失败：WARN + 记 [parsedEmptyNotes] + 返回 null（失败卡由 XhsBatchSave 弹；用户再点保存时
 * [availableShareLink] 对已记 noteId 返 null，放行宿主原生保存）。
 *
 * fetchHtml：HttpURLConnection GET，UA/头照抄 [XhsVideoSave.resolveOriginVideoUrl] 现有请求构造
 * （手机 UA 结尾 `xiaohongshu` 后缀实证必需），手动跟随 302（≤5 跳）。不用 vendored
 * OkHttpXhsPageSource（okhttp3 compileOnly，运行期不加载该类）。
 */
object XhsSavePivot {

    private const val JUDGE_PREFIX = "XhsHook·X5"

    /** 24hex noteId 门（防错归属；a59.q 实证 note_id 形态，XhsBatchSave 同款）。 */
    private val HEX24 = Regex("^[0-9a-f]{24}$")

    /** [expandShortUrl] 连接/读取超时（与 XhsVideoSave.RESOLVE_TIMEOUT_MS 同值，private 不跨类）。 */
    private const val RESOLVE_TIMEOUT_MS = 15_000

    /**
     * 解析失败/全空的 noteId（2026-10-19 单链定案）：[resolveByShareLink] 任一步失败记入，消费方 =
     * [availableShareLink]——命中返 null，XhsBatchSave 两分支既有回落路放行宿主原生保存。
     * 内存态（随进程消失），本会话有效即可。（val 引用不可 @Volatile——Kotlin 禁；
     * 集合为 ConcurrentHashMap 包装，自身已线程安全。）
     */
    internal val parsedEmptyNotes: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap())

    /** 最近一次 fetchHtml 诊断（url 前 80 字符 + body 长度；-1 = null），供「媒体全空」行回溯。 */
    @Volatile private var lastFetchDiag: String = "无"

    /** hook 现场捕获的 NoteFeed 实例（[stashFeed]；单链定案后仅落表，暂无消费方）。 */
    @Volatile private var stashedFeed: Any? = null

    /** XhsBatchSave 两个点击轨在 proceed 前把现场拿到的 NoteFeed 存进来（调用点保留）。 */
    fun stashFeed(feed: Any?) {
        stashedFeed = feed
    }

    /**
     * kotlin.Lazy 包装解包（ji9/c.java:201 `this.m = Lazy...lazy(new ji9.b(this))` 属性委托实证——
     * NoteFeed 依赖装在 UnsafeLazyImpl 内部，不拆包预算内够不到）。
     * 接口判定 `obj is Lazy<*>` 在模块/宿主双 stdlib（不同 Class 对象）下 miss，兜：类图上任一接口
     * 名 == "kotlin.Lazy" 再反射 getValue()。非 Lazy / 解包失败返回 null（走原字段下钻兜底）。
     */
    private fun unwrapLazy(obj: Any): Any? {
        if (obj is Lazy<*>) return runCatching { obj.value }.getOrNull()
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            if (c.interfaces.any { it.name == "kotlin.Lazy" }) {
                return runCatching {
                    obj.javaClass.getMethod("getValue").apply { isAccessible = true }.invoke(obj)
                }.getOrNull()
            }
            c = c.superclass
        }
        return null
    }

    /**
     * 从若干 hook 现场根对象（[roots]：thisObject **和** invoke 入参——ji9/b.java:55 链路 =
     * `cVar.Ta().u0()`，cVar 是入参 controller，只走 thisObject 字段图会 miss，2026-10-19 真机实证）
     * 向对象图下钻（≤5 层，IdentityHashMap 防环，跳过 java.* 与基本类型；kotlin.Lazy 包装解包后同层），
     * 找第一个带无参 String
     * `u0` 方法的对象 = NoteFeed（a59/q.java:53 `saveVideo(noteFeed.u0())`、ji9/b.java:55
     * `cVar.Ta().u0()` 双实证——u0 是 9.50 混淆名，随宿主版本漂移）。
     * 返回 (feed 实例, noteId)；noteId 过 24hex 门。全 miss：WARN 诊断（各根 + 首层字段类名，
     * 截 200 字符）并返回 null。
     */
    fun findFeedAndNoteId(module: XposedModule, vararg roots: Any?): Pair<Any, String>? {
        val rootList = roots.filterNotNull()
        val queue = ArrayDeque<Pair<Any, Int>>()   // 节点 + 层深（根 = 0）
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())
        rootList.forEach { queue.addLast(it to 0) }
        var visited = 0
        while (queue.isNotEmpty() && visited < 64) {
            val (node, depth) = queue.removeFirst()
            if (!seen.add(node)) continue
            visited++
            // Lazy 包装：解包值同层入队（也过 u0 门、也下钻其字段），包装体内部字段不再下钻
            val inner = unwrapLazy(node)
            if (inner != null) {
                queue.addLast(inner to depth)
                continue
            }
            // 本节点直接是 NoteFeed？（a59.q：feed 是 lambda 捕获字段，一层即中）
            val noteId = runCatching {
                val m = node.javaClass.declaredMethods.firstOrNull {
                    it.name == "u0" && it.parameterCount == 0 && it.returnType == String::class.java
                }
                m?.isAccessible = true
                (m?.invoke(node) as? String)?.takeIf { it.matches(HEX24) }
            }.getOrNull()
            if (noteId != null) return node to noteId
            // 下钻字段值（root → controller → feed；ji9.b 入参链路 arg → Ta() 两层）
            if (depth >= 5 || node.javaClass.name.startsWith("java.")) continue
            val fields = runCatching { node.javaClass.declaredFields }.getOrNull() ?: continue
            for (f in fields) {
                if (f.type.isPrimitive) continue
                val v = runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue
                if (!v.javaClass.name.startsWith("java.")) queue.addLast(v to depth + 1)
            }
        }
        // 诊断仪表：miss 时打根与首层字段类名清单，下次 miss 一眼定位断点
        val diag = buildString {
            append("roots=[")
            append(rootList.joinToString(", ") { it.javaClass.name.substringAfterLast('.') })
            append("] L1=[")
            append(rootList.flatMap { r ->
                runCatching { r.javaClass.declaredFields }.getOrDefault(emptyArray())
                    .mapNotNull { f ->
                        runCatching { f.isAccessible = true; f.get(r) }.getOrNull()
                            ?.javaClass?.name?.substringAfterLast('.')
                    }
            }.distinct().joinToString(", "))
            append("]")
        }
        module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX u0 下钻 miss 诊断：${diag.take(200)}")
        return null
    }

    /**
     * 接管判定（hook 线程同步、全内存、无网络）：解析失败/全空的 noteId 已被 [parsedEmptyNotes] 拉黑
     * → 返 null，XhsBatchSave 两分支既有回落路放行宿主原生保存；否则看 noteShares 长链可得性
     * （[XhsVideoSave.shareLinkOf]）。签名对齐旧版（module 留参）。
     */
    fun availableShareLink(noteId: String, module: XposedModule): String? {
        if (noteId in parsedEmptyNotes) return null
        return XhsVideoSave.shareLinkOf(noteId)
    }

    /**
     * 单链解析（2026-10-19 定案）：长链 → 宿主 getShortUrl 短链 → vendored resolve。IO 在
     * Dispatchers.IO（vendored repository 默认 dispatcher）。任何失败（无长链 / getShortUrl 失败 /
     * 抓取 / 解析异常 / 图视频 live 全空）= WARN + 记 [parsedEmptyNotes] + 返 null——失败卡由
     * XhsBatchSave 弹，下次点击 [availableShareLink] 返 null 放行宿主。
     */
    suspend fun resolveByShareLink(noteId: String, module: XposedModule): ResolvedNote? = withContext(Dispatchers.IO) {
        val longUrl = XhsVideoSave.shareLinkOf(noteId) ?: run {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 无 share link noteId=$noteId，解析跳过")
            parsedEmptyNotes.add(noteId)
            return@withContext null
        }
        val shortUrl = hostShortUrl(longUrl, module) ?: run {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX getShortUrl 失败 noteId=$noteId")
            parsedEmptyNotes.add(noteId)
            return@withContext null
        }
        try {
            val note = repository(module).resolve(shortUrl)
            if (note.videos.isEmpty() && note.images.isEmpty() && note.livePhotos.isEmpty()) {
                module.log(Log.WARN, MountEngine.TAG,
                    "$JUDGE_PREFIX 解析成功但图/视频/live 全空 noteId=$noteId $lastFetchDiag")
                parsedEmptyNotes.add(noteId)
                return@withContext null
            }
            // 可下 N = 图片分支合并后 URL 数（images + livePhotos 静帧原图；videos 图片分支不消费，
            // 归 X4 HD 询问）。旧「候选」= 视频 candidates 总数，对纯实况图集恒 0、误判「无可用」时
            // 无诊断价值（2026-10-19 实况图集实证），改打可直接下载的文件数。
            val downloadable = note.images.size + note.livePhotos.size
            module.log(Log.INFO, MountEngine.TAG,
                "$JUDGE_PREFIX 分享链接解析成功 noteId=$noteId 图${note.images.size} 视频${note.videos.size} live${note.livePhotos.size} → 可下 $downloadable 个文件")
            note
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            val detail = t.cause?.run { "${javaClass.simpleName}: $message" } ?: t.message
            module.log(Log.WARN, MountEngine.TAG,
                "$JUDGE_PREFIX 分享链接解析异常 noteId=$noteId ${t.javaClass.simpleName}: $detail")
            parsedEmptyNotes.add(noteId)
            null
        }
    }

    /**
     * 宿主 ShareService.getShortUrl 直调（单链第 2 步，全程反射）：i3c.d.Companion（服务定位器）
     * `e(Class)` 取 ShareService 实例（u2c/y.java:51 锚）→ `getShortUrl(长链)` 返回宿主 loader 的
     * io.reactivex.Observable → 反射 `timeout(3, SECONDS)`（TimeUnit 是 JDK 类，安全引用）→
     * `blockingFirst(default)` 同步收口发射值（NoSuchMethod 记 WARN 后试 `blockingLatest()`
     * Iterable 取首元素）→ q1c.q 包装 `a()` 取 short_url（u2c/y.java:70 `qVar.a()`、
     * q1c/q.java:10 `public final String a()` 9.50 实证；String 直发形态防御兼容）。锚行
     * "getShortUrlObservableIfNeed success"（u2c/y.java:70，9.50 实证）。
     *
     * 2026-10-19 19:34 真机 CNFE 实证：模块经 Proxy/lambda 实装 io.reactivex.functions.Consumer
     * 订阅 → 接口类型须由定义方（模块）classloader 解析而 rx 类在宿主 → 必炸
     * ClassNotFoundException。约束：本函数**全程反射、模块代码零 rx 类型**——Observable 全程仅持
     * Object，不 import / 不引用任何 io.reactivex.*，blocking 算子在宿主侧收口发射值。
     * 任何一步失败 runCatching 收口返 null（异常类名进 WARN，调用方记 [parsedEmptyNotes]）。
     */
    private fun hostShortUrl(longUrl: String, module: XposedModule): String? = runCatching {
        val loader = XhsVideoSave.hostLoaderOrThrow()
        val serviceCls = Class.forName("com.xingin.sharesdk.api.ShareService", true, loader)
        val companion = Class.forName("i3c.d", true, loader).getDeclaredField("Companion").get(null)
        val e = companion.javaClass.methods.first {
            it.name == "e" && it.parameterCount == 1 && it.parameterTypes[0] == Class::class.java
        }
        e.isAccessible = true
        val service = e.invoke(companion, serviceCls)
        val getShortUrl = serviceCls.methods.first { it.name == "getShortUrl" && it.parameterCount == 1 }
        getShortUrl.isAccessible = true
        val observable = getShortUrl.invoke(service, longUrl) ?: return@runCatching null
        // 3s 超时：timeout(long, TimeUnit) 返回同型 Observable（仍 Object 持有，不落 rx 类型）
        val timed = runCatching {
            observable.javaClass
                .getMethod("timeout", java.lang.Long.TYPE, java.util.concurrent.TimeUnit::class.java)
                .apply { isAccessible = true }
                .invoke(observable, 3L, java.util.concurrent.TimeUnit.SECONDS)
        }.getOrElse { ex ->
            if (ex !is NoSuchMethodException) throw ex
            module.log(Log.WARN, MountEngine.TAG,
                "$JUDGE_PREFIX getShortUrl timeout(long,TimeUnit) 反射缺失，无超时直取: ${ex.message}")
            observable
        }
        // 同步收口：优先 blockingFirst(default)（空流返 default）；缺方法退 blockingLatest() 取首元素
        val emitted = runCatching {
            timed.javaClass.getMethod("blockingFirst", Any::class.java)
                .apply { isAccessible = true }
                .invoke(timed, null as Any?)
        }.getOrElse { ex ->
            if (ex !is NoSuchMethodException) throw ex
            module.log(Log.WARN, MountEngine.TAG,
                "$JUDGE_PREFIX getShortUrl blockingFirst(default) 缺失，退 blockingLatest: ${ex.message}")
            val iter = (timed.javaClass.getMethod("blockingLatest").apply { isAccessible = true }
                .invoke(timed) as? Iterable<*>)?.iterator()
            if (iter != null && iter.hasNext()) iter.next() else null
        }
        if (emitted == null) return@runCatching null
        // 发射值 = q1c.q 包装（u2c/y.java:70 qVar.a()）；String 直发形态防御兼容
        if (emitted is String) return@runCatching emitted
        val a = emitted.javaClass.methods.firstOrNull {
            it.name == "a" && it.parameterCount == 0 && it.returnType == String::class.java
        } ?: return@runCatching null
        a.isAccessible = true
        a.invoke(emitted) as? String
    }.onFailure {
        module.log(Log.WARN, MountEngine.TAG,
            "$JUDGE_PREFIX getShortUrl 异常 ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * xhslink 短链 302 展开（注入 vendored [DefaultXhsContentRepository.resolveShortUrl] 的实现，
     * 单链第 3 步）：手动跟随 ≤5 跳取最终 URL（不读 body），连接构造与 [XhsVideoSave.fetchNotePageHtml]
     * 同款（手机 UA）；展开链自带 fresh xsec_token（2026-10-19 本地实证）。任何失败返 null
     * （vendored 侧回落短链原样进 fetchHtml，由其手动 302 兜底）。
     */
    private fun expandShortUrl(startUrl: String): String? {
        var u = startUrl
        var redirects = 0
        while (true) {
            val conn = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = RESOLVE_TIMEOUT_MS
            conn.readTimeout = RESOLVE_TIMEOUT_MS
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", XhsVideoSave.XHS_WEB_UA)
            try {
                when (val code = conn.responseCode) {
                    in 300..399 -> {
                        if (++redirects > 5) return null
                        u = conn.getHeaderField("Location") ?: return null
                    }
                    200 -> return u
                    else -> return null
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * 注入给 vendored repository 的抓取实现（[DefaultXhsContentRepository.fetchHtml]）。
     * 连接/请求头与 [XhsVideoSave.fetchNotePageHtml] 同一构造（手机 UA + Accept + 手动 302 ≤5 跳，
     * xhslink 短链在此展开）；失败抛异常交 vendored 侧归类（XhsResolveException.Network）。
     * vendored 侧把 fetch null/空与「解析出但无 noteId」同归 RequiresWebView，分不开——
     * 故在注入 lambda 处就地打 fetch 空/异常 WARN（url 前 80 字符 + body 长度/异常 message），
     * 并记 [lastFetchDiag] 供「媒体全空」行回溯。无状态 facade，按次构建。
     * resolveShortUrl 注入 [expandShortUrl]：vendored resolve 对 xhslink 短链先经此 canonical 化
     * （UrlParser 规范化设计路），展开链自带 fresh token 后 parser 才拿得到 expectedNoteId。
     */
    private fun repository(module: XposedModule): DefaultXhsContentRepository =
        DefaultXhsContentRepository(
            fetchHtml = { url ->
                withContext(Dispatchers.IO) {
                    try {
                        XhsVideoSave.fetchNotePageHtml(url).also { body ->
                            lastFetchDiag = "url=${url.take(80)} bodyLen=${body?.length ?: -1}"
                            if (body.isNullOrEmpty()) {
                                module.log(Log.WARN, MountEngine.TAG,
                                    "$JUDGE_PREFIX fetchHtml 返回 null/空 url=${url.take(80)} bodyLen=-1")
                            }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (t: Throwable) {
                        module.log(Log.WARN, MountEngine.TAG,
                            "$JUDGE_PREFIX fetchHtml 抛异常 url=${url.take(80)}: ${t.javaClass.simpleName}: ${t.message}")
                        throw t
                    }
                }
            },
            resolveShortUrl = { url -> expandShortUrl(url) },
        )
}
