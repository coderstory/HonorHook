package top.honorhook.xposed.target

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * U6 状态栏双排信号（渲染方案移植自 com.mod.dualsignal 3.3，用户要求整合，私用）。
 *
 * 移植源：system-files/DualSignal-jadx/…/DualSignalHook.java（872 行 jadx 实证读毕）。取舍：
 * - 保留：onAttachedToWindow（after）找 cluster root → 遍历找 mobile_combo 组 → 隐藏原生组 +
 *   注入自绘双排容器（自绘 [DualSignalBarsView]，上排主卡方柱 / 下排副卡圆点）；档位**自读**
 *   TelephonyManager/SubscriptionManager（不依赖宿主 StateFlow——绕开 §7.9 U3 的档位采集死路）；
 *   OnGlobalLayout 节流（250ms）+ 2s 自续刷新 + Choreographer 每帧取色跟随反色；
 *   所有尺寸/偏移常量原样保留（作者调好的视觉参数）。
 * - 舍弃：5G/4G 类型标 TextView（label）及 getNetworkLabel/isWifiConnected/isMobileDataEnabled
 *   —— 类型标由本模块 U2/U2b/U2c native 收口负责，不双份；toast 与写 Download 目录的
 *   dump/日志文件不搬。
 * - 改造（与原插件的三处偏差，均有实证）：
 *   1. 硬编码 id 2131364317/318/2131363001 → 运行时 getIdentifier。aapt2 实证（SystemUI.apk
 *      资源表）= mobile_combo / mobile_combo_card / ethernet_combo，防跨版本漂移。
 *   2. ModernStatusBarMobileView 挂载点不搬：该类未覆写 onAttachedToWindow，直连解析
 *      （declaredMethods）必落空；旧 API 挂它实际落到 View.onAttachedToWindow 全局点。
 *      §7.9 真机实证本宿主显示路径 constructAndBind 零触发（状态栏卡根是 SignalUnit 体系）
 *      ⇒ 挂了是死挂载（hook-craft §二·4），不做。
 *   3. HwSignalClusterView 不单挂：jadx 实证它 extends SignalClusterView 且未覆写 attach，
 *      挂 SignalClusterView.onAttachedToWindow 即覆盖两者。
 * - 与 DualSignal 插件二选一：冲突防护见 [hasForeignContainer]（容器 tag 探测，先到先得、
 *   后到退避，详见该注释）。
 */
object DualSignalFeature {

    private const val HOST_PACKAGE = "com.android.systemui"

    /** 引擎名与判据前缀都含 "SystemUiHook"：modlog-f 'SystemUiHook' 可一并过滤到汇总行与判据行。 */
    private const val JUDGE_PREFIX = "SystemUiHook·U6"

    private val engine = MountEngine("SystemUiHook·U6", HOST_PACKAGE)

    // ---- 挂载点（jadx 实证均未混淆；onAttachedToWindow 均为类自声明覆写，declaredMethods 直连可解析）----

    private const val CLS_SIGNAL_CLUSTER_VIEW = "com.android.systemui.statusbar.SignalClusterView"
    private const val CLS_SIGNAL_UNIT_NORMAL = "com.android.systemui.statusbar.SignalUnitNormalView"
    private const val CLS_SIGNAL_UNIT_UCT = "com.android.systemui.statusbar.SignalUnitUCTView"
    private const val METHOD_ATTACH = "onAttachedToWindow"

    // ---- DualSignal 插件冲突防护 ----

    /** DualSignal 插件包名（其 hook 先注入时插件容器已存在，据此退避）。 */
    private const val DUAL_SIGNAL_PACKAGE = "com.mod.dualsignal"

    /** DualSignal 3.3 自建容器的 tag（jadx 实证 TAG_CONTAINER = "dual_container"）。 */
    private const val FOREIGN_CONTAINER_TAG = "dual_container"

    // ---- 自建视图 tag（加前缀，不与插件撞）----

    private const val TAG_CONTAINER = "hh_dual_container"
    private const val TAG_SIGNAL = "hh_dual_signal"

    // ---- 视觉常量（原样移植自 DualSignal 3.3，作者调好的参数；勿改）----

