package top.honorhook.xposed.target

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.target.themestore.ThemeStoreHook

/**
 * 「支持哪些宿主版本」的事实源一致性 guard。
 *
 * 历史坑：版本这件事曾被同时编码在 `FeatureCatalog.extraVersions`、hook 内的 `setOf("…")` 字面量、
 * 表文件名三处。三处不互检时，UI 会给「已适配」绿标而 hook 侧精确套缺失，`pick` 静默落默认套盲挂——
 * 界面说支持，实际按旧表挂，只有 `installed N/M` 能事后发现（installer 曾 `0/11` 就是这个形状）。
 *
 * 现在口径：**FeatureCatalog 是唯一声明处**，hook 的表集合从它派生。本测试双向断言
 * 「catalog 说支持的版本」⇔「hook 真的能精确命中」，并断言声明的挂载策略与实际表结构一致。
 */
class VersionSupportTest {

    /** 每个 hook 暴露的两个测试缝：精确套版本、是否依赖默认套。 */
    private class TableFacts(val pkg: String, val pinned: Set<String>, val hasDefault: Boolean)

    private fun factsOf(): List<TableFacts> = listOf(
        TableFacts(ThemeStoreHook.packageName, ThemeStoreHook.pinnedTableVersions(), ThemeStoreHook.hasDefaultTable()),
        TableFacts(InstallerHook.packageName, InstallerHook.installerTableVersions(), InstallerHook.installerHasDefaultTable()),
        TableFacts(LauncherHook.packageName, LauncherHook.pinnedTableVersions(), LauncherHook.hasDefaultTable()),
    )

    /**
     * 双向对账：catalog 里声明的每个额外版本，必须**要么**有精确套、**要么**显式登记为沿用基线；
     * 反过来 hook 挂的每张精确套，也必须在 catalog 里声明过。任一方向多出来都是漂移。
     */
    @Test
    fun `declared extra versions are covered by tables or baseline sharing`() {
        factsOf().forEach { facts ->
            val target = FeatureCatalog.targets.firstOrNull { it.packageName == facts.pkg }
                ?: throw AssertionError("${facts.pkg} 未在 FeatureCatalog 登记")
            val shared = target.versionsSharingBaseline
            assertEquals(
                "${facts.pkg}: hook 精确套版本不在 catalog extraVersions 里（改了表没改 catalog）",
                emptySet<String>(),
                facts.pinned - target.extraVersions,
            )
            assertEquals(
                "${facts.pkg}: catalog 声明了额外版本，但既没有精确套也没登记 versionsSharingBaseline",
                emptySet<String>(),
                target.extraVersions - facts.pinned - shared,
            )
            assertTrue(
                "${facts.pkg}: versionsSharingBaseline 与精确套重复（同版本不该又建表又沿用基线）",
                (shared intersect facts.pinned).isEmpty(),
            )
            assertTrue(
                "${facts.pkg}: versionsSharingBaseline 含未声明为 extraVersions 的版本",
                target.extraVersions.containsAll(shared),
            )
        }
    }

    /**
     * 策略 ⇔ 表结构：`BlindLatest` 必须有任意版本兜底的默认套（没有就没得盲挂），
     * `Skip` 必须没有默认套（有就成了「悄悄盲挂」）。这条把 A2 的隐式约定钉成可测事实。
     */
    @Test
    fun `mount policy matches table structure`() {
        assertEquals(
            "theme 声明 BlindLatest ⇒ 表列表必须有空集默认套",
            FeatureCatalog.MountPolicy.BlindLatest,
            FeatureCatalog.mountPolicyOf(ThemeStoreHook.packageName),
        )
        assertTrue("theme 缺默认套，BlindLatest 无从盲挂", ThemeStoreHook.hasDefaultTable())

        assertEquals(
            "installer 声明 Skip ⇒ 不得有空集默认套",
            FeatureCatalog.MountPolicy.Skip,
            FeatureCatalog.mountPolicyOf(InstallerHook.packageName),
        )
        assertTrue("installer 出现默认套 = 策略与实现打架", !InstallerHook.installerHasDefaultTable())

        assertEquals(
            "launcher 声明 BlindLatest ⇒ 必须有默认套",
            FeatureCatalog.MountPolicy.BlindLatest,
            FeatureCatalog.mountPolicyOf(LauncherHook.packageName),
        )
        assertTrue("launcher 缺默认套", LauncherHook.hasDefaultTable())
    }

    @Test
    fun `declared versions are non blank and baseline is inside supported set`() {
        FeatureCatalog.targets.filter { it.baselineVersion != null }.forEach { target ->
            assertTrue("${target.packageName} baseline 为空串", target.baselineVersion!!.isNotBlank())
            target.extraVersions.forEach { v ->
                assertTrue("${target.packageName} extraVersion 有空串", v.isNotBlank())
                assertTrue(
                    "${target.packageName} 的 $v 重复声明为 baseline",
                    v != target.baselineVersion,
                )
            }
            assertTrue(
                "${target.packageName} supportedVersions 不含 baseline",
                target.baselineVersion in target.supportedVersions,
            )
        }
    }
}
