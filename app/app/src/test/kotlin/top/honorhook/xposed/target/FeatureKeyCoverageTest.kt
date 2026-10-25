package top.honorhook.xposed.target

import org.junit.Assert.assertTrue
import org.junit.Test
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.data.HookPoint
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.util.jar.JarFile

/**
 * feature key 目录覆盖 guard（计划 T9 / P2-3，docs/plans/2026-10-25-audit-remediation.md）：
 * 任何挂载表引用了 FeatureCatalog 未登记的 key，这里必须红。
 *
 * 为什么值得钉：RemoteConfig.isEnabled 对未知 key 的 fallback 是 `catalogDefaults[key] ?: false`
 * （RemoteConfig.kt:39）——与全模块「读不到一律视为开」哲学相反，未登记 key 会**静默关**：
 * 引擎闸门（filterByFeature）把该点剔出 pending，不进 M、不进失败清单，真机判据只表现为 M 变小，
 * 与「宿主版本错位」不可区分。且 KEY_* 常量存在 ≠ 已登记——catalogDefaults 只从 targets.features 建。
 *
 * 挂载点声明四格式（AGENTS.md）与本文覆盖方式的映射：
 * 1. HookPoint 表（含 OucHook 原字符串对模板——已收敛为 OUC_POINTS 行）：反射动态发现
 *    top.honorhook.xposed 下全部 `List<HookPoint>` 属性（对象成员 / 顶层文件类 backing field /
 *    派生表 THEME_POINTS_20_1_65_302 / 计算属性 getter），逐行断言 feature ∈ 目录；新增表自动纳入。
 * 2. 源码 addConst / addForceArg 收点 + SDK 内联 MountTask（ads/tracking/… SimpleTask）：这两种
 *    格式不落表、任务对象不带 feature，key 的唯一引用位是 install() 体内的 RemoteConfig.isEnabled(...)
 *    整目标/分组闸门。key 经 KEY_* 常量编译期内联进调用方，JVM 反射无从枚举声明——由
 *    `no hand-written feature key literals` 按 AGENTS.md「两侧禁止手写 key 字符串」纪律扫源码字面量
 *    兜住（手写裸串是这两格式唯一的打错字向量；常量引用本身由编译期保证存在）。
 *
 * 有意不做「每个 KEY_* 常量都已登记进 targets」断言：KEY_MIH_FACE_FREE 当前是已知未登记态
 * （MiHealthHook 尚未入 TargetRegistry），那属目录卫生口径而非挂载表契约，钉这里会立刻红。
 */
class FeatureKeyCoverageTest {

    /** 一张挂载表（owner=声明类简单名，member=字段/getter 名；points 保留原实例供 identity 去重）。 */
    private data class MountTable(val owner: String, val member: String, val points: List<HookPoint>)

    /** 目录已登记 key（与 RemoteConfig.catalogDefaults 同口径：targets.features，不是 KEY_* 常量集）。 */
    private val registeredKeys: Set<String> by lazy {
        FeatureCatalog.targets.flatMap { it.features }.map { it.key }.toSet()
    }

    /**
     * 已知表清单（发现机制自检锚点）：反射扫不出这些 = classpath 扫描失效，断言会空转变绿——
     * 先红在这里。删表/改名需同步本清单（HookPointTablesTest 的显式表白名单同款纪律）。
     */
    private val knownTables = setOf(
        "OucHook.OUC_POINTS",
        "AppMarketHook.MARKET_POINTS",
        "SysmgrHook.SYSMGR_POINTS",
        "InstallerMap_14_0_22_326Kt.INSTALLER_POINTS_14_0_22_326",
        "InstallerMap_14_0_38_301Kt.INSTALLER_POINTS_14_0_38_301",
        "LauncherMap_18_0_0_234Kt.LAUNCHER_POINTS_18_0_0_234",
        "ThemeStoreMap_20_1_63_305Kt.THEME_POINTS_20_1_63_305",
    )

    // ---------------- 发现①：classpath 扫描 + 反射读全部 List<HookPoint> 属性 ----------------

