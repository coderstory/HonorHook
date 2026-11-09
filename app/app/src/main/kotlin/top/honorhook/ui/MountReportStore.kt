package top.honorhook.ui

import org.json.JSONObject
import top.honorhook.FeatureCatalog
import top.honorhook.xposed.core.MountReport

/**
 * UI 侧读挂载判据（hook 侧由 `MountReport.publish` 写同一 group 的远端 prefs）。
 *
 * 拿到的是**该宿主进程最近一次挂载会话**的汇总：`ok/total` + 实测版本 + 少量失败描述。
 * 没有记录 = 宿主还没被拉起过（或模块刚装），UI 显示「未上报」而不是猜。
 */
object MountReportStore {

    data class Report(
        val hook: String,
        val hostPackage: String,
        val hostVersion: String?,
        val ok: Int,
        val total: Int,
        val failed: List<String>,
        val at: Long,
    ) {
        val complete: Boolean get() = total > 0 && ok >= total
        val ratio: String get() = "$ok/$total"
    }

    fun reportFor(hostPackage: String): Report? {
        val service = FrameworkState.current() ?: return null
        val raw = runCatching {
            service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP)
                .getString(MountReport.keyOf(hostPackage), null)
        }.getOrNull() ?: return null
        return runCatching { parse(raw) }.getOrNull()
    }

    internal fun parse(raw: String): Report? {
        val json = JSONObject(raw)
        val total = json.optInt("total", 0)
        if (total <= 0) return null
        val failed = json.optJSONArray("failed")
        return Report(
            hook = json.optString("hook"),
            hostPackage = json.optString("pkg"),
            hostVersion = json.optString("ver").takeIf { it.isNotBlank() },
            ok = json.optInt("ok", 0),
            total = total,
            failed = buildList { if (failed != null) for (i in 0 until failed.length()) add(failed.optString(i)) },
            at = json.optLong("at", 0L),
        )
    }
}
