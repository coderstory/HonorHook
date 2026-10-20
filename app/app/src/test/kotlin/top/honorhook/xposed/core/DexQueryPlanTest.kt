package top.honorhook.xposed.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Method

/**
 * DexKit 查询计划与宽松命中选择的 JVM 回归。
 *
 * 钉住两条在宿主 14.0.38.301 上实证失效的规则：
 * 1) 宽松轨**不得**再带 methodName / paramCount / paramTypes / returnType 去查——旧方法名是硬过滤，
 *    R8 把单字母方法名重排后，内容锚点明明命中也全被过滤掉（实测 10/10 全灭）。
 * 2) static 方法可 hook——旧实现照抄 Rain 的 `Modifier.isStatic` 过滤，把新版本改成静态的实现全丢弃。
 */
class DexQueryPlanTest {

    // ---------------- 查询计划 ----------------

    @Test
    fun `strict query keeps name params and return type`() {
        val spec = DexQueryPlan.strict(
            methodName = "b",
            returnType = "boolean",
            paramCount = 1,
            paramTypes = listOf("java.lang.String"),
            usingStrings = listOf("checkIsSupportAdRequest"),
            classStrings = null,
            classFieldTypes = null,
        )
        assertEquals("b", spec.methodName)
        assertEquals(1, spec.paramCount)
        assertEquals("boolean", spec.returnType)
        assertEquals(listOf("java.lang.String"), spec.paramTypes)
    }

    @Test
    fun `loose query drops name params and return type`() {
        val specs = DexQueryPlan.loose(
            usingStrings = listOf("checkIsSupportAdRequest"),
            classStrings = null,
            classFieldTypes = null,
        )
        assertEquals(1, specs.size)
        val spec = specs[0]
        assertNull(spec.methodName)
        assertNull(spec.paramCount)
        assertNull(spec.paramTypes)
        assertNull(spec.returnType)
        assertEquals(listOf("checkIsSupportAdRequest"), spec.usingStrings)
    }

    @Test
    fun `class anchor doubles as method level anchor but only inside the expected class`() {
        // 缺陷②：classStrings 是**类级锚点**。历史上它被兼作"方法体串"查（L1），会把候选铺满全 APK
        // （UI 串/布局名尤其明显）。现在：L1 只放 usingStrings；类锚点另起一层兼作方法体串，
        // 但**硬约束声明类**（requireDeclaredClass）——声明类不符 ⇒ 该层弃权，交给类级层。
        val specs = DexQueryPlan.loose(
            usingStrings = null,
            classStrings = listOf("PwdControlUtil"),
            classFieldTypes = null,
        )
        assertEquals(2, specs.size)
        // L2：类锚点兼作方法体串 + 声明类硬约束
        assertEquals(listOf("PwdControlUtil"), specs[0].usingStrings)
        assertNull(specs[0].classStrings)
        assertTrue(specs[0].requireDeclaredClass)
        // L3：类级匹配
        assertNull(specs[1].usingStrings)
        assertEquals(listOf("PwdControlUtil"), specs[1].classStrings)
        assertFalse(specs[1].requireDeclaredClass)
    }

    @Test
    fun `class field types alone yield a class level spec`() {
        val specs = DexQueryPlan.loose(
            usingStrings = null,
            classStrings = null,
            classFieldTypes = listOf("com.xingin.entities.notedetail.NoteFeed"),
        )
        assertEquals(1, specs.size)
        assertEquals(listOf("com.xingin.entities.notedetail.NoteFeed"), specs[0].classFieldTypes)
    }

