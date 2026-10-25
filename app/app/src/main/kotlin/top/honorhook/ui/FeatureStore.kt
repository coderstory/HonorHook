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
 * 默认随 FeatureCatalog（2026-10-15 产品规则：默认开，四个例外关）；开关改动需目标进程重启（下次注入读取）才生效（远端 prefs 语义）。
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

    /** 读开关（Compose 可观察；默认随 FeatureCatalog——2026-10-15 产品规则：默认开，四个例外关）。 */
    fun isEnabled(key: String): Boolean =
        states[key] ?: featureIndex[key]?.defaultEnabled ?: false

    /**
     * 单次开关写入的结果。失败两态**分立**（2026-10-26 T4 拍板）——UI 反馈按模式各归其位：
     * 未连接由 FeaturesScreen 常驻 banner 叙述（既有行为），写异常才做行内「未生效：下发失败」标注。
     */
    enum class SetEnabledResult { OK, NOT_CONNECTED, WRITE_FAILED }

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

    /**
     * 写开关：本地镜像 + 远端下发，事务本体在 [applySwitchWrite]。
     *
     * @return [SetEnabledResult]。失败两态的行为差异（T4 拍板）：
     * - [SetEnabledResult.NOT_CONNECTED]——本地写入**保留**（框架未激活既有行为不变，
     *   该场景由 FeaturesScreen 常驻 banner 叙述，UI 不另做行内标注）；
     * - [SetEnabledResult.WRITE_FAILED]——**整笔回滚**（含互斥对方）：states 与本地 prefs
     *   一并回到旧值，开关位置经 states 镜像自动弹回，如实反映 hook 侧仍是旧值。本地 prefs
     *   同步回滚防「视觉回滚了、prefs 还是新值」的漂移——否则下次 [pushAllToRemote] 会把
     *   从未生效的值当用户意图下发。
     */
    fun setEnabled(key: String, enabled: Boolean): SetEnabledResult {
        val service = FrameworkState.current()
        var writeError: Throwable? = null
        val result = applySwitchWrite(
            key, enabled,
            // null service = 未连接；lambda 仅在 service 非空时构造，事务内免空断言
            service?.let { svc ->
                { mutations: Map<String, Boolean> ->
                    val editor = svc.getRemotePreferences(FeatureCatalog.REMOTE_GROUP).edit()
                    mutations.forEach { (k, v) -> editor.putBoolean(k, v) }
                    editor.apply()
                }
            },
            states, prefs,
        ) { t -> writeError = t }
        when (result) {
            SetEnabledResult.OK -> Unit
            SetEnabledResult.NOT_CONNECTED ->
                Log.w(TAG, "LSPosed 未连接，开关只写入本地（hook 侧保持上次远端值）: $key")
            SetEnabledResult.WRITE_FAILED ->
                Log.w(TAG, "远端 prefs 下发失败（本地已回滚，hook 侧保持上次远端值）: $key", writeError)
        }
        return result
    }

    /**
     * 开关写入事务（JVM 可测缝，T4）：本地 states/prefs 双写 + 互斥联动 + 远端下发 + 失败回滚。
     * 从 [setEnabled] 抽出——FrameworkState（<clinit> 起 Handler/Looper）与 android.util.Log
     * 在 JVM 单测均不可加载，故远端写入以 [remoteWrite] 注入（null = 未连接），states/prefs
     * 也由调用方传入（生产传实参，测试注入 fakes）。
     *
     * 顺序判据：本地先写（视觉即时反馈）→ 远端失败再**整笔**回滚。回滚范围含互斥对方——
     * 互斥联动（开 A 强制关 B）与主写入同一 editor 原子下发，失败即同批作废；用户没碰过
     * 对方，视觉若停在「被联动关」即为谎报。
     */
    internal fun applySwitchWrite(
        key: String,
        enabled: Boolean,
        remoteWrite: ((Map<String, Boolean>) -> Unit)?,
        states: MutableMap<String, Boolean>,
        prefs: SharedPreferences?,
        onWriteError: (Throwable) -> Unit = {},
    ): SetEnabledResult {
        // 旧值先取（回滚依据）；valueIn 与 isEnabled 同语义——镜像缺项回目录默认
        val old = valueIn(states, key)
        // 互斥功能（2026-10-14 产品规则：清后台「直杀 / 管家广播」只能开一个）：开 A 强制关 B，关 A 不触发。
        // 互斥对方同样本地 + 远端双写；直接写 states/prefs（不递归调 setEnabled，避免对方再触发互斥成环）。
        // UI 各开关入口都经此处单点生效；对方行读 states（Compose 可观察镜像）→ 自动重组合刷新。
        val excluded = if (enabled) {
            featureIndex[key]?.excludesWith?.takeIf { it != key && valueIn(states, it) }
        } else {
            null
        }
        val oldExcluded = excluded != null && valueIn(states, excluded)
        states[key] = enabled
        prefs?.edit()?.putBoolean(key, enabled)?.apply()
        if (excluded != null) {
            states[excluded] = false
            prefs?.edit()?.putBoolean(excluded, false)?.apply()
        }
        if (remoteWrite == null) return SetEnabledResult.NOT_CONNECTED
        val mutations = buildMap {
            put(key, enabled)
            excluded?.let { put(it, false) }
        }
        return runCatching {
            remoteWrite(mutations)
            SetEnabledResult.OK
        }.getOrElse { t ->
            states[key] = old
            prefs?.edit()?.putBoolean(key, old)?.apply()
            if (excluded != null) {
                states[excluded] = oldExcluded
                prefs?.edit()?.putBoolean(excluded, oldExcluded)?.apply()
            }
            onWriteError(t)
            SetEnabledResult.WRITE_FAILED
        }
    }

    /** [states] 镜像中 key 的当前值；缺项回目录默认（与 [isEnabled] 同语义，事务内取回滚旧值用）。 */
    private fun valueIn(states: Map<String, Boolean>, key: String): Boolean =
        states[key] ?: featureIndex[key]?.defaultEnabled ?: false
}
