package top.honorhook.xposed.core

import android.content.Context
import top.honorhook.VersionName

/**
 * 宿主版本分发：同一目标可声明多套挂载表，运行时按宿主 versionName 选一套。
 * 层级顺序按 CLAUDE.md「跨版本兼容」：能选未混淆目标 / 通用手法 / DexKit 锚点，才轮到版本分发。
 */
object HostVersion {

    /** [versions] 精确匹配版本名；空集合 = 默认套（任何版本兜底）。 */
    class Table<T>(val versions: Set<String>, val build: () -> List<T>)

    /** [tasks] 选中的挂载表；[matched] false = 没有任何一套可用（调用方应打 WARN 并跳过）；
     *  [fallbackTo] 非空 = 精确/默认套均未命中，按显式 fallback 选了表内版本号最大的精确套盲挂（留痕日志用）；
     *  [matchedVersion] = 选中表对应的版本号（精确命中 = 命中版本；fallback 盲挂 = 盲挂所用版本；
     *  默认套（空集）= null）——调用方拿它当宿主版本自检的期望值，避免选中的表与自检基线打架。 */
    class Pick<T>(
        val tasks: List<T>,
        val matched: Boolean,
        val fallbackTo: String? = null,
        val matchedVersion: String? = null,
    )

    /**
     * 读宿主 versionName（选表用）。
     *
     * 两条取法，**反射目标全是 public 方法、查询全是公开 API**（不碰 hidden 成员、不需要绕过黑名单）：
     * ① `ActivityThread.currentActivityThread().getSystemContext().packageManager` +
     *    `getPackageArchiveInfo(apkPath, 0)`：系统上下文在 `attach()` 后即可用
     *    （`ContextImpl.createSystemContext` 惰性创建，**不依赖宿主 Application**）⇒ 无时机依赖；
     * ② `ActivityThread.currentApplication.context.packageManager.getPackageInfo(packageName, 0)`：
     *    公开路径，但宿主 Application 创建之后才有值（package-loaded 回调早于 Application 创建）。
     *
     * ①② 各失败各吞，返回 null = 版本没读到（调用方按"读不到版本"处理）。
     */
    fun resolveHostVersion(apkPath: String?, packageName: String): String? {
        versionFromSystemContext(apkPath)?.let { return it }
        if (packageName.isEmpty()) return null
        return runCatching {
            val app = Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication").invoke(null) as? Context ?: return null
            @Suppress("DEPRECATION")
            app.packageManager.getPackageInfo(packageName, 0)?.versionName
        }.getOrNull()
    }

    /** 取法①：系统上下文的 PackageManager 直读已安装 APK 磁盘（不依赖宿主 Application，见 [resolveHostVersion]）。 */
    private fun versionFromSystemContext(apkPath: String?): String? {
        if (apkPath.isNullOrEmpty()) return null
        return runCatching {
            val at = Class.forName("android.app.ActivityThread")
                .getMethod("currentActivityThread").invoke(null) ?: return null
            val sys = at.javaClass.getMethod("getSystemContext").invoke(at) as? Context ?: return null
            @Suppress("DEPRECATION")
            sys.packageManager.getPackageArchiveInfo(apkPath, 0)?.versionName
        }.getOrNull()
    }

    /**
     * 选表三级：① 精确命中（actual 在某套 versions 内）；② 默认套（versions 空 = 任何版本兜底）；
     * ③ [fallback] = true 的显式 opt-in：①② 均未命中时选「表内版本号最大的精确套」盲挂
     * （版本不适配按最新支持版本，[Pick.fallbackTo] 留痕）。默认 false——installer 现有契约
     * （matched=false → 基线源码路径 = 它的「最新支持版本」）不得被改变。
     * [Pick.matchedVersion] 随级别赋值：精确命中 = 命中版本，fallback = 盲挂版本，默认套 = null。
     */
    fun <T> pick(tables: List<Table<T>>, actual: String?, fallback: Boolean = false): Pick<T> {
        if (actual != null) {
            tables.firstOrNull { actual in it.versions }?.let { return Pick(it.build(), true, matchedVersion = actual) }
        }
        tables.firstOrNull { it.versions.isEmpty() }?.let { return Pick(it.build(), true) }
        if (fallback) {
            tables.flatMap { t -> t.versions.map { v -> v to t } }
                .maxWithOrNull { (va, _), (vb, _) -> VersionName.compare(va, vb) }
                ?.let { (version, table) ->
                    return Pick(table.build(), true, fallbackTo = version, matchedVersion = version)
                }
        }
        return Pick(emptyList(), false)
    }
}
