package top.honorhook.ui.xhsbatch

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** 诊断文件日志：本 ROM logcat 丢弃 App 自有 tag（见 XhsLoginLog 同因），抓取流程落文件取证 */
internal object XhsFetchLog {
    // JVM 单测无 Android 框架：Environment 调用抛 not-mocked，lazy+runCatching 使对象初始化与写日志双双无副作用
    private val f: java.io.File? by lazy {
        runCatching {
            java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS),
                "xhs_login_debug.log",
            )
        }.getOrNull()
    }
    fun w(msg: String) {
        val file = f ?: return
        runCatching { file.appendText("${System.currentTimeMillis()} fetch: $msg\n") }
    }
}

/** 抓取口：Controller 只依赖接口，JVM 单测用 fake（不碰 WebView）。 */
interface XhsFeedClient {
    /** [context] 允许为 null 仅为 JVM 单测缝（真实现 requireNotNull）。 */
    suspend fun fetchNotes(
        context: Context?,
        userId: String,
        onProgress: (Int) -> Unit = {},
    ): List<XhsUserFeedClient.FeedNote>

    fun currentCookie(): String

    companion object {
        /**
         * 登录探活：匿名访客也有 web_session cookie（实测 root 读 cookie 库实锤），
         * 不能拿 cookie 名判断；改调 /user/self，响应含 nickname 才算真登录。
         * 网络失败返回 null（未知），调用方自行回退 cookie 判定。
         */
        fun isWebLoggedInBlocking(cookie: String?): Boolean? {
            if (cookie.isNullOrBlank()) return false
            var conn: java.net.HttpURLConnection? = null
            return try {
                conn = java.net.URL("https://www.xiaohongshu.com/api/sns/web/v1/user/self")
                    .openConnection() as java.net.HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("Cookie", cookie)
                conn.setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                )
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                text.contains("nickname")
            } catch (_: Throwable) {
                null
            } finally {
                conn?.disconnect()
            }
        }
    }
}

/**
 * 用户笔记列表抓取（技术方案 §2.1）：离屏 WebView 主路线 + __INITIAL_STATE__ 正则兜底。
 *
 * 主路线 = 在不可见 WebView 里加载 profile 页，页面自身 JS 负责 x-s/x-t 签名发 edith 请求；
 * shouldInterceptRequest 拦 /api/sns/web/v1/user_posted 响应取 JSON（读出 body 后回灌替换，
 * 页面不感知）；翻页 = evaluateJavascript 注入滚动，注入频率即节流闸门。
 * 我们全程不碰签名、不自己发 edith 请求。
 */
class XhsUserFeedClient : XhsFeedClient {

    data class FeedNote(val noteId: String, val xsecToken: String)

    class FeedException(message: String) : Exception(message)

