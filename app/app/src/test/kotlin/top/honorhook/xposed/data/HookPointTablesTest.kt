package top.honorhook.xposed.data

import org.junit.Assert.assertTrue
import org.junit.Test
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.target.INSTALLER_POINTS_14_0_22_326
import top.honorhook.xposed.target.INSTALLER_POINTS_14_0_38_301
import top.honorhook.xposed.target.InstallerHook
import top.honorhook.xposed.target.LAUNCHER_POINTS_18_0_0_234
import top.honorhook.xposed.target.LauncherHook
import top.honorhook.xposed.target.OucHook
import top.honorhook.xposed.target.SysmgrHook
import top.honorhook.xposed.target.themestore.THEME_POINTS_20_1_63_305
import top.honorhook.xposed.target.themestore.MountKind as ThemeMountKind
import top.honorhook.xposed.target.InstallerHook.MountKind as InstallerMountKind

/**
 * HookPoint 挂载表的护栏测试（评审 #18/#20）。
 *
 * kind 手滑 ⇒ MountKind.valueOf 抛 ⇒ pending 构建期炸 ⇒ 整目标不挂载仅一条 ERROR——这里在 JVM 期拦住。
 * feature 裸字符串改名零编译错误、miss 走默认 true——这里钉死 feature 必须命中 FeatureCatalog KEY 常量集。
 * extraVersions ↔ installerTables 三处字符串耦合（评审 #20）——双向交叉校验。
 */
class HookPointTablesTest {

    /** FeatureCatalog 全部 KEY_* 常量（const val 编译为 object 上的静态字段，Java 反射取值免 kotlin-reflect）。 */
    private val allFeatureKeys: Set<String> = FeatureCatalog::class.java.fields
        .filter { it.type == String::class.java && it.name.startsWith("KEY_") }
        .map { it.get(null) as String }
        .toSet()

    /** group 契约格式：大写字母 + 数字 + 可选小写后缀（容 "A11"/"H2b"/"S2b"）；"" = 载体/探针行。 */
    private val groupRegex = Regex("^[A-Z][0-9]+[a-z]?$")

    private fun assertValidFeatures(rows: List<Pair<String, String>>) {
        rows.forEach { (id, feature) ->
            assertTrue(
                "$id feature=\"$feature\" 不在 FeatureCatalog KEY 常量集（裸字符串 = key 改名零编译错误）",
                feature.isEmpty() || feature in allFeatureKeys,
            )
        }
    }

    private fun assertValidGroups(rows: List<Pair<String, String>>) {
        rows.forEach { (id, group) ->
            assertTrue(
                "$id group=\"$group\" 不匹配功能点 id 格式 ^[A-Z][0-9]+[a-z]?$",
                group.isEmpty() || groupRegex.matches(group),
            )
        }
    }

    // ---------------- ① kind / feature / group 合法性 ----------------

    @Test
    fun `theme table kinds features and groups are valid`() {
        val themeKinds = ThemeMountKind.values().map { it.name }.toSet()
        val rows = THEME_POINTS_20_1_63_305.map { it.id to it }
        rows.forEach { (id, p) ->
            assertTrue(
                "$id kind=\"${p.kind}\" 不是 ThemeStoreHook MountKind 值（valueOf 构建期必炸）",
                p.kind in themeKinds,
            )
        }
        assertValidFeatures(THEME_POINTS_20_1_63_305.map { it.id to it.feature })
        assertValidGroups(THEME_POINTS_20_1_63_305.map { it.id to it.group })
    }

    @Test
    fun `installer table kinds features and groups are valid`() {
        val installerKinds = InstallerMountKind.values().map { it.name }.toSet()
        listOf(INSTALLER_POINTS_14_0_22_326, INSTALLER_POINTS_14_0_38_301).forEach { table ->
            table.forEach { p ->
                assertTrue(
                    "${p.id} kind=\"${p.kind}\" 不是 InstallerHook MountKind 值（valueOf 构建期必炸）",
                    p.kind in installerKinds,
                )
            }
            assertValidFeatures(table.map { it.id to it.feature })
            assertValidGroups(table.map { it.id to it.group })
        }
    }

