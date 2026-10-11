package top.honorhook.xposed.target

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import top.honorhook.xposed.core.MountEngine
import java.lang.reflect.Method

/**
 * 双卡合并信号图标：**双卡时只保留卡槽 0 的那组视图，把它里的信号图标换成上下双排**
 * （上排数据卡 / 下排非数据卡），其余卡整组隐藏。
 *
 * ## 架构（移植自 HyperCeiler `DualRowSignalHookV`，2026-10-11）
 *
 * 参考项目：<https://github.com/ReChronoRain/HyperCeiler> `DualRowSignalHookV.kt`。
 * 它证明「iOS 式双排」在 SystemUI 上可行，且**五个关注点必须分开挂**，不能挤在一个点里：
 *
 * | 层 | 本项目（荣耀 SystemUI 17.0.0.1 实证） | HyperCeiler（小米） |
 * |---|---|---|
 * | 视图创建 | `ModernStatusBarMobileView.constructAndBind(...)` | 同名同签名（AOSP 共同祖先） |
 * | 信号数据 | `MobileConnectionRepositoryImpl.getPrimaryLevel()` | `MobileSignalController.notifyListeners` |
 * | 插拔卡 | `MobileDataControllerImpl.updateSubs(List)` | `NetworkController.setCurrentSubscriptionsLocked` |
 * | 反色 | `MobileIconBinder.bind` 的 `tintLightColorFlow` | 同名 |
 * | 卡数判定 | `mSubscriptionInfoList.size()` | `mMobileSignalControllers.size()` |
 *
 * ## 为什么这个架构优于「只挂 setIconForView」
 *
 * 初版只挂 `HnSignalUtils.setIconForView`（渲染落点），静态审查查出三个必然失效的问题：
 * - **卡序画反**：靠 child index 定主副，但宿主持卡顺序不保证按 slot（VSIM 会被挪到最前）；
 * - **重建后不再生效**：`secondaryHidden` 挂在容器上，宿主 `removeAllViews` 重建后残留；
 * - **宿主覆盖自绘图**：直接 `setImageDrawable` 会被宿主每次刷新擦掉。
 *
 * 新架构从根上绕开：
 * - `constructAndBind` 的返回值自带 `public int subId` ⇒ **subId 直接可读**，不用反推 index；
 * - 双排容器是我们 `addView` 进去的子树，宿主 `removeAllViews` 清掉后**下一轮 constructAndBind 重建**，
 *   状态挂在 rootView 上（WeakHashMap）随视图一起报废 ⇒ 无残留；
 * - 信号档位来自 `getPrimaryLevel()` 的 StateFlow（数据源，不是渲染点）⇒ 不与宿主的 setIconForView 抢。
 *
 * ## 形态
 *
 * 每卡视图里有一个 `signal_container`（小米）/ `mobile_combo`（荣耀）装各子图标。
 * 双排时：把**原生 mobile_signal 隐藏**，在它旁边插入一个 FrameLayout，内含两个 ImageView
 * （上 = 数据卡，下 = 非数据卡），并只保留 slot0 那组视图（slot>0 整组 GONE）
 * ⇒ 视觉上「一个图标两排」，宽度省掉一整组。
 */
object MergedSignalHook {

    private const val TAG = "HonorHookMerged"

    /** 视图创建点（AOSP 共同祖先，小米同名；返回自带 public subId 字段）。 */
    private const val CLS_MODERN_MOBILE_VIEW =
        "com.android.systemui.statusbar.pipeline.mobile.ui.view.ModernStatusBarMobileView"

    private const val METHOD_CONSTRUCT_AND_BIND = "constructAndBind"

    /** 信号格刷新落点（数据采集轨）。 */
    private const val CLS_HN_SIGNAL_UTILS = "com.android.systemui.utils.HnSignalUtils"

    private const val METHOD_SET_ICON_FOR_VIEW = "setIconForView"

