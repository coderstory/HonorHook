package top.honorhook.ui.toolbox

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import top.honorhook.ui.scope.SafeCommands

/** 冻结区数据源：PackageManager 免 root 读（12 号：档位=GovernanceList 常量表静态匹配）。 */
data class AppInfo(
    val pkg: String,
    val label: String,
    val isSystem: Boolean,
    /** getApplicationEnabledSetting：0 默认 / 1 开 / 2 DISABLED / 3 DISABLED_USER */
    val enabledState: Int,
    val entry: GovernanceList.Entry?,
) {
    val frozen: Boolean get() = enabledState == 3 || enabledState == 2
    val selectable: Boolean get() = entry != null && !GovernanceList.isRedLine(pkg)
}

object AppRepository {

    fun loadAll(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val result = mutableListOf<AppInfo>()
        for (info in pm.getInstalledPackages(0)) {
            val app: ApplicationInfo = info.applicationInfo ?: continue
            val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            if (!isSystem) continue // 冻结区只管系统包；三方卸载走系统设置
            val state = runCatching {
                pm.getApplicationEnabledSetting(app.packageName)
            }.getOrDefault(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
            val pkg = app.packageName
            result += AppInfo(
                pkg = pkg,
                label = runCatching { pm.getApplicationLabel(app).toString() }.getOrDefault(pkg),
                isSystem = isSystem,
                enabledState = state,
                entry = GovernanceList.lookup(pkg),
            )
        }
        return result.sortedBy { it.label.lowercase() }
    }

    /** 免 root 单包回读（F1 判据：冻结后 enabledState==3）。 */
    fun enabledState(context: Context, pkg: String): Int = runCatching {
        context.packageManager.getApplicationEnabledSetting(pkg)
    }.getOrDefault(0)
}

/** 免 root 读 settings 三表当前值（开关区现值展示；root 不可用时只剩展示）。 */
object SettingsReader {

    fun read(context: Context, table: SafeCommands.Table, key: String): String? = runCatching {
        val resolver = context.contentResolver
        when (table) {
            SafeCommands.Table.SYSTEM -> android.provider.Settings.System.getString(resolver, key)
            SafeCommands.Table.SECURE -> android.provider.Settings.Secure.getString(resolver, key)
            SafeCommands.Table.GLOBAL -> android.provider.Settings.Global.getString(resolver, key)
        }
    }.getOrNull()

    /** 三表全查：返回命中表→值（表定位与双回读共用）。 */
    fun readAll(context: Context, key: String): Map<SafeCommands.Table, String> =
        SafeCommands.Table.entries.mapNotNull { t ->
            read(context, t, key)?.let { t to it }
        }.toMap()
}
