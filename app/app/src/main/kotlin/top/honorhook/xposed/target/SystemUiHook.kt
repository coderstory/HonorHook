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

    /** 类型标收口点（U2b）：所有图标 drawable 设置的必经静态工具（jadx 实证，类/方法未混淆）。 */
    private const val CLS_SIGNAL_UTILS = "com.android.systemui.utils.HnSignalUtils"
    private const val METHOD_SET_ICON_FOR_VIEW = "setIconForView"

    /**
     * 类型标收口点（U2c，2026-10-12 真机补）：网络类型图标有两条渲染路——Icon.Resource 走
     * setIconForView（U2b 已收），Icon.Loaded 走 IconViewBinder.bind → setImageDrawable **直接绕过
     * setIconForView**（SignalIconBinder C01821 case 1 双分支实证）⇒ U2b 单点不够。挂
     * IconViewBinder.bind（named 类，common/ui/binder）一并覆盖 Resource/Loaded 两路。
     */
    private const val CLS_ICON_VIEW_BINDER = "com.android.systemui.common.ui.binder.IconViewBinder"

    /** 副卡 = 卡槽 2（SubscriptionModel.slotId 0-based；SimData slotId=1/subId=2 真机实证）。 */
    private const val HIDDEN_SLOT_ID = 1

    /** U4：Honor 通知管线过滤器实现（未混淆；HwDependencyFactory 唯一绑定，全工程无第二子类，jadx classes2.dex 实证）。 */
    private const val CLS_HW_NOTIFICATION_FILTER =
        "com.android.systemui.statusbar.notification.HwNotificationFilterImpl"

    /** U4b：通知中心（荣耀自建 dynamicserver 管线）真正的 shade 仲裁——isDisableShownInShade 即宿主
     *  自带 hw_disable_ntf_show_in_shade 标志的读口；2026-10-12 真机实证只挂 U4 判据命中但通知中心仍渲染。 */
    private const val CLS_HW_NOTIFICATION_FEATURE =
        "com.android.systemui.statusbar.notification.HwNotificationFeatureImpl"

    /** U5：手电筒低电量门宿主类（canOpenFlashLight 覆写所在；基类 StatusBar 同名方法是恒 true 的 stub，无需另挂）。 */
    private const val CLS_HW_PHONE_STATUSBAR = "com.android.systemui.statusbar.phone.HwPhoneStatusBar"

    /** 充电通知发帖方 = 系统管家（dumpsys 实证 2026-10-12：id=200001 title「极速充电」action「本次关闭」）。 */
    private const val CHARGE_NOTIF_PKG = "com.hihonor.systemmanager"
    private const val CHARGE_NOTIF_ID = 200001

    /** 热点/USB 调试通知发帖方 pkg = system_server（android）；热点锚点用 AOSP 硬编码 group 串（比渠道 id 稳）。 */
    private const val PKG_ANDROID = "android"
    private const val HOTSPOT_NOTIF_GROUP = "TetheredNotification"

    /** 手电筒低电量阈值（与宿主同款 5%，仅用于判据日志打点，不影响放行语义）。 */
    private const val LOW_BATTERY_THRESHOLD = 5

    /** 通知身份探针每 key 一条、总量封顶（防通知风暴刷爆日志）。 */
    private const val PROBE_NOTIF_MAX = 40

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val apkPath = param.applicationInfo.sourceDir

        // U6 状态栏双排信号（独立文件，渲染方案移植自 com.mod.dualsignal 3.3）：按开关转发，
        // 自带引擎与 installed 汇总行（SystemUiHook·U6），与本文件的 U1/U2/U4/U5 挂载互不计数。
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_DUAL_SIGNAL)) {
            DualSignalFeature.install(module, param)
        } else {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_DUAL_SIGNAL, "SystemUiHook")
        }

        val pending = mutableListOf<MountEngine.SimpleTask>()

        // U1 副卡隐藏（数据层：updateSubs 入参剔除 slotId==1）
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_SIM2)) {
            pending += MountEngine.SimpleTask(
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
            pending += MountEngine.SimpleTask(
                className = CLS_SIGNAL_ICON_BINDER,
                methodName = METHOD_BIND,
                paramCount = BIND_PARAM_COUNT,
                returnType = "void",
                id = "systemui:signalIconBinder.bind",
                description = "U2 隐藏 5G/4G 类型标（bind 入参 View 内 mobile_type 置 GONE；单卡双卡一律生效）",
                dex = DexHint(classStrings = listOf("signal_unit_normal_view")),
            )
            // U2b 类型标收口（2026-10-12 systemui-jadx 实证）：只在 bind() 置一次 GONE 不够——
            // mobile_type 可见性由网络类型 StateFlow 驱动：SignalIconBinder$bind$1$2$1$4 收集
            // commonImpl.getNetworkTypeIcon()，每次发射走 C01821 case 1：
            //   IconViewBinder.bind(resource, view)（Icon.Resource → setIconForView 设 drawable）
            //   随后 view.setVisibility(0)（SignalIconBinder.java:437-439）——首条发射即把 GONE 复原，
            //   U2 的 bind 时置 GONE 被覆盖 ⇒ 5G 标仍在。uct 布局变体同构（AnonymousClass9 :907-925）。
            // 收口在 setIconForView：命中类型标 view 就 GONE + 短路；drawable 不再设置，
            // 外层随后的 setVisibility(0) 只剩无 drawable 无 margin 的空 view，不占位（margin 已被 U2 清零）。
            pending += MountEngine.SimpleTask(
                className = CLS_SIGNAL_UTILS,
                methodName = METHOD_SET_ICON_FOR_VIEW,
                paramCount = 2,
                returnType = "void",
                id = ID_SET_ICON_FOR_VIEW,
                description = "U2b 5G/4G 类型标收口（setIconForView 命中 mobile_type/mobile_signal_type 置 GONE 并短路，覆盖 StateFlow 重显路径）",
                dex = DexHint(strings = listOf("setIconForView:imageView is null, return")),
            )
            // U2c：Icon.Loaded 路收口（IconViewBinder.bind 同时覆盖 Resource/Loaded 两分支）
            pending += MountEngine.SimpleTask(
                className = CLS_ICON_VIEW_BINDER,
                methodName = METHOD_BIND,
                paramCount = 2,
                returnType = "void",
                id = ID_ICON_BINDER,
                description = "U2c 5G/4G 类型标收口·Icon.Loaded 路（IconViewBinder.bind 命中类型标 view 置 GONE 并短路）",
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_HIDE_5G, "SystemUiHook")
        }

        // U4 系统通知隐藏 ×3（充电/热点/USB 调试）：3 个 key 共用一个管线收口挂载点，
        //   身份匹配在 hook 回调里逐 key 判（matchesHiddenNotif），任一开即挂。
        val notifKeys = listOf(
            FeatureCatalog.KEY_SYSTEMUI_HIDE_CHARGE_NOTIF,
            FeatureCatalog.KEY_SYSTEMUI_HIDE_HOTSPOT_NOTIF,
            FeatureCatalog.KEY_SYSTEMUI_HIDE_ADB_NOTIF,
        )
        if (notifKeys.any { RemoteConfig.isEnabled(it) }) {
            pending += MountEngine.SimpleTask(
                className = CLS_HW_NOTIFICATION_FILTER,
                methodName = "shouldFilterOut",
                paramCount = 1,
                returnType = "boolean",
                id = ID_FILTER_NOTIF,
                description = "U4 系统通知隐藏（充电/热点/USB 调试，身份匹配见 matchesHiddenNotif）",
            )
            pending += MountEngine.SimpleTask(
                className = CLS_HW_NOTIFICATION_FEATURE,
                methodName = "isDisableShownInShade",
                paramCount = 1,
                returnType = "boolean",
                id = ID_SHADE_HIDE,
                description = "U4b 通知中心隐藏（荣耀自建 dynamicserver 管线 shade 仲裁：isDisableShownInShade 命中返 true，同宿主自带隐藏标志语义）",
            )
        } else {
            notifKeys.forEach { logDisabled(module, it, "SystemUiHook") }
        }

        // U5 手电筒低电量放行（纯查询门短路返回 true，见 mountFlashlightLowBatt 注释）
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_FLASHLIGHT_IGNORE_LOW_BATT)) {
            pending += MountEngine.SimpleTask(
                className = CLS_HW_PHONE_STATUSBAR,
                methodName = "canOpenFlashLight",
                paramCount = 0,
                returnType = "boolean",
                id = ID_FLASHLIGHT_LOW_BATT,
                description = "U5 手电筒忽略低电量（canOpenFlashLight 恒 true；QS 瓷贴 + 桌面小部件）",
            )
        } else {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_FLASHLIGHT_IGNORE_LOW_BATT, "SystemUiHook")
        }

        if (pending.isEmpty()) return
        engine.begin(apkPath, FeatureCatalog.baselineVersionOf(packageName))

        if (pending.isNotEmpty()) {
            engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
        }
    }


    /** 判据日志前缀（沿用历史口径，便于按前缀过滤真机日志）。 */
    private const val JUDGE_PREFIX = "systemui-hook"

    /** 挂载点 id（mountOne 按 id 分派）。 */
    private const val ID_HIDE_5G = "systemui:signalIconBinder.bind"
    private const val ID_SET_ICON_FOR_VIEW = "systemui:hnSignalUtils.setIconForView"
    private const val ID_ICON_BINDER = "systemui:iconViewBinder.bind"
    private const val ID_FILTER_NOTIF = "systemui:hwNotificationFilterImpl.shouldFilterOut"
    private const val ID_SHADE_HIDE = "systemui:hwNotificationFeatureImpl.isDisableShownInShade"
    private const val ID_FLASHLIGHT_LOW_BATT = "systemui:hwPhoneStatusBar.canOpenFlashLight"

    /** slotId 字段读不到的一次性告警标志（只报一次，避免每次订阅变化都刷屏）。 */
    private val unreadableWarned = AtomicBoolean(false)

    /** [diag] 回调首杀一次性标志：三处回调入口共用，验证 hook 回调是否真的被调用。 */
    private val diagFirstCallback = AtomicBoolean(false)

    private fun diagCallback(module: XposedModule, taskId: String) {
        if (diagFirstCallback.compareAndSet(false, true)) {
            module.log(Log.INFO, MountEngine.TAG, "systemui-hook [diag] 回调首次命中: $taskId")
        }
    }

    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        when (task.id) {
            ID_HIDE_5G -> mountHide5G(task, module, loader)
            ID_SET_ICON_FOR_VIEW -> mountTypeIconSink(task, module, loader)
            ID_ICON_BINDER -> mountTypeIconSink(task, module, loader)
            ID_FILTER_NOTIF -> mountHideSystemNotifs(task, module, loader)
            ID_SHADE_HIDE -> mountHideInNotificationCenter(task, module, loader)
            ID_FLASHLIGHT_LOW_BATT -> mountFlashlightLowBatt(task, module, loader)
            else -> mountHideSim2(task, module, loader)
        }

    /** U1 副卡隐藏：updateSubs 入参剔除 slotId==1（数据层，宿主重建时自然用改过的数据）。 */
    private fun mountHideSim2(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            diagCallback(module, task.id)
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
    private fun mountHide5G(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            diagCallback(module, task.id)
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

    /** 类型标 view 的资源 entry name（normal 布局 mobile_type / uct 布局 mobile_signal_type；uct_mobile_type_roam 混漫游语义不收）。 */
    private val TYPE_VIEW_NAMES = setOf("mobile_type", "mobile_signal_type")

    /**
     * U2b/U2c 共用收口：setIconForView(ImageView, int) 与 IconViewBinder.bind(Icon, ImageView)
     * 两路必经点。扫描前两参找 ImageView：命中类型标 view → GONE + 短路；其余一律放行
     * （信号格/漫游/箭头不受影响）。
     */
    private fun mountTypeIconSink(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            diagCallback(module, task.id)
            // void 方法：回调必须显式返回 null（见 mountHideSim2 的崩溃实证注释）
            val iconView = (0..1).firstNotNullOfOrNull { i ->
                runCatching { chain.getArg(i) as? android.widget.ImageView }.getOrNull()
                    ?.takeIf { isTypeIconView(it) }
            }
            if (iconView != null) {
                runCatching { iconView.visibility = android.view.View.GONE }
                engine.judge(module, task, JUDGE_PREFIX, "类型标收口命中，已 GONE")
                null   // 短路：drawable 不设置；外层随后的 setVisibility(0) 只作用于空 view
            } else {
                chain.proceed()
                null
            }
        }

    /** 按 view id 的资源 entry name 判类型标（id 为 0/读不到 = 不命中，放行原逻辑）。 */
    private fun isTypeIconView(view: android.view.View): Boolean {
        val name = runCatching {
            val id = view.id
            if (id == 0) null else view.resources.getResourceEntryName(id)
        }.getOrNull()
        return name != null && name in TYPE_VIEW_NAMES
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

    // ------------------------------------------------------------------
    // U4 系统通知隐藏 / U5 手电筒低电量放行（2026-10-12，逆向实证记录见 docs/reverse/systemui.md §八 §九）
    // ------------------------------------------------------------------

    /**
     * U4 系统通知隐藏：挂在 Honor 通知管线 finalize 过滤器的判定方法上。
     *
     * 调用链（jadx + smali 实证，基线 17.0.0.1）：HnCoordinatorImpl.attach() 把本方法包进
     * NotifFilter 经 addFinalizeFilter 注册进 AOSP NotifPipeline（HnCoordinatorImpl$4.smali:46）；
     * 返回 true ⇒ ShadeListBuilder.applyFilters 将 entry 剔出渲染列表 ⇒ 下拉/锁屏/横幅/图标全无，
     * 且 HwNotificationListnerImpl.wakeScreenOn:444 同判 ⇒ 不再点亮屏幕/AOD。
     *
     * 宿主方法本身是纯查询（keyguard/媒体中心白名单判定），故恒 proceed 放行宿主逻辑、
     * 再 OR 上我们的匹配结果——宿主原有过滤行为不受影响。
     * 返回类型 Z：hook 回调必须返回 Boolean（返回值规则见 mountHideSim2 的 2026-10-11 崩溃实证注释）。
     */
    private fun mountHideSystemNotifs(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // 入参是 NotificationEntry，抽 public 字段 mSbn（U4b 的入参直接就是 sbn）
            val hit = runCatching {
                chain.getArg(0)?.javaClass?.getField("mSbn")?.get(chain.getArg(0))
                    ?.let { matchesHiddenNotif(it, module) }
            }.getOrNull()
            val hostResult = runCatching { chain.proceed() as? Boolean }.getOrNull() ?: false
            if (hit != null) engine.judge(module, task, JUDGE_PREFIX, "隐藏$hit")
            hostResult || hit != null
        }

    /**
     * U4b 通知中心隐藏：荣耀自建「通知中心」管线（com.hihonor.systemui.dynamicserver）的 shade 仲裁。
     *
     * 本方法是宿主自带隐藏标志（extras `hw_disable_ntf_show_in_shade`）的唯一读口
     * （HwNotificationFeatureImpl.java:644），消费方 4+：
     *   NotificationCenterCoordinator.onNotificationCardUpdated:142（true = 不进通知中心）
     *   NotificationCenterCardManager:422/:460（shade 与 AOD/锁屏分支）
     *   HwNotificationFilterImpl:76（U4 管线的 shade 分支也汇到这）
     * 命中返 true 与宿主官方隐藏机制同语义（上游一致性）；恒 proceed 放行宿主逻辑后 OR。
     * 2026-10-12 真机实证：只挂 U4 管线过滤点，判据命中但通知中心仍渲染——本点才是渲染仲裁。
     */
    private fun mountHideInNotificationCenter(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            val hit = runCatching { matchesHiddenNotif(chain.getArg(0), module) }.getOrNull()
            val hostResult = runCatching { chain.proceed() as? Boolean }.getOrNull() ?: false
            if (hit != null) engine.judge(module, task, JUDGE_PREFIX, "隐藏$hit")
            hostResult || hit != null
        }

    /**
     * U4 身份匹配：返回「功能名(pkg/锚点)」用于判据日志，未命中返回 null。
     * 三条规则（dumpsys 实证 2026-10-12；渠道 id 被 NMS 强制打码，充电/热点故不用渠道做锚点）：
     *   充电    pkg==系统管家 && id==200001（真机活跃条目 title「极速充电」；普通充电态可能同 id
     *           换标题——探针日志运行期核对，见 probeNotifIdentity）
     *   热点    pkg==android && group=="TetheredNotification"（AOSP framework 硬编码 group 串，最稳锚点）
     *   USB调试 pkg==android && channelId 含 "adb"（静态推断的宽匹配，探针拿到真实渠道 id 后收紧）
     */
    private fun matchesHiddenNotif(sbn: Any, module: XposedModule): String? {
        probeNotifIdentity(module, sbn)
        val pkg = sbn.callOrNull("getPackageName") as? String ?: return null
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_CHARGE_NOTIF) &&
            pkg == CHARGE_NOTIF_PKG && (sbn.callOrNull("getId") as? Int) == CHARGE_NOTIF_ID
        ) {
            return "充电通知($pkg/$CHARGE_NOTIF_ID)"
        }
        val group = sbn.callOrNull("getGroup") as? String
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_HOTSPOT_NOTIF) &&
            pkg == PKG_ANDROID && group == HOTSPOT_NOTIF_GROUP
        ) {
            return "热点通知($pkg/group=$group)"
        }
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_HIDE_ADB_NOTIF) && pkg == PKG_ANDROID) {
            val channel = sbn.callOrNull("getNotification")?.callOrNull("getChannelId") as? String
            if (channel?.contains("adb", ignoreCase = true) == true) {
                return "USB调试通知($pkg/channel=$channel)"
            }
        }
        return null
    }

    /** 已打过探针的通知 key（每 key 一条）。 */
    private val probedNotifKeys = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * 通知身份探针：打全量身份（pkg/id/group/channel/title）。
     * NMS 对渠道字段强制打码（dumpsys/cmd notification get 均为 ***，隐私补丁），探针是渠道 id
     * 唯一证据源——USB 调试真实渠道、充电普通态 id 都靠它运行期收紧。
     */
    private fun probeNotifIdentity(module: XposedModule, sbn: Any) {
        val key = sbn.callOrNull("getKey") as? String ?: return
        if (probedNotifKeys.size >= PROBE_NOTIF_MAX) return
        if (!probedNotifKeys.add(key)) return
        val notification = sbn.callOrNull("getNotification")
        val title = runCatching {
            (notification?.callOrNull("getExtras") as? android.os.Bundle)
                ?.getCharSequence("android.title")
        }.getOrNull()
        module.log(
            Log.INFO, MountEngine.TAG,
            "systemui-hook 通知探针: pkg=${sbn.callOrNull("getPackageName")} " +
                "id=${sbn.callOrNull("getId")} group=${sbn.callOrNull("getGroup")} " +
                "channel=${notification?.callOrNull("getChannelId")} title=$title",
        )
    }

    /**
     * 反射调用无参 public 方法（含继承；对象为 null / 方法不存在 / 调用抛异常一律返 null）。
     * sbn 全量走反射而非强转：本地 android.jar（android-37.0）缺 android.app.StatusBarNotification，
     * 引可解析的 stub 路径强转会在运行期撞类身份（与目标进程 boot classpath 不一致）。
     */
    private fun Any?.callOrNull(name: String): Any? =
        runCatching { this?.javaClass?.getMethod(name)?.invoke(this) }.getOrNull()

    /**
     * U5 手电筒低电量放行：canOpenFlashLight 是纯查询门（HwPhoneStatusBar.java:625-628
     * `mBatteryManager == null || mLevel > 5`，无副作用），直接短路返回 true，不 proceed。
     * 消费方 2（jadx 实证）：QS 瓷贴 FlashlightTileUserActionInteractor.handleInput:74 +
     * 桌面小部件 FlashWidgetIntentService:49。锁屏工具箱走 BatteryStateInfo.isExhaustBatteryLevel
     * 另一扇门，不在此点覆盖（未实现，见 docs/reverse/systemui.md §九）；长按亮度面板是内联
     * mBatteryLevel>5 判断（FlashlightActivity.java:243），无单点可挂，放弃。
     */
    private fun mountFlashlightLowBatt(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // 只在宿主本来会拦的低电量档留判据；电量充足时宿主本就放行，不打点
            val level = runCatching {
                val mgr = chain.thisObject?.javaClass?.getField("mBatteryManager")?.get(chain.thisObject)
                mgr?.javaClass?.getField("mLevel")?.getInt(mgr)
            }.getOrNull()
            if (level != null && level <= LOW_BATTERY_THRESHOLD) {
                engine.judge(module, task, JUDGE_PREFIX, "电量 ${level}% 放行手电筒")
            }
            true
        }
}
