package top.honorhook.data

import android.content.Context
import android.content.SharedPreferences
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 远程偏好分组名（= LSPosed daemon SQLite configs 表的 group；本地回退 SP 用同名文件，照 HC REMOTE_PREFERENCE_GROUP）。 */
const val REMOTE_PREFERENCE_GROUP = "honorhook"

/** Hook 总开关 key（hook 进程 intercept 每次现读）。 */
const val KEY_HOOK_ENABLED = "hook_enabled"

/** F1-F4 功能位独立开关 key（默认 true；总开关为上位语义：总关 = 全部放行）。 */
const val KEY_F1_VIP_ENABLED = "f1_vip_enabled"
const val KEY_F2_FREE_ENABLED = "f2_free_enabled"
const val KEY_F3_TRIAL_ENABLED = "f3_trial_enabled"
const val KEY_F4_NO_RESET_ENABLED = "f4_no_reset_enabled"

/** F5 市场净化功能位开关 key（默认 false——改动面大（开屏/弹窗/推送/自启/推荐流），默认关，见 docs §5.2；开即按 MarketTamer 推荐配置整体生效）。 */
const val KEY_F5_MARKET_ENABLED = "f5_market_enabled"

/** F6 安装器净化功能位开关 key（默认 false——安全敏感：跳过安全检测削弱恶意应用拦截，默认关，见 docs §5.2）。 */
const val KEY_F6_INSTALLER_PURIFY_ENABLED = "f6_installer_purify_enabled"

/** F7 液态玻璃恢复功能位开关 key（默认 false——有 boot-loop 事故史（v2.3 事故：Class.forName 触发系统类 <clinit>），保守默认关，见 docs §5.2）。 */
const val KEY_F7_GLASS_ENABLED = "f7_glass_enabled"

/** 悬浮底栏开关 key（UI 侧持久化；渲染切换 TODO 后续实现，见 ThemeSettingsScreen）。hook 侧不读。 */
const val KEY_FLOATING_BOTTOM_BAR_ENABLED = "floating_bottom_bar_enabled"

/** 隐藏桌面图标开关 key（UI 侧持久化；实际生效经 PackageManager.setComponentEnabledSetting，见 ThemeSettingsScreen）。hook 侧不读。 */
const val KEY_HIDE_LAUNCHER_ICON_ENABLED = "hide_launcher_icon_enabled"

/** 功能位开关默认值表（初始化与远程对齐的兜底真源；各 hook 组归位见 ThemeStoreHook 类注释）。 */
private val FEATURE_DEFAULTS = mapOf(
    KEY_F1_VIP_ENABLED to true,
    KEY_F2_FREE_ENABLED to true,
    KEY_F3_TRIAL_ENABLED to true,
    KEY_F4_NO_RESET_ENABLED to true,
    KEY_F5_MARKET_ENABLED to false, // 改动面大（开屏/弹窗/推送/自启/推荐流），默认关
    KEY_F6_INSTALLER_PURIFY_ENABLED to false, // 安全敏感（跳过安全检测削弱恶意应用拦截），默认关
    KEY_F7_GLASS_ENABLED to false, // boot-loop 事故史（v2.3：Class.forName 触发系统类初始化），保守默认关
)

/** UI 侧开关默认值表（悬浮底栏/隐藏桌面图标；默认全关，hook 侧不读这两个 key）。 */
private val UI_TOGGLE_DEFAULTS = mapOf(
    KEY_FLOATING_BOTTOM_BAR_ENABLED to false,
    KEY_HIDE_LAUNCHER_ICON_ENABLED to false,
)

/**
 * Hook 配置存储（照 HC DockSettingsStore 双写模式 + 本项目 ThemeModeStore object 风格）。
 *
 * UI 侧：XposedService 可用 → 双写（本地 SP + 远程 prefs；远程经
 * LSPosed daemon SQLite 落库 → binder 推送到所有 hook 进程）；
 * XposedService 不可用 → 仅写本地 SP（UI 照常可切，只是暂不推送）。
 * hook 侧（xposed 进程）：只读远程 prefs + registerOnSharedPreferenceChangeListener 热监听（LSPosedRemotePreferences）。
 */
object HookSettings {

    @Volatile
    private var local: SharedPreferences? = null

    private val _enabled = MutableStateFlow(false)
    /** 总开关当前值（只读 StateFlow；修改只有 [setEnabled]，与 [syncRemote] 同步覆盖）。 */
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _features = MutableStateFlow(FEATURE_DEFAULTS)
    /** F1-F7 功能位开关当前值（只读 StateFlow；修改只有 [setFeatureEnabled]，与 [syncRemote] 同步覆盖）。 */
    val features: StateFlow<Map<String, Boolean>> = _features.asStateFlow()

    private val _uiToggles = MutableStateFlow(UI_TOGGLE_DEFAULTS)
    /** UI 侧开关（悬浮底栏/隐藏桌面图标）当前值（只读 StateFlow；修改只有 [setUiToggle]，与 [syncRemote] 同步覆盖）。 */
    val uiToggles: StateFlow<Map<String, Boolean>> = _uiToggles.asStateFlow()

