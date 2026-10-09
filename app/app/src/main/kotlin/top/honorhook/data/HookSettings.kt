package top.honorhook.data

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 远程偏好分组名（= LSPosed daemon SQLite configs 表的 group；本地回退 SP 用同名文件，照 HC REMOTE_PREFERENCE_GROUP）。 */
const val REMOTE_PREFERENCE_GROUP = "honorhook"

/** Hook 总开关 key。 */
const val KEY_HOOK_ENABLED = "hook_enabled"

/** 运行日志环形缓冲 key（hook 侧判据日志经 daemon 双写，UI 日志页读取/复制/清空；cap 200 行）。 */
const val KEY_LOG_RING = "log_ring"

/** F1-F4 功能位独立开关 key（默认 true；总开关为上位语义：总关 = 全部放行）。 */
const val KEY_F1_VIP_ENABLED = "f1_vip_enabled"
const val KEY_F2_FREE_ENABLED = "f2_free_enabled"
const val KEY_F3_TRIAL_ENABLED = "f3_trial_enabled"
const val KEY_F4_NO_RESET_ENABLED = "f4_no_reset_enabled"

/** F5 市场净化功能位开关 key（默认 false：改动面大，见 docs §5.2）。 */
const val KEY_F5_MARKET_ENABLED = "f5_market_enabled"

/** F6 安装器净化功能位开关 key（默认 false：安全敏感（跳过安全检测削弱恶意应用拦截），见 docs §5.2）。 */
const val KEY_F6_INSTALLER_PURIFY_ENABLED = "f6_installer_purify_enabled"

/** F7 液态玻璃恢复功能位开关 key（默认 false：boot-loop 事故史（v2.3：Class.forName 触发系统类 <clinit>），见 docs §5.2）。 */
const val KEY_F7_GLASS_ENABLED = "f7_glass_enabled"

/** 悬浮底栏开关 key（UI 侧专用，hook 侧不读；渲染切换 TODO，见 ThemeSettingsScreen）。 */
const val KEY_FLOATING_BOTTOM_BAR_ENABLED = "floating_bottom_bar_enabled"

/** 隐藏桌面图标开关 key（UI 侧专用；生效经 PackageManager.setComponentEnabledSetting，见 ThemeSettingsScreen）。 */
const val KEY_HIDE_LAUNCHER_ICON_ENABLED = "hide_launcher_icon_enabled"

/** 功能位开关默认值表（初始化与远程对齐的兜底真源；各 hook 组归位见 ThemeStoreHook 类注释）。 */
private val FEATURE_DEFAULTS = mapOf(
    KEY_F1_VIP_ENABLED to true,
    KEY_F2_FREE_ENABLED to true,
    KEY_F3_TRIAL_ENABLED to true,
    KEY_F4_NO_RESET_ENABLED to true,
    KEY_F5_MARKET_ENABLED to false,
    KEY_F6_INSTALLER_PURIFY_ENABLED to false,
    KEY_F7_GLASS_ENABLED to false,
)

/** UI 侧开关默认值表（悬浮底栏/隐藏桌面图标；默认全关）。 */
private val UI_TOGGLE_DEFAULTS = mapOf(
    KEY_FLOATING_BOTTOM_BAR_ENABLED to false,
    KEY_HIDE_LAUNCHER_ICON_ENABLED to false,
)

/**
 * Hook 配置存储（照 HC DockSettingsStore 双写：本地 SP + 远程 prefs）。
 * UI 进程：service 可用时双写（daemon 落库 → binder 推给 hook 进程）；断连时仅写本地并记脏键，
 * 绑定后 [syncRemote] 补推——脏键本地为权威，防 daemon 旧值回弹。hook 侧只读远程 prefs 并热监听（LSPosedRemotePreferences）。
 * [lock] 串行化 UI 写线程与 [syncRemote]（bind 回调线程），StateFlow 读改写不丢写。
 */
object HookSettings {

    @Volatile
    private var local: SharedPreferences? = null

    /** 互斥锁：保护 [pendingRemote] 与 StateFlow 读改写。 */
    private val lock = Any()

    /** 脏键集合（[lock] 保护）：本地已写、远程未同步成功的 key。 */
    private val pendingRemote = mutableSetOf<String>()

    private val _enabled = MutableStateFlow(false)
    /** 总开关当前值（只读 StateFlow）。 */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _features = MutableStateFlow(FEATURE_DEFAULTS)
    /** F1-F7 功能位开关当前值（只读 StateFlow）。 */
    val features: StateFlow<Map<String, Boolean>> = _features.asStateFlow()

    private val _uiToggles = MutableStateFlow(UI_TOGGLE_DEFAULTS)
    /** UI 侧开关（悬浮底栏/隐藏桌面图标）当前值（只读 StateFlow）。 */
    val uiToggles: StateFlow<Map<String, Boolean>> = _uiToggles.asStateFlow()

