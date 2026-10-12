package top.honorhook.xposed.core

import android.content.pm.PackageManager

/**
 * 宿主版本分发：同一目标可声明多套挂载表，运行时按宿主 versionName 选一套。
 * 层级顺序按 CLAUDE.md「跨版本兼容」：能选未混淆目标 / 通用手法 / DexKit 锚点，才轮到版本分发。
 */
object HostVersion {

    /** [versions] 精确匹配版本名；空集合 = 默认套（任何版本兜底）。 */
    class Table<T>(val versions: Set<String>, val build: () -> List<T>)

    /** [tasks] 选中的挂载表；[matched] false = 没有任何一套可用（调用方应打 WARN 并跳过）；
     *  [fallbackTo] 非空 = 精确/默认套均未命中，按显式 fallback 选了表内版本号最大的精确套盲挂（留痕日志用）。 */
    class Pick<T>(val tasks: List<T>, val matched: Boolean, val fallbackTo: String? = null)

    /**
     * 同步读宿主 versionName（不需要 Context）。
     * PackageLoadedParam 时机 Application 常未创建，故走 ActivityThread 的静态 PackageManager。
     */
    fun resolve(apkPath: String?): String? {
        if (apkPath.isNullOrEmpty()) return null
        return runCatching {
            val pm = Class.forName("android.app.ActivityThread")
                .getMethod("currentPackageManager").invoke(null) as? PackageManager ?: return null
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apkPath, 0)?.versionName
        }.getOrNull()
    }

    /**
     * 版本名比较（[FeatureCatalog.Target.supportedVersions] 与 fallback 选表共用，消双处漂移）：
     * 按 '.' 分段逐段比数字前缀（非数字尾缀截断后按 0，如 "5.9.0.0-release"），缺段按 0。
     * a<b 返回负 / 相等返回 0 / a>b 返回正。语义与 FeatureCatalog 原内联实现逐字一致。
     */
    fun compareVersionNames(a: String, b: String): Int {
        val ka = a.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val kb = b.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(ka.size, kb.size)) {
            val c = (ka.getOrElse(i) { 0 }).compareTo(kb.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return 0
    }

    /**
     * 选表三级：① 精确命中（actual 在某套 versions 内）；② 默认套（versions 空 = 任何版本兜底）；
     * ③ [fallback] = true 的显式 opt-in：①② 均未命中时选「表内版本号最大的精确套」盲挂
     * （版本不适配按最新支持版本，[Pick.fallbackTo] 留痕）。默认 false——installer 现有契约
     * （matched=false → 基线源码路径 = 它的「最新支持版本」）不得被改变。
     */
    fun <T> pick(tables: List<Table<T>>, actual: String?, fallback: Boolean = false): Pick<T> {
        if (actual != null) {
            tables.firstOrNull { actual in it.versions }?.let { return Pick(it.build(), true) }
        }
        tables.firstOrNull { it.versions.isEmpty() }?.let { return Pick(it.build(), true) }
        if (fallback) {
            tables.flatMap { t -> t.versions.map { v -> v to t } }
                .maxWithOrNull { (va, _), (vb, _) -> compareVersionNames(va, vb) }
                ?.let { (version, table) -> return Pick(table.build(), true, fallbackTo = version) }
        }
        return Pick(emptyList(), false)
    }
}