    @Test
    fun `class level spec keeps method level strings`() {
        // L3 是"类级兜底"，但不能丢掉方法体串锚点——旧 findMethodByFeatures 是 usingStrings 与
        // declaredClass 同时生效；丢掉会让类级查询瞬间变宽，可能选中同返回类型的其它方法。
        val specs = DexQueryPlan.loose(
            usingStrings = listOf("method-body-anchor"),
            classStrings = listOf("ClassTAG"),
            classFieldTypes = null,
        )
        assertEquals(3, specs.size)
        // L1：只用方法体串（classStrings 不进本层）
        assertEquals(listOf("method-body-anchor"), specs[0].usingStrings)
        assertNull(specs[0].classStrings)
        assertFalse(specs[0].requireDeclaredClass)
        // L2：类锚点兼作方法体串 + 声明类硬约束
        assertEquals(listOf("ClassTAG"), specs[1].usingStrings)
        assertTrue(specs[1].requireDeclaredClass)
        // L3：类级
        assertEquals(listOf("method-body-anchor"), specs[2].usingStrings)
        assertEquals(listOf("ClassTAG"), specs[2].classStrings)
    }

    @Test
    fun `no loose query without content anchor`() {
        assertTrue(DexQueryPlan.loose(usingStrings = null, classStrings = null, classFieldTypes = null).isEmpty())
    }

    // ---------------- 严格轨命中选择（复审缺陷①：无锚点不猜） ----------------

    @Test
    fun `strict pick prefers exact declaring class`() {
        val expect = DexHitSelector.Expectation(HolderA::class.java.name, "run", "int", 1, listOf("java.lang.String"))
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val b = HolderB::class.java.getMethod("run", String::class.java)
        assertSame(a, DexHitSelector.pickStrict(listOf(b, a), expect, hasAnchor = true))
    }

    @Test
    fun `strict pick accepts unique candidate when anchored`() {
        // 有内容锚点时保留老行为：类名漂移但唯一候选 ⇒ 采纳
        val expect = DexHitSelector.Expectation("somewhereElse", "run", "int", 1, listOf("java.lang.String"))
        val b = HolderB::class.java.getMethod("run", String::class.java)
        assertSame(b, DexHitSelector.pickStrict(listOf(b), expect, hasAnchor = true))
    }

    @Test
    fun `strict pick refuses ambiguous candidates`() {
        val expect = DexHitSelector.Expectation("somewhereElse", "run", "int", 1, listOf("java.lang.String"))
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val b = HolderB::class.java.getMethod("run", String::class.java)
        assertNull(DexHitSelector.pickStrict(listOf(a, b), expect, hasAnchor = true))
    }

    @Test
    fun `strict pick does not guess without content anchor`() {
        // 纪律：无内容锚点时唯一候选**也不采纳**（只剩"名字+签名"这种弱特征）⇒ 交宽松轨，
        // 宽松轨无锚点 → 老实 NOT_RESOLVED，不硬兜。
        val expect = DexHitSelector.Expectation("somewhereElse", "run", "int", 1, listOf("java.lang.String"))
        val b = HolderB::class.java.getMethod("run", String::class.java)
        assertNull(DexHitSelector.pickStrict(listOf(b), expect, hasAnchor = false))
    }

    @Test
    fun `strict pick accepts exact declaring class even without anchor`() {
        // 类名 + 方法名 + 签名三重对齐是最强证据：即便无内容锚点也可采纳
        val expect = DexHitSelector.Expectation(HolderA::class.java.name, "run", "int", 1, listOf("java.lang.String"))
        val a = HolderA::class.java.getMethod("run", String::class.java)
        assertSame(a, DexHitSelector.pickStrict(listOf(a), expect, hasAnchor = false))
    }

    @Test
    fun `strict pick refuses unique pick on incomplete candidate set`() {
        // 候选集不完整（DexKit 反射解析有失败）⇒ 不做"唯一"判定
        val expect = DexHitSelector.Expectation("somewhereElse", "run", "int", 1, listOf("java.lang.String"))
        val b = HolderB::class.java.getMethod("run", String::class.java)
        assertNull(DexHitSelector.pickStrict(listOf(b), expect, hasAnchor = true, complete = false))
    }

    // ---------------- 返回类型归一（复审缺陷⑤） ----------------