    /** 首次访问任何 StateFlow/写方法前调用（仅 HookApplication.onCreate）。 */
    fun init(context: Context) {
        synchronized(lock) {
            val p = context.applicationContext.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
            local = p
            _enabled.value = p.getBoolean(KEY_HOOK_ENABLED, false)
            // per-key 默认值读（F6 默认 false，不能统一 true 兜底）
            _features.value = FEATURE_DEFAULTS.mapValues { (key, default) -> p.getBoolean(key, default) }
            _uiToggles.value = UI_TOGGLE_DEFAULTS.mapValues { (key, _) -> p.getBoolean(key, false) }
        }
    }

    /**
     * 服务绑定后对齐一次（照 HC DockSettingsStore.syncRemote）：远程为权威，回写本地 + 远程；
     * [pendingRemote] 中的 key 以本地为权威且本次对齐跳过（防补推失败且 daemon 有旧值时被弹回），下次 bind 再试。
     */
    fun syncRemote(service: XposedService) {
        synchronized(lock) {
            val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
            // 脏键优先补推（本地为权威），成功才移除；失败留待下次 bind 再试
            pendingRemote.removeAll { key ->
                runCatching { remote.edit().putBoolean(key, localValueOf(key)).apply() }.isSuccess
            }
            // 对齐规则（总开关/功能位/UI 侧同）：远程为权威；daemon 缺 key 或 pendingRemote 中 → 保持本地当前值
            val value = when {
                KEY_HOOK_ENABLED in pendingRemote -> _enabled.value
                remote.contains(KEY_HOOK_ENABLED) -> remote.getBoolean(KEY_HOOK_ENABLED, false)
                else -> _enabled.value
            }
            writeBoth(value, remote)
            // 功能位同规则（读远程按 per-key 默认值兜底）
            _features.value = _features.value.mapValues { (key, current) ->
                when {
                    key in pendingRemote -> current
                    remote.contains(key) -> remote.getBoolean(key, FEATURE_DEFAULTS[key] ?: true)
                    else -> current
                }
            }
            _features.value.forEach { (key, v) ->
                local?.edit()?.putBoolean(key, v)?.apply()
                if (!remote.contains(key)) remote.edit().putBoolean(key, v).apply()
            }
            // UI 侧开关同规则
            _uiToggles.value = _uiToggles.value.mapValues { (key, current) ->
                when {
                    key in pendingRemote -> current
                    remote.contains(key) -> remote.getBoolean(key, false)
                    else -> current
                }
            }
            _uiToggles.value.forEach { (key, v) ->
                local?.edit()?.putBoolean(key, v)?.apply()
                if (!remote.contains(key)) remote.edit().putBoolean(key, v).apply()
            }
        }
    }

    /** 更新总开关（双写先本地后远程，照 HC DockSettingsStore.update；service 为 null 降级纯本地并记脏键）。 */
    fun setEnabled(enabled: Boolean, service: XposedService?) {
        synchronized(lock) {
            _enabled.value = enabled
            writeLocalAndRemote(KEY_HOOK_ENABLED, enabled, service)
        }
    }

    /** 更新功能位开关（F1-F7）：双写模式同 [setEnabled]。 */
    fun setFeatureEnabled(key: String, enabled: Boolean, service: XposedService?) {
        synchronized(lock) {
            _features.value = _features.value + (key to enabled)
            writeLocalAndRemote(key, enabled, service)
        }
    }

    /** 更新 UI 侧开关（悬浮底栏/隐藏桌面图标）：双写模式同 [setEnabled]。 */
    fun setUiToggle(key: String, enabled: Boolean, service: XposedService?) {
        synchronized(lock) {
            _uiToggles.value = _uiToggles.value + (key to enabled)
            writeLocalAndRemote(key, enabled, service)
        }
    }

    /** 双写实现：本地先写再推远程；service 为 null 或推送失败 → 记脏键（下次 [syncRemote] 以本地为权威补推）。 */
    private fun writeLocalAndRemote(key: String, enabled: Boolean, service: XposedService?) {
        local?.edit()?.putBoolean(key, enabled)?.apply()
        if (service == null) {
            pendingRemote += key
            return
        }
        val pushed = runCatching {
            service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
                .edit().putBoolean(key, enabled).apply()
        }
        if (pushed.isFailure) pendingRemote += key
    }

    /** 脏键的本地当前值（仅在 [lock] 内调用）。 */
    private fun localValueOf(key: String): Boolean = when (key) {
        KEY_HOOK_ENABLED -> _enabled.value
        else -> _features.value[key] ?: _uiToggles.value[key] ?: false
    }

    private fun writeBoth(value: Boolean, remote: SharedPreferences) {
        _enabled.value = value
        local?.edit()?.putBoolean(KEY_HOOK_ENABLED, value)?.apply()
        remote.edit().putBoolean(KEY_HOOK_ENABLED, value).apply()
    }
}