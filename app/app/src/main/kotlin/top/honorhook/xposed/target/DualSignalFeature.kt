package top.honorhook.xposed.target

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt
import java.lang.reflect.Field

/**
 * U6 状态栏双排信号（渲染方案移植自 com.mod.dualsignal 3.3.4，用户要求整合，私用）。
 *
 * 移植源：system-files/DualSignal-jadx/…/DualSignalHook.java（872 行 jadx 实证读毕）。取舍：
 * - 保留：onAttachedToWindow（after）找 cluster root → 遍历找 mobile_combo 组 → 隐藏原生组 +
 *   注入自绘双排容器（自绘 [DualSignalBarsView]，上排主卡方柱 / 下排副卡圆点）；档位**自读**
 *   TelephonyManager/SubscriptionManager（不依赖宿主 StateFlow——绕开 §7.9 U3 的档位采集死路）；
 *   OnGlobalLayout 节流（250ms）+ 2s 自续刷新 + Choreographer 每帧取色跟随反色；
 *   所有尺寸/偏移常量原样保留（作者调好的视觉参数）。
 * - 舍弃：toast 与写 Download 目录的 dump/日志文件不搬。
 * - 补回（2026-10-15，子开关 systemui.dual_signal_label）：5G/4G 制式标 TextView（上游配置键
 *   `SHOW_LABEL`）。原「类型标由 U2/U2b/U2c native 收口、不双份」的取舍作废——原生 `mobile_combo`
 *   整组被本功能设为 GONE（§7.11），5G 类型标就在该组内，视觉上随组消失，U2 收不到，只能自绘补回。
 *   数据链 `getNetworkLabel` / `isMobileDataEnabled` 一并搬（上游的 `isWifiConnected` 门已按
 *   2026-10-16 用户拍板去掉，见 `updateLabel`）。
 *   ⚠ 上游 jadx 产物的 `getNetworkLabel` **不可用**（packed-switch 的 case 表被吞成空
 *   `switch (type) { }`，恒返回默认 "4G"）；映射以 apktool smali 为准，表见 §7.11b
 *   「5G 制式标（子开关）」。门控：label 只在双排开启时可能生效（双排关 = install 早退），
 *   UI 侧对应硬门见 FeaturesScreen（双排关时本行置灰不可开）。
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
 * - 2026-10-13 校准：对上游 3.3.4 实读比对（jadx 源现版本 VERSION=3.3.4），同步两处逻辑：
 *   maintain margin 动态左边距（HOST_MARGIN_TEXT_DP）与 moveBeforeWifi 触发条件（hi != wi-1）；
 *   其余逻辑零差异。
 */
object DualSignalFeature {

    private const val HOST_PACKAGE = "com.android.systemui"

    /** 引擎名与判据前缀都含 "SystemUiHook"：modlog-f 'SystemUiHook' 可一并过滤到汇总行与判据行。 */
    private const val JUDGE_PREFIX = "SystemUiHook·U6"


    // ---- 挂载点（jadx 实证均未混淆；onAttachedToWindow 均为类自声明覆写，declaredMethods 直连可解析）----

    private const val CLS_SIGNAL_CLUSTER_VIEW = "com.android.systemui.statusbar.SignalClusterView"
    private const val CLS_SIGNAL_UNIT_NORMAL = "com.android.systemui.statusbar.SignalUnitNormalView"
    private const val CLS_SIGNAL_UNIT_UCT = "com.android.systemui.statusbar.SignalUnitUCTView"
    private const val METHOD_ATTACH = "onAttachedToWindow"

    // 117 版 OTA 新增的堆叠（stack）策略类，jadx/MT 实证未混淆：shouldShowStacked(Z,Z)Z 与
    // isStackEnabled()Z 是所有堆叠切换的唯一收口（HwSystemIcons.updateSignalStack →
    // notifySignalStackChange → 直接 setVisibility 原生组，与 U6 的 GONE 维护互搏 ⇒ 系统 SIM
    // 与本插件 SIM 间歇重叠）。恒返 false = 系统永不进入堆叠态，原生双排/重叠一并消失。
    private const val CLS_SIGNAL_STACK_POLICY =
        "com.android.systemui.statusbar.pipeline.mobile.data.repository.SignalStackPolicy"

    // 堆叠掐断的二阶效应收口（2026-11-14 真机实证，117 版 smali 8022-8065）：堆叠不可用后，系统对
    // 「胶囊激活+信号空间不足」改走 HwSystemIcons 的胶囊避让——updateWifiLeftViewAlphaByCapsule 判
    // WiFi 溢出 → setWifiIconAlpha 把 mWifiCombo alpha 渐隐到 0 ⇒ 用户视角「出现胶囊 WiFi 消失」。
    // 该类未混淆（public final，Honor 自有类，与 SignalStackPolicy 同一稳定性级别）。
    private const val CLS_HW_SYSTEM_ICONS = "com.android.systemui.statusbar.HwSystemIcons"

