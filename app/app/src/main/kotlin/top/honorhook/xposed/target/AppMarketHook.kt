package top.honorhook.xposed.target

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.HookPoint
import top.honorhook.xposed.data.RemoteConfig

/**
 * 荣耀应用市场 `com.hihonor.appmarket`（16.1.9.302，aapt2 实证 2026-10-25 真机拉取）净化。
 *
 * 选点依据（docs/reverse/appmarket.md，base.apk dex 扫描 + jadx）：
 * - **首期只收 AlarmCheckReceiver.onReceive 一点**：manifest 式未混淆类名 + AOSP 回调签名
 *   （OucHook 同款最稳锚点），拦掉 slientcheck 定时闹钟自启的自动检查（杀自动留手动）。
 * - **其余 53 号规格点全部缓期**（docs/reverse/appmarket.md §缓期清单）：本版实证 16.1.9.302
 *   已把 UpdateNotificationManager 的 notifyAppUpdate 系与 checkNotifyAppUpdate 混淆成单字母方法
 *   （b()/f()/a()，仅日志串 "notifyAppUpdate:" 残留），SplashManager/CachedSplashScreenService/
 *   OperationServiceImpl 的 suspend 函数链 jadx 反编译失败（寄存器回退）——
 *   没有可靠签名就不挂载（仓库纪律：无锚点不硬兜），待 amwork 工件重建 + MT MCP 在线核签后补。
 */
object AppMarketHook : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"

    internal val MARKET_POINTS: List<HookPoint> = listOf(
        HookPoint(
            id = "market:recv.AlarmCheckReceiver", kind = "CONST", feature = FeatureCatalog.KEY_MARKET_PURIFY,
            className = "$PKG.slientcheck.alarmwake.AlarmCheckReceiver", methodName = "onReceive",
            paramCount = 2, returnType = "void", value = null,
            description = "定时闹钟自启的自动版本检查入口（slientcheck alarmwake；杀自动留手动）",
        ),
        // 运营弹窗渲染兜底（jadx 16.1.9.302 实证 k0(FragmentActivity, OperationVO, Runnable) void）：
        // 数据源族（OperationServiceImpl 四 suspend）缓期（见 reverse/appmarket.md），此点做渲染层双保险
        HookPoint(
            id = "market:dlg.OperationDialog.k0", kind = "CONST", feature = FeatureCatalog.KEY_MARKET_PURIFY,
            className = "$PKG.operation.ui.OperationDialog", methodName = "k0",
            paramCount = 3, returnType = "void", value = null,
            description = "运营弹窗渲染入口（弹窗族兜底；数据源缓期）",
        ),
        HookPoint(
            id = "market:dlg.ToolPagePop.g0", kind = "CONST", feature = FeatureCatalog.KEY_MARKET_PURIFY,
            className = "$PKG.operation.toolpage.ToolPageActivityPopDialog", methodName = "g0",
            paramCount = 0, returnType = "void", value = null,
            description = "工具页弹窗展示（g0() void，私有 final）",
        ),
        HookPoint(
            id = "market:dlg.FloatingPush.h", kind = "CONST", feature = FeatureCatalog.KEY_MARKET_PURIFY,
            className = "$PKG.operation.FloatingPushHandler", methodName = "h",
            paramCount = 2, returnType = "void", value = null,
            description = "悬浮推送展示（h(String,String) void）",
        ),
    )

    internal fun HookPoint.toTask(): MountEngine.SimpleTask = MountEngine.SimpleTask(
        id = id,
        className = className,
        methodName = methodName,
        paramCount = paramCount,
        returnType = returnType,
        description = description,
        group = group,
        feature = feature,
    )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val apkPath = param.applicationInfo.sourceDir

        val pending = mutableListOf<MountEngine.SimpleTask>()
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY)) {
            MARKET_POINTS.forEach { point -> pending += point.toTask() }
        } else {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY, "AppMarketHook")
        }
        if (pending.isEmpty()) return

        val session = MountEngine.open("AppMarketHook", packageName, apkPath, FeatureCatalog.baselineVersionOf(packageName), RemoteConfig)
        session.attemptMount(pending, module, loader) { task -> mountOne(session, module, loader, task) }
    }

    /** onReceive 短路：闹钟不进 = 自动检查链不发生；手动刷新（UI 触发）不走 Receiver，保留。 */
    private fun mountOne(
        session: MountSession,
        module: XposedModule,
        loader: ClassLoader,
        task: MountEngine.SimpleTask,
    ): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            session.judge(module, task, "market-hook", "闹钟自启已拦截")
            null
        }
}
