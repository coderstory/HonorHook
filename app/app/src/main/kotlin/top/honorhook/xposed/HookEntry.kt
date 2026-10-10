package top.honorhook.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetRegistry

/**
 * libxposed 102 模块入口（注册于 META-INF/xposed/java_init.list）。
 *
 * 功能开关化：宿主 App 经 XposedService 写远端 prefs，此处读一次注入 [RemoteConfig]，
 * 各目标在 install() 阶段按开关门控挂载（默认全开）。目标分发走 [TargetRegistry]：新增目标
 * = 新建实现 + 注册表加一行，本入口零改动。
 */
class HookEntry : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        // 远端 prefs 缓存注入（一次 Binder 读取；读失败回 null = 全功能默认开）。
        RemoteConfig.attach(runCatching { getRemotePreferences(FeatureCatalog.REMOTE_GROUP) }.getOrNull())

        // 防重装靠 TargetRegistry 对象级判定（多键目标共享进程时不重复挂载）。
        // 任何异常只记日志：hook 挂载绝不能炸宿主进程（CLAUDE.md §九 防御性编程）。
        runCatching {
            val target = TargetRegistry.find(param.packageName)
            if (target != null && !TargetRegistry.isInstalled(target)) {
                target.install(this, param)
                TargetRegistry.markInstalled(target)
                log(Log.INFO, TAG, "target installed: ${target.packageName}")
            }
        }.onFailure { error ->
            log(Log.ERROR, TAG, "target install failed for ${param.packageName}", error)
        }
    }

    private companion object {
        const val TAG = "HonorHook"
    }
}
