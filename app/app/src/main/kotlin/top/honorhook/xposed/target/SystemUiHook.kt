package top.honorhook.xposed.target

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountEngine.DexHint
import top.honorhook.xposed.data.RemoteConfig
import java.util.concurrent.atomic.AtomicBoolean

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

    /** 未混淆的信号图标绑定器（jadx 实证，基线 17.0.0.1）。静态方法，6 参无重载歧义。 */
    private const val CLS_SIGNAL_ICON_BINDER =
        "com.android.systemui.statusbar.pipeline.mobile.ui.binder.SignalIconBinder"

    /** SignalIconBinder.bind(int, View, MobileDateModel, LocationBasedMobileViewModel, int, MobileViewLogger) */
    private const val METHOD_BIND = "bind"
    private const val BIND_PARAM_COUNT = 6

    /** 单卡信号视图布局里的 5G/4G 类型标 view id（Resource entry name，运行时 getIdentifier 取）。 */
    private const val ID_MOBILE_TYPE = "mobile_type"

    /** 副卡 = 卡槽 2（SubscriptionModel.slotId 0-based；SimData slotId=1/subId=2 真机实证）。 */
    private const val HIDDEN_SLOT_ID = 1

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val apkPath = param.applicationInfo.sourceDir

        val pending = mutableListOf<MountTask>()

        // U1 副卡隐藏（数据层：updateSubs 入参剔除 slotId==1）
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_SIM2)) {
            pending += MountTask(
                className = CLS_MOBILE_DATA_CONTROLLER,
                methodName = "updateSubs",
                paramCount = 1,
                returnType = "void",
                id = "systemui:mobileDataController.updateSubs",
                description = "U1 副卡(SIM2)状态栏信号图标隐藏（updateSubs 入参剔除 slotId==1，≥2 卡才剔；状态栏/通知头/锁屏 cluster 同源生效）",
                dex = DexHint(paramTypes = listOf("java.util.List")),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_HIDE_SIM2, "SystemUiHook")
        }

        // U2 隐藏 5G/4G 类型标（视图层：bind 入参的 View 里 mobile_type 置 GONE）
        //   bind 只在每卡视图创建时调一次（随 updateSubs 重建），故置 GONE 即持久，
        //   不受后续 StateFlow 重绑影响（宿主重绑只改 drawable 与 visibility，drawable 为空时才 setVisibility(0)）。
        // 2026-10-11 调试期临时关闭：先用「只留 U1」做对照实验，隔离 SignalIconBinder.bind 上的
        //   NPE。已查明根因：NPE 来自 U3 的 addSignalView 误返回 null（非 void 方法），与 U2 无关，
        //   U2 已恢复启用。
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_5G)) {
            pending += MountTask(
                className = CLS_SIGNAL_ICON_BINDER,
                methodName = METHOD_BIND,
                paramCount = BIND_PARAM_COUNT,
                returnType = "void",
                id = "systemui:signalIconBinder.bind",
                description = "U2 隐藏 5G/4G 类型标（bind 入参 View 内 mobile_type 置 GONE；单卡双卡一律生效）",
                dex = DexHint(classStrings = listOf("signal_unit_normal_view")),
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_HIDE_5G, "SystemUiHook")
        }

        if (pending.isEmpty()) return
        engine.begin(apkPath, HOST_VERSION_BASELINE)

        if (pending.isNotEmpty()) {
            engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
        }
    }

    private class MountTask(
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        override val id: String,
        override val description: String,
        override val dex: DexHint? = null,
    ) : MountEngine.Task

    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "systemui-hook"

    /** 挂载点 id（mountOne 按 id 分派）。 */
    private const val ID_HIDE_5G = "systemui:signalIconBinder.bind"

    /** slotId 字段读不到的一次性告警标志（只报一次，避免每次订阅变化都刷屏）。 */
    private val unreadableWarned = AtomicBoolean(false)

    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        when (task.id) {
            ID_HIDE_5G -> mountHide5G(task, module, loader)
            else -> mountHideSim2(task, module, loader)
        }

    /** U1 副卡隐藏：updateSubs 入参剔除 slotId==1（数据层，宿主重建时自然用改过的数据）。 */
    private fun mountHideSim2(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // ⚠️ updateSubs 是 void 方法：hook 回调**必须返回 null**。
            // 2026-10-11 真机崩溃实证：lambda 隐式返回了最后一行 proceedWith(...) 的 Object，
            // libxposed 抛 `ClassCastException: Return value's type from hook callback does not
            // match the hooked method` → SystemUI 反复崩溃。下面的每个分支都显式 `return@mount null`。
            val orig = runCatching { chain.getArg(0) as? List<*> }.getOrNull()
            if (orig == null || orig.size < 2) {
                chain.proceed()
                return@mount null
            }
            var unreadable = 0
            val kept = orig.filter { sub ->
                val slot = sub?.let {
                    runCatching { it.javaClass.getField("slotId").getInt(it) }.getOrNull()
                }
                if (slot == null) unreadable++
                slot != HIDDEN_SLOT_ID
            }
            if (kept.size == orig.size) {
                // 一个都没剔掉：要么本来只有主卡（正常），要么 slotId 取不到（字段非 public / 版本漂移）。
                // 后者是静默 no-op —— 判据行照打 installed 1/1 却毫无效果，必须留痕才排得动。
                if (unreadable > 0) warnUnreadableOnce(module)
                chain.proceed()
            } else {
                engine.judge(module, task, JUDGE_PREFIX, "剔除副卡后 ${kept.size}/${orig.size} 卡")
                // 传过滤副本：宿主内部只逐元素拷贝进 mSubscriptionInfoList，不持有入参引用，副本安全
                chain.proceedWith(chain.thisObject, arrayOf(kept))
            }
            null   // void 方法：必须显式 null
        }

    /**
     * U2 隐藏 5G/4G 类型标：`SignalIconBinder.bind` 的第 2 参是每卡的 SignalUnitNormalView，
     * 在宿主把 `mobile_type` 交给协程绑定**之前**置 GONE——之后宿主再 setImageDrawable/setVisibility
     * 都作用在同一个已隐藏的 View 上，不会把它显示回来（drawable 为 null 时才 setVisibility(0)，
     * 而 5G 标有 drawable；此处以 GONE + 每次 bind 重置双保险）。
     *
     * 只读不改：不改宿主数据，纯粹让这个子视图不占位；宿主其它逻辑不受影响。
     */
    private fun mountHide5G(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // SignalIconBinder.bind 是 void 方法：回调必须返回 null（见 mountHideSim2 的崩溃实证注释）
            val cardView = runCatching { chain.getArg(1) as? android.view.View }.getOrNull()
            if (cardView != null) {
                val hidden = hideMobileType(cardView)
                if (hidden) {
                    engine.judge(module, task, JUDGE_PREFIX, "5G 类型标已隐藏")
                } else {
                    // view id 读不到 = 布局结构漂移；静默 no-op 必须留痕（installed N/M 照打但无效果）
                    warnMobileTypeMissing(module)
                }
            }
            chain.proceed()
            null   // void 方法：必须显式 null
        }

    /** 在每卡视图里找 `mobile_type` 并置 GONE；返回是否命中。 */
    private fun hideMobileType(cardView: android.view.View): Boolean {
        val typeView = findByEntryName(cardView, ID_MOBILE_TYPE) ?: return false
        runCatching {
            typeView.visibility = android.view.View.GONE
            // 布局占位一并去掉，否则 GONE 的 View 仍留 margin，宽度不省
            (typeView.layoutParams as? android.view.ViewGroup.MarginLayoutParams)
                ?.let { (typeView.layoutParams as android.view.ViewGroup.MarginLayoutParams).marginEnd = 0 }
        }
        return true
    }

    /** 按资源 entry name 递归查子视图（运行时 getIdentifier，不硬编码 R.id 数值——资源 id 逐版漂移）。 */
    private fun findByEntryName(root: android.view.View, entryName: String): android.view.View? {
        val res = root.resources
        val targetId = runCatching { res.getIdentifier(entryName, "id", root.context.packageName) }.getOrNull()
        if (targetId == null || targetId == 0) return null
        if (root.id == targetId) return root
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                findByEntryName(root.getChildAt(i), entryName)?.let { return it }
            }
        }
        return null
    }

    /** mobile_type 视图找不到的一次性告警（布局结构漂移信号，不刷屏）。 */
    private val mobileTypeMissingWarned = AtomicBoolean(false)

    private fun warnMobileTypeMissing(module: XposedModule) {
        if (mobileTypeMissingWarned.compareAndSet(false, true)) {
            module.log(
                Log.WARN, MountEngine.TAG,
                "systemui-hook 未生效：单卡视图内找不到 $ID_MOBILE_TYPE（布局漂移或 view id 改名），5G 类型标未隐藏",
            )
        }
    }

    private fun warnUnreadableOnce(module: XposedModule) {
        if (unreadableWarned.compareAndSet(false, true)) {
            module.log(
                Log.WARN, MountEngine.TAG,
                "systemui-hook 未生效：SubscriptionModel.slotId 字段不可读（非 public 或版本漂移），副卡图标未剔除",
            )
        }
    }
}
