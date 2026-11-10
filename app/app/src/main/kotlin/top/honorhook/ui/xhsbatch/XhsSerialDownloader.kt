package top.honorhook.ui.xhsbatch

import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 下载口：Controller 只依赖接口，JVM 单测用内存 fake（不碰网络）。 */
interface XhsDownloader {
    fun download(url: String, target: File): XhsSerialDownloader.Result
    fun deleteRecursivelyCounted(dir: File): Pair<Int, Int>
}

/**
 * 串行媒体下载器（技术方案 §2.3）。HttpURLConnection 直连（okhttp 在 APK 内被 R8 剥离，
 * 不能用）；流式写 .part 临时名，成功后 rename——半截文件不进目录、不进计数。
 * 调用方（Controller）保证全任务单协程串行，本类不加并发结构。
 */
class XhsSerialDownloader(
    private val cookieProvider: () -> String = { "" },
) : XhsDownloader {

    /** 下载结果：bytes 仅在成功时有值。 */
    sealed interface Result {
        data class Ok(val file: File, val bytes: Long) : Result
        data class Failure(val message: String, val throttled: Boolean = false) : Result
    }

    /**
     * [url] 媒体直链；[target] 最终文件名（含目录）。已存在同名文件跳过（续跑去重，
     * 技术方案 §3.0），返回 Ok 且 bytes=0 表示「跳过」——调用方不重复计数。
     */
    override fun download(url: String, target: File): Result {
        // 小红书 CDN 会给 http:// 直链，明文被 targetSdk 禁（Cleartext not permitted）→ 升级 https
        val safeUrl = if (url.startsWith("http://")) "https://" + url.removePrefix("http://") else url
        if (target.isFile && target.length() > 0) return Result.Ok(target, 0)
        val dir = target.parentFile ?: return Result.Failure("无父目录: ${target.path}")
        if (!dir.isDirectory) dir.mkdirs()
        val part = File(dir, target.name + ".part")
        return try {
            val conn = URL(safeUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            // CDN 直链一般不要求 cookie；带上无害（web 会话同源时更稳）
            cookieProvider().takeIf { it.isNotBlank() }?.let { conn.setRequestProperty("Cookie", it) }
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/124.0.0.0 Mobile Safari/537.36",
            )
            val code = conn.responseCode
            if (code !in 200..299) {
                // 429/461 = 限流信号，交给上层退避（PRD F11）
                return Result.Failure("HTTP $code", throttled = code == 429 || code == 461)
            }
            var written = 0L
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        written += n
                    }
                }
            }
            // 零字节 = 错误页空体，按失败处理不 rename
            if (written == 0L) {
                part.delete()
                return Result.Failure("空响应体")
            }
            if (target.exists() && !target.delete()) {
                part.delete()
                return Result.Failure("目标文件无法覆盖: ${target.name}")
            }
            if (!part.renameTo(target)) {
                part.delete()
                return Result.Failure("rename 失败: ${target.name}")
            }
            Result.Ok(target, written)
        } catch (t: Throwable) {
            part.delete()
            Result.Failure("${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /** 目录递归删除（撤回，PRD F9）。返回 (删除文件数, 失败数)。 */
    override fun deleteRecursivelyCounted(dir: File): Pair<Int, Int> {
        var deleted = 0
        var failed = 0
        fun walk(f: File) {
            if (f.isDirectory) {
                f.listFiles()?.forEach(::walk)
                if (!f.delete()) failed++
            } else {
                if (f.delete()) deleted++ else failed++
            }
        }
        if (dir.isDirectory) walk(dir) else return 0 to 0
        return deleted to failed
    }
}
