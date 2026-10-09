package top.honorhook

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import top.honorhook.data.HookSettings
import top.honorhook.data.ThemeModeStore

/**
 * 模块宿主 Application。
 * 照 HC HookApplication.kt：注册 XposedService 绑定监听（绑定状态反映“模块已激活”），
 * 初始化配置存储（HookSettings 双写：本地 SP + 远程 prefs）。
 */
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
        // 对齐一次远程权威值（daemon 数据存在则覆盖本地，双向回写），照 HC MainActivity LaunchedEffect(service) syncRemote
        HookSettings.syncRemote(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (serviceState.value === service) serviceState.value = null
        FrameworkState.onServiceDied()
    }

    companion object {
        // 照 HC HookApplication.kt:25-28：暴露当前绑定的 XposedService（设置页切开关时经它写远程 prefs）
        private val serviceState = MutableStateFlow<XposedService?>(null)
        val service = serviceState.asStateFlow()
    }
}

/** 框架激活状态（照 LSPFRIDA FrameworkState 极简版：只保留激活标记与 API 版本）。 */
object FrameworkState {

    private val _active = MutableStateFlow(false)
    /** 模块是否已与 LSPosed 框架连接（XposedService 绑定 = 激活）。 */
    val active = _active.asStateFlow()

    private val _apiVersion = MutableStateFlow(0)
    /** 框架 API 版本（如 102）。 */
    val apiVersion = _apiVersion.asStateFlow()

    @Synchronized
    fun onServiceBound(service: XposedService) {
        _apiVersion.value = service.apiVersion
        _active.value = true
    }

    @Synchronized
    fun onServiceDied() {
        _active.value = false
        _apiVersion.value = 0
    }
}