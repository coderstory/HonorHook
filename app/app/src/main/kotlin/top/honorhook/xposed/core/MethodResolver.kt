package top.honorhook.xposed.core

import android.util.Log
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

/** 双轨目标解析共享入口（从 HookEntry.resolveTargetMethod 提取为公开基建，供各 TargetHook 接线）。 */
object MethodResolver {

    private const val TAG = "HonorHook"

    /**
     * DexKit 兜底命中缓存：**按 ClassLoader 分桶**。
     *
     * 分桶的理由：同一次特征查询在不同 ClassLoader 下可能命中不同类，混在一个平表里会串味。
     * 弱键只是让「裸 loader」可被回收；命中桶的 value 持有 Method ⇒ Class ⇒ ClassLoader 强引用链，
     * 键位实际回收不掉——即命中缓存在进程内是终生的（同 APK 进程内可接受；宿主 APK 不会中途更换）。
     * miss 桶 / 宽松命中桶的 value 是 String，不存在该问题（评审 NIT：注释需如实）。
     */
    private val hitBuckets = java.util.WeakHashMap<ClassLoader, ConcurrentHashMap<String, Method>>()

    /** DexKit 兜底未命中 key 集合（null 结果也缓存；ConcurrentHashMap 禁 null value，未命中单记）——命中直返 null，免重复磁盘扫描。 */
    private val missBuckets = java.util.WeakHashMap<ClassLoader, MutableSet<String>>()

    private fun hitBucket(loader: ClassLoader): ConcurrentHashMap<String, Method> =
        synchronized(hitBuckets) { hitBuckets.getOrPut(loader) { ConcurrentHashMap() } }

    private fun missBucket(loader: ClassLoader): MutableSet<String> = synchronized(missBuckets) {
        missBuckets.getOrPut(loader) {
            java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
        }
    }

    /**
     * 宽松轨已采纳的方法签名 → 占用它的挂载点 id（按 ClassLoader 分桶）。
     *
     * 宽松查询按内容锚点找方法，不同挂载点可能落到同一实现上（installer 的 `g()` 无参读点在新版本
     * 已内联消失，类级锚点会把它和 `h()` 都指到 `CommonConfigManager.c(Rsp)`）。
     * 同一方法只挂第一次，后来者**合并计成功**（复审缺陷④：原先记 NOT_RESOLVED ⇒ 扣 N、
     * 进失败清单，与"该方法确实已被挂上"的事实不符）；避免同一方法被两个 CONST 拦截器重复包装。
     * 合并留痕（WARN：谁与谁合并）由 MountEngine 用 module.log 打——落 LSPosed 框架日志（判据审计的sink）。
     */
    private val looseHitBuckets = java.util.WeakHashMap<ClassLoader, MutableMap<String, String>>()

    private fun looseHitBucket(loader: ClassLoader): MutableMap<String, String> = synchronized(looseHitBuckets) {
        looseHitBuckets.getOrPut(loader) { java.util.concurrent.ConcurrentHashMap<String, String>() }
    }

    /**
     * 解析目标方法（兼容入口）：只给"可挂载的方法"，未解析 / 与另一挂载点合并都返回 null。
     *
     * 老调用方（MmsHook 插件 ClassLoader 直查）不需要区分"合并"与"未解析"，故保留本入口；
     * 需要区分的走 [resolveOutcome]（MountEngine 用：合并要**计成功**，不能当失败扣 N）。
     */
    fun resolveMethod(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int? = null,
        returnType: String? = null,
        apkPath: String? = null,
        usingStrings: List<String>? = null,
        paramTypes: List<String>? = null,
        declaredClassStrings: List<String>? = null,
        declaredClassFieldTypes: List<String>? = null,
        pointId: String? = null,
        instanceOnly: Boolean = false,
    ): Method? = when (
        val outcome = resolveOutcome(
            loader = loader,
            className = className,
            methodName = methodName,
            paramCount = paramCount,
            returnType = returnType,
            apkPath = apkPath,
            usingStrings = usingStrings,
            paramTypes = paramTypes,
            declaredClassStrings = declaredClassStrings,
            declaredClassFieldTypes = declaredClassFieldTypes,
            pointId = pointId,
            instanceOnly = instanceOnly,
        )
    ) {
        is ResolveOutcome.Resolved -> outcome.method
        // 合并：同一方法已由另一挂载点挂上 ⇒ 不重复包装（不返回方法）
        is ResolveOutcome.Merged -> null
        ResolveOutcome.Unresolved -> null
    }