    /**
     * classpath 扫描发现包下全部类（RegistryConsistencyTest 读 scope.list 的 classpath 优先同款思路；
     * 测试类按名剔除）。**双源**（2026-10-26 实测修正：AGP 单测 classpath 里主源集类在
     * `intermediates/runtime_app_classes_jar/.../classes.jar` 内，jar 无目录 resource，
     * getResources 对其失明——首版只走 getResources 时 7 张已知表全盲，自检锚红即此）：
     * - 源一 getResources：目录形态 classpath（测试类目录即由此来）；
     * - 源二 java.class.path 逐项：目录则走查包子目录，jar 则枚举 entries（Gradle worker
     *   以 -cp 启动，该属性实测完整含 runtime_app_classes_jar）。
     * 两源类名并集去重后 forName；扫不到类时由已知表自检兜底变红，不会静默绿。
     */
    private fun classesUnder(packageName: String): List<Class<*>> {
        val loader = javaClass.classLoader
        val prefix = packageName.replace('.', '/')
        val names = sortedSetOf<String>()

        fun record(relPath: String) {
            if (relPath.endsWith(".class")) {
                names += relPath.removeSuffix(".class").replace('/', '.')
            }
        }

        val dirs = loader.getResources(prefix)
        while (dirs.hasMoreElements()) {
            val url = dirs.nextElement()
            if (url.protocol != "file") continue
            val root = File(url.toURI())
            if (!root.isDirectory) continue
            root.walkTopDown().forEach { if (it.isFile) record(it.relativeTo(root).invariantSeparatorsPath) }
        }

        System.getProperty("java.class.path")?.split(File.pathSeparator)?.forEach { entry ->
            runCatching {
                if (entry.endsWith(".jar", ignoreCase = true)) {
                    JarFile(entry).use { jar ->
                        jar.entries().asSequence()
                            .filter { it.name.startsWith(prefix) && it.name.endsWith(".class") }
                            .forEach { record(it.name) }
                    }
                } else {
                    val root = File(entry, prefix)
                    if (root.isDirectory) {
                        root.walkTopDown().forEach { if (it.isFile) record(it.relativeTo(root).invariantSeparatorsPath) }
                    }
                }
            }
        }

        return names
            .filterNot { it.substringBefore('$').endsWith("Test") }
            .mapNotNull { runCatching { Class.forName(it, false, loader) }.getOrNull() }
    }

    /** 泛型形态是否为 List<HookPoint>（backing field 与零参 getter 两种挂表形状共用）。 */
    private fun isHookPointList(type: Type): Boolean {
        val pt = type as? ParameterizedType ?: return false
        return pt.rawType == List::class.java && pt.actualTypeArguments.singleOrNull() == HookPoint::class.java
    }

    private fun hookPointTables(): List<MountTable> {
        val found = mutableListOf<MountTable>()
        for (clazz in classesUnder("top.honorhook.xposed")) {
            // Kotlin object / companion 的实例（顶层文件类全静态，instance 为 null 属预期）
            val instance = clazz.declaredFields
                .firstOrNull { it.name == "INSTANCE" && Modifier.isStatic(it.modifiers) }
                ?.let { runCatching { it.get(null) }.getOrNull() }
            // 形状一：val 属性的 backing field（`= listOf(...)` 初始化式表；私有字段须 isAccessible）
            for (f in clazz.declaredFields) {
                if (!isHookPointList(f.genericType)) continue
                f.isAccessible = true
                val v = runCatching {
                    if (Modifier.isStatic(f.modifiers)) f.get(null) else instance?.let { f.get(it) }
                }.getOrNull()
                record(found, clazz.simpleName, f.name, v)
            }
            // 形状二：零参 getter（计算属性 `get() =` 无 backing field；@JvmStatic 合成访问器已剔）
            for (m in clazz.declaredMethods) {
                if (m.parameterCount != 0 || m.isSynthetic || !isHookPointList(m.genericReturnType)) continue
                m.isAccessible = true
                val v = runCatching {
                    if (Modifier.isStatic(m.modifiers)) m.invoke(null) else instance?.let { m.invoke(it) }
                }.getOrNull()
                record(found, clazz.simpleName, m.name, v)
            }
        }
        // 字段与 getter 指向同一实例（`val x = listOf(...)`）→ identity 去重；派生表是新实例，保留独立核对
        return found.distinctBy { System.identityHashCode(it.points) }.sortedBy { it.owner + it.member }
    }

