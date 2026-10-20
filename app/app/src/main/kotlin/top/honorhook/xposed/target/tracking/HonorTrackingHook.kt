package top.honorhook.xposed.target.tracking

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 荣耀埋点 SDK（com.hihonor.hianalytics）通杀拦截。
 *
 * 实证（8 app dex 扫描 + jadx 反编译 browser 基线）：SDK 跨 6 app（浏览器/日历/天气/游戏中心/视频/云音乐），
 * 公共 facade（HiAnalyticsManager / HiAnalyticsInstance$Builder / HiAnalyticsInstanceEx$Builder）未混淆且逐包全量命中；
 * 事件实现类是混淆名（hnha.d / hnha.c，各 app 混淆映射不同）→ 不能直挂，经 facade 返回值运行时取具体类。
 *
 * 挂载链：facade 工厂（after）→ chain.result 取 HiAnalyticsInstance 实例 →
 * 按接口未混淆方法名在具体类解析上报方法，挂空实现（不调 proceed = 事件不落库不出网）。
 * 同一具体类只挂一次；接口方法名是 SDK 公共契约，跨版本稳于混淆类名。
 */
class HonorTrackingHook(override val packageName: String) : TargetHook {


    /** 已完成动态阻断的具体类（幂等门控：create 与 getInstanceByTag 会拿到同一实例）。 */
    private val blockedClasses = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_TRACK_BLOCK)) {
            logDisabled(module, FeatureCatalog.KEY_TRACK_BLOCK, "HonorTrackingHook")
            return
        }
        val session = MountEngine.open("HonorTrackingHook", packageName, param.applicationInfo?.sourceDir)

        // 挂载表 3 项（facade 工厂，未混淆）；以真机 installed N/M 为准。
        val pending = listOf(
            MountEngine.SimpleTask(
                className = "com.hihonor.hianalytics.process.HiAnalyticsInstance${'$'}Builder",
                methodName = "create",
                paramCount = 1,
                returnType = CLS_INSTANCE,
                id = "track:HiAnalyticsInstance.Builder.create",
                description = "埋点实例工厂收口：app 自建 tag 实例全量阻断上报方法",
            ),
            MountEngine.SimpleTask(
                className = "com.hihonor.hianalytics.process.HiAnalyticsManager",
                methodName = "getInstanceByTag",
                paramCount = 1,
                returnType = CLS_INSTANCE,
                id = "track:HiAnalyticsManager.getInstanceByTag",
                description = "埋点实例获取收口：含 _default_config_tag 默认实例（v2 静态 API 亦经此）",
            ),
            MountEngine.SimpleTask(
                className = "com.hihonor.hianalytics.process.HiAnalyticsInstanceEx${'$'}Builder",
                methodName = "create",
                paramCount = 0,
                returnType = "com.hihonor.hianalytics.process.HiAnalyticsInstanceEx",
                id = "track:HiAnalyticsInstanceEx.Builder.create",
                description = "埋点实例Ex工厂收口：autoCollect 自动采集实例（_instance_ex_tag）",
            ),
        )
        session.attemptMount(pending, module, loader) { task -> mountOne(session, task, module, loader) }
    }

    private fun mountOne(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            // 原工厂照常执行拿返回实例；仅追加动态阻断，不改工厂行为
            val instance = chain.proceed()
            instance?.let { blockReporting(module, loader, it) }
            session.judge(module, task, JUDGE_PREFIX)
            instance
        }

    /** 按接口未混淆方法名在具体类解析上报方法并挂空实现；同具体类只挂一次。 */
    private fun blockReporting(module: XposedModule, loader: ClassLoader, instance: Any) {
        val concrete = instance.javaClass
        if (!blockedClasses.add(concrete.name)) return
        val itf = runCatching { loader.loadClass(CLS_INSTANCE) }.getOrNull()
        if (itf == null) {
            blockedClasses.remove(concrete.name)
            return
        }
        var count = 0
        for (m in itf.methods) {
            if (m.name !in BLOCK_METHODS) continue
            runCatching {
                module.hook(concrete.getMethod(m.name, *m.parameterTypes))
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .setId("track:${concrete.simpleName}.${m.name}")
                    .intercept { _ -> null }
                count++
            }
        }
        module.log(Log.INFO, MountEngine.TAG, "HonorTrackingHook: 已阻断 $concrete 埋点方法 $count 个")
    }

    private companion object {
        const val JUDGE_PREFIX = "track-hook"
        const val CLS_INSTANCE = "com.hihonor.hianalytics.process.HiAnalyticsInstance"

        /** 上报面白名单（接口未混淆方法名）：事件/会话/点击/曝光 + 设备标识注入；清理与配置类（clearData、setAppBrandId 等）不拦。 */
        val BLOCK_METHODS = setOf(
            "onEvent", "onEvent2", "onEventNew",
            "onStreamEvent", "onStreamEvent2", "onStreamEventNoCache",
            "onResume", "onPause", "onBackground", "onForeground",
            "onFragmentResume", "onFragmentPause", "onViewClick",
            "onReport", "onReportNew",
            "setOAID", "setUpid", "setHonorOAID", "setOAIDTrackingFlag",
            "setCommonProp", "addExposureView", "setExposureIdentifier",
        )
    }
}
