package top.honorhook.xposed.target.appmarket

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.target.TargetHook
import top.honorhook.xposed.target.logDisabled
import io.github.libxposed.api.XposedInterface
import top.honorhook.xposed.target.appmarket.TamerUtil.callMethod
import top.honorhook.xposed.target.appmarket.TamerUtil.readField
import java.util.concurrent.atomic.AtomicInteger

/**
 * 底部页签裁剪（tamer 1.4.11 TabStripper 移植，2026-11-19）。
 *
 * 锚 = `main.frame.fragments.MainFrameFragmentTemp$a`（未混淆，跨版本稳）：
 * - d(≥6)：args[1] 页签 List 与 args[2]/args[3]/args[4](int[])/args[5] 四个平行列表同步裁剪；
 * - g(≥2)：args[1] List 直接删；
 * - i(1)：args[0] Integer ≥ 裁剪后数量时改默认页签下标。
 * 规则（TabRules）：game=pageType 6 或 name "游戏"；apps=7 或 "应用"；qiangxian=name 含 "抢鲜"。
 * 命中至少一条规则才挂载（全关时零任务）。
 */
object TamerTabStripper : TargetHook {

    override val packageName = "com.hihonor.appmarket"

    private const val ANCHOR = "com.hihonor.appmarket.main.frame.fragments.MainFrameFragmentTemp\$a"

    /** 裁剪后的存活页签数（i() 用它改默认下标）。 */
    private val keptCount = AtomicInteger(-1)

    private fun ruleOn(key: String) = RemoteConfig.isEnabled(key)

    /** 页签命中规则即删（规则读远端开关，每次现取——本地 prefs 读廉价，且 BO getter 是高频口）。 */
    private fun isBlockedPage(page: Any?): Boolean = runCatching {
        val blkGame = ruleOn(FeatureCatalog.KEY_MARKET_PURIFY_TAB_GAME)
        val blkApps = ruleOn(FeatureCatalog.KEY_MARKET_PURIFY_TAB_APPS)
        val blkQiangxian = ruleOn(FeatureCatalog.KEY_MARKET_PURIFY_TAB_QIANGXIAN)
        val type = (callMethod(page, "getPageType") ?: readField(page, "pageType")) as? Int
        val name = (callMethod(page, "getPageName") ?: readField(page, "pageName"))?.toString()
        // T0 路的元素是 BottomNavItemVO（jadx 实证：yu1.l(List<BottomNavItemVO>)，无 pageName，
        // 只有 text/pageType/index/showBig）；22:53 会话实证 VO 上 methods 里查不到 getText
        // （R8 改写），必须字段直读 text——text 即页签标题
        val text = (callMethod(page, "getText") ?: readField(page, "text"))?.toString()
        (blkGame && (type == 6 || name == "游戏" || text == "游戏")) ||
            (blkApps && (type == 7 || name == "应用" || text == "应用")) ||
            (blkQiangxian && (name?.contains("抢鲜") == true || text?.contains("抢鲜") == true))
    }.getOrDefault(false)

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val blkGame = ruleOn(FeatureCatalog.KEY_MARKET_PURIFY_TAB_GAME)
        val blkApps = ruleOn(FeatureCatalog.KEY_MARKET_PURIFY_TAB_APPS)
        val blkQiangxian = ruleOn(FeatureCatalog.KEY_MARKET_PURIFY_TAB_QIANGXIAN)
        if (!blkGame && !blkApps && !blkQiangxian) {
            logDisabled(module, FeatureCatalog.KEY_MARKET_PURIFY_TAB_GAME, "TamerTabStripper")
            return
        }

