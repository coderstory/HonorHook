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
 * 默认关（2026-10-14 产品规则：全新安装全关）：读不到（未连接/框架未下发/异常）一律回 false。
 * 与 UI 侧 FeatureStore 默认关对齐（2026-10-14 产品规则：全新安装全关）——
 * 开启某功能需宿主显式写入 true 后生效（下次目标进程注入时读取）。
 */
object RemoteConfig {

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * 由 HookEntry 在 onPackageLoaded 注入。
     * `getRemotePreferences` 是 XposedModule 的（protected）成员方法，只有模块子类能调，
     * 故由 HookEntry 读一次缓存后注入，各目标在 install() 阶段经 [isEnabled] 热路径查表（本地读，无重复 Binder）。
     */
    fun attach(prefs: SharedPreferences?) {
        this.prefs = prefs
    }

    /** 读功能开关；默认关（2026-10-14 产品规则：全新安装全关，与 UI 侧 FeatureStore 默认关对齐）。 */
    fun isEnabled(key: String): Boolean =
        runCatching { prefs?.getBoolean(key, false) ?: false }.getOrDefault(false)
}
