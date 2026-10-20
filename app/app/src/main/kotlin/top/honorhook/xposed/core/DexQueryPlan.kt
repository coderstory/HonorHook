package top.honorhook.xposed.core

/**
 * DexKit 查询计划（纯数据，不依赖 DexKit —— 可在 JVM 单测里钉住"严格轨/宽松轨各带哪些过滤"）。
 *
 * 严格轨 = 历史行为：方法名 / 参数个数 / 参数类型 / 返回类型全部钉死。
 * 只覆盖「类名被重映射、方法名与签名不变」这一种漂移。
 *
 * 宽松轨 = 只用**内容锚点**（方法体字符串 / 类字段类型）查，名字与签名降级为命中排序依据
 * （见 [DexHitSelector.selectLoose]）。覆盖「类名保留但方法名被 R8 重排、实现被合并进新类」——
 * installer 14.0.38.301 实证：11 个挂载点里 10 个方法名/签名/所在类整体漂移，严格轨全灭。
 */
internal data class QuerySpec(
    val methodName: String? = null,
    val returnType: String? = null,
    val paramCount: Int? = null,
    val paramTypes: List<String>? = null,
    val usingStrings: List<String>? = null,
    val classStrings: List<String>? = null,
    val classFieldTypes: List<String>? = null,
    /**
     * 硬约束：候选方法的**声明类必须等于期望声明类**，否则该层不参与判定。
     *
     * 只给"类锚点兼作方法体串"的层用（复审缺陷②）：类锚点多是类内 TAG/布局名，同一串会出现在
     * 别的类里（UI 串尤其容易铺满全 APK）⇒ 兼作方法体串时必须钉住声明类。
     */
    val requireDeclaredClass: Boolean = false,
)

internal object DexQueryPlan {

    /** 严格轨：保持现有过滤集合，行为零变化。 */
    fun strict(
        methodName: String,
        returnType: String?,
        paramCount: Int?,
        paramTypes: List<String>?,
        usingStrings: List<String>?,
        classStrings: List<String>?,
        classFieldTypes: List<String>?,
    ): QuerySpec = QuerySpec(
        methodName = methodName,
        returnType = returnType,
        paramCount = paramCount,
        paramTypes = paramTypes,
        usingStrings = usingStrings,
        classStrings = classStrings,
        classFieldTypes = classFieldTypes,
    )

    /**
     * 宽松轨：按精度从高到低返回 0~3 条查询。
     *
     * L1 = **方法体串**（只用 usingStrings）。classStrings 是**类级锚点**，不参与本层（复审缺陷②：
     * 类锚点兼作方法体串会把候选铺满全 APK，UI 串/布局名尤其明显）。
     *
     * L2 = **类锚点兼作方法体串，但硬约束声明类**。历史上"类锚点"其实多是类内 TAG，出现在目标方法体里
     * （如 installer 38 的 `PwdControlUtil` 日志出现在 `lt1.w0(Context, cv1)`、`lt1.t(Context)`、
     * `ct1.onAuthFinish` 与 `x0` 内匿名 `onAuthFinish` 共 4 个方法里）——方法级查询比枚举整个类精确。
     * 保留这层但加声明类约束（[QuerySpec.requireDeclaredClass]）：类锚点出现在**别的类**里时本层直接弃权，
     * 交给 L3，避免把候选铺满全 APK 后靠打分硬猜。
     *
     * L3 = 真正的**类级匹配**（`classFieldTypes` 没有方法级等价物，只能走这层；类名整体漂移时也只有这层能救）。
     * 保留方法体串锚点：旧 findMethodByFeatures 里 usingStrings 与 declaredClass 是**同时**生效的，
     * 类级兜底若丢掉方法体串，查询会变宽（同返回类型的其它方法可能被选中）。
     *
     * 没有任何内容锚点时返回空：没有锚点就不猜（与仓纪律一致）。
     */
    fun loose(
        usingStrings: List<String>?,
        classStrings: List<String>?,
        classFieldTypes: List<String>?,
    ): List<QuerySpec> {
        val out = mutableListOf<QuerySpec>()
        // L1：方法体串层——只放 usingStrings（classStrings 是类级锚点，不进本层）
        if (!usingStrings.isNullOrEmpty()) out += QuerySpec(usingStrings = usingStrings)
        // L2：类锚点兼作方法体串 + 声明类硬约束
        if (!classStrings.isNullOrEmpty()) {
            out += QuerySpec(usingStrings = classStrings, requireDeclaredClass = true)
        }
        // L3：类级匹配
        if (!classStrings.isNullOrEmpty() || !classFieldTypes.isNullOrEmpty()) {
            out += QuerySpec(
                usingStrings = usingStrings,
                classStrings = classStrings,
                classFieldTypes = classFieldTypes,
            )
        }
        return out
    }
}
