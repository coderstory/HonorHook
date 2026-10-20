package top.honorhook.xposed.target

import android.app.Activity
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession

/**
 * 荣耀升级引导 `com.hihonor.android.upgradeguide`（16.0.0.005，aapt2 实证）：整包只有
 * 一个 `UpgradeWizardActivity`（manifest 唯一 activity，未混淆）——解锁后全屏「发现新版本」
 * 引导页的宿主。挂 onCreate 放行原逻辑后 finish：页面闪现即退，引导流程无法展开。
 * 类名 manifest 实证，零 DexKit。
 */
object UpgradeGuideHook : TargetHook {

    override val packageName = "com.hihonor.android.upgradeguide"


    private const val CLS_WIZARD = "com.hihonor.android.upgradeguide.UpgradeWizardActivity"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!top.honorhook.xposed.data.RemoteConfig.isEnabled(FeatureCatalog.KEY_OTA_BLOCK)) {
            logDisabled(module, FeatureCatalog.KEY_OTA_BLOCK, "UpgradeGuideHook")
            return
        }
        val session = MountEngine.open("UpgradeGuideHook", packageName, param.applicationInfo.sourceDir, FeatureCatalog.baselineVersionOf(packageName))
        val loader = param.defaultClassLoader
        val task = object : MountEngine.Task {
            override val className = CLS_WIZARD
            override val methodName = "onCreate"
            override val paramCount = 1
            override val returnType = "void"
            override val id = "ota:guide.wizardOnCreate"
            override val description = "升级引导页启动即 finish（全屏『发现新版本』引导）"
        }
        session.attemptMount(listOf(task), module, loader) { t -> mountWizard(session, t, module, loader) }
    }

    private fun mountWizard(session: MountSession, task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            chain.proceed()   // 先走宿主 onCreate（super 链必须完整，防状态机半初始化）
            runCatching { (chain.thisObject as? Activity)?.finish() }
                .onSuccess { session.judge(module, task, "ota-hook", "引导页已 finish") }
            null   // void 方法：必须显式 null
        }
}
