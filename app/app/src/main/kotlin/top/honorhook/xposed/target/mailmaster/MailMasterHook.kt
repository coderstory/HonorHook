package top.honorhook.xposed.target.mailmaster

import android.app.Activity
import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网易邮箱大师 `com.netease.mail` 去广告（逆向实证见 `docs/reverse/mailmaster.md`，基线 7.27.1）。
 *
 * 壳背景：app 是网易易盾加固（manifest 实证 `com.netease.nis.wrapper.MyApplication` 入口 + libnesec.so），
 * 业务 dex 46.9MB 但 class_def 被抽空（jadx 仅 33 个壳类），字符串/类型池明文保留 ⇒ 类名全知道、方法体读不到。
 * 不直接挂业务类：挂载时序赌不赢壳解密（同 MfaAuthHook 360 加固模式）。改挂 framework Activity.onCreate
 * 收口点 + 运行时类名门控；另加 TopOn 联盟 SDK 公开 API 层断水（类名/方法名从字符串池+TopOn 公开 API 推断）。
 *
 * 断水点失败安全：B/C/D 都是「彩票挂载」——加密 dex 类在 PackageLoaded 时机壳未解密，首轮 NOT_RESOLVED
 * 靠 MountEngine 重试轮（500ms/2s×2）补挂；易盾解密通常 <1s，赶得上是常态、漏挂由 A 兜底
 * （activity 级闪退，无论广告来源实际展示都被跳过）。
 *
 * 侦察探针：SplashActivity 冷启动必经、壳此时必已解密 ⇒ 运行时反射枚举 10 个业务类的
 * declaredMethods + Constructors 全签名打 `[mm-scout]` 日志 ⇒ T2 据此把信息流挂载点上收精确化
 * （运营位 PopupMaillistAdsExtend/DisplayAds 自渲染链不走 TopOn，B/C/D 杀不到，需侦察后 T2 处理）。
 *
 * 范围：只做去广告（会员缓议，见 mailmaster.md §五·1）。
 */
object MailMasterHook : TargetHook {

    override val packageName = "com.netease.mail"

    /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("MailMasterHook", packageName)

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "mm-hook"

    /** 开屏广告 activity（manifest 实证 `android:name`）。 */
    private const val CLS_SPLASH_AD = "com.netease.mail.ad.launch.ui.SplashAdActivity"

    /** 宿主 launcher 真身（manifest 实证 `DefaultSplashActivity` → `SplashActivity`）。 */
    private const val CLS_HOST_SPLASH = "com.netease.mail.biz.main.SplashActivity"