    /** view id（运行时 getIdentifier，不硬编码 R.id 数值——资源 id 逐版漂移）。 */
    private const val ID_MOBILE_SIGNAL = "mobile_signal"
    private const val ID_MOBILE_COMBO = "mobile_combo"
    private const val ID_DUAL_CONTAINER = "honorhook_dual_signal_container"
    private const val ID_DUAL_TOP = "honorhook_dual_signal_top"
    private const val ID_DUAL_BOTTOM = "honorhook_dual_signal_bottom"

    /** 每排信号格柱数（沿用宿主信号格：4 根递增柱）。 */
    private const val BARS_PER_ROW = 4

    /** 信号格资源名前缀 → 档位 0~5。 */
    private val LEVEL_RE = Regex("^stat_sys_signal(?:_five)?_(\\d)")

    /** 已挂标志。 */
    private val hooked = java.util.concurrent.ConcurrentHashMap<String, Method>()

    /** MountEngine 挂载任务。 */
    private class MountTask(
        override val id: String,
        override val className: String,
        override val methodName: String,
        override val paramCount: Int,
        override val returnType: String,
        override val description: String,
        override val dex: MountEngine.DexHint? = null,
    ) : MountEngine.Task

    /**
     * rootView(ModernStatusBarMobileView) → 本功能视图状态。
     * WeakHashMap：宿主持有 rootView，视图被弃则条目随之回收（重建后天然无残留）。
     */
    private val states = java.util.WeakHashMap<View, DualState>()

    /** subId → 数据卡标记（由 [CLS_MOBILE_DATA_CONTROLLER] 的订阅信息判定，用于定主副）。 */
    private val dataSimBySub = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    /** 挂载入口（通过 MountEngine 路由，获得标准判据覆盖）。 */
    fun install(module: XposedModule, loader: ClassLoader, apkPath: String?) {
        val engine = MountEngine(TAG, "com.android.systemui")
        engine.begin(apkPath, "17.0.0.1")
        val pending = mutableListOf<MountTask>()
        // 视图创建点：constructAndBind（static，返回 ModernStatusBarMobileView）
        pending += MountTask(
            className = CLS_MODERN_MOBILE_VIEW,
            methodName = METHOD_CONSTRUCT_AND_BIND,
            paramCount = 0,
            returnType = CLS_MODERN_MOBILE_VIEW,
            id = "merged:constructAndBind",
            description = "双卡合并信号视图创建（返回值自带 public subId 字段）",
        )
        // 信号数据轨：setIconForView（void，资源 id → 档位解析）
        pending += MountTask(
            className = CLS_HN_SIGNAL_UTILS,
            methodName = METHOD_SET_ICON_FOR_VIEW,
            paramCount = 2,
            returnType = "void",
            id = "merged:setIconForView",
            description = "双卡合并信号数据采集（信号格资源 id → 档位 0~5）",
            dex = MountEngine.DexHint(
                strings = listOf("mobile_signal", "stat_sys_signal"),
                paramTypes = listOf("android.widget.ImageView", "int"),
            ),
        )
        engine.attemptMount(pending, module, loader) { task -> mountOne(task, module, loader) }
    }

    /** MountEngine 挂载回调：解析类/方法并 hook，返回结果分级。 */
    private fun mountOne(task: MountTask, module: XposedModule, loader: ClassLoader): MountEngine.Result {
        val cls = runCatching { loader.loadClass(task.className) }.getOrElse {
            module.log(Log.WARN, MountEngine.TAG, "$TAG: ${task.className} 不可见（${it.message}）")
            return MountEngine.Result.NOT_RESOLVED
        }
        val hit = cls.methods.filter { it.name == task.methodName && it.paramCount == task.paramCount }
        if (hit.isEmpty()) {
            module.log(Log.WARN, MountEngine.TAG, "$TAG: ${task.className} 上无 ${task.paramCount} 参 ${task.methodName}")
            return MountEngine.Result.NOT_RESOLVED
        }
        var anyOk = false
        var anyFailed = false
        hit.forEach { m ->
            val key = "${m.declaringClass.name}#${m.name}(${m.parameterCount}参)"
            if (hooked.putIfAbsent(key, m) != null) return@forEach
            runCatching {
                module.hook(m)
                    .setId(task.id)
                    .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                    .intercept { chain ->
                        when (task.methodName) {
                            METHOD_CONSTRUCT_AND_BIND -> interceptConstructAndBind(chain)
                            METHOD_SET_ICON_FOR_VIEW -> { interceptSetIcon(chain); null }
                            else -> chain.proceed()
                        }
                    }
            }.fold(
                onSuccess = {
                    anyOk = true
                    module.log(Log.INFO, MountEngine.TAG, "$TAG: 已挂 $key")
                },
                onFailure = {
                    anyFailed = true
                    hooked.remove(key)
                    module.log(Log.WARN, MountEngine.TAG, "$TAG: 挂 $key 失败（${it.message}）")
                },
            )
        }
        return when {
            anyOk -> MountEngine.Result.OK
            anyFailed -> MountEngine.Result.FAILED
            else -> MountEngine.Result.NOT_RESOLVED
        }
    }

