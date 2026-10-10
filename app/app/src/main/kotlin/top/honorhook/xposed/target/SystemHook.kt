package top.honorhook.xposed.target

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import top.honorhook.xposed.core.MountEngine

/**
 * system_server 框架钩子（由 [HookEntry.onSystemServerStarting] 触发；不走 onPackageLoaded 分发）。
 *
 * S1 受保护应用停用解锁：荣耀在 PMS 的 [ProtectedPackages.isPackageStateProtected] 加了
 * 设备侧保护判定（「健康使用手机」等系统管控应用被强制保护，Settings 点停用/强停被
 * "not allowed to disable this package" 静默拒绝，root 下 pm disable-user 同样被拒——
 * 2026-10-10 真机实证）。此处恒返 false 解锁停用；Settings 的 UI 查询（isPackageStateProtected
 * 同一读点）同步放行，按钮不再置灰。
 *
 * 注意：该方法同时是 device-owner/profile-owner 保护的判定之一——个人设备无 DO/PO 场景无实际影响。
 * 单点最小 hook + PROTECTIVE + 失败放行：system_server 侧绝不能引发 boot-loop。
 */
object SystemHook {

    private const val TAG = "HonorHook"
    private const val CLS_PROTECTED_PACKAGES = "com.android.server.pm.ProtectedPackages"

    private val engine = MountEngine("SystemHook", "android")
    private var installed = false

    /** system_server 启动早期调用（param.classLoader = 服务端类加载器）；幂等。 */
    fun install(module: XposedModule, loader: ClassLoader) {
        if (installed) return
        installed = true
        engine.begin(null) // system_server 无独立 APK 路径；仅直连轨，无版本基线
        val task = object : MountEngine.Task {
            override val id = "system:ProtectedPackages.isPackageStateProtected"
            override val className = CLS_PROTECTED_PACKAGES
            override val methodName = "isPackageStateProtected"
            override val paramCount = 2
            override val returnType = "boolean"
            override val description = "S1 受保护应用停用解锁（PMS 守卫恒 false；真机实证 root pm disable-user 同被拒）"
        }
        engine.attemptMount(listOf(task), module, loader) { t -> mountOne(t, module, loader) }
    }

    private fun mountOne(task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result {
        val method = engine.resolveTask(task, module, loader) ?: return MountEngine.Result.NOT_RESOLVED
        return runCatching {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(task.id)
                .intercept { _ -> false } // 恒返 false = 无包受保护
        }.fold(
            onSuccess = {
                module.log(Log.INFO, TAG, "system hook 已挂载: ${task.description}")
                MountEngine.Result.OK
            },
            onFailure = { t ->
                module.log(Log.WARN, TAG, "system hook 挂载失败: ${task.description} — ${t.message}", t)
                MountEngine.Result.FAILED
            },
        )
    }
}
