package top.honorhook

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.honorhook.data.HookSettings
import top.honorhook.data.ThemeModeStore

/** 模块宿主 Application（照 HC HookApplication.kt：XposedService 绑定监听 + 配置存储初始化）。 */
class HookApplication : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        ThemeModeStore.init(this)
        HookSettings.init(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        serviceState.value = service
        FrameworkState.onServiceBound(service)
        // 绑定后对齐一次远程权威值（照 HC MainActivity LaunchedEffect(service) syncRemote）
        HookSettings.syncRemote(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (serviceState.value === service) serviceState.value = null
        FrameworkState.onServiceDied()
    }

    companion object {
        // 当前绑定的 XposedService（设置页写远程 prefs 用；照 HC HookApplication.kt:25-28）
        private val serviceState = MutableStateFlow<XposedService?>(null)
        val service = serviceState.asStateFlow()
    }
}

/** 框架激活状态（照 LSPFRIDA FrameworkState 极简版；供首页详情行）。 */
object FrameworkState {

    private val _active = MutableStateFlow(false)
    /** 模块是否已与 LSPosed 框架连接（XposedService 绑定 = 激活）。 */
    val active = _active.asStateFlow()

    private val _apiVersion = MutableStateFlow(0)
    /** 框架 API 版本（如 102）。 */
    val apiVersion = _apiVersion.asStateFlow()

    private val _frameworkName = MutableStateFlow("")
    /** 框架名称（如 "LSPosed"；未绑定为空串）。 */
    val frameworkName = _frameworkName.asStateFlow()

    private val _frameworkVersion = MutableStateFlow("")
    /** 框架版本字符串（如 "v2.2.0"；未绑定为空串）。 */
    val frameworkVersion = _frameworkVersion.asStateFlow()

    @Synchronized
    fun onServiceBound(service: XposedService) {
        _apiVersion.value = service.apiVersion
        _frameworkName.value = service.frameworkName
        _frameworkVersion.value = service.frameworkVersion
        _active.value = true
    }

    @Synchronized
    fun onServiceDied() {
        _active.value = false
        _apiVersion.value = 0
        _frameworkName.value = ""
        _frameworkVersion.value = ""
    }
}