    // Honor 版 SignalClusterView（未混淆，2026-11-15 真机栈实证）：applyHonor(ZZ)V 在 noSims/
    // 飞行模式状态回调（SignalClusterView.apply → apply$1）里把 mMobileSignalGroup（上游 AOSP
    // findViewById(R.id.mobile_combo)，即 U6 接管的组）翻回 VISIBLE = 胶囊收缩期约 1s SIM 叠影
    // 的写手之一（间谍栈多次同源 applyHonor:48，setNoSims/setIsAirplaneMode/setEthernetIndicators 全触发）。
    private const val CLS_HW_SIGNAL_CLUSTER_VIEW = "com.android.systemui.statusbar.HwSignalClusterView"

    // 堆叠动画状态机（未混淆）。resetState(Z)V 是胶囊收缩期翻组的第二个真写手（2026-11-15 10:49
    // 真机栈实证：resetState:161 ← HnPhoneStatusBarAnimController.resetScaleStateWithoutAnim ←
    // HwStatusbarContents$2.run / BaseCapsuleStatusBarHelper.changeCapsuleVisibility→setKeyguardState）。
    // 上一轮静态排查曾把它判成死路（awk 方法归属失败导致漏了 :2828 调用方），运行期取证已纠正。
    // 方法体 = 纯堆叠视觉状态复位（setVisibility/alpha/scale/监听器通知），全部属于 U6 已禁用的
    // 堆叠机制；调用方封闭（AnimController:2828 + $1 死监听器 + PullAnim 的同名字符串只是日志 tag）。
    private const val CLS_SIGNAL_STACK_ANIM_HELPER = "com.android.systemui.statusbar.phone.SignalStackAnimHelper"

    // ---- DualSignal 插件冲突防护 ----

    /** DualSignal 插件包名（其 hook 先注入时插件容器已存在，据此退避）。 */
    private const val DUAL_SIGNAL_PACKAGE = "com.mod.dualsignal"

    /** DualSignal 3.3 自建容器的 tag（jadx 实证 TAG_CONTAINER = "dual_container"）。 */
    private const val FOREIGN_CONTAINER_TAG = "dual_container"

    // ---- 自建视图 tag（加前缀，不与插件撞）----

    private const val TAG_CONTAINER = "hh_dual_container"
    private const val TAG_SIGNAL = "hh_dual_signal"
    private const val TAG_LABEL = "hh_dual_label"

    // ---- 视觉常量（原样移植自 DualSignal 3.3.4，作者调好的参数；勿改）----

    private const val BAR_W_DP = 3.1f
    private const val BAR_GAP_DP = 2.0f
    private const val BAR_MAX_H_DP = 7.6f          // 双排：上排方柱压扁（状态栏高度物理约束，§7.2）
    private const val SINGLE_BAR_MAX_H_DP = 11.0f  // 单卡：不压扁
    private const val ROW_GAP_DP = 1.6f
    private const val DIM_ALPHA = 64               // 未点亮柱的透明度
    // 3.3.4 校准（原 3.3 静态双侧 1.5f）：右侧固定 2dp；左侧动态，左邻是文本类时收紧 0dp
    private const val HOST_MARGIN_DP = 2.0f
    private const val HOST_MARGIN_TEXT_DP = 0.0f
    private val BAR_H_RATIO = floatArrayOf(0.48f, 0.6f, 0.79f, 1.0f)

    // ---- 制式标视觉常量（上游 ensureContainer 原样；smali 实证 setTextSize(2, 13.0f)，2 = SP）----
    private const val LABEL_OFFSET_Y_DP = 0.7f
    private const val LABEL_MARGIN_LEFT_DP = 5.0f
    private const val LABEL_TEXT_SIZE_SP = 13.0f

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

    /**
     * 子开关「5G 制式标」：install 期读一次（hook 侧只读，改了要重启 SystemUI 才生效，与全插件一致）。
     * 为假时不注入 label 视图（连视图都不建，不是建了再藏）——省一次绘制与取色同步。
     */
    @Volatile private var labelEnabled = false

    /** DualSignal 插件包存在性探测（每进程一次，只做留痕，不做门）。 */
    private val pkgProbed = AtomicBoolean(false)

    /** WiFi 避让 no-op 的 judge 留痕门（该方法高频触发，只记首次命中）。 */
    private val wifiAlphaKeepLogged = AtomicBoolean(false)

    /** SIM 翻转收口的 judge 留痕门（布局重排高频触发，只记首次命中）。 */
    private val stackNotifyLogged = AtomicBoolean(false)

    /** applyHonor 收口的 judge 留痕门（状态回调频繁触发，只记首次命中）。 */
    private val applyHonorLogged = AtomicBoolean(false)

    /** resetState 收口的 judge 留痕门。 */
    private val resetStateLogged = AtomicBoolean(false)

    /** mStackedMobileIcons 反射 Field 缓存（键=宿主 Class，进程内稳定；WeakHashMap 防跨进程悬挂）。 */
    private val stackedIconsFieldCache = WeakHashMap<Class<*>, Field>()

