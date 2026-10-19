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
 * 自有代码只有本文件的适配：分享链接三轨收集 + fetchHtml 注入 + ResolvedNote 交付。
 * 解析/选档/下载逻辑全部在 vendored `com.neoruaa.xhsdn.*`（XHS_Downloader_Android，整文件原样复制，
 * 见 app/app/src/main/kotlin/com/neoruaa/）——本文件不自创任何解析与选档语义。
 *
 * 分享链接三轨（noteId → 含 xsec_token 的移动版分享页 URL）：
 *  ① [XhsVideoSave.noteShares]（okhttp 旁路采集 harvestNoteShare，kept）
 *  ② miss 时反射 NoteFeed.share_info：对 hook 现场拿到（[stashFeed]）的 NoteFeed 实例遍历
 *     declaredFields，用宿主 gson 别名注解 `kd.c` 定位——`field.getAnnotation` 找注解 value==
 *     "share_info" 的字段（注解值不混淆，gsa/i.java:139 实证）；拿到 share_info 对象后同法找
 *     "link" 与 "xsec_token"（真实字段名可能混淆，按注解值匹配）
 *  ③ 皆 miss → resolveByShareLink 返回 null + WARN
 *
 * fetchHtml：HttpURLConnection GET，UA/头照抄 [XhsVideoSave.resolveOriginVideoUrl] 现有请求构造
 * （手机 UA 结尾 `xiaohongshu` 后缀实证必需），手动跟随 302（≤5 跳，xhslink 短链即经此展开）。
 * 不用 vendored OkHttpXhsPageSource（okhttp3 compileOnly，运行期不加载该类）。
 */
object XhsSavePivot {

    private const val JUDGE_PREFIX = "XhsHook·X5"

    /** 24hex noteId 门（防错归属；a59.q 实证 note_id 形态，XhsBatchSave 同款）。 */
    private val HEX24 = Regex("^[0-9a-f]{24}$")

    /** link 缺失时按 token 重建（与 XhsVideoSave.resolveOriginVideoUrl 同一拼法，xsec_source=app_share 实证必需）。 */
    private fun shareUrlOf(noteId: String, token: String): String =
        "https://www.xiaohongshu.com/discovery/item/$noteId?xsec_source=app_share&xsec_token=$token"

    /** 解析出的分享链接 memo（noteId → URL；hit 免重复反射/免重复落网页）。 */
    private val linkCache = ConcurrentHashMap<String, String>()

    /** hook 现场捕获的 NoteFeed 实例（[stashFeed]；只服务当次保存的反射轨，随下次保存覆盖）。 */
    @Volatile private var stashedFeed: Any? = null

    /** XhsBatchSave 两个点击轨在 proceed 前把现场拿到的 NoteFeed 存进来（反射轨输入）。 */
    fun stashFeed(feed: Any?) {
        stashedFeed = feed
    }

    /** 宿主 gson 别名注解类 kd.c（运行期宿主 classpath，模块编译期无此类 ⇒ 只能反射拿）。 */
    private val aliasAnnotation: Class<out Annotation>? by lazy {
        runCatching {
            XhsVideoSave.hostLoaderOrThrow().loadClass("kd.c").asSubclass(Annotation::class.java)
        }.getOrNull()
    }

    /**
     * 在 [obj] 的 declaredFields 里按 `kd.c` 注解 value==[alias] 找第一个字段值。
     * 字段真实名可能混淆，注解值不混淆（gsa/i.java:139 实证）；任何反射失败静默跳过该字段。
     */
    private fun fieldByAlias(obj: Any, alias: String): Any? {
        val annCls = aliasAnnotation ?: return null
        val fields = runCatching { obj.javaClass.declaredFields }.getOrNull() ?: return null
        for (f in fields) {
            val ann = runCatching { f.getAnnotation(annCls) }.getOrNull() ?: continue
            // kotlin.Annotation 无 annotationType 成员——走 java.lang.reflect 口
            val value = runCatching {
                val type = (ann as java.lang.annotation.Annotation).annotationType()
                type.getMethod("value").invoke(ann) as? String
            }.getOrNull()
            if (value != alias) continue
            f.isAccessible = true
            return runCatching { f.get(obj) }.getOrNull()
        }
        return null
    }