    /**
     * 解析目标方法（三阶段；缓存语义见类注释）：
     * 1) 直连反射（类名/方法名/签名都还没漂移）；
     * 2) **严格轨** DexKit（类名漂移、方法名与签名不变）；
     * 3) **宽松轨** DexKit（类名保留但方法名/签名/所在类整体漂移——只用内容锚点查，
     *    旧名字与签名降级为排序依据；唯一胜出才采纳，歧义一律跳过并留日志）。
     *
     * @param className 直连失败时其包名自动作为严格轨的包名白名单（宽松轨不限包：类可能已迁包）
     * @param apkPath   目标 APK 的 sourceDir（`param.applicationInfo?.sourceDir`）；null 时跳过 DexKit 兜底
     * @param usingStrings 方法体内**引用的字符串常量**（混淆不改字符串，是最稳的特征；可选）
     * @param paramTypes   **参数类型序列**（按声明顺序；可含未被混淆的可读类名，比 paramCount 强得多；可选）
     * @param declaredClassStrings **所在类**引用的字符串常量（类名+方法名都被混淆时的兜底锚点；可选）
     * @param declaredClassFieldTypes **所在类的字段类型集合**（字段类型含可读类名，如 `common.DownloadInfo`；可选）
     * @param pointId     挂载点 id（合并留痕用：谁占了同一方法）
     * @param instanceOnly 期望实例方法（依赖 thisObject 的拦截器；static 候选不采纳，见 [DexHitSelector.Expectation]）
     */
    internal fun resolveOutcome(
        loader: ClassLoader,
        className: String,
        methodName: String,
        paramCount: Int? = null,
        returnType: String? = null,
        apkPath: String? = null,
        usingStrings: List<String>? = null,
        paramTypes: List<String>? = null,
        declaredClassStrings: List<String>? = null,
        declaredClassFieldTypes: List<String>? = null,
        pointId: String? = null,
        instanceOnly: Boolean = false,
    ): ResolveOutcome {
        // 第一轨：直连反射（照 Rain ck0.S 语义：loadClass + 按名匹配，异常/查不到只记日志转兜底；不缓存——重试靠它赌类稍后可加载）
        val direct = runCatching {
            val byName = loader.loadClass(className).declaredMethods.filter { m ->
                m.name == methodName &&
                    matchesParameters(m, paramCount, paramTypes) &&
                    // 返回类型归一（复审缺陷⑤）：表里写 `int`、宿主声明 `java.lang.Integer` 时不该判为不匹配
                    (returnType == null ||
                        DexHitSelector.canonicalTypeName(m.returnType.name) ==
                            DexHitSelector.canonicalTypeName(returnType))
            }
            // 桥接/合成方法与真实方法同签名，会把"唯一命中"判定搅成歧义 ⇒ 优先只看真实方法；
            // 仅当没有任何真实方法时才回退到合成方法（如协变返回值的桥接实现）。
            val candidates = byName.filter { !it.isSynthetic && !it.isBridge }.ifEmpty { byName }
            when (candidates.size) {
                0 -> null
                1 -> candidates[0]
                else -> {
                    // 同参数量、同返回值的重载且无 paramTypes 锚点：保留 firstOrNull 的既有权衡
                    // （不改变已可用目标的行为），但不再静默 —— 挂错方法是最难查的一类失效。
                    // 归一（int ≡ java.lang.Integer）放宽后可能出现"精确返回类型"与"包装类型"两个候选：
                    // 归一只用于**放宽**，不用于改选 —— 优先取精确返回类型那个（与归一前的选择一致）。
                    val picked = candidates.firstOrNull {
                        returnType == null || it.returnType.name == returnType
                    } ?: candidates[0]
                    Log.w(
                        TAG,
                        "直连反射命中 ${candidates.size} 个同名同参数量方法（$className#$methodName），" +
                            "paramTypes 锚点不足，取第一个: ${DexKitHelper.signatureOf(picked)}",
                    )
                    picked
                }
            }
        }.onFailure { t ->
            Log.w(TAG, "直连反射未命中 $className#$methodName（${t::class.java.simpleName}），转 DexKit 兜底", t)
        }.getOrNull()
        if (direct != null) return ResolveOutcome.Resolved(direct)

        // 第二轨：DexKit 按特征兜底（apkPath 缺失时无法走磁盘 dex 扫描）
        val apk = apkPath ?: run {
            Log.w(TAG, "直连反射未命中且未提供 apkPath，跳过 DexKit 兜底: $className#$methodName")
            return ResolveOutcome.Unresolved
        }
        // ★ 2026-10-10 审计修正：**默认包类**（className 不含 "."，如混淆名 `u15` / `o52`）无法用包名限定搜索范围，
        val pkg = className.substringBeforeLast('.', "").takeIf { it.isNotBlank() }
        val hasAnchor = !usingStrings.isNullOrEmpty() ||
            !paramTypes.isNullOrEmpty() ||
            !declaredClassStrings.isNullOrEmpty() ||
            !declaredClassFieldTypes.isNullOrEmpty()
        if (pkg == null && !hasAnchor) {
            Log.w(
                TAG,
                "DexKit 兜底跳过（默认包类且无内容锚点，避免全 APK 误命中）: $className#$methodName",
            )
            return ResolveOutcome.Unresolved
        }
        // 兜底缓存（仅第二轨；null 也缓存——防版本漂移场景每轮重试对同 key 重复做秒级磁盘扫描）
        val cacheKey = buildString {
            append(className).append('#').append(methodName).append('#').append(paramCount).append('_').append(returnType)
            append("|p=").append(paramTypes?.joinToString(","))
            append("|s=").append(usingStrings?.joinToString(","))
            append("|cs=").append(declaredClassStrings?.joinToString(","))
            append("|cf=").append(declaredClassFieldTypes?.joinToString(","))
        }
        val missSet = missBucket(loader)
        if (missSet.contains(cacheKey)) return ResolveOutcome.Unresolved
        val hitMap = hitBucket(loader)
        hitMap[cacheKey]?.let { return ResolveOutcome.Resolved(it) }
        val expect = DexHitSelector.Expectation(
            className = className,
            methodName = methodName,
            returnType = returnType,
            paramCount = paramCount,
            paramTypes = paramTypes,
            instanceOnly = instanceOnly,
        )
        // 查询失败 ≠ 空命中：withBridge 返回 null（库未就绪/桥创建失败/查询抛错）时不写 miss 缓存，
        // 把"本轮无定论"留给下一轮类加载重试——否则一次瞬时失败会把该点永久钉死成"未命中"。
        val strictResult = DexKitHelper.withBridge(apk) { bridge ->
            DexKitHelper.findMethods(
                bridge = bridge,
                loader = loader,
                spec = DexQueryPlan.strict(
                    methodName = methodName,
                    returnType = returnType,
                    paramCount = paramCount,
                    paramTypes = paramTypes,
                    usingStrings = usingStrings,
                    classStrings = declaredClassStrings,
                    classFieldTypes = declaredClassFieldTypes,
                ),
                packages = pkg?.let { listOf(it) },
            )
        }
        if (strictResult != null) {
            // 命中年名优先：声明类与期望类精确匹配的候选必胜出（同特征的两条查询不会互相串堂）；
            // 类名整体漂移时退化为"唯一候选才采纳"——多个非期望类的候选是掷硬币，宁可 NOT_RESOLVED
            // 留给宽松轨/重试，也不挂一个可能改坏宿主行为的方法（评审 SHOULD-FIX #1/#3）。
            // **无内容锚点时严格轨不猜**（复审缺陷①）：唯一候选也不采纳，交宽松轨（无锚点 ⇒ 老实 NOT_RESOLVED）。
            val hit = DexHitSelector.pickStrict(
                candidates = strictResult.methods,
                expect = expect,
                hasAnchor = hasAnchor,
                complete = strictResult.unresolvedCount == 0,
            )
            if (hit != null) {
                hitMap[cacheKey] = hit
                return ResolveOutcome.Resolved(hit)
            }
            if (strictResult.methods.isNotEmpty()) {
                // 严格轨有候选却没采纳 ⇒ 必须留痕（复审缺陷⑥）：否则 miss 缓存把它钉死，日志上看不出原因。
                Log.w(
                    TAG,
                    "DexKit 严格轨候选未采纳（多候选且声明类不符 / 无内容锚点不猜），转宽松轨: " +
                        "$className#$methodName → " +
                        strictResult.methods.joinToString { DexKitHelper.signatureOf(it) },
                )
            }
        }

        // 第三段（宽松轨）：严格轨全灭后，只用**内容锚点**重查，旧方法名/签名只参与命中排序。
        // 覆盖「类名保留但方法名被 R8 重排、实现被合并进新类」——installer 14.0.38.301 实测
        // 11 个点里 10 个方法名/签名/所在类整体漂移，严格轨全灭而内容锚点都还在。
        // 没有内容锚点就不猜（与仓纪律一致；默认包类尤其不能裸扫全 APK）。
        val contentAnchors = !usingStrings.isNullOrEmpty() ||
            !declaredClassStrings.isNullOrEmpty() ||
            !declaredClassFieldTypes.isNullOrEmpty()
        if (!contentAnchors) {
            missSet += cacheKey
            return ResolveOutcome.Unresolved
        }
        var looseUncertain = false
        var looseClaimed: String? = null
        var mergedWith: String? = null
        val outcome = resolveLoose(
            specs = DexQueryPlan.loose(usingStrings, declaredClassStrings, declaredClassFieldTypes),
            expect = expect,
            fetch = { spec ->
                val result = DexKitHelper.withBridge(apk) { bridge ->
                    // 宽松轨不限定包名：目标类可能已被挪到别的包（r12 → PackageInstallerActivity）
                    DexKitHelper.findMethods(bridge, loader, spec)
                }
                if (result == null || result.unresolvedCount > 0) {
                    // 候选集不完整（库未就绪/冷类没解析齐）：静默丢弃的候选可能正把"并列"翻转成"唯一"，
                    // 采纳即可能挂错——该层不参与判定，并让整轮结果进入"不确定"通道（评审 SHOULD-FIX #3）。
                    looseUncertain = true
                    emptyList()
                } else {
                    // instanceOnly：候选全是 static ⇒ 挂上去 thisObject 恒 null，依赖它的拦截器空转（缺陷③）
                    if (DexHitSelector.staticMismatch(expect, result.methods)) {
                        Log.w(
                            TAG,
                            "DexKit 宽松候选全为 static 实现（期望实例方法，thisObject 为 null 会空转）: " +
                                "$className#$methodName → " +
                                result.methods.joinToString { DexKitHelper.signatureOf(it) },
                        )
                    }
                    result.methods
                }
            },
            // 原子占位：putIfAbsent 返回非 null 即已被别的挂载点先占（防并发解析时同一方法被挂两次）；
            // 占位成功才记 claim，最终未采纳时释放（Duplicate 的占位努力归原主，不可释放）。
            isDuplicate = { method ->
                val sig = DexKitHelper.signatureOf(method)
                val owner = pointId ?: sig
                val prev = looseHitBucket(loader).putIfAbsent(sig, owner)
                if (prev == null) {
                    looseClaimed = sig
                    false
                } else {
                    mergedWith = prev
                    true
                }
            },
        )
        when (outcome) {
            is LooseOutcome.Hit -> {
                if (looseUncertain) {
                    Log.w(
                        TAG,
                        "DexKit 宽松命中但存在不完整查询（本轮不采纳，不缓存 miss）: $className#$methodName → " +
                            DexKitHelper.signatureOf(outcome.method),
                    )
                } else {
                    hitMap[cacheKey] = outcome.method
                    Log.i(
                        TAG,
                        "DexKit 宽松命中（名字/签名已漂移）: 期望 $className#$methodName → " +
                            DexKitHelper.signatureOf(outcome.method) +
                            if (outcome.spec.classStrings != null) "（类级锚点）" else "（方法体锚点）",
                    )
                    return ResolveOutcome.Resolved(outcome.method)
                }
            }
            is LooseOutcome.Ambiguous -> Log.w(
                TAG,
                "DexKit 宽松命中歧义（${outcome.candidates.size} 个候选，跳过）: $className#$methodName → " +
                    outcome.candidates.joinToString { DexKitHelper.signatureOf(it) },
            )
            is LooseOutcome.Duplicate -> {
                // 复审缺陷④：同一方法已被另一挂载点挂上 ⇒ **合并计成功**（不扣 N、不进失败清单），
                // 但必须留一行 WARN 让判据可审计（谁与谁合并）。
                return looseOutcomeToResolve(outcome, mergedWith)
            }
            LooseOutcome.Miss -> Unit
        }
        if (looseUncertain) {
            // 本轮无定论：释放占位 + 不写 miss，下一个重试轮（类加载驱动）再来。
            looseClaimed?.let { looseHitBucket(loader).remove(it) }
            Log.w(TAG, "DexKit 宽松轨查询不完整，不缓存未命中（留待重试）: $className#$methodName")
        } else {
            missSet += cacheKey
        }
        return ResolveOutcome.Unresolved
    }