    /**
     * [onProgress] 在每页数据落地后回调（UI 显示「已抓到 N 篇」）。
     * 返回抓到的笔记列表（去重保序）。失败抛 [FeedException]（兜底也无果时）。
     */
    override suspend fun fetchNotes(
        context: Context?,
        userId: String,
        onProgress: (Int) -> Unit,
    ): List<FeedNote> = withContext(Dispatchers.Main) {
        val context = requireNotNull(context) { "fetchNotes 需要 Context（WebView 宿主）" }
        // 输入两种形态：24 位 hex 用户 id 直用；小红书号（如 moranmoai00）先经搜索页解析
        //（2026-11-10 实测：profile 路由只认 hex id，red_id 直填被重定向到登录验证页）
        val hexId = if (userId.matches(Regex("[0-9a-fA-F]{24}"))) userId else resolveUserId(context, userId)
        XhsFetchLog.w("hexId=$hexId (raw=$userId)")
        val collected = ConcurrentLinkedQueue<FeedNote>()
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val hasMore = AtomicBoolean(true)
        val pageArrivals = AtomicInteger(0)
        val fatal = AtomicBoolean(false)
        var webView: WebView? = null

        try {
            webView = makeWebView(context, collected, seen, hasMore, pageArrivals, fatal)
            XhsFetchLog.w("loading profile url")
            webView.loadUrl("https://www.xiaohongshu.com/user/profile/$hexId")

            // 首屏等待：FIRST_PAGE_TIMEOUT_MS 内无拦截命中 → 兜底。
            // 等待期间就周期性滚动——首屏笔记是 HTML 预渲染，user_posted 必须滚动才发（2026-11-10 实测死锁根因）
            val firstDeadline = System.currentTimeMillis() + XhsBatchPacing.FIRST_PAGE_TIMEOUT_MS
            while (collected.isEmpty() && System.currentTimeMillis() < firstDeadline && !fatal.get()) {
                delay(2500)
                webView.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight)", null)
            }
            if (collected.isEmpty()) {
                XhsFetchLog.w("webview phase done")
                val fallback = fetchInitialStateFallback(hexId)
                XhsFetchLog.w("fallback notes=${fallback.size}")
                if (fallback.isEmpty()) throw FeedException("列表抓取超时且兜底无果（可能需要网页登录）")
                onProgress(fallback.size)
                return@withContext fallback
            }

            // 翻页循环：注入滚动触发页面自身签名请求；连续两页无新增 = 到底/被拦，停
            var stalePages = 0
            while (collected.size < XhsBatchPacing.MAX_NOTES && hasMore.get() && stalePages < 2 && !fatal.get()) {
                onProgress(collected.size)
                delay(XhsBatchPacing.jitter(XhsBatchPacing.PAGE_DELAY_MIN_MS, XhsBatchPacing.PAGE_DELAY_MAX_MS))
                val before = collected.size
                val beforeArrivals = pageArrivals.get()
                webView.evaluateJavascript("window.scrollTo(0, document.body.scrollHeight)", null)
                val pageDeadline = System.currentTimeMillis() + 12_000
                while (pageArrivals.get() == beforeArrivals && System.currentTimeMillis() < pageDeadline && !fatal.get()) {
                    delay(200)
                }
                stalePages = if (collected.size > before) 0 else stalePages + 1
            }
            onProgress(collected.size)
            collected.toList()
        } finally {
            // WebView 必须主线程销毁；本协程就在 Main dispatcher 上
            webView?.apply {
                stopLoading()
                destroy()
            }
        }
    }


    /**
     * 小红书号 → 24 位 hex 用户 id：离屏 WebView（桌面 UA，移动 UA 下搜索页直接 404，
     * 2026-11-10 实测）加载用户搜索页，页面自身 JS 发 usersearch 请求（带签名），
     * shouldInterceptRequest 拦响应取 users[]。优先精确匹配 red_id，退而取第一条。
     * 搜索结果强制网页登录（匿名弹扫码），未登录时超时后抛 FeedException 引导用户先登录。
     */
    private suspend fun resolveUserId(context: Context, redId: String): String =
        withContext(Dispatchers.Main) {
            val deferred = kotlinx.coroutines.CompletableDeferred<String>()
            val wv = WebView(context).apply {
                visibility = android.view.View.GONE
                layout(0, 0, 1080, 1920)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString =
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
                webViewClient = object : WebViewClient() {
                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val u = request?.url?.toString() ?: return super.shouldOverrideUrlLoading(view, request)
                        // 小红书搜索页会重写 https→http，明文被禁 = 白屏（XhsLoginOverlay 同坑同修）
                        if (u.startsWith("http://")) {
                            val upgraded = "https://" + u.removePrefix("http://")
                            XhsFetchLog.w("resolve nav upgrade: $upgraded")
                            view?.loadUrl(upgraded)
                            return true
                        }
                        XhsFetchLog.w("resolve nav: $u")
                        return super.shouldOverrideUrlLoading(view, request)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onPageFinished(view: WebView?, url: String?) {
                        XhsFetchLog.w("resolve finished: $url")
                        view?.evaluateJavascript(domHexJs(redId), null)
                        view?.evaluateJavascript(domHexCardJs(redId)) { r ->
                            val hex = r?.trim('"', ' ') ?: ""
                            XhsFetchLog.w("resolve dom hex='$hex'")
                            if (hex.isNotEmpty()) deferred.complete(hex)
                        }
                        super.onPageFinished(view, url)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        val url = request?.url?.toString() ?: return null
                        if (!url.contains("/api/sns/web/v1/search")) return null
                        XhsFetchLog.w("search api intercept: $url")
                        if (!url.contains("/api/sns/web/v1/search/usersearch")) return null
                        return try {
                            val conn = URL(url).openConnection() as HttpURLConnection
                            conn.connectTimeout = 10_000
                            conn.readTimeout = 10_000
                            request.requestHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                            XhsFetchLog.w("usersearch resp=${conn.responseCode}")
                            if (conn.responseCode !in 200..299) return null
                            val body = conn.inputStream.use { it.readBytes() }
                            val parsed = parseUserSearchJson(String(body), redId)
                            XhsFetchLog.w("usersearch parsed=$parsed")
                            parsed?.let { deferred.complete(it) }
                            val mime = conn.contentType?.substringBefore(';') ?: "application/json"
                            WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
                        } catch (t: Throwable) {
                            XhsFetchLog.w("usersearch intercept err=$t")
                            null
                        }
                    }
                }
            }
            XhsFetchLog.w("resolveUserId begin redId=$redId")
            try {
                wv.loadUrl(
                    "https://www.xiaohongshu.com/search_result?keyword=" +
                        java.net.URLEncoder.encode(redId, "UTF-8") +
                        "&source=web_search_result_note&type=54",
                )
                var polled = 0
                withTimeout(20_000) {
                    while (polled < 9 && !deferred.isCompleted) {
                        kotlinx.coroutines.delay(2_000)
                        wv.evaluateJavascript(domHexCardJs(redId)) { r ->
                            val hex = r?.trim('"', ' ') ?: ""
                            if (hex.isNotEmpty()) deferred.complete(hex)
                        }
                        polled++
                    }
                    deferred.await()
                }
            } catch (e: TimeoutCancellationException) {
                XhsFetchLog.w("resolveUserId timeout")
                throw FeedException(
                    "小红书号「$redId」解析超时：用户搜索需要网页登录态，" +
                        "请先用浏览器打开 xiaohongshu.com 登录后重试（或直接输入 24 位用户 ID）",
                )
            } finally {
                XhsFetchLog.w("resolveUserId finally")
                wv.stopLoading()
                wv.destroy()
            }
        }

    /** 旧法：页面上第一条 profile 链接——搜索页里那是**笔记作者**，会抓错人，只作日志对照 */
    private fun domHexJs(redId: String): String =
        "(function(){var k='" + redId + "';var as=document.querySelectorAll('a[href]');" +
            "for(var i=0;i<as.length;i++){var m=(as[i].getAttribute('href')||'')" +
            ".match(/user\\u002Fprofile\\u002F([0-9a-f]{24})/);" +
            "if(m)return m[1];}return '';})()"

    /**
     * 正法：找 innerText 含「小红书号：<redId>」的用户卡片（a 或卡片内 a），取其 profile hex。
     * 2026-11-10 实测：搜索页第一条 profile 链接是笔记作者的，直接取必错。
     */
    private fun domHexCardJs(redId: String): String =
        "(function(){var k='小红书号：" + redId + "';var as=document.querySelectorAll('a[href]');" +
            "for(var i=0;i<as.length;i++){var t=as[i].innerText||'';" +
            "if(t.indexOf(k)>=0){var m=(as[i].getAttribute('href')||'')" +
            ".match(/user\\u002Fprofile\\u002F([0-9a-f]{24})/);" +
            "if(m)return m[1];}" +
            "var cs=as[i].querySelectorAll('a[href]');for(var j=0;j<cs.length;j++){" +
            "var m2=(cs[j].getAttribute('href')||'').match(/user\\u002Fprofile\\u002F([0-9a-f]{24})/);" +
            "if(m2)return m2[1];}}return '';})()"

    /** usersearch 响应 → 精确匹配 red_id 的 user_id，退而取第一条；无候选返回 null。 */
    private fun parseUserSearchJson(body: String, redId: String): String? = runCatching {
        val users = org.json.JSONObject(body)
            .optJSONObject("data")?.optJSONArray("users") ?: return null
        var first: String? = null
        for (i in 0 until users.length()) {
            val u = users.optJSONObject(i) ?: continue
            val uid = u.optString("user_id").takeIf { it.isNotBlank() } ?: continue
            if (u.optString("red_id").equals(redId, ignoreCase = true)) return uid
            if (first == null) first = uid
        }
        first
    }.getOrNull()

    @SuppressLint("SetJavaScriptEnabled")
    private fun makeWebView(
        context: Context,
        collected: ConcurrentLinkedQueue<FeedNote>,
        seen: MutableSet<String>,
        hasMore: AtomicBoolean,
        pageArrivals: AtomicInteger,
        fatal: AtomicBoolean,
    ): WebView {
        val wv = WebView(context)
        wv.visibility = android.view.View.GONE
        wv.layout(0, 0, 1080, 1920)
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        wv.settings.userAgentString =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        wv.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun onPageFinished(view: WebView?, url: String?) {
                XhsFetchLog.w("profile finished: $url")
                view?.evaluateJavascript(
                    "(document.body?document.body.innerText:'').slice(0,150)",
                ) { XhsFetchLog.w("profile text: ${it?.take(150)}") }
                super.onPageFinished(view, url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!url.contains("/api/sns/web/v1/user_posted")) return null
                XhsFetchLog.w("user_posted intercept hit m=${request.method} q=${request.url.query}")
                return try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    conn.useCaches = false
                    // 回放页面自己的请求头；requestHeaders 不含 Cookie（网络栈单独附带），
                    // 必须手动补——否则匿名重放只回 87B 错误 JSON（2026-11-10 实测）
                    request.requestHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    android.webkit.CookieManager.getInstance().getCookie(url)?.let {
                        conn.setRequestProperty("Cookie", it)
                    }
                    conn.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                            "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
                    )
                    conn.setRequestProperty("Referer", "https://www.xiaohongshu.com/")
                    val code = conn.responseCode
                    XhsFetchLog.w("user_posted resp=$code")
                    if (code !in 200..299) {
                        if (code == 429 || code == 461) fatal.set(true)
                        return null
                    }
                    val body = conn.inputStream.use { it.readBytes() }
                    val before = collected.size
                    parseUserPostedJson(String(body), collected, seen, hasMore)
                    val got = collected.size - before
                    XhsFetchLog.w("user_posted body=${body.size}B parsed=$got" +
                        if (got == 0) " head=" + String(body).take(180) else "")
                    // 87B 级错误体不回灌，放行原请求让页面自己重试
                    if (got == 0 && body.size < 256) return null
                    pageArrivals.incrementAndGet()
                    val mime = conn.contentType?.substringBefore(';') ?: "application/json"
                    // 必须回传状态码+响应头（尤其 Access-Control-Allow-Origin）：edith 跨域 fetch，
                    // 无 CORS 头页面 JS 拿不到数据 → 状态不前进 → 永远重复同一 cursor（2026-11-10 实测）
                    val headers = HashMap<String, String>()
                    conn.headerFields?.forEach { (k, v) ->
                        if (!k.isNullOrBlank() && v.isNotEmpty()) headers[k] = v.joinToString(", ")
                    }
                    WebResourceResponse(
                        mime, "utf-8", conn.responseCode, "OK", headers, ByteArrayInputStream(body),
                    )
                } catch (t: Throwable) {
                    XhsFetchLog.w("user_posted intercept err=$t")
                    null // 拦截失败不拦：放行页面原请求，靠超时/兜底收场
                }
            }
        }
        return wv
    }

    /** user_posted 响应 JSON → FeedNote（兼容 note_id / noteId 两种键；缺 token 记空串）。 */
    private fun parseUserPostedJson(
        body: String,
        collected: ConcurrentLinkedQueue<FeedNote>,
        seen: MutableSet<String>,
        hasMore: AtomicBoolean,
    ) {
        runCatching {
            val root = org.json.JSONObject(body)
            val data = root.optJSONObject("data") ?: root
            hasMore.set(data.optBoolean("has_more", hasMore.get()))
            val notes = data.optJSONArray("notes") ?: return
            for (i in 0 until notes.length()) {
                val n = notes.optJSONObject(i) ?: continue
                val id = n.optString("note_id", n.optString("noteId"))
                if (id.isBlank() || !seen.add(id)) continue
                collected.add(FeedNote(id, n.optString("xsec_token")))
            }
        }
    }

    /**
     * 兜底：直接 GET profile 页 HTML，正则提取 noteId+xsec_token 对。
     * 主路线失败时的降级（首屏约 20 篇，免签名）——正则是启发式，页面上
     * xsec_token 与 noteId 同对象成对出现，按「token 先行、id 随后」配对。
     */
    private suspend fun fetchInitialStateFallback(userId: String): List<FeedNote> =
        withContext(Dispatchers.IO) {
            val html = fetchHtml("https://www.xiaohongshu.com/user/profile/$userId") ?: run {
                XhsFetchLog.w("fallback html=null"); return@withContext emptyList()
            }
            XhsFetchLog.w("fallback html len=${html.length}")
            val tokenRe = Regex("\"xsec_token\"\\s*:\\s*\"([^\"]+)\"")
            val idRe = Regex("\"(?:noteId|note_id)\"\\s*:\\s*\"([A-Za-z0-9]{16,32})\"")
            val tokens = tokenRe.findAll(html).map { it.groupValues[1] }.toList()
            val ids = idRe.findAll(html).map { it.groupValues[1] }.distinct().toList()
            val out = ArrayList<FeedNote>()
            for ((i, id) in ids.withIndex()) {
                if (out.size >= XhsBatchPacing.MAX_NOTES) break
                // token 序列与 id 序列大体一一对应；取不到就配空串（详情页可能 403，由失败隔离兜住）
                out.add(FeedNote(id, tokens.getOrNull(i) ?: ""))
            }
            out
        }

    private fun fetchHtml(url: String): String? = runCatching {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        CookieManager.getInstance().getCookie("https://www.xiaohongshu.com")
            ?.takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
        conn.setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        )
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    /** 供 Controller 组装单篇详情请求用的 cookie。 */
    override fun currentCookie(): String =
        CookieManager.getInstance().getCookie("https://www.xiaohongshu.com").orEmpty()
}