    @Test
    fun `installer table ids are unique`() {
        // id 是判据汇总（judgeCount / 失败清单）的键：重复会让两个挂载点在日志里变一个，M 对不上 N。
        listOf(INSTALLER_POINTS_14_0_22_326, INSTALLER_POINTS_14_0_38_301).forEach { table ->
            val ids = table.map { it.id }
            assertTrue("挂载点 id 重复: ${ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys}", ids.size == ids.toSet().size)
        }
    }

    // ---------------- ② extraVersions ↔ installerTables 交叉 ----------------

    @Test
    fun `extraVersions and installerTables agree both ways`() {
        val extra = FeatureCatalog.extraVersionsOf("com.android.packageinstaller")
        val tableVersions = InstallerHook.installerTables.flatMap { it.versions }.toSet()
        assertTrue("installerTables 不应为空（空集 = 版本分发静默失效）", tableVersions.isNotEmpty())
        extra.forEach { v ->
            assertTrue("extraVersions 的 $v 不在任何 installerTables versions 内（单侧改动，评审 #20）", v in tableVersions)
        }
        tableVersions.forEach { v ->
            assertTrue("installerTables versions 的 $v 未回写 FeatureCatalog.extraVersions（单侧改动，评审 #20）", v in extra)
        }
    }

    // ---------------- ③ launcher / sysmgr / ouc 三张表的护栏 ----------------

    /**
     * sysmgr / ouc **没有 MountKind 枚举**：intercept 按 `returnType` 分派，表里的 kind 只是行自描述。
     * 这里锁死它们当前只允许 CONST——将来新增 kind 必须同时改分派与本断言。
     */
    private val descriptiveOnlyKinds = setOf("CONST")

    @Test
    fun `launcher table kinds features and groups are valid`() {
        val launcherKinds = LauncherHook.MountKind.values().map { it.name }.toSet()
        LAUNCHER_POINTS_18_0_0_234.forEach { p ->
            assertTrue(
                "${p.id} kind=\"${p.kind}\" 不是 LauncherHook MountKind 值（valueOf 构建期必炸）",
                p.kind in launcherKinds,
            )
        }
        assertValidFeatures(LAUNCHER_POINTS_18_0_0_234.map { it.id to it.feature })
        assertValidGroups(LAUNCHER_POINTS_18_0_0_234.map { it.id to it.group })
    }

    @Test
    fun `sysmgr table kinds features and groups are valid`() {
        SysmgrHook.SYSMGR_POINTS.forEach { p ->
            assertTrue(
                "${p.id} kind=\"${p.kind}\" 不在允许集 $descriptiveOnlyKinds",
                p.kind in descriptiveOnlyKinds,
            )
        }
        assertValidFeatures(SysmgrHook.SYSMGR_POINTS.map { it.id to it.feature })
        assertValidGroups(SysmgrHook.SYSMGR_POINTS.map { it.id to it.group })
    }

    @Test
    fun `ouc table kinds features and groups are valid`() {
        OucHook.OUC_POINTS.forEach { p ->
            assertTrue(
                "${p.id} kind=\"${p.kind}\" 不在允许集 $descriptiveOnlyKinds",
                p.kind in descriptiveOnlyKinds,
            )
        }
        assertValidFeatures(OucHook.OUC_POINTS.map { it.id to it.feature })
        assertValidGroups(OucHook.OUC_POINTS.map { it.id to it.group })
    }

    @Test
    fun `launcher sysmgr and ouc table ids are unique`() {
        // id 是判据汇总（judgeCount / 失败清单）的键：重复会让两个挂载点在日志里变一个，M 对不上 N。
        listOf(
            "launcher" to LAUNCHER_POINTS_18_0_0_234,
            "sysmgr" to SysmgrHook.SYSMGR_POINTS,
            "ouc" to OucHook.OUC_POINTS,
        ).forEach { (name, table) ->
            val ids = table.map { it.id }
            val dup = ids.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            assertTrue("$name 挂载点 id 重复: $dup", dup.isEmpty())
        }
    }

