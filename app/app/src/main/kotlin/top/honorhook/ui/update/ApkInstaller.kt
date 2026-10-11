package top.honorhook.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object ApkInstaller {

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
     * 并行探测所有源（Range GET 1 字节，探通即弃），返回第一个可用源的完整 URL；
     * 全挂返回 null。弹窗弹出时调用，把逐源等待挪到用户点下载之前。
     */
    suspend fun probeSources(url: String, timeoutMs: Int = 6_000): String? = coroutineScope {
        SOURCE_PREFIXES
            .map { prefix ->
                async(Dispatchers.IO) {
                    runCatching {
                        val conn = URL(prefix + url).openConnection() as HttpURLConnection
                        conn.connectTimeout = timeoutMs
                        conn.readTimeout = timeoutMs
                        conn.setRequestProperty("Range", "bytes=0-0")
                        try {
                            val code = conn.responseCode
                            if (code == 200 || code == 206) prefix + url else null
                        } finally {
                            conn.disconnect()
                        }
                    }.getOrNull()
                }
            }
            .awaitAll()
            .firstOrNull { it != null }
    }

    /** 单 URL 下载；手动跟随 302（browser_download_url 会跳 objects.githubusercontent.com）。 */
    fun downloadFrom(url: String, dest: File, onProgress: (Int) -> Unit) {
        var u = url
        var redirects = 0
        while (true) {
            val conn = URL(u).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    if (++redirects > 5) error("重定向次数过多")
                    val loc = conn.getHeaderField("Location") ?: error("重定向缺 Location")
                    u = URL(URL(u), loc).toString()
                    continue
                }
                require(code == 200) { "HTTP $code" }
                val total = conn.contentLengthLong
                dest.parentFile?.mkdirs()
                var done = 0L
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) onProgress((done * 100 / total).toInt())
                        }
                    }
                }
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
