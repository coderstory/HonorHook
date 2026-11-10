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
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/** 抓取口：Controller 只依赖接口，JVM 单测用 fake（不碰 WebView）。 */
interface XhsFeedClient {
    /** [context] 允许为 null 仅为 JVM 单测缝（真实现 requireNotNull）。 */
    suspend fun fetchNotes(
        context: Context?,
        userId: String,
        onProgress: (Int) -> Unit = {},
    ): List<XhsUserFeedClient.FeedNote>

    fun currentCookie(): String
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
        val collected = ConcurrentLinkedQueue<FeedNote>()
        val seen = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val hasMore = AtomicBoolean(true)
        val pageArrivals = AtomicInteger(0)
        val fatal = AtomicBoolean(false)
        var webView: WebView? = null

        try {
            webView = makeWebView(context, collected, seen, hasMore, pageArrivals, fatal)
            webView.loadUrl("https://www.xiaohongshu.com/user/profile/$userId")

            // 首屏等待：FIRST_PAGE_TIMEOUT_MS 内无拦截命中 → 兜底
            val firstDeadline = System.currentTimeMillis() + XhsBatchPacing.FIRST_PAGE_TIMEOUT_MS
            while (collected.isEmpty() && System.currentTimeMillis() < firstDeadline && !fatal.get()) {
                delay(200)
            }
            if (collected.isEmpty()) {
                val fallback = fetchInitialStateFallback(userId)
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
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36"
        wv.webViewClient = object : WebViewClient() {
            @Deprecated("Deprecated in Java")
            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                if (!url.contains("/api/sns/web/v1/user_posted")) return null
                return try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 10_000
                    // 回放页面自己的请求头（含签名与 cookie），拿响应体后原样回灌
                    request.requestHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        if (code == 429 || code == 461) fatal.set(true)
                        return null
                    }
                    val body = conn.inputStream.use { it.readBytes() }
                    parseUserPostedJson(String(body), collected, seen, hasMore)
                    pageArrivals.incrementAndGet()
                    val mime = conn.contentType?.substringBefore(';') ?: "application/json"
                    WebResourceResponse(mime, "utf-8", ByteArrayInputStream(body))
                } catch (t: Throwable) {
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
            val html = fetchHtml("https://www.xiaohongshu.com/user/profile/$userId") ?: return@withContext emptyList()
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
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/124.0.0.0 Mobile Safari/537.36",
        )
        if (conn.responseCode !in 200..299) return null
        conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()

    /** 供 Controller 组装单篇详情请求用的 cookie。 */
    override fun currentCookie(): String =
        CookieManager.getInstance().getCookie("https://www.xiaohongshu.com").orEmpty()
}
