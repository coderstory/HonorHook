package top.honorhook.xposed.target

import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountEngine
import top.honorhook.xposed.core.MountSession
import top.honorhook.xposed.data.HookPoint
import top.honorhook.xposed.data.RemoteConfig
import top.honorhook.xposed.data.toDexHint

/**
 * 荣耀智慧搜索 `com.hihonor.search`（v10.0.33.300，2026-11-18 真机拉取实证）结果页净化。
 *
 * 需求：桌面下拉搜索的综合结果页不再出现「应用市场」安装推荐卡与「搜索大全」网络内容卡（含热搜）。
 *
 * 选点依据（docs/reverse/search.md，apktool 全量 baksmali 46678 类 + MT MCP xref）：
 * - 结果页是 multitype 列表（GeneralTabFragment → hp5/jf9/if9 路由器），列表项 = GeneralModel 子类；
 *   路由器 if9 对「无策略匹配」的项返回 -1，下游 `List.get(-1)` 直接 IndexOutOfBounds——
 *   所以**不能**在 Strategy/ViewHolder 层隐藏（会崩宿主），只能在数据源头把卡片从列表里剔除。
 * - 「应用市场」卡 = `TopAppCard`/`AdCard`，「搜索大全」网络内容卡 = `Webpage`/`DisabledWebpage`
 *   （DisabledWebpage 的 bind 是空实现但 onCreateViewHolder 会 inflate 可见占位布局，借道不干净），
 *   热搜卡 = `HotSearchData`（HotSearchInfoStrategy 绑定）。类名全部未混淆，跨版本稳。
 * - 全部卡片数据经 `cp5`（R8 混淆名，SetPageData 数据包）的 `b` 字段 List 进页面状态
 *   （general/b.smali 等 6 处构造点全部走同一 6 参构造 `<init>(Query, List, LocalAiInfo,
 *   OperStrategyInfo, Boolean, int)`；参数序列全 dex 唯一，且全由未混淆类型构成 ⇒ DexKit 锚点）。
 * - **before-hook 替换 List 实参**（同 HonorQinxuanHook 运营条目置空的手法）：构造器把过滤后的
 *   新 List 存进 final 字段，无需反射改 final；列表项是完整剔除（无空壳行）。
 * - 为什么不拦 `uo5.k`（构建 TopAppCard 的静态映射器）：调用方 `uo5.c` 的 merge 点
 *   `Collection.add(v6)` 无 null 守卫，返回 null 会把 null 塞进列表（下游 NPE 风险）。
 */
object SearchHook : TargetHook {

    override val packageName = "com.hihonor.search"

    private const val JUDGE_PREFIX = "search-hook"

    /**
     * R8 单字母混淆类（根包）；10.0.33.300=cp5 → 10.0.38.300=qp5（改名，构造器签名逐字节未变）。
     * OTA 漂移维护法：反编译后 grep synthetic 6 参构造器（signature 见 dexParamTypes），
     * 改这里 + FeatureCatalog.baselineVersion 即可。
     * 已知边界：宽松轨救不了本点——数据类无字符串锚点（usingStrings/classStrings 皆无），
     * dexParamTypes 只参与引擎排序不参与检索；兜底靠严格轨（paramTypes+declaredClass 类字段类型全条件，
     * 离线探针对 38.300 真包实测唯一命中）。构造器解析依赖引擎 Executable 加宽（2026-11-18），
     * 修前行为：直连 declaredMethods 查不到 <init>、DexKit 命中被 getMethodInstance 抛异常丢光 → 永远 0/1。
     */
    private const val CLS_PAGE_DATA = "qp5"

    /** 要剔除的卡片模型类名后缀（resultpage/domain/model 下未混淆类；后缀匹配不依赖包路径）。 */
    private val HIDDEN_CARD_SUFFIXES = listOf(
        "TopAppCard",      // 「应用市场」安装推荐卡（标题 string/app_market_name）
        "AdCard",          // 「应用市场」广告卡（AdCardStrategy，同一 0x7f130135 标题）
        "Webpage",         // 「搜索大全」网络内容卡（footer「以下内容由"%s"提供」）
        "DisabledWebpage", // WebView 不可用时的降级卡（bind 空实现 + 可见占位布局，一并剔除）
        "HotSearchData",   // 热搜卡（HotSearchInfoStrategy 绑定）
    )