    /**
     * 从 [root]（hook 现场对象：a59.q / ji9.b lambda）向对象图下钻（≤3 层，跳过 java.* 与基本类型），
     * 找第一个带无参 String `u0` 方法的对象 = NoteFeed（a59/q.java:53 `saveVideo(noteFeed.u0())`、
     * ji9/b.java:55 `cVar.Ta().u0()` 双实证——u0 是 9.50 混淆名，随宿主版本漂移）。
     * 返回 (feed 实例, noteId)；noteId 过 24hex 门。任一环节失败返回 null。
     */
    fun findFeedAndNoteId(root: Any?): Pair<Any, String>? {
        if (root == null) return null
        val queue = ArrayDeque<Any>()
        queue.addLast(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 32) {
            val node = queue.removeFirst()
            visited++
            // 本节点直接是 NoteFeed？（a59.q：feed 是 lambda 捕获字段，一层即中）
            val noteId = runCatching {
                val m = node.javaClass.declaredMethods.firstOrNull {
                    it.name == "u0" && it.parameterCount == 0 && it.returnType == String::class.java
                }
                m?.isAccessible = true
                (m?.invoke(node) as? String)?.takeIf { it.matches(HEX24) }
            }.getOrNull()
            if (noteId != null) return node to noteId
            // 下钻字段值（lambda → controller → feed 的链路，ji9.b 要两层）
            if (node.javaClass.name.startsWith("java.")) continue
            val fields = runCatching { node.javaClass.declaredFields }.getOrNull() ?: continue
            for (f in fields) {
                if (f.type.isPrimitive) continue
                val v = runCatching { f.isAccessible = true; f.get(node) }.getOrNull() ?: continue
                if (!v.javaClass.name.startsWith("java.")) queue.addLast(v)
            }
        }
        return null
    }

    /**
     * 内存三轨取分享链接（不发网络）：缓存 → noteShares → NoteFeed 反射。null = 三轨皆 miss。
     * 供点击轨在 hook 线程**同步**做接管判定（全内存、无阻塞），真正的网页解析走 [resolveByShareLink]。
     */
    fun availableShareLink(noteId: String, module: XposedModule): String? {
        linkCache[noteId]?.let { return it }
        // ① okhttp 旁路采集表
        XhsVideoSave.noteShares[noteId]?.let { share ->
            val link = share.link ?: share.token?.let { shareUrlOf(noteId, it) }
            if (link != null) {
                linkCache[noteId] = link
                return link
            }
        }
        // ② NoteFeed 反射：share_info（kd.c("share_info")）→ link / xsec_token（kd.c 同法）
        val feed = stashedFeed ?: return null
        val shareInfo = fieldByAlias(feed, "share_info") ?: run {
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 反射轨 miss：NoteFeed 无 share_info 字段 noteId=$noteId")
            return null
        }
        val link = (fieldByAlias(shareInfo, "link") as? String)
            ?.takeIf { it.contains("xiaohongshu.com") && it.contains("xsec_token=") }
        if (link != null) {
            linkCache[noteId] = link
            return link
        }
        val token = fieldByAlias(shareInfo, "xsec_token") as? String
        if (!token.isNullOrBlank()) {
            val rebuilt = shareUrlOf(noteId, token)
            linkCache[noteId] = rebuilt
            return rebuilt
        }
        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 反射轨 miss：share_info 无 link/xsec_token noteId=$noteId")
        return null
    }

    /**
     * 分享链接 → vendored [DefaultXhsContentRepository.resolve] → [ResolvedNote]。
     * 任何失败（链接不可得 / 抓取 / 解析 / 无笔记）返回 null：链接 miss WARN（三轨皆尽），
     * 解析失败 WARN 带原因。IO 在 Dispatchers.IO（vendored repository 默认 dispatcher）。
     */
    suspend fun resolveByShareLink(noteId: String, module: XposedModule): ResolvedNote? = withContext(Dispatchers.IO) {
        val link = availableShareLink(noteId, module) ?: run {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 分享链接三轨皆 miss noteId=$noteId，解析跳过")
            return@withContext null
        }
        try {
            val note = repository.resolve(link)
            module.log(Log.INFO, MountEngine.TAG,
                "$JUDGE_PREFIX 分享链接解析成功 noteId=$noteId 图${note.images.size} 视频${note.videos.size} live${note.livePhotos.size}")
            note
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX 分享链接解析失败 noteId=$noteId: ${t.message}")
            null
        }
    }

    /**
     * 注入给 vendored repository 的抓取实现（[DefaultXhsContentRepository.fetchHtml]）。
     * 连接/请求头与 [XhsVideoSave.fetchNotePageHtml] 同一构造（手机 UA + Accept + 手动 302 ≤5 跳，
     * xhslink 短链在此展开）；失败抛异常交 vendored 侧归类（XhsResolveException.Network）。
     */
    private val repository: DefaultXhsContentRepository by lazy {
        DefaultXhsContentRepository(
            fetchHtml = { url -> withContext(Dispatchers.IO) { XhsVideoSave.fetchNotePageHtml(url) } },
        )
    }
}
