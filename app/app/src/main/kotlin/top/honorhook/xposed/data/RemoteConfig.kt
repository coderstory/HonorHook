package top.honorhook.xposed.data

import android.content.SharedPreferences
import top.honorhook.FeatureCatalog

/**
 * hook 侧功能开关读取器——「恒开」语义的开关化入口。
 *
 * 通道（照 LSPFRIDA ScriptStore 远端 prefs 单通道）：
 * - 宿主 App 经 `XposedService.getRemotePreferences(FeatureCatalog.REMOTE_GROUP)` 写开关；
 * - 本对象经 HookEntry 注入的 `getRemotePreferences` 只读，不依赖宿主进程存活。
 *
 * 默认随 FeatureCatalog 目录（2026-10-15 用户拍板：默认开，四个例外关 = hide_5g / hide_sim2 / 清后台 A/B 互斥对）：
 * 读不到（未连接/框架未下发/异常）一律回目录默认。与 UI 侧 FeatureStore 默认对齐——
 * 关某功能需宿主显式写入 false 后生效（下次目标进程注入时读取）。
 */
object RemoteConfig {

    @Volatile
    private var prefs: SharedPreferences? = null

    /** 目录索引：key → defaultEnabled（lazy 建一次；targets 声明后不可变）。 */
    private val catalogDefaults: Map<String, Boolean> by lazy {
        FeatureCatalog.targets.flatMap { it.features }.associate { it.key to it.defaultEnabled }
    }

    /**
     * 由 HookEntry 在 onPackageLoaded 注入。
     * `getRemotePreferences` 是 XposedModule 的（protected）成员方法，只有模块子类能调，
     * 故由 HookEntry 读一次缓存后注入，各目标在 install() 阶段经 [isEnabled] 热路径查表（本地读，无重复 Binder）。
     */
    fun attach(prefs: SharedPreferences?) {
        this.prefs = prefs
    }

    /** 读功能开关；默认随 FeatureCatalog 目录（2026-10-15 用户拍板：默认开，四个例外关，与 UI 侧 FeatureStore 对齐），读不到/异常回目录默认。 */
    fun isEnabled(key: String): Boolean {
        val fallback = catalogDefaults[key] ?: false
        return runCatching { prefs?.getBoolean(key, fallback) ?: fallback }.getOrDefault(fallback)
    }
}