    /**
     * 参数匹配：[paramTypes] 非空时按**参数类型名序列**精确匹配（可消解同 arity 的重载，
     * 如 `SystemUiHook` 的 `updateSubs(List)` / `updateSubs(MobileCallBack)`），
     * 为空时退化为按 [paramCount] 匹配。
     *
     * 类型名比较前先按 [PRIMITIVE_ALIASES] 归一：DexHint 里写 `int` 而实际声明是 `java.lang.Integer`
     * （或反之）时不该判为不匹配 —— 否则该挂载点会从"直连命中"退化成"未解析"，
     * 白吃一轮重试再交给 DexKit（而 DexKit 用的是同一份特征，同样是空转）。
     */
    private fun matchesParameters(m: Method, paramCount: Int?, paramTypes: List<String>?): Boolean {
        if (!paramTypes.isNullOrEmpty()) {
            val actual = m.parameterTypes
            return actual.size == paramTypes.size &&
                actual.indices.all {
                    DexHitSelector.canonicalTypeName(actual[it].name) ==
                        DexHitSelector.canonicalTypeName(paramTypes[it])
                }
        }
        return paramCount == null || m.parameterCount == paramCount
    }
}

/**
 * 解析结论（MountEngine 消费）：
 * - [Resolved]：命中唯一方法，可挂载；
 * - [Merged]：与**另一挂载点**落到同一方法——同一方法只挂一次（不重复包装），但**合并计成功**；
 * - [Unresolved]：未命中（可随类加载重试）。
 */
