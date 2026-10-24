package top.honorhook.ui.toolbox

import android.content.Context
import java.io.File
import top.honorhook.ui.scope.SafeCommands

/**
 * OTA 哨兵（28 号 §5 / 07 号）：六键保持关值 + 三哨兵键变化=有推送活动。
 * 免 root 走 Settings API 读；每次进工具箱静默比对，变化 → 黄条提示「内核>6.6.118 或
 * SP>2026-08-01 的固件一律拒升」。比完即存当前值，黄条只在有新变化或六键失守时亮。
 */
object OtaSentinel {

    /** 六键（07 号）：现值应全为 0/关；失守 = 有人/云把它打开过。 */
    private val SIX_KEYS: List<Pair<SafeCommands.Table, String>> = listOf(
        SafeCommands.Table.GLOBAL to "hn_system_update_auto_download",
        SafeCommands.Table.GLOBAL to "ouc_first_version_auto_install",
        SafeCommands.Table.GLOBAL to "wifi_auto_update",
        SafeCommands.Table.SECURE to "hn_system_night_upgrade_switch",
        SafeCommands.Table.SECURE to "hn_upgrade_remind",
        SafeCommands.Table.SYSTEM to "set_forceupdate_status",
    )

    /** 三哨兵（07 号）：变化=OTA 推送活动。 */
    private val SENTINEL_KEYS: List<Pair<SafeCommands.Table, String>> = listOf(
        SafeCommands.Table.GLOBAL to "is_hota_search_success",
        SafeCommands.Table.GLOBAL to "ota_last_firmware_version_oobe",
        SafeCommands.Table.GLOBAL to "last_hota_version",
    )

    data class Report(
        val violatedSix: List<String>,
        val sentinelChanged: List<String>,
    ) {
        val shouldWarn: Boolean get() = violatedSix.isNotEmpty() || sentinelChanged.isNotEmpty()
    }

    fun check(context: Context): Report {
        val violated = SIX_KEYS.mapNotNull { (t, k) ->
            val v = SettingsReader.read(context, t, k)
            if (v != null && v != "0") k else null
        }
        val current = SENTINEL_KEYS.mapNotNull { (t, k) ->
            SettingsReader.read(context, t, k)?.let { k to it }
        }.toMap()
        val changed = run {
            val stored = readStored(context)
            current.filter { (k, v) -> stored[k] != null && stored[k] != v }.keys.toList()
        }
        writeStored(context, current)
        return Report(violated, changed)
    }

    private fun storeFile(context: Context) =
        File(context.filesDir, "toolbox/ota-sentinel.txt")

    private fun readStored(context: Context): Map<String, String> {
        val f = storeFile(context)
        if (!f.exists()) return emptyMap()
        return runCatching {
            f.readLines().mapNotNull { l ->
                val i = l.indexOf('=')
                if (i <= 0) null else l.substring(0, i) to l.substring(i + 1)
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    private fun writeStored(context: Context, values: Map<String, String>) {
        runCatching {
            storeFile(context).parentFile?.mkdirs()
            storeFile(context).writeText(values.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }
}
