package top.honorhook.xposed.target.mfauth

import android.app.Activity
import android.content.Intent
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
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
        /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
        val session = MountEngine.open("MfaAuthHook", packageName, param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName))
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
        session.attemptMount(listOf(task), module, loader) { t -> mountSplash(session, t, module, loader) }

        // ---- vault 明文探针（2026-11-03 迁移诊断，只读）----
        // 爱码仕 = Aegis 换壳，vault = files/secrets.dat（PBKDF2 409836 轮 + AES-GCM）。
        // 用户不知密码（指纹解锁）⇒ 离线解密不可行 ⇒ 挂框架 javax.crypto.Cipher.doFinal(byte[])
        // （框架类不混淆、加固壳无影响；LSPosed 只在作用域 app 进程内生效）after 截明文：
        // gzip JSON 含 "otpauth" 即 vault 解密产物 → base64 打进 LSPosed 日志，root 侧取走灌模块 prefs。
        val probeTask = object : MountEngine.Task {
            override val className = "javax.crypto.Cipher"
            override val methodName = "doFinal"
            override val paramCount = 1
            // MethodResolver 直连轨按 JVM 名比对返回类型：byte[] = "[B"
            override val returnType = "[B"
            override val id = "mfa:vault.probe"
            override val description = "vault 解密明文截获（迁移诊断，只读日志）"
        }
        session.attemptMount(listOf(probeTask), module, loader) { t -> mountVaultProbe(session, t, module, loader) }
    }

    private fun mountVaultProbe(session: MountSession, task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            val out = chain.proceed() as? ByteArray
            if (out != null && out.size >= 4) {
                val isGzip = out[0] == 0x1f.toByte() && out[1] == 0x8b.toByte()
                val looksJson = out[0] == '{'.code.toByte() || out.size > 8 && String(out, 0, 64, Charsets.UTF_8).contains("{")
                if (isGzip || looksJson) {
                    // 判据日志（挂载日志不受 debug 门控）；去重防解锁流程内重复 doFinal 刷屏
                    if (lastDumped == null || !lastDumped.contentEquals(out)) {
                        lastDumped = out
                        val b64 = android.util.Base64.encodeToString(out, android.util.Base64.NO_WRAP)
                        module.log(android.util.Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX vault-plaintext len=${out.size} gzip=$isGzip b64=$b64")
                    }
                }
            }
            out   // 原样返回，纯旁路
        }

    /** 探针去重记忆（进程级；同明文只打一次）。 */
    private var lastDumped: ByteArray? = null

    private fun mountSplash(session: MountSession, task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            chain.proceed()   // 宿主 onCreate 完整跑完（壳解密后的真实逻辑照常）
            val act = chain.thisObject as? Activity
            // 该 hook 对进程内每个 activity 的 onCreate 都触发：先类名门控再 judge，避免刷屏
            if (act != null && act.javaClass.name == CLS_SPLASH) {
                runCatching {
                    act.startActivity(Intent().setClassName(act, CLS_MAIN))
                    act.finish()
                }.onSuccess { session.judge(module, task, JUDGE_PREFIX, "开屏已跳过，直达主界面") }
            }
            null   // void 方法：必须显式 null
        }
}