internal sealed interface ResolveOutcome {
    data class Resolved(val method: Method) : ResolveOutcome
    data class Merged(val method: Method, val mergedWith: String?) : ResolveOutcome
    object Unresolved : ResolveOutcome
}

/**
 * 宽松轨结果 → 解析结论（纯函数，可 JVM 单测）。
 *
 * 关键语义（复审缺陷④）：[LooseOutcome.Duplicate] = **合并**，不是"未挂载"——该方法确实已被
 * 另一个挂载点挂上（installer 38.301 的 `CommonConfigManager.g` 与 `#h` 落到同一 `c(Rsp)`），
 * 记 NOT_RESOLVED 会把它算进失败清单并扣 N，与事实不符。歧义（[LooseOutcome.Ambiguous]）与
 * 全灭（[LooseOutcome.Miss]）才是真的没挂上。
 */
internal fun looseOutcomeToResolve(outcome: LooseOutcome, mergedWith: String? = null): ResolveOutcome =
    when (outcome) {
        is LooseOutcome.Hit -> ResolveOutcome.Resolved(outcome.method)
        is LooseOutcome.Duplicate -> ResolveOutcome.Merged(outcome.method, mergedWith)
        is LooseOutcome.Ambiguous -> ResolveOutcome.Unresolved
        LooseOutcome.Miss -> ResolveOutcome.Unresolved
    }
