package top.honorhook.ui

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.libxposed.service.XposedService

/**
 * LSPosed 激活状态（首页「LSPosed 状态」的数据源）。
 * 只有收到 LSPosed 的 XposedService Binder 才判定为激活（照 LSPFRIDA FrameworkState）。
 */
object FrameworkState {

    var active by mutableStateOf(false)
        private set
    var frameworkName by mutableStateOf("")
        private set
    var frameworkVersion by mutableStateOf("")
        private set
    var apiVersion by mutableStateOf(0)
        private set

    @Volatile
    private var service: XposedService? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前可用 XposedService（宿主写远端 prefs 用）；无服务返回 null（未激活）。 */
    fun current(): XposedService? = service

    @Synchronized
    fun onServiceBind(s: XposedService) {
        service = s
        mainHandler.post { publish(s) }
    }

    @Synchronized
    fun onServiceDied(s: XposedService) {
        if (service === s) service = null
        val latest = service
        mainHandler.post {
            if (latest == null) {
                active = false
                frameworkName = ""
                frameworkVersion = ""
                apiVersion = 0
            } else {
                publish(latest)
            }
        }
    }

    private fun publish(s: XposedService) {
        runCatching {
            active = true
            apiVersion = s.apiVersion
            frameworkName = s.frameworkName.orEmpty()
            frameworkVersion = s.frameworkVersion.orEmpty()
        }.onFailure {
            active = true
            apiVersion = XposedService.API_102
        }
    }
}