    private fun record(out: MutableList<MountTable>, owner: String, member: String, value: Any?) {
        val list = value as? List<*> ?: return
        if (list.isEmpty() || list.any { it !is HookPoint }) return
        @Suppress("UNCHECKED_CAST")
        out += MountTable(owner, member, list as List<HookPoint>)
    }

    @Test
    fun `reflection discovery finds every known hookpoint table`() {
        // getter 形态归一到属性名（getOUC_POINTS → OUC_POINTS；字段名无 get 前缀不受影响）
        val discovered = hookPointTables().map { "${it.owner}.${it.member.removePrefix("get")}" }.toSet()
        val missing = knownTables - discovered
        assertTrue(
            "classpath 反射未发现已知挂载表：$missing —— 发现机制失效（输出目录/类名漂移？），" +
                "本文件的 key 断言会随之空转，必须先修发现再谈覆盖",
            missing.isEmpty(),
        )
    }

    @Test
    fun `every hookpoint table feature key is registered in the catalog`() {
        assertTrue("FeatureCatalog.targets 未装配任何功能（注册 key 集为空）", registeredKeys.isNotEmpty())
        val offenders = hookPointTables().flatMap { t ->
            t.points.filter { it.feature.isNotEmpty() && it.feature !in registeredKeys }
                .map { "${t.owner}.${t.member}[${it.id}] → \"${it.feature}\"" }
        }
        assertTrue(
            "挂载表引用了 FeatureCatalog 未登记的 feature key（RemoteConfig.isEnabled 回退 false ⇒ " +
                "引擎闸门静默剔除该点、不进 M、不进失败清单）：$offenders",
            offenders.isEmpty(),
        )
    }

    // ---------------- 发现②：install() 体内闸门（addConst / SDK 内联 MountTask 格式的 key 引用位） ----------------

    /** `isEnabled("`：闸门调用位手写裸串（含换行后置串的写法）。 */
    private val rawKeyInGate = Regex("isEnabled\\(\\s*\"")

    /** `feature = "非空"`：任务/表行构造位手写裸串（`feature = ""` = 常挂点，合法放行）。 */
    private val rawKeyInFeature = Regex("\\bfeature\\s*=\\s*\"[^\"]+\"")

    private fun hookSourceFiles(): List<File> {
        val root = sequenceOf(
            File("src/main/kotlin/top/honorhook/xposed"),          // 单测 cwd = app 模块目录
            File("app/app/src/main/kotlin/top/honorhook/xposed"),  // 仓库根 cwd 兜底（RegistryConsistencyTest 双路同款）
        ).firstOrNull { it.isDirectory }
            ?: throw AssertionError("hook 源码树读不到（模块/仓库两级相对路径均未命中，cwd 漂移？）")
        return root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
    }

    @Test
    fun `no hand-written feature key literals in hook source`() {
        val files = hookSourceFiles()
        // 空转守卫：hook 树当前 58 个 .kt；跌破下限 = 扫描根失效，断言不许静默绿
        assertTrue("hook 源码树只扫到 ${files.size} 个 .kt（下限 30），扫描根疑似失效", files.size >= 30)
        val offenders = files.flatMap { f ->
            val text = f.readText()
            val lines = text.lines()
            (rawKeyInGate.findAll(text) + rawKeyInFeature.findAll(text)).mapNotNull { m ->
                val lineNo = text.substring(0, m.range.first).count { it == '\n' } + 1
                val line = lines[lineNo - 1].trim()
                // 整行注释不参与（KDoc 示例串，如 MountOutcome 的 feature="A" 文案即此类）
                if (line.startsWith("*") || line.startsWith("//") || line.startsWith("/*")) return@mapNotNull null
                "${f.name}:$lineNo  ${m.value}"
            }.toList()
        }
        assertTrue(
            "hook 源码出现手写 feature key 字面量（AGENTS.md：两侧禁止手写 key，必须走 FeatureCatalog.KEY_* " +
                "常量——裸串打错字零编译错误，未知 key 被 RemoteConfig 静默回 false）：$offenders",
            offenders.isEmpty(),
        )
    }
}
