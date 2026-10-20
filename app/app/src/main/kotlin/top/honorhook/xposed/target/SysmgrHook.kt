package top.honorhook.xposed.target

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import top.honorhook.xposed.data.HookPoint

/**
 * 荣耀系统管家服务 `com.hihonor.systemmanager`（17.0.0.414）：病毒检测/查杀/弹窗三路全禁。
 *
 * 目标定位：system-files/sysmgrsvc-jadx 反编译实证（2026-10-13）。本 app（116MB）是系统管家服务进程，
 * 承载杀毒引擎（com.hihonor.antivirus.* 类名可读，未混淆），也是「发现病毒应用」弹窗的归属——
 * R.string.risk_dialog_virus_title 全系统唯一存在于此。
 *
 * 为什么选这三点（三路全覆盖，镜像宿主原生语义）：
 * - hideVirusPrefer：宿主生产代码自带 `msc.config.hide_virus` 总开关（海外/定制版在用）
 *   ——hook 恒返 true，触发宿主自身的杀毒全禁用逻辑（主服务 stopSelf + 指令拒收 + 安装检测早退 + 闲时扫描早退）。
 * - CheckUninstallApkCaller.call：宿主自带 calledBySubUser 分支直接 `generateResult(0,0)` 返回未检出
 *   ——hook 构造同构 Bundle 返回，镜像宿主原生「跳过扫描」语义（安装器 bz1 消费侧拿到 result_code=0 等同未检出）。
 * - VirusNotifyService.onStartCommand：弹窗三路收口（VIRUS / MALICIOUS / MULTI_VIRUS_NOTIFY），
 *   覆盖闲时全盘扫描、手动扫描、云推等所有触发源——hook 恒返 START_NOT_STICKY(2)，
 *   宿主现有所有 return 路径本就是 2，语义不冲突。
 *
 * 影响面声明：杀毒能力整体失效是**用户要求的预期行为**（开关名「病毒检测全禁用」即此意）；
 * 反诈/AI 行为采集链不在本目标范围（走独立模块，暂未覆盖）。
 * 历史管控标记（VirusAppsManager.insertVirusApp 写的病毒库落库）不回滚——存量标记需用户在系统管家设置里手动清除。
 */
object SysmgrHook : TargetHook {

    override val packageName = "com.hihonor.systemmanager"

    /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("SysmgrHook", packageName, top.honorhook.xposed.data.RemoteConfig)

    /** 判据日志前缀（真机按此前缀过滤命中行）。 */
    private const val JUDGE_PREFIX = "sysmgr-hook"

    /**
     * 挂载表 3 点（HookPoint 行，OucHook 同款具名参数写法）。
     * 全部 kind=CONST：不调原方法，直接返回替换值（由 [mountOne] 按 returnType 分派）。
     * DexKit 锚点均为方法体独有日志串/参数类型序列——类名可读（com.hihonor.antivirus.* 未混淆），
     * 锚点仅作兜底（宿主 OTA 若混淆类名仍可检索，不强依赖）。
     */
    internal val SYSMGR_POINTS: List<HookPoint> = listOf(
        HookPoint(
            id = "sysmgr:AntivirusTools.hideVirusPrefer",
            kind = "CONST",
            feature = FeatureCatalog.KEY_SYSMGR_ANTIVIRUS,
            className = "com.hihonor.antivirus.utils.AntiVirusTools",
            methodName = "hideVirusPrefer",
            paramCount = 0,
            returnType = "boolean",
            value = null,
            description = "宿主病毒功能总开关恒开（msc.config.hide_virus 门：杀毒服务不启动/安装时检测早退/闲时扫描早退）",
            dexStrings = listOf("msc.config.hide_virus"),
        ),
        HookPoint(
            id = "sysmgr:CheckUninstallApkCaller.scanClean",
            kind = "CONST",
            feature = FeatureCatalog.KEY_SYSMGR_ANTIVIRUS,
            className = "com.hihonor.antivirus.CheckUninstallApkCaller",
            methodName = "call",
            paramCount = 1,
            returnType = "android.os.Bundle",
            value = null,
            description = "checkUninstalledApk 服务端恒返未检出（外部预检——安装器 bz1 等——全链不再扫描）",
            dexStrings = listOf("checkUninstalledApk called by sub user, skip the scan and return ok"),
            dexParamTypes = listOf("android.os.Bundle"),
        ),
        HookPoint(
            id = "sysmgr:VirusNotifyService.noDialog",
            kind = "CONST",
            feature = FeatureCatalog.KEY_SYSMGR_ANTIVIRUS,
            className = "com.hihonor.securitycenter.antivirus.securitythreats.ui.VirusNotifyService",
            methodName = "onStartCommand",
            paramCount = 3,
            returnType = "int",
            value = 2,
            description = "病毒弹窗服务指令全拒（VIRUS/MALICIOUS/MULTI_VIRUS_NOTIFY 三路收口，云推等未知触发源也不弹）",
            dexStrings = listOf("onStartCommand virus notify pkg: "),
            dexParamTypes = listOf("android.content.Intent", "int", "int"),
        ),
    )