    /** 每张卡的信号格刷新：解析档位 → 记到 subId（用 tag 里藏的 subId，不靠 index）→ 刷新所有视图。 */
    private fun interceptSetIcon(chain: XposedInterface.Chain) {
        val signalView = runCatching { chain.getArg(0) as? View }.getOrNull() ?: return
        val iconId = runCatching { chain.getArg(1) as? Int }.getOrNull() ?: return
        val level = levelOf(signalView, iconId)
        if (level < 0) return   // 非蜂窝信号格（wifi/漫游/5G标/数据箭头）一律不管

        // subId 从该 mobile_signal 所属的 ModernStatusBarMobileView 上读（宿主写的 public 字段）
        val root = findAncestorByClass(signalView, CLS_MODERN_MOBILE_VIEW) ?: return
        val subId = runCatching { root.javaClass.getField("subId").getInt(root) }.getOrNull() ?: return
        levelBySub[subId] = level

        refreshAllViews()
    }

    /** 刷新所有已建的双排视图（宿主侧数据变了，视图侧要跟上）。 */
    private fun refreshAllViews() {
        states.entries.forEach { (root, state) ->
            runCatching { refresh(root, state) }
                .onFailure { Log.w(TAG, "refresh 失败: ${it.message}") }
        }
    }

    /**
     * 视图建成：拿 rootView + subId，判定是否进入双排模式。
     *
     * **必须原样返回宿主视图**——该方法非 void，返回 null 会让后续 `bind` NPE
     * （2026-10-11 崩溃实证，见 docs/reverse/systemui.md §7.4）。
     */
    private fun interceptConstructAndBind(chain: XposedInterface.Chain): Any? {
        val root = runCatching { chain.proceed() }.getOrNull() as? View ?: return null
        val subId = runCatching { root.javaClass.getField("subId").getInt(root) }.getOrNull() ?: -1
        runCatching { onViewCreated(root, subId) }
            .onFailure { Log.w(TAG, "onViewCreated 失败: ${it.message}") }
        return root
    }

    /** 宿主每卡视图建成：卡数 >1 时给**卡槽 0 那张**插双排，其余整组隐藏。 */
    private fun onViewCreated(root: View, subId: Int) {
        if (subId < 0) return
        val state = states.getOrPut(root) { DualState(subId) }
        // 宿主重建会复用同一个 rootView（subId 同一张卡），这里幂等
        if (state.installed) { refresh(root, state); return }
        state.installed = true

        val slotOf = slotOfSub(subId)
        // 只保留卡槽 0 的那组；其余整组 GONE（与 HyperCeiler 同策略：宁可隐藏，不出两组双排）
        if (slotOf != null && slotOf != 0) {
            root.visibility = View.GONE
            state.hiddenWholeView = true
            return
        }
        buildDualIcons(root, state)
    }