    private fun isHiddenCard(className: String): Boolean =
        HIDDEN_CARD_SUFFIXES.any { className.endsWith(it) }

    internal val PAGE_DATA_POINTS: List<HookPoint> = listOf(
        HookPoint(
            id = "search:page.SetPageData.filter", kind = "FILTER",
            feature = FeatureCatalog.KEY_SEARCH_PURIFY,
            className = CLS_PAGE_DATA, methodName = "<init>",
            paramCount = 10, returnType = "void", value = null,
            description = "综合结果页数据包构造（SetPageData 载体）：剔除应用市场/搜索大全卡片",
            // 10 参**主**构造器 = 总漏斗：synthetic 6 参构造器（默认参数糖）内部委托它（qp5.smali:73
            // invoke-direct/range 10 参）、static a() 复制工厂也经它建新实例；只挂 6 参时实测用户搜索
            // 路径不经过（2026-11-18 诊断版 installed 1/1 但构造器零调用），故改挂 10 参。
            // 参数序列全 dex 唯一且全为未混淆类型 ⇒ DexKit 锚点。
            dexParamTypes = listOf(
                "com.hihonor.search.feature.resultpage.data.entity.Query",
                "java.util.List",
                "java.util.List",
                "java.lang.String",
                "com.hihonor.search.feature.resultpage.data.entity.LocalAiInfo",
                "com.hihonor.search.feature.resultpage.data.entity.IntentInfo",
                "com.hihonor.search.feature.resultpage.data.entity.OperStrategyInfo",
                "java.util.List",
                "java.lang.Boolean",
                "java.lang.String",
            ),
            // 类字段类型序列（cp5 声明的 10 个字段，未混淆类型；类维度二次确认）
            dexClassFieldTypes = listOf(
                "com.hihonor.search.feature.resultpage.data.entity.Query",
                "java.util.List",
                "java.lang.String",
                "com.hihonor.search.feature.resultpage.data.entity.LocalAiInfo",
                "com.hihonor.search.feature.resultpage.data.entity.IntentInfo",
                "com.hihonor.search.feature.resultpage.data.entity.OperStrategyInfo",
                "java.lang.Boolean",
            ),
        ),
    )

    internal fun HookPoint.toTask(): MountEngine.SimpleTask = MountEngine.SimpleTask(
        id = id,
        className = className,
        methodName = methodName,
        paramCount = paramCount,
        returnType = returnType,
        description = description,
        group = group,
        feature = feature,
        dex = toDexHint(),
    )

    override fun install(module: XposedModule, param: XposedModuleInterface.PackageLoadedParam) {
        val loader = param.defaultClassLoader
        val apkPath = param.applicationInfo.sourceDir

        val pending = mutableListOf<MountEngine.SimpleTask>()
        if (RemoteConfig.isEnabled(FeatureCatalog.KEY_SEARCH_PURIFY)) {
            PAGE_DATA_POINTS.forEach { point -> pending += point.toTask() }
        } else {
            logDisabled(module, FeatureCatalog.KEY_SEARCH_PURIFY, "SearchHook")
        }
        if (pending.isEmpty()) return

        val session = MountEngine.open("SearchHook", packageName, apkPath, FeatureCatalog.baselineVersionOf(packageName), RemoteConfig)
        session.attemptMount(pending, module, loader) { task -> mountOne(session, module, loader, task) }
    }

    /**
     * 统一拦截：before 语义——把 List 型实参里的推荐卡剔除后 proceedWith 原方法。
     * 过滤失败（args 取不到 / 非 List）原样放行保底；异常由 PROTECTIVE 兜底回退原调用。
     */
    private fun mountOne(
        session: MountSession,
        module: XposedModule,
        loader: ClassLoader,
        task: MountEngine.SimpleTask,
    ): MountEngine.Result =
        session.mount(task, module, loader) { chain ->
            val args = chain.args.toTypedArray()
            var removed = 0
            for (i in args.indices) {
                val arg = args[i] as? List<*> ?: continue
                if (arg.isEmpty()) continue
                val kept = arg.filter { item -> item == null || !isHiddenCard(item.javaClass.name) }
                if (kept.size != arg.size) {
                    removed += arg.size - kept.size
                    args[i] = kept
                }
            }
            if (removed > 0) {
                session.judge(module, task, JUDGE_PREFIX, "已剔除推荐卡 $removed 条")
            }
            chain.proceedWith(chain.thisObject, args)
        }
}