    @Test
    fun `return type comparison normalizes primitive and wrapper`() {
        // 表里写 int、宿主声明 java.lang.Integer ⇒ 不该整批丢掉
        val expect = DexHitSelector.Expectation("somewhereElse", "valueOf", "int", 1, listOf("java.lang.String"))
        val valueOf = java.lang.Integer::class.java.getMethod("valueOf", String::class.java) // 返回 java.lang.Integer
        assertSame(valueOf, DexHitSelector.selectLoose(expect, listOf(valueOf)))
    }

    @Test
    fun `return type comparison normalizes wrapper to primitive`() {
        val expect = DexHitSelector.Expectation("somewhereElse", "parseInt", "java.lang.Integer", 1, listOf("java.lang.String"))
        val parseInt = java.lang.Integer::class.java.getMethod("parseInt", String::class.java) // 返回 int
        assertSame(parseInt, DexHitSelector.selectLoose(expect, listOf(parseInt)))
    }

    // ---------------- instanceOnly：static 候选（复审缺陷③） ----------------

    @Test
    fun `instance only expectation refuses static candidates`() {
        // static 实现挂上去 thisObject 恒 null ⇒ 依赖它的拦截器静默空转 ⇒ 不采纳
        val staticMethod = java.lang.Integer::class.java.getMethod("parseInt", String::class.java)
        val expect = DexHitSelector.Expectation(
            "somewhereElse", "parseInt", "int", 1, listOf("java.lang.String"), instanceOnly = true,
        )
        assertTrue(DexHitSelector.viable(expect, listOf(staticMethod)).isEmpty())
        assertNull(DexHitSelector.selectLoose(expect, listOf(staticMethod)))
    }

    @Test
    fun `static candidates are still accepted without instance only`() {
        // 默认口径不变：static 可 hook（installer 38 有 8 个实现改成了 static）
        val staticMethod = java.lang.Integer::class.java.getMethod("parseInt", String::class.java)
        val expect = DexHitSelector.Expectation("somewhereElse", "parseInt", "int", 1, listOf("java.lang.String"))
        assertSame(staticMethod, DexHitSelector.selectLoose(expect, listOf(staticMethod)))
    }

    @Test
    fun `static mismatch is reported only for instance only expectations`() {
        val staticMethod = java.lang.Integer::class.java.getMethod("parseInt", String::class.java)
        val instanceOnly = DexHitSelector.Expectation(
            "somewhereElse", "parseInt", "int", 1, listOf("java.lang.String"), instanceOnly = true,
        )
        val relaxed = DexHitSelector.Expectation("somewhereElse", "parseInt", "int", 1, listOf("java.lang.String"))
        assertTrue(DexHitSelector.staticMismatch(instanceOnly, listOf(staticMethod)))
        assertFalse(DexHitSelector.staticMismatch(relaxed, listOf(staticMethod)))
    }

    // ---------------- 合并语义（复审缺陷④：Duplicate 计成功） ----------------

    @Test
    fun `duplicate loose hit is merged instead of unresolved`() {
        // 同一方法被两个挂载点命中（installer 38.301 的 CommonConfigManager.g 与 #h 落到同一 c(Rsp)）：
        // 合并计成功（不扣 N、不进失败清单），但要留痕（谁与谁合并）。
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val expect = DexHitSelector.Expectation("somewhereElse", "renamed", "int", 1, listOf("java.lang.String"))
        val specs = DexQueryPlan.loose(usingStrings = listOf("tag"), classStrings = null, classFieldTypes = null)
        val outcome = resolveLoose(specs, expect, fetch = { listOf(a) }, isDuplicate = { true })
        assertTrue(outcome is LooseOutcome.Duplicate)
        val resolved = looseOutcomeToResolve(outcome, "installer:CommonConfig.getSafeModeConfigJson")
        assertTrue(resolved is ResolveOutcome.Merged)
        assertEquals("installer:CommonConfig.getSafeModeConfigJson", (resolved as ResolveOutcome.Merged).mergedWith)
    }

