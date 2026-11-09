package top.honorhook.xposed.core

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONObject

/**
 * 挂载判据出口（`installed N/M` 从日志走进 UI）。
 *
 * 现状问题：判据只出现在 LSPosed 框架日志里，用户/开发者要看「某个目标到底挂上没挂上」必须去找
 * `/data/adb/lspd/log/modules_*.log`。凡是宿主 OTA 换了混淆名，界面依旧显示「已支持」，功能却是死的，
 * 只能靠事后翻日志发现（installer 的 `0/11` 事故就是这个形状）。
 *
 * 通路：挂载会话结束 → 写远端 prefs（与开关同一个 group，key = `mount_report:<宿主包名>`）→ UI 侧
 * `MountReportStore` 读出来显示。只写一行 JSON，任一环失败都只记 WARN，绝不影响挂载本身。
 */
internal object MountReport {

    const val KEY_PREFIX = "mount_report:"

    fun keyOf(hostPackage: String): String = KEY_PREFIX + hostPackage

    fun encode(
        hookName: String,
        hostPackage: String,
        actualVersion: String?,
        ok: Int,
        total: Int,
        failed: List<String>,
        at: Long = System.currentTimeMillis(),
    ): String = JSONObject().apply {
        put("hook", hookName)
        put("pkg", hostPackage)
        put("ver", actualVersion ?: "")
        put("ok", ok)
        put("total", total)
        put("at", at)
        put("failed", org.json.JSONArray().also { arr -> failed.take(5).forEach(arr::put) })
    }.toString()

    /**
     * 写远端 prefs。[prefs] 可能为 null（framework 没提供）⇒ 静默跳过。
     *
     * 为什么写远端而不是本地文件：宿主进程与模块 App 是两个 uid，本地 prefs 互相看不见；
     * 远端 prefs 是 LSPosed 维护的跨进程通道，也是开关下发用的同一条路。
     */
    fun publish(
        prefs: SharedPreferences?,
        hookName: String,
        hostPackage: String,
        actualVersion: String?,
        ok: Int,
        total: Int,
        failed: List<String>,
    ) {
        if (prefs == null || total <= 0) return
        runCatching {
            prefs.edit()
                .putString(keyOf(hostPackage), encode(hookName, hostPackage, actualVersion, ok, total, failed))
                .apply()
        }.onFailure { Log.w(MountEngine.TAG, "挂载判据上报失败（不影响挂载）: ${it.message}") }
    }
}
