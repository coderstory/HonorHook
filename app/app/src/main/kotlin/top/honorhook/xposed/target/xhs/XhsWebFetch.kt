package top.honorhook.xposed.target.xhs

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 分享页「需要网页加载」时的兜底抓取（2026-11-22）。
 *
 * 背景：批量采集走 `share link → 短链 → 上游解析`。上游 `RequiresWebView` 的语义是
 * 「用 okhttp 拿到的 HTML 里没有 `__INITIAL_STATE__`，得让真网页跑起来再取 DOM」——
 * 真机 22:22 那轮 76 篇里有 24 篇是这一种（`XhsResolveException: RequiresWebView`）。
 *
 * 做法：在宿主进程里临时造一个不可见 WebView 打开分享链，`onPageFinished` 后（再等 900ms
 * 让前端把数据渲染出来）把 `document.documentElement.outerHTML` 取回来，交给上游同一个解析器。
 *
 * 约束与取舍：
 * - WebView 只能在**主线程**创建/使用，所以这里 post 到主线程、调用方在 IO 线程等 latch；
 * - 硬超时 [timeoutMs]（默认 12s）：批量采集不能因为一篇网页卡住就整轮停摆；
 * - UA 用宿主自己的网页 UA（[XhsVideoSave.XHS_WEB_UA]），避免被降级成爬虫页。
 */
internal object XhsWebFetch {

    private const val TIMEOUT_MS = 12_000L
    private const val RENDER_GRACE_MS = 900L
    /** 最近一次抓取的取证串（长度 / 是否带 __INITIAL_STATE__ / 最终 URL），失败时由驱动写进日志。 */
    @Volatile
    var lastDiag: String? = null

    private const val JS_DOM =
        "(function(){try{return document.documentElement.outerHTML||''}catch(e){return ''}})()"

    fun html(activity: Activity?, url: String, timeoutMs: Long = TIMEOUT_MS): String? {
        if (activity == null || url.isEmpty()) return null
        val latch = CountDownLatch(1)
        val box = arrayOfNulls<String>(1)
        val main = Handler(Looper.getMainLooper())
        main.post {
            val web = runCatching { WebView(activity.applicationContext) }.getOrNull()
            if (web == null) {
                latch.countDown()
                return@post
            }
            runCatching {
                web.settings.javaScriptEnabled = true
                web.settings.domStorageEnabled = true
                web.settings.userAgentString = XhsVideoSave.XHS_WEB_UA
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, finishedUrl: String) {
                        lastDiag = "url=$finishedUrl"
                        main.postDelayed({ grab(view, box, latch, main) }, RENDER_GRACE_MS)
                    }
                }
                web.loadUrl(url)
            }.onFailure {
                latch.countDown()
                runCatching { web.destroy() }
            }
            // 兜底放行：页面永远不 finish / 取不到 DOM 时，不能让这一篇把批量采集拖死
            main.postDelayed({
                if (latch.count > 0) {
                    latch.countDown()
                    runCatching { web.destroy() }
                }
            }, timeoutMs)
        }
        val done = runCatching { latch.await(timeoutMs + 3_000L, TimeUnit.MILLISECONDS) }.getOrDefault(false)
        val out = if (done) box[0] else null
        val init = out?.contains("__INITIAL_STATE__") == true
        lastDiag = "len=${out?.length ?: 0} initState=$init ${lastDiag ?: "url=未完成加载"}"
        return out?.takeIf { it.length > 2048 }
    }

    private fun grab(view: WebView, box: Array<String?>, latch: CountDownLatch, main: Handler) {
        runCatching {
            view.evaluateJavascript(JS_DOM) { raw ->
                box[0] = decodeJsString(raw)
                latch.countDown()
                main.post { runCatching { view.destroy() } }
            }
        }.onFailure { latch.countDown() }
    }

    /** `evaluateJavascript` 回的是 JSON 字符串字面量（含 `\u003c` 之类转义），还原成原文。 */
    private fun decodeJsString(raw: String?): String? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty() || s == "null") return null
        return runCatching { org.json.JSONTokener(s).nextValue() as? String }.getOrNull()
    }
}
