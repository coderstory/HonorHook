package top.honorhook.xposed.target

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.HookPoint

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
    private val engine = MountEngine("OucHook", packageName, top.honorhook.xposed.data.RemoteConfig)

    /** manifest 前缀（组件类全名 = 此前缀 + 相对路径；manifest 实证未被混淆）。 */
    private const val PKG_HNOUC = "com.hihonor.android.hnouc"

    /**
     * 挂载表（HookPoint 行，ThemeStoreMap 同款写法——adapt_new_version.py 的 NAMED_MOUNT_RE
     * 按 className=/methodName=/paramCount= 具名三参提取，行化后 11 点全量可核对）。
     * 全部 kind=CONST：Receiver 的 onReceive（value=null，void 短路）与 Service 的
     * onStartCommand（value=2，START_NOT_STICKY 防 restart）；实际返回值由 [mountOne]
     * 按 returnType 分派，value 不消费（表口径仍写全，与 ThemeStoreMap 行格式一致）。
     *
     * 有意不收录：CheckNewVersionReceiver（手动检查路径）、HiskytoneVersionDemoReceiver（demo）。
     */
    internal val OUC_POINTS: List<HookPoint> = listOf(
        HookPoint(id = "ota:recv.FirmwareAutoDownloadReceiver", kind = "CONST", feature = FeatureCatalog.KEY_OTA_BLOCK, className = "$PKG_HNOUC.biz.impl.reveiver.FirmwareAutoDownloadReceiver", methodName = "onReceive", paramCount = 2, returnType = "void", value = null, description = "固件自动下载触发（AUTO_DOWNLOAD_ALARM）"),
        HookPoint(id = "ota:recv.NightUpgradeReceiver", kind = "CONST", feature = FeatureCatalog.KEY_OTA_BLOCK, className = "$PKG_HNOUC.biz.impl.reveiver.NightUpgradeReceiver", methodName = "onReceive", paramCount = 2, returnType = "void", value = null, description = "夜间安装调度（NIGHT_UPGRADE_ALARM）"),
        HookPoint(id = "ota:recv.UpgradeRemindReceiver", kind = "CONST", feature = FeatureCatalog.KEY_OTA_BLOCK, className = "$PKG_HNOUC.biz.impl.reveiver.UpgradeRemindReceiver", methodName = "onReceive", paramCount = 2, returnType = "void", value = null, description = "升级提醒（通知/红点复唤）"),
        HookPoint(id = "ota:recv.PowerConnectedReceiver", kind = "CONST", feature = FeatureCatalog.KEY_OTA_BLOCK, className = "$PKG_HNOUC.biz.impl.reveiver.PowerConnectedReceiver", methodName = "onReceive", paramCount = 2, returnType = "void", value = null, description = "充电触发检测（ACTION_POWER_CONNECTED）"),
        HookPoint(id = "ota:recv.DeviceActivatedReceiver", kind = "CONST", feature = FeatureCatalog.KEY_OTA_BLOCK, className = "$PKG_HNOUC.biz.impl.reveiver.DeviceActivatedReceiver", methodName = "onReceive", paramCount = 2, returnType = "void", value = null, description = "设备激活触发检测"),
        HookPoint(id = "ota:recv.SelfUpdateReceiver", kind = "CONST", feature = FeatureCatalog.KEY_OTA_BLOCK, className = "$PKG_HNOUC.biz.impl.reveiver.SelfUpdateReceiver", methodName = "onReceive", paramCount = 2, returnType = "void", value = null, description = "OUC 自升级"),
        HookPoint(id = "ota:recv.CarrierConfigReceiver", kind = "CONST", feature = FeatureCatalog.KEY_OTA_BLOCK, className = "$PKG_HNOUC.biz.impl.reveiver.CarrierConfigReceiver", methodName = "onReceive", paramCount = 2, returnType = "void", value = null, description = "运营商配置变化触发"),
        HookPoint(id = "ota:recv.FirmwareAndAppReceiver", kind = "CONST", feature = FeatureCatalog.KEY_OTA_BLOCK, className = "$PKG_HNOUC.biz.impl.reveiver.FirmwareAndAppReceiver", methodName = "onReceive", paramCount = 2, returnType = "void", value = null, description = "固件推送接收"),
        HookPoint(id = "ota:recv.FirmwareInstallReceiver", kind = "CONST", feature = FeatureCatalog.KEY_OTA_BLOCK, className = "$PKG_HNOUC.biz.impl.reveiver.FirmwareInstallReceiver", methodName = "onReceive", paramCount = 2, returnType = "void", value = null, description = "固件安装触发"),
        // 周期自动版本检测的总调度（AutoCheckService.java:942 onStartCommand 自有覆写实证）
        HookPoint(id = "ota:svc.AutoCheckService", kind = "CONST", feature = FeatureCatalog.KEY_OTA_BLOCK, className = "$PKG_HNOUC.check.auto.AutoCheckService", methodName = "onStartCommand", paramCount = 3, returnType = "int", value = 2, description = "周期自动检测调度"),
        // WiFi 场景自动下载管理（WifiAutoDownloadManageService.java:63 onStartCommand 自有覆写实证）
        HookPoint(id = "ota:svc.WifiAutoDownloadManageService", kind = "CONST", feature = FeatureCatalog.KEY_OTA_BLOCK, className = "$PKG_HNOUC.biz.impl.service.WifiAutoDownloadManageService", methodName = "onStartCommand", paramCount = 3, returnType = "int", value = 2, description = "WiFi 自动下载管理"),
    )

    /** 表行 → 运行时任务（声明式字段直拷；value 不透传——拦截值由 [mountOne] 按 returnType 定）。 */
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
        if (top.honorhook.xposed.data.RemoteConfig.isEnabled(FeatureCatalog.KEY_OTA_BLOCK)) {
            OUC_POINTS.forEach { point -> pending += point.toTask() }
        } else {
            logDisabled(module, FeatureCatalog.KEY_OTA_BLOCK, "OucHook")
        }
        if (pending.isEmpty()) return
        engine.begin(apkPath, FeatureCatalog.baselineVersionOf(packageName))
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /**
     * 统一短路：不 proceed（入口不执行 = 整链不发生）。
     * 返回值必须匹配原方法类型（libxposed 102 硬约束，见 systemui.md §7.4 实证）：
     * void onReceive → null；int onStartCommand → 2（START_NOT_STICKY，告诉系统别 restart）。
     */
    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            engine.judge(module, task, "ota-hook", "入口已短路")
            when (task.returnType) {
                "void" -> null
                else -> 2   // android.app.Service.START_NOT_STICKY
            }
        }
}
