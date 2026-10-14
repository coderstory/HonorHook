package top.honorhook.ui

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.mutableStateMapOf
import top.honorhook.FeatureCatalog

/**
 * 功能开关存储（宿主侧）——本地镜像 + 远端下发双写（照 LSPFRIDA ScriptStore 通道模型）。
 *
 * - 本地 SharedPreferences：UI 即时读取显示（框架未连接也可用）；
 * - 远端 prefs（FeatureCatalog.REMOTE_GROUP）：hook 侧在目标进程只读此通道。
 * 默认关（2026-10-14 产品规则：全新安装全关）；开关改动需目标进程重启（下次注入读取）才生效（远端 prefs 语义）。
 */
object FeatureStore {

    private const val PREFS = "honorhook_features"

    private const val TAG = "HonorHook"

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 目录索引：key → Feature（默认值 / 互斥查表用；targets 声明后不可变，lazy 建一次）。 */
    private val featureIndex: Map<String, FeatureCatalog.Feature> by lazy {
        FeatureCatalog.targets.flatMap { it.features }.associateBy { it.key }
    }

    /**
     * 开关状态的 **Compose 可观察镜像**（[init] 时从本地 prefs 灌入）。
     *
     * 直接读 `SharedPreferences` 不是 snapshot state，改开关不会让读它的组合失效——
     * 「已启用 N/M」「待启用」这类计数就会一直停在旧值。所有读写都走这张表，
     * 一处改动可同时刷新二级开关、二级页计数与一级列表标签。
     */
    private val states = mutableStateMapOf<String, Boolean>()

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        FeatureCatalog.targets.forEach { target ->
            target.features.forEach { feature ->
                states[feature.key] =
                    prefs?.getBoolean(feature.key, feature.defaultEnabled) ?: feature.defaultEnabled
            }
        }
    }

    /** 读开关（Compose 可观察；默认关——2026-10-14 产品规则：全新安装全关）。 */
    fun isEnabled(key: String): Boolean =
        states[key] ?: featureIndex[key]?.defaultEnabled ?: false

    /**
     * 写开关：本地镜像 + 远端下发。
     *
     * @return 远端下发是否成功。`false` 表示框架未连接或写入抛异常 —— 此时 hook 侧读不到，
     *   仍按默认关运行，UI 必须如实告知用户（不能只改本地就当生效了）。
     */
    data class PushResult(val sent: Int, val total: Int, val connected: Boolean)

    /**
     * 强停前调用：远端 prefs 不随模块卸载清除，重装后本地被清空而远端残留旧值，
     * 会出现"界面显示开、hook 读到关"（SystemUI 曾因此静默失效）。
     */
    fun pushAllToRemote(): PushResult {
        val all = FeatureCatalog.targets.flatMap { it.features }
        val service = FrameworkState.current()
            ?: return PushResult(0, all.size, false)
        return runCatching {
            val editor = service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
            var sent = 0
            all.forEach { feature ->
                editor.putBoolean(feature.key, isEnabled(feature.key))
                sent++
            }
            editor.apply()
            PushResult(sent, all.size, true)
        }.getOrElse { t ->
            Log.w(TAG, "远端 prefs 批量下发失败（hook 侧仍按上次远端值运行）", t)
            PushResult(0, all.size, true)
        }
    }

    fun setEnabled(key: String, enabled: Boolean): Boolean {
        states[key] = enabled
        prefs?.edit()?.putBoolean(key, enabled)?.apply()
        // 互斥功能（2026-10-14 产品规则：清后台「直杀 / 管家广播」只能开一个）：开 A 强制关 B，关 A 不触发。
        // 互斥对方同样本地 + 远端双写；直接写 states/prefs（不递归调 setEnabled，避免对方再触发互斥成环）。
        // UI 各开关入口都经此处单点生效；对方行读 states（Compose 可观察镜像）→ 自动重组合刷新。
        val excludedNow = if (enabled) {
            featureIndex[key]?.excludesWith?.takeIf { it != key && isEnabled(it) }
        } else {
            null
        }
        if (excludedNow != null) {
            states[excludedNow] = false
            prefs?.edit()?.putBoolean(excludedNow, false)?.apply()
        }
        val service = FrameworkState.current() ?: run {
            Log.w(TAG, "LSPosed 未连接，开关只写入本地（hook 侧保持默认关）: $key")
            return false
        }
        return runCatching {
            val editor = service.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
            editor.putBoolean(key, enabled)
            excludedNow?.let { editor.putBoolean(it, false) }
            editor.apply()
            true
        }.getOrElse { t ->
            Log.w(TAG, "远端 prefs 下发失败（本地已生效，hook 侧保持默认关）: $key", t)
            false
        }
    }
}
