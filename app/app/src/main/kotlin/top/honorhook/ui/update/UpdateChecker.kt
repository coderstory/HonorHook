package top.honorhook.ui.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import top.honorhook.BuildConfig
import java.net.HttpURLConnection
import java.net.URL

/** 一个 GitHub release（tag 契约 = CI ci.yml 的 `v<versionName>`，语义版本，仅 skill `release` 发布时 +1）。 */
data class Release(
    val version: String,
    val apkUrl: String,
    val tag: String,
)

object UpdateChecker {

    const val REPO = "coderstory/HonorHook"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private val TAG_RE = Regex("""^v(\d+\.\d+\.\d+)$""")

    /** "v1.0.1" → Release；不符契约返回 null。 */
    fun parseTag(tag: String, apkUrl: String): Release? {
        val m = TAG_RE.matchEntire(tag) ?: return null
        val v = m.groupValues[1]
        return Release(v, apkUrl, tag)
    }

    /**
     * 远端是否比本地新：语义版本逐段比（'.' 分段、数字前缀、缺段按 0）。
     * 与 HostVersion.compareVersionNames 同语义但不引用——后者尚未入库（另一工作流在改），
     * 本文件须随本次 commit 自包含可编译；那边入库后可收敛回去。
     */
    fun isNewer(remote: Release, localVersionName: String): Boolean {
        val ka = remote.version.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val kb = localVersionName.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(ka.size, kb.size)) {
            val c = (ka.getOrElse(i) { 0 }).compareTo(kb.getOrElse(i) { 0 })
            if (c != 0) return c > 0
        }
        return false
    }

    /** 拉 releases/latest（IO）。失败抛异常，由调用方决定如何呈现。 */
    fun fetchLatest(): Release {
        val conn = (URL(API_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "HonorHook-Manager")
        }
        try {
            require(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val assets = json.getJSONArray("assets")
            var apkUrl = ""
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    apkUrl = a.getString("browser_download_url")
                    break
                }
            }
            val rel = parseTag(json.getString("tag_name"), apkUrl)
                ?: error("tag 不符合 v<主.中.尾> 契约")
            android.util.Log.i("HonorHookUpdate", "fetchLatest ${rel.tag} apk=${apkUrl.isNotEmpty()}")
            return rel
        } finally {
            conn.disconnect()
        }
    }
}

/** 检查状态（UI 直接订阅；照 FrameworkState 的 mutableStateOf 模式）。 */
object UpdateState {

    var latest by mutableStateOf<Release?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    /** 发现新版 → 全局弹窗（手动/静默检查都触发）。 */
    var showDialog by mutableStateOf(false)
        private set

    /** 一次性 Toast 文案（手动检查无新版 / 失败），UI 消费后调 [clearNotice]。 */
    var notice by mutableStateOf<String?>(null)
        private set

    /** 并行探测出的可用下载源（完整 URL）；null = 未完成或全挂（点下载走逐源兜底链）。 */
    var probedUrl by mutableStateOf<String?>(null)
        private set

    private var probeJob: kotlinx.coroutines.Job? = null
    private var probedForApk: String? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    /** manual = 用户点了「检查更新」，必须有反馈（弹窗/Toast）；false = 启动静默检查。 */
    fun checkAsync(manual: Boolean = false) {
        if (checking) return
        checking = true
        scope.launch {
            runCatching { UpdateChecker.fetchLatest() }
                .onSuccess { r ->
                    withContext(Dispatchers.Main) {
                        latest = r
                        if (UpdateChecker.isNewer(r, BuildConfig.VERSION_NAME)) {
                            showDialog = true
                            // 弹窗弹出即并行探测全部下载源（同一 apkUrl 不重复探，手动重查不白等）
                            val apk = r.apkUrl
                            if (apk != probedForApk || probeJob?.isActive != true) {
                                probedForApk = apk
                                probedUrl = null
                                probeJob?.cancel()
                                probeJob = launch { probedUrl = ApkInstaller.probeSources(apk) }
                            }
                        } else if (manual) {
                            notice = "已是最新版本 v${BuildConfig.VERSION_NAME}"
                        }
                    }
                }
                .onFailure { e ->
                    withContext(Dispatchers.Main) {
                        if (manual) notice = "检查失败：${e.message ?: "网络错误"}"
                    }
                }
            withContext(Dispatchers.Main) { checking = false }
        }
    }

    /** 点下载时调用：探测进行中先等它出结果（赛马 4s 封顶），完成立即返回。 */
    suspend fun awaitProbe(timeoutMs: Long = 6_000): String? {
        val job = probeJob
        if (job != null && job.isActive) {
            kotlinx.coroutines.withTimeoutOrNull(timeoutMs) { job.join() }
        }
        return probedUrl
    }

    fun dismissDialog() {
        showDialog = false
    }

    fun clearNotice() {
        notice = null
    }
}
