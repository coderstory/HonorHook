package top.honorhook.xposed.target

import org.junit.Assert.assertTrue
import org.junit.Test
import top.honorhook.FeatureCatalog
import java.io.File

/**
 * 注册一致性 guard（#21/P2-2③）：新增 app 的三点人肉同步——
 * `META-INF/xposed/scope.list` ↔ `FeatureCatalog.targets` ↔ `TargetRegistry` ——此前无检查兜着，
 * 漏一侧的症状是「包收到了回调但没有 hook」或「catalog 有功能但注入收不到」，都只能真机发现。
 *
 * scope.list 读取双路：优先 classpath（AGP 单测的运行时 classpath 含 src/main/resources），
 * 回退文件系统相对路径（单测工作目录 = app 模块目录）。
 */
class RegistryConsistencyTest {

    private fun loadScopeList(): List<String> {
        val lines = javaClass.getResourceAsStream("/META-INF/xposed/scope.list")
            ?.bufferedReader()?.readLines()
            ?: run {
                val f = sequenceOf(
                    File("src/main/resources/META-INF/xposed/scope.list"),
                    File("app/app/src/main/resources/META-INF/xposed/scope.list"),
                ).firstOrNull { it.isFile }
                    ?: throw AssertionError("scope.list 读不到（classpath 与文件系统均未命中）")
                f.readLines()
            }
        // 照 HookEntry 侧消费口径：跳过空行与注释行（留扩展余地）
        return lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    }

    @Test
    fun `every catalog target is declared in scope list`() {
        val scope = loadScopeList().toSet()
        assertTrue("scope.list 一行为空集（读取失败？）", scope.isNotEmpty())
        FeatureCatalog.targets.forEach { target ->
            assertTrue(
                "catalog 目标 ${target.packageName} 未在 scope.list 声明——HookEntry 收不到该包回调",
                target.packageName in scope,
            )
        }
    }

    @Test
    fun `every catalog package has registered target`() {
        FeatureCatalog.targets.forEach { target ->
            assertTrue(
                "catalog 包 ${target.packageName} 在 TargetRegistry 无注册目标（register 漏了？）",
                TargetRegistry.find(target.packageName).isNotEmpty(),
            )
        }
    }

    /** ads/tracking/ops 多包注册的宿主（不落在 FeatureCatalog 主目标口径，scope.list 有 → 一并核对）。 */
    @Test
    fun `multi-package sdk hosts registered per scope`() {
        listOf(
            "com.hihonor.calendar",
            "com.hihonor.android.totemweather",
            "com.honor.browser",
            "com.hihonor.quickgame",
            "com.hihonor.youku.video",
            "com.hihonor.cloudmusic",
        ).forEach { pkg ->
            assertTrue(
                "SDK 通杀包 $pkg 在 scope.list 中（全 24 行含它；2026-10-26 T11 校正，原「实测 18 行」为过时数字）且应有注册 hook，find 为空 = 注册漏了",
                TargetRegistry.find(pkg).isNotEmpty(),
            )
        }
    }

    @Test
    fun `every scope list package has registered target`() {
        loadScopeList().forEach { pkg ->
            assertTrue(
                "scope.list 声明了 $pkg 但 TargetRegistry 无注册目标——scope 声明与注册表漂移",
                TargetRegistry.find(pkg).isNotEmpty(),
            )
        }
    }
}