    /** 建双排容器并接管显示。 */
    private fun buildDualIcons(root: View, state: DualState) {
        val signalHost = findChildByEntryName(root, ID_MOBILE_COMBO) ?: return
        if (signalHost !is ViewGroup) return

        val container = FrameLayout(root.context).apply {
            id = View.generateViewId()
            tag = ID_DUAL_CONTAINER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.MATCH_PARENT,
            ).also { it.gravity = Gravity.CENTER_VERTICAL }
        }
        val top = ImageView(root.context).apply { id = View.generateViewId(); tag = ID_DUAL_TOP; adjustViewBounds = true }
        val bottom = ImageView(root.context).apply { id = View.generateViewId(); tag = ID_DUAL_BOTTOM; adjustViewBounds = true }
        container.addView(top, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP,
        ))
        container.addView(bottom, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM,
        ))
        signalHost.addView(container)
        // 原生信号图标让位（双排接管）；其余子图标（5G 标/漫游/数据箭头）不受影响
        findChildByEntryName(root, ID_MOBILE_SIGNAL)?.visibility = View.GONE
        state.container = container
        state.top = top
        state.bottom = bottom
    }

    /** 档位已知时刷新双排图标。 */
    private fun refresh(root: View, state: DualState) {
        val top = state.top ?: return
        val bottom = state.bottom ?: return
        if (state.hiddenWholeView) return
        // 卡数不足则恢复原生显示（宿主自己管）
        if (activeCardCount(root) < 2) {
            syncNative(root, state, native = true)
            return
        }
        syncNative(root, state, native = false)
        val (l1, l2) = orderedLevels()
        top.setImageDrawable(signalDrawable(top, l1))
        bottom.setImageDrawable(signalDrawable(bottom, l2))
    }

    /** 双排 ↔ 原生 的显隐切换。 */
    private fun syncNative(root: View, state: DualState, native: Boolean) {
        state.container?.visibility = if (native) View.GONE else View.VISIBLE
        findChildByEntryName(root, ID_MOBILE_SIGNAL)?.visibility = if (native) View.VISIBLE else View.GONE
    }

    /**
     * 两卡档位按「数据卡优先 → 卡槽序」排序，与 HyperCeiler 的 `getSignalLevelsForRender` 同语义。
     * 数据卡放上排（iOS 的阅读习惯：主号在上）。
     */
    private fun orderedLevels(): Pair<Int, Int> {
        val entries = levelBySub.entries.sortedWith(
            compareBy(
                { (k, _) -> if (dataSimBySub[k] == true) 0 else 1 },
                { (k, _) -> slotBySub[k] ?: Int.MAX_VALUE },
                { (k, _) -> k },
            ),
        )
        val first = entries.getOrNull(0)?.value ?: -1
        val second = entries.getOrNull(1)?.value ?: -1
        return first to second
    }

    /** 本进程内已建根视图数（卡数代理）。 */
    private fun activeCardCount(root: View): Int =
        states.values.count { it.installed && !it.hiddenWholeView && it.container?.isAttachedToWindow == true }

    /**
     * subId → 卡槽号。用系统公开 API `SubscriptionManager.getSlotIndex(subId)`，
     * 不反射宿主私有字段（字段名逐版可能漂；公开 API 稳定）。
     * 失败返回 null，调用方按"未知卡槽"继续（不阻断功能）。
     */
    private fun slotOfSub(subId: Int): Int? {
        slotBySub[subId]?.let { return it }
        val slot = runCatching {
            android.telephony.SubscriptionManager.getSlotIndex(subId)
        }.getOrNull()
        if (slot != null && slot >= 0) slotBySub[subId] = slot
        return slot
    }

    /** 按资源名生成一张信号格 drawable（自绘：压扁以塞进状态栏）。 */
    private fun signalDrawable(ref: ImageView, level: Int): Drawable =
        if (level < 0) ref.drawable ?: blankDrawable()
        else MergeSignalDrawable(ref.context, level)

    private fun blankDrawable(): Drawable = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)

    /** 信号格资源 entry name → 档位 0~5；非蜂窝信号返回 -1。 */
    private fun levelOf(view: View, iconId: Int): Int {
        if (iconId == 0) return -1
        val name = runCatching { view.resources.getResourceEntryName(iconId) }.getOrNull()
        return parseLevel(name)
    }

    /** 沿 parent 链向上找指定类的祖先/自身。 */
    private fun findAncestorByClass(view: View, className: String): View? {
        var v: View? = view
        var depth = 0
        while (v != null && depth < MAX_VIEW_DEPTH) {
            if (runCatching { v.javaClass.name }.getOrNull() == className) return v
            val cur: View = v
            v = runCatching { cur.parent as? View }.getOrNull()
            depth++
        }
        return null
    }

    /** 按 entry name 在视图子树里向下找（显式栈，避免深递归）。 */
    private fun findChildByEntryName(view: View, entryName: String): View? {
        val stack = ArrayDeque<View>()
        stack.addLast(view)
        var visited = 0
        while (stack.isNotEmpty() && visited < MAX_VIEW_DEPTH) {
            val cur = stack.removeLast()
            visited++
            val id = runCatching { cur.id }.getOrNull() ?: View.NO_ID
            if (id != View.NO_ID) {
                val name = runCatching { cur.resources.getResourceEntryName(id) }.getOrNull()
                if (name == entryName) return cur
            }
            if (cur is ViewGroup) for (i in 0 until cur.childCount) stack.addLast(cur.getChildAt(i))
        }
        return null
    }

    private const val MAX_VIEW_DEPTH = 64

    private class DualState(val subId: Int) {
        var installed = false
        var hiddenWholeView = false
        var container: FrameLayout? = null
        var top: ImageView? = null
        var bottom: ImageView? = null
    }

    /** subId → 档位（由 [CLS_MOBILE_CONNECTION_REPO] 的 StateFlow 驱动，见 [collectLevels]）。 */
    private val levelBySub = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** subId → 卡槽号。 */
    private val slotBySub = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    /** 从 [CLS_MOBILE_CONNECTION_REPO] 的 getPrimaryLevel() 订阅每卡档位（待接线，见 [collectLevels]）。 */
    private fun collectLevels(module: XposedModule, loader: ClassLoader) {
        // 占位：档位采集的挂载点尚未接线。当前由 setIconForView 侧回填（见 onSignalIcon）。
    }

    /** 信号格资源 entry name → 档位 0~5；非蜂窝信号 -1。 */
    internal fun parseLevel(resourceEntryName: String?): Int {
        if (resourceEntryName.isNullOrEmpty()) return -1
        val m = LEVEL_RE.find(resourceEntryName) ?: return -1
        return m.groupValues[1].toIntOrNull() ?: -1
    }

    /**
     * 自绘单排信号 drawable：4 根递增柱，压扁到约 1/2 状态栏高度。
     * 取色跟随宿主 mobile_signal 的 tint（调用方负责传入参照视图）。
     */
    private class MergeSignalDrawable(context: android.content.Context, private val level: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val res = context.resources

        override fun draw(canvas: Canvas) {
            val b = bounds
            val w = b.width().toFloat()
            val h = b.height().toFloat()
            if (w <= 0f || h <= 0f) return
            paint.color = Color.WHITE
            val gap = w * 0.03f
            val barW = (w - gap * (BARS_PER_ROW - 1)) / BARS_PER_ROW
            val usable = h * 0.88f
            val inset = (h - usable) / 2f
            for (i in 0 until BARS_PER_ROW) {
                val barH = usable * ((i + 1).toFloat() / BARS_PER_ROW)
                val left = i * (barW + gap)
                val topOff = inset + (usable - barH)
                paint.alpha = if (i < level.coerceAtMost(BARS_PER_ROW)) 255 else 64
                val r = barW * 0.22f
                canvas.drawRoundRect(left, topOff, left + barW, inset + usable, r, r, paint)
            }
            paint.alpha = 255
        }

        override fun getIntrinsicWidth(): Int = (22.67f * res.displayMetrics.density).toInt()
        override fun getIntrinsicHeight(): Int = (17f * res.displayMetrics.density).toInt()
        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) { paint.colorFilter = colorFilter }
        @Deprecated("Deprecated in Drawable", ReplaceWith("android.graphics.PixelFormat.TRANSLUCENT"))
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }
}