package top.honorhook.xposed.core

import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * 宽松轨的命中选择与可挂载性判定（纯逻辑，JVM 可测）。
 *
 * 设计约束：宽松查询会拿回一批候选，**只有能唯一胜出的才采纳**；并列一律跳过并留日志。
 * 宁可诚实 NOT_RESOLVED，也不猜一个可能挂错的方法——挂错的 CONST/BLOCK 会直接改坏宿主行为。
 */
internal object DexHitSelector {

    /** 期望签名（来自挂载点声明）；[returnType] 为硬条件，其余只参与打分。 */
    data class Expectation(
        val className: String,
        val methodName: String,
        val returnType: String?,
        val paramCount: Int?,
        val paramTypes: List<String>?,
        /**
         * 期望**实例**方法（拦截器依赖 `thisObject`）：true 时 static 候选一律不采纳。
         *
         * 依据（2026-10-20 复审缺陷③）：static 方法被挂上时 `thisObject` 恒为 null，依赖它的拦截器
         * （installer 的 CHECK_BYPASS 读 `chain.thisObject`、CHECK_BYPASS_JOB 经回调反查 Activity）
         * 会**静默空转**——判据行照样打 "installed N/M"，功能却没生效。故这类 spec 必须显式置位，
         * 不得靠"static 也能 hook"（2026-10-20 删掉 static 过滤是纯收益，那 8 个 static 实现本身不依赖 thisObject）。
         */
        val instanceOnly: Boolean = false,
    )

    /**
     * 可挂载性：abstract / native 不可 hook；**static 可 hook**。
     *
     * 旧实现照抄 Rain 的 helper，把 `Modifier.isStatic` 也当过滤条件——那是 Rain 自身场景的取舍，
     * 对本模块是纯损失：14.0.38.301 的 installer 有 8 个实现改成了 static，即使 DexKit 命中也全被丢掉。
     */
    fun isHookable(method: Method): Boolean {
        val mod = method.modifiers
        return !Modifier.isAbstract(mod) && !Modifier.isNative(mod)
    }

    /** 可挂载且返回类型吻合的候选 = 宽松轨的"可行集"；空 = 该层查询对本点无任何可用命中。 */
    internal fun viable(expect: Expectation, candidates: List<Method>): List<Method> =
        candidates.filter { isHookable(it) }
            // 返回类型归一：表里写 `int`、宿主声明是 `java.lang.Integer` 时不该整批丢掉（复审缺陷⑤）。
            // 参数侧早就走 canonicalTypeName（见 score / matchesParameters），返回类型侧此前漏了 ⇒ 整批候选被丢弃。
            .filter {
                expect.returnType == null ||
                    canonicalTypeName(it.returnType.name) == canonicalTypeName(expect.returnType)
            }
            // instanceOnly：static 实现挂上去 thisObject 恒 null ⇒ 依赖它的拦截器空转（复审缺陷③）。
            .filter { !expect.instanceOnly || !Modifier.isStatic(it.modifiers) }

    /**
     * 严格轨命中选择（纯函数 ⇒ JVM 可测；原先内联在 MethodResolver 里没法钉回归）。
     *
     * 规则（复审缺陷①的"无锚点也猜"闸门）：
     * 1) **声明类与期望类精确匹配**的候选直接采纳（类名 + 方法名 + 签名三重对齐，最强证据）；
     * 2) **无内容锚点时不猜**：唯一候选也不采纳——此时只剩"名字+签名"这种弱特征，同名方法全 APK 一大把，
     *    猜错代价（挂错 CONST/BLOCK 直接改坏宿主行为）远大于漏挂。交宽松轨；宽松轨也无锚点 ⇒ 老实 NOT_RESOLVED。
     * 3) 有锚点且候选集完整时，唯一候选可采纳（老行为不变）；多候选并列 ⇒ 不采纳（歧义不猜）。
     *
     * @param hasAnchor 是否存在**内容锚点**（方法体字符串 / 参数类型序列 / 类字符串 / 类字段类型）
     * @param complete 候选集是否完整（DexKit 反射解析失败的条数 > 0 ⇒ 不完整 ⇒ 不做"唯一"判定）
     */
    fun pickStrict(
        candidates: List<Method>,
        expect: Expectation,
        hasAnchor: Boolean,
        complete: Boolean = true,
    ): Method? {
        if (candidates.isEmpty()) return null
        candidates.firstOrNull { it.declaringClass.name == expect.className }?.let { return it }
        if (!hasAnchor) return null
        if (!complete) return null
        return candidates.singleOrNull()
    }

    /**
     * 期望实例方法、但候选全是 static：挂上去 `thisObject` 恒 null，依赖它的拦截器会静默空转。
     * 命中面：宽松轨这类层本该被 [viable] 滤掉；此函数用于**留痕**（告知为何该层没出命中）。
     */
    fun staticMismatch(expect: Expectation, candidates: List<Method>): Boolean =
        expect.instanceOnly && candidates.isNotEmpty() && candidates.all { Modifier.isStatic(it.modifiers) }

