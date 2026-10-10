package top.honorhook.ui

import android.content.Context
import android.content.SharedPreferences
import top.honorhook.FeatureCatalog

/**
 * 功能开关存储（宿主侧）——本地镜像 + 远端下发双写（照 LSPFRIDA ScriptStore 通道模型）。
 *
 * - 本地 SharedPreferences：UI 即时读取显示（框架未连接也可用）；
 * - 远端 prefs（FeatureCatalog.REMOTE_GROUP）：hook 侧在目标进程只读此通道。
 * 默认全开；关闭某功能后需目标进程重启（下次注入读取）才生效（远端 prefs 语义）。
 */
object FeatureStore {

    private const val PREFS = "honorhook_features"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** 读开关（本地镜像；默认开）。 */
    fun isEnabled(key: String): Boolean = prefs?.getBoolean(key, true) ?: true

    /** 写开关：本地镜像 + 远端下发（框架未连接时仅本地，hook 侧保持默认开）。 */
    fun setEnabled(key: String, enabled: Boolean) {
        prefs?.edit()?.putBoolean(key, enabled)?.apply()
        try {
            FrameworkState.current()
                ?.getRemotePreferences(FeatureCatalog.REMOTE_GROUP)
                ?.edit()?.putBoolean(key, enabled)?.apply()
        } catch (_: Throwable) {
            // 框架未连接/写失败：静默，仅本地生效
        }
    }
}