    @Test
    fun `merged outcome counts as installed`() {
        // N/M 口径：MERGED（合并）算已挂载；NOT_RESOLVED / FAILED 不算
        assertTrue(MountEngine.Result.MERGED.installed)
        assertTrue(MountEngine.isInstalled(MountEngine.Result.OK))
        assertTrue(MountEngine.isInstalled(MountEngine.Result.MERGED))
        assertFalse(MountEngine.isInstalled(MountEngine.Result.NOT_RESOLVED))
        assertFalse(MountEngine.isInstalled(MountEngine.Result.FAILED))
    }

    @Test
    fun `ambiguous and missed loose outcomes stay unresolved`() {
        val expect = DexHitSelector.Expectation("somewhereElse", "renamed", "int", 1, listOf("java.lang.String"))
        assertTrue(looseOutcomeToResolve(LooseOutcome.Miss) is ResolveOutcome.Unresolved)
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val b = HolderB::class.java.getMethod("run", String::class.java)
        val specs = DexQueryPlan.loose(usingStrings = listOf("tag"), classStrings = null, classFieldTypes = null)
        val ambiguous = resolveLoose(specs, expect, fetch = { listOf(a, b) }, isDuplicate = { false })
        assertTrue(looseOutcomeToResolve(ambiguous) is ResolveOutcome.Unresolved)
    }

    // ---------------- 可挂载性 ----------------

    @Test
    fun `static methods are hookable`() {
        assertTrue(DexHitSelector.isHookable(java.lang.Integer::class.java.getMethod("parseInt", String::class.java)))
    }

    @Test
    fun `native and abstract methods are not hookable`() {
        assertFalse(DexHitSelector.isHookable(Any::class.java.getMethod("hashCode")))
        assertFalse(DexHitSelector.isHookable(java.util.List::class.java.getMethod("get", Int::class.javaPrimitiveType)))
    }

    // ---------------- 宽松命中选择 ----------------

    @Test
    fun `return type mismatch is never selected`() {
        val expect = DexHitSelector.Expectation("p81", "b", "int", 1, listOf("java.lang.String"))
        val parseInt = java.lang.Integer::class.java.getMethod("parseInt", String::class.java)
        val startsWith = java.lang.String::class.java.getMethod("startsWith", String::class.java)
        // startsWith 返回 boolean，与期望的 int 不符 ⇒ 只剩 parseInt
        assertSame(parseInt, DexHitSelector.selectLoose(expect, listOf(startsWith, parseInt)))
    }

    @Test
    fun `primitive and wrapper return types are no longer distinguished`() {
        // 缺陷⑤ 的直接后果：表里写 int 时，返回 java.lang.Integer 的方法也进可行集 ⇒ 两个候选并列 ⇒ 不猜。
        // （旧行为把 valueOf 整批丢掉，会让"唯一候选"判定失真。）
        val expect = DexHitSelector.Expectation("p81", "b", "int", 1, listOf("java.lang.String"))
        val parseInt = java.lang.Integer::class.java.getMethod("parseInt", String::class.java)
        val valueOf = java.lang.Integer::class.java.getMethod("valueOf", String::class.java)
        assertNull(DexHitSelector.selectLoose(expect, listOf(valueOf, parseInt)))
    }

    @Test
    fun `no candidate matching return type yields null`() {
        val expect = DexHitSelector.Expectation("p81", "b", "boolean", 1, listOf("java.lang.String"))
        val parseInt = java.lang.Integer::class.java.getMethod("parseInt", String::class.java)
        val valueOf = java.lang.Integer::class.java.getMethod("valueOf", String::class.java)
        assertNull(DexHitSelector.selectLoose(expect, listOf(parseInt, valueOf)))
    }

    @Test
    fun `expected declaring class wins over same signature elsewhere`() {
        val expect = DexHitSelector.Expectation(HolderA::class.java.name, "nope", "int", 1, listOf("java.lang.String"))
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val b = HolderB::class.java.getMethod("run", String::class.java)
        assertSame(a, DexHitSelector.selectLoose(expect, listOf(b, a)))
    }