    /**
     * 从 [candidates] 里选唯一最匹配 [expect] 的方法。
     *
     * 硬条件：返回类型必须一致（CONST/BLOCK 的强制值依赖返回类型，类型不符等于挂错）。
     * 打分：声明类名命中（1000）> 方法名命中（100）> 参数类型逐位命中（每位 10）> 参数个数命中（5）。
     * 最高分并列 ⇒ 返回 null（歧义不猜）。
     */
    fun selectLoose(expect: Expectation, candidates: List<Method>): Method? {
        var viable = viable(expect, candidates)
        if (viable.isEmpty()) return null
        // 桥接/合成方法与真实方法同签名：优先看真实方法（与直连轨同一偏好；有多余解才可能翻脸）。
        val real = viable.filter { !it.isSynthetic && !it.isBridge }
        if (real.isNotEmpty()) viable = real
        val scored = viable.map { it to score(expect, it) }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second == best }
        return if (top.size == 1) top[0].first else null
    }

    private fun score(expect: Expectation, method: Method): Int {
        var score = 0
        if (method.declaringClass.name == expect.className) score += 1000
        if (method.name == expect.methodName) score += 100
        val expected = expect.paramTypes
        if (!expected.isNullOrEmpty()) {
            val actual = method.parameterTypes
            if (actual.size == expected.size) {
                score += actual.indices.count {
                    canonicalTypeName(actual[it].name) == canonicalTypeName(expected[it])
                } * 10
            }
        }
        if (expect.paramCount != null && method.parameterCount == expect.paramCount) score += 5
        return score
    }

    /** 基本类型与其包装类型的等价归一（`int` / `java.lang.Integer` 视为同一类型）。 */
    fun canonicalTypeName(name: String): String = PRIMITIVE_ALIASES[name] ?: name

    private val PRIMITIVE_ALIASES = mapOf(
        "java.lang.Integer" to "int",
        "java.lang.Long" to "long",
        "java.lang.Boolean" to "boolean",
        "java.lang.Double" to "double",
        "java.lang.Float" to "float",
        "java.lang.Short" to "short",
        "java.lang.Byte" to "byte",
        "java.lang.Character" to "char",
    )
}

/**
 * 宽松轨编排结果：命中 / 歧义（跳过）/ 与已挂方法重复（**合并**：同一方法只挂一次）/ 全部查询落空。
 */
internal sealed interface LooseOutcome {
    data class Hit(val method: Method, val spec: QuerySpec) : LooseOutcome
    data class Ambiguous(val spec: QuerySpec, val candidates: List<Method>) : LooseOutcome
    data class Duplicate(val method: Method) : LooseOutcome
    object Miss : LooseOutcome
}

/**
 * 按 [specs] 顺序查询，遇到第一个有候选的查询就做选择并停下（更宽的后续查询只会更歧义）。
 * [isDuplicate] 由调用方按 ClassLoader 维护：同一方法被两个挂载点宽松命中时只挂一次。
 *
 * [QuerySpec.requireDeclaredClass] 的层（类锚点兼作方法体串）额外要求**候选声明类 == 期望声明类**：
 * 类锚点多是类内 TAG，出现在目标方法体里，但同一串也可能出现在别的类里（UI 串/布局名尤其容易）⇒
 * 不加约束会把候选铺满全 APK（复审缺陷②）。声明类不符 ⇒ 该层不参与判定，交给后续更宽的层。
 */
internal fun resolveLoose(
    specs: List<QuerySpec>,
    expect: DexHitSelector.Expectation,
    fetch: (QuerySpec) -> List<Method>,
    isDuplicate: (Method) -> Boolean,
): LooseOutcome {
    for (spec in specs) {
        val candidates = fetch(spec)
        if (candidates.isEmpty()) continue
        val scoped = if (spec.requireDeclaredClass) {
            candidates.filter { it.declaringClass.name == expect.className }
        } else {
            candidates
        }
        if (scoped.isEmpty()) continue
        val hit = DexHitSelector.selectLoose(expect, scoped)
        if (hit != null) {
            if (isDuplicate(hit)) return LooseOutcome.Duplicate(hit)
            return LooseOutcome.Hit(hit, spec)
        }
        // 两种 null 含义不同：可行集为空（返回类型全不符/全是 abstract/全是 static）= 该层对目标无用，
        // 继续下一层；可行集非空但最高分并列 = 真歧义，更宽的后续查询只会更歧义，立即终局。
        if (DexHitSelector.viable(expect, scoped).isEmpty()) continue
        return LooseOutcome.Ambiguous(spec, scoped)
    }
    return LooseOutcome.Miss
}