    /** 必须在首次访问 [enabled]/[features]/[uiToggles]/[setEnabled]/[setFeatureEnabled]/[setUiToggle] 前调用（HookApplication.onCreate）。 */
    fun init(context: Context) {
        val p = context.applicationContext.getSharedPreferences(REMOTE_PREFERENCE_GROUP, Context.MODE_PRIVATE)
        local = p
        _enabled.value = p.getBoolean(KEY_HOOK_ENABLED, false)
        // 各功能位按 FEATURE_DEFAULTS 的 per-key 默认值读（F6 默认 false，不能统一用 true 兜底）
        _features.value = FEATURE_DEFAULTS.mapValues { (key, default) -> p.getBoolean(key, default) }
        _uiToggles.value = UI_TOGGLE_DEFAULTS.mapValues { (key, _) -> p.getBoolean(key, false) }
    }

    /**
     * 服务绑定后对齐一次：远程为权威（daemon 数据存在则采用），回写本地 + 远程（照 HC DockSettingsStore.syncRemote）。
     * 使 UI 显示与 daemon 持久化数据一致（可能已被其他进程/历史状态写入）。F1-F4 功能位同规则对齐。
     */
    fun syncRemote(service: XposedService) {
        val remote = service.getRemotePreferences(REMOTE_PREFERENCE_GROUP)
        val value = if (remote.contains(KEY_HOOK_ENABLED)) {
            remote.getBoolean(KEY_HOOK_ENABLED, false)
        } else {
            _enabled.value
        }
        writeBoth(value, remote)
        // 功能位：远程为权威；daemon 缺失的 key 补写本地当前值（与总开关 writeBoth 语义一致）；
        // 读远程用 per-key 默认值（F6 默认 false 不能统一用 true 兜底）
        _features.value = _features.value.mapValues { (key, current) ->
            if (remote.contains(key)) remote.getBoolean(key, FEATURE_DEFAULTS[key] ?: true) else current
        }
        _features.value.forEach { (key, v) ->
            local?.edit()?.putBoolean(key, v)?.apply()
            if (!remote.contains(key)) remote.edit().putBoolean(key, v).apply()
        }
        // UI 侧开关同规则对齐（远程为权威；daemon 缺失的 key 补写本地当前值）
        _uiToggles.value = _uiToggles.value.mapValues { (key, current) ->
            if (remote.contains(key)) remote.getBoolean(key, false) else current
        }
        _uiToggles.value.forEach { (key, v) ->
            local?.edit()?.putBoolean(key, v)?.apply()
            if (!remote.contains(key)) remote.edit().putBoolean(key, v).apply()
        }
    }

    /**
     * 更新总开关：先写本地（无 service 也能存，UI 自洽），再推远程（照 HC DockSettingsStore.update 双写）。
     * service 为 null 时降级为纯本地（UI 仍可切，框架恢复后下一次 [syncRemote] 对齐）。
     */
    fun setEnabled(enabled: Boolean, service: XposedService?) {
        _enabled.value = enabled
        local?.edit()?.putBoolean(KEY_HOOK_ENABLED, enabled)?.apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { remote ->
            remote.edit().putBoolean(KEY_HOOK_ENABLED, enabled).apply()
        }
    }

    /**
     * 更新功能位开关（F1-F7）：双写模式同 [setEnabled]（先写本地，service 为 null 降级为纯本地）。
     * hook 侧 intercept 每次现读各自 feature key（总开关仍是上位语义：总关 = 全部放行）。
     */
    fun setFeatureEnabled(key: String, enabled: Boolean, service: XposedService?) {
        _features.value = _features.value + (key to enabled)
        local?.edit()?.putBoolean(key, enabled)?.apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { remote ->
            remote.edit().putBoolean(key, enabled).apply()
        }
    }

    /**
     * 更新 UI 侧开关（悬浮底栏/隐藏桌面图标）：双写模式同 [setFeatureEnabled]。
     * hook 侧不读这些 key——纯 UI 态持久化（隐藏桌面图标另经 PackageManager.setComponentEnabledSetting 实际生效，
     * 见 ThemeSettingsScreen.applyIconHidden）。
     */
    fun setUiToggle(key: String, enabled: Boolean, service: XposedService?) {
        _uiToggles.value = _uiToggles.value + (key to enabled)
        local?.edit()?.putBoolean(key, enabled)?.apply()
        service?.getRemotePreferences(REMOTE_PREFERENCE_GROUP)?.let { remote ->
            remote.edit().putBoolean(key, enabled).apply()
        }
    }

    private fun writeBoth(value: Boolean, remote: SharedPreferences) {
        _enabled.value = value
        local?.edit()?.putBoolean(KEY_HOOK_ENABLED, value)?.apply()
        remote.edit().putBoolean(KEY_HOOK_ENABLED, value).apply()
    }
}