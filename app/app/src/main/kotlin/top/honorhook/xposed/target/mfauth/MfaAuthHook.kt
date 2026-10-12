package top.honorhook.xposed.target.mfauth

import android.app.Activity
import android.content.Intent
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 随身认证 MFA `com.aimazhishi.mfa.authenticator` 开屏广告跳过（逆向实证见
 * `docs/reverse/mfa-authenticator.md`，基线 1.0.5）。
 *
 * 为什么不直接挂 `MfaSplashActivity`：app 是 **360 加固**（manifest 实证 `com.stub.StubApp`
 * 入口 + `libjiagu` so），业务类运行时壳解密后才可解析——挂载时序赌不赢壳解密。
 * 改挂框架 `android.app.Activity.onCreate` 收口点（框架类启动即可解析，零时序风险），
 * intercept 内按运行时类名门控，仅命中开屏 activity 才跳页。
 *
 * 开屏是什么：穿山甲全屏视频广告（真机冷启动实证，容器 id layoutAdContainer）。
 * 跳页 = 广告与 splash 内初始化逻辑一并跳过，落地后需真机回归 TOTP/备份/应用内更新。
 *
 * 实现细节：Intent 用 [Intent.setClassName] 字符串，不 import 目标类（编译期不依赖目标 app）。
 */
object MfaAuthHook : TargetHook {

    override val packageName = "com.aimazhishi.mfa.authenticator"

    /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
    private val engine = MountEngine("MfaAuthHook", packageName)

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "mfa-hook"

    /** LAUNCHER 开屏 activity（manifest 实证）。 */
    private const val CLS_SPLASH = "com.aimazhishi.mfa.authenticator.Activities.MfaSplashActivity"

    /** 主界面（manifest 实证）。 */
    private const val CLS_MAIN = "com.aimazhishi.mfa.authenticator.Activities.MainActivity"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_MFA_SPLASH)) {
            logDisabled(module, FeatureCatalog.KEY_MFA_SPLASH, "MfaAuthHook")
            return
        }
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName))
        val loader = param.defaultClassLoader
        val task = object : MountEngine.Task {
            // 框架基类收口点：启动即可解析，绕开 360 加固的业务类时序
            override val className = "android.app.Activity"
            override val methodName = "onCreate"
            // onCreate(Bundle) 与 (Bundle, PersistableBundle) 两个重载由参数量消歧
            override val paramCount = 1
            override val returnType = "void"
            override val id = "mfa:splash.skip"
            override val description = "冷启动跳过 MfaSplashActivity（直达 MainActivity）"
        }
        engine.attemptMount(listOf(task), module, loader) { t -> mountSplash(t, module, loader) }
    }

    private fun mountSplash(task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            chain.proceed()   // 宿主 onCreate 完整跑完（壳解密后的真实逻辑照常）
            val act = chain.thisObject as? Activity
            // 该 hook 对进程内每个 activity 的 onCreate 都触发：先类名门控再 judge，避免刷屏
            if (act != null && act.javaClass.name == CLS_SPLASH) {
                runCatching {
                    act.startActivity(Intent().setClassName(act, CLS_MAIN))
                    act.finish()
                }.onSuccess { engine.judge(module, task, JUDGE_PREFIX, "开屏已跳过，直达主界面") }
            }
            null   // void 方法：必须显式 null
        }
}
