package top.honorhook

/**
 * 版本号比较（共享层纯函数，UI 侧与 hook 侧共用同一实现）。
 *
 * 放在 `top.honorhook` 而不是 `xposed.core`：FeatureCatalog（UI 侧查表）与 UpdateChecker（UI 侧
 * 比新版本）反向 import hook 侧是依赖倒挂；版本比较是两边共用的纯逻辑，属共享层。
 * 迁移前实现在 `xposed.core.HostVersion.compareVersionNames`，语义逐字保留（见 [compare]）。
 */
object VersionName {

    /**
     * 版本名比较（按 '.' 分段逐段比数字前缀，非数字尾缀截断后按 0，如 "5.9.0.0-release"），缺段按 0。
     * a<b 返回负 / 相等返回 0 / a>b 返回正。
     */
    fun compare(a: String, b: String): Int {
        val ka = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val kb = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(ka.size, kb.size)) {
            val c = (ka.getOrElse(i) { 0 }).compareTo(kb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }
}