    /** 原生组被翻回可见的计数（审计：非 0 = 仍有写手翻组，数值=翻回频率，applyHonor 收口后应恒 0 增长）。 */
    private val groupFlipCount = AtomicLong(0)

    /** 被 U6 接管的原生组（弱持有）：applyHonor 收口的回藏对象集（U6 接管后任何时刻都应保持 GONE）。 */
    private val trackedGroups: MutableSet<LinearLayout> =
        java.util.Collections.newSetFromMap(java.util.WeakHashMap())

    /**
     * 可见性间谍（四轮取证，改只读哨兵）：挂 `android.view.View.setVisibility(I)V` 全量钩子，
     * 仅当 thisObject ∈ trackedGroups 时记日志——**不再拦截**（放行一切，不遮蔽真实验证），
     * 用于盯 resetState 收口后是否还有第三写手。上限 50 次采样防风暴。
     */
    private val spyInstalled = AtomicBoolean(false)

    /** 间谍日志上限。 */
    private val spyLogCount = AtomicLong(0)

    /** 未接管 mobile_combo 实例的采样上限（重建视图检测，20 次足够）。 */
    private val spyUntrackedLog = AtomicLong(0)

    fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        if (!RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_DUAL_SIGNAL)) {
            logDisabled(module, FeatureCatalog.KEY_SYSTEMUI_DUAL_SIGNAL, "SystemUiHook·U6")
            return
        }
        labelEnabled = RemoteConfig.isEnabled(FeatureCatalog.KEY_SYSTEMUI_DUAL_SIGNAL_LABEL)
        val loader = param.defaultClassLoader
        val session = MountEngine.open("SystemUiHook·U6", HOST_PACKAGE, param.applicationInfo?.sourceDir, FeatureCatalog.baselineVersionOf(HOST_PACKAGE))

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
        session.attemptMount(pending, module, loader) { task -> mountOne(session, task, module, loader) }

        // 堆叠掐断点：两个布尔收口方法恒返 false（libxposed 回调返回值即宿主返回值）。
        val stackKill = listOf(
            MountEngine.SimpleTask(
                className = CLS_SIGNAL_STACK_POLICY,
                methodName = "shouldShowStacked",
                paramCount = 2,
                returnType = "boolean",
                id = "systemui:dualSignal.StackPolicy.shouldShowStacked",
                description = "U6 双排信号·掐断动态堆叠切换（胶囊激活+空间不足时系统自行切堆叠态）",
            ),
            MountEngine.SimpleTask(
                className = CLS_SIGNAL_STACK_POLICY,
                methodName = "isStackEnabled",
                paramCount = 0,
                returnType = "boolean",
                id = "systemui:dualSignal.StackPolicy.isStackEnabled",
                description = "U6 双排信号·掐断堆叠总开关（stackMode≠OFF 即真的兜底路径）",
            ),
        )
        session.attemptMount(stackKill, module, loader) { task -> mountStackKill(session, task, module, loader) }

        // 堆叠掐断的二阶效应收口（2026-11-14 真机实证）：stackKill 后「胶囊激活+空间不足」的原生
        // 省宽兜底只剩 HwSystemIcons 胶囊避让——updateWifiLeftViewAlphaByCapsule 判 WiFi 溢出 →
        // setWifiIconAlpha 把 mWifiCombo alpha 渐隐 0 ⇒ 用户视角「出现胶囊 WiFi 图标消失」。
        // 不 proceed = 避让对 WiFi 不再发生（alpha 恒原值；恢复路径一并失效，但从未藏过也就无需恢复）。
        // 副作用面已核（117 版 smali 全树）：该方法仅 setCapsuleArea(~6575)/状态栏类型切换(~7676)
        // 两处调用；setWifiIconAlpha 的另一入口（$2 动画结束监听）依赖避免动画先行启动，而动画只由
        // setWifiIconAlpha 自身启动 ⇒ 链路不成立，无滞留标志泄漏；其它图标避让
        // （updateOperationViewAlphaByCapsule）与本挂载无关，行为不变。
        val wifiAlphaKeep = listOf(
            MountEngine.SimpleTask(
                className = CLS_HW_SYSTEM_ICONS,
                methodName = "updateWifiLeftViewAlphaByCapsule",
                paramCount = 0,
                returnType = "void",
                id = "systemui:dualSignal.HwSystemIcons.updateWifiLeftViewAlphaByCapsule",
                description = "U6 双排信号·掐断胶囊避让对 WiFi 的渐隐（堆叠已断后系统省宽的兜底路径，不 proceed = WiFi 恒显）",
            ),
        )
        session.attemptMount(wifiAlphaKeep, module, loader) { task -> mountWifiAlphaKeep(session, task, module, loader) }

        // SIM 叠影收口（2026-11-14 真机实证）：notifySignalStackChange 的 false 分支（smali :cond_3）无条件
        // mMobileCombo.setVisibility(VISIBLE)——正是把 U6 藏掉的原生 SIM 组翻回来的那只手。updateSignalStack
        // 经 checkSignalBySpace（胶囊出现/图标区重排集中触发）/onAttachedToWindow 每轮都走它，原生 SIM 组与
        // U6 自绘容器同锚 FrameLayout 叠绘 = 「SIM 图标上叠加另一个图标一段时间」，直到 U6 下一轮 maintain
        // （全局布局节流/2s 拍）重新 GONE。不 proceed = 翻转不发生（WiFi 收口同款 before 语义）；回调内补
        // mStackedMobileIcons=GONE（原生 false 分支的本职工作，替它做掉，免该视图初始可见性裸奔）。
        // 调用方全树：updateSignalStack / HnStatusBarPullAnimImpl 下拉动画重同步 / SignalStackAnimHelper
        // $StackedStateListener（堆叠动画监听，本机 stacked_mobile_icon_switch=0 已死）——翻的都是这对视图。
        val stackNotifyKill = listOf(
            MountEngine.SimpleTask(
                className = CLS_HW_SYSTEM_ICONS,
                methodName = "notifySignalStackChange",
                paramCount = 1,
                returnType = "void",
                id = "systemui:dualSignal.HwSystemIcons.notifySignalStackChange",
                description = "U6 双排信号·掐断原生 SIM 组可见性翻转（false 分支无条件翻回 VISIBLE，与 U6 的 GONE 维护互搏）",
            ),
        )
        session.attemptMount(stackNotifyKill, module, loader) { task -> mountStackNotifyKill(session, task, module, loader) }

        // SIM 叠影终局收口（2026-11-15 真机栈实证）：胶囊收缩期翻组的真写手是
        // HwSignalClusterView.applyHonor(ZZ)V——noSims/飞行模式状态回调经 SignalClusterView.apply()
        // 进来，内部把 mMobileSignalGroup（=上游 findViewById(R.id.mobile_combo)，U6 接管的组）
        // setVisibility 翻回 VISIBLE（间谍栈 5 次全部 applyHonor:48，含 setNoSims/setIsAirplaneMode
        // 两条路）。after 语义：放行原生逻辑（noSims 布局/spacer/堆叠图标/cust 指示器照常工作），
        // 随即把 trackedGroups 全部重新 GONE——同帧回藏，无 maintain 拍子（2s）留下的叠影窗口。
        // 挂载时机早于 fixGroup 填充 trackedGroups：回调迭代的是当时快照，弱持有，天然兼容。
        val applyHonorKeep = listOf(
            MountEngine.SimpleTask(
                className = CLS_HW_SIGNAL_CLUSTER_VIEW,
                methodName = "applyHonor",
                paramCount = 2,
                returnType = "void",
                id = "systemui:dualSignal.HwSignalClusterView.applyHonor",
                description = "U6 双排信号·applyHonor 后回藏被接管的原生组（胶囊收缩期翻组写手，after 语义保原生其余逻辑）",
            ),
        )
        session.attemptMount(applyHonorKeep, module, loader) { task -> mountApplyHonorKeep(session, task, module, loader) }

        // SIM 叠影第二写手收口（2026-11-15 10:49 真机栈实证）：胶囊收缩/锁屏切换期
        // BaseCapsuleStatusBarHelper.changeCapsuleVisibility → resetScaleStateWithoutAnim →
        // SignalStackAnimHelper.resetState(Z)V（:161 setVisibility）把 U6 藏掉的原生 SIM 组翻回
        // VISIBLE——上轮静态排查误判死路（awk 归属失败），运行期取证纠正。no-op（不 proceed）=
        // 堆叠状态复位整体跳过：方法体全是 U6 已禁用的堆叠视觉状态（setVisibility/alpha/scale/
        // StackedStateListener 通知），无其它职责。调用方封闭见 CLS_SIGNAL_STACK_ANIM_HELPER 注释。
        val resetStateKill = listOf(
            MountEngine.SimpleTask(
                className = CLS_SIGNAL_STACK_ANIM_HELPER,
                methodName = "resetState",
                paramCount = 1,
                returnType = "void",
                id = "systemui:dualSignal.SignalStackAnimHelper.resetState",
                description = "U6 双排信号·掐断堆叠状态复位翻组（胶囊收缩/锁屏切换路径，no-op）",
            ),
        )
        session.attemptMount(resetStateKill, module, loader) { task -> mountResetStateKill(session, task, module, loader) }
    }

    /** resetState 收口 no-op：不 proceed（void），堆叠状态复位不发生；首次命中打 judge 留痕。 */
    private fun mountResetStateKill(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { _ ->
            if (resetStateLogged.compareAndSet(false, true)) {
                session.judge(module, task, JUDGE_PREFIX, "堆叠状态复位已掐断（resetState 不放行，胶囊收缩期翻组路径二）")
            }
            null
        }

    /** SIM 翻转收口 no-op：不 proceed，原生翻组不发生；补置堆叠视图 GONE（原生 false 分支的本职）。 */
    private fun mountStackNotifyKill(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            runCatching {
                val self = chain.thisObject ?: return@runCatching
                val field = stackedIconsFieldCache[self.javaClass]
                    ?: self.javaClass.getDeclaredField("mStackedMobileIcons").apply {
                        isAccessible = true
                        stackedIconsFieldCache[self.javaClass] = this
                    }
                (field.get(self) as? View)?.let { v ->
                    if (v.visibility != View.GONE) v.visibility = View.GONE
                }
            }
            if (stackNotifyLogged.compareAndSet(false, true)) {
                session.judge(module, task, JUDGE_PREFIX, "原生 SIM 组翻转已掐断（notifySignalStackChange 不放行）")
            }
            null // void 方法：before 语义不 proceed
        }

    /** 胶囊避让藏 WiFi 收口 no-op：不 proceed（void）= 渐隐永不发生；该方法高频触发，仅首次命中打 judge 留痕。 */
    private fun mountWifiAlphaKeep(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { _ ->
            if (wifiAlphaKeepLogged.compareAndSet(false, true)) {
                session.judge(module, task, JUDGE_PREFIX, "胶囊避让藏 WiFi 已掐断（updateWifiLeftViewAlphaByCapsule 不放行）")
            }
            null // void 方法：before 语义不 proceed，无返回值语义可伪造
        }

    /**
     * applyHonor 收口（after 语义）：proceed 放行原生（noSims/spacer/堆叠图标/cust 逻辑不受影响），
     * 然后把 trackedGroups 里仍非 GONE 的组立即回藏。写手身份已由间谍定案（见 CLS_HW_SIGNAL_CLUSTER_VIEW
     * 注释），间谍钩子已撤——此处只做同帧回藏，消除 maintain 拍子（2s）造成的叠影空窗。
     */
    private fun mountApplyHonorKeep(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            chain.proceed()
            for (g in trackedGroups) {
                if (g.visibility != View.GONE) {
                    val n = groupFlipCount.incrementAndGet()
                    module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 维持: applyHonor 翻组已同帧回藏（第 $n 次）")
                    g.visibility = View.GONE
                }
            }
            if (applyHonorLogged.compareAndSet(false, true)) {
                session.judge(module, task, JUDGE_PREFIX, "applyHonor 翻组收口已挂（after 回藏 trackedGroups）")
            }
            null // void 方法，无返回值语义
        }

    /** 间谍装钩（fixGroup 首次接管时调用一次）；loader 由 attach 挂载留存。 */
    @Volatile private var spyLoader: ClassLoader? = null

    private fun ensureVisibilitySpy(session: MountSession, module: XposedModule) {
        if (!spyInstalled.compareAndSet(false, true)) return
        val loader = spyLoader ?: return
        val spy = listOf(
            MountEngine.SimpleTask(
                className = "android.view.View",
                methodName = "setVisibility",
                paramCount = 1,
                returnType = "void",
                id = "systemui:dualSignal.spy.View.setVisibility",
                description = "U6 双排信号·四轮取证：可见性间谍（拦第二写手 + 栈采样），定位后摘除",
            ),
        )
        session.attemptMount(spy, module, loader) { task -> mountVisibilitySpy(session, task, module, loader) }
    }

    private fun mountVisibilitySpy(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            val self = chain.thisObject
            if (self is View && trackedGroups.contains(self)) {
                val vis = chain.args?.getOrNull(0) as? Int
                if (vis != null && vis != View.GONE) {
                    if (spyLogCount.incrementAndGet() <= 50L) {
                        val stack = Thread.currentThread().stackTrace
                            .filter {
                                !it.className.contains("LSPosed") && !it.className.contains("HookBridge") &&
                                    !it.className.contains("libxposed") && !it.className.contains("DualSignalFeature")
                            }
                            .take(14)
                            .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                        module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 间谍: 组(id=${runCatching { if (self.id != View.NO_ID) self.resources.getResourceEntryName(self.id) else "no-id" }.getOrDefault("?")}@${Integer.toHexString(System.identityHashCode(self))}) 被 setVisibility(vis=$vis) 翻动，栈: $stack")
                    }
                    chain.proceed() // 只读哨兵：放行一切，不遮蔽验证
                } else {
                    chain.proceed()
                }
            } else if (self is View && idMobileCombo != 0 && self.id == idMobileCombo && self.visibility != View.GONE) {
                // 未接管的 mobile_combo 实例（系统重建视图/第二窗口绕过 walkAndFix）——同样记栈，上限 20
                if (spyUntrackedLog.incrementAndGet() <= 20L) {
                    val stack = Thread.currentThread().stackTrace
                        .filter { !it.className.contains("LSPosed") && !it.className.contains("HookBridge") }
                        .take(14)
                        .joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
                    module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 间谍: 未接管 mobile_combo@${Integer.toHexString(System.identityHashCode(self))} setVisibility(vis=${chain.args?.getOrNull(0)})，栈: $stack")
                }
                chain.proceed()
            } else {
                chain.proceed()
            }
        }

    /** 堆叠收口恒 false：首次命中打 judge 留痕；返回 java.lang.Boolean（libxposed 要求包装类型）。 */
    private fun mountStackKill(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            if (chain.proceed() == java.lang.Boolean.TRUE) {
                session.judge(module, task, JUDGE_PREFIX, "堆叠切换已掐断（原 ${chain.thisObject?.javaClass?.simpleName} 欲进堆叠态）")
            }
            java.lang.Boolean.FALSE
        }

    private fun mountOne(session: MountSession, task: MountEngine.SimpleTask, module: XposedModule, loader: ClassLoader): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            spyLoader = loader
            // after 语义：先放行宿主原 attach 逻辑，再调度注入（void 方法 → 回调显式返回 null，§7.4）
            chain.proceed()
            val self = chain.thisObject as? View
            if (self != null) {
                // 原插件语义：root 找不到时退回 self（safeRestyle 对小子树遍历无副作用）
                // cluster root 判定按类名（SimpleTask 无目标私有字段，isClusterRoot 由 className 推导）
                val isClusterRoot = task.className == CLS_SIGNAL_CLUSTER_VIEW
                val root = if (isClusterRoot) self else findClusterRoot(self) ?: self
                schedule(session, root, task, module)
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
    private fun schedule(session: MountSession, root: View, task: MountEngine.SimpleTask, module: XposedModule) {
        root.post { safeRestyle(session, root, task, module) }
        root.postDelayed({ safeRestyle(session, root, task, module) }, 500L)
    }

    private fun safeRestyle(session: MountSession, root: View, task: MountEngine.SimpleTask, module: XposedModule) {
        try {
            resolveResourceIdsOnce(root.context)
            val fixed = walkAndFix(session, root, task, module)
            if (fixed > 0) {
                session.judge(module, task, JUDGE_PREFIX, "双排容器已接管 $fixed 处")
            }
        } catch (t: Throwable) {
            module.log(Log.WARN, MountEngine.TAG, "$JUDGE_PREFIX restyle 失败: $t")
        }
    }

    /** 遍历找候选组：mobile_combo（按 id）或「水平 LinearLayout 且 ≥1 个 mobile 子单元」（按形态）。 */
    private fun walkAndFix(session: MountSession, v: View, task: MountEngine.SimpleTask, module: XposedModule): Int {
        var n = 0
        if (v is LinearLayout) {
            val byId = idMobileCombo != 0 && v.id == idMobileCombo
            // 形态兜底须排除混有 wifi/ethernet 的组：117 版宿主在 stack 切换期（SignalStackPolicy
            // shouldShowStacked 动态翻转）会瞬时把 wifi 与 mobile 装进同一水平容器，整组 GONE 会
            // 把 WiFi 一起带走——「WiFi 有时消失」的根因，只对按形态命中生效，按 id 命中不动。
            val byCount = v.orientation == LinearLayout.HORIZONTAL &&
                countMobileChildren(v) >= 1 && !hasWifiOrEthernetChild(v)
            if (byId || byCount) {
                // 只计真接管（退避/外壳非 FrameLayout 不计——judge 行「已接管 N 处」必须是真话）
                if (fixGroup(session, v, task, module)) n++
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                v.getChildAt(i)?.let { n += walkAndFix(session, it, task, module) }
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

    /** 形态候选的否决条件：组内混有 wifi 类名子节点或 ethernet_combo（整组 GONE 会带走 WiFi）。 */
    private fun hasWifiOrEthernetChild(ll: LinearLayout): Boolean {
        for (i in 0 until ll.childCount) {
            val c = ll.getChildAt(i) ?: continue
            if (c.javaClass.name.contains("Wifi") || c.javaClass.name.contains("WiFi")) return true
            val id = c.id
            if (idEthernetCombo != 0 && id == idEthernetCombo) return true
        }
        return false
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
    private fun fixGroup(session: MountSession, group: LinearLayout, task: MountEngine.SimpleTask, module: XposedModule): Boolean {
        val host = group.parent as? FrameLayout ?: return false   // 原插件硬约束：组外壳必须是 FrameLayout
        if (backedOff[host] == true) return false
        // 冲突防护（先到先得）：插件的容器已在 → 本 host 永久退避，原生组交给插件处理
        if (hasForeignContainer(host)) {
            backOff(module, host)
            return false
        }
        if (group.visibility != View.GONE) group.visibility = View.GONE
        trackedGroups.add(group)
        ensureVisibilitySpy(session, module)

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
     * 周期维护（原插件 maintain，含 label 分支）：保证原生组隐藏、容器存活、档位/取色/制式标新鲜、
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
        if (group.visibility != View.GONE) {
            val n = groupFlipCount.incrementAndGet()
            module.log(Log.INFO, MountEngine.TAG, "$JUDGE_PREFIX 维持: 原生组被翻回可见，重新 GONE（第 $n 次）")
            group.visibility = View.GONE
        }

        var container = findContainer(host)
        if (container == null) {
            ensureContainer(host)
            container = findContainer(host) ?: return
        }
        val signal = findSignal(container)
        val label = findLabel(container)

        val ctx = host.context
        val simCount = getSimCount(ctx).let { if (it < 0) 2 else it }
        if (simCount == 0) {
            if (container.visibility != View.GONE) container.visibility = View.GONE
            return
        }
        if (container.visibility != View.VISIBLE) container.visibility = View.VISIBLE

        val color = pickTint(group, host) ?: Color.WHITE
        signal?.setColor(color)
        label?.setTextColor(color)   // 制式标与信号格同色（上游 maintain 同款）

        val levels = getActiveLevels(ctx)
        val mainLevel = levels.getOrElse(0) { 4 }
        val subLevel = levels.getOrNull(1)
        signal?.setInfo(simCount, mainLevel, subLevel)

        updateLabel(ctx, label)

        moveBeforeWifi(host)

        // 3.3.4 校准：右侧固定 2dp；左侧按 host 前第一个可见兄弟动态——TextView 或类名小写含
        // "speed"（网速显示）→ 0dp，否则 2dp；找不到可见兄弟保持 2dp。jadx 把上游 while 还原成
        // 多出口伪码，实际语义 = 找到首个可见兄弟即定值停止。
        var leftDp = HOST_MARGIN_DP
        val row = host.parent as? ViewGroup
        if (row != null) {
            var i = row.indexOfChild(host) - 1
            while (i >= 0) {
                val pv = row.getChildAt(i)
                if (pv.visibility != View.VISIBLE) {
                    i--
                    continue
                }
                leftDp =
                    if (pv is TextView || pv.javaClass.name.lowercase().contains("speed")) {
                        HOST_MARGIN_TEXT_DP
                    } else {
                        HOST_MARGIN_DP
                    }
                break
            }
        }
        val hp = host.layoutParams
        if (hp is ViewGroup.MarginLayoutParams) {
            val wantR = dp(host, HOST_MARGIN_DP)
            val wantL = dp(host, leftDp)
            if (hp.width != ViewGroup.LayoutParams.WRAP_CONTENT ||
                hp.rightMargin != wantR || hp.leftMargin != wantL
            ) {
                hp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                hp.rightMargin = wantR
                hp.leftMargin = wantL
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

    /** 注入自建容器：LinearLayout（tag hh_dual_container）内一枚自绘双排信号格；制式标开关开时，
     *  在信号格之后补一枚 label TextView（tag hh_dual_label，视图参数原样移植上游 ensureContainer）。 */
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
        if (labelEnabled) {
            // 先 GONE：可见性由 updateLabel 按网络状态决定（WiFi / 移动数据关时不显示）
            val label = TextView(ctx)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_TEXT_SIZE_SP)
            label.typeface = Typeface.DEFAULT_BOLD
            label.setIncludeFontPadding(false)
            label.tag = TAG_LABEL
            label.translationY = dp(host, LABEL_OFFSET_Y_DP).toFloat()
            label.visibility = View.GONE
            val llp = LinearLayout.LayoutParams(WRAP, WRAP)
            llp.leftMargin = dp(host, LABEL_MARGIN_LEFT_DP)
            llp.gravity = Gravity.CENTER_VERTICAL
            container.addView(label, llp)
        }
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

    /** 按 tag 找制式标（上游 findLabel；labelEnabled 为假时容器里根本没这个孩子 → 恒 null）。 */
    private fun findLabel(container: LinearLayout): TextView? {
        for (i in 0 until container.childCount) {
            val c = container.getChildAt(i)
            if (c is TextView && (c.tag as? String) == TAG_LABEL) return c
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
            // 3.3.4 校准：单循环、无 VISIBLE 过滤，类名含 wifi 或 ethernet_combo id 任一命中即 wifi
            // （原 3.3 两段循环且带 VISIBLE 过滤）
            if (c !== host &&
                (c.javaClass.name.lowercase().contains("wifi") ||
                    (idEthernetCombo != 0 && c.id == idEthernetCombo))
            ) {
                wifi = c
                break
            }
        }
        if (wifi == null) return
        val wi = row.indexOfChild(wifi)
        val hi = row.indexOfChild(host)
        // 3.3.4 触发条件 hi != wi-1（原 3.3 为 hi > wi）：host 已在 wifi 前一位时不做无谓 remove/add
        if (hi >= 0 && wi >= 0 && hi != wi - 1) {
            row.removeView(host)
            // wifi 在第 0 位时 wi-1 = -1 会 IndexOutOfBoundsException（静态审计发现的唯一崩溃面），
            // 语义仍是「排到 wifi 前」→ 夹 0
            row.addView(host, maxOf(wi - 1, 0))
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

    // ---- 制式标数据链（上游 getNetworkLabel / isMobileDataEnabled，smali 实证）----

    /**
     * 制式文字（上游 getNetworkLabel）。**上游 jadx 产物此方法不可用**：packed-switch 的 case 表被吞，
     * 反编译成空 `switch (type) { }`，恒返回默认 "4G"；映射以 apktool smali（`.packed-switch 0x1`
     * 起 20 项）为准，表见 §7.11b。取值链：默认数据卡 `getDataNetworkType()`，为 0 时回落
     * `getNetworkType()`；tm 取不到 → null（上游同款，调用方按「无文字」处理 = 隐藏）。
     */
    private fun getNetworkLabel(ctx: Context): String? = runCatching {
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val dataSub = SubscriptionManager.getDefaultDataSubscriptionId()
        val dtm = if (dataSub < 0) tm else tm.createForSubscriptionId(dataSub)
        @Suppress("DEPRECATION")   // getNetworkType 仅在 getDataNetworkType 返回 0 时兜底（上游同款）
        val type = dtm.dataNetworkType.let { if (it == 0) dtm.networkType else it }
        // 用字面量而非 TelephonyManager 常量：19(LTE_CA) 是 @SystemApi、公开 android.jar 里没有；
        // 值本身即 smali `.packed-switch 0x1` 的 case 键，逐条可对。
        when (type) {
            20 -> "5G"   // NR
            13, 18, 19 -> "4G"   // LTE / IWLAN / LTE_CA
            3, 8, 9, 10, 15, 17 -> "3G"   // UMTS / HSDPA / HSUPA / HSPA / HSPAP / TD_SCDMA
            // GPRS / EDGE / CDMA / 1xRTT / EVDO_0 / EVDO_A / IDEN / EVDO_B / EHRPD / GSM
            1, 2, 4, 5, 6, 7, 11, 12, 14, 16 -> "2G"
            else -> "4G"   // 含 default / 未列出的新制式（上游同款：兜 4G 而不是空）
        }
    }.getOrDefault("4G")

    /**
     * 上游 isMobileDataEnabled（smali 实证）：`Settings.Global.getInt(cr, "mobile_data", 1) == 1`；
     * 读不到（SecurityException 等）→ true（上游容错：宁可显示制式标，也不要静默消失）。
     */
    private fun isMobileDataEnabled(ctx: Context): Boolean = runCatching {
        Settings.Global.getInt(ctx.contentResolver, "mobile_data", 1) == 1
    }.getOrDefault(true)

    /**
     * 制式标显示判定（上游 updateLabel 的**本模块变体**，2026-10-16 用户拍板改）：
     * **移动数据开**就取制式文字并 VISIBLE + setText；移动数据关或文字为空 → GONE。
     *
     * 与上游的唯一差异 = 去掉 WiFi 门。上游是「非 WiFi 且有移动数据」才显示
     * （`DualSignalHook.smali` .line 377-380：`if-nez isWifiConnected` + `if-eqz
     * isMobileDataEnabled` 才取文字），代价是 **WiFi 一连 5G 标就消失**——真机 2026-10-16
     * 用户报「双排 5G 没效果」，定位即此门（当时正连 WiFi）。原生状态栏是 WiFi 与移动制式同显，
     * 本变体与之一致。`isWifiConnected` 因此**已删**（本文件内无第二处消费）。
     *
     * label 为 null（子开关关 → 未注入）时直接返回。
     */
    private fun updateLabel(ctx: Context, label: TextView?) {
        if (label == null) return
        val text = if (isMobileDataEnabled(ctx)) getNetworkLabel(ctx) else null
        if (text.isNullOrEmpty()) {
            if (label.visibility != View.GONE) label.visibility = View.GONE
            return
        }
        label.text = text
        if (label.visibility != View.VISIBLE) label.visibility = View.VISIBLE
    }

    private fun dp(host: View, value: Float): Int =
        Math.round(host.resources.displayMetrics.density * value)

    /** Choreographer 每帧取色：tint 变了才 invalidate 自绘视图（原插件 ColorSyncCallback 同款）。 */
    private class ColorSyncCallback(private val host: FrameLayout) : Choreographer.FrameCallback {
        private var lastColor = Int.MIN_VALUE

        override fun doFrame(frameTimeNanos: Long) {
            // host 脱离窗口即停拍（不再 post 下一帧）——原插件每帧自续永不注销的泄漏在此收口；
            // 新 cluster/视图 attach 时 fixGroup 会挂新的 callback，生命周期随 host 走。
            // 边界（审计 2026-10-12）：同一 host 对象 detach→reattach 后 callback 不重挂（fixGroup 仅
            // firstTime），帧级取色跟随永久死亡——reattach 只重跑 maintain（档位刷新不受影响）。
            if (!host.isAttachedToWindow) return
            try {
                val container = findContainer(host) ?: return
                val signal = findSignal(container) ?: return
                val color = tintFromRow(host)
                if (color != null && color != lastColor) {
                    lastColor = color
                    signal.setColor(color)
                    findLabel(container)?.setTextColor(color)   // 制式标同色（上游 doFrame 同款）
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
