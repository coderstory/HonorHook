package top.honorhook.xposed.core

import android.content.pm.PackageManager

/**
 * 宿主版本分发：同一目标可声明多套挂载表，运行时按宿主 versionName 选一套。
 * 层级顺序按 CLAUDE.md「跨版本兼容」：能选未混淆目标 / 通用手法 / DexKit 锚点，才轮到版本分发。
 */
object HostVersion {

    /** [versions] 精确匹配版本名；空集合 = 默认套（任何版本兜底）。 */
    class Table<T>(val versions: Set<String>, val build: () -> List<T>)

    /** [tasks] 选中的挂载表；[matched] false = 没有任何一套可用（调用方应打 WARN 并跳过）。 */
    class Pick<T>(val tasks: List<T>, val matched: Boolean)

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

    fun <T> pick(tables: List<Table<T>>, actual: String?): Pick<T> {
        if (actual != null) {
            tables.firstOrNull { actual in it.versions }?.let { return Pick(it.build(), true) }
        }
        tables.firstOrNull { it.versions.isEmpty() }?.let { return Pick(it.build(), true) }
        return Pick(emptyList(), false)
    }
}
