package top.honorhook.ui.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 完整性拒绝（无可信哈希 / SHA-256 不匹配）。区别于网络异常：换源重试对它无意义——
 * 哈希只信 API 主源（api.github.com 直连），不匹配即篡改或缺配证据，fail-closed 直接终止。
 */
class IntegrityException(message: String, val detail: String? = null) : Exception(message)

object ApkInstaller {

    private const val TAG = "HonorHookUpdate"

    /**
     * 下载源候选，顺序=优先级：直连（空前缀）恒首位，镜像只作直连失败的逐个降级
     * （可用性时变，全挂走浏览器兜底）。internal 供 JVM 测试钉住直连首位。
     */
    internal val SOURCE_PREFIXES = listOf(
        "",
        "https://ghfast.top/",
        "https://gh-proxy.com/",
        "https://ghproxy.net/",
        "https://ghproxy.cc/",
        "https://gh.llkk.cc/",
    )

    private val HEX64_RE = Regex("^[0-9a-f]{64}$")

    /** 流式下载到 dest（父目录自动建）。直连优先逐源尝试（直连不通换镜像），半截文件清理；
     *  每源成功后强校验 SHA-256，不匹配删文件抛 [IntegrityException]（不换源：不匹配=篡改证据）。 */
    fun download(url: String, dest: File, expectedSha256: String?, onProgress: (Int) -> Unit) {
        // 前置拒绝在循环外：缺哈希一条源都不试、不落一个字节（fail-closed，2026-10-26 拍板）
        requireValidSha256(expectedSha256)
        var lastErr: Exception? = null
        for (prefix in SOURCE_PREFIXES) {
            try {
                Log.i(TAG, "download try ${prefix.ifEmpty { "direct" }}")
                downloadFrom(prefix + url, dest, expectedSha256, onProgress)
                return
            } catch (e: IntegrityException) {
                throw e
            } catch (e: Exception) {
                lastErr = e
                dest.delete()
            }
        }
        throw lastErr ?: IllegalStateException("下载失败")
    }

    /**
     * 探测可用下载源，直连优先（2026-10-26 反转旧「并行赛马取最先握手成功」：CDN 镜像握手
     * 普遍快于直连，赛马事实偏好镜像；元数据主源是 api.github.com 直连，下载面保持同生态）。
     * 按候选序逐个探测（[selectSource]），首个握手成功者胜出；单源 4s 超时封顶，全挂返回 null。
     */
    suspend fun probeSources(url: String, timeoutMs: Int = 4_000): String? = withContext(Dispatchers.IO) {
        val winner = selectSource(SOURCE_PREFIXES) { prefix -> probeOne(prefix, url, timeoutMs) }
        if (winner == null) {
            Log.i(TAG, "probe done: 0/${SOURCE_PREFIXES.size} 通")
            null
        } else {
            Log.i(TAG, "probe winner ${winner.first.ifEmpty { "direct" }}")
            winner.second
        }
    }

    /**
     * 候选源选择器（纯函数，JVM 可测）：按序逐个探测，首个 probe 返回非 null 者胜出，全失败返回 null。
     * 顺序即优先级——直连（空前缀）恒在首位 ⇒ 直连成功时镜像零探测。
     */
    internal fun selectSource(candidates: List<String>, probe: (String) -> String?): Pair<String, String>? {
        for (c in candidates) {
            probe(c)?.let { return c to it }
        }
        return null
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

    /**
     * 单 URL 下载 + 完整性强校验（所有下载路径的唯一咽喉：探测直连与逐源兜底都过这里）；
     * 手动跟随 302（browser_download_url 会跳 objects.githubusercontent.com）。
     */
    fun downloadFrom(url: String, dest: File, expectedSha256: String?, onProgress: (Int) -> Unit) {
        val want = requireValidSha256(expectedSha256)
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
                try {
                    verifySha256(dest, want)
                } catch (e: IntegrityException) {
                    Log.w(TAG, "sha256 mismatch ${e.detail ?: ""}")
                    throw e
                }
                Log.i(TAG, "download done $done bytes sha256 ok")
                return
            } finally {
                conn.disconnect()
            }
        }
    }

    /**
     * fail-closed 前置（2026-10-26 拍板）：哈希缺失或格式错一律拒绝下载、明确报错，不静默放行。
     * 更新器只服务已装用户——fail-open 等于给「镜像接管」留静默后门。
     * 返回规范化 64 位小写 hex（parseDigest 已小写，这里兜 trim/大小写防御）。
     */
    internal fun requireValidSha256(expected: String?): String {
        val want = expected?.trim()?.lowercase()
        if (want == null || !HEX64_RE.matches(want)) {
            throw IntegrityException("缺少 SHA-256 校验值，已取消下载")
        }
        return want
    }

    /** 下载完成后的强校验：不匹配删文件并抛错——篡改件绝不进 install()。internal 供 JVM 测试直钉。 */
    internal fun verifySha256(file: File, expectedHex: String) {
        val got = sha256Of(file)
        if (got != expectedHex.trim().lowercase()) {
            file.delete()
            throw IntegrityException(
                "校验失败，已删除下载文件",
                detail = "want=${expectedHex.lowercase()} got=$got file=${file.name}",
            )
        }
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
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
