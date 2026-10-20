package top.honorhook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号比较（共享层 [VersionName]）的 JVM 回归。
 *
 * 语义与迁移前的 `xposed.core.HostVersion.compareVersionNames` 逐字一致（迁移只换归属，不换语义）：
 * 按 '.' 分段逐段比数字前缀，非数字尾缀截断后按 0，缺段按 0。
 * 放在 `top.honorhook` 共享层的原因：FeatureCatalog（UI 侧）与 UpdateChecker 反向 import hook 侧
 * 是依赖倒挂，版本比较是两边共用的纯逻辑。
 */
class VersionNameTest {

    @Test
    fun `equal versions compare equal`() {
        assertEquals(0, VersionName.compare("14.0.37.301", "14.0.37.301"))
    }

    @Test
    fun `higher segment wins`() {
        assertTrue(VersionName.compare("14.0.37.301", "14.0.22.326") > 0)
        assertTrue(VersionName.compare("14.0.22.326", "14.0.37.301") < 0)
        assertTrue(VersionName.compare("16.1.0", "16.0.9") > 0)
    }

    @Test
    fun `missing segments count as zero`() {
        assertEquals(0, VersionName.compare("16.1", "16.1.0"))
        assertTrue(VersionName.compare("16.2", "16.1.9") > 0)
    }

    @Test
    fun `non numeric suffix is truncated`() {
        assertEquals(0, VersionName.compare("5.9.0.0-release", "5.9.0.0"))
        assertTrue(VersionName.compare("5.9.0.1-release", "5.9.0.0") > 0)
    }
}