    // ---------------- ④ 表 → 任务：字段透传不丢 ----------------

    @Test
    fun `sysmgr tasks carry table group and feature`() {
        SysmgrHook.SYSMGR_POINTS.forEach { p ->
            val task = with(SysmgrHook) { p.toTask() }
            assertTrue("${p.id} feature 透传丢失: 表=${p.feature} 任务=${task.feature}", task.feature == p.feature)
            assertTrue("${p.id} group 透传丢失: 表=${p.group} 任务=${task.group}", task.group == p.group)
        }
    }

    @Test
    fun `ouc tasks carry table group and feature`() {
        OucHook.OUC_POINTS.forEach { p ->
            val task = with(OucHook) { p.toTask() }
            assertTrue("${p.id} feature 透传丢失: 表=${p.feature} 任务=${task.feature}", task.feature == p.feature)
            assertTrue("${p.id} group 透传丢失: 表=${p.group} 任务=${task.group}", task.group == p.group)
        }
    }

    @Test
    fun `launcher tasks carry table kind group and feature`() {
        LAUNCHER_POINTS_18_0_0_234.forEach { p ->
            val task = with(LauncherHook) { p.toMountTask() }
            assertTrue("${p.id} kind 透传丢失: 表=${p.kind} 任务=${task.kind.name}", task.kind.name == p.kind)
            assertTrue("${p.id} feature 透传丢失: 表=${p.feature} 任务=${task.feature}", task.feature == p.feature)
            assertTrue("${p.id} group 透传丢失: 表=${p.group} 任务=${task.group}", task.group == p.group)
        }
    }

    // ---------------- ⑤ CONST 值的类型必须与声明返回类型一致 ----------------

    /**
     * CONST = 常量短路：intercept 直接返回表里的 `value`。
     * value 类型与宿主方法返回类型不符 ⇒ libxposed 102 硬约束被违反（宿主收到错类型）。
     * 「值不由表给」的形态（void / List / Bundle）不在此断言范围，但必须**显式列出**
     * （见 [constValueNotFromTable]）——否则白名单会把未知类型静默跳过，断言出现空洞。
     */
    private fun allowedConstValueTypes(returnType: String): Set<Class<*>> = when (returnType) {
        "boolean" -> setOf(Boolean::class.javaObjectType)
        "int", "java.lang.Integer" -> setOf(Int::class.javaObjectType)
        "long", "java.lang.Long" -> setOf(Long::class.javaObjectType)
        "double", "java.lang.Double" -> setOf(Double::class.javaObjectType)
        "java.lang.String" -> setOf(String::class.java)
        else -> emptySet()
    }

    /** 值在 JVM 期无从比对的 CONST 形态（表里给了值，但类型无法核）：必须显式列出（不许静默跳过）。 */
    private val constValueNotFromTable = setOf(
        "java.util.List",
    )

    /**
     * CONST 行但表里 `value = null` 且返回类型不是 void ⇒ 值由**目标侧硬编码**（表不提供常量），
     * JVM 期无从比对类型。必须显式登记（不许静默跳过）；新增此类行要同步此清单。
     */
    private val constValueFromTargetSide = setOf(
        // theme C7：值由 enumRef 反射取宿主枚举常量（PERMANENT）
        "theme:ThemeResManager.getThemeApplyState",
        // sysmgr：hook 恒返 true（宿主自带 hide_virus 总开关），常量写在目标侧
        "sysmgr:AntivirusTools.hideVirusPrefer",
        // sysmgr：目标侧构造同构 Bundle 返回（result_code=0），结构在目标侧
        "sysmgr:CheckUninstallApkCaller.scanClean",
    )

