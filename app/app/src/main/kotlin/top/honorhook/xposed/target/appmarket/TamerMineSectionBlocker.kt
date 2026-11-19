package top.honorhook.xposed.target.appmarket

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import top.honorhook.xposed.target.appmarket.TamerUtil.callMethod
import top.honorhook.xposed.target.appmarket.TamerUtil.readField

/**
 * 「我的」页区块隐藏（tamer 1.4.11 MineSectionBlocker 移植，2026-11-19）。
 *
 * 两路：
 * - adapter 路（clean/safety/services，默认关）：MarketManageTopAdapter onBind/e0 后按
 *   itemViewType（2007 核心工具卡 / 2004 通用服务卡）按资源 id 名 hide；
 * - sweep 路（signin/slide/mine_feed 加载占位）：MarketManageFragment onResume/onHiddenChanged/
 *   onViewCreated 后 postDelayed 600/1500/3000/5000 扫 TextView 文本，向上找 RecyclerView
 *   父级前一层 hide（找 RecyclerView 失败退回直接父级）。
 * 资源 id 经 getIdentifier 动态解析并缓存（R 类被宿主混淆，id 名跨版本稳定）。
 */
object TamerMineSectionBlocker : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"
    private const val ADAPTER_CLS = "$PKG.mine.adapter.MarketManageTopAdapter"
    private const val FRAGMENT_CLS = "$PKG.mine.MarketManageFragment"
    private const val VT_COMMON_SERVICE = 2004
    private const val VT_CORE_TOOLS = 2007
    private val SWEEP_DELAYS = longArrayOf(600, 1500, 3000, 5000)
    private val idCache = HashMap<String, Int?>()

    private fun on(key: String) = RemoteConfig.isEnabled(key)

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val clean = on(FeatureCatalog.KEY_MARKET_PURIFY_MINE_CLEAN)
        val safety = on(FeatureCatalog.KEY_MARKET_PURIFY_MINE_SAFETY)
        val services = on(FeatureCatalog.KEY_MARKET_PURIFY_MINE_SERVICES)
        val signin = on(FeatureCatalog.KEY_MARKET_PURIFY_MINE_SIGNIN)
        val slide = on(FeatureCatalog.KEY_MARKET_PURIFY_MINE_SLIDE)
        val feedLoading = on(FeatureCatalog.KEY_MARKET_PURIFY_MINE_FEED)
        if (!clean && !safety && !services && !signin && !slide && !feedLoading) {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_MINE_SIGNIN, "TamerMineSectionBlocker")
            return
        }

        val pending = mutableListOf<MountEngine.SimpleTask>()
        if (clean || safety || services) {
            pending += listOf(
                MountEngine.SimpleTask(
                    id = "market-tamer:mine.bind", className = ADAPTER_CLS, methodName = "onBindViewHolder",
                    paramCount = 2, returnType = "void", description = "「我的」顶卡渲染后按 id 隐藏（清理/安检/服务）",
                ),
            )
        }
        if (signin || slide || feedLoading) {
            // tamer 三入口都挂：onResume / onHiddenChanged(bool) / onViewCreated(V,Bundle)——任意一条可见路径都会触发 sweep
            pending += listOf(
                MountEngine.SimpleTask(
                    id = "market-tamer:mine.sweep", className = FRAGMENT_CLS, methodName = "onResume",
                    paramCount = 0, returnType = "void", description = "「我的」页可见后延时扫文本隐藏（签到/滑动引导/加载占位）",
                ),
                MountEngine.SimpleTask(
                    id = "market-tamer:mine.sweepHidden", className = FRAGMENT_CLS, methodName = "onHiddenChanged",
                    paramCount = 1, returnType = "void", description = "「我的」页 tab 切回可见后 sweep",
                ),
                MountEngine.SimpleTask(
                    id = "market-tamer:mine.sweepCreated", className = FRAGMENT_CLS, methodName = "onViewCreated",
                    paramCount = 2, returnType = "void", description = "「我的」页首次创建后 sweep",
                ),
            )
        }
        if (pending.isEmpty()) return

        val session = MountEngine.open(
            "TamerMineSectionBlocker", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pending, module, param.defaultClassLoader) { t ->
            session.mount(t, module, param.defaultClassLoader) { chain ->
                runCatching {
                    if (t.id == "market-tamer:mine.bind") {
                        chain.proceed()
                        handleBind(chain.args, clean, safety, services)
                    } else {
                        // sweep 三入口共用同一套延时逻辑（onHiddenChanged 只在 hidden=false 即变可见时扫）
                        val visible = t.id != "market-tamer:mine.sweepHidden" || chain.args.getOrNull(0) == false
                        chain.proceed()
                        if (visible) {
                            val frag = chain.thisObject
                            val handler = Handler(Looper.getMainLooper())
                            for (d in SWEEP_DELAYS) {
                                handler.postDelayed({
                                    runCatching { sweep(frag, signin, slide, feedLoading) }
                                }, d)
                            }
                        }
                    }
                }.getOrElse {
                    module.log(android.util.Log.WARN, MountEngine.TAG, "market-tamer mine 异常（已吞）: ${it.message}")
                    chain.proceed()
                }
                null
            }
        }
    }

    // —— adapter 路 ——

    private fun handleBind(args: List<Any?>?, clean: Boolean, safety: Boolean, services: Boolean) {
        val holder = args?.getOrNull(0) ?: return
        val view = readField(holder, "itemView") as? View ?: return
        when ((callMethod(holder, "getItemViewType") as? Int)) {
            VT_CORE_TOOLS -> {
                if (clean) listOf("clean_up_view", "clean_up_icon", "clean_up_title", "clean_up_subtitle")
                    .forEach { hideById(view, it) }
                if (safety) listOf(
                    "safety_check_view", "safety_check_title", "safety_check_subtitle",
                    "scan_finish_layout", "scan_finish_sub_layout", "process_view_v2",
                    "warning_tips_imageview", "default_status",
                ).forEach { hideById(view, it) }
            }
            VT_COMMON_SERVICE -> if (services) hideOnce(view)
        }
    }

    private fun hideById(view: View, name: String) {
        val id = idCache.getOrPut(name) {
            view.resources.getIdentifier(name, "id", PKG).takeIf { it != 0 }
        } ?: return
        (view.findViewById<View>(id))?.takeIf { it.visibility != View.GONE }?.visibility = View.GONE
    }

    private fun hideOnce(view: View) {
        if (view.visibility != View.GONE) view.visibility = View.GONE
    }

    // —— sweep 路 ——

    private fun sweep(frag: Any?, signin: Boolean, slide: Boolean, feedLoading: Boolean) {
        val root = callMethod(frag, "getView") as? View ?: return
        val texts = ArrayList<TextView>()
        collectTexts(root, texts)
        for (tv in texts) {
            val s = tv.text?.toString() ?: continue
            val hit = (signin && s.contains("签到")) ||
                (slide && (s.contains("滑一滑") || s.contains("发现更多精彩"))) ||
                (feedLoading && (s.contains("正在加载") || s == "加载中…" || s == "加载中"))
            if (hit) hideCardOf(tv)
        }
    }

    private fun collectTexts(view: View, out: MutableList<TextView>) {
        if (view is TextView) {
            out.add(view)
        } else if (view is ViewGroup) {
            for (i in 0 until view.childCount) view.getChildAt(i)?.let { collectTexts(it, out) }
        }
    }

    /** 向上找 RecyclerView 父级，取其直接子层 hide；找不到退回直接父级。 */
    private fun hideCardOf(tv: TextView) {
        var cur: View = tv
        var target: View? = null
        repeat(30) {
            val parent = cur.parent as? View ?: return
            if (parent.javaClass.name.contains("RecyclerView")) {
                target = cur
                return
            }
            cur = parent
        }
        val v = target ?: (tv.parent as? View) ?: tv
        if (v.visibility != View.GONE) v.visibility = View.GONE
    }
}