        // 2026-11-19 页签收敛复盘（docs/retro-2026-11-19-tamer-tab.md）：16.1.11 的页签渲染有
        // 两条独立链——①$a.d 四平行列表装配路 ②T0 LiveData 路，都终结于逐项添加 sink（A/K）。
        // pending 表曾挂 d/i/vmB/vmF/j 五点：挂载报 OK 但回调零执行（MountEngine 对 $a 类方法的
        // 派发坑，tab.d/tab.t0 双实证），且 BO getter 证实 vm 路不在渲染链上。收敛后只保留：
        // g（tamer 原点，仍会触发，顺带绑定 BO）、直挂 t0direct/ddirect（数据层）、sinkA/sinkK
        // （渲染出口兜底，内容锚点按标题拦，跨版本最稳）。
        val pending = listOf(
            MountEngine.SimpleTask(
                id = "market-tamer:tab.g", className = ANCHOR, methodName = "g", paramCount = 2,
                returnType = "void", description = "底部页签 List 直接删（MainFrameFragmentTemp\$a#g）",
            ),
        )
        val session = MountEngine.open(
            "TamerTabStripper", packageName, param.applicationInfo.sourceDir,
            FeatureCatalog.baselineVersionOf(packageName), RemoteConfig,
        )
        session.attemptMount(pending, module, param.defaultClassLoader) { t ->
            session.mount(t, module, param.defaultClassLoader) { chain ->
                runCatching {
                    when (t.id) {
                        "market-tamer:tab.g" -> {
                            val args = chain.args?.toTypedArray() ?: return@runCatching chain.proceed()
                            // tamer bindFrameBoHooks：页签 UI 读的是 frameData BO（ui4）的 List getter，
                            // 只删 g 的入参 List 不影响渲染 —— 首次进入时按 BO 类动态找 getter/setter 补挂
                            args.getOrNull(0)?.let { bindFrameBo(module, it.javaClass) }
                            val pages = args.getOrNull(1) as? MutableList<Any?> ?: return@runCatching chain.proceed()
                            val it = pages.iterator()
                            var removed = 0
                            while (it.hasNext()) {
                                val p = it.next()
                                if (isBlockedPage(p)) {
                                    it.remove()
                                    removed++
                                }
                            }
                            if (removed > 0) keptCount.set(pages.size)
                            if (removed > 0) session.judge(module, t, "market-tamer", "菜单删 $removed 项")
                            chain.proceed()
                        }
                    }
                }.getOrElse {
                    module.log(android.util.Log.WARN, MountEngine.TAG, "market-tamer tab 裁剪异常（已吞，放行原方法）: ${it.message}")
                    chain.proceed()
                }
            }
        }
    }

    /** BO 类的 List getter/setter 名（一次绑定，留审计）。 */
    @Volatile private var boGetter: String? = null
    @Volatile private var boSetter: String? = null
    @Volatile private var boBound = false
    private val boLock = Any()

    /**
     * tamer TabStripper.bindFrameBoHooks 移植（2026-11-19，g 分支补抄漏项）：
     * 页签数据真正的读写口是 frameData BO（ui4）的无参 List getter 与单 List 参 setter。
     * 16.1.11.302 上 g 只删了装配入参，UI 仍从 BO getter 读到原始 5 页签 ⇒「删了但还显示」。
     * 这里按 tamer 的动态签名法（0 参返 List = getter；1 参收 List = setter）用原始 module.hook 直挂
     * （绕过 MountEngine 统一闸门：类/方法名运行期才能确定，无法进 pending 表）。
     */
    private fun bindFrameBo(module: XposedModule, boClass: Class<*>) {
        if (boBound) return
        synchronized(boLock) {
            if (boBound) return
            runCatching { TamerTabStripper.installNavProbesOnce(module, boClass.classLoader) }
            val getter = boClass.declaredMethods.firstOrNull {
                it.parameterTypes.isEmpty() && kotlin.runCatching { List::class.java.isAssignableFrom(it.returnType) }.getOrDefault(false)
            }
            val setter = boClass.declaredMethods.firstOrNull {
                it.parameterTypes.size == 1 && kotlin.runCatching { List::class.java.isAssignableFrom(it.parameterTypes[0]) }.getOrDefault(false)
            }
            val getterName = getter?.name
            val setterName = setter?.name
            module.log(android.util.Log.INFO, MountEngine.TAG,
                "market-tamer frame-bo=${boClass.name} read=$getterName write=$setterName")
            runCatching {
                setter?.let { m ->
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("market-tamer:tab.bo.W")
                        .intercept { chain ->
                            runCatching {
                                val list = chain.args?.getOrNull(0) as? List<*>
                                    ?: return@intercept chain.proceed()
                                val filtered = filterPages(list)
                                if (filtered != null) {
                                    keptCount.set(filtered.size)
                                    module.log(android.util.Log.INFO, MountEngine.TAG,
                                        "market-tamer tab BO setter 过滤 ${list.size}→${filtered.size}")
                                    chain.proceedWith(chain.thisObject, arrayOf(filtered))
                                } else chain.proceed()
                            }.getOrElse { chain.proceed() }
                        }
                }
                getter?.let { m ->
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("market-tamer:tab.bo.R")
                        .intercept { chain ->
                            runCatching {
                                val result = chain.proceed()
                                val list = result as? List<*>
                                    ?: return@intercept result
                                val filtered = filterPages(list)
                                if (filtered != null) {
                                    keptCount.set(filtered.size)
                                    module.log(android.util.Log.INFO, MountEngine.TAG,
                                        "market-tamer tab BO getter 过滤 ${list.size}→${filtered.size}")
                                    filtered
                                } else result
                            }.getOrElse { it.printStackTrace(); null }
                        }
                }
            }.onFailure {
                module.log(android.util.Log.WARN, MountEngine.TAG, "market-tamer frame-bo 绑定失败（已吞）: ${it.message}")
            }
            boBound = true
        }
    }


    private var navProbeBound = false
    private val navProbeLock = Any()

    // T0 直挂：可见页签条数据源 T0→yu1.l/n→AgHwBottomNav.K（15:24 agkprobe 栈实证），
    // 运行期才知道方法所以绕开 MountEngine pending 表直挂；必须在 Activity#onCreate 期注册
    // （LiveData 首帧早于 g 回调）。MountEngine 表里的 tab.t0 留作二次过滤备份。
    fun installNavProbesOnce(module: XposedModule, loader: ClassLoader) {
        if (navProbeBound) return
        synchronized(navProbeLock) {
            if (navProbeBound) return
            val boLoader = loader
            // T0 直挂（绕开 MountEngine 派发——pending 表那条 t0 挂上却从不进分支，59a8 会话实证
            // K 仍收到 5 项且无任何 t0 日志；这里独立 hook + 全量诊断日志一次到位）
            runCatching {
                loader.loadClass("com.hihonor.appmarket.main.frame.fragments.MainFrameFragmentTemp")
                    .declaredMethods.filter { it.name == "T0" && it.parameterTypes.size == 2 }
                    .forEach { m ->
                        module.hook(m)
                            .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                            .setId("market-tamer:tab.t0direct")
                            .intercept { chain ->
                                val args = chain.args?.toTypedArray()
                                runCatching {
                                    val pages = args?.getOrNull(1) as? List<Any?>
                                    if (pages != null) {
                                        val kept = pages.filter { !isBlockedPage(it) }
                                        val removed = pages.size - kept.size
                                        if (removed > 0) {
                                            keptCount.set(kept.size)
                                            args?.set(1, kept)
                                            module.log(android.util.Log.INFO, MountEngine.TAG,
                                                "market-tamer t0direct 删 $removed 项（余 ${kept.size}）")
                                        }
                                    }
                                }
                                chain.proceed(args ?: emptyArray())
                            }
                    }
            }.onFailure {
                module.log(android.util.Log.INFO, MountEngine.TAG,
                    "market-tamer navprobe 注册失败: ${it.message}")
            }
            // 兜底收口：不管哪条链装配，最终逐项添加只有两个 sink——
            // HwBottomNavigationView.A(Drawable,String)（$a.d→E1 路）和
            // AgHwBottomNavigationView.K(String,Drawable,IZ)（T0→yu1.l/n 路，23:20 栈实证）。
            // 按标题直接拦，任何漏网的装配路径都到不了 UI
            fun titleBlocked(title: String?): Boolean = title != null && opaqueName(title)
            runCatching {
                val navCls2 = loader.loadClass(
                    "com.hihonor.uikit.hwbottomnavigationview.widget.HwBottomNavigationView")
                navCls2.declaredMethods.filter {
                    it.name == "A" && it.parameterTypes.size == 2 &&
                        it.parameterTypes[0] == android.graphics.drawable.Drawable::class.java
                }.forEach { m ->
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("market-tamer:tab.sinkA")
                        .intercept { chain ->
                            val title = chain.args?.getOrNull(1) as? String
                            if (titleBlocked(title)) {
                                module.log(android.util.Log.INFO, MountEngine.TAG,
                                    "market-tamer sinkA 拦截页签: $title")
                                return@intercept null
                            }
                            chain.proceed()
                        }
                }
                runCatching {
                    loader.loadClass("com.hihonor.appmarket.widgets.AgHwBottomNavigationView")
                }.getOrNull()?.declaredMethods?.filter {
                    it.name == "K" && it.parameterTypes.size == 4 &&
                        it.parameterTypes[0] == String::class.java
                }?.forEach { m ->
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("market-tamer:tab.sinkK")
                        .intercept { chain ->
                            val title = chain.args?.getOrNull(0) as? String
                            if (titleBlocked(title)) {
                                module.log(android.util.Log.INFO, MountEngine.TAG,
                                    "market-tamer sinkK 拦截页签: $title")
                                return@intercept null
                            }
                            chain.proceed()
                        }
                }
            }.onFailure {
                module.log(android.util.Log.WARN, MountEngine.TAG, "market-tamer sink reg fail: ${it.message}")
            }
            // 市场进程冷启动会把 Settings.System 的 accelerometer_rotation 写成 1
            // （23:22 实测：值 0 → 冷启动 → 12s 后变 1），全局自动旋转被它打开——
            // 拦掉该键的非 0 写入（写 0 放行）
            runCatching {
                val sysCls = loader.loadClass("android.provider.Settings\$System")
                val rotKey = sysCls.getDeclaredField("ACCELEROMETER_ROTATION").get(null).toString()
                sysCls.declaredMethods.filter {
                    (it.name == "putInt" || it.name == "putIntForUser" || it.name == "putString" ||
                        it.name == "putStringForUser") && it.parameterTypes.size >= 3 &&
                        it.parameterTypes[1] == String::class.java
                }.forEach { m ->
                    module.hook(m)
                        .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                        .setId("market-tamer:rotblock")
                        .intercept { chain ->
                            val key = chain.args?.getOrNull(1)?.toString()
                            if (key == rotKey && chain.args != null && chain.args.size >= 3 &&
                                chain.args[2]?.toString() != "0"
                            ) {
                                runCatching {
                                    module.log(android.util.Log.INFO, MountEngine.TAG,
                                        "market-tamer rotblock 拦截自动旋转写入 (${m.name})")
                                }
                                return@intercept null // void 方法
                            }
                            chain.proceed()
                        }
                }
            }.onFailure {
                module.log(android.util.Log.WARN, MountEngine.TAG,
                    "market-tamer rotblock 注册失败: ${it.message}")
            }
            navProbeBound = true
        }
    }

    /** 过滤页签列表；无需删或删空时返 null（放行原值，防宿主假定至少一页）。 */
    private fun filterPages(list: List<*>): MutableList<Any?>? {
        if (list.isEmpty()) return null
        val out = ArrayList<Any?>(list.size)
        var removed = 0
        for (p in list) if (!isBlockedPage(p)) out.add(p) else removed++
        return if (removed > 0 && out.isNotEmpty()) out else null
    }

    /** 元素 toString（标题串等非 BO 元素）按页签名匹配——d 路平行列表装的是标题/Fragment */
    private fun opaqueName(s: String?): Boolean {
        if (s == null) return false
        val blkG = RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_TAB_GAME)
        val blkA = RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_TAB_APPS)
        val blkQ = RemoteConfig.isEnabled(FeatureCatalog.KEY_MARKET_PURIFY_TAB_QIANGXIAN)
        return (blkG && s.contains("游戏")) || (blkA && s.contains("应用")) ||
            (blkQ && s.contains("抢鲜"))
    }
}
