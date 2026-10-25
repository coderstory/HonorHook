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
import top.honorhook.VersionName
import java.net.HttpURLConnection
import java.net.URL

/** 一个 GitHub release（tag 契约 = CI ci.yml 的 `v<versionName>`，语义版本，仅 skill `release` 发布时 +1）。 */
data class Release(
    val version: String,
    val apkUrl: String,
    val tag: String,
    /**
     * APK 资产的 SHA-256（api.github.com asset.digest 解析出的 64 位小写 hex）。
     * null = API 未提供/格式错 ⇒ fail-closed：下载层拒绝下载安装并明确报错
     * （2026-10-26 拍板——更新器只服务已装用户，fail-open 等于给「镜像接管」留静默后门）。
     */
    val sha256: String? = null,
)

object UpdateChecker {

    const val REPO = "coderstory/HonorHook"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private val TAG_RE = Regex("""^v(\d+\.\d+\.\d+)$""")

    /** GitHub asset.digest 契约：`sha256:<64 位 hex>`（对存量资产同样返回，不依赖 release notes 文案）。 */
    private val DIGEST_RE = Regex("""^sha256:[0-9a-fA-F]{64}$""")

    /** "v1.0.1" → Release；不符契约返回 null。 */
    fun parseTag(tag: String, apkUrl: String): Release? {
        val m = TAG_RE.matchEntire(tag) ?: return null
        val v = m.groupValues[1]
        return Release(v, apkUrl, tag)
    }

    /** asset.digest `sha256:<hex>` → 64 位小写 hex；缺失/格式错返回 null（fail-closed：下载层拒绝）。 */
    fun parseDigest(digest: String?): String? {
        val d = digest?.trim() ?: return null
        return if (DIGEST_RE.matches(d)) d.removePrefix("sha256:").lowercase() else null
    }

    /**
     * releases/latest 响应 JSON → Release（纯函数，JVM 可测；fetchLatest 只管 HTTP）。
     * 哈希只信 API 主源（api.github.com 直连）的 asset.digest——不从 release notes 文案、
     * 更不从镜像响应取（镜像不给哈希话语权）。digest 缺失/格式错 → sha256=null 交给下载层
     * fail-closed 拒绝；这里不抛错：检查更新本身仍应能看到新版本提示。
     */
    fun parseRelease(json: JSONObject): Release {
        val assets = json.getJSONArray("assets")
        var apkUrl = ""
        var digest: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                apkUrl = a.getString("browser_download_url")
                digest = if (a.has("digest") && !a.isNull("digest")) a.getString("digest") else null
                break
            }
        }
        return parseTag(json.getString("tag_name"), apkUrl)
            ?.copy(sha256 = parseDigest(digest))
            ?: error("tag 不符合 v<主.中.尾> 契约")
    }

    /** 远端是否比本地新：语义版本逐段比（复用共享层 [VersionName]，消双处漂移）。 */
    fun isNewer(remote: Release, localVersionName: String): Boolean =
        VersionName.compare(remote.version, localVersionName) > 0

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
            // 日志放 UpdateState（fetchLatest 被 JVM 网络门控测试直调，android.util.Log 是 stub 会炸）
            return parseRelease(JSONObject(conn.inputStream.bufferedReader().readText()))
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

    /** 直连优先探测出的可用下载源（完整 URL）；null = 未完成或全挂（点下载走逐源兜底链）。 */
    var probedUrl by mutableStateOf<String?>(null)
        private set

    // 跨线程读写（probe 启动在 Main、awaitProbe 可能从 IO 协程读）——加 @Volatile 保可见性（P4）
    @Volatile
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
                        android.util.Log.i(
                            "HonorHookUpdate",
                            "fetchLatest ${r.tag} apk=${r.apkUrl.isNotEmpty()} sha256=${r.sha256 != null}",
                        )
                        latest = r
                        if (UpdateChecker.isNewer(r, BuildConfig.VERSION_NAME)) {
                            showDialog = true
                            // 弹窗弹出即按直连优先顺序探测下载源（同一 apkUrl 不重复探，手动重查不白等）
                            val apk = r.apkUrl
                            if (apk != probedForApk || probeJob?.isActive != true) {
                                probedForApk = apk
                                probedUrl = null
                                probeJob?.cancel()
                                probeJob = launch {
                                    // 探测异常不能崩整个 App：静默检查无 UI 兜底也不弹 toast，
                                    // 失败只留日志，probedUrl 保持 null = 点下载走逐源兜底链
                                    runCatching { ApkInstaller.probeSources(apk) }
                                        .onSuccess { probedUrl = it }
                                        .onFailure { e ->
                                            android.util.Log.w("HonorHookUpdate", "probe 失败: ${e.message}")
                                        }
                                }
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

    /**
     * 点下载时调用：探测进行中先等它出结果，完成立即返回。直连优先逐源探测最坏 6×4s，
     * 这里 6s 封顶弃等、点下载改走 download() 兜底链（同样直连优先，语义一致）。
     */
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
