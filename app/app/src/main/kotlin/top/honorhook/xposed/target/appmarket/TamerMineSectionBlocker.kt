package top.honorhook.xposed.target.appmarket

import android.view.View
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import top.honorhook.xposed.target.appmarket.TamerUtil.readField

/**
 * 「我的」页区块隐藏（tamer MineSectionBlocker 移植；2026-11-20 按 16.1.11.302 重适配）。
 *
 * 16.1.11 改版事实（scratch/tamer/mine-1611-report.md）：
 * - Fragment/Adapter 类未变（MarketManageFragment / MarketManageTopAdapter），变的是装配模型：
 *   卡片在 adapter 构造器里按 isMineNewLayout(y0) 装配，viewType 2003=应用更新(no6)、
 *   2004=常用服务八宫格+骨架转圈(qo6)、2005=设置帮助(dq6)、2007=核心工具卡(so6，内含安全检测 cq6)、
 *   2009/2010=新版应用管理双卡(ho6，含 应用更新+安全检测+清理)。
 * - 签到按钮 = com.hihonor.appmarket.widgets.SignInActivityButton（未混淆），配置下发
 *   b(MyPageActivityLoginConfigVO) / c()。旧扫文本法失效：文案是资源引用，smali 无 const。
 *
 * 挂载点（均为未混淆类，MountEngine 派发正常）：
 * - adapter.<init>(Fragment,Fragment,Z,Z,wl0) after：按开关从列表字段 f 剔除模型
 *   （类简单名匹配：qo6=服务+骨架转圈、so6=核心工具卡）。云控到达 p0(Lqo6;)V 后同法补剔。
 * - SignInActivityButton#b(1参)/#c(0参) after：签到开关开 → 按钮本体 GONE。
 * - sweep 三入口（旧逻辑保留）：扫「滑一滑」等引导文本（新版可能不存在，容忍空转）。
 */
object TamerMineSectionBlocker : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val PKG = "com.hihonor.appmarket"
    private const val ADAPTER_CLS = "$PKG.mine.adapter.MarketManageTopAdapter"
    private const val FRAGMENT_CLS = "$PKG.mine.MarketManageFragment"
    private const val SIGNIN_CLS = "$PKG.widgets.SignInActivityButton"
    private val SWEEP_DELAYS = longArrayOf(600, 1500, 3000, 5000)

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

        // 服务八宫格卡与骨架转圈同体（qo6/2004）：services 或 feedLoading 任一开即剔除
        val dropServices = services || feedLoading
        // 核心工具卡（so6/2007）：旧版含清理加速+安全检测，两开关任一开即剔除
        val dropCore = clean || safety

        val session = MountEngine.open(
            "TamerMineSectionBlocker", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        val loader = param.defaultClassLoader
        val pending = mutableListOf<MountEngine.SimpleTask>()

        if (dropServices || dropCore) {
            pending += listOf(
                MountEngine.SimpleTask(
                    id = "market-tamer:mine.init", className = ADAPTER_CLS, methodName = "<init>",
                    paramCount = 5, returnType = "void",
                    description = "「我的」卡列表装配后按开关剔除（服务八宫格/核心工具卡）",
                ),
                MountEngine.SimpleTask(
                    id = "market-tamer:mine.cloud", className = ADAPTER_CLS, methodName = "p0",
                    paramCount = 1, returnType = "void",
                    description = "云控常用服务到达后补剔（服务八宫格）",
                ),
            )
        }
        if (signin) {
            pending += listOf(
                MountEngine.SimpleTask(
                    id = "market-tamer:mine.signinCfg", className = SIGNIN_CLS, methodName = "b",
                    paramCount = 1, returnType = "void", description = "签到按钮配置下发后隐藏",
                ),
                MountEngine.SimpleTask(
                    id = "market-tamer:mine.signinSelf", className = SIGNIN_CLS, methodName = "c",
                    paramCount = 0, returnType = "void", description = "签到按钮自渲染后隐藏",
                ),
            )
        }
        if (slide || signin || feedLoading) {
            // 文本兜底 sweep（滑动引导/签到残余/「正在加载」占位）；新版多走按钮路，容忍空转
            pending += listOf(
                MountEngine.SimpleTask(
                    id = "market-tamer:mine.sweep", className = FRAGMENT_CLS, methodName = "onResume",
                    paramCount = 0, returnType = "void", description = "「我的」页可见后延时扫文本隐藏",
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

        session.attemptMount(pending, module, loader) { t ->
            session.mount(t, module, loader) { chain ->
                runCatching {
                    when (t.id) {
                        "market-tamer:mine.init", "market-tamer:mine.cloud" -> {
                            chain.proceed()
                            val adapter = chain.thisObject
                            val list = readField(adapter, "f") as? MutableList<Any?>
                            if (list != null) {
                                val before = list.size
                                list.removeAll { item ->
                                    val n = item?.javaClass?.simpleName
                                    (dropServices && n == "qo6") || (dropCore && n == "so6")
                                }
                                if (list.size != before) {
                                    module.log(
                                        android.util.Log.INFO, MountEngine.TAG,
                                        "market-tamer mine 卡剔除 ${before}→${list.size}（${t.id}）",
                                    )
                                }
                            }
                        }
                        "market-tamer:mine.signinCfg", "market-tamer:mine.signinSelf" -> {
                            chain.proceed()
                            (chain.thisObject as? View)?.takeIf { it.visibility != View.GONE }?.visibility = View.GONE
                        }
                        else -> {
                            // sweep 三入口共用延时逻辑（onHiddenChanged 仅 hidden=false 即变可见时扫）
                            val visible = t.id != "market-tamer:mine.sweepHidden" || chain.args.getOrNull(0) == false
                            chain.proceed()
                            if (visible) {
                                val frag = chain.thisObject
                                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                for (d in SWEEP_DELAYS) {
                                    handler.postDelayed({
                                        runCatching { sweep(frag, signin, slide, feedLoading) }
                                    }, d)
                                }
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

    // —— 文本兜底 sweep（滑动引导/签到残余） ——

    private fun sweep(frag: Any?, signin: Boolean, slide: Boolean, feedLoading: Boolean) {
        val root = TamerUtil.callMethod(frag, "getView") as? View ?: return
        val texts = ArrayList<android.widget.TextView>()
        collectTexts(root, texts)
        for (tv in texts) {
            val s = tv.text?.toString() ?: continue
            val hit = (signin && s.contains("签到")) ||
                (slide && (s.contains("滑一滑") || s.contains("发现更多精彩"))) ||
                (feedLoading && (s.contains("正在加载") || s == "加载中…" || s == "加载中"))
            if (hit) hideCardOf(tv)
        }
    }

    private fun collectTexts(view: View, out: MutableList<android.widget.TextView>) {
        if (view is android.widget.TextView) {
            out.add(view)
        } else if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) view.getChildAt(i)?.let { collectTexts(it, out) }
        }
    }

    /** 向上找 RecyclerView 父级，取其直接子层 hide；找不到退回直接父级。 */
    private fun hideCardOf(tv: android.widget.TextView) {
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