    /** 侦察探针一次性门控（冷启动命中一次即关闭，防重复反射刷屏）。 */
    private val scoutOnce = AtomicBoolean(false)

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_MAILMASTER_AD_OFF)) {
            logDisabled(module, FeatureCatalog.KEY_MAILMASTER_AD_OFF, "MailMasterHook")
            return
        }
        engine.begin(param.applicationInfo.sourceDir, FeatureCatalog.baselineVersionOf(packageName))
        val loader = param.defaultClassLoader

        // A: framework 收口 —— 框架 Activity.onCreate 始终可解析，绕开易盾加密 dex 的时序问题
        val taskA = object : MountEngine.Task {
            // 框架基类收口点：启动即可解析，零时序风险
            override val className = "android.app.Activity"
            override val methodName = "onCreate"
            override val paramCount = 1
            override val returnType = "void"
            override val id = "mm:splash.finish"
            override val description = "开屏广告页闪退（framework onCreate 门控）"
        }
        // B: 开屏联盟断水 —— TopOn 公开 API，类名/方法名从字符串池+SDK 文档推断
        val taskB = MountEngine.SimpleTask(
            id = "mm:splash.toponOff",
            className = "com.anythink.splashad.api.ATSplashAd",
            methodName = "loadAd",
            paramCount = 0,
            returnType = "void",
            description = "开屏联盟广告请求断水（TopOn 入口短路）",
        )
        // C: 信息流断水 —— MasterNativeAdapter 的存在证明信息流走 TopOn native
        val taskC = MountEngine.SimpleTask(
            id = "mm:infoflow.toponOff",
            className = "com.anythink.nativead.api.ATNative",
            methodName = "loadAd",
            paramCount = 0,
            returnType = "void",
            description = "信息流联盟广告请求断水（TopOn native 入口短路）",
        )
        // D: 信息流断水备胎 —— TopOn loadAd/makeRequest 二选一，哪个命中挂哪个
        val taskD = MountEngine.SimpleTask(
            id = "mm:infoflow.makeRequest",
            className = "com.anythink.nativead.api.ATNative",
            methodName = "makeRequest",
            paramCount = 0,
            returnType = "void",
            description = "信息流断水备胎（TopOn native makeRequest 短路）",
        )

        engine.attemptMount(listOf(taskA, taskB, taskC, taskD), module, loader) { task ->
            when (task.id) {
                "mm:splash.finish" -> mountFramework(task, module, loader)
                "mm:splash.toponOff" -> mountToponOff(task, module, loader)
                "mm:infoflow.toponOff" -> mountToponOff(task, module, loader)
                "mm:infoflow.makeRequest" -> mountToponOff(task, module, loader)
                else -> MountEngine.Result.FAILED
            }
        }
    }

    /** task A: framework Activity.onCreate 门控 —— 开屏广告页闪退 + launcher 真身触发侦察探针。 */
    private fun mountFramework(task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            chain.proceed()   // 宿主 onCreate 完整跑完（壳解密后的真实逻辑照常，不影响业务初始化）
            val act = chain.thisObject as? Activity ?: return@mount null
            when (act.javaClass.name) {
                CLS_SPLASH_AD -> {
                    runCatching { act.finish() }
                        .onSuccess { engine.judge(module, task, JUDGE_PREFIX, "开屏广告页已 finish") }
                }
                CLS_HOST_SPLASH -> {
                    if (scoutOnce.compareAndSet(false, true)) {
                        runScout(act, module)
                    }
                }
            }
            null   // void 方法：必须显式 null
        }

    /** task B/C/D: TopOn 入口短路（hookBefore，不调 proceed —— 广告请求永不发出）。 */
    private fun mountToponOff(task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { _ ->
            // hookBefore 短路：不调 chain.proceed() ⇒ loadAd/makeRequest 永不执行
            engine.judge(module, task, JUDGE_PREFIX, "TopOn 入口已短路")
            null   // void 方法：必须显式 null
        }

    /** 侦察探针：反射枚举 10 个业务类的 declaredMethods + Constructors 全签名打 `[mm-scout]` 日志。
     *  壳此时必已解密（SplashActivity 是冷启动必经的 launcher 真身，业务 ClassLoader 可用）。 */
    private fun runScout(act: Activity, module: XposedModule) {
        runCatching {
            val loader = act.javaClass.classLoader
            val targets = listOf(
                "com.netease.mail.ad.launch.model.MasterAdModel",
                "com.netease.mail.ad.launch.ui.SplashAdActivity",
                "com.netease.mail.ad.launch.entity.LaunchAdInfo",
                "com.netease.mail.ad.infoflow.model.FMailManager",
                "com.netease.mail.ad.infoflow.repository.InfoFlowAdsConfigProvider",
                "com.netease.mail.ad.sdkad.topon.adapter.MasterSplashAdapter",
                "com.netease.mail.ad.sdkad.topon.adapter.MasterNativeAdapter",
                "com.netease.mail.ad.display.entity.pojo.DisplayAds",
                "com.anythink.nativead.api.ATNative",
                "com.anythink.splashad.api.ATSplashAd",
            )
            for (name in targets) {
                runCatching {
                    val cls = Class.forName(name, false, loader)
                    val sigs = mutableListOf<String>()
                    for (m in cls.declaredMethods) {
                        sigs.add("${m.name}(${m.parameterTypes.joinToString(",") { it.simpleName }})->${m.returnType.simpleName}")
                    }
                    for (c in cls.declaredConstructors) {
                        sigs.add("<init>(${c.parameterTypes.joinToString(",") { it.simpleName }})")
                    }
                    if (sigs.isEmpty()) {
                        module.log(Log.INFO, MountEngine.TAG, "[mm-scout] $name | (无公开方法/构造器)")
                    } else {
                        val chunked = sigs.chunked(12)
                        for ((i, chunk) in chunked.withIndex()) {
                            val page = if (chunked.size > 1) " (${i + 1}/${chunked.size})" else ""
                            module.log(Log.INFO, MountEngine.TAG, "[mm-scout] $name | ${chunk.joinToString("; ")}$page")
                        }
                    }
                }.onFailure {
                    // 加密类未解密属预期，INFO 非 WARN
                    module.log(Log.INFO, MountEngine.TAG, "[mm-scout] miss: $name")
                }
            }
        }.onFailure { t ->
            // 侦察体整体异常兜底：绝不能炸宿主
            module.log(Log.WARN, MountEngine.TAG, "[mm-scout] 侦察探针异常: ${t.message}", t)
        }
    }
}