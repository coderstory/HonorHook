package top.honorhook.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ApkInstaller {

    private const val TAG = "HonorHookUpdate"

    /** 下载源候选：直连 + GitHub 加速镜像前缀（镜像可用性时变，逐个试到成功；全挂走浏览器兜底）。 */
    private val SOURCE_PREFIXES = listOf(
        "",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghproxy.cc/",
        "https://gh.llkk.cc/",
    )

    /** 流式下载到 dest（父目录自动建）。源逐个尝试（直连不通换镜像），半截文件清理。 */
    fun download(url: String, dest: File, onProgress: (Int) -> Unit) {
        var lastErr: Exception? = null
        for (prefix in SOURCE_PREFIXES) {
            try {
                Log.i(TAG, "download try ${prefix.ifEmpty { "direct" }}")
                downloadFrom(prefix + url, dest, onProgress)
                return
            } catch (e: Exception) {
                lastErr = e
                dest.delete()
            }
        }
        throw lastErr ?: IllegalStateException("下载失败")
    }

    /**
     * 并行探测所有源，赛马返回最先握手成功者；全挂返回 null。
     * 判定 = TCP+TLS 握手成功（connect()）；select 首个成功即返回并 cancel 其余，
     * 单源 4s 超时封顶——awaitAll 旧版曾被死镜像拖满 10s，用户点下载干等。
     */
    suspend fun probeSources(url: String, timeoutMs: Int = 4_000): String? = coroutineScope {
        val jobs = SOURCE_PREFIXES.map { prefix ->
            prefix to async(Dispatchers.IO) { probeOne(prefix, url, timeoutMs) }
        }
        var pending = jobs
        while (pending.isNotEmpty()) {
            val (donePrefix, ok) = select<Pair<String, String?>> {
                pending.forEach { (prefix, job) -> job.onAwait { res -> prefix to res } }
            }
            pending = pending.filter { it.first != donePrefix }
            if (ok != null) {
                pending.forEach { (_, job) -> job.cancel() }
                Log.i(TAG, "probe winner ${donePrefix.ifEmpty { "direct" }}")
                return@coroutineScope ok
            }
        }
        Log.i(TAG, "probe done: 0/${SOURCE_PREFIXES.size} 通")
        null
    }

    /** 单源探测：握手成功返回完整 URL，失败返回 null（日志含耗时与原因）。 */
    private fun probeOne(prefix: String, url: String, timeoutMs: Int): String? {
        val t0 = System.currentTimeMillis()
        // URL 构造必须与 connect 同在失败语义内：空串/畸形 URL 抛 MalformedURLException
        // 会直接炸调用方（probeSources/coroutineScope），与握手失败同语义返回 null
        val conn = try {
            URL(prefix + url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            Log.i(TAG, "probe FAIL ${prefix.ifEmpty { "direct" }} ${System.currentTimeMillis() - t0}ms: ${e.message}")
            return null
        }
        conn.connectTimeout = timeoutMs
        conn.readTimeout = timeoutMs
        return try {
            conn.connect()
            Log.i(TAG, "probe OK ${prefix.ifEmpty { "direct" }} ${System.currentTimeMillis() - t0}ms")
            prefix + url
        } catch (e: Exception) {
            Log.i(TAG, "probe FAIL ${prefix.ifEmpty { "direct" }} ${System.currentTimeMillis() - t0}ms: ${e.message}")
            null
        } finally {
            conn.disconnect()
        }
    }

    /** 单 URL 下载；手动跟随 302（browser_download_url 会跳 objects.githubusercontent.com）。 */
    fun downloadFrom(url: String, dest: File, onProgress: (Int) -> Unit) {
        var u = url
        var redirects = 0
        while (true) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            try {
                val code = conn.responseCode
                Log.i(TAG, "download $code $u")
                if (code in 300..399) {
                    if (++redirects > 5) error("重定向次数过多")
                    val loc = conn.getHeaderField("Location") ?: error("重定向缺 Location")
                    u = URL(URL(u), loc).toString()
                    continue
                }
                require(code == 200) { "HTTP $code" }
                val total = conn.contentLengthLong
                Log.i(TAG, "download body total=$total")
                dest.parentFile?.mkdirs()
                var done = 0L
                var nextLog = if (total > 0) total / 4 else 0L   // 25% 步进打点，诊断慢速段
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                onProgress((done * 100 / total).toInt())
                                if (done >= nextLog) {
                                    Log.i(TAG, "download body ${done * 100 / total}% (${done}/$total)")
                                    nextLog += total / 4
                                }
                            }
                        }
                    }
                }
                // 提前断流时读到 EOF 也算"读完"，半截 APK 进系统安装器必失败；
                // 抛错让 download() 的逐源循环换下一源（probed 直连路径则走调用方 catch → Error 态）
                if (total > 0 && done != total) error("下载不完整: $done/$total")
                // total 未知（无 Content-Length，P4）：读完流即成功，进度补 100%，别停在 0
                if (total <= 0) onProgress(100)
                Log.i(TAG, "download done $done bytes")
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    /** 触发系统安装器；未授「安装未知应用」时跳授权页并返回 false（授权后重新下载入口再点）。 */
    fun install(context: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return false
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return true
    }

    /** 兜底：全部下载源失败时交给用户浏览器（用户网络环境可能有代理/加速）。 */
    fun openInBrowser(context: Context, url: String) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
