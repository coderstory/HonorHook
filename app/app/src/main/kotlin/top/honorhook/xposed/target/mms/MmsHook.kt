package top.honorhook.xposed.target.mms

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 荣耀短信 com.hihonor.mms（基线 19.0.9.304）——T1 骨架：只观测，不动任何宿主行为。
 *
 * 架构（docs/reverse/sms-ads.md §一，真机实证）：宿主单进程；intelligentmessage 插件 base.apk
 * 映射进 mms 进程，插件类（com.smartsms.* / com.ted.sms.*）同进程可达，hook 只打本包。
 *
 * 两个功能各自独立开关，本骨架不接行为：
 * - [FeatureCatalog.KEY_MMS_AD_OFF] 广告净化（T2/T3 接：底部自定义菜单 / 卡片按钮 / 应用推广卡）
 * - [FeatureCatalog.KEY_MMS_CODE_FREE] 验证码免服务（T4 接；**禁止伪装服务已开启**——用户拒绝
 *   智能信息服务的联网/隐私条款，实现方向是本地复制不依赖服务，见 FeatureCatalog 描述）
 *
 * 判据：真机冷启短信 → `MmsHook: installed N/M`。
 */
object MmsHook : TargetHook {

    override val packageName = "com.hihonor.mms"

    /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("MmsHook", packageName)

    /**
     * 插件环境探针类：mms 侧智能信息控制器，**未混淆**（跨 APK 反射留名，sms-ads.md §一；
     * `getIntelligentIpMessageEnable` 返 1/0 见 SPEC.md M3——返回类型 "int" 为逆向结论，
     * jadx 产物未落盘，T4 实装前须以设备 dex 复核，错则 NOT_RESOLVED 兜底不误伤）。
     */
    private const val CLS_SMART_SMS_UTIL = "com.smartsms.hwcontroller.SmartSmsUtilControl"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        // 双开关独立判定（任一关 → logDisabled 留痕；双关 → 整目标跳过不打 installed 行，
        // 「用户全关」与「模块没注入」的区分由 logDisabled 留痕负责）。
        val adOff = RemoteConfig.isEnabled(FeatureCatalog.KEY_MMS_AD_OFF)
        val codeFree = RemoteConfig.isEnabled(FeatureCatalog.KEY_MMS_CODE_FREE)
        if (!adOff) logDisabled(module, FeatureCatalog.KEY_MMS_AD_OFF, "MmsHook")
        if (!codeFree) logDisabled(module, FeatureCatalog.KEY_MMS_CODE_FREE, "MmsHook")
        if (!adOff && !codeFree) return

        val loader = param.defaultClassLoader
        engine.begin(param.applicationInfo?.sourceDir, HOST_VERSION_BASELINE)

        // 挂载表 1 项（T1 骨架探针）；**以真机 `installed N/M` 的 M 为准。**
        val pending = mutableListOf<MountTask>()
        pending += MountTask(
            className = CLS_SMART_SMS_UTIL,
            methodName = "getIntelligentIpMessageEnable",
            paramCount = 1,  // 签名实证：(Context)——system-files/honor-mms-19.0.9.304-jadx SmartSmsUtilControl.java:515
            returnType = "int",
            id = "mms:SmartSmsUtilControl.getIntelligentIpMessageEnable",
            description = "T1 骨架探针：智能信息插件环境存活检查（proceed 后原值返回，零行为改动）",
        )
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /** hook 编写时的宿主版本基线（sms-ads.md：pm/dumpsys 实证 19.0.9.304，设备 AAK-AN00）。 */
    private const val HOST_VERSION_BASELINE = "19.0.9.304"

    private class MountTask(
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        override val id: String,
        override val description: String,
    ) : MountEngine.Task

    /**
     * 只观测挂载：proceed 跑原方法 → judge 记录返回值（每任务至多 [MountEngine.JUDGE_LOG_PER_TASK]
     * 次，自带节流）→ **原值返回**，零行为改动。around 语义照 Exam8Hook 放行路径：chain.proceed()
     * 裸调不吞原方法异常，挂载级 PROTECTIVE 模式兜底防炸宿主。
     */
    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            val orig = chain.proceed()
            engine.judge(module, task, JUDGE_PREFIX, "当前值=$orig")
            orig
        }

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "mms-hook"
}