    @Test
    fun `parameter type overlap breaks signature ties`() {
        val expect = DexHitSelector.Expectation(
            "somewhereElse", "renamed", "void", 2,
            listOf("java.lang.String", "java.lang.Runnable"),
        )
        val withRunnable = ParamHolder1::class.java.getMethod("z", String::class.java, Runnable::class.java)
        val withInteger = ParamHolder2::class.java.getMethod("z", String::class.java, java.lang.Integer::class.java)
        assertSame(withRunnable, DexHitSelector.selectLoose(expect, listOf(withInteger, withRunnable)))
    }

    @Test
    fun `ambiguous candidates are refused instead of guessed`() {
        val expect = DexHitSelector.Expectation("somewhereElse", "renamed", "int", 1, listOf("java.lang.String"))
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val b = HolderB::class.java.getMethod("run", String::class.java)
        assertNull(DexHitSelector.selectLoose(expect, listOf(a, b)))
    }

    @Test
    fun `loose L1 carries method body strings only`() {
        // 缺陷② 的钉子：L1（方法体串层）**只**放 usingStrings；classStrings 是类级锚点，不进 L1。
        val specs = DexQueryPlan.loose(
            usingStrings = listOf("method-body-anchor"),
            classStrings = listOf("ClassTAG"),
            classFieldTypes = null,
        )
        val l1 = specs.first()
        assertEquals(listOf("method-body-anchor"), l1.usingStrings)
        assertNull(l1.classStrings)
        assertNull(l1.classFieldTypes)
        assertFalse(l1.requireDeclaredClass)
    }

    // ---------------- 宽松轨编排 ----------------

    @Test
    fun `loose resolution falls back to class level spec when method strings miss`() {
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val expect = DexHitSelector.Expectation("somewhereElse", "renamed", "int", 1, listOf("java.lang.String"))
        val specs = DexQueryPlan.loose(usingStrings = listOf("tag"), classStrings = listOf("HolderA"), classFieldTypes = null)
        val asked = mutableListOf<QuerySpec>()
        val outcome = resolveLoose(specs, expect, fetch = { spec ->
            asked += spec
            if (spec.classStrings != null) listOf(a) else emptyList()
        }, isDuplicate = { false })
        assertTrue(outcome is LooseOutcome.Hit)
        assertEquals(3, asked.size)   // L1 方法体串 → L2 类锚点（声明类硬约束）→ L3 类级
        assertEquals(listOf("HolderA"), (outcome as LooseOutcome.Hit).spec.classStrings)
    }

    @Test
    fun `loose resolution stops at the first spec with candidates`() {
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val expect = DexHitSelector.Expectation("somewhereElse", "renamed", "int", 1, listOf("java.lang.String"))
        val specs = DexQueryPlan.loose(usingStrings = listOf("tag"), classStrings = listOf("HolderA"), classFieldTypes = null)
        val asked = mutableListOf<QuerySpec>()
        val outcome = resolveLoose(specs, expect, fetch = { spec ->
            asked += spec
            listOf(a)
        }, isDuplicate = { false })
        assertTrue(outcome is LooseOutcome.Hit)
        assertEquals(1, asked.size)
    }

    @Test
    fun `loose resolution reports ambiguity instead of continuing to broader specs`() {
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val b = HolderB::class.java.getMethod("run", String::class.java)
        val expect = DexHitSelector.Expectation("somewhereElse", "renamed", "int", 1, listOf("java.lang.String"))
        val specs = DexQueryPlan.loose(usingStrings = listOf("tag"), classStrings = listOf("HolderA"), classFieldTypes = null)
        val asked = mutableListOf<QuerySpec>()
        val outcome = resolveLoose(specs, expect, fetch = { spec ->
            asked += spec
            listOf(a, b)
        }, isDuplicate = { false })
        assertTrue(outcome is LooseOutcome.Ambiguous)
        assertEquals(1, asked.size)
    }