    /** 全部挂载表（护栏覆盖面：theme / installer×2 / launcher / sysmgr / ouc）。 */
    private fun allTables(): List<Pair<String, List<HookPoint>>> = listOf(
        "theme" to THEME_POINTS_20_1_63_305,
        "installer" to INSTALLER_POINTS_14_0_22_326,
        "installer38" to INSTALLER_POINTS_14_0_38_301,
        "launcher" to LAUNCHER_POINTS_18_0_0_234,
        "sysmgr" to SysmgrHook.SYSMGR_POINTS,
        "ouc" to OucHook.OUC_POINTS,
    )

    @Test
    fun `const values match declared return types`() {
        allTables().forEach { (name, table) ->
            table.forEach { p ->
                if (p.kind != "CONST") return@forEach
                if (p.value == null) {
                    if (p.returnType == "void") return@forEach   // void 无值可校
                    // 值由目标侧硬编码（表里 value=null）⇒ 无从比对类型；必须显式登记。
                    assertTrue(
                        "$name ${p.id} CONST 的 value=null（值由目标侧硬编码）但不在已知清单 $constValueFromTargetSide",
                        p.id in constValueFromTargetSide,
                    )
                    return@forEach
                }
                val allowed = allowedConstValueTypes(p.returnType)
                if (allowed.isEmpty()) {
                    // 跳过必须是「已知且明确跳过」：不在白名单里的类型由下面的穷举测试先红。
                    assertTrue(
                        "$name ${p.id} CONST 返回类型=${p.returnType} 既不在类型白名单、也不在明确跳过集",
                        p.returnType in constValueNotFromTable,
                    )
                    return@forEach
                }
                val v = p.value
                assertTrue(
                    "$name ${p.id} CONST 声明返回类型=${p.returnType}，但 value=$v（${v.javaClass}）不在允许类型 $allowed",
                    v.javaClass in allowed,
                )
            }
        }
    }

    // ---------------- ⑥ CONST 返回类型穷举（白名单不许有静默跳过的空洞） ----------------

    @Test
    fun `const return types are all known`() {
        // 类型断言（allowedConstValueTypes）是白名单：新增一种返回类型若不同时更新白名单，
        // 那一行会被静默跳过 ⇒ 断言覆盖状态不可知。这里把「出现过的 CONST 返回类型集合」钉死。
        val known = setOf(
            "boolean", "double", "int", "long",
            "java.lang.Integer", "java.lang.Long", "java.lang.String",
            // theme C7：宿主自定义类型（值由 enumRef 取），JVM 期无从比对
            "com.hihonor.android.thememanager.theme.apply.ThemeApplyState",
            // sysmgr scanClean：宿主 Bundle 类型（结构在目标侧构造）
            "android.os.Bundle",
            // void：receiver 类 CONST 行（无值可校）
            "void",
        ) + constValueNotFromTable
        val actual = allTables().flatMap { (_, table) -> table.filter { it.kind == "CONST" } }
            .map { it.returnType }
            .toSet()
        val unknown = actual - known
        assertTrue(
            "CONST 行出现未知返回类型（类型断言会静默跳过 ⇒ 断言空洞）：$unknown",
            unknown.isEmpty(),
        )
    }

    // ---------------- ⑦ 表里的 feature key 必须在目录里注册 ----------------

    /**
     * 表里的 feature key 只被 `RemoteConfig.isEnabled` 消费，而它的目录默认取不到**未注册**的 key
     * ⇒ 回退 false ⇒ 该挂载点被引擎闸门剔除（不进 M、不进失败清单，判据行只表现为 M 变小）。
     * 这是唯一会「静默少挂一个点」的表字段（KEY_* 常量存在 ≠ 目录已注册），必须在 JVM 期拦住。
     */
    @Test
    fun `table feature keys are registered in the catalog`() {
        val registered = FeatureCatalog.targets.flatMap { it.features }.map { it.key }.toSet()
        allTables().forEach { (name, table) ->
            table.forEach { p ->
                if (p.feature.isEmpty()) return@forEach
                assertTrue(
                    "$name ${p.id} feature=\"${p.feature}\" 未在 FeatureCatalog.targets 注册" +
                        "（isEnabled 回退 false ⇒ 该点被引擎闸门静默剔除）",
                    p.feature in registered,
                )
            }
        }
    }
}