    private const val BAR_W_DP = 3.1f
    private const val BAR_GAP_DP = 2.0f
    private const val BAR_MAX_H_DP = 7.6f          // 双排：上排方柱压扁（状态栏高度物理约束，§7.2）
    private const val SINGLE_BAR_MAX_H_DP = 11.0f  // 单卡：不压扁
    private const val ROW_GAP_DP = 1.6f
    private const val DIM_ALPHA = 64               // 未点亮柱的透明度
    private const val HOST_MARGIN_DP = 1.5f        // host（mobile_combo 外壳）左右 margin
    private val BAR_H_RATIO = floatArrayOf(0.48f, 0.6f, 0.79f, 1.0f)

    private const val LAYOUT_THROTTLE_MS = 250L
    private const val REFRESH_INTERVAL_MS = 2000L

    // ---- 运行时资源 id（禁硬编码 0x7f083044 系数值——逐版漂移）----

    /** 每卡信号组（水平 LinearLayout，双排接管对象；原插件 ID_MOBILE_GROUP_NEW）。 */
    private const val NAME_MOBILE_COMBO = "mobile_combo"

    /** mobile_combo 外壳 FrameLayout（原插件 ID_SIGNAL_UNIT，fixGroup 的 host）。 */
    private const val NAME_MOBILE_COMBO_CARD = "mobile_combo_card"

    /** 原插件 moveBeforeWifi 的兜底定位 id（ID_WIFI_OLD），aapt2 实证 entry name 是 ethernet_combo。 */
    private const val NAME_ETHERNET_COMBO = "ethernet_combo"

    @Volatile private var idMobileCombo = 0
    @Volatile private var idMobileComboCard = 0
    @Volatile private var idEthernetCombo = 0

    private fun resolveResourceIdsOnce(context: Context) {
        if (idMobileCombo != 0) return
        runCatching {
            val pkg = context.packageName
            idMobileCombo = context.resources.getIdentifier(NAME_MOBILE_COMBO, "id", pkg)
            idMobileComboCard = context.resources.getIdentifier(NAME_MOBILE_COMBO_CARD, "id", pkg)
            idEthernetCombo = context.resources.getIdentifier(NAME_ETHERNET_COMBO, "id", pkg)
        }
    }

    // ---- 维护状态（全部主线程访问：attach / layout / choreographer 回调都在主线程）----

    /** 已接管 host（mobile_combo 的 FrameLayout 外壳）→ 防重复注入（原插件 sMaintained 同款）。 */
    private val maintained = WeakHashMap<FrameLayout, Boolean>()

    /** 已向 DualSignal 插件退避的 host（检测到其容器后不再注入，避免双份）。 */
    private val backedOff = WeakHashMap<FrameLayout, Boolean>()

    /** OnGlobalLayout 节流时间戳（原插件全局单值语义，跨 host 共享）。 */
    @Volatile private var lastLayoutMs = 0L

