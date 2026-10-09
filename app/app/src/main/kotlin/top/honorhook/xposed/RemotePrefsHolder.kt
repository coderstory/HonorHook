package top.honorhook.xposed

import android.content.SharedPreferences
import android.util.Log
import io.github.libxposed.api.XposedModule
import top.honorhook.data.REMOTE_PREFERENCE_GROUP

/**
 * 远程 prefs 惰性获取 + 进程级缓存（修复「install 期一次性读取失败后 null 冻结」缺陷）。
 *
 * 旧行为（缺陷）：各 TargetHook 在 install() 里读一次 getRemotePreferences，失败时 null 被闭包
 * 捕获——懒加载重试与全部 intercept 永远复用同一个 null，进程重启前功能全静默失效
 * （与模块被禁用无法区分，installed N/M 日志却照常输出）。
 *
 * 新行为：obtain() 拿到非 null 后进程级缓存（LSPosedRemotePreferences 为 daemon 快照型，
 * 拿到即持续可用）；null 时**每次调用自动重读**（daemon/binder 瞬断自愈：懒加载重试轮
 * 与每次 intercept 都会再试，成功一次即冻结）。
 * 开销：null 时每次调用一次 binder 尝试——仅发生在「服务未就绪」窗口内，拿到后零开销。
 */
object RemotePrefsHolder {

    @Volatile
    private var cached: SharedPreferences? = null

    /** 取远程 prefs：缓存命中直返；未拿到过则现场重读（失败返回 null，下次调用自动再试）。 */
    fun obtain(module: XposedModule): SharedPreferences? {
        cached?.let { return it }
        val p = runCatching { module.getRemotePreferences(REMOTE_PREFERENCE_GROUP) }
            .onFailure { module.log(Log.WARN, TAG, "读取远程 prefs 失败（下次触发自动重试），开关暂时视为关", it) }
            .getOrNull()
        if (p != null) cached = p
        return p
    }

    private const val TAG = "HonorHook"
}
