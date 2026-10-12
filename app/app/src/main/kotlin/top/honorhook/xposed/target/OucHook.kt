package top.honorhook.xposed.target

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine

/**
 * 荣耀在线升级客户端 `com.hihonor.ouc`（HnOUC.apk 18.0.3.206，aapt2 实证）：禁止系统更新
 * 的**后台自动**行为——自动检测 / WiFi 自动下载 / 夜间安装 / 升级提醒通知 / 充电·激活·运营商触发。
 *
 * 选点依据（HnOUC-jadx + manifest，2026-10-12，docs/reverse/ouc.md）：
 * - 主代码 R8 深混淆，但 **manifest 组件名未被混淆**（导出组件须可读，跨版本最稳锚点）
 *   ⇒ 全部挂载点选 manifest 命名的 Receiver/Service 的 framework 回调（onReceive / onStartCommand），
 *   类名锚点 + AOSP 方法签名，零 DexKit 依赖。
 * - 挂**入口**而非检测/通知逻辑：入口不执行 ⇒ 下游（联网检测、下载调度、提醒通知）整链不发生，
 *   与「最小侵入、上游一致性」原则一致。
 * - **手动检查不拦**：设置→系统更新→检查更新走 phoneservice → `CheckNewVersionReceiver`
 *   （action CHECK_NEW_VERSION，HICARE 模式，CheckNewVersionReceiver.java:57）——该组件故意不挂，
 *   只杀 `check.auto.AutoCheckService` 等自动调度源，用户主动检查能力保留。
 */
object OucHook : TargetHook {

    override val packageName = "com.hihonor.ouc"

    /** 共享挂载基建（重试循环/失败分级/汇总日志）。 */
    private val engine = MountEngine("OucHook", packageName)

    /** manifest 前缀（组件类全名 = 此前缀 + 相对路径；manifest 实证未被混淆）。 */
    private const val PKG_HNOUC = "com.hihonor.android.hnouc"

    /**
     * 静默后台触发的 Receiver（onReceive void 短路）：每个都是一条自动链的入口。
     * 有意不收录：CheckNewVersionReceiver（手动检查路径）、HiskytoneVersionDemoReceiver（demo）。
     */
    private val AUTO_RECEIVERS = listOf(
        "biz.impl.reveiver.FirmwareAutoDownloadReceiver" to "固件自动下载触发（AUTO_DOWNLOAD_ALARM）",
        "biz.impl.reveiver.NightUpgradeReceiver" to "夜间安装调度（NIGHT_UPGRADE_ALARM）",
        "biz.impl.reveiver.UpgradeRemindReceiver" to "升级提醒（通知/红点复唤）",
        "biz.impl.reveiver.PowerConnectedReceiver" to "充电触发检测（ACTION_POWER_CONNECTED）",
        "biz.impl.reveiver.DeviceActivatedReceiver" to "设备激活触发检测",
        "biz.impl.reveiver.SelfUpdateReceiver" to "OUC 自升级",
        "biz.impl.reveiver.CarrierConfigReceiver" to "运营商配置变化触发",
        "biz.impl.reveiver.FirmwareAndAppReceiver" to "固件推送接收",
        "biz.impl.reveiver.FirmwareInstallReceiver" to "固件安装触发",
    )

    /** 自动调度 Service（onStartCommand 短路，返回 START_NOT_STICKY 防 restart）。 */
    private val AUTO_SERVICES = listOf(
        // 周期自动版本检测的总调度（AutoCheckService.java:942 onStartCommand 自有覆写实证）
        "check.auto.AutoCheckService" to "周期自动检测调度",
        // WiFi 场景自动下载管理（WifiAutoDownloadManageService.java:63 onStartCommand 自有覆写实证）
        "biz.impl.service.WifiAutoDownloadManageService" to "WiFi 自动下载管理",
    )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val apkPath = param.applicationInfo.sourceDir

        val pending = mutableListOf<Task>()
        if (top.honorhook.xposed.data.RemoteConfig.isEnabled(FeatureCatalog.KEY_OTA_BLOCK)) {
            AUTO_RECEIVERS.forEach { (rel, what) ->
                pending += Task(
                    className = "$PKG_HNOUC.$rel", methodName = "onReceive", paramCount = 2,
                    returnType = "void", id = "ota:recv.${rel.substringAfterLast('.')}",
                    description = what,
                )
            }
            AUTO_SERVICES.forEach { (rel, what) ->
                pending += Task(
                    className = "$PKG_HNOUC.$rel", methodName = "onStartCommand", paramCount = 3,
                    returnType = "int", id = "ota:svc.${rel.substringAfterLast('.')}",
                    description = what,
                )
            }
        } else {
            logDisabled(module, FeatureCatalog.KEY_OTA_BLOCK, "OucHook")
        }
        if (pending.isEmpty()) return
        engine.begin(apkPath, FeatureCatalog.baselineVersionOf(packageName))
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    private class Task(
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        override val id: String,
        override val description: String,
    ) : MountEngine.Task

    /**
     * 统一短路：不 proceed（入口不执行 = 整链不发生）。
     * 返回值必须匹配原方法类型（libxposed 102 硬约束，见 systemui.md §7.4 实证）：
     * void onReceive → null；int onStartCommand → 2（START_NOT_STICKY，告诉系统别 restart）。
     */
    private fun mountOne(task: Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            engine.judge(module, task, "ota-hook", "入口已短路")
            when (task.returnType) {
                "void" -> null
                else -> 2   // android.app.Service.START_NOT_STICKY
            }
        }
}
