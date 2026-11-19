package top.honorhook.xposed.target.appmarket

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 关联唤醒/静默更新拦截（tamer 1.4.11 WakeBlocker 移植，2026-11-19）。
 *
 * wake（默认开）：插电/连 Wi-Fi 广播、开机 BootController、WakeMarketProvider 全短路。
 * silent_update（默认关）：静默检查 receiver + 静默/预下载 JobService 返 false。
 * 注意：AlarmCheckReceiver.onReceive 主开关（AppMarketHook market:recv.AlarmCheckReceiver）已挂，
 * 本处**不复挂**（MERGED_CONFLICT 语义：同点不同 feature 计失败），静默闹钟自启由主开关管。
 */
object TamerWakeHooks : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"

    private fun task(id: String, cls: String, method: String, argc: Int, ret: String, desc: String) =
        MountEngine.SimpleTask(
            id = id, className = cls, methodName = method, paramCount = argc, returnType = ret,
            description = desc,
        )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val pending = mutableListOf<MountEngine.SimpleTask>()

        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_WAKE)) {
            pending += listOf(
                task("market-tamer:wake.powerRecv", "$PKG.receiver.PowerConnectedReceiver", "onReceive", 2, "void", "插电广播唤醒市场→null"),
                task("market-tamer:wake.wifiRecv", "$PKG.receiver.wifi.WifiStateChangeReceiver", "onReceive", 2, "void", "连 Wi-Fi 广播唤醒市场→null"),
                task("market-tamer:wake.bootController", "$PKG.boot.BootController", "J", 3, "void", "开机广播控制器（hookAll argc==3）→null"),
                task("market-tamer:wake.providerCall", "$PKG.boot.provider.WakeMarketProvider", "call", 3, "java.lang.Object", "外部唤醒 provider call→null"),
                task("market-tamer:wake.providerCreate", "$PKG.boot.provider.WakeMarketProvider", "onCreate", 0, "boolean", "provider 创建返 false"),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_WAKE, "TamerWakeHooks")
        }

        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_SILENT_UPD)) {
            pending += listOf(
                task("market-tamer:silent.auRecv", "$PKG.slientcheck.checkupdate.au.AuCheckReceiver", "onReceive", 2, "void", "静默检查定时 receiver→null"),
                task("market-tamer:silent.batteryRecv", "$PKG.slientcheck.BatteryReceiver", "onReceive", 2, "void", "充电条件静默检查 receiver→null"),
                task("market-tamer:silent.wakeJob", "$PKG.slientcheck.checkupdate.au.freeze.SilentUpdateWakeJobService", "onStartJob", 1, "boolean", "静默更新 Job 返 false"),
                task("market-tamer:silent.predownload", "com.hihonor.predownload.job.PreDownloadJob", "onStartJob", 1, "boolean", "预下载 Job 返 false"),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_SILENT_UPD, "TamerWakeHooks")
        }

        if (pending.isEmpty()) return

        val session = MountEngine.open(
            "TamerWakeHooks", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pending, module, param.defaultClassLoader) { t ->
            session.mount(t, module, param.defaultClassLoader) {
                session.judge(module, t, "market-tamer", "已拦截")
                if (t.returnType == "boolean") false else null
            }
        }
    }
}