    /** DualSignal 插件包存在性探测（每进程一次，只做留痕，不做门）。 */
    private val pkgProbed = AtomicBoolean(false)

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_DUAL_SIGNAL)) {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_DUAL_SIGNAL, "SystemUiHook·U6")
            return
        }
        val loader = param.defaultClassLoader
        engine.begin(param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(HOST_PACKAGE))

        val pending = listOf(
            MountEngine.SimpleTask(
                className = CLS_SIGNAL_CLUSTER_VIEW,
                methodName = METHOD_ATTACH,
                paramCount = 0,
                returnType = "void",
                id = "systemui:dualSignal.SignalClusterView",
                description = "U6 双排信号·attach 入口（SignalClusterView，cluster root 直取）",
            ),
            MountEngine.SimpleTask(
                className = CLS_SIGNAL_UNIT_NORMAL,
                methodName = METHOD_ATTACH,
                paramCount = 0,
                returnType = "void",
                id = "systemui:dualSignal.SignalUnitNormalView",
                description = "U6 双排信号·attach 入口（SignalUnitNormalView，上溯找 cluster root）",
            ),
            MountEngine.SimpleTask(
                className = CLS_SIGNAL_UNIT_UCT,
                methodName = METHOD_ATTACH,
                paramCount = 0,
                returnType = "void",
                id = "systemui:dualSignal.SignalUnitUCTView",
                description = "U6 双排信号·attach 入口（SignalUnitUCTView，上溯找 cluster root）",
            ),
        )
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    private fun mountOne(task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        engine.mount(task, module, loader) { chain ->
            // after 语义：先放行宿主原 attach 逻辑，再调度注入（void 方法 → 回调显式返回 null，§7.4）
            chain.proceed()
            val self = chain.thisObject as? View
            if (self != null) {
                // 原插件语义：root 找不到时退回 self（safeRestyle 对小子树遍历无副作用）
                // cluster root 判定按类名（SimpleTask 无目标私有字段，isClusterRoot 由 className 推导）
                val isClusterRoot = task.className == CLS_SIGNAL_CLUSTER_VIEW
                val root = if (isClusterRoot) self else findClusterRoot(self) ?: self
                schedule(root, task, module)
            }
            null
        }

    /** 从 signal unit 视图上溯 ≤8 层找类名含 "SignalCluster" 的祖先（原插件 findClusterRoot 同款）。 */
    private fun findClusterRoot(v: View): View? {
        var pp = v.parent
        var depth = 0
        while (pp is View && depth < 8) {
            if (pp.javaClass.name.contains("SignalCluster")) return pp
            pp = pp.parent
            depth++
        }
        return null
    }

    /** attach 后立即 + 500ms 各刷一轮（原插件 schedule：首轮赌宿主刚建完，二轮兜异步构建）。 */
    private fun schedule(root: View, task: MountEngine.SimpleTask, module: XposedModule) {
        root.post { safeRestyle(root, task, module) }
        root.postDelayed({ safeRestyle(root, task, module) }, 500L)
    }

    private fun safeRestyle(root: View, task: MountEngine.SimpleTask, module: XposedModule) {
        try {
            resolveResourceIdsOnce(root.context)
            val fixed = walkAndFix(root, task, module)
            if (fixed > 0) {
                engine.judge(module, task, JUDGE_PREFIX, "双排容器已接管 $fixed 处")
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX restyle 失败: $t")
        }
    }

    /** 遍历找候选组：mobile_combo（按 id）或「水平 LinearLayout 且 ≥1 个 mobile 子单元」（按形态）。 */
    private fun walkAndFix(v: View, task: MountEngine.SimpleTask, module: XposedModule): Int {
        var n = 0
        if (v is LinearLayout) {
            val byId = idMobileCombo != 0 && v.id == idMobileCombo
            val byCount = v.orientation == LinearLayout.HORIZONTAL && countMobileChildren(v) >= 1
            if (byId || byCount) {
                // 只计真接管（退避/外壳非 FrameLayout 不计——judge 行「已接管 N 处」必须是真话）
                if (fixGroup(v, task, module)) n++
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { n += walkAndFix(it, task, module) }
            }
        }
        return n
    }

    private fun countMobileChildren(ll: LinearLayout): Int {
        var c = 0
        for (i in 0 until ll.childCount) {
            if (isMobileUnit(ll.getChildAt(i))) c++
        }
        return c
    }

    /** 原插件 isMobileUnit：类名判 SignalUnit / id 判 mobile_combo 系，排除 Wifi。 */
    private fun isMobileUnit(v: View?): Boolean {
        if (v == null) return false
        val cn = v.javaClass.name
        if (cn.contains("Wifi") || cn.contains("WiFi")) return false
        if (cn.contains("SignalUnit") || cn.contains("ModernStatusBarMobileView")) return true
        val id = v.id
        return (idMobileCombo != 0 && id == idMobileCombo) ||
            (idMobileComboCard != 0 && id == idMobileComboCard)
    }

    /**
     * 接管单组：host = mobile_combo 的 FrameLayout 外壳。隐藏原生组，注入自绘容器，
     * 并挂三路维护（全局布局节流 / 2s 自续刷新 / Choreographer 取色）——全部原插件机制。
     */
    private fun fixGroup(group: LinearLayout, task: MountEngine.SimpleTask, module: XposedModule): Boolean {
        val host = group.parent as? FrameLayout ?: return false   // 原插件硬约束：组外壳必须是 FrameLayout
        if (backedOff[host] == true) return false
        // 冲突防护（先到先得）：插件的容器已在 → 本 host 永久退避，原生组交给插件处理
        if (hasForeignContainer(host)) {
            backOff(module, host)
            return false
        }
        if (group.visibility != View.GONE) group.visibility = View.GONE

        val firstTime = !maintained.containsKey(host)
        if (firstTime) maintained[host] = true
        ensureContainer(host)
        maintain(host, group, module)

        if (firstTime) {
            // 维护路 1：宿主重排（重建/换卡/反色）后跟着修
            host.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    val now = android.os.SystemClock.uptimeMillis()
                    if (now - lastLayoutMs < LAYOUT_THROTTLE_MS) return
                    lastLayoutMs = now
                    try {
                        maintain(host, group, module)
                    } catch (t: Throwable) {
                        module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX maintain 失败: $t")
                    }
                }
            })
            // 维护路 2：2s 自续刷新（档位自读的节拍器，替代宿主 StateFlow 推送）
            host.post {
                host.postDelayed(object : Runnable {
                    override fun run() {
                        try {
                            maintain(host, group, module)
                        } catch (t: Throwable) {
                            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX refresh 失败: $t")
                        }
                        host.postDelayed(this, REFRESH_INTERVAL_MS)
                    }
                }, REFRESH_INTERVAL_MS)
            }
            // 维护路 3：每帧取色跟随反色（深色模式/通知头压暗）
            Choreographer.getInstance().postFrameCallback(ColorSyncCallback(host))
        }
        host.requestLayout()
        return true
    }

    /**
     * 周期维护（原插件 maintain，去掉 label 分支）：保证原生组隐藏、容器存活、档位/取色新鲜、
     * host 排到 wifi 前、宽度 margin 收紧。任何一步异常由外层吞掉（PROTECTIVE + try/catch）。
     */
    private fun maintain(host: FrameLayout, group: LinearLayout, module: XposedModule) {
        if (backedOff[host] == true) return
        probeDualSignalPackageOnce(module, host.context)
        // 插件容器可能在首注入之后才出现（hook 回调顺序不保证）——每轮复查，出现即让位
        if (hasForeignContainer(host)) {
            backOff(module, host)
            return
        }
        if (group.visibility != View.GONE) group.visibility = View.GONE

        var container = findContainer(host)
        if (container == null) {
            ensureContainer(host)
            container = findContainer(host) ?: return
        }
        val signal = findSignal(container)

        val ctx = host.context
        val simCount = getSimCount(ctx).let { if (it < 0) 2 else it }
        if (simCount == 0) {
            if (container.visibility != View.GONE) container.visibility = View.GONE
            return
        }
        if (container.visibility != View.VISIBLE) container.visibility = View.VISIBLE

        val color = pickTint(group, host) ?: Color.WHITE
        signal?.setColor(color)

        val levels = getActiveLevels(ctx)
        val mainLevel = levels.getOrElse(0) { 4 }
        val subLevel = levels.getOrNull(1)
        signal?.setInfo(simCount, mainLevel, subLevel)

        moveBeforeWifi(host)

        val hp = host.layoutParams
        if (hp is ViewGroup.MarginLayoutParams) {
            val want = dp(host, HOST_MARGIN_DP)
            if (hp.width != ViewGroup.LayoutParams.WRAP_CONTENT ||
                hp.rightMargin != want || hp.leftMargin != want
            ) {
                hp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                hp.rightMargin = want
                hp.leftMargin = want
                host.layoutParams = hp
            }
        }
    }

    /**
     * 冲突防护核心：host 直属子树里探测 DualSignal 3.3 的容器 tag "dual_container"。
     *
     * 两个模块挂同一批 onAttachedToWindow，回调先后由 LSPosed 决定，不保证本模块先到：
     * - 插件先到：我们首见 host 时即探得其容器 → 整体不注入（fixGroup 早退）；
     * - 我们先到：插件随后也会注入自己那份 → 本方法在 250ms 节流的布局回调 / 2s 刷新里
     *   探得 → [backOff] 移除我们的容器并恢复原生组（瞬时双份 ≤2s，自愈）。
     * 每个 host 只退避一次，退避后彻底停手（不与插件打架）。
     */
    private fun hasForeignContainer(host: FrameLayout): Boolean {
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i)
            if (c is LinearLayout && (c.tag as? String) == FOREIGN_CONTAINER_TAG) return true
        }
        return false
    }

    private fun backOff(module: XposedModule, host: FrameLayout) {
        if (backedOff.put(host, true) == true) return
        maintained.remove(host)
        findContainer(host)?.let { host.removeView(it) }
        module.log(
            Log.WARN, MountEngine.TAG,
            "$JUDGE_PREFIX 检测到 DualSignal 插件容器（tag $FOREIGN_CONTAINER_TAG），" +
                "本 host 退避不注入（先到先得；停用插件并重启 SystemUI 后 U6 生效）",
        )
    }

    /** DualSignal 插件包存在性留痕（只记一次；真正的冲突判定看容器 tag，不依赖包存在性）。 */
    private fun probeDualSignalPackageOnce(module: XposedModule, context: Context) {
        if (!pkgProbed.compareAndSet(false, true)) return
        val installed = runCatching {
            context.packageManager.getPackageInfo(DUAL_SIGNAL_PACKAGE, 0)
            true
        }.getOrDefault(false)
        module.log(
            Log.INFO, MountEngine.TAG,
            if (installed) {
                "$JUDGE_PREFIX DualSignal 插件包已安装（$DUAL_SIGNAL_PACKAGE）——其启用时 U6 按 host 退避，二选一"
            } else {
                "$JUDGE_PREFIX DualSignal 插件包未安装，无冲突"
            },
        )
    }

    /** 注入自建容器：LinearLayout（tag hh_dual_container）内一枚自绘双排信号格。类型标不移植。 */
    private fun ensureContainer(host: FrameLayout) {
        if (findContainer(host) != null) return
        val ctx = host.context
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            tag = TAG_CONTAINER
        }
        val signal = DualSignalBarsView(ctx)
        signal.tag = TAG_SIGNAL
        container.addView(signal, LinearLayout.LayoutParams(WRAP, WRAP))
        val flp = FrameLayout.LayoutParams(WRAP, WRAP)
        flp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        host.addView(container, flp)
    }

    private fun findContainer(host: FrameLayout): LinearLayout? {
        for (i in 0 until host.childCount) {
            val c = host.getChildAt(i)
            if (c is LinearLayout && (c.tag as? String) == TAG_CONTAINER) return c
        }
        return null
    }

    private fun findSignal(container: LinearLayout): DualSignalBarsView? {
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c is DualSignalBarsView) return c
        }
        return null
    }

    /** 取色（原插件 pickTint）：优先原生组内带 tint 的 ImageView（宿主 DarkIconDispatcher 算好的），
     *  退化到宿主所在行的兄弟视图，再退化白色。不自算反色 ⇒ 自动跟随深色模式。 */
    private fun pickTint(group: LinearLayout, host: FrameLayout): Int? {
        firstImageViewWithTint(group)?.imageTintList?.let { return it.defaultColor }
        return tintFromRow(host)
    }

    /** host 所在行里第一个可见、带 tint 的 ImageView 的颜色（无则 null）。 */
    private fun tintFromRow(host: FrameLayout): Int? {
        val row = host.parent as? ViewGroup ?: return null
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            firstImageViewWithTint(c)?.imageTintList?.let { return it.defaultColor }
        }
        return null
    }

    private fun firstImageViewWithTint(v: View?): android.widget.ImageView? {
        if (v is android.widget.ImageView) {
            if (v.imageTintList != null) return v
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                firstImageViewWithTint(v.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    /** 把 host（含自绘双排）排到所在行里 wifi 视图之前（原插件 moveBeforeWifi；状态栏顺序语义）。 */
    private fun moveBeforeWifi(host: FrameLayout) {
        val row = host.parent as? ViewGroup ?: return
        var wifi: View? = null
        for (i in 0 until row.childCount) {
            val c = row.getChildAt(i)
            if (c !== host && c.visibility == View.VISIBLE &&
                c.javaClass.name.lowercase().contains("wifi")
            ) {
                wifi = c
                break
            }
        }
        if (wifi == null && idEthernetCombo != 0) {
            // 原插件兜底：类名找不到 wifi 时按 id 找（aapt2 实证该 id 的 entry name 是 ethernet_combo）
            for (i in 0 until row.childCount) {
                val c = row.getChildAt(i)
                if (c !== host && c.visibility == View.VISIBLE && c.id == idEthernetCombo) {
                    wifi = c
                    break
                }
            }
        }
        if (wifi == null) return
        val wi = row.indexOfChild(wifi)
        val hi = row.indexOfChild(host)
        if (hi >= 0 && wi >= 0 && hi > wi) {
            row.removeView(host)
            row.addView(host, wi)
        }
    }

    // ---- 档位自读（原插件数据源；SystemUI 进程自带的权限足够，失败路径回 4 格/双卡语义）----

    private fun getSimCount(ctx: Context): Int = runCatching {
        val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return -1
        sm.activeSubscriptionInfoList?.size ?: 0
    }.getOrDefault(-1)

    /** 各 active SIM 按卡槽序的信号档位（0~4；读失败按 4 满格——原插件同款容错）。 */
    private fun getActiveLevels(ctx: Context): List<Int> = runCatching {
        val sm = ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        val subs = sm?.activeSubscriptionInfoList
        if (tm == null || subs.isNullOrEmpty()) {
            return emptyList()
        }
        subs.sortedBy { it.simSlotIndex }.map { s ->
            runCatching {
                tm.createForSubscriptionId(s.subscriptionId).signalStrength?.level ?: 4
            }.getOrDefault(4)
        }
    }.getOrDefault(emptyList())

    private fun dp(host: View, value: Float): Int =
        Math.round(host.resources.displayMetrics.density * value)

    /** Choreographer 每帧取色：tint 变了才 invalidate 自绘视图（原插件 ColorSyncCallback 同款）。 */
    private class ColorSyncCallback(private val host: FrameLayout) : Choreographer.FrameCallback {
        private var lastColor = Int.MIN_VALUE

        override fun doFrame(frameTimeNanos: Long) {
            // host 脱离窗口即停拍（不再 post 下一帧）——原插件每帧自续永不注销的泄漏在此收口；
            // 新 cluster/视图 attach 时 fixGroup 会挂新的 callback，生命周期随 host 走。
            if (!host.isAttachedToWindow) return
            try {
                val signal = findContainer(host)?.let { findSignal(it) } ?: return
                val color = tintFromRow(host)
                if (color != null && color != lastColor) {
                    lastColor = color
                    signal.setColor(color)
                }
            } catch (_: Throwable) {
            }
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

    /**
     * 自绘双排信号格（原插件 DualSignalView 逐行移植，去掉 label 关联）：
     * 上排 4 根圆角方柱 = 主卡档位，下排 4 个圆点 = 副卡档位；未点亮柱 alpha 压暗。
     * 双卡才画下排；单卡恢复原生单排高度（不压扁）。
     */
    private class DualSignalBarsView(context: Context) : View(context) {
        private var simCount = 2
        private var dual = true
        private var mainLevel = 4
        private var subLevel = 4
        private var color = Color.WHITE
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val rect = RectF()

        fun setInfo(simCount: Int, main: Int, sub: Int?) {
            val dualNow = simCount >= 2 && sub != null
            val s = sub ?: 4
            if (dualNow != dual || simCount != this.simCount || main != mainLevel || s != subLevel) {
                dual = dualNow
                this.simCount = simCount
                mainLevel = main
                subLevel = s
                requestLayout()
                invalidate()
            }
        }

        fun setColor(color: Int) {
            if (color != this.color) {
                this.color = color
                invalidate()
            }
        }

        private fun d() = resources.displayMetrics.density
        private fun barW() = d() * BAR_W_DP
        private fun gap() = d() * BAR_GAP_DP
        private fun maxH() = (if (dual) BAR_MAX_H_DP else SINGLE_BAR_MAX_H_DP) * d()
        private fun rowGap() = d() * ROW_GAP_DP
        private fun viewW() = barW() * 4f + gap() * 3f
        private fun viewH() = if (dual) maxH() + rowGap() + barW() else maxH()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(viewW().roundToInt(), viewH().roundToInt())
        }

        override fun onDraw(canvas: Canvas) {
            val bw = barW()
            val gap = gap()
            val maxH = maxH()
            val rowGap = rowGap()
            // 原插件伪码 radius = bw/BAR_GAP_DP 是 jadx 常量折叠（BAR_GAP_DP == 2.0f），实为 bw/2
            val radius = bw / 2f
            for (i in 0 until 4) {
                val h = BAR_H_RATIO[i] * maxH
                val left = i * (bw + gap)
                rect.set(left, maxH - h, left + bw, maxH)
                paint.color = color
                paint.alpha = if (i >= mainLevel) DIM_ALPHA else 255
                canvas.drawRoundRect(rect, radius, radius, paint)
            }
            if (dual) {
                val cy = maxH + rowGap + bw / 2f
                for (i in 0 until 4) {
                    val cx = i * (bw + gap) + bw / 2f
                    paint.color = color
                    paint.alpha = if (i < subLevel) 255 else DIM_ALPHA
                    canvas.drawCircle(cx, cy, bw / 2f, paint)
                }
            }
        }
    }
}
