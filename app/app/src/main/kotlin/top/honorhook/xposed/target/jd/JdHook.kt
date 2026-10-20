package top.honorhook.xposed.target.jd

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled

/**
 * 京东 `com.jingdong.app.mall` 开屏广告跳过（逆向实证见 `docs/reverse/jd.md`，基线 16.0.70）。
 *
 * 链路（jadx 实证）：`MainFrameActivity`（launcher）.onCreate → `JDHomeManager.a` → `l.b` →
 * `rv.f.n().k() != null` 才 add `SplashFragment`（开屏广告容器）。hookBefore 短路 `a()` ⇒
 * SplashFragment 永不挂载，全局状态等同「服务端没下发广告」的原生路径。
 *
 * 锚点选择：消费点唯一（`MainFrameActivity.java:995` 单调用方），但方法名 `a` 是 R8 混淆名、
 * 跨版本必漂移 ⇒ 不依赖方法名，按 [MountEngine.DexHint.paramTypes] 签名匹配
 * （paramTypes 会喂给 MethodResolver.matchesParameters 的直连反射轨）。
 * 签名不匹配 = NOT_RESOLVED = 开屏照常（失败安全，不 crash）。
 *
 * 边界：广告数据请求照发，只砍展示；埋点 `rv.b.c("0")` 随之丢失，无功能影响。
 */
object JdHook : TargetHook {

    override val packageName = "com.jingdong.app.mall"

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "jd-hook"

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_JD_SPLASH)) {
            logDisabled(module, FeatureCatalog.KEY_JD_SPLASH, "JdHook")
            return
        }
        /** 共享挂载基建（重试循环/失败分级/汇总日志/方法解析）。 */
        val session = MountEngine.open("JdHook", packageName, param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(packageName))
        val loader = param.defaultClassLoader
        val task = object : MountEngine.Task {
            override val className = "com.jingdong.app.mall.home.JDHomeManager"
            override val methodName = "a"   // R8 混淆名，跨版本会漂移——解析靠下方签名锚点，不靠名字
            override val paramCount = 2
            override val returnType = "void"
            /** 签名锚点（喂直连反射轨 matchesParameters）：混淆名目标不依赖方法名。 */
            override val dex = MountEngine.DexHint(paramTypes = listOf("com.jingdong.common.BaseActivity", "int"))
            override val id = "jd:splash.homeManagerAdd"
            override val description = "跳过开屏 SplashFragment 挂载（JDHomeManager.a 直接短路）"
        }
        session.attemptMount(listOf(task), module, loader) { t -> mountSplash(session, t, module, loader) }
    }

    private fun mountSplash(session: MountSession, task: MountEngine.Task, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { _ ->
            // hookBefore 短路：不调 chain.proceed() ⇒ a() 永不执行，SplashFragment 永不挂载
            session.judge(module, task, JUDGE_PREFIX, "SplashFragment 已短路，开屏广告未挂载")
            null   // void 方法：必须显式 null
        }
}
