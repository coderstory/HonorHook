package top.honorhook.xposed

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.xposed.target.TargetRegistry

/**
 * libxposed 102 模块入口（注册于 META-INF/xposed/java_init.list）。
 *
 * 纯 hook 模块：无 Activity、无图标、无设置界面，功能恒开（原 UI↔hook 远程 prefs 双写体系已移除）。
 * 目标分发走 [TargetRegistry]：新增目标 = 新建实现 + 注册表加一行，本入口零改动。
 */
class HookEntry : XposedModule() {

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
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