    /** 表行 → 运行时任务（DexHint 四维按非空聚合，InstallerHook.toInstallerTask 同款写法）。 */
    internal fun HookPoint.toTask(): MountEngine.SimpleTask = MountEngine.SimpleTask(
        id = id,
        className = className,
        methodName = methodName,
        paramCount = paramCount,
        returnType = returnType,
        description = description,
        group = group,
        feature = feature,
        dex = DexHint(
            strings = dexStrings,
            paramTypes = dexParamTypes,
            classStrings = dexClassStrings,
            classFieldTypes = dexClassFieldTypes,
        ).takeIf {
            dexStrings != null || dexParamTypes != null || dexClassStrings != null || dexClassFieldTypes != null
        },
    )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val apkPath = param.applicationInfo.sourceDir

        val pending = mutableListOf<MountEngine.SimpleTask>()
        if (top.honorhook.xposed.data.RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSMGR_ANTIVIRUS)) {
            SYSMGR_POINTS.forEach { point -> pending += point.toTask() }
        } else {
            logDisabled(module, FeatureCatalog.KEY_SYSMGR_ANTIVIRUS, "SysmgrHook")
        }
        if (pending.isEmpty()) return
        engine.begin(apkPath, FeatureCatalog.baselineVersionOf(packageName))
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /**
     * 按 returnType 分派替换值（全部 CONST 语义——不调原方法，直接返回强制值）：
     *   boolean → true（病毒总开关恒开）
     *   int → 2（START_NOT_STICKY，宿主 onStartCommand 所有路径本就 return 2）
     *   android.os.Bundle → 构造 {result_code:0, virus_type:0}（与宿主 CheckUninstallApkCaller.generateResult
     *     实证一致，同 calledBySubUser 跳过分支的返回值形状）；构造失败则 [chain.proceed] 放行原方法
     *     （降级为有检测，安装流程不死锁——同 InstallerHook CHECK_BYPASS 的"失败放行"语义）。
     */
    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            when (task.returnType) {
                "boolean" -> {
                    engine.judge(module, task, JUDGE_PREFIX)
                    true
                }
                "int" -> {
                    engine.judge(module, task, JUDGE_PREFIX)
                    2
                }
                "android.os.Bundle" -> {
                    val bundle = runCatching {
                        android.os.Bundle().apply {
                            putInt("result_code", 0)
                            putInt("virus_type", 0)
                        }
                    }.getOrNull()
                    if (bundle != null) {
                        engine.judge(module, task, JUDGE_PREFIX)
                        bundle
                    } else {
                        chain.proceed()
                    }
                }
                else -> chain.proceed()
            }
        }
}