    @Test
    fun `loose resolution continues to next spec when candidates are all non viable`() {
        // "有候选但全不可挂载"（返回类型对不上）≠ "并列歧义"：前者应跳层继续，
        // 否则类级第二层永远没机会救回，日志还把方向引到"锚点太宽"上。
        val expect = DexHitSelector.Expectation("java.lang.String", "startsWith", "boolean", 1, listOf("java.lang.String"))
        val wrongReturn = HolderA::class.java.getMethod("run", String::class.java) // int ≠ 期望 boolean
        val good = java.lang.String::class.java.getMethod("startsWith", String::class.java)
        val specs = DexQueryPlan.loose(usingStrings = listOf("tag"), classStrings = listOf("String"), classFieldTypes = null)
        val asked = mutableListOf<QuerySpec>()
        val outcome = resolveLoose(specs, expect, fetch = { spec ->
            asked += spec
            if (spec.classStrings == null) listOf(wrongReturn) else listOf(good)
        }, isDuplicate = { false })
        assertTrue(outcome is LooseOutcome.Hit)
        assertEquals(3, asked.size)   // L1 不可行 → L2 声明类不符弃权 → L3 类级命中
    }

    @Test
    fun `loose resolution reports duplicate hit`() {
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val expect = DexHitSelector.Expectation("somewhereElse", "renamed", "int", 1, listOf("java.lang.String"))
        val specs = DexQueryPlan.loose(usingStrings = listOf("tag"), classStrings = null, classFieldTypes = null)
        val outcome = resolveLoose(specs, expect, fetch = { listOf(a) }, isDuplicate = { true })
        assertTrue(outcome is LooseOutcome.Duplicate)
    }

    @Test
    fun `class anchor layer keeps only candidates of the expected declaring class`() {
        // 类锚点层（requireDeclaredClass）：别的类里的同串候选被丢掉，只留期望声明类的
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val b = HolderB::class.java.getMethod("run", String::class.java)
        val expect = DexHitSelector.Expectation(HolderB::class.java.name, "renamed", "int", 1, listOf("java.lang.String"))
        val specs = DexQueryPlan.loose(usingStrings = null, classStrings = listOf("HolderA"), classFieldTypes = null)
        val outcome = resolveLoose(specs, expect, fetch = { listOf(a, b) }, isDuplicate = { false })
        assertTrue(outcome is LooseOutcome.Hit)
        assertSame(b, (outcome as LooseOutcome.Hit).method)
    }

    @Test
    fun `class anchor layer abstains when declaring class differs`() {
        // 声明类不符 ⇒ 该层弃权（不跨类乱挂），落到更宽的类级层
        val a = HolderA::class.java.getMethod("run", String::class.java)
        val expect = DexHitSelector.Expectation("somewhereElse", "renamed", "int", 1, listOf("java.lang.String"))
        val specs = DexQueryPlan.loose(usingStrings = null, classStrings = listOf("HolderA"), classFieldTypes = null)
        val asked = mutableListOf<QuerySpec>()
        val outcome = resolveLoose(specs, expect, fetch = { spec ->
            asked += spec
            listOf(a)
        }, isDuplicate = { false })
        assertEquals(2, asked.size)
        assertTrue(outcome is LooseOutcome.Hit)
    }

    @Test
    fun `loose resolution misses when every spec returns nothing`() {
        val expect = DexHitSelector.Expectation("somewhereElse", "renamed", "int", 1, listOf("java.lang.String"))
        val specs = DexQueryPlan.loose(usingStrings = listOf("tag"), classStrings = null, classFieldTypes = null)
        val outcome = resolveLoose(specs, expect, fetch = { emptyList() }, isDuplicate = { false })
        assertSame(LooseOutcome.Miss, outcome)
    }
}

private class HolderA {
    @Suppress("UNUSED_PARAMETER")
    fun run(s: String): Int = 0
}

private class HolderB {
    @Suppress("UNUSED_PARAMETER")
    fun run(s: String): Int = 0
}

private class ParamHolder1 {
    @Suppress("UNUSED_PARAMETER")
    fun z(a: String, b: Runnable) {}
}

private class ParamHolder2 {
    @Suppress("UNUSED_PARAMETER")
    fun z(a: String, b: java.lang.Integer) {}
}
