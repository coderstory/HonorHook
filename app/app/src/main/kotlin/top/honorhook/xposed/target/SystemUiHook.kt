package top.honorhook.xposed.target

import android.util.Log
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import java.util.concurrent.atomic.AtomicInteger

/**
 * 荣耀 SystemUI `com.android.systemui`（基线 17.0.0.1）：隐藏副卡（SIM2 卡槽）状态栏信号图标。
 *
 * 渲染链（jadx 实证，system-files/systemui-jadx）：
 *   HnMobileUiAdapter.start() 收集 filteredSubscriptions（Kotlin Flow，订阅列表每次变化重发）
 *   → MobileDataControllerImpl.updateSubs(List<SubscriptionModel>)（HnMobileUiAdapter.java:69 唯一调用方）
 *   → 清空重建：mSubscriptionInfoList.clear()+add（过滤后列表即存储值，addCallback 路径同源）
 *   → 逐卡 mobileCallBack.addSignalView(slotId) + SignalIconBinder.bind(...)
 *
 * 故 updateSubs(List) 是所有 SignalClusterView（状态栏 / 通知头 / 锁屏 cluster）每卡图标的
 * 唯一重建出口 —— before 改参为「剔除 slotId==1 后的副本」单点生效；传副本而非原地改，
 * 因为无 VSIM 时 HnMobileUiAdapter 直接把上游 Flow 发射的原列表透传（原地改会污染上游状态）。
 *
 * 语义：仅当列表 ≥2 卡时剔副卡（单独用 SIM2 时图标照常显示）；副卡插拔/启停后 updateSubs
 * 会因订阅列表变化重发而重新走本点，无需额外失效逻辑。QS 网络瓷贴走 pipeline ViewModel 不经此处，双卡功能不受影响。
 *
 * 重载消歧：updateSubs 有 (List) / (MobileCallBack) 两个 1 参 void 重载，MethodResolver
 * 直连轨只按参数个数/返回值匹配（firstOrNull 顺序不定会撞重载）——本目标按参数类型 List 精确解析。
 */
object SystemUiHook : TargetHook {

    override val packageName = "com.android.systemui"

    /** 共享挂载基建（重试循环/失败分级/汇总日志）。 */
    private val engine = MountEngine("SystemUiHook", packageName)

    /** hook 编写时的宿主版本基线（dumpsys package 实证 2026-10-10）；OTA 超出时 MountEngine 首轮挂载打 WARN。 */
    private const val HOST_VERSION_BASELINE = "17.0.0.1"

    /** 未混淆实现类（Dagger 绑定，jadx classes.dex 实证）。 */
    private const val CLS_MOBILE_DATA_CONTROLLER = "com.android.systemui.plugins.signal.MobileDataControllerImpl"

    /** 副卡 = 卡槽 2（SubscriptionModel.slotId 0-based；SimData slotId=1/subId=2 真机实证）。 */
    private const val HIDDEN_SLOT_ID = 1

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_SIM2)) return
        engine.begin(param.applicationInfo?.sourceDir, HOST_VERSION_BASELINE)

        val pending = listOf(
            MountTask(
                className = CLS_MOBILE_DATA_CONTROLLER,
                methodName = "updateSubs",
                paramCount = 1,
                returnType = "void",
                id = "systemui:mobileDataController.updateSubs",
                description = "U1 副卡(SIM2)状态栏信号图标隐藏（updateSubs 入参剔除 slotId==1，≥2 卡才剔；状态栏/通知头/锁屏 cluster 同源生效）",
            ),
        )
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    private class MountTask(
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        override val id: String,
        override val description: String,
    ) : MountEngine.Task

    /** 判据日志节流（前 2 次）。 */
    private val judgeCount = AtomicInteger(0)

    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result {
        // 按参数类型消歧（见类注释重载说明）；未命中走 NOT_RESOLVED 重试，与 engine.resolveTask 同语义
        val method = runCatching {
            loader.loadClass(task.className).declaredMethods.firstOrNull {
                it.name == task.methodName &&
                    it.parameterCount == 1 &&
                    it.parameterTypes[0] == java.util.List::class.java
            }
        }.getOrNull() ?: run {
            module.log(Log.WARN, MountEngine.TAG, "hook 未挂载（类/方法不存在，版本差异，将重试）: ${task.description}")
            return MountEngine.Result.NOT_RESOLVED
        }
        return runCatching {
            module.hook(method)
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .setId(task.id)
                .intercept { chain ->
                    val orig = runCatching { chain.getArg(0) as? List<*> }.getOrNull()
                        ?: return@intercept chain.proceed()
                    if (orig.size < 2) return@intercept chain.proceed()
                    val kept = orig.filter { sub ->
                        val slot = sub?.let { runCatching { it.javaClass.getField("slotId").getInt(it) }.getOrNull() }
                        slot != HIDDEN_SLOT_ID
                    }
                    if (kept.size == orig.size) return@intercept chain.proceed()
                    if (judgeCount.incrementAndGet() <= 2) {
                        module.log(Log.INFO, MountEngine.TAG, "systemui-hook 生效: ${task.id}（剔除副卡后 ${kept.size}/${orig.size} 卡）")
                    }
                    // 传过滤副本：宿主内部只逐元素拷贝进 mSubscriptionInfoList，不持有入参引用，副本安全
                    chain.proceedWith(chain.thisObject, arrayOf(kept))
                }
        }.fold(
            onSuccess = { MountEngine.Result.OK },
            onFailure = { t ->
                module.log(
                    Log.WARN, MountEngine.TAG,
                    "hook 挂载失败（不阻断其他 hook）: ${task.description} — ${t.message}", t,
                )
                MountEngine.Result.FAILED
            },
        )
    }
}
