package top.honorhook.ui

import android.app.Application
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper

/**
 * 模块 App 侧的 libxposed Service 生命周期入口。
 * 只有收到 LSPosed 的 XposedService Binder 才判定为「已激活」（照 LSPFRIDA LSPFRIFAApplication）。
 */
class HonorHookApp : Application(), XposedServiceHelper.OnServiceListener {

    override fun onCreate() {
        super.onCreate()
        AppSettings.init(this)
        FeatureStore.init(this)
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        FrameworkState.onServiceBind(service)
    }

    override fun onServiceDied(service: XposedService) {
        FrameworkState.onServiceDied(service)
    }
}
