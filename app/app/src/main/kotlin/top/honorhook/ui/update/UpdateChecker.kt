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

/** 一个 GitHub release（tag 契约 = CI ci.yml 的 `v<versionName>.r<run_number>`）。 */
data class Release(
    val version: String,
    val buildNum: Int,
    val apkUrl: String,
    val tag: String,
)

object UpdateChecker {

    const val REPO = "coderstory/HonorHook"
    private const val API_URL = "https://api.github.com/repos/$REPO/releases/latest"
    private val TAG_RE = Regex("""^v(\d+)\.(\d+)\.(\d+)(?:\.r(\d+))?$""")

    /** "v0.1.0.r42" → Release；缺 .r 构建号当 0；不符契约返回 null。 */
    fun parseTag(tag: String, apkUrl: String): Release? {
        val m = TAG_RE.matchEntire(tag) ?: return null
        val version = "${m.groupValues[1]}.${m.groupValues[2]}.${m.groupValues[3]}"
        return Release(version, m.groupValues[4].toIntOrNull() ?: 0, apkUrl, tag)
    }

    /** 远端是否比本地新：先比 semver 三段，相等再比构建号。 */
    fun isNewer(remote: Release, localVersion: String, localBuildNum: Int): Boolean {
        val remoteV = remote.version.split('.')
        val localV = localVersion.split('.')
        for (i in 0 until maxOf(remoteV.size, localV.size)) {
            val cmp = remoteV.getOrElse(i) { "0" }.toIntOrNull() ?: 0
            val lcl = localV.getOrElse(i) { "0" }.toIntOrNull() ?: 0
            if (cmp != lcl) return cmp > lcl
        }
        return remote.buildNum > localBuildNum
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
            return parseTag(json.getString("tag_name"), apkUrl)
                ?: error("tag 不符合 v<version>.r<build> 契约")
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
                        if (UpdateChecker.isNewer(r, BuildConfig.VERSION_NAME, BuildConfig.BUILD_NUM)) {
                            showDialog = true
                            // 弹窗弹出即并行探测全部下载源，用户点下载时直接用可用源（零等待）
                            probedUrl = null
                            launch { probedUrl = ApkInstaller.probeSources(r.apkUrl) }
                        } else if (manual) {
                            notice = "已是最新版本 v${BuildConfig.VERSION_NAME}（构建 ${BuildConfig.BUILD_NUM}）"
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

    fun dismissDialog() {
        showDialog = false
    }

    fun clearNotice() {
        notice = null
    }
}
