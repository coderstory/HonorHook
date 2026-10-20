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

    /**
     * 框架状态是否**已确认**。
     *
     * 服务绑定是异步的：冷启动那一下 [active] 还是默认值 false，若直接拿它当结论，
     * 状态卡会先闪一个「未激活」再跳成「工作中」——等于在还不知道的时候先下结论。
     * 这里把「还没问出结果」（false）与「确认没激活」（true）分开：
     * 未确认期间 UI 走中性文案，确认后才说「未激活」。
     */
    var resolved by mutableStateOf(false)
        private set

    @Volatile
    private var service: XposedService? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 主线程强制预热：mutableStateOf 在 <clinit> 建。若首次触碰来自 binder 线程（onServiceBind），
     * state 会建在一个主线程尚未 apply 的 snapshot 上 → 主线程组合读取时
     * `IllegalStateException: Reading a state that was created after the snapshot was taken` → FC。
     * 在 Application.onCreate（主线程）先调本方法，把类加载钉死在主线程。
     */
    fun warmUp() {
        // 触发 <clinit> 即可；成员均为默认值，无副作用。
    }

    /** 当前可用 XposedService（宿主写远端 prefs 用）；无服务返回 null（未激活）。 */
    fun current(): XposedService? = service

    /**
     * 兜底计时：服务一直没绑上（模块被停用 / LSPosed 没起）时，延时把状态标成「已确认」。
     * 绑上则走 [publish] 提前置 true，不受这个计时影响。
     */
    fun armResolveTimeout(delayMs: Long = 1500) {
        mainHandler.postDelayed({
            if (!resolved) resolved = true
        }, delayMs)
    }

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
                resolved = true
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
            resolved = true
            apiVersion = s.apiVersion
            frameworkName = s.frameworkName.orEmpty()
            frameworkVersion = s.frameworkVersion.orEmpty()
        }.onFailure {
            active = true
            resolved = true
            apiVersion = XposedService.API_102
        }
    }
}
